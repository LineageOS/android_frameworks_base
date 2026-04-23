/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.WorkerThread
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import android.provider.Settings.ACTION_MEDIA_CONTROLS_SETTINGS
import android.util.Log
import android.util.MathUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.traceSection
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Dumpable
import com.android.systemui.Flags
import com.android.systemui.Flags.enableSuggestedDeviceUi
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.ui.controller.MediaPlayerData.visiblePlayerKeys
import com.android.systemui.media.controls.ui.view.MediaCarouselScrollHandler
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.controls.ui.view.MediaScrollView
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.PageIndicator
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.securelockdevice.domain.interactor.SecureLockDeviceInteractor
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.quickactions.media.domain.interactor.MediaControlChipInteractor
import com.android.systemui.util.Utils
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.animation.requiresRemeasuring
import com.android.systemui.util.boundsOnScreen
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.time.SystemClock
import dagger.Lazy
import java.io.PrintWriter
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MediaCarouselController"
private val settingsIntent = Intent().setAction(ACTION_MEDIA_CONTROLS_SETTINGS)

/**
 * Class that is responsible for keeping the view carousel up to date. This also handles changes in
 * state and applies them to the media carousel like the expansion.
 */
@SysUISingleton
class MediaCarouselController
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @ShadeDisplayAware private val context: Context,
    private val mediaControlPanelFactory: Provider<MediaControlPanel>,
    private val mediaReorderController: MediaReorderController,
    private val mediaHostStatesManager: MediaHostStatesManager,
    private val activityStarter: ActivityStarter,
    private val systemClock: SystemClock,
    @Main private val uiExecutor: DelayableExecutor,
    @Background private val bgExecutor: Executor,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val mediaManager: MediaDataManager,
    @ShadeDisplayAware private val configurationController: ConfigurationController,
    falsingManager: FalsingManager,
    dumpManager: DumpManager,
    private val logger: MediaUiEventLogger,
    private val debugLogger: MediaCarouselControllerLogger,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val globalSettings: GlobalSettings,
    private val secureSettings: SecureSettings,
    private val mediaControlChipInteractor: MediaControlChipInteractor,
    private val secureLockDeviceInteractor: Lazy<SecureLockDeviceInteractor>,
    @Background private val bgScope: CoroutineScope,
) : Dumpable {
    /** The current width of the carousel */
    private var currentCarouselWidth: Int = 0

    /** The current height of the carousel */
    private var currentCarouselHeight: Int = 0

    /** Are we currently showing only active players */
    private var currentlyShowingOnlyActive: Boolean = false

    /** Is the player currently visible (at the end of the transformation */
    private var playersVisible: Boolean = false

    /** Are we currently disabling scrolling, only allowing the first media session to show */
    private var currentlyDisableScrolling: Boolean = false

    /** A key for the last player card that is completely visible */
    private var lastFullyVisiblePlayerKey: String? = null

    /**
     * The desired location where we'll be at the end of the transformation. Usually this matches
     * the end location, except when we're still waiting on a state update call.
     */
    @MediaLocation private var desiredLocation: Int = MediaHierarchyManager.LOCATION_UNKNOWN

    /**
     * The ending location of the view where it ends when all animations and transitions have
     * finished
     */
    @MediaLocation
    @VisibleForTesting
    var currentEndLocation: Int = MediaHierarchyManager.LOCATION_UNKNOWN

    /**
     * The ending location of the view where it ends when all animations and transitions have
     * finished
     */
    @MediaLocation private var currentStartLocation: Int = MediaHierarchyManager.LOCATION_UNKNOWN

    /** The progress of the transition or 1.0 if there is no transition happening */
    private var currentTransitionProgress: Float = 1.0f

    /** The measured width of the carousel */
    private var carouselMeasureWidth: Int = 0

    /** The measured height of the carousel */
    private var carouselMeasureHeight: Int = 0
    private var desiredHostState: MediaHostState? = null
    @VisibleForTesting var mediaCarousel: MediaScrollView
    val mediaCarouselScrollHandler: MediaCarouselScrollHandler
    val mediaFrame: ViewGroup

    @VisibleForTesting
    lateinit var settingsButton: View
        private set

    private val mediaContent: ViewGroup
    @VisibleForTesting var pageIndicator: PageIndicator
    private var needsReordering: Boolean = false
    private var isUserInitiatedRemovalQueued: Boolean = false
    private var keysNeedRemoval = mutableSetOf<String>()
    private var isRtl: Boolean = false
        set(value) {
            if (value != field) {
                field = value
                mediaFrame.layoutDirection =
                    if (value) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                mediaCarouselScrollHandler.scrollToStart()
            }
        }

    private var carouselLocale: Locale? = null

    private val animationScaleObserver: ContentObserver =
        object : ContentObserver(uiExecutor, 0) {
            override fun onChange(selfChange: Boolean) {
                MediaPlayerData.players().forEach { it.updateAnimatorDurationScale() }
            }
        }

    private var allowMediaPlayerOnLockScreen = false

    /** Whether the media card currently has the "expanded" layout */
    @VisibleForTesting
    var currentlyExpanded = true
        set(value) {
            if (field != value) {
                field = value
                updateSeekbarListening(mediaCarouselScrollHandler.visibleToUser)
            }
        }

    companion object {
        private val TRANSFORM_BEZIER = PathInterpolator(0.68F, 0F, 0F, 1F)

        fun calculateAlpha(
            squishinessFraction: Float,
            startPosition: Float,
            endPosition: Float,
        ): Float {
            val transformFraction =
                MathUtils.constrain(
                    (squishinessFraction - startPosition) / (endPosition - startPosition),
                    0F,
                    1F,
                )
            return TRANSFORM_BEZIER.getInterpolation(transformFraction)
        }
    }

    private val configListener =
        object : ConfigurationController.ConfigurationListener {

            override fun onDensityOrFontScaleChanged() {
                // System font changes should only happen when UMO is offscreen or a flicker may
                // occur
                updatePlayers(recreateMedia = true)
                inflateSettingsButton()
            }

            override fun onThemeChanged() {
                updatePlayers(recreateMedia = false)
                inflateSettingsButton()
            }

            override fun onConfigChanged(newConfig: Configuration?) {
                if (newConfig == null) return
                isRtl = newConfig.layoutDirection == View.LAYOUT_DIRECTION_RTL
            }

            override fun onUiModeChanged() {
                updatePlayers(recreateMedia = false)
                inflateSettingsButton()
            }

            override fun onLocaleListChanged() {
                // Update players only if system primary language changes.
                if (carouselLocale != context.resources.configuration.locales.get(0)) {
                    carouselLocale = context.resources.configuration.locales.get(0)
                    updatePlayers(recreateMedia = true)
                    inflateSettingsButton()
                }
            }
        }

    private val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onStrongAuthStateChanged(userId: Int) {
                if (
                    keyguardUpdateMonitor.isUserInLockdown(userId) ||
                        secureLockDeviceInteractor.get().isSecureLockDeviceEnabled.value
                ) {
                    debugLogger.logCarouselHidden()
                    hideMediaCarousel()
                } else if (keyguardUpdateMonitor.isUserUnlocked(userId)) {
                    debugLogger.logCarouselVisible()
                    showMediaCarousel()
                }
            }
        }

    /**
     * Update MediaCarouselScrollHandler.visibleToUser to reflect media card container visibility.
     * It will be called when the container is out of view.
     */
    lateinit var updateUserVisibility: () -> Unit
    var updateHostVisibility: () -> Unit = {}

    private val isReorderingAllowed: Boolean
        get() = mediaReorderController.isReorderingAllowed() && !isOnLockscreen()

    private val isOnGone =
        keyguardTransitionInteractor
            .isFinishedIn(Scenes.Gone, GONE)
            .stateIn(
                if (SceneContainerFlag.isEnabled) bgScope else applicationScope,
                SharingStarted.Eagerly,
                true,
            )

    private val isGoingToDozing =
        keyguardTransitionInteractor
            .isInTransition(Edge.create(to = DOZING))
            .stateIn(
                if (SceneContainerFlag.isEnabled) bgScope else applicationScope,
                SharingStarted.Eagerly,
                true,
            )

    private var mediaFrameHeight: Int = 0
    private var mediaFrameWidth: Int = 0

    init {
        // TODO(b/397989775): avoid unnecessary setup with media_controls_in_compose enabled
        dumpManager.registerNormalDumpable(TAG, this)
        mediaFrame = inflateMediaCarousel()
        mediaCarousel = mediaFrame.requireViewById(R.id.media_carousel_scroller)
        pageIndicator = mediaFrame.requireViewById(R.id.media_page_indicator)
        mediaCarouselScrollHandler =
            MediaCarouselScrollHandler(
                mediaCarousel,
                pageIndicator,
                uiExecutor,
                this::onSwipeToDismiss,
                this::updatePageIndicatorLocation,
                this::updateSeekbarListening,
                this::closeGuts,
                falsingManager,
                this::onVisibleCardChanged,
                logger,
            )
        carouselLocale = context.resources.configuration.locales.get(0)
        isRtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        inflateSettingsButton()
        mediaContent = mediaCarousel.requireViewById(R.id.media_carousel)
        setUpListenersAndCallbacks()
    }

    private fun reorderingCallback() {
        if (needsReordering) {
            needsReordering = false
            reorderAllPlayers()
            updatePageArrows()
        }

        keysNeedRemoval.forEach { removePlayer(it, userInitiated = isUserInitiatedRemovalQueued) }
        if (keysNeedRemoval.size > 0) {
            // Carousel visibility may need to be updated after late removals
            updateHostVisibility()
        }
        keysNeedRemoval.clear()
        isUserInitiatedRemovalQueued = false

        // Update user visibility so that no extra impression will be logged when
        // activeMediaIndex resets to 0
        if (this::updateUserVisibility.isInitialized) {
            updateUserVisibility()
        }

        // Let's reset our scroll position
        mediaCarouselScrollHandler.scrollToStart()
    }

    private fun setUpListenersAndCallbacks() {
        if (MediaControlsInComposeFlag.isEnabled) return

        configurationController.addCallback(configListener)
        mediaReorderController.setCallback(::reorderingCallback)
        mediaManager.addListener(
            object : MediaDataManager.Listener {
                override fun onMediaDataLoaded(
                    key: String,
                    oldKey: String?,
                    data: MediaData,
                    immediately: Boolean,
                ) {
                    debugLogger.logMediaLoaded(key, data.active)
                    val onUiExecutionEnd = Runnable {
                        if (immediately) {
                            updateHostVisibility()
                        }
                    }
                    addOrUpdatePlayer(key, oldKey, data, onUiExecutionEnd)
                    val canRemove = data.isPlaying?.let { !it } ?: data.isClearable && !data.active
                    if (canRemove && !Utils.useMediaResumption(context)) {
                        // This media control is both paused and timed out, and the resumption
                        // setting is off - let's remove it
                        if (isReorderingAllowed) {
                            onMediaDataRemoved(key, userInitiated = MediaPlayerData.isSwipedAway)
                        } else {
                            isUserInitiatedRemovalQueued = MediaPlayerData.isSwipedAway
                            keysNeedRemoval.add(key)
                        }
                    } else {
                        keysNeedRemoval.remove(key)
                    }
                    MediaPlayerData.isSwipedAway = false
                }

                override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
                    debugLogger.logMediaRemoved(key, userInitiated)
                    removePlayer(key, userInitiated = userInitiated)
                }
            }
        )
        mediaFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (mediaFrameHeight != mediaFrame.height || mediaFrameWidth != mediaFrame.width) {
                mediaFrameHeight = mediaFrame.height
                mediaFrameWidth = mediaFrame.width
                debugLogger.logMediaBounds(
                    reason = "layout change",
                    rect = mediaFrame.boundsOnScreen,
                    location = desiredLocation,
                )
            }
            // The pageIndicator is not laid out yet when we get the current state update,
            // Lets make sure we have the right dimensions
            updatePageIndicatorLocation()
        }
        mediaHostStatesManager.addCallback(
            object : MediaHostStatesManager.Callback {
                override fun onHostStateChanged(
                    @MediaLocation location: Int,
                    mediaHostState: MediaHostState,
                ) {
                    updateUserVisibility()
                    if (location == desiredLocation) {
                        onDesiredLocationChanged(desiredLocation, mediaHostState, animate = false)
                    }
                }
            }
        )
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)

        listenForAnyStateToLockscreenTransition(applicationScope)
        listenForAnyStateToDozingTransition(applicationScope)
        listenForAnyStateToGoneKeyguardTransition(applicationScope)
        listenForLockscreenSettingChanges(applicationScope)

        // Notifies all active players about animation scale changes.
        bgExecutor.execute {
            globalSettings.registerContentObserverSync(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                animationScaleObserver,
            )
        }
    }

    private fun inflateSettingsButton() {
        val settings =
            LayoutInflater.from(context)
                .inflate(R.layout.media_carousel_settings_button, mediaFrame, false) as ViewGroup
        if (this::settingsButton.isInitialized) {
            mediaFrame.removeView(settingsButton)
        }
        settingsButton = settings
        mediaFrame.addView(settingsButton)
        mediaCarouselScrollHandler.onSettingsButtonUpdated(settings)
        settingsButton.setOnClickListener {
            logger.logCarouselSettings()
            activityStarter.startActivity(settingsIntent, /* dismissShade= */ true)
        }
    }

    private fun inflateMediaCarousel(): ViewGroup {
        val mediaCarousel =
            LayoutInflater.from(context)
                .inflate(R.layout.media_carousel, UniqueObjectHostView(context), false) as ViewGroup
        // Because this is inflated when not attached to the true view hierarchy, it resolves some
        // potential issues to force that the layout direction is defined by the locale
        // (rather than inherited from the parent, which would resolve to LTR when unattached).
        mediaCarousel.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        return mediaCarousel
    }

    private fun hideMediaCarousel() {
        mediaCarousel.visibility = View.GONE
    }

    private fun showMediaCarousel() {
        mediaCarousel.visibility = View.VISIBLE
    }

    @VisibleForTesting
    internal fun listenForAnyStateToGoneKeyguardTransition(scope: CoroutineScope): Job {
        return scope.launch {
            keyguardTransitionInteractor
                .isFinishedIn(content = Scenes.Gone, stateWithoutSceneContainer = GONE)
                .collect { isOnGone ->
                    if (isOnGone) {
                        showMediaCarousel()
                        updateHostVisibility()
                    } else if (!allowMediaPlayerOnLockScreen) {
                        updateHostVisibility()
                    }
                }
        }
    }

    @VisibleForTesting
    internal fun listenForAnyStateToLockscreenTransition(scope: CoroutineScope): Job {
        return scope.launch {
            keyguardTransitionInteractor
                .transition(Edge.create(to = LOCKSCREEN))
                .filter { it.transitionState == TransitionState.FINISHED }
                .collect {
                    if (!allowMediaPlayerOnLockScreen) {
                        updateHostVisibility()
                    }
                }
        }
    }

    @VisibleForTesting
    internal fun listenForLockscreenSettingChanges(scope: CoroutineScope): Job {
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
                    updateHostVisibility()
                }
        }
    }

    @VisibleForTesting
    internal fun listenForAnyStateToDozingTransition(scope: CoroutineScope): Job {
        return scope.launch {
            keyguardTransitionInteractor.isInTransition(Edge.create(to = DOZING)).collect {
                if (!allowMediaPlayerOnLockScreen) {
                    updateHostVisibility()
                }
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

    /** Return true if the carousel should be hidden because device is locked. */
    fun isLockedAndHidden(): Boolean {
        return !allowMediaPlayerOnLockScreen && isOnLockscreen()
    }

    private fun isOnLockscreen() = !isOnGone.value || isGoingToDozing.value

    private fun reorderAllPlayers() {
        mediaContent.removeAllViews()
        if (Flags.mediaControlsReorderFix()) {
            var left = 0
            val width = mediaCarouselScrollHandler.playerWidthPlusPadding
            if (isRtl) {
                left = (MediaPlayerData.players().size - 1) * width
            }
            for (mediaPlayer in MediaPlayerData.players()) {
                mediaPlayer.mediaViewHolder?.let {
                    val player = it.player
                    player.layout(left, 0, left + player.width, player.height)
                    mediaContent.addView(player)
                    if (isRtl) {
                        left -= width
                    } else {
                        left += width
                    }
                }
            }
        } else {
            for (mediaPlayer in MediaPlayerData.players()) {
                mediaPlayer.mediaViewHolder?.let { mediaContent.addView(it.player) }
            }
        }
        mediaCarouselScrollHandler.onPlayersChanged()
        mediaControlChipInteractor.updateMediaControlChipModelLegacy(
            MediaPlayerData.getFirstActiveMediaData()
        )
        MediaPlayerData.updateVisibleMediaPlayers()
        if (isRtl && mediaContent.childCount > 0) {
            // In RTL, Scroll to the first player as it is the rightmost player in media carousel.
            mediaCarouselScrollHandler.scrollToPlayer(destIndex = 0)
        }
        // Check postcondition: mediaContent should have the same number of children as there are
        // elements in mediaPlayers.
        if (MediaPlayerData.players().size != mediaContent.childCount) {
            Log.e(
                TAG,
                "Size of players list and number of views in carousel are out of sync. Players size is ${MediaPlayerData.players().size}. View count is ${mediaContent.childCount}.",
            )
        }
    }

    // Returns true if new player is added
    private fun addOrUpdatePlayer(
        key: String,
        oldKey: String?,
        data: MediaData,
        onUiExecutionEnd: Runnable,
    ): Boolean =
        traceSection("MediaCarouselController#addOrUpdatePlayer") {
            MediaPlayerData.moveIfExists(oldKey, key)
            val existingPlayer = MediaPlayerData.getMediaPlayer(key)
            if (existingPlayer == null) {
                bgExecutor.execute {
                    val mediaViewHolder = createMediaViewHolderInBg()
                    mediaViewHolder.titleText.gravity = if (isRtl) Gravity.RIGHT else Gravity.LEFT
                    mediaViewHolder.artistText.gravity = if (isRtl) Gravity.RIGHT else Gravity.LEFT
                    // Add the new player in the main thread.
                    uiExecutor.execute {
                        setupNewPlayer(key, data, mediaViewHolder)
                        updatePageIndicator()
                        mediaCarouselScrollHandler.onPlayersChanged()
                        mediaControlChipInteractor.updateMediaControlChipModelLegacy(
                            MediaPlayerData.getFirstActiveMediaData()
                        )
                        mediaFrame.requiresRemeasuring = true
                        onUiExecutionEnd.run()
                    }
                }
            } else {
                updatePlayer(key, data, existingPlayer)
                updatePageIndicator()
                mediaCarouselScrollHandler.onPlayersChanged()
                mediaControlChipInteractor.updateMediaControlChipModelLegacy(
                    MediaPlayerData.getFirstActiveMediaData()
                )
                mediaFrame.requiresRemeasuring = true
                onUiExecutionEnd.run()
            }
            return existingPlayer == null
        }

    private fun updatePlayer(key: String, data: MediaData, existingPlayer: MediaControlPanel) {
        existingPlayer.bindPlayer(data, key)
        MediaPlayerData.addMediaPlayer(key, data, existingPlayer, systemClock, debugLogger)
        if (isReorderingAllowed) {
            reorderAllPlayers()
        } else {
            needsReordering = true
        }
    }

    private fun setupNewPlayer(key: String, data: MediaData, mediaViewHolder: MediaViewHolder) {
        val newPlayer = mediaControlPanelFactory.get()
        newPlayer.attachPlayer(mediaViewHolder)
        newPlayer.mediaViewController.sizeChangedListener =
            this@MediaCarouselController::updateCarouselDimensions
        val lp =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        newPlayer.mediaViewHolder?.player?.setLayoutParams(lp)
        newPlayer.bindPlayer(data, key)
        newPlayer.setListening(mediaCarouselScrollHandler.visibleToUser && currentlyExpanded)
        MediaPlayerData.addMediaPlayer(key, data, newPlayer, systemClock, debugLogger)
        updateViewControllerToState(newPlayer.mediaViewController, noAnimation = true)
        if (data.active) {
            reorderAllPlayers()
        } else {
            needsReordering = true
        }
    }

    @WorkerThread
    private fun createMediaViewHolderInBg(): MediaViewHolder {
        return MediaViewHolder.create(LayoutInflater.from(context), mediaContent)
    }

    fun removePlayer(
        key: String,
        dismissMediaData: Boolean = true,
        userInitiated: Boolean = false,
    ): MediaControlPanel? {
        val removed = MediaPlayerData.removeMediaPlayer(key, dismissMediaData)
        return removed?.apply {
            mediaCarouselScrollHandler.onPrePlayerRemoved(removed.mediaViewHolder?.player)
            mediaContent.removeView(removed.mediaViewHolder?.player)
            removed.onDestroy()
            mediaCarouselScrollHandler.onPlayersChanged()
            mediaControlChipInteractor.updateMediaControlChipModelLegacy(
                MediaPlayerData.getFirstActiveMediaData()
            )
            updatePageIndicator()

            if (dismissMediaData) {
                // Inform the media manager of a potentially late dismissal
                mediaManager.dismissMediaData(key, delay = 0L, userInitiated = userInitiated)
            }
        }
    }

    private fun updatePlayers(recreateMedia: Boolean) {
        MediaControlsInComposeFlag.assertInLegacyMode()
        pageIndicator.tintList =
            ColorStateList.valueOf(context.getColor(R.color.media_paging_indicator))
        val onUiExecutionEnd = Runnable {
            if (recreateMedia) {
                reorderAllPlayers()
            }
        }

        val mediaDataList = MediaPlayerData.mediaData()
        // Do not loop through the original list of media data because the re-addition of media data
        // is being executed in background thread.
        mediaDataList.forEach { (key, data) ->
            if (recreateMedia) {
                removePlayer(key, dismissMediaData = false)
            }
            addOrUpdatePlayer(
                key = key,
                oldKey = null,
                data = data,
                onUiExecutionEnd = onUiExecutionEnd,
            )
        }
    }

    private fun updatePageIndicator() {
        val numPages = mediaContent.getChildCount()
        pageIndicator.setNumPages(numPages)
        if (numPages == 1) {
            pageIndicator.setLocation(0f)
        }
        updatePageIndicatorAlpha()

        if (!needsReordering || numPages == 1) {
            // If carousel needs to reorder, don't update arrow state until the reorder happens
            // But needsReordering can be true when there is only one player left, and we don't
            // need to delay in that case.
            updatePageArrows()
        }
    }

    private fun updatePageArrows() {
        val nPlayers = MediaPlayerData.players().size
        MediaPlayerData.players().forEachIndexed { index, mediaPlayer ->
            if (nPlayers == 1 || currentlyDisableScrolling) {
                mediaPlayer.setPageArrowsVisible(false)
            } else {
                mediaPlayer.setPageArrowsVisible(true)
                if (index == 0) {
                    mediaPlayer.setPageLeftEnabled(false)
                    mediaPlayer.setPageRightEnabled(true)
                } else if (index == nPlayers - 1) {
                    mediaPlayer.setPageLeftEnabled(true)
                    mediaPlayer.setPageRightEnabled(false)
                } else {
                    mediaPlayer.setPageLeftEnabled(true)
                    mediaPlayer.setPageRightEnabled(true)
                }
            }
            mediaPlayer.mediaViewController.refreshState()
        }
    }

    /**
     * Set a new interpolated state for all players. This is a state that is usually controlled by a
     * finger movement where the user drags from one state to the next.
     *
     * @param startLocation the start location of our state or -1 if this is directly set
     * @param endLocation the ending location of our state.
     * @param progress the progress of the transition between startLocation and endlocation. If this
     *   is not a guided transformation, this will be 1.0f
     * @param immediately should this state be applied immediately, canceling all animations?
     */
    fun setCurrentState(
        @MediaLocation startLocation: Int,
        @MediaLocation endLocation: Int,
        progress: Float,
        immediately: Boolean,
    ) {
        MediaControlsInComposeFlag.assertInLegacyMode()
        if (
            startLocation != currentStartLocation ||
                endLocation != currentEndLocation ||
                progress != currentTransitionProgress ||
                immediately
        ) {
            currentStartLocation = startLocation
            currentEndLocation = endLocation
            currentTransitionProgress = progress
            for (mediaPlayer in MediaPlayerData.players()) {
                updateViewControllerToState(mediaPlayer.mediaViewController, immediately)
            }
            maybeResetSettingsCog()
            updatePageIndicatorAlpha()
        }
    }

    @VisibleForTesting
    fun updatePageIndicatorAlpha() {
        val hostStates = mediaHostStatesManager.mediaHostStates
        val endIsVisible = hostStates[currentEndLocation]?.visible ?: false
        val startIsVisible = hostStates[currentStartLocation]?.visible ?: false
        val startAlpha = if (startIsVisible) 1.0f else 0.0f
        // when squishing in split shade, only use endState, which keeps changing
        // to provide squishFraction
        val squishFraction = hostStates[currentEndLocation]?.squishFraction ?: 1.0F
        val endAlpha =
            (if (endIsVisible) 1.0f else 0.0f) *
                calculateAlpha(
                    squishFraction,
                    (pageIndicator.translationY + pageIndicator.height) /
                        mediaCarousel.measuredHeight,
                    1F,
                )
        var alpha = 1.0f
        if (!endIsVisible || !startIsVisible) {
            var progress = currentTransitionProgress
            if (!endIsVisible) {
                progress = 1.0f - progress
            }
            // Let's fade in quickly at the end where the view is visible
            progress =
                MathUtils.constrain(MathUtils.map(0.95f, 1.0f, 0.0f, 1.0f, progress), 0.0f, 1.0f)
            alpha = MathUtils.lerp(startAlpha, endAlpha, progress)
        }
        pageIndicator.alpha = alpha
    }

    private fun updatePageIndicatorLocation() {
        // Update the location of the page indicator, carousel clipping
        val translationX =
            if (isRtl) {
                (pageIndicator.width - currentCarouselWidth) / 2.0f
            } else {
                (currentCarouselWidth - pageIndicator.width) / 2.0f
            }
        pageIndicator.translationX = translationX + mediaCarouselScrollHandler.contentTranslation
        val layoutParams = pageIndicator.layoutParams as ViewGroup.MarginLayoutParams
        pageIndicator.translationY =
            (mediaCarousel.measuredHeight - pageIndicator.height - layoutParams.bottomMargin)
                .toFloat()
    }

    /** Update listening to seekbar. */
    private fun updateSeekbarListening(visibleToUser: Boolean) {
        MediaControlsInComposeFlag.assertInLegacyMode()
        for (player in MediaPlayerData.players()) {
            player.listening = visibleToUser && currentlyExpanded
        }
    }

    /** Update the dimension of this carousel. */
    private fun updateCarouselDimensions() {
        MediaControlsInComposeFlag.assertInLegacyMode()
        var width = 0
        var height = 0
        for (mediaPlayer in MediaPlayerData.players()) {
            val controller = mediaPlayer.mediaViewController
            // When transitioning the view to gone, the view gets smaller, but the translation
            // Doesn't, let's add the translation
            width = Math.max(width, controller.currentWidth + controller.translationX.toInt())
            height = Math.max(height, controller.currentHeight + controller.translationY.toInt())
        }
        if (width != currentCarouselWidth || height != currentCarouselHeight) {
            currentCarouselWidth = width
            currentCarouselHeight = height
            mediaCarouselScrollHandler.setCarouselBounds(
                currentCarouselWidth,
                currentCarouselHeight,
            )
            updatePageIndicatorLocation()
            updatePageIndicatorAlpha()
        }
    }

    private fun maybeResetSettingsCog() {
        val hostStates = mediaHostStatesManager.mediaHostStates
        val endShowsActive = hostStates[currentEndLocation]?.showsOnlyActiveMedia ?: true
        val startShowsActive =
            hostStates[currentStartLocation]?.showsOnlyActiveMedia ?: endShowsActive
        val startDisableScrolling = hostStates[currentStartLocation]?.disableScrolling ?: false
        val endDisableScrolling = hostStates[currentEndLocation]?.disableScrolling ?: false

        if (
            currentlyShowingOnlyActive != endShowsActive ||
                currentlyDisableScrolling != endDisableScrolling ||
                ((currentTransitionProgress != 1.0f && currentTransitionProgress != 0.0f) &&
                    (startShowsActive != endShowsActive ||
                        startDisableScrolling != endDisableScrolling))
        ) {
            // Whenever we're transitioning from between differing states or the endstate differs
            // we reset the translation
            currentlyShowingOnlyActive = endShowsActive
            currentlyDisableScrolling = endDisableScrolling
            mediaCarouselScrollHandler.resetTranslation(animate = true)
            mediaCarouselScrollHandler.scrollingDisabled = currentlyDisableScrolling
        }
    }

    private fun updateViewControllerToState(
        viewController: MediaViewController,
        noAnimation: Boolean,
    ) {
        viewController.setCurrentState(
            startLocation = currentStartLocation,
            endLocation = currentEndLocation,
            transitionProgress = currentTransitionProgress,
            applyImmediately = noAnimation,
        )
    }

    /**
     * The desired location of this view has changed. We should remeasure the view to match the new
     * bounds and kick off bounds animations if necessary. If an animation is happening, an
     * animation is kicked of externally, which sets a new current state until we reach the
     * targetState.
     *
     * @param desiredLocation the location we're going to
     * @param desiredHostState the target state we're transitioning to
     * @param animate should this be animated
     */
    fun onDesiredLocationChanged(
        desiredLocation: Int,
        desiredHostState: MediaHostState?,
        animate: Boolean,
        duration: Long = 200,
        startDelay: Long = 0,
    ) =
        traceSection("MediaCarouselController#onDesiredLocationChanged") {
            MediaControlsInComposeFlag.assertInLegacyMode()
            desiredHostState?.let {
                if (this.desiredLocation != desiredLocation) {
                    // Only log an event when location changes
                    bgExecutor.execute { logger.logCarouselPosition(desiredLocation) }
                }

                // This is a hosting view, let's remeasure our players
                val prevLocation = this.desiredLocation
                this.desiredLocation = desiredLocation
                this.desiredHostState = it
                currentlyExpanded = it.expansion > 0

                // Set color of the settings button to material "on primary" color when media is on
                // communal for aesthetic and accessibility purposes since the background of
                // Glanceable Hub is a dynamic color.
                if (desiredLocation == MediaHierarchyManager.LOCATION_COMMUNAL_HUB) {
                    settingsButton
                        .requireViewById<ImageView>(R.id.settings_cog)
                        .setColorFilter(
                            context.getColor(com.android.internal.R.color.materialColorOnPrimary)
                        )
                } else {
                    settingsButton
                        .requireViewById<ImageView>(R.id.settings_cog)
                        .setColorFilter(
                            context.getColor(com.android.internal.R.color.materialColorOnSurface)
                        )
                }

                val shouldCloseGuts =
                    !currentlyExpanded &&
                        !mediaManager.hasActiveMedia() &&
                        desiredHostState.showsOnlyActiveMedia

                for (mediaPlayer in MediaPlayerData.players()) {
                    if (animate) {
                        mediaPlayer.mediaViewController.animatePendingStateChange(
                            duration = duration,
                            delay = startDelay,
                        )
                    }
                    if (shouldCloseGuts && mediaPlayer.mediaViewController.isGutsVisible) {
                        mediaPlayer.closeGuts(!animate)
                    }

                    mediaPlayer.mediaViewController.onLocationPreChange(
                        mediaPlayer.mediaViewHolder,
                        desiredLocation,
                        prevLocation,
                    )
                }
                mediaCarouselScrollHandler.showsSettingsButton = !it.showsOnlyActiveMedia
                mediaCarouselScrollHandler.falsingProtectionNeeded = it.falsingProtectionNeeded
                val nowVisible = it.visible
                if (nowVisible != playersVisible) {
                    playersVisible = nowVisible
                    if (nowVisible) {
                        mediaCarouselScrollHandler.resetTranslation()
                    }
                }
                updateCarouselSize()
            }
        }

    fun closeGuts(immediate: Boolean = true) {
        MediaControlsInComposeFlag.assertInLegacyMode()
        MediaPlayerData.players().forEach { it.closeGuts(immediate) }
    }

    /** Update the size of the carousel, remeasuring it if necessary. */
    private fun updateCarouselSize() {
        val width = desiredHostState?.measurementInput?.width ?: 0
        val height = desiredHostState?.measurementInput?.height ?: 0
        if (
            width != carouselMeasureWidth && width != 0 ||
                height != carouselMeasureHeight && height != 0
        ) {
            carouselMeasureWidth = width
            carouselMeasureHeight = height
            val playerWidthPlusPadding =
                carouselMeasureWidth +
                    context.resources.getDimensionPixelSize(R.dimen.qs_media_padding)
            // Let's remeasure the carousel
            val widthSpec = desiredHostState?.measurementInput?.widthMeasureSpec ?: 0
            val heightSpec = desiredHostState?.measurementInput?.heightMeasureSpec ?: 0
            mediaCarousel.measureAndLayout(
                widthSpec,
                heightSpec,
                width,
                mediaCarousel.measuredHeight,
            )
            debugLogger.logMediaCarouselDimensions(
                reason = "update carousel size",
                mediaCarousel.boundsOnScreen,
                desiredLocation,
            )
            mediaFrame.measureAndLayout(widthSpec, heightSpec, width, mediaCarousel.measuredHeight)
            // Update the padding after layout; view widths are used in RTL to calculate scrollX
            mediaCarouselScrollHandler.playerWidthPlusPadding = playerWidthPlusPadding
        }
    }

    private fun ViewGroup.measureAndLayout(
        widthSpec: Int,
        heightSpec: Int,
        width: Int,
        height: Int,
    ) {
        measure(widthSpec, heightSpec)
        layout(0, 0, width, height)
    }

    /** Triggered whenever carousel becomes visible, e.g. on swipe down the notification shade. */
    fun onCarouselVisibleToUser() {
        if (!enableSuggestedDeviceUi()) {
            return
        }
        onCardVisibilityChanged()
    }

    /** Triggered whenever carousel's scroll position changes, revealing a new card. */
    fun onVisibleCardChanged() {
        if (!enableSuggestedDeviceUi()) {
            return
        }
        val newVisiblePlayerKey =
            visiblePlayerKeys().elementAtOrNull(mediaCarouselScrollHandler.visibleMediaIndex)?.key
        if (newVisiblePlayerKey != lastFullyVisiblePlayerKey) {
            lastFullyVisiblePlayerKey = newVisiblePlayerKey
            if (newVisiblePlayerKey != null) {
                onCardVisibilityChanged()
            }
        }
    }

    /**
     * Triggered whenever card becomes visible either due to the carousel being visible or the card
     * visibility changed within the carousel.
     */
    private fun onCardVisibilityChanged() {
        val isCarouselVisible = mediaCarouselScrollHandler.visibleToUser
        val visibleMediaIndex = mediaCarouselScrollHandler.visibleMediaIndex
        debugLogger.logCardVisibilityChanged(isCarouselVisible, visibleMediaIndex)

        if (!enableSuggestedDeviceUi() || !isCarouselVisible) {
            return
        }
        if (MediaPlayerData.players().size > visibleMediaIndex) {
            val mediaControlPanel = MediaPlayerData.getMediaControlPanel(visibleMediaIndex)
            mediaControlPanel?.onPanelFullyVisible()
        }
    }

    @VisibleForTesting
    fun onSwipeToDismiss() {
        MediaControlsInComposeFlag.assertInLegacyMode()
        MediaPlayerData.isSwipedAway = true
        logger.logSwipeDismiss()
        mediaManager.onSwipeToDismiss()
    }

    fun getCurrentVisibleMediaContentIntent(): PendingIntent? {
        return MediaPlayerData.playerKeys()
            .elementAtOrNull(mediaCarouselScrollHandler.visibleMediaIndex)
            ?.data
            ?.clickIntent
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.apply {
            println("keysNeedRemoval: $keysNeedRemoval")
            println("dataKeys: ${MediaPlayerData.dataKeys()}")
            println("orderedPlayerSortKeys: ${MediaPlayerData.playerKeys()}")
            println("visiblePlayerSortKeys: ${MediaPlayerData.visiblePlayerKeys()}")
            println("current size: $currentCarouselWidth x $currentCarouselHeight")
            println("location: $desiredLocation")
            println(
                "state: ${desiredHostState?.expansion}, only active ${desiredHostState?.showsOnlyActiveMedia}, visible ${desiredHostState?.visible}"
            )
            println("isSwipedAway: ${MediaPlayerData.isSwipedAway}")
            println("allowMediaPlayerOnLockScreen: $allowMediaPlayerOnLockScreen")

            println("carousel children: ")
            for (i in 0 until mediaContent.childCount) {
                println(
                    "\t$i: ${mediaContent.getChildAt(i).contentDescription} left ${mediaContent.getChildAt(i).left}"
                )
            }
            println(
                "carousel scroll ${mediaCarousel.relativeScrollX}, translation ${mediaCarousel.getContentTranslation()}"
            )
        }
    }
}

@VisibleForTesting
internal object MediaPlayerData {

    data class MediaSortKey(val data: MediaData, val key: String, val updateTime: Long = 0)

    private val comparator =
        compareByDescending<MediaSortKey> {
                it.data.isPlaying == true && it.data.playbackLocation == MediaData.PLAYBACK_LOCAL
            }
            .thenByDescending {
                it.data.isPlaying == true &&
                    it.data.playbackLocation == MediaData.PLAYBACK_CAST_LOCAL
            }
            .thenByDescending { it.data.active }
            .thenByDescending { !it.data.resumption }
            .thenByDescending { it.data.playbackLocation != MediaData.PLAYBACK_CAST_REMOTE }
            .thenByDescending { it.data.lastActive }
            .thenByDescending { it.updateTime }
            .thenByDescending { it.data.notificationKey }

    private val mediaPlayers = TreeMap<MediaSortKey, MediaControlPanel>(comparator)
    private val mediaData: MutableMap<String, MediaSortKey> = mutableMapOf()

    // A map that tracks order of visible media players before they get reordered.
    private val visibleMediaPlayers = LinkedHashMap<String, MediaSortKey>()

    // Whether the user swiped away the carousel since its last update
    internal var isSwipedAway: Boolean = false

    fun addMediaPlayer(
        key: String,
        data: MediaData,
        player: MediaControlPanel,
        clock: SystemClock,
        debugLogger: MediaCarouselControllerLogger? = null,
    ) {
        val removedPlayer = removeMediaPlayer(key)
        if (removedPlayer != null && removedPlayer != player) {
            debugLogger?.logPotentialMemoryLeak(key)
            removedPlayer.onDestroy()
        }
        val sortKey = MediaSortKey(data, key, clock.currentTimeMillis())
        mediaData[key] = sortKey
        mediaPlayers[sortKey] = player
        visibleMediaPlayers[key] = sortKey
    }

    fun moveIfExists(
        oldKey: String?,
        newKey: String,
        debugLogger: MediaCarouselControllerLogger? = null,
    ) {
        if (oldKey == null || oldKey == newKey) {
            return
        }

        if (enableSuggestedDeviceUi()) {
            replaceVisiblePlayerKey(oldKey, newKey)
        }
        mediaData.remove(oldKey)?.let {
            // MediaPlayer should not be visible
            // no need to set isDismissed flag.
            val removedPlayer = removeMediaPlayer(newKey)
            removedPlayer?.run {
                debugLogger?.logPotentialMemoryLeak(newKey)
                onDestroy()
            }
            mediaData.put(newKey, it)
        }
    }

    /** Changes the key in visibleMediaPlayers while preserving the order */
    private fun replaceVisiblePlayerKey(oldKey: String, newKey: String) {
        val newVisibleMediaPlayers =
            visibleMediaPlayers.mapKeys { (key, _) -> if (key == oldKey) newKey else key }
        visibleMediaPlayers.clear()
        visibleMediaPlayers.putAll(newVisibleMediaPlayers)
    }

    fun getMediaControlPanel(visibleIndex: Int): MediaControlPanel? {
        return mediaPlayers[visiblePlayerKeys().elementAt(visibleIndex)]
    }

    fun getMediaPlayer(key: String): MediaControlPanel? {
        return mediaData[key]?.let { mediaPlayers[it] }
    }

    fun getMediaPlayerIndex(key: String): Int {
        val sortKey = mediaData[key]
        mediaPlayers.entries.forEachIndexed { index, e ->
            if (e.key == sortKey) {
                return index
            }
        }
        return -1
    }

    /**
     * Removes media player given the key.
     *
     * @param isDismissed determines whether the media player is removed from the carousel.
     */
    fun removeMediaPlayer(key: String, isDismissed: Boolean = false) =
        mediaData.remove(key)?.let {
            if (isDismissed) {
                visibleMediaPlayers.remove(key)
            }
            mediaPlayers.remove(it)
        }

    fun mediaData() = mediaData.entries.map { e -> Pair(e.key, e.value.data) }

    fun dataKeys() = mediaData.keys

    fun players() = mediaPlayers.values

    fun playerKeys() = mediaPlayers.keys

    fun visiblePlayerKeys() = visibleMediaPlayers.values

    /** Returns the [MediaData] associated with the first mediaPlayer in the mediaCarousel. */
    fun getFirstActiveMediaData(): MediaData? {
        // TODO simplify ..??
        mediaPlayers.entries.forEach { entry ->
            if (entry.key.data.active) {
                return entry.key.data
            }
        }
        return null
    }

    @VisibleForTesting
    fun clear() {
        mediaData.clear()
        mediaPlayers.clear()
        visibleMediaPlayers.clear()
    }

    /**
     * This method is called when media players are reordered. To make sure we have the new version
     * of the order of media players visible to user.
     */
    fun updateVisibleMediaPlayers() {
        visibleMediaPlayers.clear()
        playerKeys().forEach { visibleMediaPlayers[it.key] = it }
    }
}
