/*
 * Copyright (C) 2021 The ProtonAOSP Project
 * Copyright (C) 2022 The LineageOS Project
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

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.ServiceManager
import android.view.Surface
import com.android.systemui.biometrics.AuthController
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.Dependency
import com.google.hardware.pixel.display.IDisplay
import java.util.concurrent.Executor

class PixelUdfpsHbmProvider constructor(
    private val context: Context
) : UdfpsHbmProvider, IBinder.DeathRecipient, DisplayManager.DisplayListener {

    private val authController = Dependency.get(AuthController::class.java)
    private val bgExecutor = Dependency.get(Dependency.BACKGROUND_EXECUTOR)
    private val handler = Dependency.get(Dependency.MAIN_HANDLER)
    private val displayId = context.getDisplayId()
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    private var displayHal = ServiceManager.waitForDeclaredService(PIXEL_DISPLAY_HAL)
            .let { binder ->
                binder.linkToDeath(this, 0)
                IDisplay.Stub.asInterface(binder)
            }

    private val peakRefreshRate = displayManager.getDisplay(displayId).supportedModes
            .maxOf { it.refreshRate }
    private val currentRefreshRate: Float
        get() = displayManager.getDisplay(displayId).refreshRate

    // Used by both main and UI background threads
    @Volatile private var pendingEnable = false
    @Volatile private var pendingEnableCallback: Runnable? = null

    init {
        // Listen for refresh rate changes
        displayManager.registerDisplayListener(this, handler)
    }

    override fun enableHbm(hbmType: Int, surface: Surface?, onHbmEnabled: Runnable?) {
        if (hbmType != HBM_TYPE) {
            return
        }

        // Run the callback and skip enabling if already enabled
        // (otherwise it may fail, similar to disabling)
        if (displayHal.getLhbmState()) {
            onHbmEnabled?.run()
            return
        }

        // Takes 20-30 ms, so switch to background
        bgExecutor.execute {
            // Request HbmSVManager to lock the refresh rate. On the Pixel 6 Pro (raven), LHBM only
            // works at peak refresh rate.
            authController.udfpsHbmListener?.onHbmEnabled(hbmType, displayId)

            if (currentRefreshRate == peakRefreshRate) {
                // Enable immediately if refresh rate is correct
                doPendingEnable(onHbmEnabled)
            } else {
                // Otherwise, queue it and wait for the refresh rate update callback
                pendingEnable = true
                pendingEnableCallback = onHbmEnabled
            }
        }
    }

    private fun doPendingEnable(callback: Runnable? = null) {
        displayHal?.setLhbmState(true)
        // Make sure callback runs on main thread
        (callback ?: pendingEnableCallback)?.let { handler.post(it) }

        pendingEnable = false
        pendingEnableCallback = null // to avoid leaking memory
    }

    override fun disableHbm(onHbmDisabled: Runnable?) {
        // If there's a pending enable, clear it and skip the disable request entirely.
        // Otherwise, HBM will be disabled before the enable - while it's already disabled, which
        // causes the display HAL call to throw an exception.
        if (pendingEnable) {
            pendingEnable = false
            pendingEnableCallback = null
            return
        }

        // Also bail out if HBM is already disabled *and* no enable is pending.
        // This can happen sometimes if the user spams taps on the UDFPS icon.
        if (!displayHal.getLhbmState()) {
            return
        }

        // Takes 10-20 ms, so switch to background
        bgExecutor.execute {
            displayHal?.setLhbmState(false)
            // Unlock refresh rate
            handler.post { authController.udfpsHbmListener?.onHbmDisabled(HBM_TYPE, displayId) }

            onHbmDisabled?.let { handler.post(it) }
        }
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) {
        // Dispatch pending enable if we were waiting for the refresh rate to change
        if (pendingEnable && displayId == this.displayId && currentRefreshRate == peakRefreshRate) {
            doPendingEnable()
        }
    }

    override fun binderDied() {
        displayHal = null
    }

    companion object {
        // Descriptor for Pixel display HAL's AIDL service
        private const val PIXEL_DISPLAY_HAL = "com.google.hardware.pixel.display.IDisplay/default"

        // For simplicity, we only support local HBM as that's the only mode used in production
        private const val HBM_TYPE = UdfpsHbmTypes.LOCAL_HBM
    }
}
