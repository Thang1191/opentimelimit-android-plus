/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 */
package io.timelimit.android.agent

import android.content.Context
import io.timelimit.android.async.Threads
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.sync.actions.SetUserDisableLimitsUntilAction
import io.timelimit.android.sync.actions.dispatch.LocalDatabaseParentActionDispatcher

/**
 * The agent borrowing the parent's authority, through the parent's own action.
 *
 * The app already has exactly the thing a remote unlock needs: [SetUserDisableLimitsUntilAction],
 * the parent's "disable time limits until <time>", which lands in `user.disableLimitsUntil`.
 * [io.timelimit.android.logic.blockingreason.CategoryItselfHandling] already treats that timestamp as
 * "no limits right now", and it does so *by comparison with the clock* — so the override expires on
 * its own, survives a reboot, and shows up in the UI as the override it is.
 *
 * That is why this exists instead of the private flag the first version of the release lever used.
 * A bespoke hold would have to be re-implemented for every strategy that can block (overlay,
 * Shizuku suspend, DNS, work profile) and would keep blocking after the agent's window unless each
 * one was taught about it. One dispatched parent action covers all of them, because they all read
 * the same derived data.
 *
 * The action is dispatched locally rather than through a sync server: this phone *is* the child, its
 * database holds the parent user and the password hash, and the sync path would only add a round trip
 * to the same transaction.
 */
object ParentUnlock {
    /**
     * Disables limits for the child user of this device until [untilMillis] (wall clock, not uptime:
     * the value is persisted and compared against [io.timelimit.android.integration.time.TimeApi]).
     *
     * Throws when the device is not set up as a child with a parent, and the caller must surface that
     * as a failed ack rather than a silent success. Returns a line describing what it changed.
     */
    suspend fun disableLimitsUntil(context: Context, untilMillis: Long): String =
        Threads.database.executeAndWait {
            val database = DefaultAppLogic.with(context).database

            val deviceId = database.config().getOwnDeviceIdSync()
                ?: throw IllegalStateException("this phone has no own device id")

            val device = database.device().getDeviceByIdSync(deviceId)
                ?: throw IllegalStateException("device $deviceId is not in the database")

            val childId = device.currentUserId
            if (childId.isEmpty()) {
                throw IllegalStateException("no user is active on this device")
            }

            val child = database.user().getUserByIdSync(childId)
                ?: throw IllegalStateException("user $childId is missing")

            // Null is fine: the dispatcher does not authenticate local parent actions, and a device
            // with no parent row is a self-limit setup where the child owns its own limits.
            val parentId = database.user().getParentUsersSync().firstOrNull()?.id

            database.runInTransaction {
                LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                    SetUserDisableLimitsUntilAction(childId = childId, timestamp = untilMillis),
                    database = database,
                    fromChildSelfLimitAddChildUserId = null,
                    parentUserId = parentId
                )
            }

            val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(untilMillis))
            "parent unlock on '${child.name}' (${device.name}) until $clock"
        }
}
