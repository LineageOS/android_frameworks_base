/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.EventLog;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

import com.android.systemui.DejankUtils;
import com.android.systemui.EventLogTags;
import com.android.systemui.R;

public class PhoneStatusBarView extends PanelBar {
    private static final String TAG = "PhoneStatusBarView";
    private static final boolean DEBUG = PhoneStatusBar.DEBUG;
    private static final boolean DEBUG_GESTURES = false;

    PhoneStatusBar mBar;

    boolean mIsFullyOpenedPanel = false;
    private final PhoneStatusBarTransitions mBarTransitions;
    private ScrimController mScrimController;
    private float mMinFraction;
    private float mPanelFraction;
    private Runnable mHideExpandedRunnable = new Runnable() {
        @Override
        public void run() {
            if (mPanelFraction == 0.0f) {
                mBar.makeExpandedInvisible();
            }
        }
    };

    private int mShowCarrierLabel;
    private TextView mCarrierLabel;

    private int mCarrierLabelFontStyle = FONT_NORMAL;
    public static final int FONT_NORMAL = 0;
    public static final int FONT_ITALIC = 1;
    public static final int FONT_BOLD = 2;
    public static final int FONT_BOLD_ITALIC = 3;
    public static final int FONT_LIGHT = 4;
    public static final int FONT_LIGHT_ITALIC = 5;
    public static final int FONT_THIN = 6;
    public static final int FONT_THIN_ITALIC = 7;
    public static final int FONT_CONDENSED = 8;
    public static final int FONT_CONDENSED_ITALIC = 9;
    public static final int FONT_CONDENSED_LIGHT = 10;
    public static final int FONT_CONDENSED_LIGHT_ITALIC = 11;
    public static final int FONT_CONDENSED_BOLD = 12;
    public static final int FONT_CONDENSED_BOLD_ITALIC = 13;
    public static final int FONT_MEDIUM = 14;
    public static final int FONT_MEDIUM_ITALIC = 15;
    public static final int FONT_BLACK = 16;
    public static final int FONT_BLACK_ITALIC = 17;
    public static final int FONT_DANCINGSCRIPT = 18;
    public static final int FONT_DANCINGSCRIPT_BOLD = 19;
    public static final int FONT_COMINGSOON = 20;
    public static final int FONT_NOTOSERIF = 21;
    public static final int FONT_NOTOSERIF_ITALIC = 22;
    public static final int FONT_NOTOSERIF_BOLD = 23;
    public static final int FONT_NOTOSERIF_BOLD_ITALIC = 24;

    private ContentObserver mObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange, Uri uri) {
            showStatusBarCarrier();
            updateVisibilities();
        }
    };

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        showStatusBarCarrier();

        mBarTransitions = new PhoneStatusBarTransitions(this);
    }

    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public void setBar(PhoneStatusBar bar) {
        mBar = bar;
    }

    public void setScrimController(ScrimController scrimController) {
        mScrimController = scrimController;
    }

    private void showStatusBarCarrier() {
        mShowCarrierLabel = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_CARRIER, 1, UserHandle.USER_CURRENT);
        mCarrierLabelFontStyle = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_CARRIER_FONT_STYLE, FONT_NORMAL,
                UserHandle.USER_CURRENT);
    }

    @Override
    public void onFinishInflate() {
        mCarrierLabel = (TextView) findViewById(R.id.statusbar_carrier_text);
        updateVisibilities();
        mBarTransitions.init();
    }

    private void updateVisibilities() {
        if (mCarrierLabel != null) {
            if (mShowCarrierLabel == 2) {
                mCarrierLabel.setVisibility(View.VISIBLE);
            } else if (mShowCarrierLabel == 3) {
                mCarrierLabel.setVisibility(View.VISIBLE);
            } else {
                mCarrierLabel.setVisibility(View.GONE);
            }
        getFontStyle(mCarrierLabelFontStyle);
        }
    }

    public void getFontStyle(int font) {
        switch (font) {
            case FONT_NORMAL:
            default:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif",
                    Typeface.NORMAL));
                break;
            case FONT_ITALIC:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif",
                    Typeface.ITALIC));
                break;
            case FONT_BOLD:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif",
                    Typeface.BOLD));
                break;
            case FONT_BOLD_ITALIC:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif",
                    Typeface.BOLD_ITALIC));
                break;
            case FONT_LIGHT:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-light",
                    Typeface.NORMAL));
                break;
            case FONT_LIGHT_ITALIC:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-light",
                    Typeface.ITALIC));
                break;
            case FONT_THIN:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-thin",
                    Typeface.NORMAL));
                break;
            case FONT_THIN_ITALIC:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-thin",
                    Typeface.ITALIC));
                break;
            case FONT_CONDENSED:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-condensed",
                    Typeface.NORMAL));
                break;
            case FONT_CONDENSED_ITALIC:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-condensed",
                    Typeface.ITALIC));
                break;
            case FONT_CONDENSED_LIGHT:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-condensed-light",
                    Typeface.NORMAL));
                break;
            case FONT_CONDENSED_LIGHT_ITALIC:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-condensed-light",
                    Typeface.ITALIC));
                break;
            case FONT_CONDENSED_BOLD:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-condensed",
                    Typeface.BOLD));
                break;
            case FONT_CONDENSED_BOLD_ITALIC:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-condensed",
                    Typeface.BOLD_ITALIC));
                break;
            case FONT_MEDIUM:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-medium",
                    Typeface.NORMAL));
                break;
            case FONT_MEDIUM_ITALIC:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-medium",
                    Typeface.ITALIC));
                break;
            case FONT_BLACK:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-black",
                    Typeface.NORMAL));
                break;
            case FONT_BLACK_ITALIC:
                mCarrierLabel.setTypeface(Typeface.create("sans-serif-black",
                    Typeface.ITALIC));
                break;
            case FONT_DANCINGSCRIPT:
                mCarrierLabel.setTypeface(Typeface.create("cursive",
                    Typeface.NORMAL));
                break;
            case FONT_DANCINGSCRIPT_BOLD:
                mCarrierLabel.setTypeface(Typeface.create("cursive",
                    Typeface.BOLD));
                break;
            case FONT_COMINGSOON:
                mCarrierLabel.setTypeface(Typeface.create("casual",
                    Typeface.NORMAL));
                break;
            case FONT_NOTOSERIF:
                mCarrierLabel.setTypeface(Typeface.create("serif",
                    Typeface.NORMAL));
                break;
            case FONT_NOTOSERIF_ITALIC:
                mCarrierLabel.setTypeface(Typeface.create("serif",
                    Typeface.ITALIC));
                break;
            case FONT_NOTOSERIF_BOLD:
                mCarrierLabel.setTypeface(Typeface.create("serif",
                    Typeface.BOLD));
                break;
            case FONT_NOTOSERIF_BOLD_ITALIC:
                mCarrierLabel.setTypeface(Typeface.create("serif",
                    Typeface.BOLD_ITALIC));
                break;
        }
    }

    @Override
    public boolean panelEnabled() {
        return mBar.panelsEnabled();
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public void onPanelPeeked() {
        super.onPanelPeeked();
        mBar.makeExpandedVisible(false);
    }

    @Override
    public void onPanelCollapsed() {
        super.onPanelCollapsed();
        // Close the status bar in the next frame so we can show the end of the animation.
        DejankUtils.postAfterTraversal(mHideExpandedRunnable);
        mIsFullyOpenedPanel = false;
    }

    public void removePendingHideExpandedRunnables() {
        DejankUtils.removeCallbacks(mHideExpandedRunnable);
    }

    @Override
    public void onPanelFullyOpened() {
        super.onPanelFullyOpened();
        if (!mIsFullyOpenedPanel) {
            mPanel.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        mIsFullyOpenedPanel = true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean barConsumedEvent = mBar.interceptTouchEvent(event);

        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_PANELBAR_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY(),
                        barConsumedEvent ? 1 : 0);
            }
        }

        return barConsumedEvent || super.onTouchEvent(event);
    }

    @Override
    public void onTrackingStarted() {
        super.onTrackingStarted();
        mBar.onTrackingStarted();
        mScrimController.onTrackingStarted();
        removePendingHideExpandedRunnables();
    }

    @Override
    public void onClosingFinished() {
        super.onClosingFinished();
        mBar.onClosingFinished();
    }

    @Override
    public void onTrackingStopped(boolean expand) {
        super.onTrackingStopped(expand);
        mBar.onTrackingStopped(expand);
    }

    @Override
    public void onExpandingFinished() {
        super.onExpandingFinished();
        mScrimController.onExpandingFinished();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mBar.interceptTouchEvent(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public void panelScrimMinFractionChanged(float minFraction) {
        if (mMinFraction != minFraction) {
            mMinFraction = minFraction;
            if (minFraction != 0.0f) {
                mScrimController.animateNextChange();
            }
            updateScrimFraction();
        }
    }

    @Override
    public void panelExpansionChanged(float frac, boolean expanded) {
        super.panelExpansionChanged(frac, expanded);
        mPanelFraction = frac;
        updateScrimFraction();
    }

    private void updateScrimFraction() {
        float scrimFraction = Math.max(mPanelFraction, mMinFraction);
        mScrimController.setPanelExpansion(scrimFraction);
    }

    public void onDensityOrFontScaleChanged() {
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        layoutParams.height = getResources().getDimensionPixelSize(
                R.dimen.status_bar_height);
        setLayoutParams(layoutParams);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                "status_bar_show_carrier"), false, mObserver);
        getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                "status_bar_carrier_font_style"), false, mObserver);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }
}
