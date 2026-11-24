/*
 * TimeLimit Copyright <C> 2019 - 2020 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import com.google.android.material.slider.Slider
import io.timelimit.android.R
import io.timelimit.android.util.TimeTextUtil
import kotlin.properties.Delegates

class SelectTimeSpanView(context: Context, attributeSet: AttributeSet? = null): FrameLayout(context, attributeSet) {
    init {
        LayoutInflater.from(context).inflate(R.layout.view_select_time_span, this, true)
    }

    private val seekbarContainer = findViewById<View>(R.id.seekbar_container)
    private val pickerContainer = findViewById<View>(R.id.picker_container)

    private val switchToPickerButton = findViewById<ImageView>(R.id.switch_to_picker_button)
    private val switchToSeekbarButton = findViewById<ImageView>(R.id.switch_to_seekbar_button)

    private val daysText = findViewById<TextView>(R.id.days_text)
    private val dayPickerContainer = findViewById<View>(R.id.day_picker_container)
    private val dayPicker = findViewById<NumberPicker>(R.id.day_picker)
    private val daySeekbar = findViewById<Slider>(R.id.days_seek)

    private val hoursText = findViewById<TextView>(R.id.hours_text)
    private val hourPicker = findViewById<NumberPicker>(R.id.hour_picker)
    private val hourSeekbar = findViewById<Slider>(R.id.hours_seek)

    private val minutesText = findViewById<TextView>(R.id.minutes_text)
    private val minutePicker = findViewById<NumberPicker>(R.id.minute_picker)
    private val minuteSeekbar = findViewById<Slider>(R.id.minutes_seek)

    var listener: SelectTimeSpanViewListener? = null

    var timeInMillis: Long by Delegates.observable(0L) { _, oldValue, newValue ->
        if (oldValue != newValue) { bindTime() }

        listener?.onTimeSpanChanged(timeInMillis)
    }

    var maxDays: Int by Delegates.observable(0) { _, _, newValue -> bindMaxDays(newValue) }

    init {
        val attributes = context.obtainStyledAttributes(attributeSet, R.styleable.SelectTimeSpanView)

        timeInMillis = attributes.getInt(R.styleable.SelectTimeSpanView_timeInMillis, timeInMillis.toInt()).toLong()
        maxDays = attributes.getInt(R.styleable.SelectTimeSpanView_maxDays, maxDays)

        attributes.recycle()

        bindTime()
        enablePickerMode(false)
    }

    private fun bindMaxDays(newValue: Int) {
        val multipleDays = newValue > 0
        val vis = if (multipleDays) View.VISIBLE else View.GONE

        dayPicker.maxValue = newValue

        // A Slider cannot exist with valueFrom == valueTo, so the floor is 1 even while the control
        // is hidden (newValue == 0); and the current value must never sit above the new ceiling, or
        // setting valueTo throws before the value catches up.
        val sliderMax = newValue.coerceAtLeast(1).toFloat()
        if (daySeekbar.value > sliderMax) daySeekbar.value = 0f
        daySeekbar.valueTo = sliderMax

        dayPickerContainer.visibility = vis
        daysText.visibility = vis
        daySeekbar.visibility = vis

    }

    private fun bindTime() {
        val duration = Duration.decode(timeInMillis)

        daysText.text = TimeTextUtil.days(duration.days, context)
        minutesText.text = TimeTextUtil.minutes(duration.minutes, context)
        hoursText.text = TimeTextUtil.hours(duration.hours, context)

        minutePicker.value = duration.minutes
        minuteSeekbar.value = duration.minutes.toFloat()

        hourPicker.value = duration.hours
        hourSeekbar.value = duration.hours.toFloat()

        dayPicker.value = duration.days
        daySeekbar.value = duration.days.toFloat().coerceIn(daySeekbar.valueFrom, daySeekbar.valueTo)
    }

    fun clearNumberPickerFocus() {
        minutePicker.clearFocus()
        hourPicker.clearFocus()
        dayPicker.clearFocus()
    }

    fun enablePickerMode(enable: Boolean) {
        seekbarContainer.visibility = if (enable) View.GONE else View.VISIBLE
        pickerContainer.visibility = if (enable) View.VISIBLE else View.GONE
    }

    init {
        minutePicker.minValue = 0
        minutePicker.maxValue = 59

        hourPicker.minValue = 0
        hourPicker.maxValue = 23

        dayPicker.minValue = 0
        dayPicker.maxValue = 1
        dayPickerContainer.visibility = View.GONE

        minutePicker.setOnValueChangedListener { _, _, newValue ->
            timeInMillis = Duration.decode(timeInMillis).copy(minutes = newValue).timeInMillis
        }

        hourPicker.setOnValueChangedListener { _, _, newValue ->
            timeInMillis = Duration.decode(timeInMillis).copy(hours = newValue).timeInMillis
        }

        dayPicker.setOnValueChangedListener { _, _, newValue ->
            timeInMillis = Duration.decode(timeInMillis).copy(days = newValue).timeInMillis
        }

        daySeekbar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) timeInMillis = Duration.decode(timeInMillis).copy(days = value.toInt()).timeInMillis
        }

        hourSeekbar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) timeInMillis = Duration.decode(timeInMillis).copy(hours = value.toInt()).timeInMillis
        }

        minuteSeekbar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) timeInMillis = Duration.decode(timeInMillis).copy(minutes = value.toInt()).timeInMillis
        }

        pickerContainer.visibility = GONE

        switchToPickerButton.setOnClickListener { listener?.setEnablePickerMode(true) }
        switchToSeekbarButton.setOnClickListener { listener?.setEnablePickerMode(false) }
    }

    internal data class Duration (val days: Int, val hours: Int, val minutes: Int) {
        companion object {
            fun decode(timeInMillis: Long): Duration {
                val totalMinutes = (timeInMillis / (1000 * 60)).toInt()
                val totalHours = totalMinutes  / 60
                val totalDays = totalHours / 24
                val minutes = totalMinutes % 60
                val hours = totalHours % 24

                return Duration(days = totalDays, hours = hours, minutes = minutes)
            }
        }

        val timeInMillis = ((((days * 24L) + hours) * 60 + minutes) * 1000 * 60)
    }
}

interface SelectTimeSpanViewListener {
    fun onTimeSpanChanged(newTimeInMillis: Long)
    fun setEnablePickerMode(enable: Boolean)
}
