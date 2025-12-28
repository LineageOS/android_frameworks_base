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

package com.android.systemui.statusbar.events

import android.annotation.IntRange
import android.content.Context
import android.location.flags.Flags.locationIndicatorsAnimation
import android.location.flags.Flags.locationIndicatorsEnabled
import android.provider.DeviceConfig
import android.provider.DeviceConfig.NAMESPACE_PRIVACY
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Flags
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.privacy.PrivacyChipBuilder
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Listens for system events (battery, privacy, connectivity) and allows listeners to show status
 * bar animations when they happen
 */
@PerDisplaySingleton
class SystemEventCoordinator
@Inject
constructor(
    private val systemClock: SystemClock,
    private val batteryController: BatteryController,
    private val batteryInteractor: BatteryInteractor,
    private val privacyController: PrivacyItemController,
    @DisplayAware private val context: Context,
    @DisplayAware private val scope: CoroutineScope,
    connectedDisplayInteractor: ConnectedDisplayInteractor,
    @SystemEventCoordinatorLog private val logBuffer: LogBuffer,
    @Main private val mainCoroutineContext: CoroutineContext,
) {

    private val tag = "SystemEventCoordinator(displayId=${context.displayId})"

    private val onDisplayConnectedFlow = connectedDisplayInteractor.connectedDisplayAddition
    private val defaultCameraPackageName =
        context.resources.getString(R.string.config_cameraGesturePackage)

    private var connectedDisplayCollectionJob: Job? = null
    private lateinit var scheduler: SystemStatusAnimationScheduler

    fun startObserving() {
        batteryController.addCallback(batteryStateListener)
        privacyController.addCallback(privacyStateListener)
        if (
            !Flags.statusBarIsConnectedDisplayChipControlledByConfig() ||
                context.resources.getBoolean(R.bool.config_isStatusBarConnectedDisplayChipEnabled)
        ) {
            startConnectedDisplayCollection()
        }
    }

    fun stopObserving() {
        batteryController.removeCallback(batteryStateListener)
        privacyController.removeCallback(privacyStateListener)
        connectedDisplayCollectionJob?.cancel()
    }

    fun attachScheduler(s: SystemStatusAnimationScheduler) {
        this.scheduler = s
    }

    fun notifyPluggedIn(@IntRange(from = 0, to = 100) batteryLevel: Int) {
        scope.launch(mainCoroutineContext) {
            scheduler.onStatusEvent(
                BatteryEvent(
                    batteryLevel,
                    batteryInteractor.batteryIconStyle.value,
                    batteryInteractor.showPercentNextToIcon.value,
                )
            )
        }
    }

    fun notifyPrivacyItemsEmpty() {
        scope.launch(mainCoroutineContext) { scheduler.removePersistentDot() }
    }

    fun notifyPrivacyItemsChanged(showAnimation: Boolean = true) {
        // Disabling animation in case that the privacy indicator is implemented as a status bar
        // chip
        val shouldShowAnimation = showAnimation && !Flags.expandedPrivacyIndicatorsOnLargeScreen()
        val event = PrivacyEvent(shouldShowAnimation)
        event.privacyItems = privacyStateListener.currentPrivacyItems
        event.contentDescription = run {
            val items = PrivacyChipBuilder(context, event.privacyItems).joinTypes()
            context.getString(R.string.ongoing_privacy_chip_content_multiple_apps, items)
        }
        scope.launch(mainCoroutineContext) { scheduler.onStatusEvent(event) }
    }

    private fun startConnectedDisplayCollection() {
        val connectedDisplayEvent =
            ConnectedDisplayEvent().apply {
                contentDescription = context.getString(R.string.connected_display_icon_desc)
            }
        connectedDisplayCollectionJob =
            scope.launch(mainCoroutineContext) {
                onDisplayConnectedFlow.collect { scheduler.onStatusEvent(connectedDisplayEvent) }
            }
    }

    private val batteryStateListener =
        object : BatteryController.BatteryStateChangeCallback {
            private var plugged = batteryController.isPluggedIn

            override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
                if (plugged != pluggedIn) {
                    plugged = pluggedIn
                    notifyListeners(level)
                }
            }

            private fun notifyListeners(@IntRange(from = 0, to = 100) batteryLevel: Int) {
                // We only care about the plugged in status
                if (plugged) notifyPluggedIn(batteryLevel)
            }
        }

    private val privacyStateListener =
        object : PrivacyItemController.Callback {
            var currentPrivacyItems = listOf<PrivacyItem>()
            var previousPrivacyItems = listOf<PrivacyItem>()
            var timeLastEmpty = systemClock.elapsedRealtime()
            // Tracks the last time a location privacy indicator was shown for an app. Used to
            // debounce the animation.
            private val appLastLocationUseTime = mutableMapOf<String, Long>()

            override fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>) {
                if (uniqueItemsMatch(privacyItems, currentPrivacyItems)) {
                    return
                } else if (privacyItems.isEmpty()) {
                    previousPrivacyItems = currentPrivacyItems
                    timeLastEmpty = systemClock.elapsedRealtime()
                }

                currentPrivacyItems = privacyItems
                notifyListeners()
            }

            private fun notifyListeners() {
                if (currentPrivacyItems.isEmpty()) {
                    notifyPrivacyItemsEmpty()
                } else {
                    val nonExemptItems = filterOutExemptItems(currentPrivacyItems)

                    val showAnimation =
                        if (nonExemptItems.isEmpty()) {
                            false
                        } else {
                            // At this point, we have items to show. Decide whether to animate.
                            val hasOnlyLocationItems =
                                nonExemptItems.all { it.privacyType == PrivacyType.TYPE_LOCATION }
                            val hasNonLocationItems = !hasOnlyLocationItems

                            val generalDebouncePassed =
                                !uniqueItemsMatch(currentPrivacyItems, previousPrivacyItems) ||
                                    systemClock.elapsedRealtime() - timeLastEmpty >= DEBOUNCE_TIME

                            val shouldAnimateCameraMic =
                                hasNonLocationItems && generalDebouncePassed
                            // For location-only, we show an animation if the flag is enabled. The
                            // 10-minute debounce is handled in filterOutExemptItems.
                            val shouldAnimateLocation =
                                hasOnlyLocationItems &&
                                    locationIndicatorsEnabled() &&
                                    locationIndicatorsAnimation()

                            isChipAnimationEnabled() &&
                                (shouldAnimateCameraMic || shouldAnimateLocation)
                        }
                    notifyPrivacyItemsChanged(showAnimation)

                    // Update the last location usage time for all current location items.
                    val now = systemClock.elapsedRealtime()
                    currentPrivacyItems.forEach {
                        if (
                            it.privacyType == PrivacyType.TYPE_LOCATION &&
                                locationIndicatorsEnabled()
                        ) {
                            appLastLocationUseTime[it.application.packageName] = now
                        }
                    }
                }
            }

            // Return true if the lists contain the same permission groups, used by the same UIDs
            private fun uniqueItemsMatch(one: List<PrivacyItem>, two: List<PrivacyItem>): Boolean {
                return one.map { it.application.uid to it.privacyType.permGroupName }.toSet() ==
                    two.map { it.application.uid to it.privacyType.permGroupName }.toSet()
            }

            private fun filterOutExemptItems(items: List<PrivacyItem>): List<PrivacyItem> {
                val now = systemClock.elapsedRealtime()

                val locationFlagEnabled = locationIndicatorsEnabled()

                // First, filter out camera/mic exemptions
                val afterCameraMicFilter =
                    items.filterNot { item ->
                        val isCameraMicExempt =
                            isCameraOrMicrophoneRequest(item) &&
                                item.application.packageName == defaultCameraPackageName
                        if (isCameraMicExempt) {
                            logBuffer.log(
                                tag,
                                LogLevel.DEBUG,
                                {
                                    str1 = item.application.packageName
                                    str2 = item.privacyType.permGroupName
                                },
                                {
                                    "Privacy item from default camera ($str1) is exempt " +
                                        "from chip animation. Permission group=$str2"
                                },
                            )
                        }
                        isCameraMicExempt
                    }

                // Now, if the location flag is enabled and only location items remain,
                // apply the location-specific debounce
                val locationOnly =
                    afterCameraMicFilter.all { it.privacyType == PrivacyType.TYPE_LOCATION }
                if (locationFlagEnabled && locationOnly) {
                    return afterCameraMicFilter.filterNot { item ->
                        val lastAnimationTime = appLastLocationUseTime[item.application.packageName]
                        if (
                            lastAnimationTime != null &&
                                now - lastAnimationTime < DEBOUNCE_TIME_LOCATION
                        ) {
                            logBuffer.log(
                                tag,
                                LogLevel.DEBUG,
                                {
                                    str1 = item.application.packageName
                                    str2 = item.privacyType.permGroupName
                                },
                                {
                                    "Privacy item ($str1) is exempt " +
                                        "from chip animation due to debounce. Permission group=$str2"
                                },
                            )
                            return@filterNot true
                        }
                        false
                    }
                }

                return afterCameraMicFilter
            }

            private fun isCameraOrMicrophoneRequest(item: PrivacyItem): Boolean {
                return item.privacyType.let {
                    it == PrivacyType.TYPE_CAMERA || it == PrivacyType.TYPE_MICROPHONE
                }
            }

            private fun isChipAnimationEnabled(): Boolean {
                val defaultValue =
                    context.resources.getBoolean(R.bool.config_enablePrivacyChipAnimation)
                return DeviceConfig.getBoolean(
                    NAMESPACE_PRIVACY,
                    CHIP_ANIMATION_ENABLED,
                    defaultValue,
                )
            }
        }

    @VisibleForTesting fun getPrivacyStateListener() = privacyStateListener
}

private const val DEBOUNCE_TIME = 3000L
@VisibleForTesting const val DEBOUNCE_TIME_LOCATION = 600_000L // 10 minutes.
private const val CHIP_ANIMATION_ENABLED = "privacy_chip_animation_enabled"
