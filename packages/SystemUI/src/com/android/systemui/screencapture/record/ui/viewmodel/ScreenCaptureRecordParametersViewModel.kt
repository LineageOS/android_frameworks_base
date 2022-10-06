/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.screencapture.record.ui.viewmodel

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.android.internal.logging.UiEventLogger
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.screencapture.record.camera.domain.interactor.ScreenCaptureCameraHintInteractor
import com.android.systemui.screencapture.record.camera.domain.interactor.ScreenRecordCameraInteractor
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordFeaturesInteractor
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordParametersInteractor
import com.android.systemui.screencapture.record.shared.model.ScreenRecordEvent
import com.android.systemui.screencapture.record.smallscreen.domain.interactor.RecordDetailsTargetInteractor
import com.android.systemui.screencapture.record.smallscreen.shared.model.currentTargetModel
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import com.android.systemui.screenrecord.domain.interactor.ScreenRecordingServiceInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class ScreenCaptureRecordParametersViewModel
@AssistedInject
constructor(
    private val screenRecordingServiceInteractor: ScreenRecordingServiceInteractor,
    private val interactor: ScreenCaptureRecordParametersInteractor,
    screenRecordCameraInteractor: ScreenRecordCameraInteractor,
    screenCaptureRecordFeaturesInteractor: ScreenCaptureRecordFeaturesInteractor,
    recordDetailsTargetInteractor: RecordDetailsTargetInteractor,
    private val screenCaptureCameraHintInteractor: ScreenCaptureCameraHintInteractor,
    private val uiEventLogger: UiEventLogger,
) : HydratedActivatable() {

    val audioSource: ScreenRecordingAudioSource by interactor::audioSource
    val canChangeAudioSource: Boolean by
        interactor.canChangeAudioSource.hydratedStateOf(
            "ScreenCaptureAudioSourceViewModel#canChangeAudioSource"
        )

    var shouldShowTaps: Boolean
        get() = interactor.shouldShowTaps
        set(value) {
            uiEventLogger.logShouldShowTapsChanged(
                shouldShowTaps = value,
                isRecording = screenRecordingServiceInteractor.status.value.isRecording,
            )
            interactor.shouldShowTaps = value
        }

    var shouldShowFrontCamera: Boolean
        get() = interactor.shouldShowFrontCamera
        set(value) {
            uiEventLogger.logShouldShowCameraChanged(
                shouldShowCamera = value,
                isRecording = screenRecordingServiceInteractor.status.value.isRecording,
            )
            interactor.shouldShowFrontCamera = value
        }

    val lowQuality: Boolean by interactor::lowQuality

    val longerDuration: Boolean by interactor::longerDuration

    val hevc: Boolean by interactor::hevc

    var shouldRecordDevice: Boolean
        get() =
            with(interactor) {
                audioSource == ScreenRecordingAudioSource.MIC_AND_INTERNAL ||
                    audioSource == ScreenRecordingAudioSource.INTERNAL
            }
        set(value) {
            interactor.audioSource =
                if (value) {
                    uiEventLogger.log(ScreenRecordEvent.SCREEN_RECORD_AUDIO_DEVICE_ENABLED)
                    if (shouldRecordMicrophone) {
                        ScreenRecordingAudioSource.MIC_AND_INTERNAL
                    } else {
                        ScreenRecordingAudioSource.INTERNAL
                    }
                } else {
                    uiEventLogger.log(ScreenRecordEvent.SCREEN_RECORD_AUDIO_DEVICE_DISABLED)
                    if (shouldRecordMicrophone) {
                        ScreenRecordingAudioSource.MIC
                    } else {
                        ScreenRecordingAudioSource.NONE
                    }
                }
        }

    var shouldRecordMicrophone: Boolean
        get() =
            with(interactor) {
                audioSource == ScreenRecordingAudioSource.MIC_AND_INTERNAL ||
                    audioSource == ScreenRecordingAudioSource.MIC
            }
        set(value) {
            interactor.audioSource =
                if (value) {
                    uiEventLogger.log(ScreenRecordEvent.SCREEN_RECORD_AUDIO_MIC_ENABLED)
                    if (shouldRecordDevice) {
                        ScreenRecordingAudioSource.MIC_AND_INTERNAL
                    } else {
                        ScreenRecordingAudioSource.MIC
                    }
                } else {
                    uiEventLogger.log(ScreenRecordEvent.SCREEN_RECORD_AUDIO_MIC_DISABLED)
                    if (shouldRecordDevice) {
                        ScreenRecordingAudioSource.INTERNAL
                    } else {
                        ScreenRecordingAudioSource.NONE
                    }
                }
        }

    val canUseFrontCamera: Boolean by
        if (screenCaptureRecordFeaturesInteractor.isSelfieAvailable) {
                combine(
                    recordDetailsTargetInteractor.model.map { it.currentTargetModel.canUseCamera },
                    screenRecordCameraInteractor.isCameraSupported,
                ) { canUseCameraForTarget, isCameraSupported ->
                    canUseCameraForTarget && isCameraSupported
                }
            } else {
                flowOf(false)
            }
            .hydratedStateOf("ScreenCaptureAudioSourceViewModel#canUseFrontCamera", true)

    val shouldShowHint: Boolean by derivedStateOf {
        screenCaptureCameraHintInteractor.shouldShowHint
    }

    suspend fun onCameraHintShown() {
        screenCaptureCameraHintInteractor.onHintShown()
    }

    fun setLowQuality(lowQuality: Boolean) {
        interactor.lowQuality = lowQuality
    }

    fun setLongerDuration(longerDuration: Boolean) {
        interactor.longerDuration = longerDuration
    }

    fun setHevc(hevc: Boolean) {
        interactor.hevc = hevc
    }

    @AssistedFactory
    interface Factory {
        fun create(): ScreenCaptureRecordParametersViewModel
    }
}

private fun UiEventLogger.logShouldShowTapsChanged(shouldShowTaps: Boolean, isRecording: Boolean) {
    if (isRecording) {
        if (shouldShowTaps) {
            log(ScreenRecordEvent.SCREEN_RECORD_SHOW_TOUCHES_ENABLED_MID_RECORDING)
        } else {
            log(ScreenRecordEvent.SCREEN_RECORD_SHOW_TOUCHES_DISABLED_MID_RECORDING)
        }
    } else {
        if (shouldShowTaps) {
            log(ScreenRecordEvent.SCREEN_RECORD_SHOW_TOUCHES_ENABLED_PRE_RECORDING)
        } else {
            log(ScreenRecordEvent.SCREEN_RECORD_SHOW_TOUCHES_DISABLED_PRE_RECORDING)
        }
    }
}

private fun UiEventLogger.logShouldShowCameraChanged(
    shouldShowCamera: Boolean,
    isRecording: Boolean,
) {
    if (isRecording) {
        if (shouldShowCamera) {
            log(ScreenRecordEvent.SCREEN_RECORD_CAMERA_ENABLED_MID_RECORDING)
        } else {
            log(ScreenRecordEvent.SCREEN_RECORD_CAMERA_DISABLED_MID_RECORDING)
        }
    } else {
        if (shouldShowCamera) {
            log(ScreenRecordEvent.SCREEN_RECORD_CAMERA_ENABLED_PRE_RECORDING)
        } else {
            log(ScreenRecordEvent.SCREEN_RECORD_CAMERA_DISABLED_PRE_RECORDING)
        }
    }
}
