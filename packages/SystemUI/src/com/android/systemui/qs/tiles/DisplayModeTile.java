/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2018-2019 The LineageOS Project
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

import static android.provider.Settings.ACTION_DISPLAY_SETTINGS;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.ArrayUtils;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.DisplayModeState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import org.lineageos.internal.logging.LineageMetricsLogger;

import lineageos.hardware.DisplayMode;
import lineageos.hardware.LineageHardwareManager;

import javax.inject.Inject;

/** Quick settings tile: Display mode switcher **/
public class DisplayModeTile extends QSTileImpl<DisplayModeState> {

    private static final Intent DISPLAY_SETTINGS =
            new Intent(ACTION_DISPLAY_SETTINGS);

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_display_mode);

    private final CharSequence mTitle;
    private final LineageHardwareManager mHardware;
    private final DisplayMode[] mDisplayModes;

    @Inject
    public DisplayModeTile(
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
        mTitle = mContext.getString(R.string.quick_settings_display_mode);
        mHardware = LineageHardwareManager.getInstance(mContext);
        mDisplayModes = mHardware.getDisplayModes();
    }

    @Override
    public DisplayModeState newTileState() {
        return new DisplayModeState();
    }

    @Override
    protected void handleClick(@Nullable View view) {
        int next = getCurrentDisplayModeIndex() + 1;

        if (next >= mDisplayModes.length) {
            next = 0;
        }

        DisplayMode nextMode = mDisplayModes[next];
        mHardware.setDisplayMode(nextMode, false);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return DISPLAY_SETTINGS;
    }

    @Override
    public boolean isAvailable() {
        return mHardware.isSupported(LineageHardwareManager.FEATURE_DISPLAY_MODES);
    }

    @Override
    protected void handleUpdateState(DisplayModeState state, Object arg) {
        state.mode = arg == null ? getCurrentDisplayModeIndex() : (Integer) arg;
        state.label = mTitle;
        state.secondaryLabel = mDisplayModes[state.mode].name;
        state.icon = mIcon;
        state.state = Tile.STATE_ACTIVE;
    }

    @Override
    public CharSequence getTileLabel() {
        return mTitle;
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_LIVE_DISPLAY;
    }

    @Override
    public void handleSetListening(boolean listening) {
        // Do nothing
    }

    private int getCurrentDisplayModeIndex() {
        DisplayMode currentDisplayMode = mHardware.getCurrentDisplayMode();
        for (int i = 0; i < mDisplayModes.length; ++i) {
            DisplayMode displayMode = mDisplayModes[i];
            if (displayMode.id == currentDisplayMode.id
                    && displayMode.name.equals(currentDisplayMode.name)) {
                return i;
            }
        }
        return 0;
    }
}
