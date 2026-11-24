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

import android.util.Log
import io.timelimit.android.data.Database
import io.timelimit.android.data.model.PauseQuote
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The quote feed behind the `Quote` check-in.
 *
 * Fetched and cached, never fetched and shown: the pause screen only reads what is already on disk,
 * so a dead network costs nothing and the screen never stalls behind a socket. The pool grows —
 * every refresh adds what is new and an unchanged line is a no-op — because the point is variety,
 * not a fresh quote each time.
 *
 * Must be called on a database/background thread: it does a blocking HTTP request and a synchronous
 * insert.
 */
object PauseQuotes {
    private const val TAG = "PauseQuotes"

    /** ~50 quotes in one request. Verified 24/11; no key, no account, nothing about the user sent. */
    private const val FEED = "https://zenquotes.io/api/quotes"

    /** Single-quote fallback when the bulk feed is down: {text, author}. */
    private const val FALLBACK_FEED = "https://stoic-quotes.com/api/quote"

    private const val MIN_POOL_SIZE = 5
    private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    private const val RETRY_BACKOFF_MS = 6L * 60 * 60 * 1000
    private const val TIMEOUT_MS = 8000

    private var lastAttempt = 0L

    fun refreshIfStale(database: Database) {
        val now = System.currentTimeMillis()

        val count = database.pauseQuote().countSync()
        val lastFetch = database.pauseQuote().lastFetchSync() ?: 0L
        val stale = count < MIN_POOL_SIZE || now - lastFetch > REFRESH_INTERVAL_MS

        if (!stale) return

        // One attempt per six hours. A feed that is down must not become a wakeup every launch.
        if (now - lastAttempt < RETRY_BACKOFF_MS) return

        lastAttempt = now

        val fetched = runCatching { fetchBulk(now) }
            .getOrElse {
                Log.d(TAG, "bulk feed failed: ${it.message}")
                runCatching { fetchSingle(now) }.getOrDefault(emptyList())
            }

        if (fetched.isEmpty()) return

        runCatching { database.pauseQuote().insertIgnoreSync(fetched) }
            .onFailure { Log.w(TAG, "could not store quotes", it) }
    }

    private fun fetchBulk(now: Long): List<PauseQuote> {
        val body = get(FEED)
        val array = JSONArray(body)

        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val text = item.optString("q").trim()
            if (text.isEmpty()) null
            else PauseQuote(text = text, author = item.optString("a").trim().ifEmpty { null }, fetchedAt = now)
        }
    }

    private fun fetchSingle(now: Long): List<PauseQuote> {
        val body = get(FALLBACK_FEED)
        val item = JSONObject(body)
        val text = item.optString("text").trim()

        return if (text.isEmpty()) emptyList()
        else listOf(PauseQuote(text = text, author = item.optString("author").trim().ifEmpty { null }, fetchedAt = now))
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
