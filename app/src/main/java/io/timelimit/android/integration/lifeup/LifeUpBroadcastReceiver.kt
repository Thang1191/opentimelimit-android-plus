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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.logic.DefaultAppLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.annotation.RequiresApi
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

        private val ACTIONS = arrayOf(ACTION_START, ACTION_STOP, ACTION_COMPLETE)

        private const val LIFEUP_PACKAGE = "net.sarasarasa.lifeup"

        fun intentFilter() = IntentFilter().apply {
            ACTIONS.forEach { addAction(it) }
        }

        /** LifeUp names the item differently across versions and event types. */
        private fun itemNameOf(intent: Intent): String? = sequenceOf("item", "name", "title")
            .mapNotNull { intent.getStringExtra(it) }
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }

        /**
         * Adding time to a running countdown makes LifeUp emit stop and then start about a second
         * apart. Restoring blocking on that stop alone makes the block flicker off and straight back
         * on, so a stop only counts once this long has passed without a start for the same item.
         */
        private const val RESTORE_DEBOUNCE_MS = 1500L

        private val mainHandler = Handler(Looper.getMainLooper())

        /** Item name -> the restore that is waiting out the debounce. */
        private val pendingRestores = mutableMapOf<String, Runnable>()

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

        if (!isFromLifeUp(context)) {
            // Without this, anything that can send an intent on this device could name a shop item
            // and switch its rule off -- which is exactly what the hard lock exists to prevent. It
            // was reachable from `adb shell` before this check existed.
            Log.w(LOG_TAG, "ignoring $action from an untrusted sender")
            return
        }

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

        val appContext = context.applicationContext
        val key = itemName.lowercase()

        // A start for the same item means the stop right before it was not the end of the countdown.
        pendingRestores.remove(key)?.let {
            mainHandler.removeCallbacks(it)

            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "$action '$itemName' cancelled the pending restore")
            }
        }

        // A countdown that runs out or is abandoned may never send another broadcast, so the
        // suspension gets a deadline derived from time_left; LifeUpOverrideGuard.reconcile() restores
        // the rule once that deadline passes.
        if (overrideActive) {
            LifeUpOverrideGuard.remember(appContext, itemName, timeLeftOf(intent))

            applyOverride(appContext, action, itemName, true, goAsync())
        } else {
            val restore = Runnable {
                pendingRestores.remove(key)

                LifeUpOverrideGuard.forget(appContext, itemName)

                applyOverride(appContext, action, itemName, false, null)
            }

            pendingRestores[key] = restore
            mainHandler.postDelayed(restore, RESTORE_DEBOUNCE_MS)

            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "$action '$itemName' -> restore in ${RESTORE_DEBOUNCE_MS}ms")
            }
        }
    }

    /**
     * True only when the broadcast actually came from LifeUp.
     *
     * The receiver is exported because LifeUp is a separate app, and it is registered at runtime as
     * exported for the same reason, so the platform does not restrict the sender for us. The UID is
     * the only trustworthy part of a broadcast: the package name lives in an extra and extras are
     * whatever the sender wants.
     */
    private fun isFromLifeUp(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Below API 34 a receiver cannot learn who sent a broadcast. Accepting keeps the
            // integration working on older devices; the permanent fix is a signature-level
            // permission on the receiver, which needs one uses-permission line in LifeUp's manifest
            // (both apps are signed with the same key). Tracked in docs/android-audit.md.
            if (BuildConfig.DEBUG) {
                Log.w(LOG_TAG, "sender cannot be verified below API 34, accepting")
            }

            return true
        }

        return isFromLifeUpApi34(context)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun isFromLifeUpApi34(context: Context): Boolean {
        val senderUid = try {
            sentFromUid
        } catch (ex: Throwable) {
            -1
        }

        if (senderUid <= 0) {
            if (BuildConfig.DEBUG) {
                Log.w(LOG_TAG, "broadcast has no sender uid, ignoring")
            }

            return false
        }

        val packageManager = context.packageManager
        val lifeUpUid = try {
            packageManager.getPackageUid(LIFEUP_PACKAGE, 0)
        } catch (ex: PackageManager.NameNotFoundException) {
            Log.w(LOG_TAG, "$LIFEUP_PACKAGE is not installed, ignoring LifeUp events")

            return false
        }

        if (senderUid != lifeUpUid) {
            if (BuildConfig.DEBUG) {
                Log.w(LOG_TAG, "sender uid $senderUid is not LifeUp ($lifeUpUid), ignoring")
            }

            return false
        }

        // One uid can own several packages after an upgrade, so the uid alone is not the identity.
        return packageManager.getPackagesForUid(senderUid)?.contains(LIFEUP_PACKAGE) == true
    }

    /**
     * @param pendingResult only set when called straight from [onReceive]; a debounced restore runs
     *        long after the broadcast finished and has nothing to report back.
     */
    private fun applyOverride(
        context: Context,
        action: String,
        itemName: String,
        overrideActive: Boolean,
        pendingResult: PendingResult?
    ) {
        val appLogic = DefaultAppLogic.with(context.applicationContext)

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
        // Nothing is left to wait for: this restore covers every item anyway.
        pendingRestores.values.forEach { mainHandler.removeCallbacks(it) }
        pendingRestores.clear()

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
