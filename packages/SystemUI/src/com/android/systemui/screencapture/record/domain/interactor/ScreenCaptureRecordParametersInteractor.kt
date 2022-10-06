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

package com.android.systemui.screencapture.record.domain.interactor

import com.android.systemui.screencapture.common.ScreenCapture
import com.android.systemui.screencapture.common.ScreenCaptureScope
import com.android.systemui.screencapture.record.data.repository.ScreenCaptureRecordParametersRepository
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import com.android.systemui.screenrecord.domain.interactor.ScreenRecordingServiceInteractor
import com.android.systemui.screenrecord.shared.model.ScreenRecordingStatus
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@ScreenCaptureScope
class ScreenCaptureRecordParametersInteractor
@Inject
constructor(
    @ScreenCapture coroutineScope: CoroutineScope,
    private val serviceInteractor: ScreenRecordingServiceInteractor,
    private val repository: ScreenCaptureRecordParametersRepository,
) {

    var audioSource: ScreenRecordingAudioSource
        set(value) {
            if (canChangeAudioSource.value) {
                repository.audioSource = value
            }
        }
        get() = repository.audioSource

    var shouldShowTaps: Boolean
        set(value) {
            serviceInteractor.updateShouldShowTaps(value)
            repository.shouldShowTaps = value
        }
        get() = repository.shouldShowTaps

    var shouldShowFrontCamera: Boolean
        set(value) {
            if (value) {
                audioSource = audioSource.withEnabledMic()
            }
            repository.shouldShowFrontCamera = value
        }
        get() = repository.shouldShowFrontCamera

    var lowQuality: Boolean
        set(value) {
            repository.lowQuality = value
        }
        get() = repository.lowQuality

    var longerDuration: Boolean
        set(value) {
            repository.longerDuration = value
        }
        get() = repository.longerDuration

    var hevc: Boolean
        set(value) {
            repository.hevc = value
        }
        get() = repository.hevc

    val canChangeAudioSource: StateFlow<Boolean> =
        serviceInteractor.status
            .map { it.canChangeAudioSource() }
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                serviceInteractor.status.value.canChangeAudioSource(),
            )
}

private fun ScreenRecordingAudioSource.withEnabledMic(): ScreenRecordingAudioSource =
    when (this) {
        ScreenRecordingAudioSource.MIC -> this
        ScreenRecordingAudioSource.MIC_AND_INTERNAL -> this
        ScreenRecordingAudioSource.NONE -> ScreenRecordingAudioSource.MIC
        ScreenRecordingAudioSource.INTERNAL -> ScreenRecordingAudioSource.MIC_AND_INTERNAL
    }

private fun ScreenRecordingStatus.canChangeAudioSource(): Boolean =
    this is ScreenRecordingStatus.Stopped
