/*
 * Open TimeLimit Copyright <C> 2019 Jonas Lochmann
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
package io.timelimit.android

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.jakewharton.threetenabp.AndroidThreeTen

class Application : Application() {
    override fun onCreate() {
        super.onCreate()

        AndroidThreeTen.init(this)

        // Apply Material You Dynamic Colors
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Arm the resurrection chain: heartbeat alarm + persisted job + restart hooks in the service.
        // ColorOS stops this package from its own battery machinery, so something outside the process
        // has to be able to bring it back.
        io.timelimit.android.integration.platform.android.KeepAlive.start(this)
    }
}
