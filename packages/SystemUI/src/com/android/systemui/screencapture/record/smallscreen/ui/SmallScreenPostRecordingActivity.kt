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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.screencapture.record.smallscreen.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.MimeTypes
import com.android.compose.PlatformOutlinedButton
import com.android.compose.theme.PlatformTheme
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import com.android.systemui.screencapture.common.ui.compose.LoadingIcon
import com.android.systemui.screencapture.common.ui.compose.PrimaryButton
import com.android.systemui.screencapture.common.ui.compose.loadIcon
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModel
import com.android.systemui.screencapture.record.smallscreen.player.ui.compose.DefaultVideoPlayerControls
import com.android.systemui.screencapture.record.smallscreen.player.ui.compose.VideoPlayer
import com.android.systemui.screencapture.record.smallscreen.player.ui.viewmodel.VideoPlayerControlsViewModel
import com.android.systemui.screencapture.record.smallscreen.ui.viewmodel.PostRecordingActionsViewModel
import com.android.systemui.screencapture.record.smallscreen.ui.viewmodel.PostRecordingImmediateVideoViewModel
import com.android.systemui.screencapture.record.smallscreen.ui.viewmodel.PostRecordingVideoViewModel
import com.android.systemui.screencapture.record.smallscreen.ui.viewmodel.PostRecordingWaitingVideoViewModel
import com.android.systemui.screencapture.ui.postRecordingConfirmDeletion
import com.android.systemui.screenrecord.shared.model.ScreenRecording
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import javax.inject.Inject

class SmallScreenPostRecordingActivity
@Inject
constructor(
    private val videoPlayer: VideoPlayer,
    private val actionsViewModelFactory: PostRecordingActionsViewModel.Factory,
    private val waitingViewModelFactory: PostRecordingWaitingVideoViewModel.Factory,
    private val immediateViewModelFactory: PostRecordingImmediateVideoViewModel.Factory,
    private val postRecordSnackbarDialogs: PostRecordSnackbarDialogs,
    private val systemUIDialogFactory: SystemUIDialogFactory,
) : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.data ?: error("Data URI is missing")
        setContent { PlatformTheme { Content() } }
    }

    @Composable
    private fun Content() {
        val shouldShowVideoSaved = intent.shouldWaitForVideo()
        val videoViewModel: PostRecordingVideoViewModel = createVideoViewModel(intent)
        val actionsViewModel: PostRecordingActionsViewModel =
            rememberViewModel(
                "SmallScreenPostRecordingActivity#actionsViewModel",
                listOf(intent.data, displayId),
            ) {
                actionsViewModelFactory.create(intent.data!!, displayId)
            }

        val recording = videoViewModel.recording
        LaunchedEffect(shouldShowVideoSaved, recording) {
            if (shouldShowVideoSaved && recording is ScreenRecording.Saved) {
                intent.clearShouldWaitForVideo()
                postRecordSnackbarDialogs.showVideoSaved()
            }
        }
        var isShowingDeletionDialog: Boolean by rememberSaveable(Unit) { mutableStateOf(false) }
        LaunchedEffect(isShowingDeletionDialog, recording) {
            if (isShowingDeletionDialog && recording is ScreenRecording.Saved) {
                val shouldDeleteVideo =
                    postRecordingConfirmDeletion(
                        systemUIDialogFactory,
                        this@SmallScreenPostRecordingActivity,
                        actionsViewModel,
                    )
                if (shouldDeleteVideo) {
                    postRecordSnackbarDialogs.showVideoDeleted(
                        uri = recording.uri,
                        thumbnail = recording.thumbnail,
                        notificationId = videoViewModel.notificationId,
                    )
                    finishAndRemoveTask()
                }
                isShowingDeletionDialog = false
            }
        }

        Box(
            modifier =
                Modifier.background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Column {
                val shouldUseFlatBottomBar =
                    booleanResource(R.bool.screen_record_post_recording_flat_bottom_bar)
                if (!shouldUseFlatBottomBar) {
                    Spacer(modifier = Modifier.size(50.dp))
                }
                AnimatedContent(
                    targetState = recording,
                    modifier = Modifier.weight(1f).align(Alignment.CenterHorizontally),
                ) { currentRecording ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (currentRecording) {
                            is ScreenRecording.Saving ->
                                LoadingIndicator(
                                    modifier = Modifier.align(Alignment.Center).size(72.dp)
                                )
                            is ScreenRecording.NotSaved ->
                                Text(
                                    text = stringResource(R.string.screenrecord_save_error),
                                    modifier = Modifier.align(Alignment.Center).padding(64.dp),
                                )
                            is ScreenRecording.Saved -> {
                                var controlsVisible by remember { mutableStateOf(true) }
                                videoPlayer.Content(
                                    uri = currentRecording.uri,
                                    modifier =
                                        Modifier.fillMaxSize()
                                            .clickable(
                                                interactionSource = null,
                                                indication = null,
                                                onClick = { controlsVisible = !controlsVisible },
                                            ),
                                    videoControls = { viewModel ->
                                        VideoPlayerControls(
                                            viewModel = viewModel,
                                            visible = controlsVisible,
                                            modifier = Modifier.align(Alignment.BottomCenter),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.size(32.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    val rowModifier = Modifier.weight(1f).heightIn(min = 40.dp)
                    PostRecordButton(
                        onClick = {
                            actionsViewModel.new()
                            finish()
                        },
                        drawableLoaderViewModel = actionsViewModel,
                        iconRes = R.drawable.ic_arrow_back,
                        labelRes = R.string.screen_record_take_another,
                        modifier = rowModifier,
                    )
                    if (actionsViewModel.isEditAvailable) {
                        PostRecordButton(
                            onClick = { actionsViewModel.edit() },
                            drawableLoaderViewModel = actionsViewModel,
                            iconRes = R.drawable.ic_edit_square,
                            labelRes = R.string.screen_record_edit,
                            modifier = rowModifier,
                        )
                    }
                    PostRecordButton(
                        onClick = {
                            if (recording !is ScreenRecording.Saved) return@PostRecordButton
                            isShowingDeletionDialog = true
                        },
                        enabled = recording is ScreenRecording.Saved,
                        drawableLoaderViewModel = actionsViewModel,
                        iconRes = R.drawable.ic_screenshot_delete,
                        labelRes = R.string.screen_record_delete,
                        modifier = rowModifier,
                    )
                    if (shouldUseFlatBottomBar) {
                        PrimaryButton(
                            text = stringResource(R.string.screenrecord_share_label),
                            onClick = { actionsViewModel.share() },
                            modifier = rowModifier,
                        )
                    }
                }
                if (!shouldUseFlatBottomBar) {
                    val shareIcon by
                        loadIcon(
                            actionsViewModel,
                            R.drawable.ic_screenshot_share,
                            contentDescription = null,
                        )
                    PrimaryButton(
                        text = stringResource(R.string.screenrecord_share_label),
                        icon = shareIcon,
                        onClick = { actionsViewModel.share() },
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                                .height(56.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun VideoPlayerControls(
        viewModel: VideoPlayerControlsViewModel,
        modifier: Modifier = Modifier,
        visible: Boolean = true,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = modifier,
        ) {
            DefaultVideoPlayerControls(viewModel = viewModel, modifier = Modifier)
        }
    }

    @Composable
    private fun createVideoViewModel(intent: Intent): PostRecordingVideoViewModel {
        val shouldShowVideoSaved = intent.shouldWaitForVideo()
        return rememberViewModel(
            "SmallScreenPostRecordingActivity#videoViewModel",
            listOf(shouldShowVideoSaved, intent.data),
        ) {
            val notificationId = intent.getIntExtra(NOTIFICATION_ID, INVALID_NOTIFICATION_ID)
            if (shouldShowVideoSaved) {
                waitingViewModelFactory.create(intent.data!!, notificationId)
            } else {
                immediateViewModelFactory.create(intent.data!!, notificationId)
            }
        }
    }

    companion object {

        private const val SHOULD_WAIT_FOR_VIDEO = "should_show_video_saved"
        private const val NOTIFICATION_ID = "notification_id"
        private const val INVALID_NOTIFICATION_ID = 0

        /** Immediately shows the recording by the [videoUri] */
        fun showRecording(context: Context, videoUri: Uri, notificationId: Int): Intent =
            createIntent(context, videoUri) { putExtra(NOTIFICATION_ID, notificationId) }

        /**
         * Listens to the
         * [com.android.systemui.screenrecord.domain.interactor.ScreenRecordingServiceInteractor]
         * for the recording status identified by the [videoUri]
         */
        fun waitForRecording(context: Context, videoUri: Uri, notificationId: Int): Intent =
            createIntent(context, videoUri) {
                putExtra(SHOULD_WAIT_FOR_VIDEO, true)
                putExtra(NOTIFICATION_ID, notificationId)
            }

        private fun createIntent(
            context: Context,
            videoUri: Uri,
            setup: Intent.() -> Unit = {},
        ): Intent {
            return Intent(context, SmallScreenPostRecordingActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setDataAndType(videoUri, MimeTypes.VIDEO_MP4)
                .apply(setup)
        }

        private fun Intent.shouldWaitForVideo(): Boolean =
            getBooleanExtra(SHOULD_WAIT_FOR_VIDEO, false)

        private fun Intent.clearShouldWaitForVideo() {
            removeExtra(SHOULD_WAIT_FOR_VIDEO)
        }
    }
}

@Composable
private fun PostRecordButton(
    onClick: () -> Unit,
    @DrawableRes iconRes: Int,
    labelRes: Int,
    drawableLoaderViewModel: DrawableLoaderViewModel,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PlatformOutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors =
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
    ) {
        // Applying basicMarquee to the PlatformOutlinedButton animates button border, but we only
        // want to animate the content.
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.basicMarquee(),
        ) {
            LoadingIcon(
                icon =
                    loadIcon(
                            viewModel = drawableLoaderViewModel,
                            resId = iconRes,
                            contentDescription = ContentDescription.Resource(labelRes),
                        )
                        .value,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clearAndSetSemantics {}.basicMarquee(),
            )
        }
    }
}
