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
import io.timelimit.android.BuildConfig
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.data.model.CategoryFlags
import io.timelimit.android.data.model.isHardLock
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.sync.actions.UpdateCategoryFlagsAction
import io.timelimit.android.sync.actions.dispatch.LocalDatabaseParentActionDispatcher
import io.timelimit.android.usage.ServerLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The agent's levers, phone side.
 *
 * Reads what is pending for this phone, applies it, and says what actually happened. The server half
 * (`server/commands.go`) is a queue of `lock | allowance | pause | resume | release` with a closed
 * vocabulary: nothing here can create or edit a rule, only act on the ones the user already wrote.
 *
 * Deliberately not a second network channel. The loop parks on `/v1/watch`, the same change feed the
 * rest of the sync uses, so a command lands within a round trip instead of within a poll interval,
 * and the cost of a phone that has nothing to do is one parked request every ~55 s.
 *
 * **Only `release` is wired up in this build.** Everything else is acked as `failed` with a straight
 * answer, because a queue that silently swallows a lock the agent asked for is worse than a queue
 * that admits the phone cannot do it yet. Each lever gets enabled one at a time, and this one first:
 * releasing is the lever whose worst case is "the user gets their phone back".
 *
 * Every failure here is logged and dropped. This runs inside the process that enforces, and no
 * remote instruction is worth taking enforcement down for.
 */
object AgentCommands {
    private const val TAG = "limited-sync"
    private const val PREFS = "limited_agent"
    private const val KEY_SEEN = "seen_ids"

    /** The server caps a parked watch at 60 s; staying under it keeps the request a clean timeout. */
    private const val WATCH_WAIT_S = 55
    private const val STARTUP_DELAY_MS = 25_000L
    private const val ERROR_BACKOFF_MS = 45_000L
    private const val IDLE_BACKOFF_MS = 1_000L

    /** Enough to survive an ack lost in a tunnel, small enough to stay a prefs string. */
    private const val SEEN_KEEP = 40

    private const val KEY_BOOTSTRAPPED = "soft_hard_bootstrapped"

    /** The categories the user named as hard. Applied once; the flags are the truth afterwards. */
    private val HARD_CATEGORY_TITLES = setOf("tets")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) {
            return
        }
        val app = context.applicationContext
        job = scope.launch {
            delay(STARTUP_DELAY_MS)
            runCatching { bootstrap(app) }.onFailure { Log.d(TAG, "agent: bootstrap failed: $it") }
            // Seed the counter without parking: wait=0 asks "where are we now" instead of waiting.
            var seq = watchSeq(app, -1L, 0) ?: 0L
            while (isActive) {
                runCatching { drain(app) }.onFailure { Log.d(TAG, "agent: drain failed: $it") }
                val next = watchSeq(app, seq, WATCH_WAIT_S)
                if (next == null) {
                    delay(ERROR_BACKOFF_MS)
                    continue
                }
                if (next == seq) {
                    // Parked the full window with nothing to report. Cheap, but not a busy loop.
                    delay(IDLE_BACKOFF_MS)
                }
                seq = next
            }
        }
    }

    /** Applies everything pending for this phone. Oldest first, which is the order it was written. */
    private suspend fun drain(context: Context) {
        val body = ServerLink.get(context, "/v1/commands") ?: return
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return
        val commands = root.optJSONArray("commands") ?: return

        for (i in 0 until commands.length()) {
            val command = commands.optJSONObject(i) ?: continue
            runCatching { applyOne(context, command) }
                .onFailure { Log.d(TAG, "agent: apply failed for ${command.optString("id")}: $it") }
        }

        // The kill switch rides the same response as the commands, so there is exactly one thing to
        // read and no second endpoint that could disagree with it.
        if (root.optBoolean("hard_lock", true)) {
            runCatching {
                HardLock.reassert(context).forEach { line -> report(context, "hard_lock", line) }
            }.onFailure { Log.d(TAG, "agent: hard lock pass failed: $it") }
        }
    }

    /** Records something the phone did that the agent should know about, in the audit. */
    private fun report(context: Context, kind: String, note: String) {
        val body = JSONObject().put("kind", kind).put("note", note).toString()
        ServerLink.post(context, "/v1/events", body)
    }

    private suspend fun applyOne(context: Context, command: JSONObject) {
        val id = command.optString("id")
        if (id.isEmpty()) {
            return
        }
        // A lost ack leaves the command pending at the server, and re-applying a release is not
        // harmless -- it would extend the window the user never asked for. Remember what we did.
        // Keyed on id *and* creation time, not the id alone: the server's counter restarts at c1 if
        // its state is ever wiped, and an id-only guard would then silently refuse a real command as
        // "already applied".
        val key = "$id@${command.optLong("created", 0L)}"
        if (alreadySeen(context, key)) {
            ack(context, id, "applied", "already applied on this phone")
            return
        }

        val kind = command.optString("kind")
        val minutes = command.optInt("minutes", 0)
        val note = command.optString("note")

        when (kind) {
            "release" -> release(context, id, key, minutes, note, command.optString("target"))
            else -> ack(
                context,
                id,
                "failed",
                "$kind is not wired up on build ${BuildConfig.VERSION_NAME}, only release is"
            )
        }
    }

    /**
     * The parent's own unlock, borrowed.
     *
     * `release` dispatches [io.timelimit.android.sync.actions.SetUserDisableLimitsUntilAction] -- the
     * app's "disable time limits until <time>" -- instead of holding a flag of our own. Every
     * strategy already reads that timestamp (overlay, Shizuku suspend, DNS, work profile) and it
     * expires by comparison with the clock, so the window ends on its own even if this process is
     * dead by then, and the phone shows the override the user can see rather than a hidden state.
     *
     * The two calls after it are cleanup, not policy: an overlay already on screen would otherwise
     * stay there until the next tick, and an annoy screen would keep its own countdown going.
     */
    private suspend fun release(
        context: Context,
        id: String,
        key: String,
        minutes: Int,
        note: String,
        target: String
    ) {
        val holdMinutes = minutes.coerceIn(1, 1440)

        // A targeted release opens one hard category; an untargeted one is the parent unlock for the
        // whole child user. Keeping them separate is what lets the hard/soft split mean anything:
        // opening a soft category never requires the agent, so nothing but a hard one arrives here
        // with a target.
        if (target.isNotEmpty()) {
            HardLock.grant(context, target, holdMinutes)
            markSeen(context, key)
            val reason = if (note.isEmpty()) "" else " ($note)"
            ack(context, id, "applied", "hard category $target opened for ${holdMinutes}m$reason")
            return
        }

        val until = System.currentTimeMillis() + holdMinutes.toLong() * 60_000L

        val result = ParentUnlock.disableLimitsUntil(context, until)

        val logic = DefaultAppLogic.with(context)
        logic.backgroundTaskLogic.dismissLockScreenNow()

        // doParentTempUnlock pokes LiveData, which only accepts setValue from the main thread. A
        // failure here must not cost the parent unlock that already landed, so it is fire-and-forget.
        Threads.mainThreadHandler.post {
            runCatching { logic.annoyLogic.doParentTempUnlock() }
                .onFailure { Log.d(TAG, "agent: annoy unlock failed: $it") }
        }

        markSeen(context, key)

        val reason = if (note.isEmpty()) "" else " ($note)"
        ack(context, id, "applied", "$result, ${holdMinutes}m$reason")
    }

    private fun ack(context: Context, id: String, state: String, result: String) {
        val body = JSONObject()
            .put("id", id)
            .put("state", state)
            .put("result", result)
            .toString()
        ServerLink.post(context, "/v1/commands/ack", body)
    }

    private fun watchSeq(context: Context, after: Long, waitSeconds: Int): Long? {
        val body = ServerLink.get(
            context,
            "/v1/watch?after=$after&wait=$waitSeconds",
            readTimeoutMs = (waitSeconds + 25) * 1000
        ) ?: return null
        val seq = runCatching { JSONObject(body).optLong("seq", -1L) }.getOrNull() ?: return null
        return if (seq >= 0) seq else null
    }

    /**
     * One-time setup for the soft/hard split, run once per install.
     *
     * It is a migration rather than a lever on purpose: which categories are hard is the user's
     * decision, made once, and afterwards it lives in the categories' own flags where the app can see
     * it. Turning random-character unlock on is the soft half -- friction on every local override,
     * with no server involved.
     */
    private suspend fun bootstrap(context: Context) {
        if (prefs(context).getBoolean(KEY_BOOTSTRAPPED, false)) return

        val marked = Threads.database.executeAndWait {
            val database = DefaultAppLogic.with(context).database

            database.config().setRandomUnlockEnabledSync(true)
            database.config().setRandomUnlockLengthSync(24)

            val childId = database.config().getOwnDeviceIdSync()
                ?.let { database.device().getDeviceByIdSync(it)?.currentUserId }
                .orEmpty()
            if (childId.isEmpty()) return@executeAndWait emptyList<String>()

            val parentId = database.user().getParentUsersSync().firstOrNull()?.id
            val names = HARD_CATEGORY_TITLES.map { it.lowercase() }
            val markedNow = mutableListOf<String>()

            for (category in database.category().getCategoriesByChildIdSync(childId)) {
                if (category.title.trim().lowercase() !in names || category.isHardLock) continue

                database.runInTransaction {
                    LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                        UpdateCategoryFlagsAction(
                            categoryId = category.id,
                            modifiedBits = CategoryFlags.HARD_LOCK,
                            newValues = CategoryFlags.HARD_LOCK
                        ),
                        database = database,
                        fromChildSelfLimitAddChildUserId = null,
                        parentUserId = parentId
                    )
                }
                markedNow += category.title
            }
            markedNow
        }

        prefs(context).edit().putBoolean(KEY_BOOTSTRAPPED, true).apply()

        report(
            context,
            "bootstrap",
            "random-character unlock on (24), hard: " +
                (marked.ifEmpty { listOf("none") }.joinToString(", "))
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun alreadySeen(context: Context, id: String): Boolean =
        prefs(context).getString(KEY_SEEN, "").orEmpty().split(',').contains(id)

    private fun markSeen(context: Context, id: String) {
        val current = prefs(context).getString(KEY_SEEN, "").orEmpty()
            .split(',')
            .filter { it.isNotEmpty() }
        val next = (current + id).takeLast(SEEN_KEEP)
        prefs(context).edit().putString(KEY_SEEN, next.joinToString(",")).apply()
    }
}
