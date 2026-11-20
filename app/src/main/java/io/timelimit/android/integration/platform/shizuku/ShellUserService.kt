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
package io.timelimit.android.integration.platform.shizuku

import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The UserService that Shizuku starts in its own process. Everything here runs with the identity
 * Shizuku was started with (shell / uid 2000 over adb, root / uid 0 with Sui), which is what makes
 * `pm disable-user`, `settings put global ...` and friends work.
 *
 * Two constructors are declared because Shizuku v13 prefers `UserService(Context)`; older versions
 * only use the no-arg one. `@Keep` stops R8 from removing them in release builds.
 */
class ShellUserService @Keep constructor() : IUserService.Stub() {
    companion object {
        private const val TIMEOUT_SECONDS = 15L
    }

    /**
     * Shizuku v13 prefers this constructor (older versions only use the no-arg one). The context is
     * a package-scoped one created by Shizuku and is not a normal app context, so it is unused.
     */
    @Keep
    constructor(context: android.content.Context) : this()

    /** Reserved by Shizuku. The service process is not killed on unbind, so it has to die here. */
    override fun destroy() {
        System.exit(0)
    }

    override fun runCommand(command: String): String? {
        val process = try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        } catch (ex: Exception) {
            return null
        }

        val output = StringBuilder()

        // Both pipes have to be drained: a chatty command fills the stderr pipe and then blocks
        // forever waiting for a reader that never comes (the reflected newProcess path did this too,
        // and a wedged blocking call is the worst failure mode for a blocker).
        val stdoutDrain = daemonThread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { output.append(it.readText()) }
            } catch (_: Exception) {
            }
        }
        val stderrDrain = daemonThread {
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            } catch (_: Exception) {
            }
        }

        var exitCode = -1
        val exited = CountDownLatch(1)

        daemonThread {
            try {
                exitCode = process.waitFor()
            } catch (_: InterruptedException) {
            } finally {
                exited.countDown()
            }
        }

        if (!exited.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()

            return null
        }

        stdoutDrain.join(500)
        stderrDrain.join(500)

        return if (exitCode == 0) output.toString().trim() else null
    }

    private fun daemonThread(body: () -> Unit) = Thread(body).apply {
        isDaemon = true
        start()
    }
}
