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
package io.timelimit.android.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line fetched from the quote feed.
 *
 * Kept on the phone because the pause screen reads only local state: a pause that waits for the
 * network is a pause that fails whenever the network does, and the whole point is that it always
 * ends. The unique index is what makes re-fetching idempotent -- the same feed read twice must not
 * put the same line in the pool twice.
 */
@Entity(
    tableName = "pause_quote",
    indices = [Index(value = ["text"], unique = true)]
)
class PauseQuote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "author")
    val author: String?,
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long
)
