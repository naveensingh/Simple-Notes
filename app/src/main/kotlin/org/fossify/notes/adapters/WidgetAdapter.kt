package org.fossify.notes.adapters

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.serialization.json.Json
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.setText
import org.fossify.commons.extensions.setTextSize
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.helpers.WIDGET_TEXT_COLOR
import org.fossify.notes.R
import org.fossify.notes.R.id.widget_text_holder
import org.fossify.notes.extensions.config
import org.fossify.notes.extensions.getPercentageFontSize
import org.fossify.notes.extensions.notesDB
import org.fossify.notes.helpers.*
import org.fossify.notes.models.Note
import org.fossify.notes.models.NoteType
import org.fossify.notes.models.Task

class WidgetAdapter(val context: Context, val intent: Intent) : RemoteViewsService.RemoteViewsFactory {
    private val textIds = arrayOf(
        R.id.widget_text_left, R.id.widget_text_center, R.id.widget_text_right,
        R.id.widget_text_left_monospace, R.id.widget_text_center_monospace, R.id.widget_text_right_monospace
    )
    private val checklistIds = arrayOf(
        R.id.checklist_text_left, R.id.checklist_text_center, R.id.checklist_text_right,
        R.id.checklist_text_left_monospace, R.id.checklist_text_center_monospace, R.id.checklist_text_right_monospace
    )
    private var widgetTextColor = DEFAULT_WIDGET_TEXT_COLOR
    private var note: Note? = null
    private var tasks = mutableListOf<Task>()

    override fun getViewAt(position: Int): RemoteViews {
        val noteId = intent.getLongExtra(NOTE_ID, 0L)
        val remoteView: RemoteViews

        if (note == null) {
            return RemoteViews(context.packageName, R.layout.widget_text_layout)
        }

        val textSize = context.getPercentageFontSize() / context.resources.displayMetrics.density
        if (note!!.type == NoteType.TYPE_CHECKLIST) {
            remoteView = RemoteViews(context.packageName, R.layout.item_checklist_widget).apply {
                val checklistItem = tasks.getOrNull(position) ?: return@apply
                val widgetNewTextColor = if (checklistItem.isDone) widgetTextColor.adjustAlpha(DONE_CHECKLIST_ITEM_ALPHA) else widgetTextColor
                val paintFlags = if (checklistItem.isDone) Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG else Paint.ANTI_ALIAS_FLAG

                for (id in checklistIds) {
                    setText(id, checklistItem.title)
                    setTextColor(id, widgetNewTextColor)
                    setTextSize(id, textSize)
                    setInt(id, "setPaintFlags", paintFlags)
                    setViewVisibility(id, View.GONE)
                }

                setViewVisibility(getProperChecklistTextView(context), View.VISIBLE)

                Intent().apply {
                    putExtra(OPEN_NOTE_ID, noteId)
                    setOnClickFillInIntent(R.id.checklist_text_holder, this)
                }
            }
        } else {
            remoteView = RemoteViews(context.packageName, R.layout.widget_text_layout).apply {
                val noteText = note!!.getNoteStoredValue(context) ?: ""
                for (id in textIds) {
                    setText(id, noteText)
                    setTextColor(id, widgetTextColor)
                    setTextSize(id, textSize)
                    setViewVisibility(id, View.GONE)
                }
                setViewVisibility(getProperTextView(context), View.VISIBLE)

                Intent().apply {
                    putExtra(OPEN_NOTE_ID, noteId)
                    setOnClickFillInIntent(widget_text_holder, this)
                }
            }
        }

        return remoteView
    }

    private fun getProperTextView(context: Context): Int {
        val gravity = context.config.gravity
        val isMonospaced = context.config.monospacedFont

        return when {
            gravity == GRAVITY_CENTER && isMonospaced -> R.id.widget_text_center_monospace
            gravity == GRAVITY_CENTER -> R.id.widget_text_center
            gravity == GRAVITY_END && isMonospaced -> R.id.widget_text_right_monospace
            gravity == GRAVITY_END -> R.id.widget_text_right
            isMonospaced -> R.id.widget_text_left_monospace
            else -> R.id.widget_text_left
        }
    }

    private fun getProperChecklistTextView(context: Context): Int {
        val gravity = context.config.gravity
        val isMonospaced = context.config.monospacedFont

        return when {
            gravity == GRAVITY_CENTER && isMonospaced -> R.id.checklist_text_center_monospace
            gravity == GRAVITY_CENTER -> R.id.checklist_text_center
            gravity == GRAVITY_END && isMonospaced -> R.id.checklist_text_right_monospace
            gravity == GRAVITY_END -> R.id.checklist_text_right
            isMonospaced -> R.id.checklist_text_left_monospace
            else -> R.id.checklist_text_left
        }
    }

    override fun onCreate() {}

    override fun getLoadingView() = null

    override fun getItemId(position: Int) = position.toLong()

    override fun onDataSetChanged() {
        widgetTextColor = intent.getIntExtra(WIDGET_TEXT_COLOR, DEFAULT_WIDGET_TEXT_COLOR)
        val noteId = intent.getLongExtra(NOTE_ID, 0L)
        note = context.notesDB.getNoteWithId(noteId)
        if (note?.type == NoteType.TYPE_CHECKLIST) {
            tasks = note!!.getNoteStoredValue(context)?.ifEmpty { "[]" }?.let { Json.decodeFromString(it) } ?: mutableListOf()

            // checklist title can be null only because of the glitch in upgrade to 6.6.0, remove this check in the future
            tasks = tasks.toMutableList() as ArrayList<Task>
            val sorting = context.config.getSorting(noteId)
            if (sorting and SORT_BY_CUSTOM == 0) {
                Task.sorting = sorting
                tasks.sort()
            }
            if (context.config.getMoveDoneChecklistItems(noteId)) {
                tasks.sortBy { it.isDone }
            }
        }
    }

    override fun hasStableIds() = true

    override fun getCount(): Int {
        return if (note?.type == NoteType.TYPE_CHECKLIST) {
            tasks.size
        } else {
            1
        }
    }

    override fun getViewTypeCount() = 2

    override fun onDestroy() {}
}
