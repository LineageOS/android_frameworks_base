/*
 * Copyright (C) 2018-2023 The LineageOS Project
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
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.Clock;

import lineageos.providers.LineageSettings;

public class ClockController {

    private static final String TAG = "ClockController";

    private static final int CLOCK_POSITION_RIGHT = 0;
    private static final int CLOCK_POSITION_CENTER = 1;
    private static final int CLOCK_POSITION_LEFT = 2;

    private Context mContext;
    private Clock mActiveClock, mCenterClock, mLeftClock, mRightClock;

    private int mClockPosition;
    private boolean mDenyListed;

    public ClockController(Context context, View statusBar) {
        mContext = context;

        mCenterClock = statusBar.findViewById(R.id.clock_center);
        mLeftClock = statusBar.findViewById(R.id.clock);
        mRightClock = statusBar.findViewById(R.id.clock_right);

        mActiveClock = mLeftClock;

        Uri iconHideList = Settings.Secure.getUriFor(StatusBarIconController.ICON_HIDE_LIST);
        Uri statusBarClock = LineageSettings.System.getUriFor(
                LineageSettings.System.STATUS_BAR_CLOCK);
        ContentObserver contentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                if (iconHideList.equals(uri)) {
                    mDenyListed = StatusBarIconController.getIconHideList(mContext,
                            Settings.Secure.getString(mContext.getContentResolver(),
                                    StatusBarIconController.ICON_HIDE_LIST)).contains("clock");
                } else if (statusBarClock.equals(uri)) {
                    mClockPosition = LineageSettings.System.getInt(mContext.getContentResolver(),
                            LineageSettings.System.STATUS_BAR_CLOCK, CLOCK_POSITION_LEFT);
                }
                updateActiveClock();
            }
        };
        mContext.getContentResolver().registerContentObserver(iconHideList, false, contentObserver);
        mContext.getContentResolver().registerContentObserver(statusBarClock, false,
                contentObserver);
        contentObserver.onChange(true, iconHideList);
        contentObserver.onChange(true, statusBarClock);
    }

    public Clock getClock() {
        switch (mClockPosition) {
            case CLOCK_POSITION_RIGHT:
                return mRightClock;
            case CLOCK_POSITION_CENTER:
                return mCenterClock;
            case CLOCK_POSITION_LEFT:
            default:
                return mLeftClock;
        }
    }

    private void updateActiveClock() {
        mContext.getMainExecutor().execute(() -> {
            mActiveClock.setClockVisibleByUser(false);
            removeDarkReceiver();
            mActiveClock = getClock();
            mActiveClock.setClockVisibleByUser(true);
            addDarkReceiver();

            // Override any previous setting
            mActiveClock.setClockVisibleByUser(!mDenyListed);
        });
    }

    public void addDarkReceiver() {
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mActiveClock);
    }

    public void removeDarkReceiver() {
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mActiveClock);
    }

    public void onDensityOrFontScaleChanged() {
        mActiveClock.onDensityOrFontScaleChanged();
    }
}
