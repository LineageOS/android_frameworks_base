/*
 * Copyright 2017 Paranoid Android
 * Copyright 2020 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.CentralSurfacesImpl;

public class BurnInProtectionController {
    private static final String TAG = "BurnInProtectionController";
    private static final boolean DEBUG = false;
    private static final long INTERVAL = 60000; // Milliseconds

    private int mHorizontalShift = 0;
    private int mVerticalShift = 0;
    private int mHorizontalDirection = 1;
    private int mVerticalDirection = 1;
    private int mNavigationBarHorizontalMaxShift;
    private int mNavigationBarVerticalMaxShift;
    private int mHorizontalMaxShift;
    private int mVerticalMaxShift;
    private long mShiftInterval;

    private final Handler mHandler = new Handler();
    private final Runnable mRunnable = () -> {
            shiftItems();
            mHandler.postDelayed(this.mRunnable, INTERVAL);
    };

    private PhoneStatusBarView mPhoneStatusBarView;
    private CentralSurfacesImpl mStatusBar;

    private Context mContext;

    public BurnInProtectionController(Context context, CentralSurfacesImpl statusBar,
            PhoneStatusBarView phoneStatusBarView) {
        mContext = context;

        mPhoneStatusBarView = phoneStatusBarView;
        mStatusBar = statusBar;

        mHorizontalMaxShift = mContext.getResources()
                .getDimensionPixelSize(R.dimen.burnin_protection_horizontal_shift);
        // total of ((vertical_max_shift - 1) * 2) pixels can be moved
        mVerticalMaxShift = mContext.getResources()
                .getDimensionPixelSize(R.dimen.burnin_protection_vertical_shift) - 1;
    }

    public void startShiftTimer(boolean enabled) {
        if (!enabled) return;
        mHandler.removeCallbacks(mRunnable);
        mHandler.postDelayed(mRunnable, INTERVAL);
        if (DEBUG) Log.d(TAG, "Started shift timer");
    }

    public void stopShiftTimer(boolean enabled) {
        if (!enabled) return;
        mHandler.removeCallbacks(mRunnable);
        if (DEBUG) Log.d(TAG, "Canceled shift timer");
    }

    private void shiftItems() {
        mHorizontalShift += mHorizontalDirection;
        if ((mHorizontalShift >=  mHorizontalMaxShift) ||
                (mHorizontalShift <= -mHorizontalMaxShift)) {
            mHorizontalDirection *= -1;
        }

        mVerticalShift += mVerticalDirection;
        if ((mVerticalShift >=  mVerticalMaxShift) ||
                (mVerticalShift <= -mVerticalMaxShift)) {
            mVerticalDirection *= -1;
        }

        mPhoneStatusBarView.shiftStatusBarItems(mHorizontalShift, mVerticalShift);
        NavigationBarView navigationBarView = mStatusBar.getNavigationBarView();

        if (navigationBarView != null) {
            navigationBarView.shiftNavigationBarItems(mHorizontalShift, mVerticalShift);
        }
        if (DEBUG) Log.d(TAG, "Shifting items\u2026");
    }
}
