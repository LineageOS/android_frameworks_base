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

package com.android.systemui.flashlight.data.repository

import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraManager.TorchCallback
import android.provider.Settings
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.flashlight.flags.FlashlightStrength
import com.android.systemui.flashlight.shared.logger.FlashlightLogger
import com.android.systemui.flashlight.shared.model.FlashlightModel
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printSection
import com.android.systemui.util.println
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provides information about flashlight availability, its state of being on/off, and level. Can be
 * used to enable/disable or set level of flashlight.
 */
interface FlashlightRepository {
    val state: StateFlow<FlashlightModel>

    val deviceSupportsFlashlight: Boolean

    fun setEnabled(enabled: Boolean)

    /** Consistent with [CameraManager.turnOnTorchWithStrengthLevel] level cannot be 0 */
    fun setLevel(level: Int)

    /**
     * The level will not be remembered when the flashlight is disabled and enabled. This function
     * is useful onValueChange and should be finished with a [setLevel] call onValueChangeFinished.
     */
    fun setTemporaryLevel(level: Int)
}

@SysUISingleton
class FlashlightRepositoryImpl
@Inject
constructor(
    @Background private val bgScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val secureSettings: SecureSettingsRepository,
    private val cameraManager: CameraManager,
    private val dumpManager: DumpManager,
    private val packageManager: PackageManager,
    private val logger: FlashlightLogger,
    private val userRepo: UserRepository,
) : FlashlightRepository, CoreStartable {

    private sealed interface FlashlightInfo {
        data object NotSupported : FlashlightInfo

        sealed interface Supported : FlashlightInfo {
            data object Initial : Supported

            data object ErrorLoading : Supported

            data class LoadedSuccessfully(
                val cameraId: String,
                val defaultLevel: Int?,
                val maxLevel: Int?,
            ) : Supported {
                val hasAdjustableLevels: Boolean =
                    defaultLevel != null && maxLevel != null && maxLevel > BASE_TORCH_LEVEL
            }
        }
    }

    private val flashlightInfo = MutableStateFlow<FlashlightInfo>(FlashlightInfo.NotSupported)

    private var canAttemptReconnect = AtomicBoolean(true)

    private var _deviceSupportsFlashlight = false

    private val defaultEnabledLevelForUser = ConcurrentHashMap<Int, Int>()

    override fun start() {
        if (FlashlightStrength.isUnexpectedlyInLegacyMode()) {
            return
        }
        dumpManager.registerNormalDumpable(javaClass.simpleName, this)
        bgScope.launch {
            _deviceSupportsFlashlight =
                packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
            if (!_deviceSupportsFlashlight) {
                logger.d(
                    "start: device does not have camera flash system feature. " +
                        "Will not attempt to read flashlight info."
                )
                return@launch
            }

            flashlightInfo.emit(FlashlightInfo.Supported.Initial)

            // let's see if it can connect to a flashlight in practice
            connectToCameraLoadFlashlightInfo()
        }
    }

    /**
     * Does nothing if already loaded successfully or not supported. Emits
     * [FlashlightInfo.Supported.ErrorLoading] if flashlight is supported but cannot be loaded
     *
     * @return flashlight info loaded
     */
    private suspend fun connectToCameraLoadFlashlightInfo(): Boolean =
        when (flashlightInfo.value) {
            is FlashlightInfo.NotSupported -> false
            is FlashlightInfo.Supported.LoadedSuccessfully -> true
            is FlashlightInfo.Supported.Initial,
            FlashlightInfo.Supported.ErrorLoading -> {
                if (canAttemptReconnect.getAndSet(false)) {
                    updateReconnect()
                    var foundCamera: Boolean
                    try {
                        foundCamera = loadFlashlightInfo() != null
                    } catch (exception: Exception) {
                        foundCamera = false
                        logger.w("Could not find a camera: ${exception.message}")
                    }
                    if (!foundCamera) {
                        flashlightInfo.emit(FlashlightInfo.Supported.ErrorLoading)
                    }
                    foundCamera
                } else {
                    logger.d(
                        "Need to wait for ${RECONNECT_COOLDOWN.inWholeSeconds} seconds from" +
                            " last attempt before trying to reconnect."
                    )
                    false
                }
            }
        }

    /** Prevent reconnect attempts until [RECONNECT_COOLDOWN] later. */
    private fun updateReconnect() {
        bgScope.launch {
            delay(RECONNECT_COOLDOWN)
            canAttemptReconnect.set(true)
        }
    }

    /**
     * Reads flashlight info from available [CameraCharacteristics].
     *
     * On devices with multiple flash-capable back cameras, the HAL routes torch through the
     * logical camera (which has physical camera IDs) rather than a plain physical camera.
     * We prefer the logical camera to ensure torch commands reach the correct camera node.
     *
     * @return the id of a connected camera that has flashlight, or null if none connected.
     * @throws CameraAccessException if the camera device have been disconnected
     */
    private suspend fun loadFlashlightInfo(): String? {
        val ids = cameraManager.cameraIdList

        fun isBackFlashCamera(id: String): Boolean {
            val cc = cameraManager.getCameraCharacteristics(id)
            val flashAvailable = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
            val lensFacing = cc.get(CameraCharacteristics.LENS_FACING)
            return flashAvailable == true &&
                lensFacing == CameraCharacteristics.LENS_FACING_BACK
        }

        fun isLogicalCamera(id: String): Boolean {
            val cc = cameraManager.getCameraCharacteristics(id)
            return cc.physicalCameraIds.isNotEmpty()
        }

        // Prefer logical multi-camera with flash — the HAL routes torch through these
        // on devices where multiple cameras share a single flash unit.
        val selectedId =
            ids.firstOrNull { isBackFlashCamera(it) && isLogicalCamera(it) }
                ?: ids.firstOrNull { isBackFlashCamera(it) }

        if (selectedId != null) {
            val cc = cameraManager.getCameraCharacteristics(selectedId)
            val default: Int? = cc.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL)
            val max: Int? = cc.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)
            if (default != null) {
                defaultEnabledLevelForUser[currentUserId] = default
            }
            flashlightInfo.emit(
                FlashlightInfo.Supported.LoadedSuccessfully(selectedId, default, max)
            )
        }

        return selectedId
    }

    /**
     * Should be started if and only if device supports flashlight.
     *
     * TODO(B/412982015) try to reconnect on new subscriptions
     */
    private val _state: Flow<FlashlightModel> = conflatedCallbackFlow {
        val callbackFromSystem =
            object : TorchCallback() {
                override fun onTorchModeUnavailable(camId: String) {
                    val currentFlashlightInfo = flashlightInfo.value
                    if (
                        currentFlashlightInfo is FlashlightInfo.Supported.LoadedSuccessfully &&
                            camId == currentFlashlightInfo.cameraId
                    ) {
                        trySend(FlashlightModel.Unavailable.Temporarily.CameraInUse)
                    }
                }

                override fun onTorchModeChanged(camId: String, enabled: Boolean) {
                    val currentFlashlightInfo = flashlightInfo.value
                    if (currentFlashlightInfo is FlashlightInfo.Supported.LoadedSuccessfully)
                        if (camId == currentFlashlightInfo.cameraId) {
                            if (currentFlashlightInfo.hasAdjustableLevels) {
                                trySend(
                                    FlashlightModel.Available.Level(
                                        enabled,
                                        if (enabled) {
                                            cameraManager.getTorchStrengthLevel(camId)
                                        } else {
                                            defaultEnabledLevelForUser.getOrPut(currentUserId) {
                                                initialDefaultLevel
                                            }
                                        },
                                        currentFlashlightInfo.maxLevel!!, // b/c hasAdjustableLevels
                                    )
                                )
                            } else {
                                trySend(FlashlightModel.Available.Binary(enabled))
                            }
                        } else {
                            logger.w(
                                "onTorchModeChanged: saved camera id was" +
                                    " ${currentFlashlightInfo.cameraId} but flashlight with" +
                                    " camera id $camId called back."
                            )
                        }
                }

                /**
                 * This callback does not shoot when torch is turned off and level goes back to
                 * default in the backend.
                 */
                override fun onTorchStrengthLevelChanged(camId: String, newStrengthLevel: Int) {
                    val currentFlashlightInfo = flashlightInfo.value
                    if (currentFlashlightInfo is FlashlightInfo.Supported.LoadedSuccessfully)
                        if (camId == currentFlashlightInfo.cameraId) {
                            if (currentFlashlightInfo.hasAdjustableLevels)
                                trySend(
                                    FlashlightModel.Available.Level(
                                        true, // this callback happens only when enabled
                                        newStrengthLevel,
                                        currentFlashlightInfo.maxLevel!!, // b/c hasAdjustableLevels
                                    )
                                )
                            else
                                logger.w(
                                    "onTorchStrengthLevelChanged: One of the levels was" +
                                        " null or max was below base level. default:${currentFlashlightInfo.defaultLevel}, max:${currentFlashlightInfo.maxLevel}"
                                )
                        } else {
                            logger.w(
                                "onTorchStrengthLevelChanged: saved camera id was" +
                                    " ${currentFlashlightInfo.cameraId} but flashlight with" +
                                    " camera id $camId called back."
                            )
                        }
                }
            }

        cameraManager.registerTorchCallback(bgDispatcher.asExecutor(), callbackFromSystem)
        awaitClose { cameraManager.unregisterTorchCallback(callbackFromSystem) }
    }

    /**
     * When flashlight is temporarily unavailable, we wait for it to to become available and then we
     * call [connectToCameraLoadFlashlightInfo] to switch on to the [_state].
     */
    private val reconnectFlow: Flow<FlashlightModel> = conflatedCallbackFlow {
        val callbackFromSystem =
            object : TorchCallback() {
                override fun onTorchModeChanged(camId: String, enabled: Boolean) {
                    bgScope.launch { connectToCameraLoadFlashlightInfo() }
                }
            }
        trySend(FlashlightModel.Unavailable.Temporarily.NotFound)
        cameraManager.registerTorchCallback(bgDispatcher.asExecutor(), callbackFromSystem)
        awaitClose { cameraManager.unregisterTorchCallback(callbackFromSystem) }
    }

    /**
     * The only place this repo diverges from the [CameraManager.getTorchStrengthLevel] API, when
     * the flashlight is off. This API will show the last enabled level, but that one will show the
     * device default.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: StateFlow<FlashlightModel> =
        flashlightInfo
            .flatMapLatest {
                when (it) {
                    is FlashlightInfo.NotSupported ->
                        flowOf(FlashlightModel.Unavailable.Permanently.NotSupported)

                    is FlashlightInfo.Supported.Initial ->
                        flowOf(FlashlightModel.Unavailable.Temporarily.Loading)

                    is FlashlightInfo.Supported.ErrorLoading -> reconnectFlow

                    is FlashlightInfo.Supported.LoadedSuccessfully -> _state
                }
            }
            .onEach {
                updateSecureSettings(it)
                logger.logStateChanged(it)
            }
            .stateIn(
                bgScope,
                SharingStarted.WhileSubscribed(),
                FlashlightModel.Unavailable.Temporarily.Loading,
            )

    /** We do this for tests and other flashlight controls e.g. on lock screen. */
    private suspend fun updateSecureSettings(it: FlashlightModel) {
        when (it) {
            is FlashlightModel.Unavailable ->
                secureSettings.setInt(Settings.Secure.FLASHLIGHT_AVAILABLE, 0)

            is FlashlightModel.Available -> {
                secureSettings.setInt(Settings.Secure.FLASHLIGHT_AVAILABLE, 1)
                secureSettings.setInt(Settings.Secure.FLASHLIGHT_ENABLED, if (it.enabled) 1 else 0)
            }
        }
    }

    override val deviceSupportsFlashlight: Boolean
        get() = _deviceSupportsFlashlight

    override fun setEnabled(enabled: Boolean) {
        bgScope.launch {
            if (!connectToCameraLoadFlashlightInfo()) {
                logger.w("Could not connect to a flashlight")
                return@launch
            }
            val currentState = waitForStateToBecomeAvailableOrPermanentLyUnavailable()
            if (currentState !is FlashlightModel.Available) return@launch

            val currentFlashlightInfo = flashlightInfo.value
            if (currentFlashlightInfo !is FlashlightInfo.Supported.LoadedSuccessfully) return@launch

            try {
                if (enabled != currentState.enabled) {
                    if (currentFlashlightInfo.hasAdjustableLevels && enabled) {
                        cameraManager.turnOnTorchWithStrengthLevel(
                            currentFlashlightInfo.cameraId,
                            defaultEnabledLevelForUser.getOrPut(currentUserId) {
                                initialDefaultLevel
                            },
                        )
                    } else {
                        cameraManager.setTorchMode(currentFlashlightInfo.cameraId, enabled)
                    }
                }
            } catch (e: CameraAccessException) {
                e.printStackTrace()
                logger.w("Error trying to setEnabled to $enabled: ${e.message}")
            }
        }
    }

    /** @throws IllegalArgumentException if level is below 1 and above max */
    override fun setLevel(level: Int) {
        setLevel(level, true)
    }

    override fun setTemporaryLevel(level: Int) {
        setLevel(level, false)
    }

    private fun setLevel(level: Int, persist: Boolean) {
        bgScope.launch {
            if (!connectToCameraLoadFlashlightInfo()) {
                logger.w("Could not connect to a flashlight")
                return@launch
            }
            val currentState = waitForStateToBecomeAvailableOrPermanentLyUnavailable()

            if (currentState !is FlashlightModel.Available.Level) return@launch

            val currentInfo = flashlightInfo.value
            if (currentInfo !is FlashlightInfo.Supported.LoadedSuccessfully) return@launch

            try {
                if (level != currentState.level) {
                    cameraManager.turnOnTorchWithStrengthLevel(currentInfo.cameraId, level)
                }
                if (persist) {
                    defaultEnabledLevelForUser[currentUserId] = level
                }
            } catch (e: CameraAccessException) {
                e.printStackTrace()
                logger.w("Error trying to setLevel to $level: ${e.message}")
            }
        }
    }

    private suspend fun waitForStateToBecomeAvailableOrPermanentLyUnavailable(): FlashlightModel =
        withTimeoutOrNull(RECONNECT_TIMEOUT.inWholeMilliseconds) {
            state.firstOrNull { it !is FlashlightModel.Unavailable.Temporarily }
        } ?: state.value

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            printSection("FlashlightRepository") {
                println("flashlightInfo", flashlightInfo.value)
                println("state", state.value)
            }
        }
    }

    private val initialDefaultLevel: Int
        get() =
            (flashlightInfo.value as? FlashlightInfo.Supported.LoadedSuccessfully)?.defaultLevel
                ?: BASE_TORCH_LEVEL

    private val currentUserId: Int
        get() = userRepo.selectedUser.value.userInfo.id

    private companion object {
        private const val BASE_TORCH_LEVEL = 1
        private val RECONNECT_COOLDOWN = 30.seconds
        private val RECONNECT_TIMEOUT = 2.seconds
    }
}
