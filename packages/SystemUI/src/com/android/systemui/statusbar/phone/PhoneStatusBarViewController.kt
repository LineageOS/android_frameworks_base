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
package com.android.systemui.statusbar.phone

import android.app.StatusBarManager.WINDOW_STATUS_BAR
import android.content.res.Resources
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.VisibleForTesting
import com.android.systemui.Gefingerpoken
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.ShadeLogger
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.display.StatusBarTouchShadeDisplayPolicy
import com.android.systemui.shade.display.domain.interactor.ShadeExpansionTargetDisplayInteractor
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.statusbar.core.StatusBarEventForwardingModernization
import com.android.systemui.statusbar.data.repository.StatusBarConfigurationController
import com.android.systemui.statusbar.gesture.StatusBarLongPressGestureDetector
import com.android.systemui.statusbar.layout.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import com.android.systemui.statusbar.window.StatusBarWindowStateController
import com.android.systemui.unfold.UNFOLD_STATUS_BAR
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel
import com.android.systemui.util.ViewController
import com.android.systemui.util.kotlin.getOrNull
import com.android.systemui.util.view.ViewUtil
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

private const val TAG = "PhoneStatusBarViewController"

/** Controller for [PhoneStatusBarView]. */
class PhoneStatusBarViewController
private constructor(
    view: PhoneStatusBarView,
    @Named(UNFOLD_STATUS_BAR) private val progressProvider: ScopedUnfoldTransitionProgressProvider?,
    @DisplayAware private val resources: Resources,
    private val centralSurfaces: CentralSurfaces,
    private val statusBarWindowStateController: StatusBarWindowStateController,
    private val shadeController: ShadeController,
    private val shadeViewController: ShadeViewController,
    private val shadeModeInteractor: ShadeModeInteractor,
    private val panelExpansionInteractor: PanelExpansionInteractor,
    private val statusBarLongPressGestureDetector: Provider<StatusBarLongPressGestureDetector>,
    private val windowRootView: Provider<WindowRootView>,
    private val shadeLogger: ShadeLogger,
    private val userChipViewModel: StatusBarUserChipViewModel,
    private val viewUtil: ViewUtil,
    private val configurationController: ConfigurationController,
    private val statusOverlayHoverListenerFactory: StatusOverlayHoverListenerFactory,
    private val darkIconDispatcher: DarkIconDispatcher,
    private val statusBarContentInsetsProvider: StatusBarContentInsetsProvider,
    private val lazyStatusBarShadeDisplayPolicy: Lazy<StatusBarTouchShadeDisplayPolicy>,
    private val shadeExpansionTargetDisplayInteractor: ShadeExpansionTargetDisplayInteractor,
    private val lazyShadeDisplaysRepository: Lazy<ShadeDisplaysRepository>,
    private val statusBarWindowControllerStore: StatusBarWindowControllerStore,
) : ViewController<PhoneStatusBarView>(view) {

    private lateinit var clock: Clock
    private lateinit var clockCenter: Clock
    private lateinit var clockRight: Clock
    private lateinit var startSideContainer: View
    private lateinit var endSideContainer: View

    private val shadeInvocationSplitRatio: Float =
        resources.getFloat(R.dimen.config_invocationGestureSplitRatio)

    // Creates a [View.OnTouchListener] that only handles mouse click events.
    private fun createMouseClickListener(onClick: () -> Unit): View.OnTouchListener =
        object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                // We want to handle only mouse events here to avoid stealing finger touches
                // from status bar which expands shade when swiped down on. See b/326097469.
                // We're using onTouchListener instead of onClickListener as the later will lead
                // to isClickable being set to true and hence ALL touches always being
                // intercepted. See [View.OnTouchEvent]
                if (event.source == InputDevice.SOURCE_MOUSE) {
                    if (event.action == MotionEvent.ACTION_UP) {
                        dispatchEventToShadeDisplayPolicy(event)
                        v.performClick()
                        onClick()
                    }
                    return true
                }
                return false
            }
        }

    // Creates a [View.OnTouchListener] that handles mouse clicks and finger taps.
    private fun createClickListener(v: View, onClick: () -> Unit): View.OnTouchListener {
        val gestureDetector =
            GestureDetector(
                mView.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean {
                        // Return true here to receive subsequent events, which are then
                        // handled by onSingleTapUp.
                        return true
                    }

                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        dispatchEventToShadeDisplayPolicy(e)
                        v.performClick()
                        onClick()
                        return true
                    }
                },
            )
        return View.OnTouchListener { _, event ->
            // Handle mouse clicks separately.
            if (event.source == InputDevice.SOURCE_MOUSE) {
                if (event.action == MotionEvent.ACTION_UP) {
                    dispatchEventToShadeDisplayPolicy(event)
                    v.performClick()
                    onClick()
                }
                return@OnTouchListener true
            }

            // For all other (touch) events, delegate to the GestureDetector.
            return@OnTouchListener gestureDetector.onTouchEvent(event)
        }
    }

    private fun dispatchEventToShadeDisplayPolicy(event: MotionEvent) {
        // Notify the shade display policy that the status bar was touched. This may cause
        // the shade to change display if the touch was in a display different than the shade
        // one.
        val isRtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        shadeExpansionTargetDisplayInteractor.setExpansionIntentFromStatusBarEvent(
            event.x,
            event.displayId,
            mView.width,
            shadeInvocationSplitRatio,
            isRtl,
        )
    }

    private val configurationListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onDensityOrFontScaleChanged() {
                reloadDimens()
            }
        }

    private fun reloadDimens() {
        // The hover listener uses a resource, so we need to re-create it.
        updateStartSideContainerHoverListener()
    }

    override fun onViewAttached() {
        clock = mView.requireViewById(R.id.clock)
        clockCenter = mView.requireViewById(R.id.clock_center)
        clockRight = mView.requireViewById(R.id.clock_right)

        addDarkReceivers()

        if (mView.context.getDisplayId() != DEFAULT_DISPLAY) {
            // With the StatusBarConnectedDisplays changes, external status bar elements are not
            // interactive when the shade window can't change displays.
            mView.setIsStatusBarInteractiveSupplier {
                val shadeDisplayPolicy = lazyShadeDisplaysRepository.get().currentPolicy
                shadeDisplayPolicy is StatusBarTouchShadeDisplayPolicy
            }
        }

        addCursorSupportToIconContainers()

        if (!StatusBarEventForwardingModernization.isEnabled) {
            mView.setLongPressGestureDetector(statusBarLongPressGestureDetector.get())
        }
        progressProvider?.setReadyToHandleTransition(true)
        configurationController.addCallback(configurationListener)
    }

    private fun addCursorSupportToIconContainers() {
        endSideContainer = mView.requireViewById(R.id.system_icons)
        endSideContainer.setOnHoverListener(
            statusOverlayHoverListenerFactory.createDarkAwareListener(endSideContainer)
        )

        if (statusBarTapToExpandShadeEnabled()) {
            endSideContainer.setOnTouchListener(
                createClickListener(endSideContainer) { animateExpandQs() }
            )
        } else {
            endSideContainer.setOnTouchListener(createMouseClickListener { animateExpandQs() })
        }

        startSideContainer = mView.requireViewById(R.id.status_bar_start_side_content)
        updateStartSideContainerHoverListener()
        if (statusBarTapToExpandShadeEnabled()) {
            startSideContainer.setOnTouchListener(
                createClickListener(startSideContainer) { shadeController.animateExpandShade() }
            )
        } else {
            startSideContainer.setOnTouchListener(
                createMouseClickListener { shadeController.animateExpandShade() }
            )
        }
    }

    private fun updateStartSideContainerHoverListener() {
        val iconContainerHeightPx =
            context.resources.getDimensionPixelSize(R.dimen.status_bar_icon_container_height)
        startSideContainer.setOnHoverListener(
            statusOverlayHoverListenerFactory.createDarkAwareListener(
                startSideContainer,
                customHeightPx = iconContainerHeightPx,
            )
        )
    }

    private fun statusBarTapToExpandShadeEnabled(): Boolean {
        return context.resources.getBoolean(R.bool.config_statusBarTapToExpandShade)
    }

    private fun animateExpandQs() {
        if (shadeModeInteractor.isDualShade) {
            shadeController.animateExpandQs()
        } else {
            shadeController.animateExpandShade()
        }
    }

    @VisibleForTesting
    public override fun onViewDetached() {
        removeDarkReceivers()
        startSideContainer.setOnHoverListener(null)
        endSideContainer.setOnHoverListener(null)
        progressProvider?.setReadyToHandleTransition(false)
        configurationController.removeCallback(configurationListener)
    }

    init {
        // These should likely be done in `onInit`, not `init`.
        mView.setTouchEventHandler(
            if (StatusBarEventForwardingModernization.isEnabled) {
                null
            } else {
                PhoneStatusBarViewTouchHandler()
            }
        )
        statusBarContentInsetsProvider?.let {
            mView.setHasCornerCutoutFetcher { it.currentRotationHasCornerCutout() }
            mView.setInsetsFetcher { it.getStatusBarContentInsetsForCurrentRotation() }
        }
        mView.init(userChipViewModel)
    }

    override fun onInit() {}

    fun setImportantForAccessibility(mode: Int) {
        mView.importantForAccessibility = mode
    }

    /**
     * Sends a touch event to the status bar view.
     *
     * This is required in certain cases because the status bar view is in a separate window from
     * the rest of SystemUI, and other windows may decide that their touch should instead be treated
     * as a status bar window touch.
     */
    fun sendTouchToView(ev: MotionEvent): Boolean {
        return mView.dispatchTouchEvent(ev)
    }

    /**
     * Returns true if the given (x, y) point (in screen coordinates) is within the status bar
     * view's range and false otherwise.
     */
    fun touchIsWithinView(x: Float, y: Float): Boolean {
        return viewUtil.touchIsWithinView(mView, x, y)
    }

    /** Called when a touch event occurred on {@link PhoneStatusBarView}. */
    fun onTouch(event: MotionEvent) {
        if (statusBarWindowStateController.windowIsShowing()) {
            val upOrCancel =
                event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL
            centralSurfaces.setInteracting(
                WINDOW_STATUS_BAR,
                !upOrCancel || shadeController.isExpandedVisible,
            )
        }
    }

    private fun addDarkReceivers() {
        darkIconDispatcher.addDarkReceiver(clock)
        darkIconDispatcher.addDarkReceiver(clockCenter)
        darkIconDispatcher.addDarkReceiver(clockRight)
    }

    private fun removeDarkReceivers() {
        darkIconDispatcher.removeDarkReceiver(clock)
        darkIconDispatcher.removeDarkReceiver(clockCenter)
        darkIconDispatcher.removeDarkReceiver(clockRight)
    }

    @Deprecated(
        "This touch handler will be unused once StatusBarEventForwardingModernization is enabled"
    )
    inner class PhoneStatusBarViewTouchHandler : Gefingerpoken {
        private val touchSlop = ViewConfiguration.get(mView.context).scaledTouchSlop
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var isIntercepting = false
        private val cachedEvents = mutableListOf<MotionEvent>()

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            StatusBarEventForwardingModernization.assertInLegacyMode()

            if (event.action == MotionEvent.ACTION_DOWN) {
                dispatchEventToShadeDisplayPolicy(event)
            }

            // Let ShadeViewController intercept touch events when flexiglass is disabled.
            if (!SceneContainerFlag.isEnabled) {
                return shadeViewController.handleExternalInterceptTouch(event)
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isIntercepting = false
                    clearCachedEvents()
                    initialTouchX = event.x
                    initialTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - initialTouchY
                    if (dy > touchSlop) {
                        if (!isIntercepting) {
                            isIntercepting = true
                            dispatchCachedEvents()
                        }
                        windowRootView.get().dispatchTouchEvent(event)
                        return true
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    clearCachedEvents()
                    isIntercepting = false
                }
            }

            if (!isIntercepting) {
                cacheEvent(event)
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            StatusBarEventForwardingModernization.assertInLegacyMode()

            onTouch(event)

            // If panels aren't enabled, ignore the gesture and don't pass it down to the
            // panel view.
            if (!centralSurfaces.commandQueuePanelsEnabled) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    Log.v(
                        TAG,
                        String.format(
                            "onTouchForwardedFromStatusBar: panel disabled, " +
                                "ignoring touch at (${event.x.toInt()},${event.y.toInt()})"
                        ),
                    )
                }
                return false
            }

            // If scene framework is enabled, route the touch to it and
            // ignore the rest of the gesture.
            if (SceneContainerFlag.isEnabled) {
                windowRootView.get().dispatchTouchEvent(event)
                return true
            }

            if (event.action == MotionEvent.ACTION_DOWN) {
                // If the view that would receive the touch is disabled, just have status
                // bar eat the gesture.
                if (!shadeViewController.isViewEnabled) {
                    shadeLogger.logMotionEvent(
                        event,
                        "onTouchForwardedFromStatusBar: panel view disabled",
                    )
                    return true
                }
                if (panelExpansionInteractor.isFullyCollapsed && event.y < 1f) {
                    // b/235889526 Eat events on the top edge of the phone when collapsed
                    shadeLogger.logMotionEvent(event, "top edge touch ignored")
                    return true
                }
            }

            return shadeViewController.handleExternalTouch(event)
        }

        private fun cacheEvent(event: MotionEvent) {
            cachedEvents.add(MotionEvent.obtain(event))
        }

        private fun dispatchCachedEvents() {
            cachedEvents.forEach { windowRootView.get()?.dispatchTouchEvent(it) }
            clearCachedEvents()
        }

        private fun clearCachedEvents() {
            cachedEvents.forEach { it.recycle() }
            cachedEvents.clear()
        }
    }

    class Factory
    @Inject
    constructor(
        @Named(UNFOLD_STATUS_BAR)
        private val progressProvider: Optional<ScopedUnfoldTransitionProgressProvider>,
        private val userChipViewModel: StatusBarUserChipViewModel,
        @DisplayAware private val resources: Resources,
        private val centralSurfaces: CentralSurfaces,
        @DisplayAware private val statusBarWindowStateController: StatusBarWindowStateController,
        private val shadeController: ShadeController,
        private val shadeViewController: ShadeViewController,
        private val shadeModeInteractor: ShadeModeInteractor,
        private val panelExpansionInteractor: PanelExpansionInteractor,
        @DisplayAware
        private val statusBarLongPressGestureDetector: Provider<StatusBarLongPressGestureDetector>,
        private val windowRootView: Provider<WindowRootView>,
        private val shadeLogger: ShadeLogger,
        private val viewUtil: ViewUtil,
        @DisplayAware
        private val statusBarConfigurationController: StatusBarConfigurationController,
        private val statusOverlayHoverListenerFactory: StatusOverlayHoverListenerFactory,
        @DisplayAware private val darkIconDispatcher: DarkIconDispatcher,
        @DisplayAware private val statusBarContentInsetsProvider: StatusBarContentInsetsProvider,
        private val lazyStatusBarShadeDisplayPolicy: Lazy<StatusBarTouchShadeDisplayPolicy>,
        private val shadeExpansionTargetDisplayInteractor: ShadeExpansionTargetDisplayInteractor,
        private val lazyShadeDisplaysRepository: Lazy<ShadeDisplaysRepository>,
        private val statusBarWindowControllerStore: StatusBarWindowControllerStore,
    ) {
        fun create(view: PhoneStatusBarView): PhoneStatusBarViewController {
            return PhoneStatusBarViewController(
                view,
                progressProvider.getOrNull(),
                resources,
                centralSurfaces,
                statusBarWindowStateController,
                shadeController,
                shadeViewController,
                shadeModeInteractor,
                panelExpansionInteractor,
                statusBarLongPressGestureDetector,
                windowRootView,
                shadeLogger,
                userChipViewModel,
                viewUtil,
                statusBarConfigurationController,
                statusOverlayHoverListenerFactory,
                darkIconDispatcher,
                statusBarContentInsetsProvider,
                lazyStatusBarShadeDisplayPolicy,
                shadeExpansionTargetDisplayInteractor,
                lazyShadeDisplaysRepository,
                statusBarWindowControllerStore,
            )
        }
    }
}
