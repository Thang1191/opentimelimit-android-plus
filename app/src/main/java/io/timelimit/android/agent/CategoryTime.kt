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
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.sync.actions.IncrementCategoryExtraTimeAction
import io.timelimit.android.sync.actions.dispatch.LocalDatabaseParentActionDispatcher
import java.time.LocalDate
import java.util.TimeZone

/**
 * Time, as a parent action.
 *
 * A hard category has two ways to be shut: the wall the agent renews, and its own time budget. The
 * wall is what `release` opens -- but opening the wall on a category whose time is spent leaves the
 * app just as blocked, for the other reason, and the user reads that as "the unlock did not work".
 * So a targeted release hands out the same number of minutes as time as it does as window: one
 * action, one meaning.
 *
 * This is the app's own `category.extra_time` column, per day, so it is spent like any extra time the
 * user grants from the lock screen and it is gone when the day turns. Nothing here edits a rule: the
 * limit itself is untouched, and once the granted time is used the category closes again on its own.
 */
object CategoryTime {
    private const val TAG = "limited-sync"

    /**
     * Adds [minutes] of extra time for today to [categoryId] through the parent's own action.
     * Returns a one-line summary, or throws when the phone is not set up to dispatch it.
     */
    suspend fun addToday(context: Context, categoryId: String, minutes: Int): String =
        Threads.database.executeAndWait {
            val database = DefaultAppLogic.with(context).database

            val category = database.category().getCategoryByIdSync(categoryId)
                ?: throw IllegalStateException("category $categoryId is not in the database")

            val parentId = database.user().getParentUsersSync().firstOrNull()?.id
            val day = LocalDate.now(TimeZone.getDefault().toZoneId()).toEpochDay().toInt()

            database.runInTransaction {
                LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                    IncrementCategoryExtraTimeAction(
                        categoryId = categoryId,
                        addedExtraTime = minutes * 60_000L,
                        extraTimeDay = day
                    ),
                    database = database,
                    fromChildSelfLimitAddChildUserId = null,
                    parentUserId = parentId
                )
            }

            "+${minutes}m on '${category.title}'"
        }
}
