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
import android.service.quicksettings.Tile;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.BatteryController;

import org.lineageos.internal.logging.LineageMetricsLogger;

import javax.inject.Inject;

import vendor.lineage.powershare.V1_0.IPowerShare;

public class PowerShareTile extends QSTileImpl<BooleanState> implements BatteryController.BatteryStateChangeCallback {
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_powershare);
    private IPowerShare mPowerShare;
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
        mPowerShare = getPowerShare();
        mBatteryController = batteryController;

        batteryController.addCallback(this);

        NotificationManager notificationManager = mContext.getSystemService(NotificationManager.class);

        notificationChannel = new NotificationChannel(CHANNEL_ID,
                mContext.getString(R.string.quick_settings_powershare_label),
                NotificationManager.IMPORTANCE_DEFAULT);
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
        refreshState();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        batteryLevel = level;
        refreshState();
    }

    @Override
    public void refreshState() {
        updatePowerShareState();

        super.refreshState();
    }

    private void updatePowerShareState() {
        minBatteryLevel = getMinBatteryLevel();

        if (batteryLevel < minBatteryLevel || powersave) {
            try {
                mPowerShare.setEnabled(false);
            } catch (RemoteException ex) {
                ex.printStackTrace();
            }
        }

        NotificationManager notificationManager =
                (NotificationManager) mContext.getSystemService(NotificationManager.class);

        try {
            if (mPowerShare.isEnabled()) {
                notificationManager.notify(NOTIFICATION_ID, notification);
            } else {
                notificationManager.cancel(NOTIFICATION_ID);
            }
        } catch (RemoteException ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public boolean isAvailable() {
        return mPowerShare != null;
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    public void handleClick() {
        try {
            boolean powerShareEnabled = mPowerShare.isEnabled();

            if (mPowerShare.setEnabled(!powerShareEnabled) != powerShareEnabled) {
                refreshState();
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

        minBatteryLevel = getMinBatteryLevel();

        state.icon = mIcon;
        try {
            state.value = mPowerShare.isEnabled();
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

    private synchronized IPowerShare getPowerShare() {
        try {
            return IPowerShare.getService();
        } catch (RemoteException ex) {
            ex.printStackTrace();
        }

        return null;
    }

    private int getMinBatteryLevel() {
        try {
            return mPowerShare.getMinBattery();
        } catch (RemoteException ex) {
            ex.printStackTrace();
        }

        return minBatteryLevel;
    }
}
