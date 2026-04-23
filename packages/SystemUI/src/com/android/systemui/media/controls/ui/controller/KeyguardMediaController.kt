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

import android.content.Context
import android.content.res.Configuration
import android.os.UserHandle
import android.provider.Settings
import android.util.MathUtils
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.Dumpable
import com.android.systemui.classifier.Classifier
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.initOnBackPressedDispatcherOwner
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.dagger.MediaModule
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.media.remedia.ui.compose.Media
import com.android.systemui.media.remedia.ui.compose.MediaPresentationStyle
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaFalsingSystem
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.MediaContainerView
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.util.asIndenting
import com.android.systemui.util.boundsOnScreen
import com.android.systemui.util.println
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controls the media notifications on the lock screen, handles its visibility and placement -
 * switches media player positioning between split pane container vs single pane container
 */
@SysUISingleton
class KeyguardMediaController
@Inject
constructor(
    @param:Named(MediaModule.KEYGUARD) private val mediaHost: MediaHost,
    @Application private val applicationScope: CoroutineScope,
    @param:Background private val backgroundDispatcher: CoroutineDispatcher,
    private val bypassController: KeyguardBypassController,
    private val statusBarStateController: SysuiStatusBarStateController,
    @ShadeDisplayAware private val context: Context,
    @ShadeDisplayAware private val configurationController: ConfigurationController,
    private val splitShadeStateController: SplitShadeStateController,
    private val logger: KeyguardMediaControllerLogger,
    dumpManager: DumpManager,
    private val mediaViewModelFactory: MediaViewModel.Factory,
    private val mediaCarouselInteractor: MediaCarouselInteractor,
    private val falsingSystem: MediaFalsingSystem,
    private val secureSettings: SecureSettings,
) : Dumpable {
    private var lastUsedStatusBarState = -1

    /** Is the media player visible? */
    var visible by mutableStateOf(false)
        private set

    private var distanceForFullShadeTransition = 0
    private var fullShadeTransitionProgress: Float by mutableStateOf(0.0f)

    private val composeView =
        ComposeView(context).apply {
            repeatWhenAttached {
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.CREATED) {
                        initOnBackPressedDispatcherOwner(this@repeatWhenAttached.lifecycle)
                        setComposeContent(this@apply)
                    }
                }
            }
        }

    private var allowMediaPlayerOnLockScreen = true
    private val _isMediaVisibleOnLockscreen = mutableStateOf(false)
    private var isMediaVisibleOnLockscreen: Boolean
        get() = _isMediaVisibleOnLockscreen.value
        set(value) {
            _isMediaVisibleOnLockscreen.value = value
            onMediaHostVisibilityChanged(value)
        }

    init {
        dumpManager.registerDumpable(this)
        setUpListenersAndCallbacks()
    }

    private fun setUpListenersAndCallbacks() {
        if (SceneContainerFlag.isEnabled) return

        if (!MediaControlsInComposeFlag.isEnabled) {
            // First let's set the desired state that we want for this host
            mediaHost.expansion = MediaHostState.EXPANDED
            mediaHost.showsOnlyActiveMedia = true
            mediaHost.falsingProtectionNeeded = true

            // Let's now initialize this view, which also creates the host view for us.
            mediaHost.init(MediaHierarchyManager.LOCATION_LOCKSCREEN)
            listenForLockscreenSettingChanges(applicationScope)
        } else {
            applicationScope.launch {
                combine(
                        mediaCarouselInteractor.allowMediaOnLockscreen,
                        mediaCarouselInteractor.hasActiveMedia,
                        mediaCarouselInteractor.isOnLockscreen,
                    ) { allowMediaOnLockscreen, activeMedia, isOnLockscreen ->
                        if (allowMediaOnLockscreen && isOnLockscreen) {
                            activeMedia
                        } else {
                            false
                        }
                    }
                    .collect { isMediaVisibleOnLockscreen = it }
            }
        }
        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onStateChanged(newState: Int) {
                    refreshMediaPosition(reason = "StatusBarState.onStateChanged")
                }

                override fun onDozingChanged(isDozing: Boolean) {
                    refreshMediaPosition(reason = "StatusBarState.onDozingChanged")
                }
            }
        )
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResources()
                }
            }
        )
        updateResources()
    }

    private fun setComposeContent(composeView: ComposeView) {
        composeView.setContent {
            val transitionAlpha by remember {
                derivedStateOf {
                    // This maps the 0.0 -> 0.25 progress to 1.0 -> 0.0 Alpha
                    (1f - (fullShadeTransitionProgress / 0.25f)).coerceIn(0f, 1f)
                }
            }
            Media(
                viewModelFactory = mediaViewModelFactory,
                presentationStyle = MediaPresentationStyle.Default,
                behavior =
                    MediaUiBehavior(
                        carouselVisibility = MediaCarouselVisibility.WhenAnyCardIsActive,
                        isCarouselScrollFalseTouch = {
                            falsingSystem.isFalseTouch(Classifier.MEDIA_CAROUSEL_SWIPE)
                        },
                    ),
                onDismissed = { mediaCarouselInteractor.onSwipeToDismiss() },
                modifier = Modifier.graphicsLayer { alpha = transitionAlpha },
                visible = { visible },
                location = Media.Location.LOCKSCREEN,
            )
        }
    }

    @VisibleForTesting
    fun listenForLockscreenSettingChanges(scope: CoroutineScope): Job {
        return scope.launch {
            secureSettings
                .observerFlow(UserHandle.USER_ALL, Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN)
                // query to get initial value
                .onStart { emit(Unit) }
                .map { getMediaLockScreenSetting() }
                .distinctUntilChanged()
                .flowOn(backgroundDispatcher)
                .collectLatest {
                    allowMediaPlayerOnLockScreen = it
                    onMediaHostVisibilityChanged(mediaHost.visible)
                }
        }
    }

    private suspend fun getMediaLockScreenSetting(): Boolean {
        return withContext(backgroundDispatcher) {
            secureSettings.getBoolForUser(
                Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
                true,
                UserHandle.USER_CURRENT,
            )
        }
    }

    private fun updateResources() {
        distanceForFullShadeTransition =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_media_transition_distance
            )
        useSplitShade = splitShadeStateController.shouldUseSplitNotificationShade(context.resources)
    }

    @VisibleForTesting
    var useSplitShade = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            reattachHostView()
            refreshMediaPosition(reason = "useSplitShade changed")
        }

    var visibilityChangedListener: ((Boolean) -> Unit)? = null

    /** single pane media container placed at the top of the notifications list */
    var singlePaneContainer: MediaContainerView? = null
        private set

    private var splitShadeContainer: ViewGroup? = null

    /**
     * Attaches media container in single pane mode, situated at the top of the notifications list
     */
    fun attachSinglePaneContainer(mediaView: MediaContainerView?) {
        SceneContainerFlag.assertInLegacyMode()
        if (MediaControlsInComposeFlag.isEnabled) {
            singlePaneContainer = mediaView
            reattachHostView()
            onMediaHostVisibilityChanged(isMediaVisibleOnLockscreen)
        } else {
            val needsListener = singlePaneContainer == null
            singlePaneContainer = mediaView
            if (needsListener) {
                // On reinflation we don't want to add another listener
                mediaHost.addVisibilityChangeListener(this::onMediaHostVisibilityChanged)
            }
            reattachHostView()
            onMediaHostVisibilityChanged(mediaHost.visible)
        }

        singlePaneContainer?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Called whenever the media hosts visibility changes */
    @VisibleForTesting
    fun onMediaHostVisibilityChanged(visible: Boolean) {
        refreshMediaPosition(reason = "onMediaHostVisibilityChanged")

        if (visible) {
            if (useSplitShade) {
                return
            }
            if (MediaControlsInComposeFlag.isEnabled) {
                composeView.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                }
            } else {
                mediaHost.hostView.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        }
    }

    /** Attaches media container in split shade mode, situated to the left of notifications */
    fun attachSplitShadeContainer(container: ViewGroup) {
        SceneContainerFlag.assertInLegacyMode()
        splitShadeContainer = container
        reattachHostView()
        refreshMediaPosition(reason = "attachSplitShadeContainer")
    }

    private fun reattachHostView() {
        val inactiveContainer: ViewGroup?
        val activeContainer: ViewGroup?
        if (useSplitShade) {
            activeContainer = splitShadeContainer
            inactiveContainer = singlePaneContainer
        } else {
            inactiveContainer = splitShadeContainer
            activeContainer = singlePaneContainer
        }
        if (inactiveContainer?.childCount == 1) {
            inactiveContainer.removeAllViews()
        }
        if (activeContainer?.childCount == 0) {
            if (MediaControlsInComposeFlag.isEnabled) {
                composeView.parent?.let { (it as? ViewGroup)?.removeView(composeView) }
                activeContainer.addView(composeView)
                composeView.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                }
            } else {
                // Detach the hostView from its parent view if exists
                mediaHost.hostView.parent?.let {
                    (it as? ViewGroup)?.removeView(mediaHost.hostView)
                }
                activeContainer.addView(mediaHost.hostView)
            }
        }
    }

    fun isWithinMediaViewBounds(x: Int, y: Int): Boolean {
        if (MediaControlsInComposeFlag.isEnabled) {
            return composeView.boundsOnScreen.contains(x, y)
        }
        return mediaHost.visible && mediaHost.hostView.boundsOnScreen.contains(x, y)
    }

    fun refreshMediaPosition(reason: String) {
        SceneContainerFlag.assertInLegacyMode()
        val currentState = statusBarStateController.state

        val keyguardOrUserSwitcher = (currentState == StatusBarState.KEYGUARD)
        // mediaHost.visible required for proper animations handling
        val isMediaHostVisible =
            if (MediaControlsInComposeFlag.isEnabled) {
                isMediaVisibleOnLockscreen
            } else {
                mediaHost.visible && allowMediaPlayerOnLockScreen
            }
        val isBypassNotEnabled = !bypassController.bypassEnabled
        val useSplitShade = useSplitShade
        val shouldBeVisibleForSplitShade = shouldBeVisibleForSplitShade()
        visible =
            isMediaHostVisible &&
                isBypassNotEnabled &&
                keyguardOrUserSwitcher &&
                shouldBeVisibleForSplitShade
        logger.logRefreshMediaPosition(
            reason = reason,
            visible = visible,
            useSplitShade = useSplitShade,
            currentState = currentState,
            keyguardOrUserSwitcher = keyguardOrUserSwitcher,
            mediaHostVisible = isMediaHostVisible,
            bypassNotEnabled = isBypassNotEnabled,
            shouldBeVisibleForSplitShade = shouldBeVisibleForSplitShade,
        )
        val currActiveContainer = activeContainer

        logger.logActiveMediaContainer("before refreshMediaPosition", currActiveContainer)
        if (visible) {
            showMediaPlayer()
        } else {
            hideMediaPlayer()
        }
        logger.logActiveMediaContainer("after refreshMediaPosition", currActiveContainer)

        lastUsedStatusBarState = currentState
    }

    fun setTransitionToFullShadeAmount(transitionAmount: Float) {
        val progress = MathUtils.saturate(transitionAmount / distanceForFullShadeTransition)
        if (progress <= 1.0f || progress >= 0.0f) {
            fullShadeTransitionProgress = progress
        }
    }

    private fun shouldBeVisibleForSplitShade(): Boolean {
        if (!useSplitShade) {
            return true
        }
        // We have to explicitly hide media for split shade when on AOD, as it is a child view of
        // keyguard status view, and nothing hides keyguard status view on AOD.
        // When using the double-line clock, it is not an issue, as media gets implicitly hidden
        // by the clock. This is not the case for single-line clock though.
        // For single shade, we don't need to do it, because media is a child of NSSL, which already
        // gets hidden on AOD.
        return !statusBarStateController.isDozing
    }

    private fun showMediaPlayer() {
        if (useSplitShade) {
            setVisibility(splitShadeContainer, View.VISIBLE)
            setVisibility(singlePaneContainer, View.GONE)
        } else {
            setVisibility(singlePaneContainer, View.VISIBLE)
            setVisibility(splitShadeContainer, View.GONE)
        }
    }

    private fun hideMediaPlayer() {
        // always hide splitShadeContainer as it's initially visible and may influence layout
        setVisibility(splitShadeContainer, View.GONE)
        setVisibility(singlePaneContainer, View.GONE)
    }

    private fun setVisibility(view: ViewGroup?, newVisibility: Int) {
        val currentMediaContainer = view ?: return

        val isVisible = newVisibility == View.VISIBLE

        if (currentMediaContainer is MediaContainerView) {
            val previousVisibility = currentMediaContainer.visibility

            currentMediaContainer.setKeyguardVisibility(isVisible)
            if (previousVisibility != newVisibility) {
                visibilityChangedListener?.invoke(isVisible)
            }
        } else {
            currentMediaContainer.visibility = newVisibility
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            println("KeyguardMediaController")
            withIncreasedIndent {
                println("Self", this@KeyguardMediaController)
                println("visible", visible)
                println("useSplitShade", useSplitShade)
                println("bypassController.bypassEnabled", bypassController.bypassEnabled)
                println("singlePaneContainer", singlePaneContainer)
                println("splitShadeContainer", splitShadeContainer)
                if (lastUsedStatusBarState != -1) {
                    println(
                        "lastUsedStatusBarState",
                        StatusBarState.toString(lastUsedStatusBarState),
                    )
                }
                println(
                    "statusBarStateController.state",
                    StatusBarState.toString(statusBarStateController.state),
                )
            }
        }
    }

    // This field is only used to log current active container.
    private val activeContainer: ViewGroup?
        get() = if (useSplitShade) splitShadeContainer else singlePaneContainer
}
