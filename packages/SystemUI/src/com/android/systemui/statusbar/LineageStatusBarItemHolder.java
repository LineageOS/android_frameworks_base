/**
 * Copyright (C) 2018 The LineageOS project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.widget.RelativeLayout;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;

import org.lineageos.internal.statusbar.LineageStatusBarItem;

public class LineageStatusBarItemHolder extends RelativeLayout {
    private static final String TAG = "LineageStatusBarItemHolder";

    private boolean mStatusBarIsVisible;
    private LineageStatusBarItem mLineageStatusBarItem;

    private Context mContext;

    public LineageStatusBarItemHolder(Context context) {
        this(context, null);
    }

    public LineageStatusBarItemHolder(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LineageStatusBarItemHolder(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mStatusBarIsVisible = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Locate child statusbar item
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof LineageStatusBarItem) {
                mLineageStatusBarItem = (LineageStatusBarItem) v;
                break;
             }
        }

        setOnSystemUiVisibilityChangeListener(mSystemUiVisibilityChangeListener);
        setStatusBarVisibility(getSystemUiVisibility());

        mLineageStatusBarItem.setFillColors(
                mContext.getColor(R.color.dark_mode_icon_color_dual_tone_fill),
                mContext.getColor(R.color.light_mode_icon_color_dual_tone_fill));

        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mDarkReceiver);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setOnSystemUiVisibilityChangeListener(null);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mDarkReceiver);
    }

    private DarkReceiver mDarkReceiver = new DarkReceiver() {
        @Override
        public void onDarkChanged(Rect area, float darkIntensity, int tint) {
            mLineageStatusBarItem.onDarkChanged(area, darkIntensity, tint);
        }
    };

    // Collect and propagate the latest visibility or systemui visibility
    // state to the LineageStatusBarItem child.

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (mStatusBarIsVisible != isVisible) {
            mStatusBarIsVisible = isVisible;
            updateVisibility();
        }
    }

    private View.OnSystemUiVisibilityChangeListener mSystemUiVisibilityChangeListener =
            new View.OnSystemUiVisibilityChangeListener() {
        @Override
        public void onSystemUiVisibilityChange(int visibility) {
            setStatusBarVisibility(visibility);
        }
    };

    private void setStatusBarVisibility(int visibility) {
        final boolean isVisible =
                (visibility & SYSTEM_UI_FLAG_FULLSCREEN) == 0
                || (visibility & SYSTEM_UI_FLAG_LOW_PROFILE) != 0;
        if (mStatusBarIsVisible != isVisible) {
            mStatusBarIsVisible = isVisible;
            updateVisibility();
        }
    }

    private void updateVisibility() {
        mLineageStatusBarItem.setStatusBarVisibility(mStatusBarIsVisible);
    }
}
