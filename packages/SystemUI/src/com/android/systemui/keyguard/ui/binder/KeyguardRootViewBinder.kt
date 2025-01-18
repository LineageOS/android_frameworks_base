/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.binder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.graphics.Point
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.OnLayoutChangeListener
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.OnHierarchyChangeListener
import android.view.WindowInsets
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.keyguard.AuthInteractionProperties
import com.android.systemui.Flags
import com.android.systemui.Flags.msdlFeedback
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.common.ui.view.onApplyWindowInsets
import com.android.systemui.common.ui.view.onLayoutChanged
import com.android.systemui.common.ui.view.onTouchListener
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryHapticsInteractor
import com.android.systemui.initOnBackPressedDispatcherOwner
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.view.layout.sections.AodPromotedNotificationSection
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.keyguard.ui.viewmodel.TransitionData
import com.android.systemui.keyguard.ui.viewmodel.ViewStateAccessor
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.KeyguardBlueprintLog
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.R as sharedR
import com.android.systemui.statusbar.CrossFadeHelper
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.util.kotlin.DisposableHandles
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.MSDLPlayer
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Bind keyguard UI to run whenever the keyguard view is attached. */
object KeyguardRootViewBinder {
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: KeyguardRootViewModel,
        blueprintViewModel: KeyguardBlueprintViewModel,
        configuration: ConfigurationState,
        shadeInteractor: ShadeInteractor,
        smartspaceViewModel: KeyguardSmartspaceViewModel,
        deviceEntryHapticsInteractor: DeviceEntryHapticsInteractor?,
        vibratorHelper: VibratorHelper?,
        falsingManager: FalsingManager?,
        statusBarKeyguardViewManager: StatusBarKeyguardViewManager?,
        mainImmediateDispatcher: CoroutineDispatcher,
        msdlPlayer: MSDLPlayer?,
        @KeyguardBlueprintLog blueprintLog: LogBuffer,
    ): DisposableHandle {
        val disposables = DisposableHandles()
        val childViews = mutableMapOf<Int, View>()

        disposables +=
            view.onTouchListener { _, event ->
                var consumed = false
                if (falsingManager?.isFalseTap(FalsingManager.LOW_PENALTY) == false) {
                    // signifies a primary button click down has reached keyguardrootview
                    // we need to return true here otherwise an ACTION_UP will never arrive
                    if (Flags.nonTouchscreenDevicesBypassFalsing()) {
                        if (
                            event.action == MotionEvent.ACTION_DOWN &&
                                event.buttonState == MotionEvent.BUTTON_PRIMARY &&
                                !event.isTouchscreenSource()
                        ) {
                            consumed = true
                        } else if (
                            event.action == MotionEvent.ACTION_UP && !event.isTouchscreenSource()
                        ) {
                            statusBarKeyguardViewManager?.showBouncer(
                                true,
                                "KeyguardRootViewBinder: click on lockscreen",
                            )
                            consumed = true
                        }
                    }
                    viewModel.setRootViewLastTapPosition(Point(event.x.toInt(), event.y.toInt()))
                }
                consumed
            }

        val burnInParams = MutableStateFlow(BurnInParameters())
        val viewState = ViewStateAccessor(alpha = { view.alpha })

        disposables +=
            view.repeatWhenAttached(mainImmediateDispatcher) {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    if (deviceEntryHapticsInteractor != null && vibratorHelper != null) {
                        launch {
                            deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry.collect {
                                if (msdlFeedback()) {
                                    msdlPlayer?.playToken(
                                        MSDLToken.UNLOCK,
                                        authInteractionProperties,
                                    )
                                } else {
                                    vibratorHelper.performHapticFeedback(
                                        view,
                                        HapticFeedbackConstants.BIOMETRIC_CONFIRM,
                                    )
                                }
                            }
                        }

                        launch {
                            deviceEntryHapticsInteractor.playErrorHaptic.collect {
                                if (msdlFeedback()) {
                                    msdlPlayer?.playToken(
                                        MSDLToken.FAILURE,
                                        authInteractionProperties,
                                    )
                                } else {
                                    vibratorHelper.performHapticFeedback(
                                        view,
                                        HapticFeedbackConstants.BIOMETRIC_REJECT,
                                    )
                                }
                            }
                        }
                    }

                    launch("$TAG#topClippingBounds") {
                        val clipBounds = Rect()
                        viewModel.topClippingBounds.collect { clipTop ->
                            if (clipTop == null) {
                                view.setClipBounds(null)
                            } else {
                                clipBounds.apply {
                                    top = clipTop
                                    left = view.getLeft()
                                    right = view.getRight()
                                    bottom = view.getBottom()
                                }
                                view.setClipBounds(clipBounds)
                            }
                        }
                    }

                    launch("$TAG#alpha") {
                        viewModel.alpha(viewState).collect { alpha ->
                            view.alpha = alpha
                            childViews[burnInLayerId]?.alpha = alpha
                            childViews[sliceViewId]?.alpha = alpha
                        }
                    }

                    launch("$TAG#nonAuthUIAlpha") {
                        viewModel.nonAuthUIAlpha.collect { alpha ->
                            for (childView in childViews) {
                                if (!authUiIds.contains(childView.key)) {
                                    childView.value.alpha = alpha
                                }
                            }
                        }
                    }

                    launch("$TAG#zoomOut") {
                        viewModel.scaleFromZoomOut.collect { scaleFromZoomOut ->
                            view.scaleX = scaleFromZoomOut
                            view.scaleY = scaleFromZoomOut
                        }
                    }

                    launch("$TAG#translationY") {
                        // When translation happens in burnInLayer, it won't be weather clock large
                        // clock isn't added to burnInLayer due to its scale transition so we also
                        // need to add translation to it here same as translationX
                        viewModel.translationY.collect { y ->
                            childViews[burnInLayerId]?.translationY = y
                            childViews[sliceViewId]?.translationY = y
                            childViews[largeClockId]?.translationY = y
                            childViews[largeClockDateId]?.translationY = y
                            childViews[aodPromotedNotificationId]?.translationY = y
                            childViews[aodNotificationIconContainerId]?.translationY = y
                        }
                    }

                    launch("$TAG#translationX") {
                        viewModel.translationX.collect { state ->
                            val px = state.value ?: return@collect
                            when {
                                state.isToOrFrom(KeyguardState.AOD) -> {
                                    // Large Clock is not translated in the x direction
                                    childViews[burnInLayerId]?.translationX = px
                                    childViews[sliceViewId]?.translationX = px
                                    childViews[aodPromotedNotificationId]?.translationX = px
                                    childViews[aodNotificationIconContainerId]?.translationX = px
                                }

                                state.isToOrFrom(KeyguardState.GLANCEABLE_HUB) -> {
                                    for ((key, childView) in childViews.entries) {
                                        when (key) {
                                            indicationArea,
                                            startButton,
                                            endButton,
                                            deviceEntryIcon -> {
                                                // Do not move these views
                                            }

                                            else -> childView.translationX = px
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        disposables +=
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    if (SceneContainerFlag.isEnabled) {
                        view.initOnBackPressedDispatcherOwner(
                            this@repeatWhenAttached.lifecycle,
                            force = true,
                        )
                    }

                    launch {
                        viewModel.burnInLayerVisibility.collect { visibility ->
                            childViews[burnInLayerId]?.visibility = visibility
                            childViews[sliceViewId]?.visibility = visibility
                        }
                    }

                    launch {
                        viewModel.scale.collect { scaleViewModel ->
                            if (scaleViewModel.scaleClockOnly) {
                                // For clocks except weather clock, we have scale transition besides
                                // translate
                                childViews[largeClockId]?.let {
                                    it.scaleX = scaleViewModel.scale
                                    it.scaleY = scaleViewModel.scale
                                }
                            }
                        }
                    }

                    launch {
                        blueprintViewModel.currentTransition.collect { currentTransition ->
                            // When blueprint/clock transitions end (null), make sure NSSL is in the
                            // right place
                            if (currentTransition == null) {
                                childViews[nsslPlaceholderId]?.let { notificationListPlaceholder ->
                                    viewModel.onNotificationContainerBoundsChanged(
                                        notificationListPlaceholder.top.toFloat(),
                                        notificationListPlaceholder.bottom.toFloat(),
                                        animate = true,
                                    )
                                }
                            }
                        }
                    }

                    launch {
                        val iconsAppearTranslationPx =
                            configuration
                                .getDimensionPixelSize(R.dimen.shelf_appear_translation)
                                .stateIn(this)
                        viewModel.isNotifIconContainerVisible.collect { isVisible ->
                            if (isVisible.value) {
                                blueprintViewModel.refreshBlueprint()
                            }
                            childViews[aodNotificationIconContainerId]
                                ?.setAodNotifIconContainerIsVisible(isVisible)
                        }
                    }

                    launch {
                        viewModel.isAodPromotedNotifVisible.collect { isVisible ->
                            if (isVisible.value) {
                                blueprintViewModel.refreshBlueprint()
                            }
                            childViews[aodPromotedNotificationId]?.setAodPromotedNotifIsVisible(
                                isVisible
                            )
                        }
                    }

                    launch {
                        shadeInteractor.isAnyFullyExpanded.collect { isFullyAnyExpanded ->
                            view.visibility =
                                if (isFullyAnyExpanded) {
                                    INVISIBLE
                                } else {
                                    VISIBLE
                                }
                        }
                    }

                    launch { burnInParams.collect { viewModel.updateBurnInParams(it) } }
                }
            }

        burnInParams.update { current ->
            current.copy(
                translationX = { childViews[burnInLayerId]?.translationX },
                translationY = { childViews[burnInLayerId]?.translationY },
            )
        }

        disposables +=
            view.onLayoutChanged(
                OnLayoutChange(
                    viewModel,
                    blueprintViewModel,
                    smartspaceViewModel,
                    childViews,
                    burnInParams,
                    Logger(blueprintLog, TAG),
                )
            )

        // Ensure the view reflects the final state after items are added.
        fun onViewAdded(id: Int, view: View) {
            when (id) {
                aodPromotedNotificationId ->
                    view.setAodPromotedNotifIsVisible(viewModel.isAodPromotedNotifVisible.value)
                aodNotificationIconContainerId ->
                    view.setAodNotifIconContainerIsVisible(
                        viewModel.isNotifIconContainerVisible.value
                    )
            }
        }

        // Views will be added or removed after the call to bind(). This is needed to avoid many
        // calls to findViewById
        view.setOnHierarchyChangeListener(
            object : OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View, child: View) {
                    childViews.put(child.id, child)
                    onViewAdded(child.id, child)
                }

                override fun onChildViewRemoved(parent: View, child: View) {
                    childViews.remove(child.id)
                }
            }
        )
        disposables += DisposableHandle {
            view.setOnHierarchyChangeListener(null)
            childViews.clear()
        }

        disposables +=
            view.onApplyWindowInsets { _: View, insets: WindowInsets ->
                val insetTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                val insetAmount = insets.getInsetsIgnoringVisibility(insetTypes).top
                if (burnInParams.value.topInset() != insetAmount) {
                    burnInParams.update { current -> current.copy(topInset = { insetAmount }) }
                }
                insets
            }

        return disposables
    }

    private class OnLayoutChange(
        private val viewModel: KeyguardRootViewModel,
        private val blueprintViewModel: KeyguardBlueprintViewModel,
        private val smartspaceViewModel: KeyguardSmartspaceViewModel,
        private val childViews: Map<Int, View>,
        private val burnInParams: MutableStateFlow<BurnInParameters>,
        private val logger: Logger,
    ) : OnLayoutChangeListener {
        var prevTransition: TransitionData? = null

        override fun onLayoutChange(
            view: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int,
        ) {
            val prevSmartspaceVisibility = smartspaceViewModel.bcSmartspaceVisibility.value
            val smartspaceVisibility = childViews[bcSmartspaceId]?.visibility ?: GONE
            val smartspaceVisibilityChanged = prevSmartspaceVisibility != smartspaceVisibility

            // After layout, ensure the notifications are positioned correctly
            childViews[nsslPlaceholderId]?.let { notificationListPlaceholder ->
                // Do not update a second time while a blueprint transition is running
                val transition = blueprintViewModel.currentTransition.value
                val shouldAnimate = transition != null && transition.config.type.animateNotifChanges
                if (prevTransition == transition && shouldAnimate && !smartspaceVisibilityChanged) {
                    logger.w("Skipping onNotificationContainerBoundsChanged during transition")
                    return
                }

                prevTransition = transition
                viewModel.onNotificationContainerBoundsChanged(
                    notificationListPlaceholder.top.toFloat(),
                    notificationListPlaceholder.bottom.toFloat(),
                    animate = (shouldAnimate || smartspaceVisibilityChanged),
                )
            }

            val calculatedMinViewY =
                childViews.entries.fold(Int.MAX_VALUE) { currentMin, (_, view) ->
                    min(
                        currentMin,
                        if (!isUserVisible(view)) {
                            Int.MAX_VALUE
                        } else {
                            view.top
                        },
                    )
                }
            if (calculatedMinViewY != burnInParams.value.minViewY()) {
                burnInParams.update { current -> current.copy(minViewY = { calculatedMinViewY }) }
            }
        }

        private fun isUserVisible(view: View): Boolean {
            return view.id != burnInLayerId &&
                view.visibility == VISIBLE &&
                view.width > 0 &&
                view.height > 0
        }
    }

    private fun View.setAodNotifIconContainerIsVisible(isVisible: AnimatedValue<Boolean>) {
        animate().cancel()
        val animatorListener =
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isVisible.stopAnimating()
                }
            }
        when {
            !isVisible.isAnimating -> {
                visibility =
                    if (isVisible.value) {
                        alpha = 1f
                        VISIBLE
                    } else {
                        alpha = 0f
                        INVISIBLE
                    }
            }

            else -> {
                if (isVisible.value) {
                    CrossFadeHelper.fadeIn(this, animatorListener)
                } else {
                    CrossFadeHelper.fadeOut(this, animatorListener)
                }
            }
        }
    }

    private fun View.setAodPromotedNotifIsVisible(isVisible: AnimatedValue<Boolean>) {
        animate().cancel()
        val animatorListener =
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isVisible.stopAnimating()
                }
            }

        if (isVisible.isAnimating) {
            if (isVisible.value) {
                alpha = 0f
                visibility = VISIBLE
                CrossFadeHelper.fadeIn(this, animatorListener)
            } else {
                CrossFadeHelper.fadeOut(this, animatorListener)
            }
        } else {
            if (isVisible.value) {
                alpha = 1f
                visibility = VISIBLE
            } else {
                // Hide with GONE, not INVISIBLE, so there won't be a redundant bottom
                // margin between the smart space and the shelf.
                alpha = 0f
                visibility = GONE
            }
        }
    }

    private fun MotionEvent.isTouchscreenSource(): Boolean {
        return device?.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) == true
    }

    private val burnInLayerId = R.id.burn_in_layer
    private val sliceViewId = R.id.keyguard_slice_view
    private val aodPromotedNotificationId = AodPromotedNotificationSection.viewId
    private val aodNotificationIconContainerId = R.id.aod_notification_icon_container
    private val largeClockId = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
    private val largeClockDateId = sharedR.id.date_smartspace_view_large
    private val largeClockWeatherId = sharedR.id.weather_smartspace_view_large
    private val bcSmartspaceId = sharedR.id.bc_smartspace_view
    private val smallClockId = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
    private val indicationArea = R.id.keyguard_indication_area
    private val startButton = R.id.start_button
    private val endButton = R.id.end_button
    private val deviceEntryIcon = R.id.device_entry_icon_view
    private val nsslPlaceholderId = R.id.nssl_placeholder
    private val authInteractionProperties = AuthInteractionProperties()
    private val authUiIds = setOf(deviceEntryIcon, indicationArea)

    private const val AOD_ICONS_APPEAR_DURATION: Long = 200
    private const val TAG = "KeyguardRootViewBinder"
}
