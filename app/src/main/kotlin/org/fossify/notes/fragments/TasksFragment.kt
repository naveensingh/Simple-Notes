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
import org.fossify.notes.adapters.TasksAdapter
import org.fossify.notes.databinding.FragmentChecklistBinding
import org.fossify.notes.dialogs.EditTaskDialog
import org.fossify.notes.dialogs.NewChecklistItemDialog
import org.fossify.notes.extensions.config
import org.fossify.notes.extensions.updateWidgets
import org.fossify.notes.helpers.NOTE_ID
import org.fossify.notes.helpers.NotesHelper
import org.fossify.notes.interfaces.TasksActionListener
import org.fossify.notes.models.CompletedTasks
import org.fossify.notes.models.Note
import org.fossify.notes.models.NoteItem
import org.fossify.notes.models.Task
import java.io.File

class TasksFragment : NoteFragment(), TasksActionListener {

    private var noteId = 0L
    private var expanded = false

    private lateinit var binding: FragmentChecklistBinding

    var tasks = mutableListOf<Task>()

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
            (binding.checklistList.adapter as? TasksAdapter)?.finishActMode()
        }
    }

    private fun loadNoteById(noteId: Long) {
        NotesHelper(requireActivity()).getNoteWithId(noteId) { storedNote ->
            if (storedNote != null && activity?.isDestroyed == false) {
                note = storedNote

                try {
                    val taskType = object : TypeToken<List<Task>>() {}.type
                    tasks = Gson().fromJson<ArrayList<Task>>(storedNote.getNoteStoredValue(requireActivity()), taskType) ?: ArrayList(1)
                    tasks = tasks.toMutableList() as ArrayList<Task>
                    setupFragment()
                } catch (e: Exception) {
                    migrateCheckListOnFailure(storedNote)
                }
            }
        }
    }

    private fun migrateCheckListOnFailure(note: Note) {
        tasks.clear()

        note.getNoteStoredValue(requireActivity())?.split("\n")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEachIndexed { index, value ->
            tasks.add(
                Task(
                    id = index,
                    title = value,
                    isDone = false
                )
            )
        }

        saveAndReload()
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
                (binding.checklistList.adapter as? TasksAdapter)?.finishActMode()
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
        NewChecklistItemDialog(activity as SimpleActivity, noteId) { titles ->
            var currentMaxId = tasks.maxByOrNull { item -> item.id }?.id ?: 0
            val newItems = ArrayList<Task>()

            titles.forEach { title ->
                title.split("\n").map { it.trim() }.filter { it.isNotBlank() }.forEach { row ->
                    newItems.add(Task(currentMaxId + 1, System.currentTimeMillis(), row, false))
                    currentMaxId++
                }
            }

            if (config?.addNewChecklistItemsTop == true) {
                tasks.addAll(0, newItems)
            } else {
                tasks.addAll(newItems)
            }

            saveNote()
            setupAdapter()
        }
    }

    private fun prepareTaskItems(): List<NoteItem> {
        return if (config?.getMoveDoneChecklistItems(noteId) == true) {
            mutableListOf<NoteItem>().apply {
                val (checked, unchecked) = tasks.partition { it.isDone }
                this += unchecked
                if (checked.isNotEmpty()) {
                    if (unchecked.isEmpty()) {
                        expanded = true
                    }

                    this += CompletedTasks(tasks = checked, expanded = expanded)
                    if (expanded) {
                        this += checked
                    }
                } else {
                    expanded = false
                }
            }
        } else {
            tasks.toList()
        }
    }

    private fun getTasksAdapter(): TasksAdapter {
        var adapter = binding.checklistList.adapter as? TasksAdapter
        if (adapter == null) {
            adapter = TasksAdapter(
                activity = activity as SimpleActivity,
                listener = this,
                recyclerView = binding.checklistList,
                noteId = noteId,
                itemClick = ::itemClicked
            )
            binding.checklistList.adapter = adapter
        }

        return adapter
    }

    private fun setupAdapter() {
        updateUIVisibility()
        Task.sorting = requireContext().config.getSorting(noteId)
        if (Task.sorting and SORT_BY_CUSTOM == 0) {
            tasks.sort()
        }

        getTasksAdapter().submitList(prepareTaskItems())
    }

    private fun itemClicked(item: Any) {
        when (item) {
            is Task -> {
                val index = tasks.indexOf(item)
                if (index != -1) {
                    tasks[index] = item.copy(isDone = !item.isDone)
                    saveAndReload()
                }
            }

            is CompletedTasks -> {
                expanded = !expanded
                setupAdapter()
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
            note!!.value = getTasks()

            ensureBackgroundThread {
                saveNoteValue(note!!, note!!.value)
                context?.updateWidgets()
                activity?.runOnUiThread(callback)
            }
        }
    }

    fun removeCheckedItems() {
        tasks = tasks.filter { !it.isDone }.toMutableList()
        saveNote()
        setupAdapter()
    }

    private fun updateUIVisibility() {
        binding.apply {
            fragmentPlaceholder.beVisibleIf(tasks.isEmpty())
            fragmentPlaceholder2.beVisibleIf(tasks.isEmpty())
            checklistList.beVisibleIf(tasks.isNotEmpty())
        }
    }

    fun getTasks() = Gson().toJson(tasks)

    override fun editTask(task: Task, callback: () -> Unit) {
        EditTaskDialog(activity as SimpleActivity, task.title) { title ->
            val editedTask = task.copy(title = title)
            val index = tasks.indexOf(task)
            tasks[index] = editedTask
            saveAndReload()
            callback()
        }
    }

    override fun deleteTasks(tasksToDelete: List<Task>) {
        tasks.removeAll(tasksToDelete)
        saveAndReload()
    }

    override fun moveTask(fromPosition: Int, toPosition: Int) {
        activity?.config?.sorting = SORT_BY_CUSTOM
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                tasks.swap(i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                tasks.swap(i, i - 1)
            }
        }

        saveNote()
        setupAdapter()
    }

    override fun moveTasksToTop(taskIds: List<Int>) = moveTasks(taskIds.reversed(), targetPosition = 0)

    override fun moveTasksToBottom(taskIds: List<Int>) = moveTasks(taskIds, targetPosition = tasks.lastIndex)

    private fun moveTasks(taskIds: List<Int>, targetPosition: Int) {
        activity?.config?.sorting = SORT_BY_CUSTOM
        taskIds.forEach { id ->
            val position = tasks.indexOfFirst { it.id == id }
            if (position != -1) {
                tasks.move(position, targetPosition)
            }
        }

        saveAndReload()
    }

    override fun saveAndReload() {
        saveNote {
            loadNoteById(noteId)
        }
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
