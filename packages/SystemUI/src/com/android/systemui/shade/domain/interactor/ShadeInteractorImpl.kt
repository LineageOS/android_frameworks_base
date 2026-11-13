/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.shade.domain.interactor

import com.android.app.tracing.coroutines.flow.flowName
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.isPulsing
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.policy.data.repository.UserSetupRepository
import com.android.systemui.statusbar.policy.domain.interactor.DeviceProvisioningInteractor
import com.android.systemui.user.domain.interactor.UserSwitcherInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The non-empty [ShadeInteractor] implementation. */
@SysUISingleton
class ShadeInteractorImpl
@Inject
constructor(
    @Application val scope: CoroutineScope,
    deviceProvisioningInteractor: DeviceProvisioningInteractor,
    dozeParams: DozeParameters,
    keyguardRepository: KeyguardRepository,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    powerInteractor: PowerInteractor,
    userSetupRepository: UserSetupRepository,
    userSwitcherInteractor: UserSwitcherInteractor,
    private val baseShadeInteractor: BaseShadeInteractor,
    sceneInteractor: SceneInteractor,
    shadeStatusBarComponentsInteractor: ShadeStatusBarComponentsInteractor,
) : ShadeInteractor, BaseShadeInteractor by baseShadeInteractor {

    override val isShadeEnabled: StateFlow<Boolean> =
        shadeStatusBarComponentsInteractor.disableFlags
            .map { it.isShadeEnabled() }
            .flowName("isShadeEnabled")
            .stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    override val isQsEnabled: StateFlow<Boolean> =
        shadeStatusBarComponentsInteractor.disableFlags
            .map { it.isQuickSettingsEnabled() }
            .flowName("isQsEnabled")
            .stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    override val isAnyFullyExpanded: StateFlow<Boolean> =
        anyExpansion
            .map { it >= 1f }
            .distinctUntilChanged()
            .flowName("isAnyFullyExpanded")
            .stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    override val isShadeFullyExpanded: Flow<Boolean> =
        baseShadeInteractor.shadeExpansion.map { it >= 1f }.distinctUntilChanged()

    override val isShadeAnyExpanded: StateFlow<Boolean> =
        baseShadeInteractor.shadeExpansion
            .map { it > 0 }
            .flowName("isShadeAnyExpanded")
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val isShadeFullyCollapsed: Flow<Boolean> =
        baseShadeInteractor.shadeExpansion.map { it <= 0f }.distinctUntilChanged()

    override val isUserInteracting: StateFlow<Boolean> =
        combine(
                isUserInteractingWithShade,
                isUserInteractingWithQs,
                sceneInteractor.isRemoteUserInteractionOngoing,
            ) { shade, qs, isRemoteUserInteractionOngoing ->
                shade || qs || isRemoteUserInteractionOngoing
            }
            .flowName("isUserInteracting")
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val isShadeTouchable: Flow<Boolean> =
        combine(
            powerInteractor.isAsleep,
            keyguardTransitionInteractor.isInTransition(Edge.create(to = KeyguardState.AOD)),
            keyguardRepository.dozeTransitionModel.map { it.to.isPulsing() },
        ) { isAsleep, isTransitioningToAod, isPulsing ->
            when {
                // If the device is transitioning to AOD, only accept touches if still animating.
                isTransitioningToAod -> dozeParams.shouldControlScreenOff()
                // If the device is asleep, only accept touches if there's a pulse
                isAsleep -> isPulsing
                else -> true
            }
        }

    override val isExpandToQsEnabled: Flow<Boolean> =
        combine(
            shadeStatusBarComponentsInteractor.disableFlags,
            isShadeEnabled,
            keyguardRepository.isDozing,
            userSetupRepository.isUserSetUp,
            deviceProvisioningInteractor.isDeviceProvisioned,
        ) { disableFlags, isShadeEnabled, isDozing, isUserSetup, isDeviceProvisioned ->
            isDeviceProvisioned &&
                // Disallow QS during setup if it's a simple user switcher. (The user intends to
                // use the lock screen user switcher, QS is not needed.)
                (isUserSetup || !userSwitcherInteractor.isSimpleUserSwitcher) &&
                isShadeEnabled &&
                disableFlags.isQuickSettingsEnabled() &&
                !isDozing
        }
}
