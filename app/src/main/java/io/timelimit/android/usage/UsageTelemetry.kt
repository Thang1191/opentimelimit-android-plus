/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 */
package io.timelimit.android.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the phone actually did.
 *
 * This is the sensor half of the telemetry the agent reads: nothing here decides anything, and a
 * failure here must never touch blocking. Everything it produces is best effort and swallowed.
 *
 * Two layers, because they answer different questions:
 *
 *   sessions  one row per foreground stretch. "3h of YouTube" and "42 opens of 19 seconds" are
 *             completely different habits and only sessions tell them apart.
 *   hours     per local day+hour+package totals, derived from the sessions above rather than from
 *             queryUsageStats, so both layers agree about what a session is.
 *
 * Deliberate reductions, all of them learned from a real day on the Ace:
 *
 *   - The launcher is the single biggest "app" on the phone (275min/24h). Foreground time that
 *     includes the home screen is noise, so it is excluded along with the search box, SystemUI,
 *     the keyboard and ourselves (LockActivity alone showed 346 focus flips a day).
 *   - Sessions arrive badly fragmented (Telegram: 148 opens a day, some under a second). A resume
 *     within STITCH_GAP_MS of the previous pause continues the same session, and anything still
 *     under MIN_SESSION_MS after stitching is dropped as noise.
 *   - Usage events only exist for ~24h on this build. The cursor therefore never reaches further
 *     back than that: a phone that was offline for two days loses that detail and only keeps the
 *     hourly totals it had already computed.
 *
 * The work profile gets its own instance of this app reporting with profile "work" -- an app in
 * user 0 cannot read user 10's usage stats, there is no per-user read command, and the system dump
 * exposes no work-profile events. A label per profile is the only honest way to include Island.
 */
object UsageTelemetry {
    private const val TAG = "limited-sync"
    private const val PREFS = "usage_telemetry"
    private const val KEY_CURSOR = "cursor_ms"

    /** A resume this soon after a pause is the same visit, not a new one. */
    private const val STITCH_GAP_MS = 5_000L

    /** Shorter than this after stitching is a focus blip, not a visit. */
    private const val MIN_SESSION_MS = 2_000L

    /** Usage events are retained ~24h by the platform; asking for more returns less. */
    private const val EVENT_WINDOW_MS = 24L * 60L * 60L * 1000L

    /** Re-read a few seconds of overlap: a deterministic session id makes a repeat harmless. */
    private const val OVERLAP_MS = 3_000L

    /** One upload should stay small enough to be a single quick request. */
    private const val MAX_SESSIONS = 400

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * "" for the owner profile, "work" for the Island profile. Sent with every row.
     *
     * isManagedProfile is the only public way to ask this: UserHandle.myUserId() is a hidden API,
     * and ANDROID_ID already differs per profile, so the profile also gets its own device id.
     */
    fun profile(context: Context): String {
        val managed = try {
            val manager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
            manager?.isManagedProfile == true
        } catch (t: Throwable) {
            false
        }
        return if (managed) "work" else ""
    }

    /**
     * Packages whose foreground time says nothing about habits: the home screen, the search box,
     * SystemUI (notification shade), the keyboard, and this app.
     */
    private fun exclusions(context: Context): Set<String> {
        val set = mutableSetOf(
            context.packageName,
            "com.android.systemui",
            "com.heytap.quicksearchbox",
            "com.android.inputmethod.latin"
        )
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { set += it }
        } catch (t: Throwable) {
            Log.d(TAG, "usage: launcher lookup failed: $t")
        }
        return set
    }

    /**
     * Collects everything since the last run. Returns null when there is nothing worth sending.
     * `blockedPackages` only labels a session; it never changes what is recorded.
     */
    fun collect(context: Context, blockedPackages: Set<String>): JSONObject? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (manager == null) {
            Log.d(TAG, "usage: no UsageStatsManager")
            return null
        }
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cursor = prefs.getLong(KEY_CURSOR, 0L)
        val since = maxOf(cursor - OVERLAP_MS, now - EVENT_WINDOW_MS)
        if (since >= now - 1_000L) {
            return null
        }

        val skip = exclusions(context)
        val raw = readSessions(manager, since, now, skip)
        val stitched = stitch(raw)
        if (stitched.isEmpty()) {
            prefs.edit().putLong(KEY_CURSOR, now).apply()
            return null
        }

        val profile = profile(context)
        val sessions = JSONArray()
        var sent = 0
        for (session in stitched.asReversed()) {
            if (sent >= MAX_SESSIONS) {
                break
            }
            val state = if (session.packageName in blockedPackages) "blocked" else "allowed"
            sessions.put(JSONObject()
                .put("id", "${session.packageName}@${session.start}")
                .put("pkg", session.packageName)
                .put("s", session.start)
                .put("e", session.end)
                .put("st", state)
                .put("u", profile))
            sent++
        }

        val hours = hourly(stitched, blockedPackages, profile)

        prefs.edit().putLong(KEY_CURSOR, now).apply()

        val batch = JSONObject().put("sessions", sessions).put("hours", hours)
        Log.d(TAG, "usage: collected sessions=${sessions.length()} hours=${hours.length()} " +
                "since=${(now - since) / 1000}s profile=${profile.ifEmpty { "owner" }}")
        return batch
    }

    private data class Raw(val packageName: String, val start: Long, val end: Long)

    /**
     * Pairs resumes with pauses. Only one activity per package can be open at a time, so a state
     * machine per package is enough; screen-off closes everything still open.
     */
    private fun readSessions(
        manager: UsageStatsManager,
        since: Long,
        now: Long,
        skip: Set<String>
    ): List<Raw> {
        val open = HashMap<String, Long>()
        val out = ArrayList<Raw>()
        val events = try {
            manager.queryEvents(since, now)
        } catch (t: Throwable) {
            Log.d(TAG, "usage: queryEvents failed: $t")
            return out
        }
        val event = UsageEvents.Event()

        fun close(packageName: String, at: Long) {
            val start = open.remove(packageName) ?: return
            if (at > start) {
                out += Raw(packageName, start, at)
            }
        }

        fun closeAll(at: Long) {
            for (packageName in open.keys.toList()) {
                close(packageName, at)
            }
        }

        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val packageName = event.packageName ?: continue
                val time = event.timeStamp
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (packageName in skip) {
                            close(packageName, time)
                            continue
                        }
                        // only one app is in front: anything else still open was left in this moment
                        closeAll(time)
                        open[packageName] = time
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                        close(packageName, time)
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN ->
                        closeAll(time)
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "usage: event walk stopped: $t")
        }
        // whatever is still open is being used right now
        closeAll(now)
        return out.filter { it.packageName !in skip }
    }

    /** Merges visits separated by a pause shorter than STITCH_GAP_MS, then drops the blips. */
    private fun stitch(sessions: List<Raw>): List<Raw> {
        if (sessions.isEmpty()) {
            return sessions
        }
        val sorted = sessions.sortedWith(compareBy({ it.packageName }, { it.start }))
        val out = ArrayList<Raw>(sorted.size)
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            current = if (next.packageName == current.packageName && next.start - current.end <= STITCH_GAP_MS) {
                Raw(current.packageName, current.start, maxOf(current.end, next.end))
            } else {
                out += current
                next
            }
        }
        out += current

        // A single visit that is still tiny after stitching was never a visit.
        val kept = out.filter { it.end - it.start >= MIN_SESSION_MS }
        val dropped = out.size - kept.size
        if (dropped > 0) {
            Log.d(TAG, "usage: stitched ${sessions.size} -> ${out.size}, dropped $dropped blips")
        }
        return kept
    }

    /** Splits sessions across hour boundaries into the coarse layer the server keeps for 90 days. */
    private fun hourly(
        sessions: List<Raw>,
        blockedPackages: Set<String>,
        profile: String
    ): JSONArray {
        data class Key(val day: String, val hour: Int, val packageName: String, val blocked: Boolean)
        val totals = HashMap<Key, LongArray>() // [foreground seconds, launches]
        val calendar = java.util.Calendar.getInstance()

        for (session in sessions) {
            var cursor = session.start
            var first = true
            while (cursor < session.end) {
                calendar.timeInMillis = cursor
                val day = dayFormat.format(Date(cursor))
                val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                val hourEnd = cursor + (60L * 60L * 1000L) - (cursor % (60L * 60L * 1000L))
                val sliceEnd = minOf(session.end, hourEnd)
                val key = Key(day, hour, session.packageName, session.packageName in blockedPackages)
                val acc = totals.getOrPut(key) { LongArray(2) }
                acc[0] += (sliceEnd - cursor) / 1000
                if (first) {
                    acc[1] += 1L
                    first = false
                }
                cursor = sliceEnd
            }
        }
        val out = JSONArray()
        for ((key, acc) in totals) {
            out.put(JSONObject()
                .put("d", key.day)
                .put("h", key.hour)
                .put("pkg", key.packageName)
                .put("fg", acc[0])
                .put("ln", acc[1])
                .put("bl", if (key.blocked) acc[0] else 0L)
                .put("u", profile))
        }
        return out
    }
}
