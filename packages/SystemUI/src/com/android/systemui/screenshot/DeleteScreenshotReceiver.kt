/*
 * Copyright (C) 2020 The Android Open Source Project
 * Copyright (C) 2025 The LineageOS Project
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

package com.android.systemui.screenshot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.android.systemui.dagger.qualifiers.Background
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Removes the file at a provided URI.
 */
class DeleteScreenshotReceiver @Inject constructor(
    @Background private val backgroundExecutor: Executor
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return

        // Delete the image from the media store
        backgroundExecutor.execute {
            context.contentResolver.delete(uri, null, null)
        }

    }
}
