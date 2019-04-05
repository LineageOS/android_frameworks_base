/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.fingerprint;

import android.content.pm.PackageManager;
import android.view.View;

import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;

public class FODCircleViewImpl extends SystemUI implements CommandQueue.Callbacks {
    private static final String TAG = "FODCircleViewImpl";

    private FODCircleView mFodCircleView;

    @Override
    public void start() {
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            getComponent(CommandQueue.class).addCallbacks(this);
        }
    }

    @Override
    public void handleInDisplayFingerprintView(boolean show, boolean isEnrolling) {
        if (mFodCircleView == null) {
            mFodCircleView = new FODCircleView(mContext);
        }

        if (!mFodCircleView.viewAdded && show) {
            mFodCircleView.show(isEnrolling);
        } else if (mFodCircleView.viewAdded) {
            mFodCircleView.hide();
        }
    }
}
