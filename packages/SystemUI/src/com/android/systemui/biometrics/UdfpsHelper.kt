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

import android.animation.ValueAnimator
import android.annotation.UiThread
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.RequestReason
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
    @RequestReason val requestReason: Int,
    private var view: View = View(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)!!
    private val isKeyguard = requestReason == REASON_AUTH_KEYGUARD
    private var newIsQsExpanded = false

    private val currentBrightness: Float get() =
        displayManager.getBrightness(Display.DEFAULT_DISPLAY)
    private val minBrightness: Float = context.resources
        .getFloat(com.android.internal.R.dimen.config_screenBrightnessSettingMinimumFloat)
    private val maxBrightness: Float = context.resources
        .getFloat(com.android.internal.R.dimen.config_screenBrightnessSettingMaximumFloat)
    private val brightnessAlphaMap: Map<Int, Int> = context.resources
        .getStringArray(com.android.systemui.res.R.array.config_udfpsDimmingBrightnessAlphaArray)
        .associate {
            val (brightness, alpha) = it.split(",").map { value -> value.trim().toInt() }
            brightness to alpha
        }
    val maxPanelBrightness: Int = brightnessAlphaMap.keys.max()

    private val dimLayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
        0 /* flags are set in computeLayoutParams() */,
        PixelFormat.TRANSPARENT
    ).apply {
        title = "Dim Layer for UDFPS"
        fitInsetsTypes = 0
        gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        flags = Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
        // Avoid announcing window title
        accessibilityTitle = " "
        inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_SPY
    }

    private val alphaAnimator = ValueAnimator().apply {
        duration = 800L
        addUpdateListener { animator ->
            view.alpha = animator.animatedValue as Float
            dimLayoutParams.alpha = animator.animatedValue as Float
            try {
                windowManager.updateViewLayout(view, dimLayoutParams)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "View not attached to WindowManager", e)
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                brightnessToAlpha()
                windowManager.updateViewLayout(view, dimLayoutParams)
            }
        }

        override fun onDisplayRemoved(displayId: Int) {}
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
            .lastOrNull { it.key <= brightness } ?: return 0f
        val upperEntry = brightnessAlphaMap.entries
            .firstOrNull { it.key >= brightness } ?: return 0f
        val (lowerBrightness, lowerAlpha) = lowerEntry
        val (upperBrightness, upperAlpha) = upperEntry

        return interpolate(
            brightness.toFloat(),
            lowerBrightness,
            upperBrightness,
            lowerAlpha,
            upperAlpha
        ).div(255.0f)
    }

    // The current function does not account for Doze state where the brightness can go lower
    // than what is set on config_screenBrightnessSettingMinimumFloat.
    // While it's possible to operate with floats, the dimming array was made by referencing
    // brightness_alpha_lut array from the kernel. This provides a comparable array.
    private fun brightnessToAlpha() {
        val adjustedBrightness =
            (currentBrightness.coerceIn(minBrightness, maxBrightness) * maxPanelBrightness).toInt()

        val targetAlpha = brightnessAlphaMap[adjustedBrightness]?.div(255.0f)
            ?: interpolateAlpha(adjustedBrightness)

        Log.i(TAG, "Adjusted Brightness: $adjustedBrightness, Alpha: $targetAlpha")

        alphaAnimator.setFloatValues(view.alpha, targetAlpha)
        // Set the dim for both the view and the layout
        view.alpha = targetAlpha
        dimLayoutParams.alpha = targetAlpha
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
                    listenForShadeTouchability(this)
                }
            }
        }

        if (!isKeyguard) {
            view.isVisible = true
        }
    }

    // We don't have ways to get temporary brightness when operating the brightness slider.
    // Therefore, the dim layer is hidden when the slider is expected to be utilized.
    private suspend fun listenForQsExpansion(scope: CoroutineScope): Job {
        return scope.launch {
            shadeInteractor.qsExpansion.collect { qsExpansion ->
                if (qsExpansion == 1f && !newIsQsExpanded) {
                    newIsQsExpanded = true
                    displayManager.registerDisplayListener(
                        displayListener,
                        null,
                        DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS,
                    )
                    view.isVisible = false
                } else if (qsExpansion == 0f && newIsQsExpanded) {
                    newIsQsExpanded = false
                    displayManager.unregisterDisplayListener(displayListener)
                    view.isVisible = true
                }
            }
        }
    }

    private suspend fun listenForShadeTouchability(scope: CoroutineScope): Job {
        return scope.launch {
            shadeInteractor.isShadeTouchable.collect {
                view.isVisible = it
                if (view.isVisible) {
                    brightnessToAlpha()
                    alphaAnimator.cancel()
                    alphaAnimator.start()
                }
            }
        }
    }
}
