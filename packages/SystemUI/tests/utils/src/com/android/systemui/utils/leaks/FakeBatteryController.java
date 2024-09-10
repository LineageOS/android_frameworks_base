/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.os.Bundle;
import android.testing.LeakCheck;
import android.view.View;

import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class FakeBatteryController extends BaseLeakChecker<BatteryStateChangeCallback>
        implements BatteryController {
    private boolean mIsAodPowerSave = false;
    private boolean mWirelessCharging;
    private boolean mPowerSaveMode = false;

    private final List<BatteryStateChangeCallback> mCallbacks = new ArrayList<>();

    public FakeBatteryController(LeakCheck test) {
        super(test, "battery");
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {

    }

    @Override
    public void dump(PrintWriter pw, String[] args) {

    }

    @Override
    public void setPowerSaveMode(boolean powerSave) {
        mPowerSaveMode = powerSave;
        for (BatteryStateChangeCallback callback: mCallbacks) {
            callback.onPowerSaveChanged(powerSave);
        }
    }

    /**
     * Note: this method ignores the View argument
     */
    @Override
    public void setPowerSaveMode(boolean powerSave, View view) {
        setPowerSaveMode(powerSave);
    }

    @Override
    public boolean isPluggedIn() {
        return false;
    }

    @Override
    public boolean isPowerSave() {
        return mPowerSaveMode;
    }

    @Override
    public boolean isAodPowerSave() {
        return mIsAodPowerSave;
    }

    @Override
    public boolean isWirelessCharging() {
        return mWirelessCharging;
    }

    public void setIsAodPowerSave(boolean isAodPowerSave) {
        mIsAodPowerSave = isAodPowerSave;
    }

    public void setWirelessCharging(boolean wirelessCharging) {
        mWirelessCharging = wirelessCharging;
    }

    @Override
    public void addCallback(BatteryStateChangeCallback listener) {
        mCallbacks.add(listener);
    }

    @Override
    public void removeCallback(BatteryStateChangeCallback listener) {
        mCallbacks.remove(listener);
    }
}
