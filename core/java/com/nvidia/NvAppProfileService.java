/*
 * Copyright (c) 2012 - 2014 NVIDIA Corporation.  All rights reserved.
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
 *
 * Class structure based upon Camera in Camera.java:
 * Copyright (C) 2009 The Android Open Source Project
 */

package com.nvidia;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.nvidia.profilemanager.NvAppProfileSettingId;

import java.util.Arrays;
import java.util.List;

/**
 * @hide
 */
public class NvAppProfileService {
    private static final String TAG = "NvAppProfileService";
    private static final String APP_START_ACTION =
            "com.nvidia.NvAppProfileService.action.APP_START";
    private static final String APP_START_TARGET_PACKAGE = "com.nvidia.stats";
    private static final String FEATURE_POWER_BUDGET_CONTROL =
            "nvidia.feature.power_budget_control";
    private static final String FEATURE_FAN_ON_DEVICE = "nvidia.feature.fan_on_device";
    private static final String NvPowerModeProperty = "persist.vendor.sys.NV_POWER_MODE";

    private final NvAppProfiles mAppProfile;
    private final Context mContext;

    private boolean mInitAppProfiles = false;
    private boolean mFanCapEnabled = false;
    private boolean mPbcEnabled = false;

    public NvAppProfileService(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            mContext = context;
        } else {
            mContext = appContext;
        }

        mAppProfile = new NvAppProfiles(mContext);
    }

    private static String getPackageName(String appName) {
        int index = appName.indexOf('/');
        if (index < 0) {
            Log.e(TAG, "appName does not contain '/'. " +
                    "The packageName cannot be extracted from appName!");
            return null;
        }
        return appName.substring(0, index);
    }

    /*
     * These are functions that depend on NvAppProfiles and may or may not
     * be supported for certain platforms. In the latter case, these methods
     * should return -1.
     */
    public boolean canForceHwUi(String appName) {
        if (appName == null) {
            return false;
        }

        String packageName = getPackageName(appName);
        if (packageName == null && mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.FORCE_HW_UI) <= 0) {
            return false;
        }

        return true;
    }

    public boolean getAppProfileFRCEnable(String packageName) {
        return packageName != null && mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.VIDEO_FRC_ENABLE) == 1;
    }

    public boolean getAppProfileCreateSecureDecoder(String packageName) {
        return packageName != null && mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.VIDEO_SECURE_DECODE) == 1;
    }

    public boolean getAppProfileTSFilterEnable(String packageName) {
        return packageName != null && mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.VIDEO_TS_FILTERING) == 1;
    }

    public boolean getAppProfileNvidiaCertification(String packageName) {
        return packageName != null && mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.NVIDIA_VIDEO_CERTIFICATION_ENABLED) == 1;
    }

    public boolean getAppProfileDisableApp(String packageName) {
        return packageName != null && mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.DISABLE_APP) == 1;
    }

    private int getAppProfileCpuScalingMinFreq(String packageName) {
        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.SCALING_MIN_FREQ);
    }

    private int getAppProfileCpuCoreBias(String packageName) {
        return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.CORE_BIAS);
    }

    private int getAppProfileGpuScaling(String packageName) {
        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.GPU_SCALING);
    }

    private int getAppProfileCpuMaxNormalFreq(String packageName) {
        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.CPU_FREQ_BIAS);
    }

    private int getAppProfileCpuMaxNormalFreqPercent(String packageName) {
        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.MAX_CPU_FREQ_PCT);
    }

    private int getAppProfileCpuMinCore(String packageName) {
        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.MIN_CPU_CORES);
    }

    private int getAppProfileCpuMaxCore(String packageName) {
        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.MAX_CPU_CORES);
    }

    private int getAppProfileGpuCbusCapLevel(String packageName) {
        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.GPU_CORE_CAP);
    }

    private int getAppProfileEdpMode(String packageName) {
        return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.EDP_MODE);
    }

    private int getAppProfilePbcPwr(String packageName) {
        if (!mPbcEnabled) return -1;

        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.PBC_PWR_LIMIT);
    }

    private int getAppProfileFanCap(String packageName) {
        if (!mFanCapEnabled) return -1;

        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.FAN_PWM_CAP);
    }

    private int getAppProfileVoltTempMode(String packageName) {
        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.VOLT_TEMP_MODE);
    }

    private int getAppProfileAggresivePrismEnable(String packageName) {
        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.AGGRESSIVE_PRISM_ENABLE);
    }

    private int getAppProfileDevicePowerMode(String packageName) {
        return mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.SYSTEM_POWER_MODE);
    }

    public String getAppProfileRegionEnableList(String packageName) {
        return mAppProfile.getApplicationProfileString(packageName,
                NvAppProfileSettingId.SET_REGION_LIST);
    }

    public int getAppProfileNvidiaBBCApps(String packageName) {
        return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.BBC_APPS);
    }

    public List<String> getAppProfileForceQueryAppList() {
        String applicationProfileString = mAppProfile.getApplicationProfileString(
                "com.nvidia.shield.force_query_app_list",
                NvAppProfileSettingId.FORCE_QUERY_PACKAGES);
        if (TextUtils.isEmpty(applicationProfileString)) {
            return null;
        }
        return Arrays.asList(applicationProfileString.split(";"));
    }

    public List<String> getAppProfileAutoMediaScanPackages() {
        String applicationProfileString = mAppProfile.getApplicationProfileString(
                "com.nvidia.shield.auto_media_scan_packages",
                NvAppProfileSettingId.AUTO_MEDIA_SCAN_PACKAGES);
        if (TextUtils.isEmpty(applicationProfileString)) {
            return null;
        }
        return Arrays.asList(applicationProfileString.split(";"));
    }

    private int retrievePowerMode() {
        final String powerMode = SystemProperties.get(NvPowerModeProperty);
        if (powerMode != null) {
            try {
                return Integer.parseInt(powerMode);
            } catch (NumberFormatException ex) {
                // Fallthrough to error case
            }
        }

        return -1;
    }

    private void setGpuModeSetting(String packageName) {
        mAppProfile.getApplicationProfile(packageName,
                NvAppProfileSettingId.GPU_MODESET_ENABLE);
    }

    /**
     * Interface for the caller
     */
    public void setAppProfile(String packageName) {
        // Greedy initialization of App Profiles
        if (!mInitAppProfiles) {
            PackageManager pm = mContext.getPackageManager();
            mPbcEnabled = pm.hasSystemFeature(FEATURE_POWER_BUDGET_CONTROL);
            mFanCapEnabled = pm.hasSystemFeature(FEATURE_FAN_ON_DEVICE);

            Log.w(TAG, "Enabled");
            mInitAppProfiles = true;
        }

        mAppProfile.powerHint(packageName);

        Intent intent = new Intent(APP_START_ACTION);
        intent.setPackage(APP_START_TARGET_PACKAGE);
        intent.putExtra("AppStartId", packageName);
        mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT,
                "nvidia.permission.READ_APP_START_INFO");
    }
}
