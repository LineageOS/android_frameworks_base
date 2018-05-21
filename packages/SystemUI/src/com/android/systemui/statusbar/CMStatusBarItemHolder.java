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

import com.android.systemui.R;

import org.cyanogenmod.internal.statusbar.CMStatusBarItem;

import java.util.ArrayList;

public class CMStatusBarItemHolder extends RelativeLayout
        implements CMStatusBarItem.Manager {
    private static final String TAG = "CMStatusBarItemHolder";

    private ArrayList<CMStatusBarItem.VisibilityReceiver> mVisibilityReceivers =
            new ArrayList<CMStatusBarItem.VisibilityReceiver>();

    private Rect mLastArea;
    private int mLastTint;

    private boolean mItemHolderIsVisible;

    private Context mContext;

    public CMStatusBarItemHolder(Context context) {
        this(context, null);
    }

    public CMStatusBarItemHolder(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CMStatusBarItemHolder(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mItemHolderIsVisible = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        setOnSystemUiVisibilityChangeListener(mSystemUiVisibilityChangeListener);
        updateStatusBarVisibility(getSystemUiVisibility());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setOnSystemUiVisibilityChangeListener(null);
    }

    // Collect and propagate item holder visibility to
    // registered receivers.
    //
    // We watch both our own view visibility and systemui visibility.
    // Latest change in either direction wins (and has been observed
    // thus far to always be correct).

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        updateVisibilityReceivers(isVisible);
    }

    private View.OnSystemUiVisibilityChangeListener mSystemUiVisibilityChangeListener =
            new View.OnSystemUiVisibilityChangeListener() {
        @Override
        public void onSystemUiVisibilityChange(int visibility) {
            updateStatusBarVisibility(visibility);
        }
    };

    private void updateStatusBarVisibility(int visibility) {
        final boolean isVisible =
                (visibility & SYSTEM_UI_FLAG_FULLSCREEN) == 0
                || (visibility & SYSTEM_UI_FLAG_LOW_PROFILE) != 0;
        updateVisibilityReceivers(isVisible);
    }

    private void updateVisibilityReceivers(boolean isVisible) {
        if (isVisible == mItemHolderIsVisible) {
            return;
        }
        mItemHolderIsVisible = isVisible;
        for (CMStatusBarItem.VisibilityReceiver r : mVisibilityReceivers) {
            r.onVisibilityChanged(mItemHolderIsVisible);
        }
    }

    // CMStatusBarItem.Manager methods

    public void addVisibilityReceiver(CMStatusBarItem.VisibilityReceiver visibilityReceiver) {
        mVisibilityReceivers.add(visibilityReceiver);
        visibilityReceiver.onVisibilityChanged(mItemHolderIsVisible);
    }
}
