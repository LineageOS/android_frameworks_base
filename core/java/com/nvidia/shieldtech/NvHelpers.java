/**
 * Copyright (c) 2014-2016, NVIDIA CORPORATION.  All rights reserved.
 *
 * NVIDIA CORPORATION and its licensors retain all intellectual property
 * and proprietary rights in and to this software, related documentation
 * and any modifications thereto.  Any use, reproduction, disclosure or
 * distribution of this software and related documentation without an express
 * license agreement from NVIDIA CORPORATION is strictly prohibited.
 */

package com.nvidia.shieldtech;

import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;
import android.view.Surface;

/**
 * {@hide}
 */
public class NvHelpers {
    private static final String TAG = "NvHelpers";

    public static boolean isConsoleMode() {
        return (SystemProperties.getInt("persist.vendor.tegra.stb.mode",0) != 0);
    }

    /*
     * Sets Wifi enabled state
     * Returns false if unable to do so
     */
    public static boolean toggleWifi (Context context, boolean isEnabled) {
        // punt to shieldtech to check for connected devices
        return toggleSettingIfPossible (context, "nvidia.shieldtech.action.ENABLE_WIFI", isEnabled);
    }

    /*
     * Sets bluetooth enabled state
     * Returns false if unable to do so
     */
    public static boolean toggleBluetooth (Context context, boolean isEnabled) {
        // punt to shieldtech to check for connected devices
        return toggleSettingIfPossible (context, "nvidia.shieldtech.action.ENABLE_BT", isEnabled);
    }

    /*
     * Sets airplane mode enabled state
     * Returns false if unable to do so
     */
    public static boolean toggleAirplaneMode (Context context, boolean isEnabled) {
        // punt to shieldtech to check for connected devices
        return toggleSettingIfPossible (context, "nvidia.shieldtech.action.ENABLE_AIRPLANE_MODE", isEnabled);
    }

    /*
     * Locates a handler for the request
     * Returns false if unable to do so or if currently Guest user
     */
    private static boolean toggleSettingIfPossible (Context context, String settingAction, boolean isEnabled) {
        Intent safeToggleIntent = new Intent(settingAction);

        if (null != safeToggleIntent.resolveActivity(context.getPackageManager())) {
            safeToggleIntent.putExtra("enable", isEnabled);
            safeToggleIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivityAsUser(safeToggleIntent, UserHandle.CURRENT_OR_SELF);
            return true;
        }
        return false;
    }
}
