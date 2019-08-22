/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
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
import android.provider.Settings.Global;
import android.service.quicksettings.Tile;
import android.view.WindowManagerPolicyControl;

import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.SystemSetting;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

import org.lineageos.internal.logging.LineageMetricsLogger;

/** Quick settings tile: Expanded desktop **/
public class ExpandedDesktopTile extends QSTileImpl<BooleanState> {

    private static final Intent EXPANDED_DESKTOP_SETTINGS =
            new Intent("org.lineageos.lineageparts.EXPANDED_DESKTOP_SETTINGS");

    private final SystemSetting mSetting;

    public ExpandedDesktopTile(QSHost host) {
        super(host);

        mSetting = new SystemSetting(mContext, mHandler, Settings.Global.POLICY_CONTROL) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        setEnabled(!mState.value);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return EXPANDED_DESKTOP_SETTINGS;
    }

    private void setEnabled(boolean enabled) {
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.POLICY_CONTROL,
                enabled ? "immersive.full=*" : "");
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean expandedDesktop = !"".equals(Settings.Global.getString(
                mContext.getContentResolver(), Settings.Global.POLICY_CONTROL));
        state.value = expandedDesktop;
        state.label = mContext.getString(R.string.quick_settings_expanded_desktop_label);
        if (expandedDesktop) {
            final int expandedDesktopStyle = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.POLICY_CONTROL_STYLE,
                WindowManagerPolicyControl.ImmersiveDefaultStyles.IMMERSIVE_FULL);

            switch (expandedDesktopStyle) {
                case WindowManagerPolicyControl.ImmersiveDefaultStyles.IMMERSIVE_STATUS:
                    state.contentDescription = mContext.getString(
                            R.string.accessibility_quick_settings_expanded_desktop_on_statusbar);
                    state.icon = ResourceIcon.get(R.drawable.ic_expdesk_hide_statusbar);
                    break;
                case WindowManagerPolicyControl.ImmersiveDefaultStyles.IMMERSIVE_NAVIGATION:
                    state.contentDescription =  mContext.getString(
                            R.string.accessibility_quick_settings_expanded_desktop_on_navbar);
                    state.icon = ResourceIcon.get(R.drawable.ic_expdesk_hide_navbar);
                    break;
                case WindowManagerPolicyControl.ImmersiveDefaultStyles.IMMERSIVE_FULL:
                default:
                    state.contentDescription =  mContext.getString(
                            R.string.accessibility_quick_settings_expanded_desktop_on_both);
                    state.icon = ResourceIcon.get(R.drawable.ic_expdesk_hide_both);
            }
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_expanded_desktop_off);
            state.icon = ResourceIcon.get(R.drawable.ic_expdesk_hide_none);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_expanded_desktop_label);
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_expanded_desktop_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_expanded_desktop_changed_off);
        }
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.DONT_LOG;
    }

    @Override
    public void handleSetListening(boolean listening) {
        // Do nothing
    }
}
