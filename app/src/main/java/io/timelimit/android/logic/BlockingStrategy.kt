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
package io.timelimit.android.logic

import android.util.Log
import io.timelimit.android.integration.platform.shizuku.ShizukuIntegration
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared skeleton for the three enforcement strategies (Shizuku package disabling, forced Private
 * DNS, work profile quiet mode).
 *
 * They all ran the same machinery duplicated in three files: a single-thread executor, a
 * poll-and-reset `pendingSync` flag, a serialized loop that calls `updateBlockingSync()` and sleeps
 * 500 ms, the same four observers (battery status, time modification, user/device derived data,
 * Shizuku availability) and the same "did the data load yet" gate. Only the apply/restore body ever
 * differed, so that is all a subclass implements now.
 *
 * A strategy never decides *what* should be blocked - that is [CategoryHandlingCache]'s job, filled
 * per tick from the derived data. It only converges the device towards that answer.
 *
 * Subclasses must call [startObserving] from their own `init`, as the last statement. Doing it from
 * the base constructor would start delivering observer callbacks before the subclass's own fields
 * exist.
 */
abstract class BlockingStrategy(protected val appLogic: AppLogic) {
    companion object {
        private const val SYNC_INTERVAL_MS = 500L
    }

    protected val logTag: String get() = javaClass.simpleName

    /** Per-tick cache of "should this category be blocked right now", shared by all strategies. */
    protected val categoryHandlingCache = CategoryHandlingCache()

    protected val userAndDeviceRelatedDataLive =
        appLogic.database.derivedDataDao().getUserAndDeviceRelatedDataLive()

    @Volatile
    protected var didLoadData = false

    private val pendingSync = AtomicBoolean(true)
    private val executor = Executors.newSingleThreadExecutor()
    private var observing = false

    private val backgroundRunnable = Runnable {
        while (pendingSync.getAndSet(false)) {
            try {
                updateBlockingSync()
            } catch (ex: Exception) {
                // A strategy blowing up must not kill the loop that restores blocking later.
                Log.w(logTag, "updateBlockingSync failed", ex)
            }

            Thread.sleep(SYNC_INTERVAL_MS)
        }
    }

    protected fun triggerUpdate() {
        pendingSync.set(true)
        executor.submit(backgroundRunnable)
    }

    /**
     * Registers the common observers. Call once, at the end of the subclass `init`.
     */
    protected fun startObserving() {
        if (observing) return

        observing = true

        appLogic.platformIntegration.getBatteryStatusLive().observeForever { triggerUpdate() }
        appLogic.realTimeLogic.registerTimeModificationListener { triggerUpdate() }
        userAndDeviceRelatedDataLive.observeForever {
            didLoadData = true
            triggerUpdate()
        }

        ShizukuIntegration.registerStateListener { available ->
            if (available) {
                onShizukuAvailable()

                triggerUpdate()
            }
        }
    }

    /** Hook for strategies that need to do something the moment Shizuku comes back. */
    protected open fun onShizukuAvailable() {}

    /** Converge the device towards [categoryHandlingCache]. Runs on the strategy's own thread. */
    protected abstract fun updateBlockingSync()
}
