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
package io.timelimit.android.integration.lifeup

import android.content.Context
import io.timelimit.android.logic.AppLogic

/**
 * Keeps a LifeUp countdown from suspending a rule forever.
 *
 * LifeUp does not broadcast every way a countdown can end. Observed on 20/11/2026 (Ace, LifeUp with
 * developer-mode broadcasts on), for one item:
 *
 * - start / resume / add time -> `app.lifeup.item.countdown.start`, extras `{name, time_left, item_id}`
 * - pause                     -> `app.lifeup.item.countdown.stop`, extras carry the leftover `time_left`
 * - **end (abandon)**        -> nothing at all, and `app.lifeup.item.countdown.complete` never fires
 *
 * So a rule suspended by a start had no event to restore it and blocking stayed off indefinitely.
 * Every event does carry `time_left`, which is enough to know when the countdown *must* be over:
 * [remember] stores `now + time_left` per item, and [reconcile] restores any suspended rule whose
 * deadline has passed (or that has no deadline at all).
 *
 * Consequence to be honest about: ending a countdown early is detected at its deadline, not instantly.
 */
object LifeUpOverrideGuard {
    private const val PREFS_NAME = "lifeup_override"
    private const val KEY_PREFIX = "deadline_"

    /** Used when a start arrives without `time_left`, so a rule can still never be suspended forever. */
    private const val UNKNOWN_DURATION_FALLBACK = 4 * 60 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(itemName: String) = KEY_PREFIX + itemName.lowercase()

    /** Records how long the running countdown may still suspend a rule. */
    fun remember(context: Context, itemName: String, remainingMillis: Long?) {
        val duration = when {
            remainingMillis == null || remainingMillis <= 0 -> UNKNOWN_DURATION_FALLBACK
            else -> remainingMillis
        }

        prefs(context).edit()
            .putLong(key(itemName), System.currentTimeMillis() + duration)
            .apply()
    }

    /** The countdown paused or finished on its own; there is nothing left to wait for. */
    fun forget(context: Context, itemName: String) {
        prefs(context).edit().remove(key(itemName)).apply()
    }

    /**
     * Restores rules that a LifeUp countdown suspended but can no longer justify. Cheap enough to run
     * from the background loop: it reads the rules and only touches rows that are actually suspended.
     *
     * @return number of rules restored
     */
    fun reconcile(appLogic: AppLogic): Int {
        val dao = appLogic.database.timeLimitRules()
        val now = System.currentTimeMillis()
        val prefs = prefs(appLogic.context)
        var restored = 0

        for (rule in dao.getAllRulesSync()) {
            if (!rule.lifeUpOverrideActive) continue

            val itemName = rule.lifeUpShopItemName
            val deadline = if (itemName.isEmpty()) 0L else prefs.getLong(key(itemName), 0L)

            // No deadline means the countdown this rule waited for is gone (paused, ended, app data
            // cleared) - a suspension with no known end has to be treated as over.
            if (deadline == 0L || now >= deadline) {
                dao.updateTimeLimitRule(rule.copy(lifeUpOverrideActive = false))

                if (deadline != 0L) prefs.edit().remove(key(itemName)).apply()

                restored++
            }
        }

        return restored
    }
}
