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

import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.map
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import io.timelimit.android.R
import io.timelimit.android.data.model.Category
import io.timelimit.android.databinding.CategoryBatteryLimitViewBinding
import io.timelimit.android.livedata.ignoreUnchanged
import io.timelimit.android.sync.actions.UpdateCategoryBatteryLimit
import io.timelimit.android.ui.help.HelpDialogFragment
import io.timelimit.android.ui.main.ActivityViewModel

object CategoryBatteryLimitView {
    fun bind(
            binding: CategoryBatteryLimitViewBinding,
            lifecycleOwner: LifecycleOwner,
            category: LiveData<Category?>,
            auth: ActivityViewModel,
            categoryId: String,
            fragmentManager: FragmentManager
    ) {
        binding.titleView.setOnClickListener {
            HelpDialogFragment.newInstance(
                    title = R.string.category_settings_battery_limit_title,
                    text = R.string.category_settings_battery_limit_description
            ).show(fragmentManager)
        }

        fun updateButtonVisibility() {
            val category = category.value
            val modified = category == null || (category.minBatteryLevelMobile != binding.minLevelMobile) || (category.minBatteryLevelWhileCharging != binding.minLevelCharging)

            binding.confirmBtn.visibility = if (modified) View.VISIBLE else View.GONE
        }

        binding.seekbarCharging.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            binding.minLevelCharging = value.toInt() * 10

            updateButtonVisibility()
        })

        binding.seekbarMobile.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            binding.minLevelMobile = value.toInt() * 10

            updateButtonVisibility()
        })

        category.map {
            it?.run { it.minBatteryLevelMobile / 10 to it.minBatteryLevelWhileCharging / 10 }
        }.ignoreUnchanged().observe(lifecycleOwner, Observer {
            if (it != null) {
                val (mobile, charging) = it

                binding.seekbarMobile.value = mobile.toFloat()
                binding.seekbarCharging.value = charging.toFloat()

                updateButtonVisibility()
            }
        })

        binding.confirmBtn.setOnClickListener {
            if (
                    auth.tryDispatchParentAction(
                            UpdateCategoryBatteryLimit(
                                    categoryId = categoryId,
                                    mobileLimit = binding.seekbarMobile.value.toInt() * 10,
                                    chargingLimit = binding.seekbarCharging.value.toInt() * 10
                            )
                    )
            ) {
                Snackbar.make(binding.root, R.string.category_settings_battery_limit_confirm_toast, Snackbar.LENGTH_SHORT).show()

                binding.confirmBtn.visibility = View.GONE            }
        }
    }
}