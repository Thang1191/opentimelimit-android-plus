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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.logic.DefaultAppLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Turns a LifeUp countdown into a rule state flip.
 *
 * LifeUp emits `app.lifeup.item.countdown.{start,stop,complete}` when a shop item's countdown runs,
 * is paused, extended or finishes (see LifeUp's `datasource/repository/impl/b`, `sendBoardCastEvent`).
 * The item name rides in `name` (some versions also send `item`/`title`), and only when the user
 * enabled developer-mode broadcasts in LifeUp's lab section.
 *
 * Semantics: while the countdown runs, a rule whose `lifeUpShopItemName` matches is *suspended*
 * (`lifeUpOverrideActive`), so `RemainingTime` skips it and the category is not blocked. Every other
 * event - stop, complete, or a name this version does not know - restores the rule, which brings
 * blocking back.
 *
 * Delivery: LifeUp sends these as implicit broadcasts with no package set, and since API 26 an
 * implicit broadcast does not reach a manifest-registered receiver. `AppLogic` therefore also
 * registers this receiver at runtime, which is not subject to that restriction. The manifest entry
 * stays for ROMs (and older LifeUp builds) that deliver it anyway.
 */
class LifeUpBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val LOG_TAG = "LifeUpIntegration"

        const val ACTION_START = "app.lifeup.item.countdown.start"
        const val ACTION_STOP = "app.lifeup.item.countdown.stop"
        const val ACTION_COMPLETE = "app.lifeup.item.countdown.complete"

        val ACTIONS = arrayOf(ACTION_START, ACTION_STOP, ACTION_COMPLETE)

        fun intentFilter() = IntentFilter().apply {
            ACTIONS.forEach { addAction(it) }
        }

        /** LifeUp names the item differently across versions and event types. */
        private fun itemNameOf(intent: Intent): String? = sequenceOf("item", "name", "title")
            .mapNotNull { intent.getStringExtra(it) }
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }

        /** Milliseconds left in the countdown; LifeUp sends it on every countdown event. */
        private fun timeLeftOf(intent: Intent): Long? = when (val value = intent.extras?.get("time_left")) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val itemName = itemNameOf(intent)

        if (BuildConfig.DEBUG) {
            // Log the raw payload. Without it, a silent early return looks exactly like a broadcast
            // that never arrived - which is the bug this receiver used to hide.
            Log.d(LOG_TAG, "broadcast action=$action item=$itemName extras=${intent.extras}")
        }

        // Only an explicit "start" suspends a rule. Everything else restores it, so an unknown
        // pause/resume variant in a future LifeUp version can never leave blocking switched off.
        val overrideActive = action.endsWith(".start")

        if (itemName.isNullOrBlank()) {
            if (overrideActive) {
                // Cannot know which rule to suspend; blocking stays on, which is the safe side.
                if (BuildConfig.DEBUG) {
                    Log.w(LOG_TAG, "start without an item name, ignoring")
                }

                return
            }

            // A stop/complete without a name is still a real "the countdown ended" signal. Restore
            // every LifeUp-linked rule rather than leaving blocking switched off forever, which is
            // what a name-less pause did before: the rule stayed suspended with nothing to clear it.
            if (BuildConfig.DEBUG) {
                Log.w(LOG_TAG, "broadcast $action has no item name, restoring every LifeUp rule")
            }

            restoreAllLifeUpRules(context, action)

            return
        }

        // A countdown that runs out or is abandoned may never send another broadcast, so the
        // suspension gets a deadline derived from time_left; LifeUpOverrideGuard.reconcile() restores
        // the rule once that deadline passes.
        if (overrideActive) {
            LifeUpOverrideGuard.remember(context, itemName, timeLeftOf(intent))
        } else {
            LifeUpOverrideGuard.forget(context, itemName)
        }

        val appLogic = DefaultAppLogic.with(context.applicationContext)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = appLogic.database.timeLimitRules()
                val rules = dao.getAllRulesSync()
                var modified = 0

                for (rule in rules) {
                    if (!rule.lifeUpShopItemName.equals(itemName, ignoreCase = true)) continue
                    if (rule.lifeUpOverrideActive == overrideActive) continue

                    dao.updateTimeLimitRule(rule.copy(lifeUpOverrideActive = overrideActive))
                    modified++
                }

                if (BuildConfig.DEBUG) {
                    Log.d(
                        LOG_TAG,
                        "$action '$itemName' -> overrideActive=$overrideActive, $modified rule(s) updated"
                    )
                }
            } catch (ex: Exception) {
                Log.w(LOG_TAG, "failed to apply $action for '$itemName'", ex)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun restoreAllLifeUpRules(context: Context, action: String) {
        val appLogic = DefaultAppLogic.with(context.applicationContext)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = appLogic.database.timeLimitRules()
                var restored = 0

                for (rule in dao.getAllRulesSync()) {
                    if (rule.lifeUpShopItemName.isEmpty() || !rule.lifeUpOverrideActive) continue

                    dao.updateTimeLimitRule(rule.copy(lifeUpOverrideActive = false))
                    restored++
                }

                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "$action restored $restored suspended rule(s)")
                }
            } catch (ex: Exception) {
                Log.w(LOG_TAG, "failed to restore rules for $action", ex)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
