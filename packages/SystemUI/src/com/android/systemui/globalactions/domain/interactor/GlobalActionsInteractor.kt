/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.globalactions.domain.interactor

import android.app.ActivityManager
import android.os.UserManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.globalactions.data.repository.GlobalActionsRepository
import com.android.systemui.globalactions.shared.model.GlobalActionType
import com.android.systemui.plugins.GlobalActions.GlobalActionsManager
import com.android.systemui.statusbar.policy.domain.interactor.DeviceProvisioningInteractor
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

@SysUISingleton
class GlobalActionsInteractor
@Inject
constructor(
    @param:Background private val bgDispatcher: CoroutineDispatcher,
    private val repository: GlobalActionsRepository,
    private val globalActionsManager: GlobalActionsManager,
    private val userManager: UserManager,
    private val userRepository: UserRepository,
    deviceUnlockedInteractor: DeviceUnlockedInteractor,
    deviceProvisioningInteractor: DeviceProvisioningInteractor,
) {
    /** Is the global actions dialog visible. */
    val isVisible: StateFlow<Boolean> = repository.isVisible

    /**
     * The list of all possible global actions. This list is used to determine what actions can be
     * displayed but it does not guarantee that they will be displayed. The actions that are finally
     * displayed are determined by device state.
     */
    val possibleGlobalActions: List<GlobalActionType>
        get() = repository.possibleGlobalActions

    /**
     * The list of global actions that are currently available to be displayed to the user.
     *
     * This flow combines the possible global actions with the current device state (provisioned,
     * locked) to determine which actions should be filtered out.
     */
    val availableGlobalActions: Flow<List<GlobalActionType>> =
        combine(
            deviceProvisioningInteractor.isDeviceProvisioned,
            deviceUnlockedInteractor.deviceUnlockStatus,
        ) { isProvisioned, unlockStatus ->
            repository.possibleGlobalActions.filterNot { action ->
                val isBlockedByLock =
                    !unlockStatus.isUnlocked && action in repository.lockedDeviceStateBlockList
                val isBlockedByProvisioning =
                    !isProvisioned && action in repository.unprovisionedDeviceStateBlockList

                isBlockedByLock || isBlockedByProvisioning
            }
        }

    /** Notifies that the global actions dialog is shown. */
    fun onShown() {
        repository.setVisible(true)
    }

    /** Notifies that the global actions dialog has been dismissed. */
    fun onDismissed() {
        repository.setVisible(false)
    }

    /**
     * Initiates a device shutdown.
     *
     * @return True if the shutdown action was successfully initiated, false otherwise (e.g., monkey
     *   user).
     */
    suspend fun shutdown(): Boolean =
        withContext(bgDispatcher) {
            if (ActivityManager.isUserAMonkey()) {
                return@withContext false
            }

            globalActionsManager.shutdown()
            true
        }

    /**
     * Initiates a device reboot.
     *
     * @param safeMode If true, attempts to reboot into safe mode.
     * @return True if the reboot action was successfully initiated, false otherwise (e.g., monkey
     *   user, or restricted safe mode).
     */
    suspend fun reboot(safeMode: Boolean = false): Boolean =
        withContext(bgDispatcher) {
            if (ActivityManager.isUserAMonkey()) {
                return@withContext false
            }

            if (safeMode && isSafeBootRestricted()) {
                return@withContext false
            }

            globalActionsManager.reboot(safeMode, null)
            true
        }

    private fun isSafeBootRestricted(): Boolean {
        val currentUser = userRepository.selectedUser.value.userInfo.userHandle
        return userManager.hasUserRestrictionForUser(UserManager.DISALLOW_SAFE_BOOT, currentUser)
    }
}
