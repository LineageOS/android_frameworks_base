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

package com.android.systemui.screencapture.record.largescreen.ui.viewmodel

import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityOptions.LaunchCookie
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import com.android.app.tracing.coroutines.launchInTraced
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.android.systemui.res.R
import com.android.systemui.screencapture.ScreenCaptureEvent
import com.android.systemui.screencapture.common.ScreenCapture
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiParameters
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModel
import com.android.systemui.screencapture.domain.interactor.ScreenCaptureUiInteractor
import com.android.systemui.screencapture.record.largescreen.domain.interactor.AppWindowInteractor
import com.android.systemui.screencapture.record.largescreen.domain.interactor.LargeScreenCaptureParametersInteractor
import com.android.systemui.screencapture.record.largescreen.domain.interactor.ScreenshotInteractor
import com.android.systemui.screencapture.record.largescreen.shared.model.AppWindowModel
import com.android.systemui.screencapture.record.largescreen.shared.model.ScreenCaptureRegion
import com.android.systemui.screencapture.record.largescreen.shared.model.ScreenCaptureType
import com.android.systemui.screenrecord.domain.interactor.ScreenRecordingServiceInteractor
import com.android.systemui.screenrecord.shared.model.ScreenRecordingParameters
import com.android.systemui.screenrecord.shared.model.ScreenRecordingStatus
import com.android.systemui.util.kotlin.pairwiseBy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/** Models UI for the Screen Capture UI for large screen devices. */
class PreCaptureViewModel
@AssistedInject
constructor(
    @Assisted private val displayId: Int,
    @Background private val backgroundScope: CoroutineScope,
    private val displayManager: DisplayManager,
    private val screenshotInteractor: ScreenshotInteractor,
    private val drawableLoaderViewModel: DrawableLoaderViewModel,
    private val screenCaptureUiInteractor: ScreenCaptureUiInteractor,
    private val screenRecordingServiceInteractor: ScreenRecordingServiceInteractor,
    private val largeScreenCaptureParametersInteractor: LargeScreenCaptureParametersInteractor,
    private val uiEventLogger: UiEventLogger,
    @ScreenCapture private val screenCaptureUiParams: ScreenCaptureUiParameters,
    toolbarViewModelFactory: PreCaptureToolbarViewModel.Factory,
    private val appWindowInteractor: AppWindowInteractor,
    private val context: Context,
) : HydratedActivatable(), DrawableLoaderViewModel by drawableLoaderViewModel {

    private val recordingParameters = screenCaptureUiParams as ScreenCaptureUiParameters.Record
    private val isShowingUiFlow = MutableStateFlow(true)
    val recordingIsNotStarted by
        screenRecordingServiceInteractor.status
            .map { it is ScreenRecordingStatus.Started }
            .pairwiseBy(initialValue = false) { wasRecording, isRecording ->
                !wasRecording && !isRecording
            }
            .hydratedStateOf("PreCaptureViewModel#recordingIsStarted")

    private val captureTypeSource = MutableStateFlow(ScreenCaptureType.SCREENSHOT)
    private val captureRegionSource = MutableStateFlow(ScreenCaptureRegion.PARTIAL)

    private val regionBoxSource = MutableStateFlow<Rect?>(null)

    var runningTasks: List<RunningTaskInfo> = emptyList()
    private val topTaskSource = MutableStateFlow<RunningTaskInfo?>(null)
    private val appWindowSelectionSource = MutableStateFlow<AppWindowModel?>(null)
    val appWindowSelection: AppWindowModel? by appWindowSelectionSource.hydratedStateOf()

    val toolbarViewModel = toolbarViewModelFactory.create()

    val isShowingUi: Boolean by isShowingUiFlow.hydratedStateOf()

    val captureType: ScreenCaptureType by captureTypeSource.hydratedStateOf()

    val captureRegion: ScreenCaptureRegion by captureRegionSource.hydratedStateOf()

    val topTask: RunningTaskInfo? by topTaskSource.hydratedStateOf()

    val regionBox: Rect? by regionBoxSource.hydratedStateOf()

    val snackbarHostState = SnackbarHostState()

    private fun isValidCaptureOptions(
        captureType: ScreenCaptureType,
        captureRegion: ScreenCaptureRegion,
    ): Boolean {
        if (
            captureType == ScreenCaptureType.SCREENSHOT &&
                captureRegion == ScreenCaptureRegion.APP_WINDOW
        ) {
            return toolbarViewModel.appWindowRegionSupported
        }

        if (
            captureType == ScreenCaptureType.RECORDING &&
                captureRegion == ScreenCaptureRegion.PARTIAL
        ) {
            return toolbarViewModel.regionRecordingSupported
        }
        return true
    }

    private suspend fun initializeCaptureType() {
        val defaultType = recordingParameters.largeScreenParameters?.defaultCaptureType
        if (defaultType != null) {
            captureTypeSource.value = defaultType
            largeScreenCaptureParametersInteractor.setSelectedCaptureType(defaultType)
        } else {
            captureTypeSource.value =
                largeScreenCaptureParametersInteractor.getSelectedCaptureType()
        }
    }

    private suspend fun initializeCaptureRegion() {
        val defaultRegion = recordingParameters.largeScreenParameters?.defaultCaptureRegion
        if (defaultRegion != null) {
            captureRegionSource.value = defaultRegion
            largeScreenCaptureParametersInteractor.setSelectedCaptureRegion(defaultRegion)
        } else {
            captureRegionSource.value =
                largeScreenCaptureParametersInteractor.getSelectedCaptureRegion()
        }
    }

    fun updateCaptureType(selectedType: ScreenCaptureType) {
        // This fixes the crash when select partial capture region first and then click Record radio
        // button (b/458133150). We need to remove this code once region recording is supported.
        if (
            selectedType == ScreenCaptureType.RECORDING &&
                captureRegion == ScreenCaptureRegion.PARTIAL
        ) {
            updateCaptureRegion(ScreenCaptureRegion.FULLSCREEN)
        }
        captureTypeSource.value = selectedType
        backgroundScope.launch {
            largeScreenCaptureParametersInteractor.setSelectedCaptureType(selectedType)
        }
        uiEventLogger.log(
            ScreenCaptureEvent.fromRegionAndType(captureRegionSource.value, selectedType)
        )
    }

    private suspend fun showDisclaimer(resId: Int) {
        val newMessage = context.getString(resId)
        if (snackbarHostState.currentSnackbarData?.visuals?.message == newMessage) {
            return
        }
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(message = newMessage, duration = SnackbarDuration.Short)
    }

    private fun hideDisclaimer() {
        snackbarHostState.currentSnackbarData?.dismiss()
    }

    fun updateCaptureRegion(selectedRegion: ScreenCaptureRegion) {
        if (selectedRegion != ScreenCaptureRegion.PARTIAL) {
            toolbarViewModel.updateOpacityForRegionBox(
                isInteracting = false,
                regionBoxBounds = null,
            )
        }
        if (selectedRegion == ScreenCaptureRegion.APP_WINDOW) {
            runningTasks = appWindowInteractor.getAppWindowTasks(displayId)
        }
        captureRegionSource.value = selectedRegion
        backgroundScope.launch {
            largeScreenCaptureParametersInteractor.setSelectedCaptureRegion(selectedRegion)
        }
        uiEventLogger.log(
            ScreenCaptureEvent.fromRegionAndType(selectedRegion, captureTypeSource.value)
        )
    }

    fun updateTaskSelectionFromHover(pointerPosition: Point) {
        val task =
            runningTasks.firstOrNull {
                it.configuration.windowConfiguration.bounds.contains(
                    pointerPosition.x,
                    pointerPosition.y,
                )
            }
        topTaskSource.value = task

        if (task != null) {
            appWindowSelectionSource.value = calculateVisibleArea(task)
        } else {
            appWindowSelectionSource.value = null
        }
    }

    fun focusTask(task: RunningTaskInfo) {
        topTaskSource.value = task
        appWindowSelectionSource.value = calculateVisibleArea(task)
    }

    fun unfocusTask(task: RunningTaskInfo) {
        if (topTaskSource.value == task) {
            topTaskSource.value = null
            appWindowSelectionSource.value = null
        }
    }

    private fun calculateVisibleArea(selectedTask: RunningTaskInfo): AppWindowModel {
        val selectedTaskIndex = runningTasks.indexOf(selectedTask)
        val selectedTaskBounds = selectedTask.configuration.windowConfiguration.bounds
        val overlappingBounds = mutableListOf<Rect>()

        if (selectedTaskIndex > 0) {
            for (i in 0 until selectedTaskIndex) {
                val overlayingTask = runningTasks[i]
                if (overlayingTask.isVisible) {
                    val overlayingTaskBounds =
                        overlayingTask.configuration.windowConfiguration.bounds
                    if (Rect.intersects(selectedTaskBounds, overlayingTaskBounds)) {
                        overlappingBounds.add(overlayingTaskBounds)
                    }
                }
            }
        }
        return AppWindowModel(
            taskBounds = selectedTaskBounds,
            overlappingBounds = overlappingBounds,
        )
    }

    fun updateRegionBoxBounds(bounds: Rect) {
        regionBoxSource.value = bounds
        backgroundScope.launch {
            largeScreenCaptureParametersInteractor.setSelectedCaptureRegionBox(bounds)
        }
    }

    /** Initiates capture of the screen depending on the currently chosen capture type. */
    fun beginCapture() {
        when (captureTypeSource.value) {
            ScreenCaptureType.SCREENSHOT -> takeScreenshot()
            ScreenCaptureType.RECORDING -> startRecording()
        }
    }

    private fun takeScreenshot() {
        when (captureRegionSource.value) {
            ScreenCaptureRegion.FULLSCREEN -> beginFullscreenScreenshot()
            ScreenCaptureRegion.PARTIAL -> beginPartialScreenshot()
            ScreenCaptureRegion.APP_WINDOW -> topTask?.let { beginAppWindowScreenshot(it.taskId) }
        }
    }

    private fun beginFullscreenScreenshot() {
        // Hide the UI to avoid the parent window closing animation.
        hideUi()
        backgroundScope.launch {
            // Temporary fix to allow enough time for the pre-capture UI to dismiss.
            // TODO(b/435225255) Exclude the screen capture UI window type from the captured image.
            delay(200)
            screenshotInteractor.requestFullscreenScreenshot(
                displayId = displayId,
                customSaveUri = toolbarViewModel.currentSaveLocationUri,
            )
        }
        closeUi()
    }

    private fun beginPartialScreenshot() {
        val regionBoxRect = requireNotNull(regionBoxSource.value)

        // Hide the UI to avoid the parent window closing animation.
        hideUi()
        backgroundScope.launch {
            // Temporary fix to allow enough time for the pre-capture UI to dismiss.
            // TODO(b/435225255) Exclude the screen capture UI window type from the captured image.
            delay(200)
            screenshotInteractor.requestPartialScreenshot(
                regionBounds = regionBoxRect,
                displayId = displayId,
                customSaveUri = toolbarViewModel.currentSaveLocationUri,
            )
        }
        closeUi()
    }

    fun captureTaskAtPosition(pointerPosition: Point) {
        val task =
            appWindowInteractor.getAppWindowTasks(displayId).firstOrNull {
                it.configuration.windowConfiguration.bounds.contains(
                    pointerPosition.x,
                    pointerPosition.y,
                )
            }
        if (task == null) {
            return
        }
        when (captureTypeSource.value) {
            ScreenCaptureType.SCREENSHOT -> beginAppWindowScreenshot(task.taskId)
            ScreenCaptureType.RECORDING -> startAppWindowRecording(task.taskId)
        }
    }

    private fun beginAppWindowScreenshot(taskId: Int) {
        hideUi()
        backgroundScope.launch {
            screenshotInteractor.requestAppWindowScreenshot(taskId, displayId)
        }
        closeUi()
    }

    private fun startRecording() {
        when (captureRegionSource.value) {
            ScreenCaptureRegion.FULLSCREEN -> startFullscreenRecording()
            ScreenCaptureRegion.PARTIAL -> {}
            ScreenCaptureRegion.APP_WINDOW -> topTask?.let { startAppWindowRecording(it.taskId) }
        }
    }

    private fun startFullscreenRecording() {
        require(captureTypeSource.value == ScreenCaptureType.RECORDING)
        require(captureRegionSource.value == ScreenCaptureRegion.FULLSCREEN)
        uiEventLogger.log(ScreenCaptureEvent.SCREEN_CAPTURE_LARGE_SCREEN_TOOK_FULLSCREEN_RECORDING)
        beginRecording(recordingTarget = null)
    }

    private fun startAppWindowRecording(taskId: Int) {
        require(captureTypeSource.value == ScreenCaptureType.RECORDING)
        require(captureRegionSource.value == ScreenCaptureRegion.APP_WINDOW)

        uiEventLogger.log(ScreenCaptureEvent.SCREEN_CAPTURE_LARGE_SCREEN_TOOK_APP_WINDOW_RECORDING)
        beginRecording(
            recordingTarget =
                MediaProjectionCaptureTarget(LaunchCookie("media_projection_launch_token"), taskId)
        )
    }

    private fun beginRecording(recordingTarget: MediaProjectionCaptureTarget?) {
        // Hide the pre-capture UI before starting the recording.
        // TODO(b/437970158): Show the countdown before starting recording.
        hideUi()
        closeUi()

        backgroundScope.launch {
            screenRecordingServiceInteractor.startRecordingDelayed(
                // TODO(b/437971334): Get options from the UI.
                ScreenRecordingParameters(
                    captureTarget = recordingTarget,
                    audioSource = toolbarViewModel.recordParametersViewModel.audioSource,
                    displayId = displayId,
                    shouldShowTaps = toolbarViewModel.recordParametersViewModel.shouldShowTaps,
                    lowQuality = toolbarViewModel.recordParametersViewModel.lowQuality,
                    longerDuration = toolbarViewModel.recordParametersViewModel.longerDuration,
                )
            )
        }
    }

    /**
     * Simply hides all Composables from being visible, which avoids the parent window close
     * animation. This is useful to ensure the UI is not visible before a screenshot is taken. Note:
     * this does NOT close the parent window. See [closeUi] for closing the window.
     */
    fun hideUi() {
        isShowingUiFlow.value = false
    }

    /** Closes the UI by hiding the parent window. */
    fun closeUi() {
        screenCaptureUiInteractor.hide(
            com.android.systemui.screencapture.common.shared.model.ScreenCaptureType.RECORD
        )
    }

    override suspend fun onActivated() {
        coroutineScope {
            combine(captureTypeSource, captureRegionSource) { type, region -> type to region }
                .mapLatest { (type, region) ->
                    if (type == ScreenCaptureType.RECORDING) {
                        val resId =
                            if (region == ScreenCaptureRegion.FULLSCREEN) {
                                R.string.screenrecord_permission_dialog_warning_entire_screen
                            } else {
                                R.string.screenrecord_permission_dialog_warning_single_app
                            }
                        showDisclaimer(resId)
                    } else {
                        hideDisclaimer()
                    }
                }
                .launchInTraced("PreCaptureViewModel#disclaimer", this)

            launch {
                coroutineScope {
                    launch { initializeCaptureType() }
                    launch { initializeCaptureRegion() }
                }
                if (!isValidCaptureOptions(captureTypeSource.value, captureRegionSource.value)) {
                    // When the initial capture type and region are invalid, reset the region to
                    // Fullscreen.
                    captureRegionSource.value = ScreenCaptureRegion.FULLSCREEN
                    launch {
                        largeScreenCaptureParametersInteractor.setSelectedCaptureRegion(
                            ScreenCaptureRegion.FULLSCREEN
                        )
                    }
                }
                if (captureRegion == ScreenCaptureRegion.APP_WINDOW) {
                    runningTasks = appWindowInteractor.getAppWindowTasks(displayId)
                }
            }
            launch { toolbarViewModel.activate() }
            launch { initializeRegionBox() }
        }
    }

    private suspend fun initializeRegionBox() {
        if (regionBoxSource.value != null) {
            return
        }
        val display = displayManager.getDisplay(displayId) ?: return
        val displayMetrics = DisplayMetrics()
        display.getRealMetrics(displayMetrics)
        val bounds = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)

        val selectedRegionBox = largeScreenCaptureParametersInteractor.getSelectedCaptureRegionBox()
        if (selectedRegionBox != null && bounds.contains(selectedRegionBox)) {
            regionBoxSource.value = selectedRegionBox
        } else {
            regionBoxSource.value =
                Rect(bounds).apply { inset(bounds.width() / 4, bounds.height() / 4) }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(displayId: Int): PreCaptureViewModel
    }
}
