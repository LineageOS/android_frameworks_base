/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.MobileSignalController;
import com.android.systemui.statusbar.policy.MobileSignalController.MobileIconGroup;

public class TelephonyIcons {
    //***** Data connection icons
    static final int FLIGHT_MODE_ICON = R.drawable.stat_sys_airplane_mode;

    static int ICON_LTE = R.drawable.ic_lte_mobiledata;
    static int ICON_LTE_PLUS = R.drawable.ic_lte_plus_mobiledata;
    static int ICON_G = R.drawable.ic_g_mobiledata;
    static int ICON_E = R.drawable.ic_e_mobiledata;
    static int ICON_H = R.drawable.ic_h_mobiledata;
    static int ICON_H_PLUS = R.drawable.ic_h_plus_mobiledata;
    static int ICON_3G = R.drawable.ic_3g_mobiledata;
    static int ICON_4G = R.drawable.ic_4g_mobiledata;
    static int ICON_4G_PLUS = R.drawable.ic_4g_plus_mobiledata;
    static int ICON_1X = R.drawable.ic_1x_mobiledata;

    static final MobileIconGroup CARRIER_NETWORK_CHANGE = new MobileIconGroup(
            "CARRIER_NETWORK_CHANGE",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.carrier_network_change_mode,
            0,
            false);

    static MobileIconGroup THREE_G = new MobileIconGroup(
            "3G",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_3g,
            TelephonyIcons.ICON_3G,
            true);

    static final MobileIconGroup WFC = new MobileIconGroup(
            "WFC",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            0, 0, false);

    static final MobileIconGroup UNKNOWN = new MobileIconGroup(
            "Unknown",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            0, 0, false);

    static MobileIconGroup E = new MobileIconGroup(
            "E",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_edge,
            TelephonyIcons.ICON_E,
            false);

    static MobileIconGroup ONE_X = new MobileIconGroup(
            "1X",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_cdma,
            TelephonyIcons.ICON_1X,
            true);

    static MobileIconGroup G = new MobileIconGroup(
            "G",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_gprs,
            TelephonyIcons.ICON_G,
            false);

    static MobileIconGroup H = new MobileIconGroup(
            "H",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_3_5g,
            TelephonyIcons.ICON_H,
            false);

    static MobileIconGroup H_PLUS = new MobileIconGroup(
            "H+",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_3_5g_plus,
            TelephonyIcons.ICON_H_PLUS,
            false);

    static MobileIconGroup FOUR_G = new MobileIconGroup(
            "4G",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_4g,
            TelephonyIcons.ICON_4G,
            true);

    static MobileIconGroup FOUR_G_PLUS = new MobileIconGroup(
            "4G+",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0,0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_4g_plus,
            TelephonyIcons.ICON_4G_PLUS,
            true);

    static MobileIconGroup LTE = new MobileIconGroup(
            "LTE",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_lte,
            TelephonyIcons.ICON_LTE,
            true);

    static MobileIconGroup LTE_PLUS = new MobileIconGroup(
            "LTE+",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.data_connection_lte_plus,
            TelephonyIcons.ICON_LTE_PLUS,
            true);

    static final MobileIconGroup DATA_DISABLED = new MobileIconGroup(
            "DataDisabled",
            null,
            null,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH,
            0, 0,
            0,
            0,
            AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0],
            R.string.cell_data_off_content_description,
            0,
            false);

    public static void updateIcons(boolean useOldStyle) {
        TelephonyIcons.ICON_LTE = LTE.mDataType = useOldStyle ?
                R.drawable.stat_sys_data_fully_connected_lte : R.drawable.ic_lte_mobiledata;
        TelephonyIcons.ICON_LTE_PLUS = LTE_PLUS.mDataType = useOldStyle ?
                R.drawable.stat_sys_data_fully_connected_lte_plus : R.drawable.ic_lte_plus_mobiledata;
        TelephonyIcons.ICON_G = G.mDataType = useOldStyle ?
                R.drawable.stat_sys_data_fully_connected_g : R.drawable.ic_g_mobiledata;
        TelephonyIcons.ICON_E = E.mDataType = useOldStyle ?
                R.drawable.stat_sys_data_fully_connected_e : R.drawable.ic_e_mobiledata;
        TelephonyIcons.ICON_H = H.mDataType = useOldStyle ?
                R.drawable.stat_sys_data_fully_connected_h : R.drawable.ic_h_mobiledata;
        TelephonyIcons.ICON_H_PLUS = H_PLUS.mDataType = useOldStyle ?
                R.drawable.stat_sys_data_fully_connected_hp : R.drawable.ic_h_plus_mobiledata;
        TelephonyIcons.ICON_3G = THREE_G.mDataType = useOldStyle ?
                R.drawable.stat_sys_data_fully_connected_3g : R.drawable.ic_3g_mobiledata;
        TelephonyIcons.ICON_4G = FOUR_G.mDataType = useOldStyle ?
                R.drawable.stat_sys_data_fully_connected_4g : R.drawable.ic_4g_mobiledata;
        TelephonyIcons.ICON_4G_PLUS = FOUR_G_PLUS.mDataType = useOldStyle ?
                R.drawable.stat_sys_data_fully_connected_4g_plus : R.drawable.ic_4g_plus_mobiledata;
        TelephonyIcons.ICON_1X = ONE_X.mDataType = useOldStyle ?
                R.drawable.stat_sys_data_fully_connected_1x : R.drawable.ic_1x_mobiledata;
    }
}

