/*
 * Copyright (C) 2023 the risingOS Android Project
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
package com.android.systemui.statusbar.phone

import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.PropertyValuesHolder
import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.view.animation.LinearInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator

import com.android.systemui.R

class FaceUnlockImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    enum class State {
        SCANNING, NOT_VERIFIED, SUCCESS
    }

    private var currentState: State = State.SCANNING
    private val scanningAnimation: ObjectAnimator = createScanningAnimation()
    private val successShakeAnimation: AnimatorSet = createShakeAnimation(5f)
    private val failureShakeAnimation: AnimatorSet = createShakeAnimation(10f)

    init {
        updateDrawable(animate = false)
    }

    fun setState(state: State) {
        if (currentState != state) {
            currentState = state
            updateDrawable(animate = true)
            handleAnimationForState(state)
        }
    }

    private fun updateDrawable(animate: Boolean) {
        setImageResource(when (currentState) {
            State.SCANNING -> R.drawable.face_scanning
            State.NOT_VERIFIED -> R.drawable.face_not_verified
            State.SUCCESS -> R.drawable.face_success
        })
    }

    private fun createScanningAnimation(): ObjectAnimator {
        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 1f, 1.2f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 1f, 1.2f, 1f)
        val scanningAnimator = ObjectAnimator.ofPropertyValuesHolder(this, scaleX, scaleY)
        scanningAnimator.duration = 1000
        scanningAnimator.repeatCount = ObjectAnimator.INFINITE
        scanningAnimator.interpolator = LinearInterpolator()
        return scanningAnimator
    }

    private fun createShakeAnimation(amplitude: Float): AnimatorSet {
        val animatorSet = AnimatorSet()
        val translationX = PropertyValuesHolder.ofFloat("translationX", 0f, amplitude, -amplitude, amplitude, -amplitude, 0f)
        val shakeAnimator = ObjectAnimator.ofPropertyValuesHolder(this, translationX)
        shakeAnimator.duration = 500
        shakeAnimator.interpolator = AccelerateDecelerateInterpolator()
        animatorSet.play(shakeAnimator)
        return animatorSet
    }

    private fun handleAnimationForState(state: State) {
        when (state) {
            State.SCANNING -> {
                failureShakeAnimation.cancel()
                successShakeAnimation.cancel()
                scanningAnimation.start()
            }
            State.NOT_VERIFIED -> {
                scanningAnimation.cancel()
                successShakeAnimation.cancel()
                failureShakeAnimation.start()
            }
            State.SUCCESS -> {
                scanningAnimation.cancel()
                failureShakeAnimation.cancel()
                successShakeAnimation.start()
            }
        }
    }
}
