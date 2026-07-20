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
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.data.model.CategoryFlags
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.blockingType
import io.timelimit.android.integration.platform.shizuku.ShizukuIntegration
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Logic class that handles enforcing a specific Private DNS hostname for categories
 * with BLOCKING_TYPE_FORCE_DNS.
 */
class DnsBlockingLogic(private val appLogic: AppLogic) {
    companion object {
        private const val LOG_TAG = "DnsBlockingLogic"
        private const val PREFS_NAME = "dns_logic_prefs"
        private const val PREF_ORIGINAL_MODE = "original_dns_mode"
        private const val PREF_ORIGINAL_SPECIFIER = "original_dns_specifier"
        private const val PREF_IS_OVERRIDDEN = "is_dns_overridden"
    }

    private val categoryHandlingCache = CategoryHandlingCache()
    private val pendingSync = AtomicBoolean(true)
    private val executor = Executors.newSingleThreadExecutor()

    private val userAndDeviceRelatedDataLive = appLogic.database.derivedDataDao().getUserAndDeviceRelatedDataLive()
    private var didLoadData = false
    
    private val prefs = appLogic.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var targetDnsHostname: String? = null

    private val backgroundRunnable = Runnable {
        while (pendingSync.getAndSet(false)) {
            updateBlockingSync()
            Thread.sleep(500)
        }
    }

    private val dnsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
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

        // Listen for Shizuku state changes
        ShizukuIntegration.registerStateListener { available ->
            if (available) {
                triggerUpdate()
            }
        }

        appLogic.context.contentResolver.registerContentObserver(
            Settings.Global.CONTENT_URI, true, dnsObserver
        )
    }

    private fun updateBlockingSync() {
        if (!didLoadData) return
        if (!ShizukuIntegration.isShizukuAvailable()) return

        val userAndDeviceRelatedData = userAndDeviceRelatedDataLive.value ?: return
        val userRelatedData = userAndDeviceRelatedData.userRelatedData ?: return

        // Only for child users
        if (userRelatedData.user.type != UserType.Child) {
            restoreOriginalDns()
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

        // Find categories with Force DNS blocking type
        val dnsCategories = userRelatedData.categoryById.values.filter { categoryRelatedData ->
            categoryRelatedData.category.blockingType == CategoryFlags.BLOCKING_TYPE_FORCE_DNS
        }

        if (dnsCategories.isEmpty()) {
            restoreOriginalDns()
            return
        }

        var activeHostnameToForce: String? = null

        for (categoryData in dnsCategories) {
            val categoryId = categoryData.category.id
            val handling = categoryHandlingCache.get(categoryId)

            if (handling.shouldBlockActivities) {
                val hostname = categoryData.category.forceDnsHostname
                if (hostname.isNotBlank()) {
                    activeHostnameToForce = hostname
                    break // Force the first matching active DNS rule
                }
            }
        }

        if (activeHostnameToForce != null) {
            targetDnsHostname = activeHostnameToForce
            enforceDns(activeHostnameToForce)
        } else {
            targetDnsHostname = null
            restoreOriginalDns()
        }
    }

    private fun enforceDns(hostname: String) {
        val currentMode = Settings.Global.getString(appLogic.context.contentResolver, "private_dns_mode")
        val currentHostname = Settings.Global.getString(appLogic.context.contentResolver, "private_dns_specifier")

        // Store original if we haven't already
        if (!prefs.getBoolean(PREF_IS_OVERRIDDEN, false)) {
            prefs.edit()
                .putString(PREF_ORIGINAL_MODE, currentMode)
                .putString(PREF_ORIGINAL_SPECIFIER, currentHostname)
                .putBoolean(PREF_IS_OVERRIDDEN, true)
                .apply()
        }

        if (currentMode != "hostname" || currentHostname != hostname) {
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Enforcing DNS to: $hostname")
            }
            ShizukuIntegration.executeCommand("settings put global private_dns_mode hostname")
            ShizukuIntegration.executeCommand("settings put global private_dns_specifier $hostname")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(appLogic.context, "DNS Enforced: $hostname", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun restoreOriginalDns() {
        targetDnsHostname = null
        if (prefs.getBoolean(PREF_IS_OVERRIDDEN, false)) {
            val originalMode = prefs.getString(PREF_ORIGINAL_MODE, null) ?: "automatic"
            val originalSpecifier = prefs.getString(PREF_ORIGINAL_SPECIFIER, null) ?: ""

            val currentMode = Settings.Global.getString(appLogic.context.contentResolver, "private_dns_mode")
            val currentHostname = Settings.Global.getString(appLogic.context.contentResolver, "private_dns_specifier")

            if (currentMode != originalMode || (originalMode == "hostname" && currentHostname != originalSpecifier)) {
                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "Restoring original DNS settings")
                }
                ShizukuIntegration.executeCommand("settings put global private_dns_mode $originalMode")
                if (originalMode == "hostname") {
                    ShizukuIntegration.executeCommand("settings put global private_dns_specifier $originalSpecifier")
                } else {
                    ShizukuIntegration.executeCommand("settings delete global private_dns_specifier")
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(appLogic.context, "DNS Restored", android.widget.Toast.LENGTH_SHORT).show()
                }
            }

            prefs.edit().clear().apply()
        }
    }
}
