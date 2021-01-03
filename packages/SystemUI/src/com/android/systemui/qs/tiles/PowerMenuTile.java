/*
 * Copyright (C) 2021 The LineageOS Project
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
import android.os.RemoteException;
import android.service.quicksettings.Tile;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

import org.lineageos.internal.logging.LineageMetricsLogger;

import javax.inject.Inject;

public class PowerMenuTile extends QSTileImpl<BooleanState> {

    @Inject
    public PowerMenuTile(QSHost host) {
        super(host);
    }

    @Override
    protected void handleClick() {
        final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        try {
            wm.showGlobalActions();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.quick_settings_power_menu_label);
        state.icon = ResourceIcon.get(R.drawable.ic_qs_power_menu);
        state.state = Tile.STATE_ACTIVE;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_power_menu_label);
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_POWERMENU;
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    public void handleSetListening(boolean listening) {}
}
