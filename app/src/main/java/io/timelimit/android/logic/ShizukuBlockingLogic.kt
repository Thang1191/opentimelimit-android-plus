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

import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.data.model.CategoryApp
import io.timelimit.android.data.model.CategoryFlags
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.blockingType
import io.timelimit.android.integration.platform.shizuku.ShizukuIntegration
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
class ShizukuBlockingLogic(private val appLogic: AppLogic) {
    companion object {
        private const val LOG_TAG = "ShizukuBlockingLogic"
    }

    // Tracks packages currently disabled by this logic (to avoid redundant calls)
    private val disabledPackages = mutableSetOf<String>()
    private val categoryHandlingCache = CategoryHandlingCache()
    private val pendingSync = AtomicBoolean(true)
    private val executor = Executors.newSingleThreadExecutor()

    private val userAndDeviceRelatedDataLive = appLogic.database.derivedDataDao().getUserAndDeviceRelatedDataLive()
    private var didLoadData = false

    private val backgroundRunnable = Runnable {
        while (pendingSync.getAndSet(false)) {
            updateBlockingSync()
            Thread.sleep(500)
        }
    }

    private fun triggerUpdate() {
        pendingSync.set(true)
        executor.submit(backgroundRunnable)
    }

    init {
        appLogic.platformIntegration.getBatteryStatusLive().observeForever { triggerUpdate() }
        appLogic.realTimeLogic.registerTimeModificationListener { triggerUpdate() }
        userAndDeviceRelatedDataLive.observeForever {
            didLoadData = true
            triggerUpdate()
        }

        // Listen for Shizuku state changes to re-apply when it becomes available
        ShizukuIntegration.registerStateListener { available ->
            if (available) {
                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "Shizuku became available, re-applying blocking")
                }
                triggerUpdate()
            }
        }
    }

    private fun updateBlockingSync() {
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

        for (pkg in toDisable) {
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Disabling package: $pkg")
            }
            if (ShizukuIntegration.disablePackage(pkg)) {
                disabledPackages.add(pkg)
            } else {
                if (BuildConfig.DEBUG) {
                    Log.w(LOG_TAG, "Failed to disable package: $pkg")
                }
            }
        }
        if (toDisable.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(appLogic.context, "Disabled ${toDisable.size} app(s) via Shizuku", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        for (pkg in toEnable) {
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Enabling package: $pkg")
            }
            if (ShizukuIntegration.enablePackage(pkg)) {
                disabledPackages.remove(pkg)
            } else {
                if (BuildConfig.DEBUG) {
                    Log.w(LOG_TAG, "Failed to enable package: $pkg")
                }
            }
        }
        if (toEnable.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(appLogic.context, "Enabled ${toEnable.size} app(s) via Shizuku", android.widget.Toast.LENGTH_SHORT).show()
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
        while (iterator.hasNext()) {
            val pkg = iterator.next()
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Re-enabling package: $pkg")
            }
            if (ShizukuIntegration.enablePackage(pkg)) {
                iterator.remove()
            }
        }
    }
}
