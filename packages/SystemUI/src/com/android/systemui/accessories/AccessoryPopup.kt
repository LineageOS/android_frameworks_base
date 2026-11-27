/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.accessories

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.PowerManager
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.android.internal.R as InternalR
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * Transient pill shown at the top of the screen. All the methods must be called on main.
 * A pill with an action requested while the screen is off is shown once it turns on.
 */
@SysUISingleton
class AccessoryPopup
@Inject
constructor(
    @Application private val context: Context,
    @Main private val mainHandler: Handler,
    private val powerManager: PowerManager,
    broadcastDispatcher: BroadcastDispatcher,
) {
    private val windowContext by lazy {
        val display =
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        context.createDisplayContext(display).createWindowContext(WINDOW_TYPE, null)
    }
    private val windowManager by lazy { windowContext.getSystemService(WindowManager::class.java) }

    private val dismissRunnable = Runnable { dismissShown() }

    private var pill: ViewGroup? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var shownKey: String? = null
    private var content: AccessoryPopupContent? = null
    private var pending: Pair<String, AccessoryPopupContent>? = null

    init {
        broadcastDispatcher.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    pending?.let { (key, pendingContent) ->
                        pending = null
                        show(key, pendingContent)
                    }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_ON),
        )
    }

    fun show(key: String, newContent: AccessoryPopupContent) {
        if (!powerManager.isInteractive) {
            pending = if (newContent.onClick != null) key to newContent else null
            return
        }

        mainHandler.removeCallbacks(dismissRunnable)
        shownKey = key
        content = newContent

        val shownPill = pill
        if (shownPill != null) {
            crossfade(shownPill)
            animateIn(shownPill)
            bindInteraction(shownPill, newContent)
        } else {
            val newPill =
                LayoutInflater.from(windowContext).inflate(R.layout.accessory_popup, null)
                    as ViewGroup
            bindContent(newPill, newContent)
            prepareEnter(newPill)
            val params = createLayoutParams(newPill, newContent)
            windowManager.addView(newPill, params)
            pill = newPill
            layoutParams = params
            bindInteraction(newPill, newContent)
        }

        mainHandler.postDelayed(dismissRunnable, newContent.durationMs)
    }

    fun update(key: String, newContent: AccessoryPopupContent) {
        pending?.let { (pendingKey, _) ->
            if (pendingKey == key) {
                pending = if (newContent.onClick != null) key to newContent else null
            }
        }

        val shownPill = pill ?: return
        if (shownKey != key || content == newContent) {
            return
        }
        content = newContent
        crossfade(shownPill)
        bindInteraction(shownPill, newContent)
    }

    fun dismiss(key: String) {
        if (pending?.first == key) {
            pending = null
        }
        if (shownKey == key) {
            dismissShown()
        }
    }

    private fun dismissShown() {
        mainHandler.removeCallbacks(dismissRunnable)
        val shownPill = pill ?: return
        setTouchable(shownPill, false)
        shownPill
            .animate()
            .alpha(0f)
            .scaleX(COLLAPSED_SCALE_X)
            .scaleY(COLLAPSED_SCALE_Y)
            .translationY(enterOffset())
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(EMPHASIZED_ACCELERATE)
            .withEndAction { remove(shownPill) }
    }

    private fun remove(view: ViewGroup) {
        view.animate().cancel()
        if (view.isAttachedToWindow) {
            windowManager.removeViewImmediate(view)
        }
        if (pill === view) {
            pill = null
            layoutParams = null
            shownKey = null
            content = null
        }
    }

    // Swap the contents while they're faded out, so a size change isn't seen as a jump
    private fun crossfade(view: ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            child
                .animate()
                .alpha(0f)
                .setDuration(CONTENT_FADE_OUT_MS)
                .setInterpolator(EMPHASIZED_ACCELERATE)
                .withEndAction {
                    if (i == 0) {
                        content?.let { bindContent(view, it) }
                    }
                    child
                        .animate()
                        .alpha(1f)
                        .setDuration(CONTENT_FADE_IN_MS)
                        .setInterpolator(EMPHASIZED_DECELERATE)
                }
        }
    }

    private fun bindContent(view: ViewGroup, content: AccessoryPopupContent) {
        fun color(id: Int) = windowContext.getColor(id)

        val container =
            if (content.isError) InternalR.color.materialColorErrorContainer
            else InternalR.color.materialColorPrimaryContainer
        val onContainer =
            if (content.isError) InternalR.color.materialColorOnErrorContainer
            else InternalR.color.materialColorOnPrimaryContainer
        val accent =
            if (content.isError) InternalR.color.materialColorError
            else InternalR.color.materialColorPrimary

        view.requireViewById<View>(R.id.accessory_popup_icon_container).backgroundTintList =
            ColorStateList.valueOf(color(container))
        view.requireViewById<ImageView>(R.id.accessory_popup_icon).apply {
            setImageResource(content.icon)
            imageTintList = ColorStateList.valueOf(color(onContainer))
        }
        view.requireViewById<TextView>(R.id.accessory_popup_title).text = content.title
        view.requireViewById<TextView>(R.id.accessory_popup_subtitle).text = content.subtitle
        view.requireViewById<ImageView>(R.id.accessory_popup_bolt).apply {
            visibility =
                if (content.isCharging && content.levelText != null) View.VISIBLE else View.GONE
            imageTintList = ColorStateList.valueOf(color(accent))
        }
        view.requireViewById<TextView>(R.id.accessory_popup_level).apply {
            visibility = if (content.levelText != null) View.VISIBLE else View.GONE
            text = content.levelText
            setTextColor(color(accent))
        }
        view.accessibilityPaneTitle =
            listOfNotNull(content.title, content.subtitle, content.levelText).joinToString()
    }

    private fun bindInteraction(view: View, content: AccessoryPopupContent) {
        val onClick = content.onClick
        if (onClick != null) {
            view.setOnClickListener {
                view.setOnClickListener(null)
                view.isClickable = false
                setTouchable(view, false)
                onClick()
            }
        } else {
            view.setOnClickListener(null)
            view.isClickable = false
        }
        setTouchable(view, onClick != null)
    }

    private fun setTouchable(view: View, isTouchable: Boolean) {
        val params = layoutParams ?: return
        val flags =
            if (isTouchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags != params.flags && view.isAttachedToWindow) {
            params.flags = flags
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun prepareEnter(view: View) {
        view.alpha = 0f
        view.scaleX = COLLAPSED_SCALE_X
        view.scaleY = COLLAPSED_SCALE_Y
        view.translationY = enterOffset()
        view.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    animateIn(view)
                    return true
                }
            }
        )
    }

    private fun animateIn(view: View) {
        view.pivotX = view.width / 2f
        view.pivotY = 0f
        view
            .animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(ENTER_DURATION_MS)
            .setInterpolator(EMPHASIZED_DECELERATE)
    }

    private fun enterOffset() =
        windowContext.resources.getDimension(R.dimen.accessory_popup_enter_offset)

    private fun createLayoutParams(
        view: View,
        content: AccessoryPopupContent,
    ): WindowManager.LayoutParams {
        val statusBarHeight =
            windowManager.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.statusBars())
                .top
        val res = windowContext.resources
        var flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (content.onClick == null) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        return WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                res.getDimensionPixelSize(R.dimen.accessory_popup_height),
                WINDOW_TYPE,
                flags,
                PixelFormat.TRANSLUCENT,
            )
            .apply {
                title = TAG
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = statusBarHeight + res.getDimensionPixelSize(R.dimen.accessory_popup_margin_top)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                // Let the shadow draw outside the window without growing its bounds
                setSurfaceInsets(view, true /* manual */, false /* preservePrevious */)
            }
    }

    companion object {
        private const val TAG = "AccessoryPopup"

        private const val WINDOW_TYPE = WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL

        private const val ENTER_DURATION_MS = 450L
        private const val EXIT_DURATION_MS = 200L
        private const val CONTENT_FADE_OUT_MS = 100L
        private const val CONTENT_FADE_IN_MS = 200L

        private const val COLLAPSED_SCALE_X = 0.4f
        private const val COLLAPSED_SCALE_Y = 0.6f

        private val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
