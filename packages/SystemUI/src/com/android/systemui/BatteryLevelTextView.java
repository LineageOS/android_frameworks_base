/*
 * Copyright (C) 2014-2016 The CyanogenMod Project
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

package com.android.systemui;

import android.content.Context;
import android.icu.text.NumberFormat;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.tuner.TunerService;

import cyanogenmod.providers.CMSettings;

public class BatteryLevelTextView extends TextView implements
        BatteryController.BatteryStateChangeCallback, TunerService.Tunable {

    private static final String STATUS_BAR_SHOW_BATTERY_PERCENT =
            "cmsystem:" + CMSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT;
    private static final String STATUS_BAR_BATTERY_STYLE =
            "cmsystem:" + CMSettings.System.STATUS_BAR_BATTERY_STYLE;

    private BatteryController mBatteryController;
    private final String mSlotBattery;

    private boolean mIsBlacklisted = false;
    private boolean mRequestedVisibility;
    private int mBatteryStyle = BatteryMeterDrawable.BATTERY_STYLE_PORTRAIT;

    public BatteryLevelTextView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mSlotBattery = context.getString(com.android.internal.R.string.status_bar_battery);
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        setText(NumberFormat.getPercentInstance().format((double) level / 100.0));
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
        mBatteryController.addStateChangedCallback(this);
        TunerService.get(getContext()).addTunable(this, StatusBarIconController.ICON_BLACKLIST,
                STATUS_BAR_SHOW_BATTERY_PERCENT, STATUS_BAR_BATTERY_STYLE);
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        // Unused
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        TunerService.get(getContext()).removeTunable(this);
        if (mBatteryController != null) {
            mBatteryController.removeStateChangedCallback(this);
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        boolean isStyleText = false;

        switch (key) {
            case StatusBarIconController.ICON_BLACKLIST:
                ArraySet<String> icons = StatusBarIconController.getIconBlacklist(newValue);
                mIsBlacklisted = icons.contains(mSlotBattery);
                break;
            case STATUS_BAR_SHOW_BATTERY_PERCENT:
                mRequestedVisibility = newValue != null && Integer.parseInt(newValue) == 2;
                break;
            case STATUS_BAR_BATTERY_STYLE:
                if (newValue != null) {
                    mBatteryStyle = Integer.parseInt(newValue);
                }
                break;
            default:
                break;
        }

        if (!mIsBlacklisted && (mRequestedVisibility
                || mBatteryStyle == BatteryMeterDrawable.BATTERY_STYLE_TEXT)) {
            setVisibility(View.VISIBLE);
        } else {
            setVisibility(View.GONE);
        }
    }
}
