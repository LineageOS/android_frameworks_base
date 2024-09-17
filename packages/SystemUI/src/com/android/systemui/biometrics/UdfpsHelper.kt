/*
 * Copyright (C) 2024 The LineageOS Project
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

package com.android.systemui.biometrics

import android.annotation.UiThread
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.RequestReason
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "UdfpsHelper"

/**
 * Facilitates implementations that use GHBM where dim layer
 * and pressed icon aren't controlled by kernel
 */
@UiThread
class UdfpsHelper(
    private val context: Context,
    private val windowManager: WindowManager,
    private val shadeInteractor: ShadeInteractor,
    private val transitionInteractor: KeyguardTransitionInteractor,
    @RequestReason val requestReason: Int,
    private var view: View = View(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }
) {
    private val displayManager: DisplayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val isKeyguard = when (requestReason) {
        REASON_AUTH_KEYGUARD -> true
        else -> false
    }
    private var newIsQsExpanded: Boolean = false

    private val currentBrightnessFloat: Float
        get() = displayManager.getBrightness(Display.DEFAULT_DISPLAY)
    private val minBrightnessFloat: Float = context.resources
        .getFloat(com.android.internal.R.dimen.config_screenBrightnessSettingMinimumFloat)
    private val maxBrightnessFloat: Float = context.resources
        .getFloat(com.android.internal.R.dimen.config_screenBrightnessSettingMaximumFloat)
    private val brightnessAlphaMap: Map<Int, Int> = context.resources
        .getStringArray(com.android.systemui.res.R.array.config_udfpsDimmingBrightnessAlphaArray)
        .associate {
            val (brightness, alpha) = it.split(",").map { value -> value.trim().toInt() }
            brightness to alpha
        }

    private fun interpolate(
        value: Float,
        fromMin: Int,
        fromMax: Int,
        toMin: Int,
        toMax: Int
    ): Float {
        return toMin + (value - fromMin) * (toMax - toMin) / (fromMax - fromMin)
    }

    private fun interpolateAlpha(brightness: Int): Float {
        val lowerEntry = brightnessAlphaMap.entries
            .lastOrNull { it.key <= brightness }
        val upperEntry = brightnessAlphaMap.entries
            .firstOrNull { it.key >= brightness }

        return if (lowerEntry != null && upperEntry != null) {
            val (lowerBrightness, lowerAlpha) = lowerEntry
            val (upperBrightness, upperAlpha) = upperEntry
             interpolate(
                brightness.toFloat(),
                lowerBrightness,
                upperBrightness,
                lowerAlpha,
                upperAlpha
            ).div(255.0f)
        } else {
            0f
        }
    }

    // The current function does not account for Doze state where the brightness can
    // go lower than what is set on config_screenBrightnessSettingMinimumFloat.
    // While we can operate with floats, the dimming array was made by referencing
    // brightness_alpha_lut array from the kernel. This gives us a comparable array.
    private fun brightnessToAlpha() {
        val minBrightness = (minBrightnessFloat * 4095).toInt()
        val maxBrightness = (maxBrightnessFloat * 4095).toInt()

        val normalizedBrightness =
            interpolate(currentBrightnessFloat, 0, 1, minBrightness, maxBrightness).toInt()

        val currentAlpha = brightnessAlphaMap[normalizedBrightness]?.div(255.0f)
            ?: interpolateAlpha(normalizedBrightness)

        Log.i(TAG, "Normalized Brightness: $normalizedBrightness, Alpha: $currentAlpha")

        // Set the dim for both the view and the layout
        view.alpha = currentAlpha
        dimLayoutParams.alpha = currentAlpha
    }

    private val dimLayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
        0 /* flags set in computeLayoutParams() */,
        PixelFormat.TRANSPARENT
    ).apply {
        title = "Dim Layer for - Udfps"
        fitInsetsTypes = 0
        gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        flags = (Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION
        // Avoid announcing window title.
        accessibilityTitle = " "
        inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_SPY
    }

    fun addDimLayer() {
        brightnessToAlpha()
        windowManager.addView(view, dimLayoutParams)
    }

    fun removeDimLayer() {
        windowManager.removeView(view)
    }

    init {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                listenForQsExpansion(this)

                if (isKeyguard) {
                    listenForAnyStateToLockscreenTransition(this)
                    listenForLockscreenToAnyStateTransition(this)
                }
            }
        }

        if (!isKeyguard) {
            view.visibility = View.VISIBLE
        }
    }

    private suspend fun listenForAnyStateToLockscreenTransition(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.transition(Edge.create(to = LOCKSCREEN))
                .collect { transitionStep ->
                    if (transitionStep.transitionState == TransitionState.STARTED) {
                        view.visibility = View.VISIBLE
                    }
                }
        }
    }

    private suspend fun listenForLockscreenToAnyStateTransition(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.transition(Edge.create(from = LOCKSCREEN))
                .collect { transitionStep ->
                    if (transitionStep.transitionState == TransitionState.FINISHED) {
                        view.visibility = View.GONE
                    }
                }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                brightnessToAlpha()
                windowManager.updateViewLayout(view, dimLayoutParams)
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
        }
    }

    // Since we are not observing KEY_BRIGHTNESS, the display listener updates
    // brightness changes only when the slider is released. So, the dim layer
    // is set to View.GONE when the slider is expected to be utilized.
    private suspend fun listenForQsExpansion(scope: CoroutineScope): Job {
        return scope.launch {
            shadeInteractor.qsExpansion.collect { qsExpansion ->
                if (qsExpansion == 1f && !newIsQsExpanded) {
                    newIsQsExpanded = true
                    displayManager.registerDisplayListener(
                        displayListener, null, EVENT_FLAG_DISPLAY_BRIGHTNESS
                    )
                    view.visibility = View.GONE
                } else if (qsExpansion == 0f && newIsQsExpanded) {
                    newIsQsExpanded = false
                    displayManager.unregisterDisplayListener(displayListener)
                    view.visibility = View.VISIBLE
                }
            }
        }
    }
}
