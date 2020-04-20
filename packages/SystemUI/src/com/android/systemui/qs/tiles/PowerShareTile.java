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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.RemoteException;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.BatteryController;

import org.lineageos.internal.logging.LineageMetricsLogger;

import vendor.lineage.powershare.V1_0.ILineagePowerShare;

import javax.inject.Inject;

public class PowerShareTile extends QSTileImpl<BooleanState> implements BatteryController.BatteryStateChangeCallback {
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_powershare);
    private ILineagePowerShare mPowerShare;
    private BatteryController mBatteryController;
    private boolean powersave = false;
    private int batteryLevel = 100;
    private int minBatteryLevel = 0;
    private NotificationChannel notificationChannel;
    private Notification notification;
    private static final String CHANNEL_ID = "powershare";
    private static final int NOTIFICATION_ID = 1;

    @Inject
    public PowerShareTile(QSHost host, BatteryController batteryController) {
        super(host);
        mPowerShare = getLineagePowerShare();
        mBatteryController = batteryController;

        batteryController.addCallback(this);

        NotificationManager notificationManager = mContext.getSystemService(NotificationManager.class);

        notificationChannel = new NotificationChannel(CHANNEL_ID, mContext.getString(R.string.quick_settings_powershare_label), NotificationManager.IMPORTANCE_DEFAULT);
        notificationManager.createNotificationChannel(notificationChannel);

        Notification.Builder builder = new Notification.Builder(mContext, CHANNEL_ID);
        builder.setContentTitle(mContext.getString(R.string.quick_settings_powershare_enabled_label));
        builder.setSmallIcon(R.drawable.ic_qs_powershare);
        notification = builder.build();
        notification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        notification.visibility = Notification.VISIBILITY_PUBLIC;
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        powersave = isPowerSave;
        updatePowerShareState();
        refreshState();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        batteryLevel = level;
        updatePowerShareState();
        refreshState();
    }

    private void updatePowerShareState() {
        mPowerShare = getLineagePowerShare();
        minBatteryLevel = getMinBatteryLevel();

        if (mPowerShare != null) {
            if (batteryLevel < minBatteryLevel || powersave) {
                try {
                    mPowerShare.setState(false);
                } catch (RemoteException ex) {
                    ex.printStackTrace();
                }
            }

            NotificationManager notificationManager = (NotificationManager) mContext.getSystemService(NotificationManager.class);

            try {
                if (mPowerShare.getState()) {
                    notificationManager.notify(NOTIFICATION_ID, notification);
                } else {
                    notificationManager.cancel(NOTIFICATION_ID);
                }
            } catch (RemoteException ex) {
                ex.printStackTrace();
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return mContext.getResources().getBoolean(R.bool.quick_settings_powershare_available);
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    public void handleClick() {
        mPowerShare = getLineagePowerShare();

        try {
            if (mPowerShare != null) {
                boolean powerShareEnabled = mPowerShare.getState();

                if (mPowerShare.setState(!powerShareEnabled) != powerShareEnabled) {
                    updatePowerShareState();
                    refreshState();
                }
            }
        } catch (RemoteException ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        if (powersave) {
            return mContext.getString(R.string.quick_settings_powershare_off_powersave_label);
        } else {
            minBatteryLevel = getMinBatteryLevel();

            if (batteryLevel < minBatteryLevel) {
                return mContext.getString(R.string.quick_settings_powershare_off_low_battery_label);
            }
        }

        return mContext.getString(R.string.quick_settings_powershare_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (state.slash == null) {
            state.slash = new SlashState();
        }

        mPowerShare = getLineagePowerShare();
        minBatteryLevel = getMinBatteryLevel();

        state.icon = mIcon;
        try {
            state.value = mPowerShare != null && mPowerShare.getState();
        } catch (RemoteException ex) {
            state.value = false;
            ex.printStackTrace();
        }
        state.slash.isSlashed = state.value;
        state.label = mContext.getString(R.string.quick_settings_powershare_label);

        if (powersave || batteryLevel < minBatteryLevel) {
            state.state = Tile.STATE_UNAVAILABLE;
        } else if (!state.value) {
            state.state = Tile.STATE_INACTIVE;
        } else {
            state.state = Tile.STATE_ACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_POWERSHARE;
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    private synchronized ILineagePowerShare getLineagePowerShare() {
        if (mPowerShare == null) {
            try {
                return ILineagePowerShare.getService();
            } catch (RemoteException ex) {
                ex.printStackTrace();
            }

            return null;
        } else {
            return mPowerShare;
        }
    }

    private int getMinBatteryLevel() {
        if (mPowerShare != null) {
            try {
                return mPowerShare.getMinBattery();
            } catch (RemoteException ex) {
                ex.printStackTrace();
            }
        }

        return minBatteryLevel;
    }
}
