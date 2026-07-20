/*
 * TimeLimit Copyright <C> 2019 - 2022 Jonas Lochmann
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

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.util.SparseArray
import androidx.core.util.size
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.integration.platform.ForegroundApp
import io.timelimit.android.integration.platform.RuntimePermissionStatus
import io.timelimit.android.integration.platform.android.foregroundapp.usagestats.DirectUsageStatsReader
import io.timelimit.android.integration.platform.android.foregroundapp.usagestats.UsageStatsConstants

@TargetApi(Build.VERSION_CODES.Q)
class InstanceIdForegroundAppHelper(context: Context): UsageStatsForegroundAppHelper(context) {
    companion object {
        const val START_QUERY_INTERVAL = 1000 * 60 * 60 * 24 * 3 // 3 days
        private const val TOLERANCE = 3000L
    }

    private var lastQueryTime = 0L
    private var lastEventTimestamp = 0L
    private val apps = SparseArray<ForegroundApp>()

    override suspend fun getForegroundApps(
        queryInterval: Long,
        experimentalFlags: Long
    ): Set<ForegroundApp> {
        if (getPermissionStatus() != RuntimePermissionStatus.Granted) {
            throw SecurityException()
        }

        val result = backgroundThread.executeAndWait {
            val now = System.currentTimeMillis()

            val didTimeWentBackwards = lastQueryTime > now
            val didNeverQuery = lastQueryTime == 0L
            val shouldDoFullQuery = didTimeWentBackwards || didNeverQuery

            if (shouldDoFullQuery) {
                apps.clear()
            }

            val minQueryStartTime = (now - START_QUERY_INTERVAL).coerceAtLeast(1)
            val queryStartTimeByLastEvent = lastEventTimestamp - TOLERANCE

            val queryStartTime = if (shouldDoFullQuery) {
                minQueryStartTime
            } else {
                queryStartTimeByLastEvent
                    .coerceAtLeast(minQueryStartTime)
                    .coerceAtMost(now - TOLERANCE)
            }

            val queryEndTime = now + TOLERANCE

            android.util.Log.d("InstanceIdHelper", "queryEvents: startTime=$queryStartTime, endTime=$queryEndTime, shouldDoFullQuery=$shouldDoFullQuery")
            val nativeEvents = usageStatsManager.queryEvents(queryStartTime, queryEndTime)
            if (nativeEvents == null) {
                android.util.Log.d("InstanceIdHelper", "nativeEvents is null")
            } else {
                val events = DirectUsageStatsReader(nativeEvents)

                try {
                    var isFirstEvent = true
                    var eventCount = 0
                    var moveToForegroundCount = 0

                    while (events.loadNextEvent()) {
                        eventCount++
                        // check the consistency
                        if (events.timestamp < lastEventTimestamp - TOLERANCE && !isFirstEvent) {
                            throw InstanceIdException.EventsNotSortedByTimestamp(
                                currentMin = lastEventTimestamp,
                                eventTimestamp = events.timestamp
                            )
                        }

                        // process the event
                        if (events.eventType == UsageStatsConstants.DEVICE_STARTUP) {
                            apps.clear()
                        } else if (events.eventType == UsageStatsConstants.MOVE_TO_FOREGROUND) {
                            moveToForegroundCount++
                            val app = ForegroundApp(events.packageName, events.className)

                            apps.put(events.instanceId, app)
                        } else if (
                            events.eventType == UsageStatsConstants.MOVE_TO_BACKGROUND ||
                            events.eventType == UsageStatsConstants.ACTIVITY_STOPPED
                        ) {
                            apps.remove(events.instanceId)
                        }

                        // save values for the next iteration and the next query
                        if (isFirstEvent || events.timestamp > lastEventTimestamp) lastEventTimestamp = events.timestamp
                        isFirstEvent = false
                    }
                    android.util.Log.d("InstanceIdHelper", "processed $eventCount events, $moveToForegroundCount were MOVE_TO_FOREGROUND")
                } finally {
                    events.free()
                }
            }

            lastQueryTime = now

            val appsSet = mutableSetOf<ForegroundApp>()

            for (index in 0 until apps.size) {
                appsSet.add(apps.valueAt(index))
            }

            android.util.Log.d("InstanceIdHelper", "getForegroundApps: found ${appsSet.size} foreground apps. Current map: $appsSet")

            appsSet
        }

        return result
    }

    sealed class InstanceIdException(message: String): RuntimeException(message) {
        class EventsNotSortedByTimestamp(currentMin: Long, eventTimestamp: Long): InstanceIdException(
            "events not sorted by timestamp; expected at least $currentMin but got $eventTimestamp"
        )
    }
}