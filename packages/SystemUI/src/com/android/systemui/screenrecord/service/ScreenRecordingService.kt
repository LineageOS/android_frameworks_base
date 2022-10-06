/* Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.screenrecord.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.projection.StopReason
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import com.android.systemui.Flags
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.android.systemui.res.R
import com.android.systemui.screencapture.data.repository.StaticScreenCaptureDeviceStateRepository
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordFeaturesInteractor
import com.android.systemui.screenrecord.ScreenMediaRecorder
import com.android.systemui.screenrecord.ScreenMediaRecorder.SavedRecording
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import com.android.systemui.screenrecord.data.repository.ScreenRecordingPreferenceRepository
import com.android.systemui.screenrecord.notification.NotificationInteractor
import com.android.systemui.screenrecord.notification.ScreenRecordingServiceNotificationInteractor
import com.android.systemui.screenrecord.shared.model.ScreenRecordingParameters
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ScreenRecordingService"

open class ScreenRecordingService : ComponentService() {
    private val tag: String = TAG
    private val createNotificationInteractor: Context.() -> NotificationInteractor = {
        ScreenRecordingServiceNotificationInteractor(
            context = this,
            notificationManager = getSystemService(NotificationManager::class.java)!!,
            screenCaptureRecordFeaturesInteractor =
                ScreenCaptureRecordFeaturesInteractor(
                    StaticScreenCaptureDeviceStateRepository(resources)
                ),
        )
    }
    private val onRecordingSaved:
        ScreenRecordingService.(
            recordingContext: RecordingContext, recording: SavedRecording,
        ) -> Unit =
        { recordingContext, recording ->
            notificationInteractor.notifySaved(
                notificationId = recordingContext.notificationId,
                savedRecording = recording,
            )
        }

    private val backgroundContext = Dispatchers.IO
    private val binder = BinderInterface()
    private val screenMediaRecorderListener: ScreenMediaRecorder.ScreenMediaRecorderListener =
        object : ScreenMediaRecorder.ScreenMediaRecorderListener {
            override fun onStarted() {
                launchCallbackAction { onRecordingStarted() }
            }

            override fun onInfo(mr: MediaRecorder?, what: Int, extra: Int) {
                launchCallbackAction { onRecordingInterrupted(userId, StopReason.STOP_ERROR) }
            }

            override fun onStopped(userId: Int, @StopReason stopReason: Int) {
                launchCallbackAction { onRecordingInterrupted(userId, stopReason) }
            }
        }

    private lateinit var notificationInteractor: NotificationInteractor
    private lateinit var screenRecordingPreferenceRepository: ScreenRecordingPreferenceRepository

    private var recordingContext: RecordingContext? = null
    private var callback: IScreenRecordingServiceCallback? = null

    override fun onCreate() {
        super.onCreate()
        notificationInteractor = createNotificationInteractor()
        screenRecordingPreferenceRepository = ScreenRecordingPreferenceRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP ->
                launchCallbackAction {
                    onRecordingInterrupted(
                        userId,
                        intent.getIntExtra(EXTRA_STOP_REASON, StopReason.STOP_UNKNOWN),
                    )
                }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // System UI has likely crashed because we don't expect it to willingly unbind from this
        // service
        recordingContext?.stopRecording(StopReason.STOP_ERROR)
        return super.onUnbind(intent)
    }

    private fun RecordingContext.startRecording() {
        screenRecordingPreferenceRepository.setShouldShowTaps(shouldShowTaps)
        if (shouldShowSeconds) {
            screenRecordingPreferenceRepository.setShouldShowSeconds(shouldShowSeconds)
        }
        try {
            Log.d(tag, "Starting screen recording user=$userId $this")
            val notification = notificationInteractor.createRecordingNotification(audioSource)
            if (Flags.screenRecordingServiceFix()) {
                startForeground(notificationId, notification)
                recorder.start()
            } else {
                recorder.start()
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.notify(null, notificationId, notification)
            }
        } catch (e: Exception) {
            screenRecordingPreferenceRepository.maybeRestoreSetting()
            Log.e(tag, "Error starting screen recording", e)
            notificationInteractor.notifyErrorStarting(notificationId)
            showToast(R.string.screenrecord_start_error)
            if (Flags.screenRecordingServiceFix()) {
                stopForeground(STOP_FOREGROUND_DETACH)
            }
            stopSelf()
        }
    }

    private suspend fun RecordingContext.saveRecording(uri: Uri) {
        try {
            callback?.onSavingRecording(uri, notificationId)
            Log.d(tag, "Saving screen recording")
            notificationInteractor.notifyProcessing(
                notificationId = notificationId,
                audioSource = audioSource,
            )

            val savedRecording: SavedRecording =
                withContext(backgroundContext) {
                    recorder.save(uri).apply {
                        callback?.onRecordingSaved(uri, thumbnail, notificationId)
                    }
                }
            onRecordingSaved(this, savedRecording)
        } catch (e: Exception) {
            launchCallbackAction { onRecordingSaveError(uri, notificationId) }
            notificationInteractor.notifyErrorSaving(notificationId)
            Log.e(tag, "Error saving screen recording", e)
            showToast(R.string.screenrecord_save_error)
        } finally {
            recorder.release()
            if (Flags.screenRecordingServiceFix()) {
                stopForeground(STOP_FOREGROUND_DETACH)
            }
            stopSelf()
        }
    }

    private fun RecordingContext.stopRecording(@StopReason reason: Int) {
        var recordingUri: Uri? = null
        try {
            recordingUri = recorder.createRecordingUri()
            Log.d(tag, "Stopping screen recording reason=$reason")
            recordingContext = null
            screenRecordingPreferenceRepository.maybeRestoreSetting()
            recorder.end(reason)
            coroutineScope.launch { saveRecording(recordingUri) }
        } catch (e: Exception) {
            launchCallbackAction { onRecordingSaveError(recordingUri, notificationId) }
            notificationInteractor.notifyErrorSaving(notificationId)
            Log.e(tag, "Error stopping screen recording", e)
            showToast(R.string.screenrecord_save_error)
            recorder.release()
            if (Flags.screenRecordingServiceFix()) {
                stopForeground(STOP_FOREGROUND_DETACH)
            }
            stopSelf() // only stop if there is an error. Otherwise, leave it to saveRecording
        }
    }

    private fun launchCallbackAction(action: IScreenRecordingServiceCallback.() -> Unit) {
        callback?.let { coroutineScope.launch(backgroundContext) { it.action() } }
    }

    private inner class BinderInterface : IScreenRecordingService.Stub() {

        override fun setCallback(serviceCallback: IScreenRecordingServiceCallback?) {
            callback = serviceCallback
        }

        override fun stopRecording(@StopReason reason: Int) {
            recordingContext?.stopRecording(reason)
        }

        override fun updateParameters(parameters: ScreenRecordingParameters) {
            screenRecordingPreferenceRepository.setShouldShowTaps(parameters.shouldShowTaps)
            if (parameters.shouldShowSeconds) {
                screenRecordingPreferenceRepository.setShouldShowSeconds(
                    parameters.shouldShowSeconds
                )
            }
        }

        override fun startRecording(parameters: ScreenRecordingParameters) {
            val actualNotificationId =
                if (parameters.notificationId != 0) {
                    parameters.notificationId
                } else {
                    UUID.randomUUID().mostSignificantBits.toInt()
                }
            val context =
                RecordingContext(
                    notificationId = actualNotificationId,
                    captureTarget = parameters.captureTarget,
                    audioSource = parameters.audioSource,
                    displayId = parameters.displayId,
                    shouldShowTaps = parameters.shouldShowTaps,
                    shouldShowSeconds = parameters.shouldShowSeconds,
                    recorder =
                        ScreenMediaRecorder(
                            this@ScreenRecordingService,
                            Handler(Looper.getMainLooper()),
                            Process.myUid(),
                            parameters.audioSource,
                            parameters.captureTarget,
                            parameters.displayId,
                            screenMediaRecorderListener,
                            parameters.lowQuality,
                            parameters.longerDuration,
                            parameters.hevc,
                        ),
                )
            context.startRecording()
            recordingContext = context
        }
    }

    protected data class RecordingContext(
        val recorder: ScreenMediaRecorder,
        val captureTarget: MediaProjectionCaptureTarget?,
        val audioSource: ScreenRecordingAudioSource,
        val displayId: Int,
        val shouldShowTaps: Boolean,
        val shouldShowSeconds: Boolean,
        val notificationId: Int,
    )

    companion object {

        const val ACTION_STOP =
            "com.android.systemui.screenrecord.ScreenRecordingService.ACTION_STOP"
        const val EXTRA_STOP_REASON =
            "com.android.systemui.screenrecord.ScreenRecordingService.EXTRA_STOP_REASON"
    }
}

private fun ComponentService.showToast(@StringRes message: Int) {
    if (Looper.myLooper() != null) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    } else {
        coroutineScope.launch { showToast(message) }
    }
}
