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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.google.android.material.snackbar.Snackbar
import io.timelimit.android.R
import io.timelimit.android.data.model.Category
import io.timelimit.android.data.model.CategoryFlags
import io.timelimit.android.data.model.blockingType
import io.timelimit.android.databinding.CategoryBlockingTechniqueViewBinding
import io.timelimit.android.integration.platform.shizuku.ShizukuIntegration
import io.timelimit.android.sync.actions.UpdateCategoryFlagsAction
import io.timelimit.android.sync.actions.UpdateCategoryForceDnsHostnameAction
import io.timelimit.android.ui.main.ActivityViewModel

/**
 * Handles binding logic for the blocking technique radio group in category settings.
 * Follows the same pattern as [CategoryBatteryLimitView], [ManageCategoryNetworksView], etc.
 */
object BlockingTechniqueView {
    fun bind(
        binding: CategoryBlockingTechniqueViewBinding,
        lifecycleOwner: LifecycleOwner,
        category: LiveData<Category?>,
        auth: ActivityViewModel,
        categoryId: String
    ) {
        // Update Shizuku status indicator
        updateShizukuStatus(binding)

        // Listen for Shizuku state changes
        ShizukuIntegration.registerStateListener { _ ->
            binding.root.post { updateShizukuStatus(binding) }
        }

        category.observe(lifecycleOwner) { cat ->
            // Remove listener to prevent feedback loops
            binding.blockingTechniqueGroup.setOnCheckedChangeListener(null)

            if (cat != null) {
                // Set the current selection based on category flags
                val currentBlockingType = cat.blockingType
                val checkedId = when (currentBlockingType) {
                    CategoryFlags.BLOCKING_TYPE_SHIZUKU_DISABLE -> R.id.blocking_type_shizuku_disable
                    CategoryFlags.BLOCKING_TYPE_WORK_PROFILE -> R.id.blocking_type_work_profile
                    CategoryFlags.BLOCKING_TYPE_FORCE_DNS -> R.id.blocking_type_force_dns
                    else -> R.id.blocking_type_normal
                }
                binding.blockingTechniqueGroup.check(checkedId)

                // Show Shizuku status when a Shizuku-dependent option is selected
                updateShizukuStatusVisibility(binding, currentBlockingType)
                updateForceDnsHostnameVisibility(binding, currentBlockingType)

                // Populate current DNS hostname
                if (binding.forceDnsHostnameInput.text.toString() != cat.forceDnsHostname) {
                    binding.forceDnsHostnameInput.setText(cat.forceDnsHostname)
                }

                // Set listener for changes
                binding.blockingTechniqueGroup.setOnCheckedChangeListener { _, id ->
                    val newBlockingType = when (id) {
                        R.id.blocking_type_shizuku_disable -> CategoryFlags.BLOCKING_TYPE_SHIZUKU_DISABLE
                        R.id.blocking_type_work_profile -> CategoryFlags.BLOCKING_TYPE_WORK_PROFILE
                        R.id.blocking_type_force_dns -> CategoryFlags.BLOCKING_TYPE_FORCE_DNS
                        else -> CategoryFlags.BLOCKING_TYPE_NORMAL
                    }

                    if (newBlockingType != currentBlockingType) {
                        val success = auth.tryDispatchParentAction(
                            UpdateCategoryFlagsAction(
                                categoryId = categoryId,
                                modifiedBits = CategoryFlags.BLOCKING_TYPE_MASK,
                                newValues = newBlockingType
                            )
                        )

                        if (success) {
                            Snackbar.make(
                                binding.root,
                                R.string.category_settings_blocking_technique_changed_toast,
                                Snackbar.LENGTH_SHORT
                            ).show()
                        } else {
                            // Revert the radio button to the old value
                            binding.blockingTechniqueGroup.check(checkedId)
                        }
                    }

                    // Update status visibility
                    updateShizukuStatusVisibility(binding, newBlockingType)
                    updateForceDnsHostnameVisibility(binding, newBlockingType)
                    updateShizukuStatus(binding)
                }

                // Handle text change
                binding.forceDnsHostnameInput.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val newHostname = binding.forceDnsHostnameInput.text.toString()
                        if (newHostname != cat.forceDnsHostname) {
                            auth.tryDispatchParentAction(
                                UpdateCategoryForceDnsHostnameAction(
                                    categoryId = categoryId,
                                    newHostname = newHostname
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateShizukuStatusVisibility(
        binding: CategoryBlockingTechniqueViewBinding,
        blockingType: Long
    ) {
        // Show Shizuku status text for Shizuku-dependent blocking types
        val needsShizuku = blockingType == CategoryFlags.BLOCKING_TYPE_SHIZUKU_DISABLE ||
                blockingType == CategoryFlags.BLOCKING_TYPE_WORK_PROFILE ||
                blockingType == CategoryFlags.BLOCKING_TYPE_FORCE_DNS

        binding.shizukuStatus.visibility = if (needsShizuku) View.VISIBLE else View.GONE
    }

    private fun updateForceDnsHostnameVisibility(
        binding: CategoryBlockingTechniqueViewBinding,
        blockingType: Long
    ) {
        val isForceDns = blockingType == CategoryFlags.BLOCKING_TYPE_FORCE_DNS
        binding.forceDnsHostnameLayout.visibility = if (isForceDns) View.VISIBLE else View.GONE
    }

    private fun updateShizukuStatus(binding: CategoryBlockingTechniqueViewBinding) {
        if (binding.shizukuStatus.visibility != View.VISIBLE) return

        val available = ShizukuIntegration.isShizukuAvailable()
        if (available) {
            binding.shizukuStatus.setText(R.string.category_settings_blocking_technique_shizuku_status_available)
            val color = com.google.android.material.color.MaterialColors.getColor(
                binding.shizukuStatus, com.google.android.material.R.attr.colorSecondary
            )
            binding.shizukuStatus.setTextColor(color)
        } else {
            binding.shizukuStatus.setText(R.string.category_settings_blocking_technique_shizuku_status_unavailable)
            // Resolve colorError from the theme via obtainStyledAttributes
            val context = binding.root.context
            val errorAttrs = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.colorError)
            )
            val errorColor = errorAttrs.getColor(0, 0xFFB00020.toInt())
            errorAttrs.recycle()
            binding.shizukuStatus.setTextColor(errorColor)
        }
    }


}
