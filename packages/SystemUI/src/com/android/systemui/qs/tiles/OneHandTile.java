/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2019 Syberia Project
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
import android.provider.Settings;
import android.service.quicksettings.Tile;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

import android.os.RemoteException;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

public class OneHandTile extends QSTileImpl<BooleanState> {

    //one handed mode
    private static final String EXTRA_ALIGNMENT_STATE = "alignment_state";
    private static final int EXTRA_ALIGNMENT_STATE_LEFT = 0;
    private static final int EXTRA_ALIGNMENT_STATE_RIGHT = 1;
    private static final String ACTION_ONEHAND_TRIGGER_EVENT =
            "com.android.server.wm.onehand.intent.action.ONEHAND_TRIGGER_EVENT";

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_onehandtile);

    private IWindowManager wm;

    public OneHandTile(QSHost host) {
        super(host);
        wm = WindowManagerGlobal.getWindowManagerService();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_onehand_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.RESURRECTED;
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    @Override
    protected void handleClick() {
        if (!isOneHandTurnedOn()) {
        toggleOneHandedMode(mContext, EXTRA_ALIGNMENT_STATE_RIGHT);
        } else {
        toggleOneHandedMode(mContext, -1);
        }
        refreshState();
    }

    @Override
    public void handleLongClick() {
        if (!isOneHandTurnedOn()) {
        toggleOneHandedMode(mContext, EXTRA_ALIGNMENT_STATE_LEFT);
        } else {
        toggleOneHandedMode(mContext, -1);
        }
        refreshState();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {

        state.value = isOneHandTurnedOn();
        state.icon = mIcon;
        state.label = mContext.getString(R.string.qs_onehand_label);
        state.state = Tile.STATE_INACTIVE;
    }

    private static void toggleOneHandedMode(Context context, int direction) {
    Intent intent = new Intent();
        intent.setAction(ACTION_ONEHAND_TRIGGER_EVENT);
        intent.putExtra(EXTRA_ALIGNMENT_STATE, direction);
        context.sendBroadcast(intent);
    }

    private boolean isOneHandTurnedOn() {
        try {
            return wm.isOnehandTurnedON();
        } catch (RemoteException e) {
            return false;
        }
    }
}
