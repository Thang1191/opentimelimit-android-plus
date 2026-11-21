/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 */
package io.timelimit.android.agent

import android.content.Context
import android.util.Log
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.data.model.isHardLock
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.sync.actions.UpdateCategoryTemporarilyBlockedAction
import io.timelimit.android.sync.actions.dispatch.LocalDatabaseParentActionDispatcher

/**
 * The hard half of the soft/hard split.
 *
 * A hard category is marked with [io.timelimit.android.data.model.CategoryFlags.HARD_LOCK] and the
 * phone keeps a wall on it: a `temporarily_blocked` window that is renewed before it lapses. Opening
 * one locally still works -- the app has no way to forbid it, and pretending otherwise would be a
 * lie -- but the wall comes back within about half a minute unless the server granted the opening.
 *
 * That is the whole design, and it is deliberately a fight rather than a lock: the point is that
 * giving in costs a visible round trip through the server, not that the tap fails. What keeps it from
 * being a trap:
 *
 *  - a grant (`release` with a `target`) opens the category for its window and the wall stays down
 *    for exactly that long, so anything the server authorised is left alone;
 *  - the wall only ever touches `temporarily_blocked` on the marked category. It never writes the
 *    user-level override or `disable_limits_until`, so a granted release of the whole device still
 *    holds;
 *  - `hard_lock` can be switched off entirely from the server (`POST /v1/hardlock`), which is the
 *    kill switch if this ever behaves badly;
 *  - every reassert is reported back as an event, so nothing about it is silent.
 */
object HardLock {
    private const val TAG = "limited-sync"
    private const val PREFS = "limited_agent"
    private const val KEY_GRANTS = "hard_grants"

    /**
     * How far ahead each reassert pushes the wall. Twice the watcher's cadence, so one slow poll does
     * not open a hole in the wall, while staying short enough that "unblocked" is never a fiction.
     */
    private const val WALL_MS = 60_000L

    /** Below this much remaining wall, refresh it early rather than wait for it to expire. */
    private const val WALL_REFRESH_MS = 20_000L

    /**
     * Aligns the marked categories with the wall and with any grant. Returns one line per category it
     * changed, which the caller reports as events; an empty list means nothing needed doing.
     */
    suspend fun reassert(context: Context): List<String> = Threads.database.executeAndWait {
        val database = DefaultAppLogic.with(context).database

        val deviceId = database.config().getOwnDeviceIdSync() ?: return@executeAndWait emptyList()
        val device = database.device().getDeviceByIdSync(deviceId) ?: return@executeAndWait emptyList()
        val childId = device.currentUserId
        if (childId.isEmpty()) return@executeAndWait emptyList()

        val parentId = database.user().getParentUsersSync().firstOrNull()?.id
        val grants = grants(context)
        val now = System.currentTimeMillis()
        val changed = mutableListOf<String>()

        val hardCategories = database.category().getCategoriesByChildIdSync(childId)
            .filter { it.isHardLock }

        for (category in hardCategories) {
            val grantedUntil = grants[category.id] ?: 0L

            if (grantedUntil > now) {
                // The server opened this one; make sure the wall is actually down for the window and
                // leave it alone until it expires.
                if (category.temporarilyBlocked) {
                    database.runInTransaction {
                        LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                            UpdateCategoryTemporarilyBlockedAction(
                                categoryId = category.id,
                                blocked = false,
                                endTime = null
                            ),
                            database = database,
                            fromChildSelfLimitAddChildUserId = null,
                            parentUserId = parentId
                        )
                    }
                    changed += "opened '${category.title}' until ${stamp(grantedUntil)}"
                }
                continue
            }

            val wallHolds = category.temporarilyBlocked &&
                category.temporarilyBlockedEndTime - now > WALL_REFRESH_MS
            if (wallHolds) continue

            database.runInTransaction {
                LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                    UpdateCategoryTemporarilyBlockedAction(
                        categoryId = category.id,
                        blocked = true,
                        endTime = now + WALL_MS
                    ),
                    database = database,
                    fromChildSelfLimitAddChildUserId = null,
                    parentUserId = parentId
                )
            }
            changed += "reasserted '${category.title}'"
        }

        if (changed.isNotEmpty()) {
            Log.d(TAG, "agent: hard lock ${changed.joinToString("; ")}")
        }
        changed
    }

    /** Opens one category for `minutes`. Called when a `release` carries a target. */
    fun grant(context: Context, categoryId: String, minutes: Int) {
        val until = System.currentTimeMillis() + minutes.toLong() * 60_000L
        val current = grants(context).toMutableMap()
        current[categoryId] = until
        writeGrants(context, current)
    }

    private fun stamp(millis: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(millis))

    private fun grants(context: Context): Map<String, Long> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GRANTS, "")
            .orEmpty()
        if (raw.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        return raw.split(',')
            .mapNotNull { entry ->
                val id = entry.substringBefore(':')
                val until = entry.substringAfter(':', "").toLongOrNull()
                // Drop what has expired while reading, so the pref cannot grow forever.
                if (id.isEmpty() || until == null || until <= now) null else id to until
            }
            .toMap()
    }

    private fun writeGrants(context: Context, grants: Map<String, Long>) {
        val value = grants.entries.joinToString(",") { "${it.key}:${it.value}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GRANTS, value)
            .apply()
    }
}
