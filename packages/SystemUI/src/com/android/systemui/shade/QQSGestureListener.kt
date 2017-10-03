/*
 * Copyright (C) 2023 The LineageOS Project
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
import android.database.ContentObserver
import android.os.PowerManager
import android.view.GestureDetector
import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.CentralSurfaces
import lineageos.providers.LineageSettings
import javax.inject.Inject

@SysUISingleton
class QQSGestureListener @Inject constructor(
        private val context: Context,
        private val falsingManager: FalsingManager,
        private val powerManager: PowerManager,
        private val statusBarStateController: StatusBarStateController,
        private val centralSurfaces: CentralSurfaces,
) : GestureDetector.SimpleOnGestureListener() {

    private var doubleTapToSleepEnabled = false
    private val quickQsOffsetHeight: Int

    init {
        val contentObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                doubleTapToSleepEnabled = LineageSettings.System.getInt(
                        context.contentResolver, LineageSettings.System.DOUBLE_TAP_SLEEP_GESTURE,
                        if (context.resources.getBoolean(org.lineageos.platform.internal.
                                R.bool.config_dt2sGestureEnabledByDefault)) 1 else 0) != 0
            }
        }
        context.contentResolver.registerContentObserver(
                LineageSettings.System.getUriFor(LineageSettings.System.DOUBLE_TAP_SLEEP_GESTURE),
                false, contentObserver)
        contentObserver.onChange(true)

        quickQsOffsetHeight = context.resources.getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height)
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        // Go to sleep when double tapping the QQS status bar
        // or lockscreen (keyguard showing, but not bouncer)
        if (
            e.actionMasked == MotionEvent.ACTION_UP &&
                !statusBarStateController.isDozing &&
                doubleTapToSleepEnabled &&
                (e.getY() < quickQsOffsetHeight ||
                    statusBarStateController.getState() == StatusBarState.KEYGUARD &&
                        !centralSurfaces.isBouncerShowing()) &&
                !falsingManager.isFalseDoubleTap
        ) {
            powerManager.goToSleep(e.getEventTime())
            return true
        }
        return false
    }

}
