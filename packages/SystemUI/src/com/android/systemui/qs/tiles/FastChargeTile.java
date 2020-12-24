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
import android.os.RemoteException;
import android.service.quicksettings.Tile;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import org.lineageos.internal.logging.LineageMetricsLogger;

import vendor.lineage.fastcharge.V1_0.IFastCharge;

import java.util.NoSuchElementException;

import javax.inject.Inject;

public class FastChargeTile extends QSTileImpl<BooleanState> {

    private IFastCharge mFastCharge;

    @Inject
    public FastChargeTile(QSHost host) {
        super(host);
        mFastCharge = getFastCharge();
        if (mFastCharge == null) {
            return;
        }
    }

    private void updateFastChargeState() {
        if (!isAvailable()) {
            return;
        }
    }

    @Override
    public boolean isAvailable() {
        return mFastCharge != null;
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
            boolean fastChargeEnabled = mFastCharge.isEnabled();

            if (mFastCharge.setEnabled(!fastChargeEnabled) != fastChargeEnabled) {
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
        return mContext.getString(R.string.quick_settings_fastcharge_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (!isAvailable()) {
            return;
        }

        if (state.slash == null) {
            state.slash = new SlashState();
        }

        state.icon = ResourceIcon.get(R.drawable.ic_qs_fastcharge);
        try {
            state.value = mFastCharge.isEnabled();
        } catch (RemoteException ex) {
            state.value = false;
            ex.printStackTrace();
        }
        state.slash.isSlashed = state.value;
        state.label = mContext.getString(R.string.quick_settings_fastcharge_label);

        if (!state.value) {
            state.state = Tile.STATE_INACTIVE;
        } else {
            state.state = Tile.STATE_ACTIVE;
        }

    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_FASTCHARGE;
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    private synchronized IFastCharge getFastCharge() {
        try {
            return IFastCharge.getService();
        } catch (RemoteException ex) {
            ex.printStackTrace();
        } catch (NoSuchElementException ex) {
            // service not available
        }

        return null;
    }

}
