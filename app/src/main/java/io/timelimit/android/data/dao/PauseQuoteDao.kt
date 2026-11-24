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
package io.timelimit.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.timelimit.android.data.model.PauseQuote

@Dao
interface PauseQuoteDao {
    @Query("SELECT * FROM pause_quote ORDER BY id")
    fun getAllSync(): List<PauseQuote>

    @Query("SELECT COUNT(*) FROM pause_quote")
    fun countSync(): Long

    /** When the pool was last topped up, so a dead feed is not hit again on the next launch. */
    @Query("SELECT MAX(fetched_at) FROM pause_quote")
    fun lastFetchSync(): Long?

    /** IGNORE, not REPLACE: the unique index turns a repeated line into a no-op without a new id. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnoreSync(items: List<PauseQuote>)

    @Query("DELETE FROM pause_quote WHERE id IN (:ids)")
    fun deleteByIdsSync(ids: List<Long>)
}
