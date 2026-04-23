/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.controls.ui.controller

import android.os.UserHandle
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import android.testing.TestableLooper
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.integration.SystemUiIntegrationTest
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.domain.pipeline.interactor.mediaCarouselInteractor
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.remedia.data.repository.setHasMedia
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.media.remedia.ui.viewmodel.factory.mediaViewModelFactory
import com.android.systemui.media.remedia.ui.viewmodel.mediaFalsingSystem
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.MediaContainerView
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@SystemUiIntegrationTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@DisableSceneContainer
class KeyguardMediaControllerTest : SysuiTestCase() {

    @Mock private lateinit var mediaHost: MediaHost
    @Mock private lateinit var bypassController: KeyguardBypassController
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var configurationController: ConfigurationController

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val mediaFalsingSystem = kosmos.mediaFalsingSystem
    private val mediaViewModelFactory = kosmos.mediaViewModelFactory
    private val transitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val secureSettings = kosmos.fakeSettings
    private val mediaContainerView: MediaContainerView = MediaContainerView(context, null)
    private val hostView = UniqueObjectHostView(context)
    private lateinit var keyguardMediaController: KeyguardMediaController
    private lateinit var statusBarStateListener: StatusBarStateController.StateListener

    @Before
    fun setup() {
        doAnswer {
                statusBarStateListener = it.arguments[0] as StatusBarStateController.StateListener
                return@doAnswer Unit
            }
            .whenever(statusBarStateController)
            .addCallback(any(StatusBarStateController.StateListener::class.java))
        // default state is positive, media should show up
        whenever(mediaHost.visible).thenReturn(true)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(mediaHost.hostView).thenReturn(hostView)
        hostView.layoutParams = FrameLayout.LayoutParams(100, 100)
        kosmos.fakeSettings.putBoolForUser(
            Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
            true,
            UserHandle.USER_CURRENT,
        )
        keyguardMediaController =
            KeyguardMediaController(
                mediaHost,
                kosmos.applicationCoroutineScope,
                kosmos.testDispatcher,
                bypassController,
                statusBarStateController,
                context,
                configurationController,
                ResourcesSplitShadeStateController(),
                mock<KeyguardMediaControllerLogger>(),
                mock<DumpManager>(),
                mediaViewModelFactory,
                kosmos.mediaCarouselInteractor,
                mediaFalsingSystem,
                kosmos.fakeSettings,
            )
        keyguardMediaController.attachSinglePaneContainer(mediaContainerView)
        keyguardMediaController.useSplitShade = false

        if (MediaControlsInComposeFlag.isEnabled) {
            kosmos.setHasMedia(visible = true, active = true)
        } else {
            verify(mediaHost).expansion = MediaHostState.EXPANDED
            verify(mediaHost)
                .addVisibilityChangeListener(keyguardMediaController::onMediaHostVisibilityChanged)
        }
    }

    @Test
    fun testHiddenWhenHostIsHidden() {
        if (MediaControlsInComposeFlag.isEnabled) {
            kosmos.setHasMedia(visible = false)
        } else {
            whenever(mediaHost.visible).thenReturn(false)
        }

        keyguardMediaController.refreshMediaPosition(TEST_REASON)

        assertThat(mediaContainerView.visibility).isEqualTo(GONE)
    }

    @EnableFlags(Flags.FLAG_MEDIA_CONTROLS_IN_COMPOSE)
    @Test
    fun mediaLockedAndHidden_mediaIsHidden() =
        testScope.runTest {
            kosmos.fakeSettings.putBoolForUser(
                Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
                false,
                UserHandle.USER_CURRENT,
            )
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                this,
            )
            kosmos.setHasMedia(visible = true, active = true)

            assertThat(mediaContainerView.visibility).isEqualTo(GONE)
        }

    @EnableFlags(Flags.FLAG_MEDIA_CONTROLS_IN_COMPOSE)
    @Test
    fun mediaOnLockscreen_mediaIsVisible() =
        testScope.runTest {
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                this,
            )
            kosmos.setHasMedia(visible = true, active = true)

            assertThat(mediaContainerView.visibility).isEqualTo(VISIBLE)
        }

    @Test
    fun testVisibleOnKeyguardOrFullScreenUserSwitcher() {
        testStateVisibility(StatusBarState.SHADE, GONE)
        testStateVisibility(StatusBarState.SHADE_LOCKED, GONE)
        testStateVisibility(StatusBarState.KEYGUARD, VISIBLE)
    }

    private fun testStateVisibility(state: Int, visibility: Int) {
        whenever(statusBarStateController.state).thenReturn(state)
        keyguardMediaController.refreshMediaPosition(TEST_REASON)
        assertThat(mediaContainerView.visibility).isEqualTo(visibility)
    }

    @Test
    fun testActivatesSplitShadeContainerInSplitShadeMode() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)
        keyguardMediaController.useSplitShade = true

        assertThat(splitShadeContainer.visibility).isEqualTo(VISIBLE)
    }

    @Test
    fun testActivatesSinglePaneContainerInSinglePaneMode() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)

        assertThat(splitShadeContainer.visibility).isEqualTo(GONE)
        assertThat(mediaContainerView.visibility).isEqualTo(VISIBLE)
    }

    @Test
    fun testAttachedToSplitShade() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)
        keyguardMediaController.useSplitShade = true

        assertTrue(
            "HostView wasn't attached to the split pane container",
            splitShadeContainer.childCount == 1,
        )
    }

    @Test
    fun testAttachedToSinglePane() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)

        assertTrue(
            "HostView wasn't attached to the single pane container",
            mediaContainerView.childCount == 1,
        )
    }

    @Test
    fun dozing_inSplitShade_mediaIsHidden() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)
        keyguardMediaController.useSplitShade = true

        setDozing()

        assertThat(splitShadeContainer.visibility).isEqualTo(GONE)
    }

    @Test
    fun dozing_inSingleShade_mediaIsVisible() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)
        keyguardMediaController.useSplitShade = false

        setDozing()

        assertThat(mediaContainerView.visibility).isEqualTo(VISIBLE)
    }

    @Test
    fun keyguardShowing_notAllowedOnLockscreen_viewIsHidden() {
        kosmos.testScope.runTest {
            val settingsJob =
                keyguardMediaController.listenForLockscreenSettingChanges(
                    kosmos.applicationCoroutineScope
                )
            secureSettings.putBool(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, false)
            runCurrent()

            assertThat(mediaContainerView.visibility).isEqualTo(GONE)

            settingsJob.cancel()
        }
    }

    @Test
    fun keyguardShowing_allowedOnLockscreen_viewIsVisible() {
        kosmos.testScope.runTest {
            val settingsJob =
                keyguardMediaController.listenForLockscreenSettingChanges(
                    kosmos.applicationCoroutineScope
                )
            secureSettings.putBool(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, true)
            runCurrent()

            assertThat(mediaContainerView.visibility).isEqualTo(VISIBLE)

            settingsJob.cancel()
        }
    }

    private fun setDozing() {
        whenever(statusBarStateController.isDozing).thenReturn(true)
        statusBarStateListener.onDozingChanged(true)
    }

    private companion object {
        private const val TEST_REASON = "test reason"
    }
}
