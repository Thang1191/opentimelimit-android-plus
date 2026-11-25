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
import io.timelimit.android.data.Database
import io.timelimit.android.data.extensions.getTimezone
import io.timelimit.android.data.model.isHardLock
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.data.model.CategoryFlags
import io.timelimit.android.date.DateInTimezone
import io.timelimit.android.sync.actions.UpdateCategoryFlagsAction
import io.timelimit.android.sync.actions.UpdateCategoryTemporarilyBlockedAction
import io.timelimit.android.sync.actions.dispatch.LocalDatabaseParentActionDispatcher

/**
 * The hard half of the soft/hard split.
 *
 * A hard category is marked with [io.timelimit.android.data.model.CategoryFlags.HARD_LOCK] and the
 * phone keeps a wall on it: a `temporarily_blocked` window, renewed long before it could ever lapse.
 * Opening one locally still works -- the app has no way to forbid it, and pretending otherwise would
 * be a lie -- but the wall comes back within about half a minute unless the server granted opening.
 *
 * That is the whole design, and it is deliberately a fight rather than a lock: the point is that
 * giving in costs a visible round trip through the server, not that the tap fails. What keeps it from
 * being a trap:
 *
 *  - a grant (`release` with a `target`) takes the flag off, so the category is soft for its window
 *    and [reassert] puts it back -- which is also what keeps the wall from being the only thing that
 *    distinguishes hard from soft;
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
    private const val KEY_SOFTENED = "hard_softened"

    /**
     * How far ahead the wall is pushed: a day, and it is renewed well before it can lapse.
     *
     * The first version used sixty-second slices, which turned the wall into a heartbeat: every few
     * minutes the slice either expired (activities briefly unblocked, apps were unsuspended) or got
     * rewritten (every observer refires, the blocking logic reruns). Both sides of that alternation
     * are visible work for Android, and together they were the periodic lag spike the user felt. A
     * long-lived wall makes the block a fact in the database instead of a race against the ticker.
     */
    private const val WALL_MS = 24L * 60 * 60_000L

    /** Refresh the wall while this much lifetime remains, so it never reaches zero. */
    private const val WALL_REFRESH_MS = 60 * 60_000L

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
        val now = System.currentTimeMillis()
        val changed = mutableListOf<String>()

        // A released hard category is a soft one until its window ends. Putting the flag back is the
        // other half of that promise: without it a temporary release would quietly become permanent
        // and the wall would never come back.
        val softenedNow = softened(context)
        val stillSoft = mutableMapOf<String, Long>()
        for ((softId, until) in softenedNow) {
            if (until == 0L || until > now) {
                stillSoft[softId] = until
                continue
            }
            val category = database.category().getCategoryByIdSync(softId) ?: continue
            if (!category.isHardLock) {
                database.runInTransaction {
                    LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                        UpdateCategoryFlagsAction(
                            categoryId = softId,
                            modifiedBits = CategoryFlags.HARD_LOCK,
                            newValues = CategoryFlags.HARD_LOCK
                        ),
                        database = database,
                        fromChildSelfLimitAddChildUserId = null,
                        parentUserId = parentId
                    )
                }
                changed += "hardened '${category.title}' again"
            }
        }
        if (stillSoft.size != softenedNow.size) writeSoftened(context, stillSoft)

        val hardCategories = database.category().getCategoriesByChildIdSync(childId)
            .filter { it.isHardLock }

        // A grant is access. While a category still has extra time for today, walling it would shut
        // the very minutes it was just given: the user reads "17 minutes left today" and watches the
        // app refuse to open, which is the report that produced this branch. So the budget is the
        // limit while any of it remains, and the wall only comes back once the day's extra time is
        // spent -- at which point the rule blocks on its own and the wall is the belt to that brace.
        val day = todayKey(context, database)

        for (category in hardCategories) {
            val grantedLeft = if (day >= 0) category.getExtraTime(day) else 0L

            if (grantedLeft > 0) {
                val walled = category.temporarilyBlocked && category.temporarilyBlockedEndTime > now
                if (walled) {
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
                    changed += "opened '${category.title}' (${grantedLeft / 60_000L}m extra left today)"
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

    /**
     * The user's own calendar day, so "extra time today" means the same day the phone shows -- the
     * budget is keyed on it, and a mismatch would silently read as "no grant left".
     */
    private fun todayKey(context: Context, database: Database): Int {
        val deviceId = database.config().getOwnDeviceIdSync() ?: return -1
        val childId = database.device().getDeviceByIdSync(deviceId)?.currentUserId.orEmpty()
        if (childId.isEmpty()) return -1

        val child = database.user().getUserByIdSync(childId)
        val now = DefaultAppLogic.with(context).timeApi.getCurrentTimeInMillis()

        return DateInTimezone.newInstance(now, child.getTimezone()).dayOfEpoch
    }

    /**
     * Turns a hard category into a soft one.
     *
     * The point is that "unlock" has to change what the user can do, not only what the wall does: a
     * released category still showed the hard-locked label, kept its switch disabled and kept refusing
     * edits, which is indistinguishable from a failed unlock. So the flag itself comes off -- the
     * category becomes soft, the random-character prompt is the only friction -- and for a temporary
     * release [reassert] puts the flag back when the window ends.
     */
    suspend fun soften(context: Context, categoryId: String, minutes: Int, permanent: Boolean): String =
        Threads.database.executeAndWait {
            val database = DefaultAppLogic.with(context).database
            val category = database.category().getCategoryByIdSync(categoryId)
                ?: throw IllegalStateException("category $categoryId is not in the database")

            // Whether the flag is ours to put back. A release aimed at a soft category is only a
            // wall being cleared -- and remembering it as "softened" would make [reassert] harden a
            // category the user never marked, minutes later, out of nowhere.
            val wasHard = category.isHardLock

            val parentId = database.user().getParentUsersSync().firstOrNull()?.id
            database.runInTransaction {
                // Drop the wall first: leaving a temporary block behind would keep the app shut
                // after the user was told it was open.
                LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                    UpdateCategoryTemporarilyBlockedAction(
                        categoryId = categoryId, blocked = false, endTime = null
                    ),
                    database = database,
                    fromChildSelfLimitAddChildUserId = null,
                    parentUserId = parentId
                )
                if (wasHard) {
                    LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                        UpdateCategoryFlagsAction(
                            categoryId = categoryId,
                            modifiedBits = CategoryFlags.HARD_LOCK,
                            newValues = 0
                        ),
                        database = database,
                        fromChildSelfLimitAddChildUserId = null,
                        parentUserId = parentId
                    )
                }
            }

            if (!wasHard) {
                return@executeAndWait "'${category.title}' was already soft; wall cleared, " +
                    "use allowance or time to move its budget"
            }

            val until = if (permanent) 0L else System.currentTimeMillis() + minutes * 60_000L
            val current = softened(context).toMutableMap()
            current[categoryId] = until
            writeSoftened(context, current)

            if (permanent) "turned '${category.title}' into a soft lock permanently"
            else "turned '${category.title}' into a soft lock for ${minutes}m " +
                "(hard again at ${stamp(until)})"
        }

    /**
     * Makes a category hard for good: the flag goes on and stays on, and any release window still
     * running is dropped, because "hard" and "temporarily soft" cannot both be true.
     *
     * Without this the only way to harden a category was editing HARD_CATEGORY_TITLES and rebuilding
     * the APK -- which is a poor lever, since the whole point of the split is that the server decides.
     */
    suspend fun harden(context: Context, categoryId: String): String =
        Threads.database.executeAndWait {
            val database = DefaultAppLogic.with(context).database
            val category = database.category().getCategoryByIdSync(categoryId)
                ?: throw IllegalStateException("category $categoryId is not in the database")

            val soft = softened(context).toMutableMap()
            if (soft.remove(categoryId) != null) writeSoftened(context, soft)

            if (category.isHardLock) {
                "'${category.title}' was already hard locked"
            } else {
                val parentId = database.user().getParentUsersSync().firstOrNull()?.id
                database.runInTransaction {
                    LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                        UpdateCategoryFlagsAction(
                            categoryId = categoryId,
                            modifiedBits = CategoryFlags.HARD_LOCK,
                            newValues = CategoryFlags.HARD_LOCK
                        ),
                        database = database,
                        fromChildSelfLimitAddChildUserId = null,
                        parentUserId = parentId
                    )
                }
                "'${category.title}' is now hard locked by the server"
            }
        }

    private fun softened(context: Context): Map<String, Long> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOFTENED, "")
            .orEmpty()
        if (raw.isEmpty()) return emptyMap()

        return raw.split(',')
            .mapNotNull { entry ->
                val id = entry.substringBefore(':')
                val until = entry.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
                if (id.isEmpty()) null else id to until
            }
            .toMap()
    }

    private fun writeSoftened(context: Context, value: Map<String, Long>) {
        val raw = value.entries.joinToString(",") { "${it.key}:${it.value}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOFTENED, raw)
            .apply()
    }

    private fun stamp(millis: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(millis))
}
