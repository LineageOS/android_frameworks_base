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

package com.android.systemui.brightness.ui.viewmodel

import android.graphics.drawable.Icon
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.display.BrightnessUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.brightness.data.model.LinearBrightness
import com.android.systemui.brightness.data.repository.fakeScreenBrightnessRepository
import com.android.systemui.brightness.domain.interactor.brightnessMirrorShowingInteractor
import com.android.systemui.brightness.domain.interactor.brightnessPolicyEnforcementInteractor
import com.android.systemui.brightness.domain.interactor.screenBrightnessInteractor
import com.android.systemui.brightness.domain.model.GammaBrightness
import com.android.systemui.classifier.domain.interactor.falsingInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.graphics.imageLoader
import com.android.systemui.haptics.msdl.msdlPlayer
import com.android.systemui.haptics.slider.sliderHapticsViewModelFactory
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.res.R
import com.android.systemui.settings.brightness.ui.brightnessWarningToast
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class BrightnessSliderViewModelTest : SysuiTestCase() {

    private val minBrightness = 0f
    private val maxBrightness = 1f

    private val kosmos = testKosmos()

    private var brightnessMirrorInteractorRetrieved = false

    private val underTest by lazy { kosmos.create(true) }

    @Before
    fun setUp() {
        kosmos.fakeScreenBrightnessRepository.setMinMaxBrightness(
            LinearBrightness(minBrightness),
            LinearBrightness(maxBrightness),
        )
    }

    @Test
    fun brightnessChangeInRepository_changeInFlow() =
        with(kosmos) {
            testScope.runTest {
                underTest.activateIn(this)

                var brightness = 0.6f
                fakeScreenBrightnessRepository.setBrightness(LinearBrightness(brightness))
                runCurrent()

                assertThat(underTest.currentBrightness.value)
                    .isEqualTo(
                        BrightnessUtils.convertLinearToGammaFloat(
                            brightness,
                            minBrightness,
                            maxBrightness,
                        )
                    )

                brightness = 0.2f
                fakeScreenBrightnessRepository.setBrightness(LinearBrightness(brightness))
                runCurrent()

                assertThat(underTest.currentBrightness.value)
                    .isEqualTo(
                        BrightnessUtils.convertLinearToGammaFloat(
                            brightness,
                            minBrightness,
                            maxBrightness,
                        )
                    )
            }
        }

    @Test
    fun maxGammaBrightness() {
        assertThat(underTest.maxBrightness)
            .isEqualTo(GammaBrightness(BrightnessUtils.GAMMA_SPACE_MAX))
    }

    @Test
    fun minGammaBrightness() {
        assertThat(underTest.minBrightness)
            .isEqualTo(GammaBrightness(BrightnessUtils.GAMMA_SPACE_MIN))
    }

    @Test
    fun dragging_temporaryBrightnessSet_currentBrightnessDoesntChange() =
        with(kosmos) {
            testScope.runTest {
                underTest.activateIn(this)

                val temporaryBrightness by
                    collectLastValue(fakeScreenBrightnessRepository.temporaryBrightness)

                val newBrightness = underTest.maxBrightness.value / 3
                val expectedTemporaryBrightness =
                    BrightnessUtils.convertGammaToLinearFloat(
                        newBrightness,
                        minBrightness,
                        maxBrightness,
                    )
                val drag = Drag.Dragging(GammaBrightness(newBrightness))

                underTest.onDrag(drag)

                assertThat(temporaryBrightness!!.floatValue)
                    .isWithin(1e-5f)
                    .of(expectedTemporaryBrightness)
                assertThat(underTest.currentBrightness.value).isNotEqualTo(newBrightness)
            }
        }

    @Test
    fun draggingStopped_currentBrightnessChanges() =
        with(kosmos) {
            testScope.runTest {
                underTest.activateIn(this)

                val newBrightness = underTest.maxBrightness.value / 3
                val drag = Drag.Stopped(GammaBrightness(newBrightness))

                underTest.onDrag(drag)
                runCurrent()

                assertThat(underTest.currentBrightness.value).isEqualTo(newBrightness)
            }
        }

    @Test
    fun icon() {
        assertThat(BrightnessSliderViewModel.getIconForPercentage(0f))
            .isEqualTo(R.drawable.ic_brightness_low)
        assertThat(BrightnessSliderViewModel.getIconForPercentage(20f))
            .isEqualTo(R.drawable.ic_brightness_low)
        assertThat(BrightnessSliderViewModel.getIconForPercentage(20.1f))
            .isEqualTo(R.drawable.ic_brightness_medium)
        assertThat(BrightnessSliderViewModel.getIconForPercentage(50f))
            .isEqualTo(R.drawable.ic_brightness_medium)
        assertThat(BrightnessSliderViewModel.getIconForPercentage(79.9f))
            .isEqualTo(R.drawable.ic_brightness_medium)
        assertThat(BrightnessSliderViewModel.getIconForPercentage(80f))
            .isEqualTo(R.drawable.ic_brightness_full)
        assertThat(BrightnessSliderViewModel.getIconForPercentage(100f))
            .isEqualTo(R.drawable.ic_brightness_full)
    }

    @Test
    fun supportedMirror_mirrorShowingWhenDragging() =
        with(kosmos) {
            testScope.runTest {
                underTest.activateIn(this)

                val mirrorInInteractor by
                    collectLastValue(brightnessMirrorShowingInteractor.isShowing)

                underTest.setIsDragging(true)
                assertThat(mirrorInInteractor).isEqualTo(true)
                assertThat(underTest.showMirror).isEqualTo(true)

                underTest.setIsDragging(false)
                assertThat(mirrorInInteractor).isEqualTo(false)
                assertThat(underTest.showMirror).isEqualTo(false)
            }
        }

    @Test
    fun unsupportedMirror_mirrorNeverShowing() =
        with(kosmos) {
            testScope.runTest {
                val mirrorInInteractor by
                    collectLastValue(brightnessMirrorShowingInteractor.isShowing)

                val noMirrorViewModel = create(false)
                noMirrorViewModel.activateIn(this)

                noMirrorViewModel.setIsDragging(true)
                assertThat(mirrorInInteractor).isEqualTo(false)
                assertThat(noMirrorViewModel.showMirror).isEqualTo(false)

                noMirrorViewModel.setIsDragging(false)
                assertThat(mirrorInInteractor).isEqualTo(false)
                assertThat(noMirrorViewModel.showMirror).isEqualTo(false)
            }
        }

    @Test
    fun unsupportedMirror_interactorNeverRetrieved() {
        with(kosmos) {
            runTest {
                val noMirrorViewModel = brightnessSliderViewModelFactory.create(false)
                noMirrorViewModel.activateIn(this)

                noMirrorViewModel.setIsDragging(true)
                noMirrorViewModel.setIsDragging(false)

                assertThat(brightnessMirrorInteractorRetrieved).isFalse()
            }
        }
    }

    @Test
    fun loadImage_timesOutAndReturnsNull_whenLoaderHangs() =
        with(kosmos) {
            testScope.runTest {
                // GIVEN: a mock ImageLoader that simulates a long-running operation
                val hangingImageLoader: ImageLoader = mock {
                    onBlocking {
                        loadDrawable(any<Icon>(), any(), any(), any(), any())
                    } doSuspendableAnswer
                        {
                            delay(10_000)
                            mock<android.graphics.drawable.Drawable>()
                        }
                }

                val underTest = create(imageLoader = hangingImageLoader)

                // WHEN: we load the image
                val loadedIcon = underTest.loadImage(R.drawable.ic_brightness_full, context)

                // THEN: return null due to timeout
                assertThat(loadedIcon).isNull()
            }
        }

    private fun Kosmos.create(
        supportsMirror: Boolean = true,
        imageLoader: ImageLoader = this.imageLoader,
    ): BrightnessSliderViewModel {
        return BrightnessSliderViewModel(
            screenBrightnessInteractor,
            brightnessPolicyEnforcementInteractor,
            sliderHapticsViewModelFactory,
            {
                brightnessMirrorInteractorRetrieved = true
                brightnessMirrorShowingInteractor
            },
            falsingInteractor,
            supportsMirror,
            brightnessWarningToast,
            imageLoader,
            msdlPlayer,
        )
    }
}
