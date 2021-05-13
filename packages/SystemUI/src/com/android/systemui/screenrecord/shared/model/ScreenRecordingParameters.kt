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

package com.android.systemui.screenrecord.shared.model

import android.os.Parcel
import android.os.Parcelable
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.android.systemui.screenrecord.ScreenRecordingAudioSource

data class ScreenRecordingParameters
@JvmOverloads
constructor(
    val captureTarget: MediaProjectionCaptureTarget?,
    val audioSource: ScreenRecordingAudioSource,
    val displayId: Int,
    val shouldShowTaps: Boolean,
    val shouldShowSeconds: Boolean = false,
    val notificationId: Int = 0,
    val lowQuality: Boolean = false,
    val longerDuration: Boolean = false,
) : Parcelable {

    constructor(
        parcel: Parcel
    ) : this(
        parcel.readParcelable(
            MediaProjectionCaptureTarget::class.java.classLoader,
            MediaProjectionCaptureTarget::class.java,
        ),
        parcel.readSerializable(
            ScreenRecordingAudioSource::class.java.classLoader,
            ScreenRecordingAudioSource::class.java,
        ) as ScreenRecordingAudioSource,
        parcel.readInt(),
        parcel.readBoolean(),
        parcel.readBoolean(),
        parcel.readInt(),
        parcel.readBoolean(),
        parcel.readBoolean(),
        parcel.readBoolean(),
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) =
        with(parcel) {
            writeParcelable(captureTarget, flags)
            writeSerializable(audioSource)
            writeInt(displayId)
            writeBoolean(shouldShowTaps)
            writeBoolean(shouldShowSeconds)
            writeInt(notificationId)
            writeBoolean(lowQuality)
            writeBoolean(longerDuration)
        }

    companion object CREATOR : Parcelable.Creator<ScreenRecordingParameters> {
        override fun createFromParcel(parcel: Parcel): ScreenRecordingParameters =
            ScreenRecordingParameters(parcel)

        override fun newArray(size: Int): Array<ScreenRecordingParameters?> = arrayOfNulls(size)
    }
}
