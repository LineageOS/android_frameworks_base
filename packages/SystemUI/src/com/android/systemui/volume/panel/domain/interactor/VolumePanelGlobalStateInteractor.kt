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

package com.android.systemui.volume.panel.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.volume.panel.data.repository.VolumePanelGlobalStateRepository
import com.android.systemui.volume.panel.shared.model.VolumePanelGlobalState
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine

@SysUISingleton
class VolumePanelGlobalStateInteractor
@Inject
constructor(
    @param:Application private val coroutineScope: CoroutineScope,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val repository: VolumePanelGlobalStateRepository,
    private val activityStarter: ActivityStarter,
) {

    val globalState: StateFlow<VolumePanelGlobalState> =
        repository.globalState
            .mapLatest { state ->
                if (state.isVisible) {
                    awaitKeyguard()
                }
                state
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, VolumePanelGlobalState(false))

    init {
        deviceEntryInteractor.isDeviceEntered
            .onEach { isDeviceEntered ->
                if (!isDeviceEntered) {
                    setVisible(false)
                }
            }
            .launchIn(coroutineScope)
    }

    fun setVisible(isVisible: Boolean) {
        if (isVisible && !deviceEntryInteractor.isDeviceEntered.value) {
            return
        }
        repository.updateVolumePanelState { it.copy(isVisible = isVisible) }
    }

    private suspend fun awaitKeyguard() {
        return suspendCancellableCoroutine { continuation ->
            activityStarter.dismissKeyguardThenExecute(
                /* action = */ {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                    false
                },
                /* cancel = */ {},
                /* afterKeyguardGone = */ true,
            )
        }
    }
}
