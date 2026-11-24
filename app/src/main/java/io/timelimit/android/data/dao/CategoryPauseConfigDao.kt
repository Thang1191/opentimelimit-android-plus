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

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.timelimit.android.data.model.CategoryPauseConfig

@Dao
interface CategoryPauseConfigDao {
    @Query("SELECT * FROM category_pause_config WHERE category_id = :categoryId")
    fun getByCategoryIdSync(categoryId: String): CategoryPauseConfig?

    @Query("SELECT * FROM category_pause_config WHERE category_id = :categoryId")
    fun getByCategoryIdLive(categoryId: String): LiveData<CategoryPauseConfig?>

    /**
     * Only the enabled rows, watched by [io.timelimit.android.agent.PausePoint] so the enforcement
     * loop never touches the database: a settings change lands in memory within a frame and a
     * disabled category disappears from the watch list on its own.
     */
    @Query("SELECT * FROM category_pause_config WHERE enabled = 1")
    fun getAllEnabledLive(): LiveData<List<CategoryPauseConfig>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplaceSync(item: CategoryPauseConfig)

    @Query("DELETE FROM category_pause_config WHERE category_id = :categoryId")
    fun deleteByCategoryId(categoryId: String)
}
