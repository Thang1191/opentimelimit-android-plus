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
package io.timelimit.android.integration.platform.android.foregroundapp

import android.content.Context
import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.integration.platform.ForegroundApp
import io.timelimit.android.integration.platform.android.foregroundapp.usagestats.DirectUsageStatsReader

class QForegroundAppHelper(context: Context) : UsageStatsForegroundAppHelper(context) {
    companion object {
        private const val LOG_TAG = "QForegroundApp"
    }

    private val legacy = LollipopForegroundAppHelper(context)
    private val modern = InstanceIdForegroundAppHelper(context)

    /**
     * FORK: upstream selects the helper with
     * `val canUseModern = DirectUsageStatsReader.instanceIdSupported`.
     * The fork had that hard-wired to `false` ("FORCE LEGACY HELPER FOR DEBUGGING"), which pinned every
     * API 29+ device to the legacy usage-events path even where instance ids are available.
     *
     * The instance-id path is kept as the preferred one, but it never takes the decision down with it:
     * if it throws or comes back empty, the legacy helper answers instead. An empty answer from a broken
     * reflection would otherwise leave blocking blind, which on a device without device owner means no
     * blocking at all.
     */
    override suspend fun getForegroundApps(
        queryInterval: Long,
        experimentalFlags: Long
    ): Set<ForegroundApp> {
        if (DirectUsageStatsReader.instanceIdSupported) {
            try {
                val modernResult = modern.getForegroundApps(queryInterval, experimentalFlags)

                if (modernResult.isNotEmpty()) {
                    if (BuildConfig.DEBUG) {
                        Log.d(LOG_TAG, "instance-id helper reported ${modernResult.size} app(s)")
                    }

                    return modernResult
                }
            } catch (ex: Exception) {
                // reflection into UsageEvents.Event#getInstanceId is not guaranteed on vendor ROMs
                if (BuildConfig.DEBUG) {
                    Log.w(LOG_TAG, "instance-id helper failed, using legacy helper", ex)
                }
            }
        }

        return legacy.getForegroundApps(queryInterval, experimentalFlags)
    }
}
