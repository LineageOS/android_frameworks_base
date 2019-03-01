/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.VisibleForTesting;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;
import android.text.format.DateUtils;

import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.core.lifecycle.Lifecycle;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;
import com.android.settingslib.R;

import java.lang.ref.WeakReference;

/**
 * Preference controller for battery status
 */
public abstract class AbstractBatteryStatusPreferenceController extends AbstractPreferenceController
        implements LifecycleObserver, OnStart, OnStop {

    @VisibleForTesting
    static final String KEY_BATTERY_STATUS = "battery_status";
    private static final int EVENT_UPDATE_BATTERY = 700;

    private Preference mBatteryStatus;
    private Handler mHandler;
    private Context mContext;

    public AbstractBatteryStatusPreferenceController(Context context, Lifecycle lifecycle) {
        super(context);
	mContext = context;
        if (lifecycle != null) {
            lifecycle.addObserver(this);
        }
    }

    @Override
    public void onStart() {
        getHandler().sendEmptyMessage(EVENT_UPDATE_BATTERY);
    }

    @Override
    public void onStop() {
        getHandler().removeMessages(EVENT_UPDATE_BATTERY);
    }

    @Override
    public boolean isAvailable() {
        Intent intent = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        return intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false);
    }

    @Override
    public String getPreferenceKey() {
        return KEY_BATTERY_STATUS;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mBatteryStatus = screen.findPreference(KEY_BATTERY_STATUS);
        updateBattery();
    }

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new MyHandler(this);
        }
        return mHandler;
    }

    private void updateBattery() {
        Intent intent = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        if (mBatteryStatus != null) {
            String batterystatus = mContext.getString(R.string.battery_info_status_unknown);
	    String batterylevel = Integer.toString(Math.round(100.f
                    * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
                    / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100))) + "%";

            switch (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                case BatteryManager.BATTERY_STATUS_CHARGING:
                    batterystatus = mContext.getString(R.string.battery_info_status_charging);
                    break;
                case BatteryManager.BATTERY_STATUS_DISCHARGING:
                    batterystatus = mContext.getString(R.string.battery_info_status_discharging);
                    break;
                case BatteryManager.BATTERY_STATUS_FULL:
                    batterystatus = mContext.getString(R.string.battery_info_status_full);
                    break;
                case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                    batterystatus = mContext.getString(R.string.battery_info_status_not_charging);
                    break;
                case BatteryManager.BATTERY_STATUS_UNKNOWN:
                default:
                    break;
            }

            mBatteryStatus.setSummary(batterylevel + " - " + batterystatus);
        }
    }

    private static class MyHandler extends Handler {
        private WeakReference<AbstractBatteryStatusPreferenceController> mStatus;

        public MyHandler(AbstractBatteryStatusPreferenceController activity) {
            mStatus = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            AbstractBatteryStatusPreferenceController status = mStatus.get();
            if (status == null) {
                return;
            }

            switch (msg.what) {
                case EVENT_UPDATE_BATTERY:
                    status.updateBattery();
                    sendEmptyMessageDelayed(EVENT_UPDATE_BATTERY, 1000);
                    break;

                default:
                    throw new IllegalStateException("Unknown message " + msg.what);
            }
        }
    }
}
