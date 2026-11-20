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
import io.timelimit.android.integration.platform.shizuku.ShizukuIntegration
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
    private const val MARK_PREFIX = "since_"

    /** Used when a start arrives without `time_left`, so a rule can still never be suspended forever. */
    private const val UNKNOWN_DURATION_FALLBACK = 4 * 60 * 60 * 1000L

    /**
     * The probe is ~40ms, so it can run on every reconcile pass: ending a countdown early broadcasts
     * nothing and this probe is the only thing that notices, so the cadence *is* the responsiveness.
     */
    private const val SERVICE_CHECK_INTERVAL = 1000L

    /**
     * Counts LifeUp's countdown services. `|| true` because grep exits 1 on zero matches and the
     * shell UserService reports a non-zero exit as a failed command.
     */
    private const val COUNTDOWN_SERVICE_PROBE =
        "dumpsys activity services net.sarasarasa.lifeup | grep -c CountDownTimerService || true"

    /** A countdown that just started may not have its service up yet - don't judge it too early. */
    private const val SERVICE_CHECK_GRACE = 5 * 1000L

    @Volatile
    private var lastServiceCheck = 0L

    @Volatile
    private var countdownServiceRunning = true

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(itemName: String) = KEY_PREFIX + itemName.lowercase()

    private fun markKey(itemName: String) = MARK_PREFIX + itemName.lowercase()

    /** Records how long the running countdown may still suspend a rule. */
    fun remember(context: Context, itemName: String, remainingMillis: Long?) {
        val duration = when {
            remainingMillis == null || remainingMillis <= 0 -> UNKNOWN_DURATION_FALLBACK
            else -> remainingMillis
        }

        val now = System.currentTimeMillis()

        prefs(context).edit()
            .putLong(key(itemName), now + duration)
            .putLong(markKey(itemName), now)
            .apply()
    }

    /** The countdown paused or finished on its own; there is nothing left to wait for. */
    fun forget(context: Context, itemName: String) {
        prefs(context).edit()
            .remove(key(itemName))
            .remove(markKey(itemName))
            .apply()
    }

    /**
     * Whether LifeUp still has a countdown service alive.
     *
     * Ending a countdown early broadcasts nothing at all, but it does tear the service down, so this
     * is the only instant "it is over" signal available. Optimistic on every uncertainty: a missing
     * or unreadable answer must never restore a rule while a countdown may still be running.
     */
    private fun isCountdownServiceRunning(): Boolean {
        if (!ShizukuIntegration.isShizukuAvailable()) return true

        val now = System.currentTimeMillis()
        if (now - lastServiceCheck < SERVICE_CHECK_INTERVAL) return countdownServiceRunning

        lastServiceCheck = now
        val output = ShizukuIntegration.executeCommand(COUNTDOWN_SERVICE_PROBE)?.trim()

        countdownServiceRunning = when {
            output.isNullOrEmpty() -> true
            else -> (output.toIntOrNull() ?: 1) > 0
        }

        return countdownServiceRunning
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
            val startedAt = if (itemName.isEmpty()) 0L else prefs.getLong(markKey(itemName), 0L)

            // No deadline means the countdown this rule waited for is gone (paused, ended, app data
            // cleared) - a suspension with no known end has to be treated as over.
            val deadlineOver = deadline == 0L || now >= deadline

            // Ending a countdown early emits nothing, so ask the device instead: once LifeUp's
            // countdown service is gone, the suspension has nothing left to justify it.
            val serviceGone = !deadlineOver &&
                now - startedAt > SERVICE_CHECK_GRACE &&
                !isCountdownServiceRunning()

            if (deadlineOver || serviceGone) {
                dao.updateTimeLimitRule(rule.copy(lifeUpOverrideActive = false))

                if (deadline != 0L) forget(appLogic.context, itemName)

                restored++
            }
        }

        return restored
    }
}
