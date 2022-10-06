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
import android.app.ActivityOptions.LaunchCookie
import android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
import android.media.projection.StopReason
import android.view.Display
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.android.app.tracing.coroutines.launchTraced
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.android.systemui.screencapture.common.ScreenCaptureUiScope
import com.android.systemui.screencapture.common.domain.interactor.ScreenCaptureMarkupInteractor
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureTarget
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureType
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModel
import com.android.systemui.screencapture.domain.interactor.ScreenCaptureUiInteractor
import com.android.systemui.screencapture.record.camera.domain.interactor.ScreenCaptureCameraTransformationInteractor
import com.android.systemui.screencapture.record.camera.domain.interactor.ScreenRecordCameraInteractor
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordFeaturesInteractor
import com.android.systemui.screencapture.record.shared.model.ScreenRecordEvent
import com.android.systemui.screencapture.record.smallscreen.domain.interactor.RecordDetailsStateInteractor
import com.android.systemui.screencapture.record.smallscreen.domain.interactor.RecordDetailsTargetInteractor
import com.android.systemui.screencapture.record.smallscreen.shared.model.RecordDetailsPopupType
import com.android.systemui.screencapture.record.smallscreen.shared.model.currentTargetModel
import com.android.systemui.screencapture.record.ui.viewmodel.ScreenCaptureRecordParametersViewModel
import com.android.systemui.screenrecord.domain.interactor.ScreenRecordingServiceInteractor
import com.android.systemui.screenrecord.shared.model.ScreenRecordingParameters
import com.android.systemui.screenrecord.shared.model.ScreenRecordingStatus
import com.android.systemui.shared.system.ActivityManagerWrapper
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

class SmallScreenCaptureRecordViewModel
@VisibleForTesting
constructor(
    @Background private val bgContext: CoroutineContext,
    private val screenRecordingServiceInteractor: ScreenRecordingServiceInteractor,
    private val screenCaptureCameraTransformationInteractor:
        ScreenCaptureCameraTransformationInteractor,
    recordDetailsAppSelectorViewModelFactory: RecordDetailsAppSelectorViewModel.Factory,
    screenCaptureRecordParametersViewModelFactory: ScreenCaptureRecordParametersViewModel.Factory,
    recordDetailsTargetViewModelFactory: RecordDetailsTargetViewModel.Factory,
    recordDetailsColorPickerViewModelFactory: RecordDetailsColorPickerViewModel.Factory,
    private val drawableLoaderViewModel: DrawableLoaderViewModel,
    private val screenCaptureUiInteractor: ScreenCaptureUiInteractor,
    private val markupInteractor: ScreenCaptureMarkupInteractor,
    private val activityManager: ActivityManagerWrapper,
    private val screenCaptureRecordFeaturesInteractor: ScreenCaptureRecordFeaturesInteractor,
    recordDetailsTargetInteractor: RecordDetailsTargetInteractor,
    private val recordDetailsStateInteractor: RecordDetailsStateInteractor,
    private val screenRecordCameraInteractor: ScreenRecordCameraInteractor,
    private val uiEventLogger: UiEventLogger,
    private val defaultDetailsPopupType: RecordDetailsPopupType,
) : HydratedActivatable(), DrawableLoaderViewModel by drawableLoaderViewModel {

    @AssistedInject
    constructor(
        @Background bgContext: CoroutineContext,
        screenRecordingServiceInteractor: ScreenRecordingServiceInteractor,
        screenCaptureCameraTransformationInteractor: ScreenCaptureCameraTransformationInteractor,
        recordDetailsAppSelectorViewModelFactory: RecordDetailsAppSelectorViewModel.Factory,
        screenCaptureRecordParametersViewModelFactory:
            ScreenCaptureRecordParametersViewModel.Factory,
        recordDetailsTargetViewModelFactory: RecordDetailsTargetViewModel.Factory,
        recordDetailsColorPickerViewModelFactory: RecordDetailsColorPickerViewModel.Factory,
        drawableLoaderViewModel: DrawableLoaderViewModel,
        screenCaptureUiInteractor: ScreenCaptureUiInteractor,
        markupInteractor: ScreenCaptureMarkupInteractor,
        activityManager: ActivityManagerWrapper,
        screenCaptureRecordFeaturesInteractor: ScreenCaptureRecordFeaturesInteractor,
        recordDetailsTargetInteractor: RecordDetailsTargetInteractor,
        recordDetailsStateInteractor: RecordDetailsStateInteractor,
        screenRecordCameraInteractor: ScreenRecordCameraInteractor,
        uiEventLogger: UiEventLogger,
    ) : this(
        bgContext,
        screenRecordingServiceInteractor,
        screenCaptureCameraTransformationInteractor,
        recordDetailsAppSelectorViewModelFactory,
        screenCaptureRecordParametersViewModelFactory,
        recordDetailsTargetViewModelFactory,
        recordDetailsColorPickerViewModelFactory,
        drawableLoaderViewModel,
        screenCaptureUiInteractor,
        markupInteractor,
        activityManager,
        screenCaptureRecordFeaturesInteractor,
        recordDetailsTargetInteractor,
        recordDetailsStateInteractor,
        screenRecordCameraInteractor,
        uiEventLogger,
        RecordDetailsPopupType.Invisible,
    )

    val recordDetailsAppSelectorViewModel: RecordDetailsAppSelectorViewModel =
        recordDetailsAppSelectorViewModelFactory.create()
    val recordDetailsParametersViewModel: ScreenCaptureRecordParametersViewModel =
        screenCaptureRecordParametersViewModelFactory.create()
    val recordDetailsTargetViewModel: RecordDetailsTargetViewModel =
        recordDetailsTargetViewModelFactory.create()
    val recordDetailsColorPickerViewModel: RecordDetailsColorPickerViewModel =
        recordDetailsColorPickerViewModelFactory.create()

    val isRecording: Boolean by
        screenRecordingServiceInteractor.status
            .map { it.isRecording }
            .distinctUntilChanged()
            .onEach { recording ->
                if (recording) {
                    detailsPopup = defaultDetailsPopupType
                }
            }
            .hydratedStateOf(
                traceName = "SmallScreenCaptureRecordViewModel#isRecording",
                initialValue = screenRecordingServiceInteractor.status.value.isRecording,
            )

    var detailsPopup by recordDetailsStateInteractor::type
        private set

    val markupEnabled: Boolean? by
        markupInteractor.enabled
            .onEach { enabled ->
                if (!enabled && detailsPopup == RecordDetailsPopupType.ColorSelector) {
                    resetDetailsPopup()
                }
            }
            .hydratedStateOf(
                traceName = "SmallScreenCaptureRecordViewModel#markupEnabled",
                initialValue = null,
            )
    private val captureTargetModel by
        recordDetailsTargetInteractor.model.hydratedStateOf(
            traceName = "SmallScreenCaptureRecordViewModel#captureTargetModel",
            initialValue = null,
        )

    private val isCameraBackgroundColorSupported: Boolean by
        screenRecordCameraInteractor.canChangeBackgroundColor.hydratedStateOf(
            traceName = "SmallScreenCaptureRecordViewModel#isBackgroundColorSupported"
        )
    val shouldShowColorPickerButton: Boolean by derivedStateOf {
        val canSelectCameraBackgroundColor =
            recordDetailsParametersViewModel.shouldShowFrontCamera &&
                isCameraBackgroundColorSupported
        val canSelectMarkupBackgroundColor = shouldShowMarkupButton && markupEnabled == true
        canSelectCameraBackgroundColor || canSelectMarkupBackgroundColor
    }
    val shouldShowMarkupButton: Boolean by derivedStateOf {
        screenCaptureRecordFeaturesInteractor.isMarkupAvailable &&
            captureTargetModel?.currentTargetModel?.canUseMarkup == true
    }

    val shouldShowSettingsButton: Boolean by derivedStateOf { isRecording }

    val shouldShowDim: Boolean by derivedStateOf {
        !isRecording && !recordDetailsParametersViewModel.shouldShowFrontCamera
    }
    val isTransient: Boolean by screenCaptureCameraTransformationInteractor::isTransforming

    override suspend fun onActivated() {
        coroutineScope {
            launchTraced(
                "ScreenCaptureRecordSmallScreenViewModel#recordDetailsAppSelectorViewModel"
            ) {
                recordDetailsAppSelectorViewModel.activate()
            }
            launchTraced(
                "ScreenCaptureRecordSmallScreenViewModel#recordDetailsParametersViewModel"
            ) {
                recordDetailsParametersViewModel.activate()
            }
            launchTraced("ScreenCaptureRecordSmallScreenViewModel#recordDetailsTargetViewModel") {
                recordDetailsTargetViewModel.activate()
            }
            launchTraced(
                "ScreenCaptureRecordSmallScreenViewModel#recordDetailsMarkupColorPickerViewModel"
            ) {
                recordDetailsColorPickerViewModel.activate()
            }
        }
    }

    fun showSettings() {
        detailsPopup = RecordDetailsPopupType.Settings
    }

    fun showAppSelector() {
        detailsPopup = RecordDetailsPopupType.AppSelector
    }

    fun showCameraColorSelector() {
        detailsPopup = RecordDetailsPopupType.ColorSelector
    }

    fun resetDetailsPopup() {
        detailsPopup =
            if (isRecording) {
                RecordDetailsPopupType.Invisible
            } else {
                RecordDetailsPopupType.Settings
            }
    }

    fun setMarkupEnabled(enabled: Boolean) {
        markupInteractor.setEnabled(enabled)
    }

    fun dismiss() {
        screenCaptureUiInteractor.hide(ScreenCaptureType.RECORD)
    }

    suspend fun onPrimaryButtonTapped() {
        if (screenRecordingServiceInteractor.status.value.isRecording) {
            withContext(bgContext) {
                screenRecordingServiceInteractor.stopRecording(StopReason.STOP_HOST_APP)
            }
            uiEventLogger.log(ScreenRecordEvent.SCREEN_RECORD_RECORDING_STOPPED_TOOLBAR)
        } else {
            startRecording()
            uiEventLogger.log(ScreenRecordEvent.SCREEN_RECORD_RECORDING_STARTED)
        }
        dismiss()
    }

    private suspend fun startRecording() {
        val audioSource = recordDetailsParametersViewModel.audioSource
        val target = captureTargetModel?.currentTargetModel?.screenCaptureTarget ?: return
        val lowQuality = recordDetailsParametersViewModel.lowQuality
        val longerDuration = recordDetailsParametersViewModel.longerDuration
        val hevc = recordDetailsParametersViewModel.hevc
        when (target) {
            is ScreenCaptureTarget.Fullscreen -> {
                val shouldShowTaps = recordDetailsParametersViewModel.shouldShowTaps
                screenRecordingServiceInteractor.startRecordingDelayed(
                    ScreenRecordingParameters(
                        captureTarget = null,
                        displayId = target.displayId,
                        shouldShowTaps = shouldShowTaps,
                        audioSource = audioSource,
                        lowQuality = lowQuality,
                        longerDuration = longerDuration,
                        hevc = hevc,
                    )
                )
            }
            is ScreenCaptureTarget.App -> {
                val cookie = LaunchCookie("screen_record")
                withContext(bgContext) {
                    activityManager.startActivityFromRecents(
                        target.taskId,
                        ActivityOptions.makeBasic().apply {
                            pendingIntentBackgroundActivityStartMode =
                                MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                            setLaunchCookie(cookie)
                        },
                    )
                }
                val displayId: Int =
                    if (target.displayId == Display.INVALID_DISPLAY) {
                        withContext(bgContext) {
                            val runningTasks = activityManager.getRunningTasks(false)
                            runningTasks.find { it.taskId == target.taskId }?.displayId
                                ?: Display.DEFAULT_DISPLAY
                        }
                    } else {
                        target.displayId
                    }

                screenRecordingServiceInteractor.startRecordingDelayed(
                    ScreenRecordingParameters(
                        captureTarget =
                            MediaProjectionCaptureTarget(
                                launchCookie = cookie,
                                taskId = target.taskId,
                            ),
                        displayId = displayId,
                        shouldShowTaps = false,
                        audioSource = audioSource,
                        lowQuality = lowQuality,
                        longerDuration = longerDuration,
                        hevc = hevc,
                    )
                )
            }
            else -> error("Unsupported target=$target")
        }
    }

    @AssistedFactory
    @ScreenCaptureUiScope
    interface Factory {
        fun create(): SmallScreenCaptureRecordViewModel
    }
}

private val ScreenRecordingStatus.isRecording
    get() = this is ScreenRecordingStatus.Started
