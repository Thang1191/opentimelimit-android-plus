/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 */
package io.timelimit.android.usage

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Batches waiting for the server.
 *
 * The one thing this exists for: usage events live ~24h on the phone, so a batch that is lost
 * because the tunnel was down at the wrong moment is lost for good. Files on disk survive both the
 * tunnel and the process being killed by ColorOS.
 *
 * Deliberately dull: one file per batch, oldest first, trimmed at MAX_AGE_MS. A batch the server
 * has already accepted is deleted, and a batch it has not is left alone -- re-sending is safe
 * because the server's ingest is idempotent.
 *
 * Nothing here can grow without bound: trim() runs on every put.
 */
object UsageOutbox {
    private const val TAG = "limited-sync"
    private const val DIR = "usage-outbox"

    /** Beyond this the detail is worthless: the platform dropped the events two days ago. */
    private const val MAX_AGE_MS = 48L * 60L * 60L * 1000L

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    fun put(context: Context, batch: JSONObject): Boolean = try {
        val file = File(dir(context), "batch-${System.currentTimeMillis()}.json")
        file.writeText(batch.toString())
        trim(context)
        true
    } catch (t: Throwable) {
        Log.d(TAG, "usage outbox: put failed: $t")
        false
    }

    fun pending(context: Context): List<File> = try {
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name } ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    fun drop(file: File) {
        try {
            if (!file.delete()) {
                Log.d(TAG, "usage outbox: could not delete ${file.name}")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "usage outbox: delete failed: $t")
        }
    }

    /** Drops anything too old to be worth sending and keeps the queue small. */
    fun trim(context: Context) {
        try {
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            val files = pending(context)
            for (file in files) {
                if (file.lastModified() < cutoff) {
                    drop(file)
                }
            }
            if (files.size > 50) {
                files.take(files.size - 50).forEach { drop(it) }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "usage outbox: trim failed: $t")
        }
    }
}
