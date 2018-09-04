/*
 * Copyright (C) 2015 The CyanogenMod Project
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

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import lineageos.providers.LineageSettings;
import org.lineageos.internal.logging.LineageMetricsLogger;

import java.net.InetAddress;

/** Quick settings tile: AdbOverNetwork **/
public class AdbOverNetworkTile extends QSTileImpl<BooleanState> {

    private boolean mListening;
    private final KeyguardMonitor mKeyguardMonitor;
    private final KeyguardMonitorCallback mKeyguardCallback = new KeyguardMonitorCallback();

    private final NetworkController mController;
    private final WifiSignalCallback mSignalCallback = new WifiSignalCallback();
    private final WifiManager mWifiManager;

    private static final Intent SETTINGS_DEVELOPMENT =
            new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);

    public AdbOverNetworkTile(QSHost host) {
        super(host);
        mController = Dependency.get(NetworkController.class);
        mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
        mWifiManager = mContext.getSystemService(WifiManager.class);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (mKeyguardMonitor.isSecure() && !mKeyguardMonitor.canSkipBouncer()) {
            Dependency.get(ActivityStarter.class)
                    .postQSRunnableDismissingKeyguard(this::toggleAction);
        } else {
            toggleAction();
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return SETTINGS_DEVELOPMENT;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = isAdbEnabled() && isAdbNetworkEnabled();
        state.icon = ResourceIcon.get(R.drawable.ic_qs_network_adb);
        state.label = mContext.getString(R.string.quick_settings_network_adb_label);
        if (state.value) {
            WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
            if (wifiInfo != null) {
                InetAddress address = NetworkUtils.intToInetAddress(wifiInfo.getIpAddress());
                state.secondaryLabel = address.getHostAddress();
                state.state = Tile.STATE_ACTIVE;
            }
        } else {
            state.secondaryLabel = null;
            state.state = canEnableAdbNetwork() ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE;
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_network_adb_label);
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_ADB_OVER_NETWORK;
    }

    private boolean isAdbEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.ADB_ENABLED, 0) > 0;
    }

    private boolean isAdbNetworkEnabled() {
        return LineageSettings.Secure.getInt(mContext.getContentResolver(),
                LineageSettings.Secure.ADB_PORT, 0) > 0;
    }

    private boolean isWifiConnected() {
        ConnectivityManager connMgr = mContext.getSystemService(ConnectivityManager.class);
        NetworkInfo networkInfo = connMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return networkInfo != null && networkInfo.isConnected();
    }

    private boolean canEnableAdbNetwork() {
        return isAdbEnabled() && isWifiConnected();
    }

    private void toggleAction() {
        if (canEnableAdbNetwork()) {
            LineageSettings.Secure.putIntForUser(mContext.getContentResolver(),
                    LineageSettings.Secure.ADB_PORT, getState().value ? -1 : 5555,
                    UserHandle.USER_CURRENT);
        }
    }

    private ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshState();
        }
    };

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening != listening) {
            mListening = listening;
            if (listening) {
                mContext.getContentResolver().registerContentObserver(
                        LineageSettings.Secure.getUriFor(LineageSettings.Secure.ADB_PORT),
                        false, mObserver);
                mContext.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                        false, mObserver);
                mKeyguardMonitor.addCallback(mKeyguardCallback);
                mController.addCallback(mSignalCallback);
            } else {
                mContext.getContentResolver().unregisterContentObserver(mObserver);
                mKeyguardMonitor.removeCallback(mKeyguardCallback);
                mController.removeCallback(mSignalCallback);
            }
        }
    }

    private class KeyguardMonitorCallback implements KeyguardMonitor.Callback {
        @Override
        public void onKeyguardShowingChanged() {
            refreshState();
        }
    }

    private class WifiSignalCallback implements SignalCallback {
        @Override
        public void setWifiIndicators(boolean enabled, IconState statusIcon, IconState qsIcon,
                boolean activityIn, boolean activityOut, String description, boolean isTransient,
                String statusLabel) {
            refreshState();
        }
    }
}
