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

package com.android.systemui.screencapture.record.smallscreen.ui.viewmodel

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.internal.logging.UiEventLogger
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiParameters
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModel
import com.android.systemui.screencapture.domain.interactor.ScreenCaptureUiInteractor
import com.android.systemui.screencapture.record.largescreen.data.repository.ParentUriRepository
import com.android.systemui.screencapture.record.shared.model.ScreenRecordEvent
import com.android.systemui.screenrecord.service.ActivityStartingReceiver
import com.android.systemui.settings.UserTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

private const val MIME_TYPE = "video/mp4"

class PostRecordingActionsViewModel
@AssistedInject
constructor(
    @Assisted private val videoUri: Uri,
    @Assisted private val displayId: Int,
    private val context: Context,
    private val broadcastSender: BroadcastSender,
    private val userTracker: UserTracker,
    private val drawableLoaderViewModel: DrawableLoaderViewModel,
    private val screenCaptureUiInteractor: ScreenCaptureUiInteractor,
    private val parentUriRepository: ParentUriRepository,
    private val uiEventLogger: UiEventLogger,
    private val activityStarter: ActivityStarter,
) : HydratedActivatable(), DrawableLoaderViewModel by drawableLoaderViewModel {

    var parentUri: Uri? by mutableStateOf(null)
        private set

    var isEditAvailable: Boolean by mutableStateOf(false)
        private set

    override suspend fun onActivated() {
        parentUri = parentUriRepository.getParentDirectoryUri(videoUri)
        isEditAvailable = isIntentAvailable(Intent.ACTION_EDIT, videoUri, MIME_TYPE)
    }

    private fun isIntentAvailable(action: String, uri: Uri, mimeType: String): Boolean {
        val intent = Intent(action).setDataAndType(uri, mimeType)
        return context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) !=
            null
    }

    fun new() {
        uiEventLogger.log(ScreenRecordEvent.SCREEN_RECORD_POST_RECORDING_NEW)
        screenCaptureUiInteractor.show(ScreenCaptureUiParameters.Record())
    }

    fun edit() {
        uiEventLogger.log(ScreenRecordEvent.SCREEN_RECORD_POST_RECORDING_EDIT)
        startVideoActivity(
            action = Intent.ACTION_EDIT,
            label = context.getString(R.string.screen_record_edit),
            shouldShowChooser = false,
        )
    }

    fun view() {
        startVideoActivity(action = Intent.ACTION_VIEW, label = null, shouldShowChooser = false)
    }

    fun openInFolder() {
        parentUri?.let {
            startVideoActivity(
                action = Intent.ACTION_VIEW,
                label = null,
                shouldShowChooser = false,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                uri = it,
            )
        }
    }

    fun share() {
        uiEventLogger.log(ScreenRecordEvent.SCREEN_RECORD_POST_RECORDING_SHARE)
        startVideoActivity(
            action = Intent.ACTION_SEND,
            label = context.getString(R.string.screenrecord_share_label),
            shouldShowChooser = true,
        )
    }

    /** Executes a [runnable] after the keyguard is dismissed. */
    fun executeDismissingKeyguard(runnable: Runnable) {
        activityStarter.executeRunnableDismissingKeyguard(
            runnable,
            /* cancelAction= */ null,
            /* dismissShade= */ true,
            /* afterKeyguardGone= */ true,
            /* deferred= */ false,
        )
    }

    private fun startVideoActivity(
        action: String,
        label: String?,
        shouldShowChooser: Boolean,
        mimeType: String = MIME_TYPE,
        uri: Uri = videoUri,
    ) {
        val intent =
            Intent(action)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, videoUri)
                .let { intent ->
                    if (shouldShowChooser) {
                        Intent.createChooser(intent, label)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    } else {
                        intent
                    }
                }

        executeDismissingKeyguard {
            broadcastSender.sendBroadcastAsUser(
                intent =
                    ActivityStartingReceiver.wrapIntent(
                        context,
                        intent,
                        displayId
                            .takeIf { it != Display.INVALID_DISPLAY }
                            ?.let { validDisplayId ->
                                ActivityOptions.makeBasic()
                                    .apply { launchDisplayId = validDisplayId }
                                    .toBundle()
                            },
                    ),
                userHandle = userTracker.userHandle,
            )
        }
    }

    @AssistedFactory
    interface Factory {

        fun create(videoUri: Uri, displayId: Int): PostRecordingActionsViewModel
    }
}
