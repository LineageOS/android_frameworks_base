/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2017-2018 The LineageOS Project
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

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.net.TetheringManager;
import android.service.quicksettings.Tile;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import javax.inject.Inject;

/**
 * USB Tether quick settings tile
 */
public class UsbTetherTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "usb_tether";

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_usb_tether);

    private static final Intent TETHER_SETTINGS = new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.TetherSettings"));

    private final TetheringManager mTetheringManager;

    private boolean mListening;

    private boolean mUsbConnected = false;
    private boolean mUsbTetherEnabled = false;

    @Inject
    public UsbTetherTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mTetheringManager = mContext.getSystemService(TetheringManager.class);
    }

    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) {
            return;
        }
        mListening = listening;
        if (listening) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_STATE);
            mContext.registerReceiver(mReceiver, filter);
        } else {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    @Override
    protected void handleClick(@Nullable View view) {
        if (mUsbConnected) {
            mTetheringManager.setUsbTethering(!mUsbTetherEnabled);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(TETHER_SETTINGS);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mUsbConnected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
            if (mUsbConnected && mTetheringManager.isTetheringSupported()) {
                mUsbTetherEnabled = intent.getBooleanExtra(UsbManager.USB_FUNCTION_RNDIS, false);
            } else {
                mUsbTetherEnabled = false;
            }
            refreshState();
        }
    };

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = mUsbTetherEnabled;
        state.label = mContext.getString(R.string.quick_settings_usb_tether_label);
        state.icon = mIcon;
        state.state = !mUsbConnected ? Tile.STATE_UNAVAILABLE
                : mUsbTetherEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_usb_tether_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }
}
