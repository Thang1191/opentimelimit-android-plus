/*
 * Open TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
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
package io.timelimit.android.ui.manage.category.settings

import android.net.Uri
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.android.material.slider.Slider
import io.timelimit.android.async.Threads
import io.timelimit.android.data.Database
import io.timelimit.android.data.model.CategoryPauseConfig
import io.timelimit.android.databinding.CategoryPausePointViewBinding

/**
 * The settings half of Pause Point, on the category's own page.
 *
 * Every control writes straight through: there is no confirm button, because a rule the user is
 * authoring for himself should not need a second decision, and the enforcement loop is watching the
 * table so any change is live before the next tick.
 */
object CategoryPausePointView {
    fun bind(
        binding: CategoryPausePointViewBinding,
        lifecycleOwner: LifecycleOwner,
        database: Database,
        categoryId: String,
        onChooseFolder: () -> Unit,
        onPreview: () -> Unit
    ) {
        var current = CategoryPauseConfig.default(categoryId)
        var rendering = false

        binding.pauseWait.valueTo = (CategoryPauseConfig.MAX_WAIT_SECONDS - CategoryPauseConfig.MIN_WAIT_SECONDS).toFloat()
        binding.pauseCooldown.valueTo = CategoryPauseConfig.MAX_COOLDOWN_MINUTES.toFloat()

        binding.pauseCheckIn.adapter = ArrayAdapter(
            binding.root.context,
            android.R.layout.simple_spinner_dropdown_item,
            CategoryPauseConfig.CHECK_IN_NAMES
        )

        fun save(transform: (CategoryPauseConfig) -> CategoryPauseConfig) {
            val updated = transform(current)
            current = updated

            Threads.database.execute {
                database.categoryPauseConfig().insertOrReplaceSync(updated)
            }
        }

        fun render(config: CategoryPauseConfig) {
            rendering = true

            binding.pauseEnabled.isChecked = config.enabled
            binding.pauseWait.value = (config.waitSeconds - CategoryPauseConfig.MIN_WAIT_SECONDS)
                .coerceIn(0, binding.pauseWait.valueTo.toInt()).toFloat()
            binding.pauseCooldown.value = config.cooldownMinutes
                .coerceIn(0, CategoryPauseConfig.MAX_COOLDOWN_MINUTES).toFloat()
            binding.pauseCheckIn.setSelection(config.checkInType.coerceIn(0, CategoryPauseConfig.CHECK_IN_NAMES.size - 1))

            binding.pauseWaitValue.text = "${config.waitSeconds}s"
            binding.pauseCooldownValue.text = if (config.cooldownMinutes == 0) "off"
            else "${config.cooldownMinutes}m"

            binding.pauseFolderValue.text = config.folderUri?.let { folderLabel(it) } ?: "No folder picked"
            binding.pauseFolderBtn.isEnabled = config.checkInType == CategoryPauseConfig.CHECK_IN_IMAGES

            rendering = false
        }

        database.categoryPauseConfig().getByCategoryIdLive(categoryId)
            .observe(lifecycleOwner, Observer { config ->
                current = config ?: CategoryPauseConfig.default(categoryId)
                render(current)
            })

        binding.pauseEnabled.setOnCheckedChangeListener { _, checked ->
            if (!rendering) save { it.copy(enabled = checked) }
        }

        binding.pauseWait.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            if (!rendering) binding.pauseWaitValue.text =
                "${value.toInt() + CategoryPauseConfig.MIN_WAIT_SECONDS}s"
        })

        binding.pauseWait.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                val seconds = slider.value.toInt() + CategoryPauseConfig.MIN_WAIT_SECONDS

                save { it.copy(waitSeconds = seconds) }
            }
        })

        binding.pauseCooldown.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            if (!rendering) binding.pauseCooldownValue.text = if (value.toInt() == 0) "off" else "${value.toInt()}m"
        })

        binding.pauseCooldown.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                val minutes = slider.value.toInt()

                save { it.copy(cooldownMinutes = minutes) }
            }
        })

        binding.pauseCheckIn.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (rendering || position == current.checkInType) return

                save { it.copy(checkInType = position) }
                binding.pauseFolderBtn.isEnabled = position == CategoryPauseConfig.CHECK_IN_IMAGES
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.pauseFolderBtn.setOnClickListener { onChooseFolder() }
        binding.pausePreviewBtn.setOnClickListener { onPreview() }

        render(current)
    }

    /** The tree URI is opaque, so show the part a human recognises: the last path segment. */
    private fun folderLabel(uriString: String): String =
        runCatching { Uri.decode(Uri.parse(uriString).lastPathSegment ?: uriString) }
            .getOrDefault(uriString)
            .substringAfterLast(':')
            .ifEmpty { uriString }
}
