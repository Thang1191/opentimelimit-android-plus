/*
 * Open TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 * Copyright <C> 2020 Marcel Voigt
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
package io.timelimit.android.ui.main

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import io.timelimit.android.BuildConfig
import io.timelimit.android.agent.HardLockGuard
import io.timelimit.android.agent.Interruptions
import io.timelimit.android.ui.lock.LockActivity
import io.timelimit.android.R
import io.timelimit.android.coroutines.runAsync
import io.timelimit.android.data.model.User
import io.timelimit.android.data.model.UserType
import io.timelimit.android.livedata.*
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.sync.actions.ParentAction
import io.timelimit.android.sync.actions.apply.ApplyActionUtil

class ActivityViewModel(application: Application): AndroidViewModel(application) {
    companion object {
        private const val LOG_TAG = "ActivityViewModel"

        /**
         * Actions that hand time or freedom back. The challenge that guards them is a fixed string
         * anyone can type, so passing it from the app's own screens is just as much an escape as
         * passing it at the wall -- the difference is only which door was used.
         */
        private val WEAKENING_ACTIONS = setOf(
                "IncrementCategoryExtraTimeAction",
                "SetCategoryExtraTimeAction",
                "UpdateCategoryTemporarilyBlockedAction",
                "SetUserDisableLimitsUntilAction"
        )
    }

    val logic = DefaultAppLogic.with(application)
    private val database = logic.database

    val shouldHighlightAuthenticationButton = MutableLiveData<Boolean>().apply { value = false }

    private val authenticatedUserMetadata = MutableLiveData<AuthenticatedUser?>().apply { value = null }
    private val deviceUser = logic.deviceUserEntry

    private val authenticatedUserChecked = authenticatedUserMetadata.switchMap { user ->
        val userEntryLive =
            if (user == null) liveDataFromNullableValue(null)
            else database.user().getUserByIdLive(user.userId)

        userEntryLive.map { userEntry ->
            if (userEntry == null) null
            else {
                val valid = when (user) {
                    is AuthenticatedUser.Password -> user.passwordHash == userEntry.password
                    is AuthenticatedUser.LocalAuth -> true
                    null -> false
                }

                if (valid) userEntry
                else null
            }
        }
    }

    private val authenticatedChildChecked: LiveData<User?> = deviceUser.map { user ->
        if (user?.type == UserType.Child && user.allowSelfLimitAdding) {
            user
        } else null
    }

    val authenticatedUserOrChild: LiveData<User?> =
        mergeLiveDataWaitForValues(authenticatedChildChecked, authenticatedUserChecked)
            .map { (localChild, authenticatedUser) ->
                authenticatedUser ?: localChild
            }
            .ignoreUnchanged()

    val authenticatedUser = authenticatedUserOrChild.map { if (it?.type != UserType.Parent) null else it }

    fun isParentAuthenticated(): Boolean {
        val user = authenticatedUserOrChild.value

        return user != null && user.type == UserType.Parent
    }

    fun isParentOrChildAuthenticated(childId: String): Boolean {
        val user = authenticatedUserOrChild.value

        return user != null && (user.type == UserType.Parent || user.id == childId)
    }

    fun requestAuthentication() {
        shouldHighlightAuthenticationButton.value = true
    }

    fun requestAuthenticationOrReturnTrue(): Boolean {
        if (isParentAuthenticated()) {
            return true
        } else {
            requestAuthentication()

            return false
        }
    }

    fun requestAuthenticationOrReturnTrueAllowChild(childId: String): Boolean {
        if (isParentOrChildAuthenticated(childId)) {
            return true
        } else {
            requestAuthentication()

            return false
        }
    }

    fun tryDispatchParentAction(action: ParentAction, allowAsChild: Boolean = false): Boolean = tryDispatchParentActions(listOf(action), allowAsChild)

    fun tryDispatchParentActions(actions: List<ParentAction>, allowAsChild: Boolean = false): Boolean {
        val status = authenticatedUserOrChild.value

        if (status == null || (status.type != UserType.Parent && !allowAsChild)) {
            requestAuthentication()
            return false
        }

        runAsync {
            try {
                for (action in actions) {
                    // A hard category is not editable from this device, even with parent authority.
                    // The edit never reaches the database; the agent's own path does not come through
                    // here, which is exactly how opening one still works -- via the server.
                    val lockedCategory = HardLockGuard.refusal(getApplication(), action)

                    if (lockedCategory != null) {
                        Toast.makeText(
                                getApplication(),
                                getApplication<Application>().getString(
                                        R.string.hard_lock_category_is_locked,
                                        lockedCategory
                                ),
                                Toast.LENGTH_LONG
                        ).show()

                        return@runAsync
                    }

                    ApplyActionUtil.applyParentAction(
                            action = action,
                            database = database,
                            platformIntegration = logic.platformIntegration,
                            fromChildSelfLimitAddChildUserId = if (allowAsChild && status.type == UserType.Child) status.id else null,
                            parentUserId = if (status.type == UserType.Parent) status.id else null
                    )
                }

                // The other half of the honesty metric: a change made while the wall is frontmost is
                // the user getting past it. Recording here rather than on each button means a door
                // added later is counted without being taught to say its name.
                val weakening = actions.map { it.javaClass.simpleName }.firstOrNull { name ->
                    name in WEAKENING_ACTIONS || name.contains("SpecialMode")
                }

                if (weakening != null || LockActivity.resumed > 0) {
                    Interruptions.record(
                            context = getApplication(),
                            kind = "escape",
                            outcome = "escaped",
                            category = null,
                            packageName = null,
                            reason = (if (LockActivity.resumed > 0) "wall:" else "app:") + (weakening ?: "action")
                    )
                }
            } catch (ex: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "error dispatching actions", ex)
                }

                Toast.makeText(getApplication(), R.string.error_general, Toast.LENGTH_SHORT).show()
            }
        }

        return true
    }

    fun setAuthenticatedUser(user: AuthenticatedUser) {
        authenticatedUserMetadata.value = user
        shouldHighlightAuthenticationButton.value = false
    }

    fun getAuthenticatedUser() = authenticatedUserMetadata.value

    fun logOut() {
        authenticatedUserMetadata.value = null
    }
}

sealed class AuthenticatedUser {
    abstract val userId: String

    data class Password(
        override val userId: String,
        val passwordHash: String
    ): AuthenticatedUser()

    sealed class LocalAuth: AuthenticatedUser() {
        data class U2f(override val userId: String): LocalAuth()
        data class ScanCode(override val userId: String): LocalAuth()
        data class Biometric(override val userId: String): LocalAuth()
    }
}