/*
 * Copyright (C) 2020 The LineageOS Project
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
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.quicksettings.Tile;

import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.GlobalSetting;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

import lineageos.providers.LineageSettings;
import org.lineageos.internal.logging.LineageMetricsLogger;

import javax.inject.Inject;

/** Quick settings tile: Battery life saver **/
public class BatteryLifeSaverTile extends QSTileImpl<BooleanState> {

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_battery_life_saver);
    private int mBatteryLifeSaver;
    private boolean mListening;
    private final BatteryLifeSaverObserver mObserver;

    @Inject
    public BatteryLifeSaverTile(QSHost host) {
        super(host);
        mObserver = new BatteryLifeSaverObserver(mHandler);
        mBatteryLifeSaver = LineageSettings.System.getInt(mContext.getContentResolver(),
                LineageSettings.System.BATTERY_LIFE_SAVER, 0);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        mBatteryLifeSaver += 1;
        if (mBatteryLifeSaver > 2) { mBatteryLifeSaver = 0; }
        LineageSettings.System.putInt(mContext.getContentResolver(),
                LineageSettings.System.BATTERY_LIFE_SAVER,
                mBatteryLifeSaver);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = mBatteryLifeSaver != 0;
        state.label = mContext.getString(R.string.quick_settings_battery_life_saver_label);
        state.icon = mIcon;
        if (mBatteryLifeSaver == 1) {
            state.secondaryLabel = mContext.getString(R.string.accessibility_quick_settings_battery_life_saver_optimized);
            state.state = Tile.STATE_ACTIVE;
        } else if (mBatteryLifeSaver == 2) {
            state.secondaryLabel = mContext.getString(R.string.accessibility_quick_settings_battery_life_saver_fully_optimized);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.secondaryLabel = mContext.getString(R.string.accessibility_quick_settings_battery_life_saver_disabled);
            state.state = Tile.STATE_INACTIVE;
        }
        state.contentDescription = state.secondaryLabel;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_battery_life_saver_label);
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mBatteryLifeSaver == 1) {
            return mContext.getString(R.string.accessibility_quick_settings_battery_life_saver_optimized);
        } else if (mBatteryLifeSaver == 2) {
            return mContext.getString(R.string.accessibility_quick_settings_battery_life_saver_fully_optimized);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_battery_life_saver_disabled);
        }
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_BATTERY_LIFE_SAVER;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mObserver.startObserving();
            refreshState();
        } else {
            mObserver.endObserving();
        }
    }

    private class BatteryLifeSaverObserver extends ContentObserver {
        public BatteryLifeSaverObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }

        public void startObserving() {
            mContext.getContentResolver().registerContentObserver(
                    LineageSettings.System.getUriFor(
                            LineageSettings.System.BATTERY_LIFE_SAVER), false, this);
        }

        public void endObserving() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
    }
}
