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

package com.android.systemui.screenrecord.data.repository

import android.app.Activity
import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import com.android.systemui.screenrecord.RecordingService
import com.android.systemui.screenrecord.shared.model.ScreenRecordingParameters
import com.android.systemui.settings.UserContextProvider
import javax.inject.Inject

class LegacyScreenRecordingStartStopRepository
@Inject
constructor(private val userContextProvider: UserContextProvider) :
    ScreenRecordingStartStopRepository {

    private val options: Bundle = BroadcastOptions.makeBasic().setInteractive(true).toBundle()
    private val userContext: Context
        get() = userContextProvider.userContext

    override fun startRecording(parameters: ScreenRecordingParameters) {
        PendingIntent.getForegroundService(
                userContext,
                RecordingService.REQUEST_CODE,
                with(parameters) {
                    RecordingService.getStartIntent(
                        userContext,
                        Activity.RESULT_OK,
                        audioSource.ordinal,
                        shouldShowTaps,
                        displayId,
                        captureTarget,
                        lowQuality,
                        longerDuration,
                        hevc,
                    )
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            .send(options)
    }

    override fun stopRecording(reason: Int) {
        PendingIntent.getService(
                userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStopIntent(userContext),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            .send(options)
    }
}
