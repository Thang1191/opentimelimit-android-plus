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
import androidx.room.PrimaryKey

/**
 * Pause Point settings for one category.
 *
 * A rule the user authors on the phone, not a lever: it is configured from the category's settings
 * page and it is deliberately not part of the sync protocol, because enforcement lives on this device
 * and a rule that has to round-trip through a server before it can slow you down is a rule that can
 * be edited while you are weak.
 *
 * Absent means off. A row always exists once the user touches the section, so that "off" and "never
 * configured" are the same thing to every reader and nobody has to guess what the defaults were.
 */
@Entity(tableName = "category_pause_config")
data class CategoryPauseConfig(
    @PrimaryKey
    @ColumnInfo(name = "category_id")
    val categoryId: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = false,
    @ColumnInfo(name = "wait_seconds")
    val waitSeconds: Int = DEFAULT_WAIT_SECONDS,
    @ColumnInfo(name = "cooldown_minutes")
    val cooldownMinutes: Int = DEFAULT_COOLDOWN_MINUTES,
    @ColumnInfo(name = "check_in_type")
    val checkInType: Int = CHECK_IN_BREATH,
    @ColumnInfo(name = "folder_uri")
    val folderUri: String? = null,
    @ColumnInfo(name = "copy")
    val copy: String? = null
) {
    companion object {
        const val CHECK_IN_BREATH = 0
        const val CHECK_IN_IMAGES = 1
        const val CHECK_IN_QUOTE = 2
        const val CHECK_IN_TIMER = 3
        const val CHECK_IN_WRITE = 4

        const val DEFAULT_WAIT_SECONDS = 10
        const val DEFAULT_COOLDOWN_MINUTES = 20
        const val MIN_WAIT_SECONDS = 3
        const val MAX_WAIT_SECONDS = 60
        const val MIN_COOLDOWN_MINUTES = 0
        const val MAX_COOLDOWN_MINUTES = 120

        /** The user-facing names, indexed by the stored constants. */
        val CHECK_IN_NAMES = listOf("Breath", "Pictures", "Quote", "Timer", "Write a line")

        fun default(categoryId: String) = CategoryPauseConfig(categoryId = categoryId)
    }
}
