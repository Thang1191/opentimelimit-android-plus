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
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import io.timelimit.android.data.Database
import io.timelimit.android.data.model.Device
import io.timelimit.android.data.model.User
import io.timelimit.android.integration.lifeup.LifeUpBroadcastReceiver
import io.timelimit.android.integration.platform.PlatformIntegration
import io.timelimit.android.integration.time.TimeApi
import io.timelimit.android.livedata.ignoreUnchanged
import io.timelimit.android.livedata.liveDataFromNullableValue
import io.timelimit.android.ui.widget.TimesWidgetProvider

class AppLogic(
        val platformIntegration: PlatformIntegration,
        val timeApi: TimeApi,
        val database: Database,
        val context: Context,
        val isInitialized: LiveData<Boolean>,
        enabledInitially: Boolean = true
) {
    val enable = MutableLiveData<Boolean>().apply { value = enabledInitially }

    val deviceId = database.config().getOwnDeviceId()

    val deviceEntry = deviceId.switchMap<String?, Device?> {
        if (it == null) {
            liveDataFromNullableValue(null)
        } else {
            database.device().getDeviceById(it)
        }
    }.ignoreUnchanged()

    val deviceEntryIfEnabled = enable.switchMap {
        if (it == null || it == false) {
            liveDataFromNullableValue(null as Device?)
        } else {
            deviceEntry
        }
    }

    val deviceUserId: LiveData<String> = deviceEntry.map { it?.currentUserId ?: "" }

    val deviceUserEntry = deviceUserId.switchMap {
        if (it == "") {
            liveDataFromNullableValue(null as User?)
        } else {
            database.user().getUserByIdLive(it)
        }
    }.ignoreUnchanged()

    private val foregroundAppQueryInterval = database.config().getForegroundAppQueryIntervalAsync().apply { observeForever {  } }

    fun getForegroundAppQueryInterval() = foregroundAppQueryInterval.value ?: 0L

    val defaultUserLogic = DefaultUserLogic(this)
    val realTimeLogic = RealTimeLogic(this)
    val backgroundTaskLogic = BackgroundTaskLogic(this)

    private var lifeUpReceiver: LifeUpBroadcastReceiver? = null
    val appSetupLogic = AppSetupLogic(this)

    init {
        SyncInstalledAppsLogic(this)
        WatchdogLogic(this)
        TimesWidgetProvider.triggerUpdates(context)

        // Initialize Shizuku lifecycle listeners
        io.timelimit.android.integration.platform.shizuku.ShizukuIntegration.init()

        registerLifeUpReceiver()
    }

    /**
     * LifeUp broadcasts the item countdown implicitly (no package set). Since API 26 an implicit
     * broadcast does not reach a manifest-registered receiver, so the manifest entry alone silently
     * drops every "countdown stopped" event - the rule stayed suspended and blocking never came back.
     * A runtime registration is not subject to that restriction.
     */
    private fun registerLifeUpReceiver() {
        if (lifeUpReceiver != null) return

        try {
            val receiver = LifeUpBroadcastReceiver()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, LifeUpBroadcastReceiver.intentFilter(), Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, LifeUpBroadcastReceiver.intentFilter())
            }

            lifeUpReceiver = receiver
        } catch (ex: Exception) {
            android.util.Log.w("AppLogic", "failed to register LifeUp receiver", ex)
        }
    }

    val manipulationLogic = ManipulationLogic(this)
    val suspendAppsLogic = SuspendAppsLogic(this)
    val shizukuBlockingLogic = ShizukuBlockingLogic(this)
    val workProfileBlockingLogic = WorkProfileBlockingLogic(this)
    val dnsBlockingLogic = DnsBlockingLogic(this)
    val annoyLogic = AnnoyLogic(this)


    fun shutdown() {
        enable.value = false
    }
}
