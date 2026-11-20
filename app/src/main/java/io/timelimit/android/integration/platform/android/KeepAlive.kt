/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.integration.platform.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.timelimit.android.BuildConfig

/**
 * Keeps the blocking loop alive on ROMs that stop apps behind the user's back.
 *
 * ColorOS kills this package from its own battery machinery (`am_kill ... due to o-stop(40)` in
 * `logcat`, `reason=13` in `dumpsys activity exit-info`). Disabling the foreground service is not
 * enough by itself, because a killed process has nothing left to restart it - the resurrection has to
 * come from outside the process:
 *
 *  - a repeating exact alarm (heartbeat), which AlarmManager delivers even when the app is not running,
 *  - a persisted job, which survives a reboot and a data reset of the app's timers,
 *  - the restart hooks in [BackgroundService] for the times the process dies while the service runs.
 *
 * All paths end in [ensureAlive], which (re)starts the foreground service and re-arms the heartbeat.
 * Starting a foreground service from the background is normally forbidden since Android 12; this app is
 * exempt because it holds the "draw over other apps" permission, and any failure is caught rather than
 * allowed to crash the receiver.
 */
object KeepAlive {
    private const val LOG_TAG = "KeepAlive"

    const val ACTION_HEARTBEAT = "io.timelimit.android.keepalive.HEARTBEAT"

    /** How often the alarm fires. Two minutes is cheap and short enough that blocking is never off for long. */
    private const val HEARTBEAT_INTERVAL_MS = 2 * 60 * 1000L

    /** Restart shortly after the service is destroyed, rather than waiting for the next heartbeat. */
    private const val RESTART_DELAY_MS = 5 * 1000L

    private const val JOB_ID = 4711
    private const val CHAIN_JOB_ID = 4714
    private const val HEARTBEAT_REQUEST = 4712
    private const val RESTART_REQUEST = 4713

    private const val PREFS_NAME = "keepalive"
    private const val KEY_ARMED_AT = "armed_at"

    /** Ignore `setRepeating` calls that arrive right after the last one; re-arming re-bases the alarm. */
    private const val REARM_GUARD_MS = 60 * 1000L

    /** The chained job comes back this often; two minutes is short enough that blocking is never off for long. */
    private const val CHAIN_INTERVAL_MS = 2 * 60 * 1000L

    fun start(context: Context) {
        scheduleHeartbeat(context)
        scheduleJob(context)
        scheduleChainedJob(context)
    }

    /**
     * Arms the self-repeating heartbeat.
     *
     * A one-shot alarm re-armed from every service start never fires: each start pushes the next
     * delivery two minutes out, so a process that is restarted more often than that starves its own
     * watchdog. `setRepeating` lets AlarmManager own the schedule instead - it is armed once, and the
     * timestamp guard keeps repeated `setRepeating` calls from re-basing it.
     */
    fun scheduleHeartbeat(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        if (now - prefs.getLong(KEY_ARMED_AT, 0L) < REARM_GUARD_MS) return

        try {
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                now + HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                heartbeatIntent(context)
            )

            prefs.edit().putLong(KEY_ARMED_AT, now).apply()
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "could not arm the heartbeat", ex)
        }
    }

    /** Used from the service's own teardown: come back in a few seconds, not in two minutes. */
    fun restartSoon(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = System.currentTimeMillis() + RESTART_DELAY_MS

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, restartIntent(context))
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "could not schedule the restart", ex)
            scheduleHeartbeat(context)
        }
    }

    /** Brings the foreground service back if it is not running, then re-arms the heartbeat. */
    fun ensureAlive(context: Context) {
        val appContext = context.applicationContext

        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "ensureAlive")
        }

        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, BackgroundService::class.java))
        } catch (ex: Exception) {
            // Android 12+ refuses this unless the app is exempt (overlay permission covers us); a
            // refusal must not take the receiver down with it.
            Log.w(LOG_TAG, "could not restart the foreground service", ex)
        }

        scheduleHeartbeat(appContext)
        scheduleJob(appContext)
        scheduleChainedJob(appContext)
    }

    /**
     * A one-shot job that re-schedules itself, so there is a second resurrection path besides the
     * alarm that does not depend on any alarm bookkeeping at all.
     */
    fun scheduleChainedJob(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return

        val job = JobInfo.Builder(CHAIN_JOB_ID, ComponentName(context, KeepAliveJobService::class.java))
            .setMinimumLatency(CHAIN_INTERVAL_MS)
            .setPersisted(true)
            .build()

        try {
            jobScheduler.schedule(job)
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "could not schedule the chained job", ex)
        }
    }

    private fun scheduleJob(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return

        val job = JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJobService::class.java))
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build()

        try {
            if (jobScheduler.getPendingJob(JOB_ID) == null) {
                jobScheduler.schedule(job)
            }
        } catch (ex: Exception) {
            Log.w(LOG_TAG, "could not schedule the persisted job", ex)
        }
    }

    private fun heartbeatIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        HEARTBEAT_REQUEST,
        Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_HEARTBEAT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun restartIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        RESTART_REQUEST,
        Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_HEARTBEAT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Entry point for every out-of-process wake up: the heartbeat, boot, a package update (which is a
 * regular event here because the app is side loaded repeatedly) and the restart after a service kill.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (BuildConfig.DEBUG) {
            Log.d("KeepAlive", "woke up: ${intent?.action}")
        }

        KeepAlive.ensureAlive(context)
    }
}

/**
 * Second resurrection path. A persisted job survives a reboot and is not affected by the app's own
 * alarms being wiped, so it covers the case where the heartbeat never gets re-armed.
 */
class KeepAliveJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (BuildConfig.DEBUG) {
            Log.d("KeepAlive", "job ${params?.jobId}")
        }

        KeepAlive.ensureAlive(this)

        // The chained job is one-shot by design; re-arm the next link.
        KeepAlive.scheduleChainedJob(this)

        jobFinished(params, false)

        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
