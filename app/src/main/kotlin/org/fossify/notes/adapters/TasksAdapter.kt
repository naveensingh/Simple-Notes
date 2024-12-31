package org.fossify.notes.adapters

import android.annotation.SuppressLint
import android.graphics.Paint
import android.util.TypedValue
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.adapters.MyRecyclerViewListAdapter
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.removeBit
import org.fossify.commons.interfaces.ItemMoveCallback
import org.fossify.commons.interfaces.ItemTouchHelperContract
import org.fossify.commons.interfaces.StartReorderDragListener
import org.fossify.commons.views.MyRecyclerView
import org.fossify.notes.R
import org.fossify.notes.databinding.ItemCheckedTasksBinding
import org.fossify.notes.databinding.ItemChecklistBinding
import org.fossify.notes.extensions.config
import org.fossify.notes.extensions.getPercentageFontSize
import org.fossify.notes.helpers.DONE_CHECKLIST_ITEM_ALPHA
import org.fossify.notes.interfaces.TasksActionListener
import org.fossify.notes.models.CompletedTasks
import org.fossify.notes.models.NoteItem
import org.fossify.notes.models.Task

private const val TYPE_TASK = 0
private const val TYPE_COMPLETED_TASKS = 1

class TasksAdapter(
    activity: BaseSimpleActivity,
    val listener: TasksActionListener?,
    recyclerView: MyRecyclerView,
    val noteId: Long,
    itemClick: (Any) -> Unit = {},
) : MyRecyclerViewListAdapter<NoteItem>(
    activity = activity, recyclerView = recyclerView, diffUtil = TaskDiffCallback(), itemClick = itemClick
), ItemTouchHelperContract {

    private var touchHelper: ItemTouchHelper? = null
    private var startReorderDragListener: StartReorderDragListener

    init {
        setupDragListener(true)
        setHasStableIds(true)

        touchHelper = ItemTouchHelper(ItemMoveCallback(this))
        touchHelper!!.attachToRecyclerView(recyclerView)

        startReorderDragListener = object : StartReorderDragListener {
            override fun requestDrag(viewHolder: RecyclerView.ViewHolder) {
                touchHelper?.startDrag(viewHolder)
            }
        }
    }

    override fun getActionMenuId() = R.menu.cab_checklist

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_move_to_top -> moveSelectedItemsToTop()
            R.id.cab_move_to_bottom -> moveSelectedItemsToBottom()
            R.id.cab_rename -> renameTask()
            R.id.cab_delete -> deleteSelection()
        }
    }

    override fun getItemId(position: Int) = getItemKey(getItem(position)).toLong()

    override fun getSelectableItemCount() = currentList.filterIsInstance<Task>().size

    override fun getIsItemSelectable(position: Int) = getItem(position) is Task

    override fun getItemSelectionKey(position: Int) = getItemKey(getItem(position))

    override fun getItemKeyPosition(key: Int) = currentList.indexOfFirst { getItemKey(it) == key }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeCreated() = notifyDataSetChanged()

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeDestroyed() = notifyDataSetChanged()

    override fun prepareActionMode(menu: Menu) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) {
            return
        }

        menu.findItem(R.id.cab_rename).isVisible = isOneItemSelected()
        menu.findItem(R.id.cab_move_to_top).isVisible = selectedItems.none { it.isDone }
            || !activity.config.getMoveDoneChecklistItems(noteId)
        menu.findItem(R.id.cab_move_to_bottom).isVisible = selectedItems.none { it.isDone }
            || !activity.config.getMoveDoneChecklistItems(noteId)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is Task -> TYPE_TASK
            is CompletedTasks -> TYPE_COMPLETED_TASKS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(
            when (viewType) {
                TYPE_TASK -> ItemChecklistBinding.inflate(layoutInflater, parent, false).root
                TYPE_COMPLETED_TASKS -> ItemCheckedTasksBinding.inflate(layoutInflater, parent, false).root
                else -> throw IllegalArgumentException("Unsupported view type: $viewType")
            }
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bindView(item, allowSingleClick = true, allowLongClick = true) { itemView, _ ->
            when (item) {
                is Task -> setupView(itemView, item, holder)
                is CompletedTasks -> setupCompletedTasks(itemView, item)
            }
        }

        bindViewHolder(holder)
    }

    private fun renameTask() {
        listener?.editTask(task = getSelectedItems().first()) {
            finishActMode()
        }
    }

    private fun deleteSelection() {
        listener?.deleteTasks(getSelectedItems())
        finishActMode()
    }

    private fun moveSelectedItemsToTop() {
        listener?.moveTasksToTop(taskIds = getSelectedItems().map { it.id })
    }

    private fun moveSelectedItemsToBottom() {
        listener?.moveTasksToBottom(taskIds = getSelectedItems().map { it.id })
    }

    private fun getSelectedItems() = currentList.filterIsInstance<Task>().filter { selectedKeys.contains(it.id) }.toMutableList()

    @SuppressLint("ClickableViewAccessibility")
    private fun setupView(view: View, task: Task, holder: ViewHolder) {
        val isSelected = selectedKeys.contains(task.id)
        ItemChecklistBinding.bind(view).apply {
            checklistTitle.apply {
                text = task.title
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getPercentageFontSize())
                gravity = context.config.getTextGravity()

                if (task.isDone) {
                    paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    alpha = DONE_CHECKLIST_ITEM_ALPHA
                } else {
                    paintFlags = paintFlags.removeBit(Paint.STRIKE_THRU_TEXT_FLAG)
                    alpha = 1f
                }
            }

            checklistCheckbox.isChecked = task.isDone
            checklistHolder.isSelected = isSelected

            val canMoveTask = !task.isDone || !activity.config.getMoveDoneChecklistItems(noteId)
            checklistDragHandle.beVisibleIf(beVisible = canMoveTask && selectedKeys.isNotEmpty())
            checklistDragHandle.applyColorFilter(textColor)
            checklistDragHandle.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    startReorderDragListener.requestDrag(holder)
                }
                false
            }
        }
    }

    private fun setupCompletedTasks(view: View, completedTasks: CompletedTasks) {
        ItemCheckedTasksBinding.bind(view).apply {
            numCheckedItems.apply {
                text = resources.getQuantityString(R.plurals.num_checked_items, completedTasks.tasks.size, completedTasks.tasks.size)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getPercentageFontSize())
            }

            expandCollapseIcon.applyColorFilter(textColor)
            expandCollapseIcon.setImageResource(
                if (completedTasks.expanded) {
                    org.fossify.commons.R.drawable.ic_chevron_up_vector
                } else {
                    org.fossify.commons.R.drawable.ic_chevron_down_vector
                }
            )
        }
    }

    override fun onRowMoved(fromPosition: Int, toPosition: Int) {
        listener?.moveTask(fromPosition, toPosition)
    }

    override fun onRowSelected(myViewHolder: MyRecyclerViewAdapter.ViewHolder?) {}

    override fun onRowClear(myViewHolder: MyRecyclerViewAdapter.ViewHolder?) {
        listener?.saveAndReload()
    }

    private fun getItemKey(item: NoteItem) = when (item) {
        is Task -> item.id
        is CompletedTasks -> item.id
    }
}

private class TaskDiffCallback : DiffUtil.ItemCallback<NoteItem>() {
    override fun areItemsTheSame(oldItem: NoteItem, newItem: NoteItem): Boolean {
        return if (oldItem is Task && newItem is Task) {
            return oldItem.id == newItem.id
        } else {
            true
        }
    }

    override fun areContentsTheSame(oldItem: NoteItem, newItem: NoteItem) = oldItem == newItem
}
