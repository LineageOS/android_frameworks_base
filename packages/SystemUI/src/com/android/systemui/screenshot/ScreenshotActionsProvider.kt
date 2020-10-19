/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import com.android.internal.logging.UiEventLogger
import com.android.systemui.Flags
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.DebugLogger.debugLog
import com.android.systemui.res.R
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordFeaturesInteractor
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_COPY_TAPPED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_DELETE_TAPPED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_EDIT_TAPPED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_OPEN_TAPPED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_PREVIEW_TAPPED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_SHARE_TAPPED
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonAppearance
import com.android.systemui.screenshot.ui.viewmodel.PreviewAction
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

typealias ScrollClickCallback = ((Uri) -> Unit)

/**
 * Provides actions for screenshots. This class can be overridden by a vendor-specific SysUI
 * implementation.
 */
interface ScreenshotActionsProvider {
    fun onScrollChipReady(onClick: ScrollClickCallback)

    fun onScrollChipInvalidated()

    fun setCompletedScreenshot(result: ScreenshotSavedResult)

    /**
     * Provide the AssistContent for the focused task if available, null if the focused task isn't
     * known or didn't return data.
     */
    fun onAssistContent(assistContent: AssistContent?) {}

    interface Factory {
        fun create(
            requestId: UUID,
            request: ScreenshotData,
            actionExecutor: ActionExecutor,
            actionsCallback: ScreenshotActionsController.ActionsCallback,
        ): ScreenshotActionsProvider
    }
}

class DefaultScreenshotActionsProvider
@AssistedInject
constructor(
    private val context: Context,
    private val uiEventLogger: UiEventLogger,
    private val actionIntentCreator: ActionIntentCreator,
    private val packageManager: PackageManager,
    @Application private val applicationScope: CoroutineScope,
    screenCaptureRecordFeaturesInteractor: ScreenCaptureRecordFeaturesInteractor,
    @Assisted val requestId: UUID,
    @Assisted val request: ScreenshotData,
    @Assisted val actionExecutor: ActionExecutor,
    @Assisted val actionsCallback: ScreenshotActionsController.ActionsCallback,
) : ScreenshotActionsProvider {
    private var addedScrollChip = false
    private var onScrollClick: ScrollClickCallback? = null
    private var pendingAction: (suspend (ScreenshotSavedResult) -> Unit)? = null
    private var result: ScreenshotSavedResult? = null
    private var webUri: Uri? = null

    init {
        actionsCallback.providePreviewAction(
            PreviewAction(context.resources.getString(R.string.screenshot_edit_description)) {
                debugLog(LogConfig.DEBUG_ACTIONS) { "Preview tapped" }
                uiEventLogger.log(SCREENSHOT_PREVIEW_TAPPED, 0, request.packageNameString)
                onDeferrableActionTapped { result ->
                    actionExecutor.startSharedTransition(
                        actionIntentCreator.createView(result.uri),
                        result.user,
                        true,
                    )
                }
            }
        )

        actionsCallback.provideActionButton(
            ActionButtonAppearance(
                AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_share),
                context.resources.getString(R.string.screenshot_share_label),
                context.resources.getString(R.string.screenshot_share_description),
            ),
            showDuringEntrance = true,
        ) {
            debugLog(LogConfig.DEBUG_ACTIONS) { "Share tapped" }
            uiEventLogger.log(SCREENSHOT_SHARE_TAPPED, 0, request.packageNameString)
            onDeferrableActionTapped { result ->
                val uri = webUri
                val shareIntent =
                    if (uri != null) {
                        actionIntentCreator.createShareWithText(
                            result.uri,
                            extraText = uri.toString(),
                        )
                    } else {
                        actionIntentCreator.createShareWithSubject(result.uri, result.subject)
                    }

                actionExecutor.startSharedTransition(shareIntent, result.user, false)
            }
        }

        if (screenCaptureRecordFeaturesInteractor.isLargeScreenScreencaptureEnabled) {
            actionsCallback.provideActionButton(
                ActionButtonAppearance(
                    AppCompatResources.getDrawable(context, R.drawable.ic_content_copy),
                    context.resources.getString(R.string.screenshot_copy_label),
                    context.resources.getString(R.string.screenshot_copy_description),
                ),
                showDuringEntrance = true,
            ) {
                debugLog(LogConfig.DEBUG_ACTIONS) { "Copy tapped" }
                uiEventLogger.log(SCREENSHOT_COPY_TAPPED, 0, request.packageNameString)
                onDeferrableActionTapped { result ->
                    actionExecutor.copyScreenshotToClipboard(result.uri)
                }
            }
        } else {
            // The edit button is intentionally hidden on large-screen devices since the screenshot
            // thumbnail serves the same action.
            actionsCallback.provideActionButton(
                ActionButtonAppearance(
                    AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_edit),
                    context.resources.getString(R.string.screenshot_edit_label),
                    context.resources.getString(R.string.screenshot_edit_description),
                ),
                showDuringEntrance = true,
            ) {
                debugLog(LogConfig.DEBUG_ACTIONS) { "Edit tapped" }
                uiEventLogger.log(SCREENSHOT_EDIT_TAPPED, 0, request.packageNameString)
                onDeferrableActionTapped { result ->
                    actionExecutor.startSharedTransition(
                        actionIntentCreator.createEdit(result.uri),
                        result.user,
                        true,
                    )
                }
            }
        }

        // Check if there is an appropriate package to open up the screenshot's directory before
        // showing the open button.
        if (
            screenCaptureRecordFeaturesInteractor.isLargeScreenScreencaptureEnabled &&
                shouldShowOpenButton()
        ) {
            actionsCallback.provideActionButton(
                ActionButtonAppearance(
                    context.getDrawable(R.drawable.ic_screen_capture_folder),
                    context.resources.getString(R.string.screenshot_open_in_folder_label),
                    context.resources.getString(R.string.screenshot_open_in_folder_description),
                ),
                showDuringEntrance = true,
            ) {
                debugLog(LogConfig.DEBUG_ACTIONS) { "Open tapped" }
                uiEventLogger.log(SCREENSHOT_OPEN_TAPPED, 0, request.packageNameString)
                onDeferrableActionTapped { result ->
                    val intent = actionIntentCreator.createOpenInFiles(result.uri)
                    if (intent != null) {
                        actionExecutor.startSharedTransition(intent, result.user, false)
                    } else {
                        Log.e(TAG, "Failed to create Intent for mediaStoreUri: ${result.uri} ")
                    }
                }
            }
        }

        actionsCallback.provideActionButton(
            ActionButtonAppearance(
                AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_delete),
                context.resources.getString(R.string.screenshot_delete_label),
                context.resources.getString(R.string.screenshot_delete_description),
            ),
            showDuringEntrance = true,
        ) {
            debugLog(LogConfig.DEBUG_ACTIONS) { "Delete tapped" }
            uiEventLogger.log(SCREENSHOT_DELETE_TAPPED, 0, request.packageNameString)
            onDeferrableActionTapped { result ->
                actionExecutor.sendPendingIntent(
                    actionIntentCreator.createDelete(result.uri)
                )
            }
        }
    }

    override fun onScrollChipReady(onClick: ScrollClickCallback) {
        onScrollClick = onClick
        if (!addedScrollChip) {
            actionsCallback.provideActionButton(
                ActionButtonAppearance(
                    AppCompatResources.getDrawable(context, R.drawable.ic_screenshot_scroll),
                    context.resources.getString(R.string.screenshot_scroll_label),
                    context.resources.getString(R.string.screenshot_scroll_label),
                ),
                showDuringEntrance = true,
            ) {
                if (Flags.deleteAfterScrollCapture()) {
                    onDeferrableActionTapped { result -> onScrollClick?.invoke(result.uri) }
                } else {
                    onScrollClick?.invoke(Uri.EMPTY)
                }
            }
            addedScrollChip = true
        }
    }

    override fun onScrollChipInvalidated() {
        onScrollClick = null
    }

    override fun setCompletedScreenshot(result: ScreenshotSavedResult) {
        if (this.result != null) {
            Log.e(TAG, "Got a second completed screenshot for existing request!")
            return
        }
        this.result = result
        pendingAction?.also { applicationScope.launch { it.invoke(result) } }
    }

    override fun onAssistContent(assistContent: AssistContent?) {
        webUri = assistContent?.webUri
    }

    private fun onDeferrableActionTapped(onResult: suspend (ScreenshotSavedResult) -> Unit) {
        result?.let { applicationScope.launch { onResult.invoke(it) } }
            ?: run { pendingAction = onResult }
    }

    private fun shouldShowOpenButton(): Boolean {
        val filesIntent = Intent(Intent.ACTION_VIEW)
        filesIntent.setType(DocumentsContract.Document.MIME_TYPE_DIR)
        filesIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        filesIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        filesIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val result = packageManager.resolveActivity(filesIntent, 0) != null

        return result
    }

    @AssistedFactory
    interface Factory : ScreenshotActionsProvider.Factory {
        override fun create(
            requestId: UUID,
            request: ScreenshotData,
            actionExecutor: ActionExecutor,
            actionsCallback: ScreenshotActionsController.ActionsCallback,
        ): DefaultScreenshotActionsProvider
    }

    companion object {
        private const val TAG = "ScreenshotActionsPrvdr"
    }
}
