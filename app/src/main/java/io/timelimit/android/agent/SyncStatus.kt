/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 */
package io.timelimit.android.agent

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity

/**
 * One dot that says whether the phone is actually talking to the server.
 *
 * LifeUp got a sync LED for the same reason: the interesting failure is never "the server is down",
 * it is "the server is up and this phone has not reached it for twenty minutes", which is invisible
 * until something stops working. Three states, read from the last answer rather than from a flag:
 *
 *  - green: the server answered in the last two minutes (the watcher parks for 55 s, so a healthy
 *    phone touches this constantly);
 *  - amber: no answer for two minutes but a request is in flight or failed recently;
 *  - red: nothing for fifteen minutes, which means the tunnel, the token or the network is wrong.
 *
 * Tap it for the age of the last answer and the last error. Nothing is cached or stored: it reads
 * [ServerLink]'s own clock, so it cannot disagree with the traffic it is reporting on.
 */
object SyncStatus {
    private const val TAG = "limited-sync"
    private const val DOT_TAG = "limited_sync_dot"

    private const val GREEN_MS = 2 * 60 * 1000L
    private const val AMBER_MS = 15 * 60 * 1000L
    private const val TICK_MS = 5_000L

    @Volatile private var lastAnswerAt = 0L
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var lastError = ""

    /** Called by [ServerLink] on every request: an attempt is proof of life even when it fails. */
    fun recordAttempt() {
        lastAttemptAt = System.currentTimeMillis()
    }

    /** Called when a host answered, whatever the status code. A 401 is still a live server. */
    fun recordAnswer() {
        lastAnswerAt = System.currentTimeMillis()
        lastError = ""
    }

    /** Called when no host answered at all: the only case that is really a sync failure. */
    fun recordFailure(reason: String) {
        lastError = reason
    }

    private fun color(): Int {
        val now = System.currentTimeMillis()
        if (lastAnswerAt > 0 && now - lastAnswerAt <= GREEN_MS) return 0xFF3DDC84.toInt()
        if (lastAttemptAt > 0 && now - lastAttemptAt <= AMBER_MS) return 0xFFFFC107.toInt()
        return 0xFFE53935.toInt()
    }

    private fun describe(): String {
        val now = System.currentTimeMillis()
        val answered = if (lastAnswerAt == 0L) "never answered" else "answered ${age(now - lastAnswerAt)} ago"
        return if (lastError.isEmpty()) answered else "$answered; last error: $lastError"
    }

    private fun age(millis: Long): String = when {
        millis < 60_000L -> "${millis / 1000}s"
        millis < 3_600_000L -> "${millis / 60_000}m"
        else -> "${millis / 3_600_000}h"
    }

    /** Puts the dot on an activity's content, top-right, and keeps it current while it lives. */
    fun attach(activity: FragmentActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(DOT_TAG) != null) return

        val density = activity.resources.displayMetrics.density
        val size = (10 * density).toInt()
        val margin = (14 * density).toInt()

        val dot = View(activity).apply {
            tag = DOT_TAG
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color())
                setStroke((1 * density).toInt(), 0x66000000)
            }
            contentDescription = "sync status"
            setOnClickListener {
                Toast.makeText(activity, describe(), Toast.LENGTH_LONG).show()
            }
        }

        content.addView(
            dot,
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                topMargin = margin
                rightMargin = margin
            }
        )
        dot.bringToFront()

        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) return
                (dot.background as? GradientDrawable)?.setColor(color())
                handler.postDelayed(this, TICK_MS)
            }
        }
        handler.postDelayed(tick, TICK_MS)
        activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                handler.removeCallbacks(tick)
            }
        })
    }
}
