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
import io.timelimit.android.data.Database
import io.timelimit.android.data.extensions.getTimezone
import io.timelimit.android.data.model.Category
import io.timelimit.android.data.model.CategoryFlags
import io.timelimit.android.data.model.isHardLock
import io.timelimit.android.date.DateInTimezone
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.sync.actions.IncrementCategoryExtraTimeAction
import io.timelimit.android.sync.actions.SetCategoryExtraTimeAction
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
 * (`server/commands.go`) is a queue of `allowance | release | time | harden` with a closed
 * vocabulary: nothing here can create or edit a rule, only act on the ones the user already wrote.
 *
 * Deliberately not a second network channel. The loop parks on `/v1/watch`, the same change feed the
 * rest of the sync uses, so a command lands within a round trip instead of within a poll interval,
 * and the cost of a phone that has nothing to do is one parked request every ~55 s.
 *
 * All four verbs are wired in this build. An unknown kind is acked `failed` with a straight answer,
 * because a queue that silently swallows a command the agent asked for is worse than a queue that
 * admits the phone cannot do it yet -- and a command that throws is acked the same way instead of
 * being retried forever, since a deterministic failure would otherwise sit in the pending list until
 * its TTL and crowd out the next twelve.
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

    // Bumped when the list below grows: the migration only runs once per install, so a new hard
    // category needs a new key or it would never be applied on a phone that already bootstrapped.
    private const val KEY_BOOTSTRAPPED = "soft_hard_bootstrapped_2"

    /** The categories the user named as hard. Applied once; the flags are the truth afterwards. */
    private val HARD_CATEGORY_TITLES = setOf("tets", "addiction")

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
                runCatching { flushInterruptions(app) }
                    .onFailure { Log.d(TAG, "agent: interruption flush failed: $it") }
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
    private suspend fun flushInterruptions(context: Context) {
        try {
            val sent = Interruptions.flush(context)
            if (sent > 0) Log.d(TAG, "interruptions uploaded: $sent")
        } catch (e: Exception) {
            Log.d(TAG, "interruption flush failed: ${e.message}")
        }
    }

    private suspend fun drain(context: Context) {
        val body = ServerLink.get(context, "/v1/commands") ?: return
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return
        val commands = root.optJSONArray("commands") ?: return

        // Cache the kill switch for the write-path guard, which has to answer without a network call
        // (it runs inside UI dispatches). Absent means enabled: the guard fails closed.
        HardLockGuard.cacheServerValue(context, root.optBoolean("hard_lock", true))

        for (i in 0 until commands.length()) {
            val command = commands.optJSONObject(i) ?: continue
            runCatching { applyOne(context, command) }
                .onFailure { failure ->
                    Log.d(TAG, "agent: apply failed for ${command.optString("id")}: $failure")
                    // Say so instead of leaving it pending. A deterministic failure (a category the
                    // phone does not have) would otherwise be retried every drain until its TTL and
                    // hold a slot against the server's pending cap.
                    ack(
                        context,
                        command.optString("id"),
                        "failed",
                        "phone error: ${failure.message ?: failure.javaClass.simpleName}"
                    )
                }
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
            "allowance" -> allowance(context, id, key, minutes, note, command.optString("target"))
            "release" -> release(
                context, id, key, minutes, note,
                command.optString("target"), command.optBoolean("permanent", false)
            )
            "time" -> setTime(context, id, key, minutes, note, command.optString("target"))
            "harden" -> harden(context, id, key, note, command.optString("target"))
            else -> ack(
                context,
                id,
                "failed",
                "$kind is not a lever on build ${BuildConfig.VERSION_NAME} " +
                    "(allowance, release, time, harden)"
            )
        }
    }

    /**
     * Adds [minutes] to a category's budget for today. The target is a category id, a category title
     * or a package id, and a package is resolved to whatever category the app lives in -- the app's
     * model has no per-app budget, only per-category, so "20 more minutes of TikTok" is really "20
     * more minutes for the category TikTok is in", and the ack names the category so that is never a
     * surprise.
     *
     * An increment, not a set: [setTime] is the absolute form and exists to take a grant back.
     * The expiry is the app's own -- extra time is stored against a day and stops counting when the
     * day rolls over -- so this cannot leave a budget behind that keeps growing tomorrow.
     */
    private suspend fun allowance(
        context: Context,
        id: String,
        key: String,
        minutes: Int,
        note: String,
        target: String
    ) {
        if (target.isEmpty()) {
            ack(context, id, "failed", "allowance needs a target: a category id, title or package")
            return
        }

        val add = minutes.coerceIn(1, 1440)

        val result = Threads.database.executeAndWait {
            val database = DefaultAppLogic.with(context).database
            val category = resolveCategory(context, database, target)
            val day = today(context, database)

            val parentId = database.user().getParentUsersSync().firstOrNull()?.id

            database.runInTransaction {
                LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                    IncrementCategoryExtraTimeAction(
                        categoryId = category.id,
                        addedExtraTime = add * 60_000L,
                        extraTimeDay = day
                    ),
                    database = database,
                    fromChildSelfLimitAddChildUserId = null,
                    parentUserId = parentId
                )
            }

            val total = category.getExtraTime(day) + add * 60_000L

            "'${category.title}' +${add}m -> ${total / 60_000L}m extra today (resets at midnight)"
        }

        markSeen(context, key)
        val reason = if (note.isEmpty()) "" else " ($note)"
        ack(context, id, "applied", "$result$reason")
    }

    /**
     * A category id, or the category a package id lives in.
     *
     * Called on the database thread. Order: id, then title (case-insensitive), then package. The
     * title matters because that is what telemetry and the interruption log report -- an agent
     * reading `Addiction` out of a digest must be able to name it -- and the package is the other
     * thing it sees. Throws when nothing matches, which the drain turns into a `failed` ack carrying
     * the message: a wrong identifier is worth saying out loud.
     */
    private fun resolveCategory(
        context: Context,
        database: Database,
        target: String
    ): Category {
        database.category().getCategoryByIdSync(target)?.let { return it }

        val deviceId = database.config().getOwnDeviceIdSync()
            ?: throw IllegalStateException("this phone has no own device id")
        val childId = database.device().getDeviceByIdSync(deviceId)?.currentUserId.orEmpty()
        if (childId.isEmpty()) {
            throw IllegalStateException("no user is active on this device")
        }

        val categories = database.category().getCategoriesByChildIdSync(childId)
        categories.firstOrNull { it.title.equals(target, ignoreCase = true) }?.let { return it }

        val row = database.categoryApp().getCategoryAppSync(categories.map { it.id }, target)
            ?: throw IllegalStateException(
                "'$target' is not a category id, a category title or an installed app"
            )

        return database.category().getCategoryByIdSync(row.categoryId)
            ?: throw IllegalStateException("category ${row.categoryId} is not in the database")
    }

    /**
     * The day the app itself thinks it is, for a user in their own timezone.
     *
     * The budget rows are keyed on this number, so computing it from the *device* timezone (what a
     * plain `LocalDate.now()` does) writes a grant that the blocking logic never sees whenever the
     * child user's timezone is not the system one.
     */
    private fun today(
        context: Context,
        database: Database
    ): Int {
        val deviceId = database.config().getOwnDeviceIdSync()
        val childId = deviceId?.let { database.device().getDeviceByIdSync(it)?.currentUserId }.orEmpty()
        val child = if (childId.isEmpty()) null else database.user().getUserByIdSync(childId)
        val now = DefaultAppLogic.with(context).timeApi.getCurrentTimeInMillis()
        return DateInTimezone.newInstance(now, child.getTimezone()).dayOfEpoch
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
        target: String,
        permanent: Boolean
    ) {
        val holdMinutes = minutes.coerceIn(1, 1440)

        // A targeted release opens one hard category; an untargeted one is the parent unlock for the
        // whole child user. Keeping them separate is what lets the hard/soft split mean anything:
        // opening a soft category never requires the agent, so nothing but a hard one arrives here
        // with a target.
        if (target.isNotEmpty()) {
            // "Unlock" has to change what the user may do, not only what the wall does: the flag
            // itself comes off, so the category is soft -- switch enabled, no veil, random-character
            // prompt as the only friction. A temporary release is put back by HardLock.reassert.
            // Hard and soft lock both block; they differ in who may lift the block. Moving a
            // category back to soft is only that restriction moving hands, so it grants no time:
            // a category whose budget is spent stays spent, exactly as it would on a soft lock.
            val softened = HardLock.soften(context, target, holdMinutes, permanent)
            markSeen(context, key)
            val reason = if (note.isEmpty()) "" else " ($note)"
            ack(context, id, "applied", "$softened$reason")
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

    /**
     * Sets a category's extra time for today to exactly [minutes], taken away included.
     *
     * Not a lever: the agent has no business handing itself time, and the one reason this exists is
     * to undo time it should never have granted. Absolute rather than incremental so the outcome is
     * the number asked for, whatever the category was carrying.
     */
    private suspend fun setTime(
        context: Context,
        id: String,
        key: String,
        minutes: Int,
        note: String,
        target: String
    ) {
        if (target.isEmpty()) {
            ack(context, id, "failed", "time needs a target")
            return
        }

        val result = Threads.database.executeAndWait {
            val database = DefaultAppLogic.with(context).database
            val category = resolveCategory(context, database, target)

            val parentId = database.user().getParentUsersSync().firstOrNull()?.id
            val day = today(context, database)

            database.runInTransaction {
                LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                    SetCategoryExtraTimeAction(
                        categoryId = category.id,
                        newExtraTime = minutes.coerceAtLeast(0) * 60_000L,
                        extraTimeDay = day
                    ),
                    database = database,
                    fromChildSelfLimitAddChildUserId = null,
                    parentUserId = parentId
                )
            }

            "'${category.title}' now has ${minutes.coerceAtLeast(0)}m of extra time today"
        }

        markSeen(context, key)
        val reason = if (note.isEmpty()) "" else " ($note)"
        ack(context, id, "applied", "$result$reason")
    }

    /** The other half of `release`: the server puts a category under its own control. */
    private suspend fun harden(context: Context, id: String, key: String, note: String, target: String) {
        if (target.isEmpty()) {
            ack(context, id, "failed", "harden needs a target")
            return
        }

        val result = HardLock.harden(context, target)
        markSeen(context, key)
        val reason = if (note.isEmpty()) "" else " ($note)"
        ack(context, id, "applied", "$result$reason")
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
