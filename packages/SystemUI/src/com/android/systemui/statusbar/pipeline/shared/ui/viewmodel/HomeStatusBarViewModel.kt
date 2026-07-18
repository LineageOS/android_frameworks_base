/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import android.annotation.ColorInt
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.compose.runtime.getValue
import com.android.app.tracing.FlowTracing.traceEach
import com.android.app.tracing.TrackGroupUtils.trackGroup
import com.android.systemui.Flags
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.desktop.domain.interactor.DesktopInteractor
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayId
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.lifecycle.Activatable
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.MessageInitializer
import com.android.systemui.log.core.MessagePrinter
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.display.domain.interactor.ShadeExpansionTargetDisplayInteractor
import com.android.systemui.shade.domain.interactor.DisplayAwareShadeElementToggleInteractor
import com.android.systemui.shade.domain.interactor.NotificationShadeElement
import com.android.systemui.shade.domain.interactor.QSShadeElement
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.MediaProjectionStopDialogModel
import com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.ShareToAppChipViewModel
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModel
import com.android.systemui.statusbar.chips.uievents.StatusBarChipsUiEventLogger
import com.android.systemui.statusbar.domain.interactor.ScrollToTopInteractor
import com.android.systemui.statusbar.events.domain.interactor.SystemStatusEventAnimationInteractor
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.Idle
import com.android.systemui.statusbar.layout.ui.viewmodel.AppHandlesViewModel
import com.android.systemui.statusbar.layout.ui.viewmodel.StatusBarBoundsViewModel
import com.android.systemui.statusbar.layout.ui.viewmodel.StatusBarContentInsetsViewModel
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.notification.icon.domain.interactor.StatusBarNotificationIconsInteractor
import com.android.systemui.statusbar.notification.shared.StatusBarHeadline
import com.android.systemui.statusbar.notification.domain.model.TopPinnedState
import com.android.systemui.statusbar.phone.domain.interactor.DarkIconInteractor
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.phone.domain.interactor.LightsOutInteractor
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.HomeStatusBarIconBlockListInteractor
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.HomeStatusBarInteractor
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.StatusBarVisibilityInteractor
import com.android.systemui.statusbar.pipeline.shared.ui.model.ChipsVisibilityModel
import com.android.systemui.statusbar.pipeline.shared.ui.model.SystemInfoCombinedVisibilityModel
import com.android.systemui.statusbar.pipeline.shared.ui.model.VisibilityModel
import com.android.systemui.statusbar.policy.domain.interactor.DeviceProvisioningInteractor
import com.android.systemui.statusbar.quickactions.ime.domain.interactor.ImeIndicatorChipInteractor
import com.android.systemui.statusbar.quickactions.popups.StatusBarPopupChips
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupChipsViewModel
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import com.android.systemui.statusbar.systemstatusicons.domain.interactor.SystemStatusIconBlocklistInteractor
import com.android.systemui.statusbar.systemstatusicons.ui.viewmodel.SystemStatusIconsViewModel
import com.android.systemui.user.domain.interactor.UserLogoutInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A view model that manages the visibility of the [CollapsedStatusBarFragment] based on the device
 * state.
 *
 * Right now, most of the status bar visibility management is actually in
 * [CollapsedStatusBarFragment.calculateInternalModel], which uses
 * [CollapsedStatusBarFragment.shouldHideNotificationIcons] and
 * [StatusBarHideIconsForBouncerManager]. We should move those pieces of logic to this class instead
 * so that it's all in one place and easily testable outside of the fragment.
 */
interface HomeStatusBarViewModel : Activatable {
    /** Factory for the unified (percent embedded) battery view model */
    val unifiedBatteryViewModel: BatteryViewModel.BasedOnUserSetting.Factory

    /** Factory to create the view model for system status icons */
    val systemStatusIconsViewModelFactory: SystemStatusIconsViewModel.Factory

    /**
     * Factory to create the view model for storing bounds of child views in/around the status bar.
     */
    val statusBarBoundsViewModelFactory: StatusBarBoundsViewModel.Factory

    /**
     * Factory to create the view model for storing bounds of app handles overlapping with the
     * status bar.
     */
    val appHandlesViewModelFactory: AppHandlesViewModel.Factory

    /**
     * True if the device is currently transitioning from lockscreen to occluded and false
     * otherwise.
     */
    val isTransitioningFromLockscreenToOccluded: StateFlow<Boolean>

    /** Emits whenever a transition from lockscreen to dream has started. */
    val transitionFromLockscreenToDreamStartedEvent: Flow<Unit>

    /**
     * The current media projection stop dialog to be shown, or
     * `MediaProjectionStopDialogModel.Hidden` if no dialog is visible.
     */
    val mediaProjectionStopDialogDueToCallEndedState: StateFlow<MediaProjectionStopDialogModel>

    /** All supported activity chips, whether they are currently active or not. */
    val ongoingActivityChips: ChipsVisibilityModel

    /**
     * Invoked each time a chip's on-screen bounds have changed.
     *
     * @param key if [Flags.statusBarHunAnimationCall()] is enabled, then the key is the raw
     *   notification key without any prefixes. If the flag is disabled, then the key is the chip's
     *   full key, possibly including prefixes or non-notification keys.
     */
    fun onChipBoundsChanged(key: String, bounds: RectF)

    /** Notifies that the status bar was tapped. */
    fun onStatusBarTap(eventX: Float)

    /** Notifies that there was a long press on the status bar. */
    fun onStatusBarLongPressed()

    /** Notifies that the clock container was clicked. */
    fun onClockClicked()

    /** Notifies that the status bar's blank space was clicked. */
    fun onSpacerClicked()

    /** Notifies that the system icons container was clicked. */
    fun onQuickSettingsChipClicked()

    /** Notifies that the notification icons container was clicked. */
    fun onNotificationIconChipClicked()

    /** Notifies that there is an intent to start expansion of a shade */
    fun onShadeExpansionIntent(eventX: Float, statusBarWidth: Int, isConsumed: Boolean)

    /** Whether the QS Chip should be highlighted. */
    val isQuickSettingsChipHighlighted: Boolean

    /** Whether the Notifications chip should be highlighted. */
    val isNotificationsChipHighlighted: Boolean

    /** View model for the carrier name that may show in the status bar based on carrier config */
    val operatorNameViewModel: StatusBarOperatorNameViewModel

    /** The popup chips that should be shown on the right-hand side of the status bar. */
    val popupChips: List<QuickActionChipModel>

    /**
     * True if the status bar should be visible.
     *
     * TODO(b/364360986): Once the is<SomeChildView>Visible flows are fully enabled, we shouldn't
     *   need this flow anymore.
     */
    val isHomeStatusBarAllowed: StateFlow<Boolean>

    /** True if the operator name view is not hidden due to HUN or other visibility state */
    val shouldShowOperatorNameView: Flow<Boolean>
    val isClockVisible: Flow<VisibilityModel>
    val isNotificationIconContainerVisible: Flow<VisibilityModel>
    val hideStartSideContentForHeadsUp: Flow<Boolean>

    /**
     * Pair of (system info visibility, event animation state). The animation state can be used to
     * respond to the system event chip animations. In all cases, system info visibility correctly
     * models the View.visibility for the system info area
     */
    val systemInfoCombinedVis: StateFlow<SystemInfoCombinedVisibilityModel>

    /** Which icons to block from the home status bar */
    val iconBlockList: Flow<List<String>>

    /** Interactor that provides the set of blocked icon slots */
    val systemStatusIconBlockListInteractor: SystemStatusIconBlocklistInteractor

    /** This status bar's current content area for the given rotation in absolute bounds. */
    val contentArea: Flow<Rect>

    /**
     * Apps can request a low profile mode [android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE] where
     * status bar and navigation icons dim. In this mode, a notification dot appears where the
     * notification icons would appear if they would be shown outside of this mode.
     *
     * This flow tells when to show or hide the notification dot in the status bar to indicate
     * whether there are notifications when the device is in
     * [android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE].
     */
    val areNotificationsLightsOut: Flow<Boolean>

    /**
     * A flow of [StatusBarTintColor], a functional interface that will allow a view to calculate
     * its correct tint depending on location
     */
    val areaTint: Flow<StatusBarTintColor>

    /** [IsAreaDark] applicable for this status bar's display and content area */
    val areaDark: IsAreaDark

    /** True if the desktop status bar is enabled. */
    val useDesktopStatusBar: Boolean

    /** Emits `true` whenever there is at least one status bar notification. */
    val hasStatusBarNotifications: Boolean

    /** True if the user can click on the notifications chip. */
    val isNotificationsChipClickable: Boolean

    /** True if the user can click on the quick settings chip. */
    val isQuickSettingsChipClickable: Boolean

    /** True if the sign out button is currently visible. */
    val isSignOutButtonVisible: Boolean

    /** Notifies that the current user should be signed out. */
    fun onSignOut()

    /** Interface for the assisted factory, to allow for providing a fake in tests */
    interface HomeStatusBarViewModelFactory {
        fun create(): HomeStatusBarViewModel
    }
}

class HomeStatusBarViewModelImpl
@AssistedInject
constructor(
    @field:DisplayId @DisplayId private val thisDisplayId: Int,
    override val unifiedBatteryViewModel: BatteryViewModel.BasedOnUserSetting.Factory,
    override val systemStatusIconsViewModelFactory: SystemStatusIconsViewModel.Factory,
    override val statusBarBoundsViewModelFactory: StatusBarBoundsViewModel.Factory,
    override val appHandlesViewModelFactory: AppHandlesViewModel.Factory,
    loggerFactory: LogBufferFactory,
    tableLoggerFactory: TableLogBufferFactory,
    @DisplayAware private val resources: Resources,
    @DisplayAware homeStatusBarInteractor: HomeStatusBarInteractor,
    homeStatusBarIconBlockListInteractor: HomeStatusBarIconBlockListInteractor,
    lightsOutInteractor: LightsOutInteractor,
    notificationsInteractor: ActiveNotificationsInteractor,
    desktopInteractor: DesktopInteractor,
    darkIconInteractor: DarkIconInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    keyguardInteractor: KeyguardInteractor,
    statusBarNotificationIconsInteractor: StatusBarNotificationIconsInteractor,
    @DisplayAware statusBarVisibilityInteractor: StatusBarVisibilityInteractor,
    override val operatorNameViewModel: StatusBarOperatorNameViewModel,
    sceneInteractor: SceneInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val shadeExpansionTargetDisplayInteractor: ShadeExpansionTargetDisplayInteractor,
    private val displayAwareShadeElementToggleInteractor: DisplayAwareShadeElementToggleInteractor,
    private val qsShadeElement: QSShadeElement,
    private val notificationElement: NotificationShadeElement,
    private val imeIndicatorChipInteractor: ImeIndicatorChipInteractor,
    shareToAppChipViewModel: ShareToAppChipViewModel,
    @DisplayAware private val ongoingActivityChipsViewModel: OngoingActivityChipsViewModel,
    statusBarPopupChipsViewModelFactory: StatusBarPopupChipsViewModel.Factory,
    @DisplayAware animations: SystemStatusEventAnimationInteractor,
    @DisplayAware statusBarContentInsetsViewModel: StatusBarContentInsetsViewModel,
    @DisplayAware bgDisplayScope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    private val uiEventLogger: StatusBarChipsUiEventLogger,
    deviceProvisioningInteractor: DeviceProvisioningInteractor,
    private val userLogoutInteractor: UserLogoutInteractor,
    private val scrollToTopInteractor: ScrollToTopInteractor,
    private val headsUpNotificationInteractor: HeadsUpNotificationInteractor,
) : HomeStatusBarViewModel, HydratedActivatable(enableEnqueuedActivations = true) {

    val logger = loggerFactory.getOrCreate(logBufferName(thisDisplayId), 60)
    val tableLogger = tableLoggerFactory.getOrCreate(tableLogBufferName(thisDisplayId), 200)

    private val statusBarPopupChips by lazy {
        statusBarPopupChipsViewModelFactory.create(thisDisplayId)
    }

    override val systemStatusIconBlockListInteractor: SystemStatusIconBlocklistInteractor =
        homeStatusBarIconBlockListInteractor

    override val isTransitioningFromLockscreenToOccluded: StateFlow<Boolean> =
        if (SceneContainerFlag.isEnabled) {
                flowOf(false)
            } else {
                keyguardTransitionInteractor
                    .isInTransition(Edge.create(from = LOCKSCREEN, to = OCCLUDED))
                    .distinctUntilChanged()
                    .logDiffsForTable(
                        tableLogBuffer = tableLogger,
                        columnName = COL_LOCK_TO_OCCLUDED,
                        initialValue = false,
                    )
            }
            .stateIn(bgDisplayScope, SharingStarted.WhileSubscribed(), initialValue = false)

    override val transitionFromLockscreenToDreamStartedEvent: Flow<Unit> =
        keyguardTransitionInteractor
            .transition(Edge.create(from = LOCKSCREEN, to = DREAMING))
            .filter { it.transitionState == TransitionState.STARTED }
            .map {}
            .flowOn(bgDispatcher)

    override val mediaProjectionStopDialogDueToCallEndedState =
        shareToAppChipViewModel.stopDialogToShow

    override val popupChips
        get() = statusBarPopupChips.shownQuickActionChips

    override val areNotificationsLightsOut: Flow<Boolean> =
        combine(
                notificationsInteractor.areAnyNotificationsPresent,
                lightsOutInteractor.isLowProfile(thisDisplayId) ?: flowOf(false),
            ) { hasNotifications, isLowProfile ->
                hasNotifications && isLowProfile
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLogger,
                columnName = COL_NOTIF_LIGHTS_OUT,
                initialValue = false,
            )
            .flowOn(bgDispatcher)

    override val areaTint: Flow<StatusBarTintColor> =
        darkIconInteractor
            .darkState(thisDisplayId)
            .map { (areas: Collection<Rect>, tint: Int) ->
                StatusBarTintColor { viewBounds: Rect ->
                    if (DarkIconDispatcher.isInAreas(areas, viewBounds)) {
                        tint
                    } else {
                        DarkIconDispatcher.DEFAULT_ICON_TINT
                    }
                }
            }
            .conflate()
            .distinctUntilChanged()
            .flowOn(bgDispatcher)

    override val areaDark: IsAreaDark by
        darkIconInteractor
            .isAreaDark(thisDisplayId)
            .hydratedStateOf(initialValue = IsAreaDark { true })

    override val useDesktopStatusBar: Boolean by
        desktopInteractor.useDesktopStatusBar.hydratedStateOf(initialValue = false)

    override val isQuickSettingsChipHighlighted: Boolean by
        combine(
                shadeInteractor.isQsExpanded,
                statusBarVisibilityInteractor.isShadeWindowOnThisDisplay,
            ) { isQsExpanded, isShadeOnThisDisplay ->
                isQsExpanded && isShadeOnThisDisplay
            }
            .hydratedStateOf(initialValue = false)

    override val isNotificationsChipHighlighted: Boolean by
        combine(
                shadeInteractor.isNotificationsExpanded,
                statusBarVisibilityInteractor.isShadeWindowOnThisDisplay,
            ) { isNotificationsExpanded, isShadeOnThisDisplay ->
                isNotificationsExpanded && isShadeOnThisDisplay
            }
            .hydratedStateOf(initialValue = false)

    override val hasStatusBarNotifications: Boolean by
        statusBarNotificationIconsInteractor.hasStatusBarNotifications.hydratedStateOf(
            initialValue = false
        )

    override val isNotificationsChipClickable: Boolean by
        deviceProvisioningInteractor.isDeviceProvisioned.hydratedStateOf(initialValue = false)

    override val isQuickSettingsChipClickable: Boolean by
        deviceProvisioningInteractor.isDeviceProvisioned.hydratedStateOf(initialValue = false)

    override val isHomeStatusBarAllowed = statusBarVisibilityInteractor.isHomeStatusBarAllowed

    private val shadeInvocationSplitRatio: Float =
        resources.getFloat(R.dimen.config_invocationGestureSplitRatio)

    override val shouldShowOperatorNameView: Flow<Boolean> =
        combine(
                statusBarVisibilityInteractor.shouldHomeStatusBarBeVisible,
                homeStatusBarInteractor.visibilityViaDisableFlags,
                homeStatusBarInteractor.shouldShowOperatorName,
            ) { shouldStatusBarBeVisible, visibilityViaDisableFlags, shouldShowOperator ->
                shouldStatusBarBeVisible &&
                    visibilityViaDisableFlags.isSystemInfoAllowed &&
                    shouldShowOperator
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLogger,
                columnName = COL_SHOW_OPERATOR_NAME,
                initialValue = false,
            )
            .flowOn(bgDispatcher)

    private val chipsVisibilityModel: StateFlow<ChipsVisibilityModel> =
        if (StatusBarHeadline.isEnabled) {
                flowOf(
                    ChipsVisibilityModel(
                        chips = MultipleOngoingActivityChipsModel(),
                        areChipsAllowed = false,
                    )
                )
            } else {
                combine(
                        ongoingActivityChipsViewModel.chips,
                        statusBarVisibilityInteractor.canShowOngoingActivityChips,
                    ) { chips, canShow ->
                        ChipsVisibilityModel(chips, areChipsAllowed = canShow)
                    }
                    .traceEach(trackGroup(TRACK_GROUP, "chips"), logcat = true) {
                        "Chips[allowed=${it.areChipsAllowed} numChips=${it.chips.active.size}]"
                    }
            }
            .stateIn(
                bgDisplayScope,
                SharingStarted.WhileSubscribed(),
                ChipsVisibilityModel(
                    chips = MultipleOngoingActivityChipsModel(),
                    areChipsAllowed = false,
                ),
            )

    override val ongoingActivityChips: ChipsVisibilityModel by
        chipsVisibilityModel.hydratedStateOf(
            initialValue =
                ChipsVisibilityModel(
                    chips = MultipleOngoingActivityChipsModel(),
                    areChipsAllowed = false,
                )
        )

    override fun onChipBoundsChanged(key: String, bounds: RectF) {
        ongoingActivityChipsViewModel.onChipBoundsChanged(key, bounds)
    }

    override fun onStatusBarTap(eventX: Float) {
        logger.d({ double1 = eventX.toDouble() }, { "Statusbar Tap at x=$double1" })
        scrollToTopInteractor.onScrollToTop(thisDisplayId, eventX.toInt())
    }

    override fun onClockClicked() {
        // ImeIndicator is a QuickActions LaunchChip (not PopupChip), with no associated popups. On
        // click it launches the InputMethodPicker (SystemUI Dialog, via InputMethodManager API)
        // that appears as if it were a StatusBar popup, thus is required to mimic the behaviour of
        // StatusBar popups. In particular, click on StatusBar clock should hide InputMethodPicker
        // for consistency, like how they currently trigger the open StatusBar popup (if any) to
        // dismiss (by virtue of the click falling outside the bounds of that popup).
        // TODO: b/478352392 - Consider achieving the same effect by hiding InputMethodPicker upon
        // "out-of-bounds click" instead, via SceneContainerViewModel.onEmptySpaceMotionEvent().
        imeIndicatorChipInteractor.hideInputMethodPicker(thisDisplayId)
    }

    override fun onSpacerClicked() {
        // ImeIndicator is a QuickActions LaunchChip (not PopupChip), with no associated popups. On
        // click it launches the InputMethodPicker (SystemUI Dialog, via InputMethodManager API)
        // that appears as if it were a StatusBar popup, thus is required to mimic the behaviour of
        // StatusBar popups. In particular, click on StatusBar spacer should hide InputMethodPicker
        // for consistency, like how they currently trigger the open StatusBar popup (if any) to
        // dismiss (by virtue of the click falling outside the bounds of that popup).
        // TODO: b/478352392 - Consider achieving the same effect by hiding InputMethodPicker upon
        // "out-of-bounds click" instead, via SceneContainerViewModel.onEmptySpaceMotionEvent().
        imeIndicatorChipInteractor.hideInputMethodPicker(thisDisplayId)
    }

    override fun onStatusBarLongPressed() {
        shadeInteractor.expandNotificationsShade(
            loggingReason = "HomeStatusBarViewModel.onStatusBarLongPressed"
        )
    }

    override fun onQuickSettingsChipClicked() {
        displayAwareShadeElementToggleInteractor.toggleShadeElement(qsShadeElement, thisDisplayId)
    }

    override fun onNotificationIconChipClicked() {
        displayAwareShadeElementToggleInteractor.toggleShadeElement(
            notificationElement,
            thisDisplayId,
        )
    }

    override fun onShadeExpansionIntent(eventX: Float, statusBarWidth: Int, isConsumed: Boolean) {
        // The event was already consumed by a child (like a chip). So we don't want to expand the
        // shade, but we still update the target display to ensure the shade window becomes
        // available on the active display.
        if (isConsumed) {
            shadeExpansionTargetDisplayInteractor.updateShadeDisplayIfNeeded(thisDisplayId)
            return
        }

        val isRtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        shadeExpansionTargetDisplayInteractor.setExpansionIntentFromStatusBarEvent(
            eventX = eventX,
            displayId = thisDisplayId,
            statusBarWidth = statusBarWidth,
            shadeInvocationSplitRatio = shadeInvocationSplitRatio,
            isRtl = isRtl,
        )
    }

    private val hasOngoingActivityChips =
        chipsVisibilityModel.map { it.chips.active.any { chip -> !chip.isHidden } }

    private val isAnyChipVisible =
        combine(
            hasOngoingActivityChips,
            statusBarVisibilityInteractor.canShowOngoingActivityChips,
        ) { hasChips, canShowChips ->
            hasChips && canShowChips
        }

    override val hideStartSideContentForHeadsUp: Flow<Boolean> =
        headsUpNotificationInteractor.statusBarHeadsUpState.map {
            it is TopPinnedState.Pinned
        }

    override val isClockVisible: Flow<VisibilityModel> =
        combine(
                statusBarVisibilityInteractor.shouldHomeStatusBarBeVisible,
                homeStatusBarInteractor.visibilityViaDisableFlags,
            ) { shouldStatusBarBeVisible, visibilityViaDisableFlags ->
                val showClock = shouldStatusBarBeVisible && visibilityViaDisableFlags.isClockAllowed
                // Always use View.GONE here, so that we do not get stray spaces
                VisibilityModel(showClock.toVisibleOrGone(), visibilityViaDisableFlags.animate)
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLogger,
                columnPrefix = COL_PREFIX_CLOCK,
                initialValue = VisibilityModel(false.toVisibleOrGone(), false),
            )
            .flowOn(bgDispatcher)

    override val isNotificationIconContainerVisible: Flow<VisibilityModel> =
        combine(
                statusBarVisibilityInteractor.shouldHomeStatusBarBeVisible,
                isAnyChipVisible,
                homeStatusBarInteractor.visibilityViaDisableFlags,
            ) { shouldStatusBarBeVisible, anyChipVisible, visibilityViaDisableFlags ->
                val showNotificationIconContainer =
                    if (anyChipVisible) {
                        false
                    } else {
                        shouldStatusBarBeVisible &&
                            visibilityViaDisableFlags.areNotificationIconsAllowed
                    }
                VisibilityModel(
                    showNotificationIconContainer.toVisibleOrGone(),
                    visibilityViaDisableFlags.animate,
                )
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLogger,
                columnPrefix = COL_PREFIX_NOTIF_CONTAINER,
                initialValue = VisibilityModel(false.toVisibleOrInvisible(), false),
            )
            .flowOn(bgDispatcher)

    private val isSystemInfoVisible =
        combine(
            statusBarVisibilityInteractor.shouldHomeStatusBarBeVisible,
            homeStatusBarInteractor.visibilityViaDisableFlags,
        ) { shouldStatusBarBeVisible, visibilityViaDisableFlags ->
            val showSystemInfo =
                shouldStatusBarBeVisible && visibilityViaDisableFlags.isSystemInfoAllowed
            VisibilityModel(showSystemInfo.toVisibleOrGone(), visibilityViaDisableFlags.animate)
        }

    override val systemInfoCombinedVis =
        combine(isSystemInfoVisible, animations.animationState) { sysInfoVisible, animationState ->
                SystemInfoCombinedVisibilityModel(sysInfoVisible, animationState)
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLogger,
                columnPrefix = COL_PREFIX_SYSTEM_INFO,
                initialValue =
                    SystemInfoCombinedVisibilityModel(VisibilityModel(View.VISIBLE, false), Idle),
            )
            .stateIn(
                bgDisplayScope,
                SharingStarted.WhileSubscribed(),
                SystemInfoCombinedVisibilityModel(VisibilityModel(View.VISIBLE, false), Idle),
            )

    override val iconBlockList: Flow<List<String>> =
        homeStatusBarIconBlockListInteractor.iconBlockList.flowOn(bgDispatcher)

    override val contentArea: Flow<Rect> =
        statusBarContentInsetsViewModel.contentArea.flowOn(bgDispatcher)

    override val isSignOutButtonVisible: Boolean by
        if (
                (Flags.signOutButtonOnKeyguardStatusBar() ||
                    Flags.signOutButtonOnKeyguardStatusBar2()) &&
                    keyguardInteractor.isSignOutButtonOnStatusBarEnabled
            ) {
                combine(
                    userLogoutInteractor.isLogoutToSystemUserEnabled,
                    deviceProvisioningInteractor.isDeviceProvisioned,
                    sceneInteractor.currentScene,
                ) { isLogoutToSystemUserEnabled, isDeviceProvisioned, currentScene ->
                    isLogoutToSystemUserEnabled &&
                        isDeviceProvisioned &&
                        currentScene == Scenes.Lockscreen
                }
            } else {
                flowOf(false)
            }
            .hydratedStateOf(initialValue = false)

    override fun onSignOut() {
        logger.d { "onSignOut" }
        enqueueOnActivatedScope { userLogoutInteractor.logOutToSystemUser() }
    }

    @View.Visibility
    private fun Boolean.toVisibleOrGone(): Int {
        return if (this) View.VISIBLE else View.GONE
    }

    // Similar to the above, but uses INVISIBLE in place of GONE
    @View.Visibility
    private fun Boolean.toVisibleOrInvisible(): Int = if (this) View.VISIBLE else View.INVISIBLE

    override suspend fun onActivated() {
        coroutineScope {
            if (StatusBarPopupChips.isEnabled) {
                launch { statusBarPopupChips.activate() }
            }
            launch { uiEventLogger.hydrateUiEventLogging(chipsFlow = chipsVisibilityModel) }
            awaitCancellation()
        }
    }

    /** Inject this to create the display-dependent view model */
    @AssistedFactory
    interface HomeStatusBarViewModelFactoryImpl :
        HomeStatusBarViewModel.HomeStatusBarViewModelFactory {
        override fun create(): HomeStatusBarViewModelImpl
    }

    companion object {
        private const val COL_LOCK_TO_OCCLUDED = "Lock->Occluded"
        private const val COL_NOTIF_LIGHTS_OUT = "notifLightsOut"
        private const val COL_SHOW_OPERATOR_NAME = "showOperatorName"
        private const val COL_PREFIX_CLOCK = "clock"
        private const val COL_PREFIX_NOTIF_CONTAINER = "notifContainer"
        private const val COL_PREFIX_SYSTEM_INFO = "systemInfo"

        private const val TAG = "HomeStatusBarViewModel"

        private const val TRACK_GROUP = "StatusBar"

        private fun logBufferName(displayId: Int) = "HomeStatusBarViewModelLog[$displayId]"

        private fun tableLogBufferName(displayId: Int) =
            "HomeStatusBarViewModelTableLog[$displayId]"

        private fun LogBuffer.d(initializer: MessageInitializer = {}, printer: MessagePrinter) =
            this.log(TAG, LogLevel.DEBUG, initializer, printer)
    }
}

/** Lookup the color for a given view in the status bar */
fun interface StatusBarTintColor {
    @ColorInt fun tint(viewBounds: Rect): Int
}
