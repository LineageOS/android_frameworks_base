/*
 * Copyright (C) 2018 The OmniROM Project
 *               2020 The LineageOS Project
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

package com.android.systemui.qs.tiles;

import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.BatteryController;

import org.lineageos.internal.logging.LineageMetricsLogger;

import javax.inject.Inject;

public class AODTile extends QSTileImpl<BooleanState> implements
        BatteryController.BatteryStateChangeCallback {

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_aod);
    private final BatteryController mBatteryController;

    private final SecureSetting mSetting;

    @Inject
    public AODTile(QSHost host, BatteryController batteryController) {
        super(host);

        mSetting = new SecureSetting(mContext, mHandler, Settings.Secure.DOZE_ALWAYS_ON) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };

        mBatteryController = batteryController;
        batteryController.observe(getLifecycle(), this);
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        refreshState();
    }

    @Override
    public boolean isAvailable() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dozeAlwaysOnDisplayAvailable);
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    public void handleClick() {
        setEnabled(!mState.value);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    private void setEnabled(boolean enabled) {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON,
                enabled ? 1 : 0);
    }

    @Override
    public CharSequence getTileLabel() {
        if (mBatteryController.isAodPowerSave()) {
            return mContext.getString(R.string.quick_settings_aod_off_powersave_label);
        }
        return mContext.getString(R.string.quick_settings_aod_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer) arg : mSetting.getValue();
        final boolean enable = value != 0;
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        state.icon = mIcon;
        state.value = enable;
        state.slash.isSlashed = state.value;
        state.label = mContext.getString(R.string.quick_settings_aod_label);
        if (mBatteryController.isAodPowerSave()) {
            state.state = Tile.STATE_UNAVAILABLE;
        } else {
            state.state = enable ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_AOD;
    }

    @Override
    public void handleSetListening(boolean listening) {
    }
}
