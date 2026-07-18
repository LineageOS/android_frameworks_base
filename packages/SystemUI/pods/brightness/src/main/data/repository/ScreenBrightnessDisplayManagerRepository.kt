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

package com.android.systemui.brightness.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.hardware.display.BrightnessInfo
import android.hardware.display.DisplayManager
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.brightness.data.model.LinearBrightness
import com.android.systemui.brightness.data.model.formatBrightness
import com.android.systemui.brightness.data.model.logDiffForTable
import com.android.systemui.brightness.shared.BrightnessLog
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
@SysUISingleton
public class ScreenBrightnessDisplayManagerRepository
@Inject
constructor(
    @DisplayId private val displayId: Int,
    private val displayManager: DisplayManager,
    @BrightnessLog private val logBuffer: LogBuffer,
    @BrightnessLog private val tableBuffer: TableLogBuffer,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundContext: CoroutineContext,
    @Application private val context: Context,
) : ScreenBrightnessRepository {

    private val apiQueue = Channel<SetBrightnessMethod>(capacity = Channel.UNLIMITED)

    init {
        applicationScope.launch(context = backgroundContext) {
            for (call in apiQueue) {
                val bounds = getMinMaxLinearBrightness()
                val value = call.value.clamp(bounds.first, bounds.second).floatValue
                when (call) {
                    is SetBrightnessMethod.Temporary -> {
                        displayManager.setTemporaryBrightness(displayId, value)
                    }
                    is SetBrightnessMethod.Permanent -> {
                        displayManager.setBrightness(displayId, value)
                    }
                }
                logBrightnessChange(call is SetBrightnessMethod.Permanent, value)
            }
        }
    }

    private val brightnessInfo: StateFlow<BrightnessInfo?> =
        conflatedCallbackFlow {
                val listener =
                    object : DisplayManager.DisplayListener {
                        override fun onDisplayAdded(displayId: Int) {}

                        override fun onDisplayRemoved(displayId: Int) {}

                        override fun onDisplayChanged(displayId: Int) {
                            if (
                                displayId == this@ScreenBrightnessDisplayManagerRepository.displayId
                            ) {
                                trySend(Unit)
                            }
                        }
                    }
                displayManager.registerDisplayListener(
                    listener,
                    null,
                    DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS,
                )

                awaitClose { displayManager.unregisterDisplayListener(listener) }
            }
            .onStart { emit(Unit) }
            .map { brightnessInfoValue() }
            .flowOn(backgroundContext)
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(replayExpirationMillis = 0L),
                null,
            )

    private suspend fun brightnessInfoValue(): BrightnessInfo? {
        return withContext(backgroundContext) {
            displayManager.getDisplay(displayId).brightnessInfo
        }
    }

    override val minLinearBrightness: SharedFlow<LinearBrightness> =
        brightnessInfo
            .filterNotNull()
            .map { LinearBrightness(it.brightnessMinimum) }
            .logDiffForTable(tableBuffer, TABLE_PREFIX_LINEAR, TABLE_COLUMN_MIN, null)
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), LinearBrightness(0f))

    override val maxLinearBrightness: SharedFlow<LinearBrightness> =
        brightnessInfo
            .filterNotNull()
            .map { LinearBrightness(it.brightnessMaximum) }
            .logDiffForTable(tableBuffer, TABLE_PREFIX_LINEAR, TABLE_COLUMN_MAX, null)
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), LinearBrightness(1f))

    override suspend fun getMinMaxLinearBrightness(): Pair<LinearBrightness, LinearBrightness> {
        val brightnessInfo = brightnessInfo.value ?: brightnessInfoValue()
        val min = brightnessInfo?.brightnessMinimum ?: 0f
        val max = brightnessInfo?.brightnessMaximum ?: 1f
        return LinearBrightness(min) to LinearBrightness(max)
    }

    override val linearBrightness: Flow<LinearBrightness> =
        brightnessInfo
            .filterNotNull()
            .map { LinearBrightness(it.brightness) }
            .logDiffForTable(tableBuffer, TABLE_PREFIX_LINEAR, TABLE_COLUMN_BRIGHTNESS, null)
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), LinearBrightness(0f))

    override val isBrightnessOverriddenByWindow: StateFlow<Boolean> =
        brightnessInfo
            .filterNotNull()
            .map { it.isBrightnessOverrideByWindow }
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), false)

    override fun setTemporaryBrightness(value: LinearBrightness) {
        apiQueue.trySend(SetBrightnessMethod.Temporary(value))
    }

    override fun setBrightness(value: LinearBrightness) {
        apiQueue.trySend(SetBrightnessMethod.Permanent(value))
    }

    override val isAutoBrightnessEnabledFlow: StateFlow<Boolean> =
        conflatedCallbackFlow {
            val uri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE)
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(isAutoBrightnessEnabled())
                }
            }
            context.contentResolver.registerContentObserver(uri, false, observer, UserHandle.USER_ALL)
            trySend(isAutoBrightnessEnabled())
            awaitClose {
                context.contentResolver.unregisterContentObserver(observer)
            }
        }
        .flowOn(backgroundContext)
        .stateIn(applicationScope, SharingStarted.WhileSubscribed(), isAutoBrightnessEnabled())

    fun isAutoBrightnessEnabled(): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            UserHandle.USER_CURRENT
        ) != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    }

    override fun toggleBrightnessMode() {
        val enabled = isAutoBrightnessEnabled()
        Settings.System.putIntForUser(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            UserHandle.USER_CURRENT
        )
    }

    private sealed interface SetBrightnessMethod {
        val value: LinearBrightness

        @JvmInline
        value class Temporary(override val value: LinearBrightness) : SetBrightnessMethod

        @JvmInline
        value class Permanent(override val value: LinearBrightness) : SetBrightnessMethod
    }

    private fun logBrightnessChange(permanent: Boolean, value: Float) {
        logBuffer.log(
            LOG_BUFFER_BRIGHTNESS_CHANGE_TAG,
            if (permanent) LogLevel.DEBUG else LogLevel.VERBOSE,
            { str1 = value.formatBrightness() },
            { "Change requested: $str1" },
        )
    }

    private companion object {
        const val TABLE_COLUMN_BRIGHTNESS = "brightness"
        const val TABLE_COLUMN_MIN = "min"
        const val TABLE_COLUMN_MAX = "max"
        const val TABLE_PREFIX_LINEAR = "linear"
        const val LOG_BUFFER_BRIGHTNESS_CHANGE_TAG = "BrightnessChange"
    }
}
