package org.fossify.notes.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.notes.activities.SimpleActivity
import org.fossify.notes.adapters.ChecklistAdapter
import org.fossify.notes.databinding.FragmentChecklistBinding
import org.fossify.notes.dialogs.NewChecklistItemDialog
import org.fossify.notes.extensions.config
import org.fossify.notes.extensions.updateWidgets
import org.fossify.notes.helpers.NOTE_ID
import org.fossify.notes.helpers.NotesHelper
import org.fossify.notes.interfaces.ChecklistItemsListener
import org.fossify.notes.models.ChecklistItem
import org.fossify.notes.models.Note
import java.io.File

class ChecklistFragment : NoteFragment(), ChecklistItemsListener {

    private var noteId = 0L

    private lateinit var binding: FragmentChecklistBinding

    var items = mutableListOf<ChecklistItem>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentChecklistBinding.inflate(inflater, container, false)
        noteId = requireArguments().getLong(NOTE_ID, 0L)
        setupFragmentColors()
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadNoteById(noteId)
    }

    override fun setMenuVisibility(menuVisible: Boolean) {
        super.setMenuVisibility(menuVisible)

        if (menuVisible) {
            activity?.hideKeyboard()
        } else if (::binding.isInitialized) {
            (binding.checklistList.adapter as? ChecklistAdapter)?.finishActMode()
        }
    }

    private fun loadNoteById(noteId: Long) {
        NotesHelper(requireActivity()).getNoteWithId(noteId) { storedNote ->
            if (storedNote != null && activity?.isDestroyed == false) {
                note = storedNote

                try {
                    val checklistItemType = object : TypeToken<List<ChecklistItem>>() {}.type
                    items = Gson().fromJson<ArrayList<ChecklistItem>>(storedNote.getNoteStoredValue(requireActivity()), checklistItemType) ?: ArrayList(1)

                    items = items.toMutableList() as ArrayList<ChecklistItem>
                    val sorting = config?.sorting ?: 0
                    if (sorting and SORT_BY_CUSTOM == 0 && config?.moveDoneChecklistItems == true) {
                        items.sortBy { it.isDone }
                    }

                    setupFragment()
                } catch (e: Exception) {
                    migrateCheckListOnFailure(storedNote)
                }
            }
        }
    }

    private fun migrateCheckListOnFailure(note: Note) {
        items.clear()

        note.getNoteStoredValue(requireActivity())?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEachIndexed { index, value ->
            items.add(
                ChecklistItem(
                    id = index,
                    title = value,
                    isDone = false
                )
            )
        }

        saveChecklist(items)
    }

    private fun setupFragment() {
        if (activity == null || requireActivity().isFinishing) {
            return
        }

        setupFragmentColors()
        checkLockState()
        setupAdapter()
    }

    private fun setupFragmentColors() {
        val adjustedPrimaryColor = requireActivity().getProperPrimaryColor()
        binding.checklistFab.apply {
            setColors(
                requireActivity().getProperTextColor(),
                adjustedPrimaryColor,
                adjustedPrimaryColor.getContrastColor()
            )

            setOnClickListener {
                showNewItemDialog()
                (binding.checklistList.adapter as? ChecklistAdapter)?.finishActMode()
            }
        }

        binding.fragmentPlaceholder.setTextColor(requireActivity().getProperTextColor())
        binding.fragmentPlaceholder2.apply {
            setTextColor(adjustedPrimaryColor)
            underlineText()
            setOnClickListener {
                showNewItemDialog()
            }
        }
    }

    override fun checkLockState() {
        if (note == null) {
            return
        }

        binding.apply {
            checklistContentHolder.beVisibleIf(!note!!.isLocked() || shouldShowLockedContent)
            checklistFab.beVisibleIf(!note!!.isLocked() || shouldShowLockedContent)
            setupLockedViews(this.toCommonBinding(), note!!)
        }
    }

    private fun showNewItemDialog() {
        NewChecklistItemDialog(activity as SimpleActivity) { titles ->
            var currentMaxId = items.maxByOrNull { item -> item.id }?.id ?: 0
            val newItems = ArrayList<ChecklistItem>()

            titles.forEach { title ->
                title.split("\n").map { it.trim() }.filter { it.isNotBlank() }.forEach { row ->
                    newItems.add(ChecklistItem(currentMaxId + 1, System.currentTimeMillis(), row, false))
                    currentMaxId++
                }
            }

            if (config?.addNewChecklistItemsTop == true) {
                items.addAll(0, newItems)
            } else {
                items.addAll(newItems)
            }

            saveNote()
            setupAdapter()
        }
    }

    private fun setupAdapter() {
        updateUIVisibility()
        ChecklistItem.sorting = requireContext().config.sorting
        if (ChecklistItem.sorting and SORT_BY_CUSTOM == 0) {
            items.sort()
            if (context?.config?.moveDoneChecklistItems == true) {
                items.sortBy { it.isDone }
            }
        }

        var checklistAdapter = binding.checklistList.adapter as? ChecklistAdapter
        if (checklistAdapter == null) {
            checklistAdapter = ChecklistAdapter(
                activity = activity as SimpleActivity,
                listener = this,
                recyclerView = binding.checklistList,
                itemClick = ::toggleCompletion
            )
            binding.checklistList.adapter = checklistAdapter
        }

        checklistAdapter.submitList(items.toList())
    }

    private fun toggleCompletion(any: Any) {
        val item = any as ChecklistItem
        val index = items.indexOf(item)
        if (index != -1) {
            items[index] = item.copy(isDone = !item.isDone)
            saveNote {
                loadNoteById(noteId)
            }
        }
    }

    private fun saveNote(callback: () -> Unit = {}) {
        if (note == null) {
            return
        }

        if (note!!.path.isNotEmpty() && !note!!.path.startsWith("content://") && !File(note!!.path).exists()) {
            return
        }

        if (context == null || activity == null) {
            return
        }

        if (note != null) {
            note!!.value = getChecklistItems()

            ensureBackgroundThread {
                saveNoteValue(note!!, note!!.value)
                context?.updateWidgets()
                activity?.runOnUiThread(callback)
            }
        }
    }

    fun removeDoneItems() {
        items = items.filter { !it.isDone }.toMutableList() as ArrayList<ChecklistItem>
        saveNote()
        setupAdapter()
    }

    private fun updateUIVisibility() {
        binding.apply {
            fragmentPlaceholder.beVisibleIf(items.isEmpty())
            fragmentPlaceholder2.beVisibleIf(items.isEmpty())
            checklistList.beVisibleIf(items.isNotEmpty())
        }
    }

    fun getChecklistItems() = Gson().toJson(items)

    override fun saveChecklist(updatedItems: List<ChecklistItem>, callback: () -> Unit) {
        items = updatedItems.toMutableList()
        saveNote(callback = callback)
    }

    override fun refreshItems() {
        loadNoteById(noteId)
        setupAdapter()
    }

    private fun FragmentChecklistBinding.toCommonBinding(): CommonNoteBinding = this.let {
        object : CommonNoteBinding {
            override val root: View = it.root
            override val noteLockedLayout: View = it.noteLockedLayout
            override val noteLockedImage: ImageView = it.noteLockedImage
            override val noteLockedLabel: TextView = it.noteLockedLabel
            override val noteLockedShow: TextView = it.noteLockedShow
        }
    }
}
