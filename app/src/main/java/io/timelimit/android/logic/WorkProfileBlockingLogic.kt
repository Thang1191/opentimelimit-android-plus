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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.timelimit.android.BuildConfig
import io.timelimit.android.data.model.CategoryFlags
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.blockingType
import io.timelimit.android.integration.platform.shizuku.ShizukuIntegration
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Logic class that handles work profile quiet mode toggling for categories
 * with BLOCKING_TYPE_WORK_PROFILE.
 */
class WorkProfileBlockingLogic(private val appLogic: AppLogic) {
    companion object {
        private const val LOG_TAG = "WorkProfileBlockingLogic"
    }

    private var workProfileUserHandle: UserHandle? = null
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

    private val workProfileStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            triggerUpdate()
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

        // Listen for Shizuku state changes to attempt permission grant
        ShizukuIntegration.registerStateListener { available ->
            if (available) {
                ensurePermissionGrantedViaShizuku()
                triggerUpdate()
            }
        }

        val workProfileFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        }
        ContextCompat.registerReceiver(
            appLogic.context,
            workProfileStateReceiver,
            workProfileFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun ensurePermissionGrantedViaShizuku() {
        if (ContextCompat.checkSelfPermission(appLogic.context, "android.permission.MODIFY_QUIET_MODE") != PackageManager.PERMISSION_GRANTED) {
            if (ShizukuIntegration.isShizukuAvailable()) {
                val pkgName = appLogic.context.packageName
                ShizukuIntegration.executeCommand("pm grant $pkgName android.permission.MODIFY_QUIET_MODE")
            }
        }
    }

    private fun findWorkProfileUserHandle() {
        val userManager = appLogic.context.getSystemService(Context.USER_SERVICE) as UserManager
        workProfileUserHandle = userManager.userProfiles.find { it.hashCode() != 0 }
    }

    private fun updateBlockingSync() {
        if (!didLoadData) return

        if (ContextCompat.checkSelfPermission(appLogic.context, "android.permission.MODIFY_QUIET_MODE") != PackageManager.PERMISSION_GRANTED) {
            ensurePermissionGrantedViaShizuku()
            // Continue if we have it now, otherwise we can't do anything yet.
            if (ContextCompat.checkSelfPermission(appLogic.context, "android.permission.MODIFY_QUIET_MODE") != PackageManager.PERMISSION_GRANTED) {
                if (BuildConfig.DEBUG) Log.w(LOG_TAG, "Missing MODIFY_QUIET_MODE permission")
                return
            }
        }

        if (workProfileUserHandle == null) {
            findWorkProfileUserHandle()
            if (workProfileUserHandle == null) {
                if (BuildConfig.DEBUG) Log.d(LOG_TAG, "No work profile found")
                return
            }
        }

        val userAndDeviceRelatedData = userAndDeviceRelatedDataLive.value ?: return
        val userRelatedData = userAndDeviceRelatedData.userRelatedData ?: return

        // Only for child users
        if (userRelatedData.user.type != UserType.Child) {
            setQuietModeEnabled(false)
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

        // Find categories with Work Profile blocking type
        val workProfileCategories = userRelatedData.categoryById.values.filter { categoryRelatedData ->
            categoryRelatedData.category.blockingType == CategoryFlags.BLOCKING_TYPE_WORK_PROFILE
        }

        if (workProfileCategories.isEmpty()) {
            setQuietModeEnabled(false)
            return
        }

        var shouldBlock = false

        for (categoryData in workProfileCategories) {
            val categoryId = categoryData.category.id
            val handling = categoryHandlingCache.get(categoryId)

            if (handling.shouldBlockActivities) {
                shouldBlock = true
                break // One blocked category is enough to disable the work profile
            }
        }

        setQuietModeEnabled(shouldBlock)
    }

    private fun setQuietModeEnabled(enableQuietMode: Boolean) {
        val userHandle = workProfileUserHandle ?: return
        val userManager = appLogic.context.getSystemService(Context.USER_SERVICE) as UserManager

        if (userManager.isQuietModeEnabled(userHandle) != enableQuietMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "Setting work profile quiet mode to $enableQuietMode")
                }
                userManager.requestQuietModeEnabled(enableQuietMode, userHandle)
                Handler(Looper.getMainLooper()).post {
                    val msg = if (enableQuietMode) "Work Profile Paused" else "Work Profile Resumed"
                    Toast.makeText(appLogic.context, msg, Toast.LENGTH_SHORT).show()
                }
            } else {
                if (BuildConfig.DEBUG) {
                    Log.w(LOG_TAG, "requestQuietModeEnabled requires Android P (API 28)")
                }
            }
        }
    }
}
