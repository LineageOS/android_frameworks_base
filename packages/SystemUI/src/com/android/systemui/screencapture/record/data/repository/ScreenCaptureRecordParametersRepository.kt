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

package com.android.systemui.screencapture.record.data.repository

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.systemui.Prefs
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.screencapture.common.ScreenCaptureScope
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import com.android.systemui.settings.UserTracker
import javax.inject.Inject

@ScreenCaptureScope
class ScreenCaptureRecordParametersRepository
@Inject
constructor(@Application private val context: Context, private val userTracker: UserTracker) {

    private val userContext: Context
        get() = context.createContextAsUser(userTracker.userHandle, 0)

    private val audioSourceState = mutableStateOf(loadAudioSource())
    var audioSource: ScreenRecordingAudioSource
        get() = audioSourceState.value
        set(value) {
            audioSourceState.value = value
            saveAudioSource(value)
        }

    private val shouldShowTapsState = mutableStateOf(Prefs.getInt(userContext, PREF_TAPS, 0) == 1)
    var shouldShowTaps: Boolean
        get() = shouldShowTapsState.value
        set(value) {
            shouldShowTapsState.value = value
            Prefs.putInt(userContext, PREF_TAPS, if (value) 1 else 0)
        }

    var shouldShowFrontCamera: Boolean by mutableStateOf(false)

    private val lowQualityState = mutableStateOf(Prefs.getInt(userContext, PREF_LOW, 0) == 1)
    var lowQuality: Boolean
        get() = lowQualityState.value
        set(value) {
            lowQualityState.value = value
            Prefs.putInt(userContext, PREF_LOW, if (value) 1 else 0)
        }

    private val longerDurationState = mutableStateOf(Prefs.getInt(userContext, PREF_LONGER, 0) == 1)
    var longerDuration: Boolean
        get() = longerDurationState.value
        set(value) {
            longerDurationState.value = value
            Prefs.putInt(userContext, PREF_LONGER, if (value) 1 else 0)
        }

    private val hevcState = mutableStateOf(Prefs.getInt(userContext, PREF_HEVC, 0) == 1)
    var hevc: Boolean
        get() = hevcState.value
        set(value) {
            hevcState.value = value
            Prefs.putInt(userContext, PREF_HEVC, if (value) 1 else 0)
        }

    private fun loadAudioSource(): ScreenRecordingAudioSource {
        val useAudio = Prefs.getInt(userContext, PREF_AUDIO, 0) == 1
        if (!useAudio) return ScreenRecordingAudioSource.NONE
        val pos = Prefs.getInt(userContext, PREF_AUDIO_SOURCE, 0)
        val values = ScreenRecordingAudioSource.values()
        return if (pos >= 0 && pos < values.size) values[pos] else ScreenRecordingAudioSource.NONE
    }

    private fun saveAudioSource(value: ScreenRecordingAudioSource) {
        val useAudio = value != ScreenRecordingAudioSource.NONE
        Prefs.putInt(userContext, PREF_AUDIO, if (useAudio) 1 else 0)
        if (useAudio) {
            Prefs.putInt(userContext, PREF_AUDIO_SOURCE, value.ordinal)
        }
    }

    companion object {
        private const val PREF_TAPS = "screenrecord_show_taps"
        private const val PREF_LOW = "screenrecord_use_low_quality"
        private const val PREF_LONGER = "screenrecord_use_longer_timeout"
        private const val PREF_AUDIO = "screenrecord_use_audio"
        private const val PREF_AUDIO_SOURCE = "screenrecord_audio_source"
        private const val PREF_HEVC = "screenrecord_use_hevc"
    }
}
