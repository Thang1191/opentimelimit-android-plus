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
package io.timelimit.android.logic

import android.content.Context
import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.data.model.CategoryApp
import io.timelimit.android.data.model.CategoryFlags
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.blockingType
import io.timelimit.android.integration.platform.shizuku.ShizukuIntegration

/**
 * Logic class that handles app disabling/enabling via Shizuku for categories
 * with BLOCKING_TYPE_SHIZUKU_DISABLE.
 *
 * Follows the same pattern as [SuspendAppsLogic]:
 * - Observes user/device data changes
 * - Computes which packages should be disabled based on category blocking state
 * - Applies changes via [ShizukuIntegration]
 * - Tracks last applied state to avoid redundant operations
 */
class ShizukuBlockingLogic(appLogic: AppLogic) : BlockingStrategy(appLogic) {
    companion object {
        private const val LOG_TAG = "ShizukuBlockingLogic"
        private const val PREFS_NAME = "shizuku_blocking"
        private const val KEY_BLOCKED = "blocked_packages"
    }

    // Tracks packages currently blocked by this logic (to avoid redundant calls)
    private val disabledPackages = mutableSetOf<String>()

    private val prefs by lazy {
        appLogic.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        // A suspension survives a reboot while this process does not, so adopt whatever a previous run
        // left behind - otherwise those apps would stay blocked forever and never be released.
        disabledPackages.addAll(prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet())

        startObserving()
    }

    private fun persist() {
        prefs.edit().putStringSet(KEY_BLOCKED, disabledPackages.toSet()).apply()
    }

    /**
     * Suspension is preferred over disabling: the icon stays where it is and goes grey, the app cannot
     * be started, and it reverses exactly. `pm disable-user` remains the fallback for ROMs that refuse
     * to suspend, where the icon disappears instead.
     */
    private fun block(packageName: String): Boolean {
        if (ShizukuIntegration.suspendPackage(packageName)) return true

        if (BuildConfig.DEBUG) {
            Log.w(LOG_TAG, "suspend refused for $packageName, falling back to disable-user")
        }

        return ShizukuIntegration.disablePackage(packageName)
    }

    private fun unblock(packageName: String): Boolean {
        if (ShizukuIntegration.unsuspendPackage(packageName)) return true

        return ShizukuIntegration.enablePackage(packageName)
    }

    override fun onShizukuAvailable() {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "Shizuku became available, re-applying blocking")
        }
    }

    override fun updateBlockingSync() {
        if (!didLoadData) return

        // Check if Shizuku is available
        if (!ShizukuIntegration.isShizukuAvailable()) {
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Shizuku not available, clearing disabled state tracking")
            }
            // Don't clear disabledPackages — Shizuku may come back and we need to know what to re-enable
            return
        }

        val userAndDeviceRelatedData = userAndDeviceRelatedDataLive.value ?: return
        val userRelatedData = userAndDeviceRelatedData.userRelatedData ?: return

        // Only for child users
        if (userRelatedData.user.type != UserType.Child) {
            enableAllTrackedPackages()
            return
        }

        val now = appLogic.timeApi.getCurrentTimeInMillis()
        val batteryStatus = appLogic.platformIntegration.getBatteryStatus()

        categoryHandlingCache.reportStatus(
            user = userRelatedData,
            timeInMillis = now,
            batteryStatus = batteryStatus,
            currentNetworkId = null
        )

        // Find categories with Shizuku blocking type
        val shizukuCategories = userRelatedData.categoryById.values.filter { categoryRelatedData ->
            categoryRelatedData.category.blockingType == CategoryFlags.BLOCKING_TYPE_SHIZUKU_DISABLE
        }

        if (shizukuCategories.isEmpty()) {
            enableAllTrackedPackages()
            return
        }

        // Determine which packages should be disabled
        val shouldBeDisabled = mutableSetOf<String>()

        for (categoryData in shizukuCategories) {
            val categoryId = categoryData.category.id
            val handling = categoryHandlingCache.get(categoryId)

            if (handling.shouldBlockActivities) {
                // This category is blocked — all its apps should be disabled
                val categoryApps = userRelatedData.categoryApps
                    .filter { it.categoryId == categoryId && it.appSpecifier.activityName == null }
                    .map { it.appSpecifier.packageName }

                shouldBeDisabled.addAll(categoryApps)
            }
        }

        // Apply changes: disable newly blocked, enable newly unblocked
        val toDisable = shouldBeDisabled - disabledPackages
        val toEnable = disabledPackages - shouldBeDisabled

        var disabledCount = 0
        for (pkg in toDisable) {
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Blocking package: $pkg")
            }
            if (block(pkg)) {
                disabledPackages.add(pkg)
                disabledCount++
            } else {
                if (BuildConfig.DEBUG) {
                    Log.w(LOG_TAG, "Failed to block package: $pkg")
                }
            }
        }
        if (disabledCount > 0) {
            persist()

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(appLogic.context, "Blocked $disabledCount app(s)", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        var enabledCount = 0
        for (pkg in toEnable) {
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Unblocking package: $pkg")
            }
            if (unblock(pkg)) {
                disabledPackages.remove(pkg)
                enabledCount++
            } else {
                if (BuildConfig.DEBUG) {
                    Log.w(LOG_TAG, "Failed to unblock package: $pkg")
                }
            }
        }
        if (enabledCount > 0) {
            persist()

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(appLogic.context, "Unblocked $enabledCount app(s)", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Re-enables all packages that were disabled by this logic.
     * Called when the user is no longer a child, or there are no Shizuku-type categories.
     */
    private fun enableAllTrackedPackages() {
        if (disabledPackages.isEmpty()) return

        if (!ShizukuIntegration.isShizukuAvailable()) {
            if (BuildConfig.DEBUG) {
                Log.w(LOG_TAG, "Cannot enable packages — Shizuku not available")
            }
            return
        }

        val iterator = disabledPackages.iterator()
        var changed = false

        while (iterator.hasNext()) {
            val pkg = iterator.next()
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Re-enabling package: $pkg")
            }
            if (unblock(pkg)) {
                iterator.remove()
                changed = true
            }
        }

        if (changed) persist()
    }
}
