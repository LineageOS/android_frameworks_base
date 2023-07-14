/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.navigationbar.gestural

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Region
import android.os.Trace
import android.util.Log
import android.view.ISystemGestureExclusionListener
import android.view.IWindowManager
import android.view.InputEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import com.android.systemui.plugins.NavigationEdgeBackPlugin
import com.android.systemui.res.R
import com.android.systemui.shared.system.InputChannelCompat
import com.android.systemui.shared.system.InputMonitorCompat
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.BackPanelUiThread
import com.android.systemui.util.concurrency.UiThreadContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.io.PrintWriter

interface DisplayBackGestureHandler {

    fun onMotionEvent(ev: MotionEvent)

    fun setIsLeftPanel(isLeft: Boolean)

    fun setLongSwipeEnabled(enabled: Boolean)

    fun setBatchingEnabled(enabled: Boolean)

    fun pilferPointers()

    fun isValidTrackpadBackGesture(): Boolean

    fun getExcludeRegion(): Region

    fun dispose()

    fun dump(prefix: String, writer: PrintWriter)
}

class DisplayBackGestureHandlerImpl
@AssistedInject
constructor(
    @Assisted private val context: Context,
    @Assisted private val windowManager: WindowManager,
    @Assisted private val onInputEvent: (InputEvent) -> Unit,
    @Assisted backCallback: NavigationEdgeBackPlugin.BackCallback,
    @BackPanelUiThread private val uiThreadContext: UiThreadContext,
    private val backPanelControllerFactory: BackPanelController.Factory,
    configurationControllerFactory: ConfigurationControllerImpl.Factory,
    private val windowManagerService: IWindowManager,
) : DisplayBackGestureHandler {

    @AssistedFactory
    interface Factory {
        fun create(
            context: Context,
            windowManager: WindowManager,
            backCallback: NavigationEdgeBackPlugin.BackCallback,
            onInputEvent: (InputEvent) -> Unit,
        ): DisplayBackGestureHandlerImpl
    }

    private val displayId = context.displayId
    private val configurationController = configurationControllerFactory.create(context)
    private val displaySize =
        Point().apply {
            val bounds = windowManager.maximumWindowMetrics.bounds
            set(bounds.width(), bounds.height())
        }
    private val edgeBackPlugin = createEdgeBackPlugin(backCallback)
    private val excludeRegion = Region()

    private val inputMonitorCompat = InputMonitorCompat("edge-swipe", displayId)
    private val inputEventReceiver: InputChannelCompat.InputEventReceiver =
        inputMonitorCompat.getInputReceiver(
            uiThreadContext.looper,
            uiThreadContext.choreographer,
        ) { ev ->
            onInputEvent(ev)
        }

    private val configurationListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onConfigChanged(newConfig: Configuration?) {
                newConfig?.windowConfiguration?.maxBounds?.let {
                    displaySize.set(it.width(), it.height())
                    edgeBackPlugin.setDisplaySize(displaySize)
                }
            }
        }

    private val gestureExclusionListener: ISystemGestureExclusionListener =
        object : ISystemGestureExclusionListener.Stub() {
            override fun onSystemGestureExclusionChanged(
                displayId: Int,
                systemGestureExclusion: Region,
                unrestrictedOrNull: Region?,
            ) {
                if (displayId != this@DisplayBackGestureHandlerImpl.displayId) return
                uiThreadContext.executor.execute { excludeRegion.set(systemGestureExclusion) }
            }
        }

    init {
        configurationController.addCallback(configurationListener)
        registerSystemGestureExclusionListener()
    }

    override fun onMotionEvent(ev: MotionEvent) = edgeBackPlugin.onMotionEvent(ev)

    override fun setIsLeftPanel(isLeft: Boolean) = edgeBackPlugin.setIsLeftPanel(isLeft)

    override fun setLongSwipeEnabled(enabled: Boolean) = edgeBackPlugin.setLongSwipeEnabled(enabled)

    override fun setBatchingEnabled(enabled: Boolean) =
        inputEventReceiver.setBatchingEnabled(enabled)

    override fun pilferPointers() = inputMonitorCompat.pilferPointers()

    override fun isValidTrackpadBackGesture(): Boolean {
        // for trackpad gestures, unless the whole screen is excluded region, 3-finger swipe
        // gestures are allowed even if the cursor is in the excluded region.
        val insets =
            windowManager.currentWindowMetrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars()
            )
        return !excludeRegion.bounds.contains(
            insets.left,
            insets.top,
            displaySize.x - insets.right,
            displaySize.y - insets.bottom,
        )
    }

    override fun getExcludeRegion() = excludeRegion

    override fun dispose() {
        inputEventReceiver.dispose()
        inputMonitorCompat.dispose()
        edgeBackPlugin.onDestroy()
        configurationController.removeCallback(configurationListener)
        unregisterSystemGestureExclusionListener()
    }

    private fun createEdgeBackPlugin(
        backCallback: NavigationEdgeBackPlugin.BackCallback
    ): BackPanelController {
        val backPanelController =
            backPanelControllerFactory.create(context, windowManager, uiThreadContext.handler)
        backPanelController.init()

        try {
            Trace.beginSection("setEdgeBackPlugin")
            backPanelController.setBackCallback(backCallback)
            backPanelController.setLayoutParams(createLayoutParams())
            backPanelController.setDisplaySize(displaySize)
        } finally {
            Trace.endSection()
        }
        return backPanelController
    }

    private fun registerSystemGestureExclusionListener() {
        try {
            windowManagerService.registerSystemGestureExclusionListener(
                gestureExclusionListener,
                displayId,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register window manager callbacks", e)
        }
    }

    private fun unregisterSystemGestureExclusionListener() {
        try {
            windowManagerService.unregisterSystemGestureExclusionListener(
                gestureExclusionListener,
                displayId,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister window manager callbacks", e)
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val resources = context.resources
        val layoutParams =
            WindowManager.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.navigation_edge_panel_width),
                resources.getDimensionPixelSize(R.dimen.navigation_edge_panel_height),
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN),
                PixelFormat.TRANSLUCENT,
            )
        layoutParams.accessibilityTitle = context.getString(R.string.nav_bar_edge_panel)
        layoutParams.windowAnimations = 0
        layoutParams.privateFlags =
            layoutParams.privateFlags or
                (WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS or
                    WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION)
        layoutParams.title = "$TAG $displayId"
        layoutParams.fitInsetsTypes = 0
        layoutParams.setTrustedOverlay()
        return layoutParams
    }

    override fun dump(prefix: String, pw: PrintWriter) {
        pw.println("$prefix$TAG (displayId=$displayId)")
        pw.println("$prefix  displaySize=$displaySize")
        pw.println("$prefix  edgeBackPlugin=$edgeBackPlugin")
        pw.println("$prefix  excludeRegion=$excludeRegion")
        edgeBackPlugin.dump("$prefix  ", pw)
    }

    companion object {
        private const val TAG = "DisplayBackGestureHandler"
    }
}
