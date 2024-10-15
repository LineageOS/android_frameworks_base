/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_BP
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_OTHER
import android.hardware.biometrics.BiometricRequestConstants.RequestReason
import android.hardware.fingerprint.FingerprintSensorProperties.TYPE_UDFPS_OPTICAL
import android.hardware.fingerprint.FingerprintSensorProperties.TYPE_UDFPS_ULTRASONIC
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback
import android.testing.TestableLooper.RunWithLooper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.biometrics.ui.view.UdfpsTouchOverlay
import com.android.systemui.biometrics.ui.viewmodel.DefaultUdfpsTouchOverlayViewModel
import com.android.systemui.biometrics.ui.viewmodel.DeviceEntryUdfpsTouchOverlayViewModel
import com.android.systemui.biometrics.ui.viewmodel.PromptUdfpsTouchOverlayViewModel
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

private const val REQUEST_ID = 2L

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class UdfpsControllerOverlayTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    @JvmField @Rule var rule = MockitoJUnit.rule()

    @Mock private lateinit var inflater: LayoutInflater
    @Mock private lateinit var windowManager: WindowManager
    @Mock private lateinit var accessibilityManager: AccessibilityManager
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var udfpsDisplayMode: UdfpsDisplayModeProvider
    @Mock private lateinit var controllerCallback: IUdfpsOverlayControllerCallback
    @Mock
    private lateinit var deviceEntryUdfpsTouchOverlayViewModel:
        DeviceEntryUdfpsTouchOverlayViewModel
    @Mock private lateinit var defaultUdfpsTouchOverlayViewModel: DefaultUdfpsTouchOverlayViewModel
    @Mock private lateinit var promptUdfpsTouchOverlayViewModel: PromptUdfpsTouchOverlayViewModel
    @Mock private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    @Mock private lateinit var shadeInteractor: ShadeInteractor
    @Mock private lateinit var udfpsOverlayInteractor: UdfpsOverlayInteractor
    private lateinit var powerRepository: FakePowerRepository
    private lateinit var powerInteractor: PowerInteractor
    private lateinit var testScope: TestScope
    private lateinit var sceneInteractor: SceneInteractor

    private val onTouch = { _: View, _: MotionEvent -> true }
    private var overlayParams: UdfpsOverlayParams = UdfpsOverlayParams()
    private var ultrasonicOverlayParams: UdfpsOverlayParams =
        UdfpsOverlayParams(sensorType = TYPE_UDFPS_ULTRASONIC)
    private var opticalOverlayParams: UdfpsOverlayParams =
        UdfpsOverlayParams(sensorType = TYPE_UDFPS_OPTICAL)
    private lateinit var controllerOverlay: UdfpsControllerOverlay

    @Before
    fun setup() {
        testScope = kosmos.testScope
        powerRepository = kosmos.fakePowerRepository
        powerInteractor = kosmos.powerInteractor
        keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
        keyguardTransitionInteractor = kosmos.keyguardTransitionInteractor
        sceneInteractor = kosmos.sceneInteractor
        whenever(inflater.inflate(R.layout.udfps_touch_overlay, null, false))
            .thenReturn(mock(UdfpsTouchOverlay::class.java))
    }

    private suspend fun withReasonSuspend(@RequestReason reason: Int, block: suspend () -> Unit) {
        withReason(reason)
        block()
    }

    private fun withReason(@RequestReason reason: Int, block: () -> Unit = {}) {
        controllerOverlay =
            UdfpsControllerOverlay(
                context,
                inflater,
                windowManager,
                accessibilityManager,
                keyguardUpdateMonitor,
                keyguardStateController,
                udfpsDisplayMode,
                REQUEST_ID,
                reason,
                controllerCallback,
                onTouch,
                keyguardTransitionInteractor,
                { deviceEntryUdfpsTouchOverlayViewModel },
                { defaultUdfpsTouchOverlayViewModel },
                { promptUdfpsTouchOverlayViewModel },
                shadeInteractor,
                udfpsOverlayInteractor,
                powerInteractor,
                testScope,
                { sceneInteractor },
            )
        block()
    }

    @Test
    fun showUdfpsOverlay_whileGoingToSleep() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.GONE,
                    testScope = this,
                )
                powerRepository.updateWakefulness(
                    rawState = WakefulnessState.STARTING_TO_SLEEP,
                    lastWakeReason = WakeSleepReason.POWER_BUTTON,
                    lastSleepReason = WakeSleepReason.OTHER,
                )
                runCurrent()

                // WHEN a request comes to show the view
                controllerOverlay.show(overlayParams)
                runCurrent()

                // THEN the view does not get added immediately
                verify(windowManager, never()).addView(any(), any())

                // we hide to end the job that listens for the finishedGoingToSleep signal
                controllerOverlay.hide()
            }
        }

    @Test
    fun showUdfpsOverlay_whileAsleep() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.GONE,
                    testScope = this,
                )
                powerRepository.updateWakefulness(
                    rawState = WakefulnessState.ASLEEP,
                    lastWakeReason = WakeSleepReason.POWER_BUTTON,
                    lastSleepReason = WakeSleepReason.OTHER,
                )
                runCurrent()

                // WHEN a request comes to show the view
                controllerOverlay.show(overlayParams)
                runCurrent()

                // THEN view isn't added yet
                verify(windowManager, never()).addView(any(), any())

                // we hide to end the job that listens for the finishedGoingToSleep signal
                controllerOverlay.hide()
            }
        }

    @Test
    fun showUdfpsOverlay_whileAwake() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.GONE,
                    testScope = this,
                )
                powerRepository.updateWakefulness(
                    rawState = WakefulnessState.AWAKE,
                    lastWakeReason = WakeSleepReason.POWER_BUTTON,
                    lastSleepReason = WakeSleepReason.OTHER,
                )
                runCurrent()

                // WHEN a request comes to show the view
                controllerOverlay.show(overlayParams)
                runCurrent()

                // THEN the view gets added immediately
                try {
                    verify(windowManager).addView(any(), any())
                } finally {
                    // we hide to end the job that listens for the finishedGoingToSleep signal
                    controllerOverlay.hide()
                }
            }
        }

    @Test
    @DisableSceneContainer
    fun showUdfpsOverlay_afterFinishedTransitioningToDreaming() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.GONE,
                    testScope = this,
                )
                powerRepository.updateWakefulness(
                    rawState = WakefulnessState.STARTING_TO_SLEEP,
                    lastWakeReason = WakeSleepReason.POWER_BUTTON,
                    lastSleepReason = WakeSleepReason.OTHER,
                )
                runCurrent()

                // WHEN a request comes to show the view
                controllerOverlay.show(overlayParams)
                runCurrent()

                // THEN the view does not get added immediately
                verify(windowManager, never()).addView(any(), any())

                // When the device finishes transitions to DREAMING
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GONE,
                    to = KeyguardState.DREAMING,
                    testScope = this,
                )
                runCurrent()

                // THEN the view gets added
                try {
                    verify(windowManager).addView(eq(controllerOverlay.getTouchOverlay()), any())
                } finally {
                    // we hide to end the job that listens for the finishedGoingToSleep signal
                    controllerOverlay.hide()
                }
            }
        }

    @Test
    fun showUdfpsOverlay_afterFinishedTransitioningToAOD() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.GONE,
                    testScope = this,
                )
                powerRepository.updateWakefulness(
                    rawState = WakefulnessState.STARTING_TO_SLEEP,
                    lastWakeReason = WakeSleepReason.POWER_BUTTON,
                    lastSleepReason = WakeSleepReason.OTHER,
                )
                runCurrent()

                // WHEN a request comes to show the view
                controllerOverlay.show(overlayParams)
                runCurrent()

                // THEN the view does not get added immediately
                verify(windowManager, never()).addView(any(), any())

                // WHEN the device finishes transitioning to AOD
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    testScope = this,
                )
                runCurrent()

                // THEN the view gets added
                try {
                    verify(windowManager).addView(any(), any())
                } finally {
                    // we hide to end the job that listens for the finishedGoingToSleep signal
                    controllerOverlay.hide()
                }
            }
        }

    @Test
    fun neverRemoveViewThatHasNotBeenAdded() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                controllerOverlay.show(overlayParams)
                val view = controllerOverlay.getTouchOverlay()
                view?.let {
                    // parent is null, signalling that the view was never added
                    whenever(view.parent).thenReturn(null)
                }
                verify(windowManager, never()).removeView(eq(view))
            }
        }

    @Test
    fun canNotHide() = withReason(REASON_AUTH_BP) { assertThat(controllerOverlay.hide()).isFalse() }

    @Test
    fun cancels() =
        withReason(REASON_AUTH_BP) {
            controllerOverlay.cancel()
            verify(controllerCallback).onUserCanceled()
        }

    @Test
    fun matchesRequestIds() =
        withReason(REASON_AUTH_BP) {
            assertThat(controllerOverlay.matchesRequestId(REQUEST_ID)).isTrue()
            assertThat(controllerOverlay.matchesRequestId(REQUEST_ID + 1)).isFalse()
        }

    @Test
    fun addViewPending_layoutIsNotUpdated() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                // GIVEN going to sleep
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.GONE,
                    testScope = this,
                )
                powerRepository.updateWakefulness(
                    rawState = WakefulnessState.STARTING_TO_SLEEP,
                    lastWakeReason = WakeSleepReason.POWER_BUTTON,
                    lastSleepReason = WakeSleepReason.OTHER,
                )
                runCurrent()

                // WHEN a request comes to show the view
                controllerOverlay.show(overlayParams)
                runCurrent()

                // THEN the view does not get added immediately
                verify(windowManager, never()).addView(any(), any())

                // WHEN updateOverlayParams gets called when the view is pending to be added
                controllerOverlay.updateOverlayParams(overlayParams)

                // THEN the view layout is never updated
                verify(windowManager, never()).updateViewLayout(any(), any())

                // CLEANUP we hide to end the job that listens for the finishedGoingToSleep signal
                controllerOverlay.hide()
            }
        }

    @Test
    fun setHandleTouchesOutsideOfUdfpsViewLifecycle_ultrasonic() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                // WHEN a request comes to show ultrasonic UDFPS
                controllerOverlay.show(ultrasonicOverlayParams)
                runCurrent()

                // THEN handle touches outside of the overlayTouchViewLifecycle
                verify(udfpsOverlayInteractor).setTouchHandlingViewModel(any())
            }
            reset(udfpsOverlayInteractor)
            withReasonSuspend(REASON_AUTH_BP) {
                // WHEN a request comes to show ultrasonic UDFPS
                controllerOverlay.show(ultrasonicOverlayParams)
                runCurrent()

                // THEN handle touches outside of the overlayTouchViewLifecycle
                verify(udfpsOverlayInteractor).setTouchHandlingViewModel(any())
            }
            reset(udfpsOverlayInteractor)
            withReasonSuspend(REASON_AUTH_OTHER) {
                // WHEN a request comes to show ultrasonic UDFPS
                controllerOverlay.show(ultrasonicOverlayParams)
                runCurrent()

                // THEN handle touches outside of the overlayTouchViewLifecycle
                verify(udfpsOverlayInteractor).setTouchHandlingViewModel(any())
            }
        }

    @Test
    fun setHandleTouchesOutsideOfUdfpsViewLifecycle_optical() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                // WHEN a request comes to show optical UDFPS
                controllerOverlay.show(opticalOverlayParams)
                runCurrent()

                // THEN handle touches outside of the overlayTouchViewLifecycle
                verify(udfpsOverlayInteractor, never()).setTouchHandlingViewModel(any())
            }
            withReasonSuspend(REASON_AUTH_BP) {
                // WHEN a request comes to show optical UDFPS
                controllerOverlay.show(opticalOverlayParams)
                runCurrent()

                // THEN handle touches outside of the overlayTouchViewLifecycle
                verify(udfpsOverlayInteractor, never()).setTouchHandlingViewModel(any())
            }
            withReasonSuspend(REASON_AUTH_OTHER) {
                // WHEN a request comes to show optical UDFPS
                controllerOverlay.show(opticalOverlayParams)
                runCurrent()

                // THEN handle touches outside of the overlayTouchViewLifecycle
                verify(udfpsOverlayInteractor, never()).setTouchHandlingViewModel(any())
            }
        }
}
