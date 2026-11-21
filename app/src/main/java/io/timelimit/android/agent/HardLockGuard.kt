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
import io.timelimit.android.data.Database
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.data.model.isHardLock
import io.timelimit.android.sync.actions.AddCategoryAppsAction
import io.timelimit.android.sync.actions.AddCategoryNetworkId
import io.timelimit.android.sync.actions.CreateTimeLimitRuleAction
import io.timelimit.android.sync.actions.DeleteCategoryAction
import io.timelimit.android.sync.actions.DeleteTimeLimitRuleAction
import io.timelimit.android.sync.actions.IncrementCategoryExtraTimeAction
import io.timelimit.android.sync.actions.ParentAction
import io.timelimit.android.sync.actions.RemoveCategoryAppsAction
import io.timelimit.android.sync.actions.RemoveUserAction
import io.timelimit.android.sync.actions.ResetCategoryNetworkIds
import io.timelimit.android.sync.actions.SetCategoryExtraTimeAction
import io.timelimit.android.sync.actions.SetCategoryForUnassignedApps
import io.timelimit.android.sync.actions.SetParentCategory
import io.timelimit.android.sync.actions.SetUserDisableLimitsUntilAction
import io.timelimit.android.sync.actions.UpdateCategoryBatteryLimit
import io.timelimit.android.sync.actions.UpdateCategoryBlockAllNotificationsAction
import io.timelimit.android.sync.actions.UpdateCategoryBlockedTimesAction
import io.timelimit.android.sync.actions.UpdateCategoryDisableLimitsAction
import io.timelimit.android.sync.actions.UpdateCategoryFlagsAction
import io.timelimit.android.sync.actions.UpdateCategoryForceDnsHostnameAction
import io.timelimit.android.sync.actions.UpdateCategorySortingAction
import io.timelimit.android.sync.actions.UpdateCategoryTemporarilyBlockedAction
import io.timelimit.android.sync.actions.UpdateCategoryTimeWarningsAction
import io.timelimit.android.sync.actions.UpdateCategoryTitleAction
import io.timelimit.android.sync.actions.UpdateTimeLimitRuleAction

/**
 * Stops the person holding the phone from editing a hard category.
 *
 * The soft/hard split was a lie for exactly one session: passing the random-character prompt is full
 * parent authority, so `HARD_LOCK` categories could be renamed, emptied or deleted like any other, and
 * re-asserting one field afterwards was theatre. The honest fix is here, at the single place the UI
 * submits parent edits, rather than in the wall: **the action never reaches the database.**
 *
 * Deliberately narrow:
 *  - it refuses, it does not fight. Nothing is reverted behind the user's back; the edit simply does
 *    not happen, and the toast says why.
 *  - the agent's own path is untouched. `AgentCommands`/`HardLock` dispatch straight to
 *    `LocalDatabaseParentActionDispatcher`, so opening a hard category still works -- through the
 *    server, which is the whole design.
 *  - the server can switch the guard off: `hard_lock=false` rides the same `/v1/commands` response the
 *    phone already reads, cached in prefs by [AgentCommands]. With the switch off nothing is refused,
 *    which is the escape if this ever gets in the way. `pm clear` remains the last resort.
 *
 * It cannot stop deleting the app data, and it is not meant to. It stops the edit that takes five
 * seconds at 1am.
 */
object HardLockGuard {
    private const val PREFS = "limited_agent"
    private const val KEY_HARD_LOCK = "hard_lock_enabled"

    /** Last value the server sent. Absent means enabled: the guard fails closed, not open. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HARD_LOCK, true)

    fun cacheServerValue(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HARD_LOCK, enabled)
            .apply()
    }

    /**
     * Returns the title of the hard category this action would change, or null when it may proceed.
     *
     * Call from a background thread: it reads the database synchronously.
     */
    suspend fun refusal(context: Context, action: ParentAction): String? {
        if (!isEnabled(context)) return null
        val database = DefaultAppLogic.with(context).database
        return Threads.database.executeAndWait { refusalSync(action, database) }
    }

    /**
     * The lock screen's "allow temporarily" escape, checked on the database thread.
     *
     * It does not touch a category, so it needs its own check: an app that lives in a hard category
     * must not be exempted for ten minutes from the lock screen any more than its category may be
     * edited from settings.
     */
    fun blocksTemporaryAllowance(context: Context, packageName: String?): Boolean {
        if (packageName == null || !isEnabled(context)) return false

        val database = DefaultAppLogic.with(context).database
        val hardIds = database.category().getCategoryPageSync(offset = 0, pageSize = 10_000)
            .filter { it.isHardLock }
            .map { it.id }
        if (hardIds.isEmpty()) return false

        return database.categoryApp().getCategoryAppSync(hardIds, packageName) != null
    }

    /** Non-suspending core, for callers already on the database thread. */
    fun refusalSync(action: ParentAction, database: Database): String? {
        // Paged read rather than a new DAO query: the app already has a global page getter for its
        // settings list, and one more SELECT would need its own migration story.
        val hard = database.category().getCategoryPageSync(offset = 0, pageSize = 10_000)
            .filter { it.isHardLock }
        if (hard.isEmpty()) return null
        val hardById = hard.associateBy { it.id }

        fun byId(id: String): String? = hardById[id]?.title

        return when (action) {
            is AddCategoryAppsAction -> byId(action.categoryId)
                ?: packagesInHardCategory(action.packageNames, hard.map { it.id }, database)
            is RemoveCategoryAppsAction -> byId(action.categoryId)
            is DeleteCategoryAction -> byId(action.categoryId)
            is UpdateCategoryTitleAction -> byId(action.categoryId)
            is UpdateCategoryForceDnsHostnameAction -> byId(action.categoryId)
            is SetCategoryExtraTimeAction -> byId(action.categoryId)
            is IncrementCategoryExtraTimeAction -> byId(action.categoryId)
            is UpdateCategoryTemporarilyBlockedAction -> byId(action.categoryId)
            is UpdateCategoryTimeWarningsAction -> byId(action.categoryId)
            is UpdateCategoryBatteryLimit -> byId(action.categoryId)
            is UpdateCategoryFlagsAction -> byId(action.categoryId)
            is UpdateCategoryBlockedTimesAction -> byId(action.categoryId)
            is UpdateCategoryBlockAllNotificationsAction -> byId(action.categoryId)
            is UpdateCategoryDisableLimitsAction -> byId(action.categoryId)
            is UpdateCategorySortingAction -> action.categoryIds.firstNotNullOfOrNull { byId(it) }
            is AddCategoryNetworkId -> byId(action.categoryId)
            is ResetCategoryNetworkIds -> byId(action.categoryId)
            is SetParentCategory -> byId(action.categoryId)
            is SetCategoryForUnassignedApps -> byId(action.categoryId)

            // Whole-child levers: "disable limits until" and rule edits reach the hard categories
            // without naming them, so they are refused while any hard category exists.
            is SetUserDisableLimitsUntilAction -> anyHard(hard)
            is CreateTimeLimitRuleAction -> ruleCategory(action.rule.categoryId, database)?.title
            is UpdateTimeLimitRuleAction -> ruleCategoryByRuleId(action.ruleId, database)?.title
            is DeleteTimeLimitRuleAction -> ruleCategoryByRuleId(action.ruleId, database)?.title
            is RemoveUserAction -> anyHard(hard)

            else -> null
        }
    }

    private fun anyHard(hard: List<io.timelimit.android.data.model.Category>): String =
        hard.first().title

    /** Adding an app to another category silently removes it from the hard one. */
    private fun packagesInHardCategory(
        packageNames: List<String>,
        hardIds: List<String>,
        database: Database
    ): String? {
        if (hardIds.isEmpty()) return null
        for (packageName in packageNames) {
            val row = database.categoryApp().getCategoryAppSync(hardIds, packageName) ?: continue
            return database.category().getCategoryByIdSync(row.categoryId)?.title
        }
        return null
    }

    private fun ruleCategory(
        categoryId: String,
        database: Database
    ): io.timelimit.android.data.model.Category? =
        database.category().getCategoryByIdSync(categoryId)?.takeIf { it.isHardLock }

    private fun ruleCategoryByRuleId(
        ruleId: String,
        database: Database
    ): io.timelimit.android.data.model.Category? =
        database.timeLimitRules().getTimeLimitRuleByIdSync(ruleId)
            ?.let { ruleCategory(it.categoryId, database) }
}
