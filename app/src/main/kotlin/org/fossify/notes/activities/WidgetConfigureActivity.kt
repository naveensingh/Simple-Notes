package org.fossify.notes.activities

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.dialogs.ColorPickerDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.IS_CUSTOMIZING_COLORS
import org.fossify.commons.helpers.PROTECTION_NONE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.notes.R
import org.fossify.notes.adapters.TasksAdapter
import org.fossify.notes.databinding.WidgetConfigBinding
import org.fossify.notes.extensions.config
import org.fossify.notes.extensions.getPercentageFontSize
import org.fossify.notes.extensions.widgetsDB
import org.fossify.notes.helpers.*
import org.fossify.notes.models.Note
import org.fossify.notes.models.NoteType
import org.fossify.notes.models.Task
import org.fossify.notes.models.Widget

class WidgetConfigureActivity : SimpleActivity() {
    private var mBgAlpha = 0f
    private var mWidgetId = 0
    private var mBgColor = 0
    private var mBgColorWithoutTransparency = 0
    private var mTextColor = 0
    private var mCurrentNoteId = 0L
    private var mIsCustomizingColors = false
    private var mShowTitle = false
    private var mNotes = listOf<Note>()
    private val binding by viewBinding(WidgetConfigBinding::inflate)

    public override fun onCreate(savedInstanceState: Bundle?) {
        useDynamicTheme = false
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(binding.root)
        initVariables()

        mWidgetId = intent.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (mWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID && !mIsCustomizingColors) {
            finish()
        }

        updateTextColors(binding.notesPickerHolder)
        binding.configSave.setOnClickListener { saveConfig() }
        binding.configBgColor.setOnClickListener { pickBackgroundColor() }
        binding.configTextColor.setOnClickListener { pickTextColor() }
        binding.notesPickerValue.setOnClickListener { showNoteSelector() }

        val primaryColor = getProperPrimaryColor()
        binding.configBgSeekbar.setColors(mTextColor, primaryColor, primaryColor)
        binding.notesPickerHolder.background = ColorDrawable(getProperBackgroundColor())

        binding.showNoteTitleHolder.setOnClickListener {
            binding.showNoteTitle.toggle()
            handleNoteTitleDisplay()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.textNoteView.setTextSize(TypedValue.COMPLEX_UNIT_PX, getPercentageFontSize())
    }

    private fun initVariables() {
        val extras = intent.extras
        if (extras?.getInt(CUSTOMIZED_WIDGET_ID, 0) == 0) {
            mBgColor = config.widgetBgColor
            mTextColor = config.widgetTextColor
        } else {
            mBgColor = extras?.getInt(CUSTOMIZED_WIDGET_BG_COLOR) ?: config.widgetBgColor
            mTextColor = extras?.getInt(CUSTOMIZED_WIDGET_TEXT_COLOR) ?: config.widgetTextColor
            mShowTitle = extras?.getBoolean(CUSTOMIZED_WIDGET_SHOW_TITLE) ?: false
        }

        if (mTextColor == resources.getColor(org.fossify.commons.R.color.default_widget_text_color) && config.isUsingSystemTheme) {
            mTextColor = resources.getColor(org.fossify.commons.R.color.you_primary_color, theme)
        }

        mBgAlpha = Color.alpha(mBgColor) / 255.toFloat()

        mBgColorWithoutTransparency = Color.rgb(Color.red(mBgColor), Color.green(mBgColor), Color.blue(mBgColor))
        binding.configBgSeekbar.apply {
            progress = (mBgAlpha * 100).toInt()

            onSeekBarChangeListener {
                mBgAlpha = it / 100f
                updateBackgroundColor()
            }
        }
        updateBackgroundColor()

        updateTextColor()
        mIsCustomizingColors = extras?.getBoolean(IS_CUSTOMIZING_COLORS) ?: false
        binding.notesPickerHolder.beVisibleIf(!mIsCustomizingColors)
        binding.textNoteViewTitle.beGoneIf(!mShowTitle)

        NotesHelper(this).getNotes {
            mNotes = it
            binding.notesPickerHolder.beVisibleIf(mNotes.size > 1 && !mIsCustomizingColors)
            var note = mNotes.firstOrNull { !it.isLocked() }

            if (mNotes.size == 1 && note == null) {
                note = mNotes.first()
                if (note.shouldBeUnlocked(this)) {
                    updateCurrentNote(note)
                } else {
                    performSecurityCheck(
                        protectionType = note.protectionType,
                        requiredHash = note.protectionHash,
                        successCallback = { _, _ -> updateCurrentNote(note) },
                        failureCallback = { finish() }
                    )
                }
            } else if (note != null) {
                updateCurrentNote(note)
            }
        }
    }

    private fun showNoteSelector() {
        val items = ArrayList<RadioItem>()
        mNotes.forEach {
            items.add(RadioItem(it.id!!.toInt(), it.title))
        }

        RadioGroupDialog(this, items, mCurrentNoteId.toInt()) {
            val selectedId = it as Int
            val note = mNotes.firstOrNull { it.id!!.toInt() == selectedId } ?: return@RadioGroupDialog
            if (note.protectionType == PROTECTION_NONE || note.shouldBeUnlocked(this)) {
                updateCurrentNote(note)
            } else {
                performSecurityCheck(
                    protectionType = note.protectionType,
                    requiredHash = note.protectionHash,
                    successCallback = { _, _ -> updateCurrentNote(note) }
                )
            }
        }
    }

    private fun updateCurrentNote(note: Note) {
        mCurrentNoteId = note.id!!
        binding.notesPickerValue.text = note.title
        binding.textNoteViewTitle.text = note.title
        if (note.type == NoteType.TYPE_CHECKLIST) {
            val taskType = object : TypeToken<List<Task>>() {}.type
            val items = Gson().fromJson<ArrayList<Task>>(note.value, taskType) ?: ArrayList(1)
            items.apply {
                if (isEmpty()) {
                    add(Task(0, System.currentTimeMillis(), "Milk", true))
                    add(Task(1, System.currentTimeMillis(), "Butter", true))
                    add(Task(2, System.currentTimeMillis(), "Salt", false))
                    add(Task(3, System.currentTimeMillis(), "Water", false))
                    add(Task(4, System.currentTimeMillis(), "Meat", true))
                }
            }

            TasksAdapter(this, null, binding.checklistNoteView, mCurrentNoteId).apply {
                updateTextColor(mTextColor)
                binding.checklistNoteView.adapter = this
                submitList(items.toList())
            }

            binding.textNoteView.beGone()
            binding.checklistNoteView.beVisible()
        } else {
            val sampleValue = if (note.value.isEmpty() || mIsCustomizingColors) getString(R.string.widget_config) else note.value
            binding.textNoteView.text = sampleValue
            binding.textNoteView.typeface = if (config.monospacedFont) Typeface.MONOSPACE else Typeface.DEFAULT
            binding.textNoteView.beVisible()
            binding.checklistNoteView.beGone()
        }
    }

    private fun saveConfig() {
        if (mCurrentNoteId == 0L) {
            finish()
            return
        }

        val views = RemoteViews(packageName, R.layout.activity_main)
        views.setBackgroundColor(R.id.text_note_view, mBgColor)
        views.setBackgroundColor(R.id.checklist_note_view, mBgColor)
        AppWidgetManager.getInstance(this)?.updateAppWidget(mWidgetId, views) ?: return

        val extras = intent.extras
        val id = if (extras?.containsKey(CUSTOMIZED_WIDGET_KEY_ID) == true) extras.getLong(CUSTOMIZED_WIDGET_KEY_ID) else null
        mWidgetId = extras?.getInt(CUSTOMIZED_WIDGET_ID, mWidgetId) ?: mWidgetId
        mCurrentNoteId = extras?.getLong(CUSTOMIZED_WIDGET_NOTE_ID, mCurrentNoteId) ?: mCurrentNoteId
        val widget = Widget(id, mWidgetId, mCurrentNoteId, mBgColor, mTextColor, mShowTitle)
        ensureBackgroundThread {
            widgetsDB.insertOrUpdate(widget)
        }

        storeWidgetBackground()
        requestWidgetUpdate()

        Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mWidgetId)
            setResult(Activity.RESULT_OK, this)
        }
        finish()
    }

    private fun storeWidgetBackground() {
        config.apply {
            widgetBgColor = mBgColor
            widgetTextColor = mTextColor
        }
    }

    private fun requestWidgetUpdate() {
        Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE, null, this, MyWidgetProvider::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(mWidgetId))
            sendBroadcast(this)
        }
    }

    private fun updateBackgroundColor() {
        mBgColor = mBgColorWithoutTransparency.adjustAlpha(mBgAlpha)
        binding.textNoteView.setBackgroundColor(mBgColor)
        binding.checklistNoteView.setBackgroundColor(mBgColor)
        binding.textNoteViewTitle.setBackgroundColor(mBgColor)
        binding.configBgColor.setFillWithStroke(mBgColor, mBgColor)
        binding.configSave.backgroundTintList = ColorStateList.valueOf(getProperPrimaryColor())
    }

    private fun updateTextColor() {
        binding.textNoteView.setTextColor(mTextColor)
        binding.textNoteViewTitle.setTextColor(mTextColor)
        (binding.checklistNoteView.adapter as? TasksAdapter)?.updateTextColor(mTextColor)
        binding.configTextColor.setFillWithStroke(mTextColor, mTextColor)
        binding.configSave.setTextColor(getProperPrimaryColor().getContrastColor())
    }

    private fun pickBackgroundColor() {
        ColorPickerDialog(this, mBgColorWithoutTransparency) { wasPositivePressed, color ->
            if (wasPositivePressed) {
                mBgColorWithoutTransparency = color
                updateBackgroundColor()
            }
        }
    }

    private fun pickTextColor() {
        ColorPickerDialog(this, mTextColor) { wasPositivePressed, color ->
            if (wasPositivePressed) {
                mTextColor = color
                updateTextColor()
            }
        }
    }

    private fun handleNoteTitleDisplay() {
        val showTitle = binding.showNoteTitle.isChecked
        binding.textNoteViewTitle.beGoneIf(!showTitle)
        mShowTitle = showTitle
    }
}
