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

/**
 * The lines the pause screen shows for a pictures check-in.
 *
 * Two jobs, one list. It is the built-in voice of the pause, and it is what the settings field is
 * pre-filled with, so the lines are something the user edits rather than something they only get if
 * they think to overwrite a blank box. Keeping both in one place is what stops the screen and the
 * settings page drifting apart.
 *
 * The stored text lives in the category's `copy` column, one line per pause.
 */
object PausePrompts {
    val DEFAULT_LINES: List<String> = listOf(
        "Remember this?",
        "Remember this? You were happy here. Nothing about that has changed.",
        "This was worth the day it took. The feed is not.",
        "Whoever is in this picture would rather you came back to them.",
        "Look at it for ten seconds. That is the whole ask.",
        "You kept this on purpose. Keep it a moment longer.",
        "The good part of today already happened, and it is in here.",
        "Nothing you are about to open will be better than this.",
        "You will not remember what you were about to look at."
    )

    /** The stored text as lines. Blank or empty falls back to the built-ins. */
    fun lines(copy: String?): List<String> {
        val parsed = copy
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return parsed.ifEmpty { DEFAULT_LINES }
    }

    /** What the settings field starts out showing. */
    fun editorText(copy: String?): String = lines(copy).joinToString("\n")

    /** True when the typed lines are still the built-ins, in which case there is nothing to store. */
    fun isDefault(text: String): Boolean = text.lines().map { it.trim() }.filter { it.isNotEmpty() } == DEFAULT_LINES
}
