/*
 * Copyright (C) 2019 The LineageOS Project
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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.WindowManager.LayoutParams;

import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.SecurityController.SecurityControllerCallback;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.util.List;
import java.util.Set;

/** Quick settings tile: VPN **/
public class VpnTile extends QSTileImpl<BooleanState> {
    private final SecurityController mController;
    private final KeyguardMonitor mKeyguard;
    private final Callback mCallback = new Callback();
    private final ActivityStarter mActivityStarter;
    private Dialog mDialog;
    private boolean mRegistered;

    public VpnTile(QSHost host) {
        super(host);
        mController = Dependency.get(SecurityController.class);
        mKeyguard = Dependency.get(KeyguardMonitor.class);
        mActivityStarter = Dependency.get(ActivityStarter.class);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (DEBUG) Log.d(TAG, "handleSetListening " + listening);
        if (listening) {
            mController.addCallback(mCallback);
            mKeyguard.addCallback(mCallback);
        } else {
            mController.removeCallback(mCallback);
            mKeyguard.removeCallback(mCallback);
        }
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
        mController.onUserSwitched(newUserId);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_VPN_SETTINGS);
    }

    @Override
    protected void handleSecondaryClick() {
        handleClick();
    }

    @Override
    protected void handleClick() {
        if (mKeyguard.isSecure() && !mKeyguard.canSkipBouncer()) {
            mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                showConnectDialogOrDisconnect();
            });
        } else {
            showConnectDialogOrDisconnect();
        }
    }

    private void showConnectDialogOrDisconnect() {
        if (mController.isVpnRestricted()) {
            return;
        }
        if (mController.isVpnEnabled()) {
            mController.disconnectPrimaryVpn();
            return;
        }
        final List<VpnProfile> profiles = mController.getConfiguredLegacyVpns();
        final List<String> vpnApps = mController.getVpnAppPackageNames();
        if (profiles.isEmpty() && vpnApps.isEmpty()) {
            return;
        }

        mUiHandler.post(() -> {
            CharSequence[] labels = new CharSequence[profiles.size() + vpnApps.size()];
            int profileCount = profiles.size();
            for (int i = 0; i < profileCount; i++) {
                labels[i] = profiles.get(i).name;
            }
            for (int i = 0; i < vpnApps.size(); i++) {
                try {
                    labels[profileCount + i] = VpnConfig.getVpnLabel(mContext, vpnApps.get(i));
                } catch (PackageManager.NameNotFoundException e) {
                    labels[profileCount + i] = vpnApps.get(i);
                }
            }

            mDialog = new AlertDialog.Builder(mContext)
                    .setTitle(R.string.quick_settings_vpn_connect_dialog_title)
                    .setItems(labels, (dialog, which) -> {
                        if (which < profileCount) {
                            mController.connectLegacyVpn(profiles.get(which));
                        } else {
                            mController.launchVpnApp(vpnApps.get(which - profileCount));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            mDialog.getWindow().setType(LayoutParams.TYPE_KEYGUARD_DIALOG);
            SystemUIDialog.setShowForAllUsers(mDialog, true);
            SystemUIDialog.registerDismissListener(mDialog);
            SystemUIDialog.setWindowOnTop(mDialog);
            mUiHandler.post(() -> mDialog.show());
            mHost.collapsePanels();
        });
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_vpn_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.quick_settings_vpn_label);
        state.value = mController.isVpnEnabled();
        state.secondaryLabel = mController.getPrimaryVpnName();
        state.contentDescription = state.label;
        state.icon = ResourceIcon.get(R.drawable.ic_qs_vpn);
        boolean hasAnyVpn = mController.getConfiguredLegacyVpns().size() > 0
                || mController.getVpnAppPackageNames().size() > 0;
        if (mController.isVpnRestricted() || !hasAnyVpn) {
            state.state = Tile.STATE_UNAVAILABLE;
        } else if (state.value) {
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.VPN;
    }

    private final class Callback implements SecurityControllerCallback, KeyguardMonitor.Callback {
        @Override
        public void onStateChanged() {
            refreshState();
        }

        @Override
        public void onKeyguardShowingChanged() {
            refreshState();
        }
    };
}
