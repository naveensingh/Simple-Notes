package org.fossify.notes.dialogs

import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.helpers.SORT_BY_DATE_CREATED
import org.fossify.commons.helpers.SORT_BY_TITLE
import org.fossify.commons.helpers.SORT_DESCENDING
import org.fossify.notes.R
import org.fossify.notes.activities.SimpleActivity
import org.fossify.notes.databinding.DialogSortChecklistBinding
import org.fossify.notes.extensions.config
import org.fossify.notes.helpers.SORT_MOVE_DONE_ITEMS

class SortChecklistDialog(
    private val activity: SimpleActivity,
    private val noteId: Long?,
    private val callback: () -> Unit
) {
    private val binding = DialogSortChecklistBinding.inflate(activity.layoutInflater)
    private val view = binding.root
    private val config = activity.config
    private var currSorting = config.getSorting(noteId)

    init {
        setupSortRadio()
        setupOrderRadio()
        setupMoveUndoneChecklistItems()

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ -> dialogConfirmed() }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(view, this, org.fossify.commons.R.string.sort_by)
            }
    }

    private fun setupSortRadio() {
        val fieldRadio = binding.sortingDialogRadioSorting
        fieldRadio.setOnCheckedChangeListener { _, checkedId ->
            val isCustomSorting = checkedId == binding.sortingDialogRadioCustom.id
            binding.sortingDialogRadioOrder.beGoneIf(isCustomSorting)
            binding.sortingDialogOrderDivider.beGoneIf(isCustomSorting)
        }

        var fieldBtn = binding.sortingDialogRadioTitle

        if (currSorting and SORT_BY_DATE_CREATED != 0) {
            fieldBtn = binding.sortingDialogRadioDateCreated
        }

        if (currSorting and SORT_BY_CUSTOM != 0) {
            fieldBtn = binding.sortingDialogRadioCustom
        }

        if (noteId == null) {
            binding.sortingDialogUseForThisChecklist.beGone()
        } else {
            binding.sortingDialogUseForThisChecklist.isChecked = config.hasOwnSorting(noteId)
        }

        fieldBtn.isChecked = true
    }

    private fun setupOrderRadio() {
        var orderBtn = binding.sortingDialogRadioAscending

        if (currSorting and SORT_DESCENDING != 0) {
            orderBtn = binding.sortingDialogRadioDescending
        }

        orderBtn.isChecked = true
    }

    private fun setupMoveUndoneChecklistItems() {
        binding.settingsMoveUndoneChecklistItems.isChecked = config.getMoveDoneChecklistItems(noteId)
        binding.settingsMoveUndoneChecklistItemsHolder.setOnClickListener {
            binding.settingsMoveUndoneChecklistItems.toggle()
        }
    }

    private fun dialogConfirmed() {
        val sortingRadio = binding.sortingDialogRadioSorting
        var sorting = when (sortingRadio.checkedRadioButtonId) {
            R.id.sorting_dialog_radio_date_created -> SORT_BY_DATE_CREATED
            R.id.sorting_dialog_radio_custom -> SORT_BY_CUSTOM
            else -> SORT_BY_TITLE
        }

        if (sortingRadio.checkedRadioButtonId != R.id.sorting_dialog_radio_custom
            && binding.sortingDialogRadioOrder.checkedRadioButtonId == R.id.sorting_dialog_radio_descending
        ) {
            sorting = sorting or SORT_DESCENDING
        }

        if (binding.settingsMoveUndoneChecklistItems.isChecked) {
            sorting = sorting or SORT_MOVE_DONE_ITEMS
        }

        if (binding.sortingDialogUseForThisChecklist.isChecked) {
            config.saveOwnSorting(noteId!!, sorting)
        } else {
            if (noteId != null) {
                config.removeOwnSorting(noteId)
            }
            config.sorting = sorting
        }

        callback()
    }
}
