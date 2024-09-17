package com.android.systemui.biometrics

import android.annotation.UiThread
import android.content.Context
import android.content.ContentResolver
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.MathUtils
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
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
class UdfpsHelper (
        private val context: Context,
        private val windowManager: WindowManager,
        private val shadeInteractor: ShadeInteractor,
        private val transitionInteractor: KeyguardTransitionInteractor,
        private val udfpsControllerOverlay: UdfpsControllerOverlay,
        private var view: View = View(context).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
) {
    private var newIsQsExpanded: Boolean = false
    private val brightnessAlphaMap: Map<Int, Int> = context.resources
        .getStringArray(com.android.systemui.res.R.array.config_udfpsDimmingBrightnessAlphaArray)
        .associate {
            val (brightness, alpha) = it.split(",").map { value -> value.trim().toInt() }
            brightness to alpha
        }

    // Linear interpolation is probably not ideal for brightnesToAlpha
    private fun interpolate(value: Int, fromMin: Int, fromMax: Int, toMin: Int, toMax: Int): Int {
        return toMin + (value - fromMin) * (toMax - toMin) / (fromMax - fromMin)
    }

    private fun interpolateAlpha(brightness: Int): Int {
        val lowerEntry = brightnessAlphaMap.entries
            .lastOrNull { it.key <= brightness }
        val upperEntry = brightnessAlphaMap.entries
            .firstOrNull { it.key >= brightness }

        return if (lowerEntry != null && upperEntry != null) {
            val (lowerBrightness, lowerAlpha) = lowerEntry
            val (upperBrightness, upperAlpha) = upperEntry
            interpolate(brightness, lowerBrightness, upperBrightness, lowerAlpha, upperAlpha)
        } else {
            255
        }
    }

    private fun getAlphaForBrightness() {
        val currentBrightness = Settings.System.getInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, 100)

        val currentAlpha = brightnessAlphaMap[currentBrightness]
            ?: interpolateAlpha(currentBrightness)

        Log.e("BrightnessAlpha", "Current Brightness: $currentBrightness")
        Log.e("BrightnessAlpha", "Current Alpha (Int): $currentAlpha")
        Log.e("BrightnessAlpha", "Normalized Alpha (Float): ${currentAlpha / 255.0f}")

        // Set the dim for both the view and the layout
        view.alpha = currentAlpha / 255.0f
        dimLayoutParams.alpha = currentAlpha / 255.0f
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
        getAlphaForBrightness()
        windowManager.addView(view, dimLayoutParams)
    }

    fun removeDimLayer() {
        windowManager.removeView(view)
    }

    init {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                // Always listen for shade expansion regardless of enrollment
                listenForQsExpansion(this)

                if (!udfpsControllerOverlay.isEnrollment) {
                    listenForAnyStateToAodTransition(this)
                    listenForAnyStateToLockscreenTransition(this)
                    listenFromLockscreenTransition(this)
                }
            }
        }

        if (udfpsControllerOverlay.isEnrollment) {
            view.visibility = View.VISIBLE
        }
    }

    private suspend fun listenForAnyStateToAodTransition(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.anyStateToAodTransition.collect { transitionStep ->
                if (transitionStep.transitionState == TransitionState.FINISHED) {
                    view.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun listenForAnyStateToLockscreenTransition(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.anyStateToLockscreenTransition.collect { transitionStep ->
                if (transitionStep.transitionState == TransitionState.STARTED) {
                    view.visibility = View.VISIBLE
                }
            }
        }
    }

    private suspend fun listenFromLockscreenTransition(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.fromLockscreenTransition.collect { transitionStep ->
                if (transitionStep.transitionState == TransitionState.FINISHED) {
                    view.visibility = View.GONE
                }
            }
        }
    }

    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            getAlphaForBrightness()
            windowManager.updateViewLayout(view, dimLayoutParams)
        }
    }

    // Since we are observing SCREEN_BRIGHTNESS and not KEY_BRIGHTNESS,
    // the brightness updates only when the slider is released. So,
    // the dim layer is set to View.GONE when the slider is expected to be utilized.
    private suspend fun listenForQsExpansion(scope: CoroutineScope): Job {
        return scope.launch {
            shadeInteractor.qsExpansion.collect { qsExpansion ->
                if (qsExpansion == 1f && !newIsQsExpanded) {
                    newIsQsExpanded = true
                    context.contentResolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                        false,
                        brightnessObserver
                    )
                    view.visibility = View.GONE
                } else if (qsExpansion == 0f && newIsQsExpanded) {
                    context.contentResolver.unregisterContentObserver(brightnessObserver)
                    newIsQsExpanded = false
                    view.visibility = View.VISIBLE
                }
            }
        }
    }
}
