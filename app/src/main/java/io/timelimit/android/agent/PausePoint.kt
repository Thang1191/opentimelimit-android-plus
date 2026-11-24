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
package io.timelimit.android.agent

import android.content.Context
import io.timelimit.android.async.Threads
import io.timelimit.android.data.Database
import io.timelimit.android.data.model.CategoryPauseConfig
import io.timelimit.android.data.model.PauseQuote
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pause Point — the gate the user asked for, in front of a category's apps.
 *
 * The rule belongs to the category and is authored on the phone, so nothing here is a lever: there
 * is no server verb, no threshold to negotiate and no command that can switch it off remotely. The
 * whole feature is a wait, followed by one button, and the requirement that leaving does not count
 * as pressing it.
 *
 * State is held in memory because it describes one launch of one app, not a fact about the world:
 *
 * - a [Gate] exists from the moment the app came to the foreground until Continue is pressed. It
 *   carries the uptime the countdown started at, so leaving and returning resumes the ring instead
 *   of restarting it — a cheat attempt costs exactly the time it costs.
 * - [enteredUntil] is the cool-down: once the user has pressed Continue, the same package is not
 *   paused again until it runs out, so switching apps inside a session is not punished.
 *
 * Configuration is watched rather than polled: the enforcement loop reads [configs], which the
 * database keeps current, so the loop never touches SQLite on its own thread.
 */
object PausePoint {
    data class Gate(
        val packageName: String,
        val categoryId: String,
        val categoryTitle: String,
        val waitSeconds: Int,
        val checkInType: Int,
        val folderUri: String?,
        val copy: String?,
        val quoteText: String?,
        val quoteAuthor: String?,
        val startedAt: Long
    )

    private val configs = ConcurrentHashMap<String, CategoryPauseConfig>()
    private val loaded = AtomicBoolean(false)
    private val lock = Any()

    private var quotes: List<PauseQuote> = emptyList()
    private var quoteIndex = 0

    private var gate: Gate? = null
    private var enteredPackage: String? = null
    private var enteredUntil = 0L
    private var currentForeground: String? = null

    /**
     * Pause Point is a launch gate, so it has to know what a launch is: the foreground package
     * *changing* to something. Being foreground is not a launch, otherwise the loop would gate the
     * same appearance over and over — which is what made a zero cool-down re-pause the instant
     * Continue was pressed.
     *
     * Returns true only for the tick where the package in front is a different one from last tick.
     */
    fun noteForeground(packageName: String?): Boolean = synchronized(lock) {
        val previous = currentForeground
        currentForeground = packageName

        packageName != null && packageName != previous
    }

    /** True when this category has the rule switched on. Cheap: one map lookup, no SQLite. */
    fun isEnabled(categoryId: String): Boolean = configs[categoryId]?.enabled == true

    /**
     * Packages that must never be gated, whatever category they land in.
     *
     * Two reasons. Ourselves: gating the settings screen is gating the only place the rule can be
     * switched off. The system's own doors: a camera or dialer that waits ten seconds is not a
     * mindful check, it is a missed call.
     */
    private val NEVER_PAUSE = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.contactspicker",
        "com.google.android.apps.messaging"
    )

    fun isExempt(packageName: String, ownPackageName: String): Boolean =
        packageName == ownPackageName || packageName in NEVER_PAUSE

    /**
     * Called once from the enforcement loop. Watches the enabled rows so the settings page takes
     * effect without the loop waiting on a query, and warms the quote pool in the background.
     */
    fun warmUp(context: Context, database: Database) {
        if (!loaded.compareAndSet(false, true)) return

        database.categoryPauseConfig().getAllEnabledLive().observeForever { rows ->
            configs.clear()
            rows?.forEach { configs[it.categoryId] = it }
        }

        Threads.database.execute {
            runCatching { quotes = database.pauseQuote().getAllSync() }
            runCatching { PauseQuotes.refreshIfStale(database) }
            runCatching { quotes = database.pauseQuote().getAllSync() }
        }
    }

    /** Re-read the quote pool after a refresh, without waiting for a restart. */
    fun reloadQuotes(database: Database) {
        Threads.database.execute {
            runCatching { quotes = database.pauseQuote().getAllSync() }
        }
    }

    /**
     * The gate for this app, or null when this app should be left alone right now.
     *
     * Returns the existing gate when one is already running for the package — that is what makes a
     * re-launch resume rather than restart — and a fresh one otherwise, recording the fact that the
     * pause appeared so the interruption log can answer "how often" later.
     */
    fun gateFor(
        context: Context,
        now: Long,
        packageName: String,
        categoryId: String,
        categoryTitle: String
    ): Gate? {
        val config = configs[categoryId] ?: return null
        if (!config.enabled) return null

        synchronized(lock) {
            if (enteredPackage == packageName && now < enteredUntil) return null

            gate?.let { existing ->
                if (existing.packageName == packageName) return existing
            }

            val quote = if (config.checkInType == CategoryPauseConfig.CHECK_IN_QUOTE) nextQuote() else null

            val created = Gate(
                packageName = packageName,
                categoryId = categoryId,
                categoryTitle = categoryTitle,
                waitSeconds = config.waitSeconds.coerceIn(
                    CategoryPauseConfig.MIN_WAIT_SECONDS,
                    CategoryPauseConfig.MAX_WAIT_SECONDS
                ),
                checkInType = config.checkInType,
                folderUri = config.folderUri,
                copy = config.copy,
                quoteText = quote?.text,
                quoteAuthor = quote?.author,
                startedAt = now
            )

            gate = created

            Interruptions.record(
                context = context,
                kind = "pause",
                outcome = "shown",
                category = categoryTitle,
                packageName = packageName,
                reason = null
            )

            return created
        }
    }

    /** Continue was pressed. This is the only way through, so it is also the only cool-down reset. */
    fun markEntered(context: Context, packageName: String, now: Long) {
        val entered = synchronized(lock) {
            val current = gate?.takeIf { it.packageName == packageName } ?: return
            val cooldown = configs[current.categoryId]?.cooldownMinutes
                ?: CategoryPauseConfig.DEFAULT_COOLDOWN_MINUTES

            enteredPackage = packageName
            enteredUntil = now + cooldown * 60_000L
            gate = null

            // The app is about to be the foreground package again; do not count that as a new
            // launch, or a zero cool-down would pause the same appearance straight back.
            currentForeground = packageName

            current
        }

        Interruptions.record(
            context = context,
            kind = "pause",
            outcome = "entered",
            category = entered.categoryTitle,
            packageName = packageName,
            reason = null
        )
    }

    /** The user left the app instead of pressing Continue. The gate ends here, with the launch. */
    fun markLeft(context: Context, packageName: String) {
        val current = synchronized(lock) {
            val armed = gate?.takeIf { it.packageName == packageName } ?: return
            // Leaving without pressing Continue ends this launch's gate outright. Keeping it alive
            // would make "pop out and come back" cheaper than staying put, when the whole point is
            // that every launch pays the wait: the next time the app comes up it is a new launch,
            // and a new launch starts the countdown from the top.
            gate = null
            armed
        }

        Interruptions.record(
            context = context,
            kind = "pause",
            outcome = "left",
            category = current.categoryTitle,
            packageName = packageName,
            reason = null
        )
    }

    /** Drop an armed gate for a package, e.g. the rule was switched off while the pause was up. */
    fun forget(packageName: String) {
        synchronized(lock) {
            if (gate?.packageName == packageName) gate = null
        }
    }

    private fun nextQuote(): PauseQuote? {
        val pool = quotes
        if (pool.isEmpty()) return null

        quoteIndex = (quoteIndex + 1) % pool.size

        return pool[quoteIndex]
    }
}
