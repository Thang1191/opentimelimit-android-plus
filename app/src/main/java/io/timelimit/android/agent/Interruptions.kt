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
import io.timelimit.android.usage.ServerLink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What happened at the wall, as opposed to how long anything ran.
 *
 * [io.timelimit.android.usage.UsageUploader] can say a session ended; it cannot say the user was
 * stopped and what they did next. This writes one line per lock screen to a file -- cheap, ordered,
 * survives the process dying, and costs nothing when the tunnel is down -- and the agent loop drains
 * it into `/v1/interruptions` when there is a connection.
 *
 * Only two kinds exist: `block` (the wall was shown) and `escape` (the user authorised past it).
 * Nothing here is inferred. A lock screen that closes because the block expired is neither.
 */
object Interruptions {
    private const val TAG = "limited-sync"
    private const val FILE = "interruptions.jsonl"
    private const val DEDUPE_MS = 60_000L
    private const val MAX_PENDING = 500

    private var lastKey: String? = null
    private var lastAt = 0L

    /**
     * Records one interruption. Safe from any thread, never throws: telemetry failing must never
     * affect enforcement, and a lock screen that fails to log is still a lock screen.
     */
    fun record(
        context: Context,
        kind: String,
        outcome: String?,
        category: String?,
        packageName: String?,
        reason: String?
    ) {
        try {
            val now = System.currentTimeMillis()
            val key = "$kind|$packageName|$category|$reason"

            synchronized(this) {
                // A lock screen re-shown for the same app within a minute is the same interruption:
                // rotating the phone or leaving and returning must not inflate the count.
                if (key == lastKey && now - lastAt < DEDUPE_MS) return
                lastKey = key
                lastAt = now
            }

            val row = JSONObject().apply {
                put("at", now)
                put("kind", kind)
                if (!outcome.isNullOrEmpty()) put("outcome", outcome)
                if (!category.isNullOrEmpty()) put("category", category)
                if (!packageName.isNullOrEmpty()) put("package", packageName)
                if (!reason.isNullOrEmpty()) put("reason", reason)
            }.toString()

            synchronized(this) {
                val file = file(context)
                file.appendText(row + "\n")
                trim(file)
            }

            // Try the wire now rather than waiting for the agent loop: a block is the one event where
            // a minute of delay turns a measurement into a guess. The file stays as the fallback, so
            // a dead tunnel costs nothing but this attempt.
            Thread {
                runCatching { flush(context) }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.d(TAG, "interruption not recorded: ${e.message}")
        }
    }

    /**
     * Sends what is queued. Returns how many rows the server accepted this pass.
     *
     * The file is the queue: rows are read, posted, and removed only once the server has answered
     * 200, so a dead tunnel leaves the file exactly as it was and nothing is lost or doubled --
     * the server keys on the row's own content.
     */
    fun flush(context: Context): Int {
        val file = file(context)
        if (!file.exists() || file.length() == 0L) return 0

        val pending = try {
            file.readLines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.d(TAG, "interruption queue unreadable: ${e.message}")
            return 0
        }
        if (pending.isEmpty()) return 0

        val events = JSONArray()
        pending.take(MAX_PENDING).forEach { line ->
            try {
                events.put(JSONObject(line))
            } catch (e: Exception) {
                // A half-written line is dropped rather than blocking the queue behind it.
            }
        }
        if (events.length() == 0) {
            file.delete()
            return 0
        }

        val body = JSONObject().put("events", events).toString()
        val response = ServerLink.post(context, "/v1/interruptions", body) ?: return 0
        if (response.first != 200) {
            Log.d(TAG, "interruption upload answered ${response.first}")
            return 0
        }

        synchronized(this) {
            val sent = events.length()
            val remaining = try {
                file.readLines().filter { it.isNotBlank() }.drop(sent)
            } catch (e: Exception) {
                emptyList()
            }
            if (remaining.isEmpty()) file.delete()
            else file.writeText(remaining.joinToString("\n", postfix = "\n"))
        }

        return events.length()
    }

    private fun trim(file: File) {
        val lines = file.readLines()
        if (lines.size <= MAX_PENDING) return
        file.writeText(lines.takeLast(MAX_PENDING).joinToString("\n", postfix = "\n"))
    }

    private fun file(context: Context) = File(context.filesDir, FILE)
}
