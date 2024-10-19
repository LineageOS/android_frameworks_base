/*
 * Copyright (C) 2018-2020 The LineageOS Project
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

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import org.lineageos.internal.util.PackageManagerUtils;

import lineageos.hardware.LineageHardwareManager;
import lineageos.providers.LineageSettings;

import javax.inject.Inject;

public class ReadingModeTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "reading_mode";

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_reader);

    private static final Intent DISPLAY_SETTINGS = new Intent("android.settings.DISPLAY_SETTINGS");

    private LineageHardwareManager mHardware;

    @Inject
    public ReadingModeTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mHardware = LineageHardwareManager.getInstance(mContext);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        boolean newStatus = !isReadingModeEnabled();
        mHardware.set(LineageHardwareManager.FEATURE_READING_ENHANCEMENT, newStatus);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return DISPLAY_SETTINGS;
    }

    @Override
    public boolean isAvailable() {
        return mHardware.isSupported(LineageHardwareManager.FEATURE_READING_ENHANCEMENT);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = isReadingModeEnabled();
        state.icon = mIcon;
        if (state.value) {
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_reading_mode_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_reading_mode_off);
            state.state = Tile.STATE_INACTIVE;
        }
        state.label = getTileLabel();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_reading_mode);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    @Override
    public void handleSetListening(boolean listening) {
        // Do nothing
    }

    private boolean isReadingModeEnabled() {
        return mHardware.get(LineageHardwareManager.FEATURE_READING_ENHANCEMENT);
    }
}
