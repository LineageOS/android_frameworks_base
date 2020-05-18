/**
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

package com.android.systemui.biometrics;

import android.content.Context;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;

import java.util.NoSuchElementException;

public class FODUtils {
    private static int sHeight = -1;

    public static int getHeight(Context context, boolean includeDecor) {
        if (sHeight != -1) return sHeight;

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon == null) {
            return 0;
        }
        DisplayMetrics dm = new DisplayMetrics();
        if (includeDecor) {
            context.getSystemService(WindowManager.class).getDefaultDisplay().getMetrics(dm);
        } else {
            context.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
        }
        try {
            return dm.heightPixels - daemon.getPositionY() + daemon.getSize() / 2;
        } catch (NoSuchElementException | RemoteException e) {
            return 0;
        }
    }

    private static IFingerprintInscreen getFingerprintInScreenDaemon() {
        try {
            return IFingerprintInscreen.getService();
        } catch (NoSuchElementException | RemoteException e) {
            // do nothing
        }
        return null;
    }
}
