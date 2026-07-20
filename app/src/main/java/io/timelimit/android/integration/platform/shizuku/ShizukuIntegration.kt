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

import android.content.pm.PackageManager
import android.util.Log
import io.timelimit.android.BuildConfig
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Singleton managing the Shizuku connection lifecycle and providing
 * methods for elevated shell operations (app disable/enable, DNS, work profile).
 *
 * Shizuku must be installed and running on the device. The app must declare
 * the ShizukuProvider in AndroidManifest.xml.
 */
object ShizukuIntegration {
    private const val LOG_TAG = "ShizukuIntegration"

    // Permission request code for Shizuku permission callback
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 19283

    private val stateListeners = mutableListOf<(Boolean) -> Unit>()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "Shizuku binder received")
        }
        notifyStateListeners(true)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "Shizuku binder dead")
        }
        notifyStateListeners(false)
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Shizuku permission result: granted=$granted")
            }
            notifyStateListeners(granted && isShizukuAvailable())
        }
    }

    /**
     * Initialize Shizuku listeners. Call once during app startup.
     */
    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (ex: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(LOG_TAG, "Failed to initialize Shizuku listeners", ex)
            }
        }
    }

    /**
     * Checks if Shizuku is installed, running, and we have permission.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && hasPermission()
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Checks if Shizuku binder is alive (Shizuku app is running).
     */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Checks if we already have Shizuku permission.
     */
    fun hasPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Requests Shizuku permission from the user.
     * Result will be delivered via OnRequestPermissionResultListener.
     */
    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !hasPermission()) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (ex: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(LOG_TAG, "Failed to request Shizuku permission", ex)
            }
        }
    }

    /**
     * Executes a shell command via Shizuku with elevated (shell-level) privileges.
     *
     * Uses reflection to access Shizuku's private newProcess method,
     * which returns a standard java.lang.Process.
     *
     * @param command The shell command to execute
     * @return The command's stdout output, or null on failure
     */
    fun executeCommand(command: String): String? {
        if (!isShizukuAvailable()) {
            if (BuildConfig.DEBUG) {
                Log.w(LOG_TAG, "executeCommand: Shizuku not available")
            }
            return null
        }

        return try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            
            val shProcess = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val reader = BufferedReader(InputStreamReader(shProcess.inputStream))
            val errReader = BufferedReader(InputStreamReader(shProcess.errorStream))
            val output = reader.readText()
            val errOutput = errReader.readText()
            val exitCode = shProcess.waitFor()
            reader.close()
            errReader.close()

            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "executeCommand '$command' -> exitCode=$exitCode, output=$output, err=$errOutput")
            }

            if (exitCode == 0) output.trim() else null
        } catch (ex: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(LOG_TAG, "executeCommand '$command' failed", ex)
            }
            null
        }
    }



    /**
     * Disables a package for user 0 using `pm disable-user`.
     * @param packageName The package to disable
     * @return true if the command succeeded
     */
    fun disablePackage(packageName: String): Boolean {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "disablePackage: $packageName")
        }
        val result = executeCommand("pm disable-user --user 0 $packageName")
        return result != null
    }

    /**
     * Re-enables a previously disabled package using `pm enable`.
     * @param packageName The package to enable
     * @return true if the command succeeded
     */
    fun enablePackage(packageName: String): Boolean {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "enablePackage: $packageName")
        }
        val result = executeCommand("pm enable $packageName")
        return result != null
    }

    /**
     * Sets the system-wide Private DNS mode and hostname.
     * @param hostname The DNS hostname to force (e.g., "dns.google")
     * @return true if both commands succeeded
     */
    fun setPrivateDns(hostname: String): Boolean {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "setPrivateDns: $hostname")
        }
        val modeResult = executeCommand("settings put global private_dns_mode hostname")
        val specifierResult = executeCommand("settings put global private_dns_specifier $hostname")
        return modeResult != null && specifierResult != null
    }

    /**
     * Clears forced Private DNS, restoring it to automatic mode.
     * @return true if the command succeeded
     */
    fun clearPrivateDns(): Boolean {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "clearPrivateDns")
        }
        val result = executeCommand("settings put global private_dns_mode opportunistic")
        return result != null
    }

    /**
     * Gets the current Private DNS specifier hostname.
     * @return The current hostname, or null if not set or on failure
     */
    fun getPrivateDnsSpecifier(): String? {
        return executeCommand("settings get global private_dns_specifier")
    }

    /**
     * Gets the current Private DNS mode.
     * @return The mode string (e.g., "hostname", "opportunistic", "off"), or null on failure
     */
    fun getPrivateDnsMode(): String? {
        return executeCommand("settings get global private_dns_mode")
    }

    /**
     * Register a listener for Shizuku availability state changes.
     * @param listener Called with true when Shizuku becomes available, false when it dies
     */
    fun registerStateListener(listener: (Boolean) -> Unit) {
        synchronized(stateListeners) {
            stateListeners.add(listener)
        }
    }

    /**
     * Unregister a previously registered state listener.
     */
    fun unregisterStateListener(listener: (Boolean) -> Unit) {
        synchronized(stateListeners) {
            stateListeners.remove(listener)
        }
    }

    private fun notifyStateListeners(available: Boolean) {
        synchronized(stateListeners) {
            stateListeners.forEach { it(available) }
        }
    }
}
