/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 */
package io.timelimit.android.usage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the sensor: collect, queue, flush. Owned by BackgroundService, so it lives exactly as long
 * as blocking does.
 *
 * Cadence is 15 minutes plus an immediate run on screen-off, because that is the moment a visit
 * actually ends and the number is final. Everything is best effort: a failed collect, a full disk
 * or a dead tunnel is logged and forgotten. If telemetry ever took blocking down with it, the
 * trade would be backwards -- enforcement is the product, this is the diary.
 */
object UsageUploader {
    private const val TAG = "limited-sync"

    /** Long enough to be cheap, short enough that a 24h event window never expires on us. */
    private const val INTERVAL_MS = 15L * 60L * 1000L

    /** First run after start: gives the foreground service time to settle. */
    private const val STARTUP_DELAY_MS = 20L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var screenReceiver: BroadcastReceiver? = null

    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) {
            return
        }
        val app = context.applicationContext
        appContext = app
        job = scope.launch {
            delay(STARTUP_DELAY_MS)
            while (isActive) {
                runCatching { tick(app) }.onFailure { Log.d(TAG, "usage: tick failed: $it") }
                delay(INTERVAL_MS)
            }
        }
        if (screenReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != Intent.ACTION_SCREEN_OFF) {
                        return
                    }
                    scope.launch {
                        runCatching { tick(app) }.onFailure { Log.d(TAG, "usage: screen-off tick failed: $it") }
                    }
                }
            }
            screenReceiver = receiver
            try {
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    IntentFilter(Intent.ACTION_SCREEN_OFF),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } catch (t: Throwable) {
                Log.d(TAG, "usage: screen-off receiver not registered: $t")
                screenReceiver = null
            }
        }
        Log.d(TAG, "usage: uploader started (profile=${UsageTelemetry.profile(app).ifEmpty { "owner" }})")
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        val receiver = screenReceiver
        val app = appContext
        if (receiver != null && app != null) {
            try {
                app.unregisterReceiver(receiver)
            } catch (t: Throwable) {
                Log.d(TAG, "usage: receiver unregister failed: $t")
            }
        }
        screenReceiver = null
    }

    /** collect -> outbox -> server. Safe to call from anywhere off the main thread. */
    suspend fun tick(context: Context) {
        val batch = UsageTelemetry.collect(context, currentBlockedPackages(context))
        if (batch != null) {
            UsageOutbox.put(context, batch)
        }
        flush(context)
    }

    /** Sends everything queued, oldest first, stopping at the first failure that is not our fault. */
    suspend fun flush(context: Context) {
        for (file in UsageOutbox.pending(context)) {
            val body = try {
                file.readText()
            } catch (t: Throwable) {
                Log.d(TAG, "usage: unreadable batch ${file.name}: $t")
                UsageOutbox.drop(file)
                continue
            }
            val result = ServerLink.post(context, "/v1/usage", body)
            when {
                result == null -> {
                    Log.d(TAG, "usage: no host answered, ${UsageOutbox.pending(context).size} batch(es) queued")
                    return
                }
                result.first in 200..299 -> {
                    UsageOutbox.drop(file)
                    Log.d(TAG, "usage: batch accepted (${result.second?.take(120)})")
                }
                result.first == 401 -> {
                    // A wrong token will not fix itself by retrying: keep the batch, stop sending.
                    Log.d(TAG, "usage: unauthorized, token not baked in?")
                    return
                }
                else -> {
                    Log.d(TAG, "usage: server said ${result.first}: ${result.second?.take(160)}")
                    return
                }
            }
        }
    }

    /**
     * What is blocked right now, read from the same prefs ShizukuBlockingLogic persists, so a
     * session can be labelled without wiring the collector into the blocking stack.
     */
    private fun currentBlockedPackages(context: Context): Set<String> = try {
        context.getSharedPreferences("shizuku_blocking", Context.MODE_PRIVATE)
            .getStringSet("blocked_packages", emptySet()) ?: emptySet()
    } catch (t: Throwable) {
        emptySet()
    }
}
