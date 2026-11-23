/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 */
package io.timelimit.android.usage

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.agent.SyncStatus
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * The wire to the lifeup-sync server, deliberately the same wire the LifeUp patch uses.
 *
 * Same host, same fallback, same headers, same token: the token is baked into BuildConfig at Gradle
 * time from the file the server itself reads, so it never appears in git, is never typed on the
 * phone, and the two apps cannot drift apart in how they authenticate.
 *
 * The fallback exists for one case only: the Cloudflare tunnel being down while the phone is on the
 * tailnet. A 4xx is the server answering, and an answer is never retried through another door.
 *
 * There is no dependency here on purpose. The fork has no HTTP client of its own (okio is present
 * but is not an HTTP stack), and one file using HttpURLConnection is cheaper than pulling in OkHttp
 * for two requests a minute.
 */
object ServerLink {
    private const val TAG = "limited-sync"
    private const val PREFS = "limited_server"
    private const val KEY_DEVICE = "device_id"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Stable per install: survives updates, differs between phones and between profiles. */
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE, null)?.let { if (it.length >= 8) return it }
        var id = ""
        try {
            id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (t: Throwable) {
            Log.d(TAG, "usage: android id unavailable: $t")
        }
        if (id.length < 8) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        }
        id = id.replace(Regex("[^A-Za-z0-9_-]"), "")
        prefs.edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    /** What the server shows this phone as. The profile suffix keeps Island's rows distinguishable. */
    fun label(context: Context): String {
        var label = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val profile = UsageTelemetry.profile(context)
        if (profile.isNotEmpty()) {
            label = "$label ($profile)"
        }
        return if (label.length > 40) label.substring(0, 40) else label
    }

    /**
     * POSTs a JSON body. Returns the HTTP status and body, or null when no host answered at all.
     * A null means "try again later"; any status means the server has spoken and the caller decides.
     */
    fun post(context: Context, path: String, body: String): Pair<Int, String?>? {
        val token = BuildConfig.SYNC_TOKEN
        val device = deviceId(context)
        val label = label(context)
        val hosts = if (BuildConfig.SYNC_BASE_FALLBACK.isNotEmpty()) {
            listOf(BuildConfig.SYNC_BASE, BuildConfig.SYNC_BASE_FALLBACK)
        } else {
            listOf(BuildConfig.SYNC_BASE)
        }
        SyncStatus.recordAttempt()
        for (host in hosts) {
            if (host.isEmpty()) {
                continue
            }
            try {
                val url = URL(host + path)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token.isNotEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                connection.setRequestProperty("X-Device", device)
                connection.setRequestProperty("X-Device-Label", label)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }
                connection.disconnect()
                SyncStatus.recordAnswer()
                return status to text
            } catch (t: Throwable) {
                Log.d(TAG, "usage: $path via $host failed: $t")
            }
        }
        SyncStatus.recordFailure("no host answered for $path")
        return null
    }

    /**
     * GET returning the body. readTimeoutMs is a parameter because /v1/watch deliberately parks for
     * longer than any upload: the default would cut a healthy long poll short and look like a dead
     * server every time the phone had nothing to fetch.
     */
    fun get(context: Context, path: String, readTimeoutMs: Int = READ_TIMEOUT_MS): String? {
        val token = BuildConfig.SYNC_TOKEN
        SyncStatus.recordAttempt()
        for (host in listOf(BuildConfig.SYNC_BASE, BuildConfig.SYNC_BASE_FALLBACK)) {
            if (host.isEmpty()) {
                continue
            }
            try {
                val connection = URL(host + path).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = readTimeoutMs
                if (token.isNotEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                connection.setRequestProperty("X-Device", deviceId(context))
                connection.setRequestProperty("X-Device-Label", label(context))
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                SyncStatus.recordAnswer()
                return text
            } catch (t: Throwable) {
                Log.d(TAG, "usage: get $path via $host failed: $t")
            }
        }
        SyncStatus.recordFailure("no host answered for $path")
        return null
    }
}
