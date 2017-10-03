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

package com.android.systemui.shade

import android.content.Context
import android.hardware.display.AmbientDisplayConfiguration
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import com.android.systemui.Dumpable
import com.android.systemui.dock.DockManager
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.FalsingManager.LOW_PENALTY
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent
import com.android.systemui.tuner.TunerService
import com.android.systemui.tuner.TunerService.Tunable
import lineageos.providers.LineageSettings;
import java.io.PrintWriter
import javax.inject.Inject

/**
 * If tap and/or double tap to wake is enabled, this gestureListener will wake the display on
 * tap/double tap when the device is pulsing (AoD2) or transitioning to AoD. Taps are gated by the
 * proximity sensor and falsing manager.
 *
 * Touches go through the [NotificationShadeWindowViewController] when the device is dozing but the
 * screen is still ON and not in the true AoD display state. When the device is in the true AoD
 * display state, wake-ups are handled by [com.android.systemui.doze.DozeSensors].
 */
@CentralSurfacesComponent.CentralSurfacesScope
class PulsingGestureListener @Inject constructor(
        private val notificationShadeWindowView: NotificationShadeWindowView,
        private val falsingManager: FalsingManager,
        private val dockManager: DockManager,
        private val centralSurfaces: CentralSurfaces,
        private val ambientDisplayConfiguration: AmbientDisplayConfiguration,
        private val statusBarStateController: StatusBarStateController,
        private val powerManager: PowerManager,
        tunerService: TunerService,
        dumpManager: DumpManager,
        context: Context
) : GestureDetector.SimpleOnGestureListener(), Dumpable {
    private var doubleTapEnabled = false
    private var singleTapEnabled = false
    private var doubleTapEnabledNative = false

    companion object {
        internal val DOUBLE_TAP_SLEEP_GESTURE =
            "lineagesystem:" + LineageSettings.System.DOUBLE_TAP_SLEEP_GESTURE
    }
    private var doubleTapToSleepEnabled = false
    private val quickQsOffsetHeight: Int

    init {
        val tunable = Tunable { key: String?, value: String? ->
            when (key) {
                Settings.Secure.DOUBLE_TAP_TO_WAKE ->
                    doubleTapEnabledNative = TunerService.parseIntegerSwitch(value, false)
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE ->
                    doubleTapEnabled = ambientDisplayConfiguration.doubleTapGestureEnabled(
                            UserHandle.USER_CURRENT)
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE ->
                    singleTapEnabled = ambientDisplayConfiguration.tapGestureEnabled(
                            UserHandle.USER_CURRENT)
                DOUBLE_TAP_SLEEP_GESTURE ->
                    doubleTapToSleepEnabled = TunerService.parseIntegerSwitch(value, true)
            }
        }
        tunerService.addTunable(tunable,
                Settings.Secure.DOUBLE_TAP_TO_WAKE,
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE,
                DOUBLE_TAP_SLEEP_GESTURE)

        dumpManager.registerDumpable(this)

        quickQsOffsetHeight = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height)
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        if (statusBarStateController.isDozing &&
                singleTapEnabled &&
                !dockManager.isDocked &&
                !falsingManager.isProximityNear &&
                !falsingManager.isFalseTap(LOW_PENALTY)
        ) {
            centralSurfaces.wakeUpIfDozing(
                    SystemClock.uptimeMillis(),
                    notificationShadeWindowView,
                    "PULSING_SINGLE_TAP")
            return true
        }
        return false
    }

    /**
     * Receives [MotionEvent.ACTION_DOWN], [MotionEvent.ACTION_MOVE], and [MotionEvent.ACTION_UP]
     * motion events for a double tap.
     */
    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        // React to the [MotionEvent.ACTION_UP] event after double tap is detected. Falsing
        // checks MUST be on the ACTION_UP event.
        if (e.actionMasked == MotionEvent.ACTION_UP && !falsingManager.isFalseDoubleTap) {
            if (statusBarStateController.isDozing &&
                (doubleTapEnabled || singleTapEnabled || doubleTapEnabledNative) &&
                !falsingManager.isProximityNear
            ) {
                centralSurfaces.wakeUpIfDozing(
                        SystemClock.uptimeMillis(),
                        notificationShadeWindowView,
                        "PULSING_DOUBLE_TAP")
                return true
            } else if (!statusBarStateController.isDozing &&
                doubleTapToSleepEnabled &&
                e.getY() < quickQsOffsetHeight
            ) {
                powerManager.goToSleep(e.getEventTime())
                return true
            }
        }
        return false
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("singleTapEnabled=$singleTapEnabled")
        pw.println("doubleTapEnabled=$doubleTapEnabled")
        pw.println("doubleTapEnabledNative=$doubleTapEnabledNative")
        pw.println("doubleTapToSleepEnabled=$doubleTapToSleepEnabled")
        pw.println("isDocked=${dockManager.isDocked}")
        pw.println("isProxCovered=${falsingManager.isProximityNear}")
    }
}
