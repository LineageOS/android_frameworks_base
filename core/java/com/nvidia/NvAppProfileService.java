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

/**
 * @file
 * <b>NVIDIA Tegra Android Power Management</b>
 *
 * @b Description: Exposes App Profiles system
 *    to frameworks
 */

package com.nvidia;
import android.os.SystemProperties;
import android.util.Log;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.PowerManager;

import com.nvidia.profilemanager.NvAppProfileSettingId;

/**
 * @hide
 *
 */
public class NvAppProfileService {
    private static final String TAG = "NvAppProfileService";
    private static final String FEATURE_POWER_BUDGET_CONTROL = "nvidia.feature.power_budget_control";
    private static final String FEATURE_FAN_ON_DEVICE = "nvidia.feature.fan_on_device";
    private NvAppProfiles mAppProfile;
    private PowerManager mPowerManager;
    private InputManager mInputManager;
    private NvWhitelistService mWhitelistService;
    private Context mContext;
    private boolean enableAppProfiles;
    private boolean pbcEnabled;
    private boolean fanCapEnabled;
    private boolean initAppProfiles;
    private boolean isStylusSupported;

    public NvAppProfileService (Context context) {
      Context appContext = context.getApplicationContext();
      if (appContext == null) {
          mContext = context;
      } else {
          mContext = appContext;
      }
      mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
      mAppProfile = new NvAppProfiles(mContext);
      mWhitelistService = new NvWhitelistService(mContext);
      pbcEnabled = false;
      fanCapEnabled = false;
      initAppProfiles = false;
      isStylusSupported = SystemProperties.getBoolean("ro.feature.stylus", false);
      if (isStylusSupported) {
          try {
              mInputManager = (InputManager)mContext.getSystemService(Context.INPUT_SERVICE);
          } catch (Exception e){
              Log.w(TAG, "InputManager not started: " + e);
          }
      }
    }

    private static String getPackageName(String appName) {
        int index = appName.indexOf('/');
        if (index < 0) {
            Log.e(TAG, "appName does not contain '/'. the packageName cannot be extracted from appName!");
            return null;
        }
        return appName.substring(0, index);
    }

    public boolean canForceHwUi(String appName) {
      if (appName == null)
          return false;

      final String packageName = getPackageName(appName);

      if (packageName == null)
          return false;

      int forceHwUi = mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.FORCE_HW_UI);

      if (forceHwUi <= 0)
        return false;

      return true;
    }

    public boolean appStylusFingerOnlyMode(String packageName) {
      if (packageName == null)
          return true;

      int mode = mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.STYLUS_FINGER_ONLY_MODE);

      if (mode != 0)
        return true;

      return false;
    }

    /*
     * These are functions that depend on NvAppProfiles and may or may not
     * be supported for certain platforms. In the latter case, these methods
     * should return -1.
     */
    public boolean getAppProfileFRCEnable(String packageName) {
      return packageName != null && mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.VIDEO_FRC_ENABLE) == 1;
    }

    public boolean getAppProfileCreateSecureDecoder(String packageName) {
      return packageName != null && mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.VIDEO_SECURE_DECODE) == 1;
    }

    public boolean getAppProfileTSFilterEnable(String packageName) {
      return packageName != null && mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.VIDEO_TS_FILTERING) == 1;
    }

    public boolean getAppProfileNvidiaCertification(String packageName) {
      return packageName != null && mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.NVIDIA_VIDEO_CERTIFICATION_ENABLED) == 1;
    }

    public int killProcessBelowAdj(String packageName) {
      if (packageName == null) {
          return -1;
      }
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.KILL_PROCESS_BELOW_ADJ);
    }

    public NvWhitelistService getWhitelistService() {
        return mWhitelistService;
    }

    public boolean getAppProfileDisableApp(String packageName) {
      boolean z = true;
      if (packageName == null) {
          return false;
      }
      if (mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.DISABLE_APP) != 1) {
          z = false;
      }
      return z;
    }

    private int getAppProfileCpuScalingMinFreq(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.SCALING_MIN_FREQ);
    }

    private int getAppProfileCpuCoreBias(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.CORE_BIAS);
    }

    private int getAppProfileGpuScaling(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.GPU_SCALING);
    }

    private int getAppProfileCpuMaxNormalFreq(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.CPU_FREQ_BIAS);
    }

    private int getAppProfileCpuMaxNormalFreqPercent(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.MAX_CPU_FREQ_PCT);
    }

    private int getAppProfileCpuMinCore(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.MIN_CPU_CORES);
    }

    private int getAppProfileCpuMaxCore(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.MAX_CPU_CORES);
    }

    private int getAppProfileGpuCbusCapLevel(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.GPU_CORE_CAP);
    }

    private int getAppProfileEdpMode(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.EDP_MODE);
    }

    private int getAppProfilePbcPwr(String packageName) {
      if (!pbcEnabled)
        return -1;

      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.PBC_PWR_LIMIT);
    }

    private int getAppProfileFanCap(String packageName) {
      if (!fanCapEnabled)
        return -1;

      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.FAN_PWM_CAP);
    }

    private int getAppProfileVoltTempMode(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.VOLT_TEMP_MODE);
    }

    private int getAppProfileAggresivePrismEnable(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.AGGRESSIVE_PRISM_ENABLE);
    }

    private int getAppProfileDevicePowerMode(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.SYSTEM_POWER_MODE);
    }

    public String getAppProfileRegionEnableList(String packageName) {
      return mAppProfile.getApplicationProfileString(packageName, NvAppProfileSettingId.SET_REGION_LIST);
    }

    public int getAppProfileNvidiaBBCApps(String packageName) {
      return mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.BBC_APPS);
    }

    private int retrievePowerMode() {
        int powerMode = -1;
        String strPowerMode = SystemProperties.get(NvConstants.NvPowerModeProperty);
        if (strPowerMode != null) {
            try {
                powerMode = Integer.parseInt(strPowerMode);
            }
            catch (NumberFormatException nfe) {
                // no need to worry about this
            }
        }
        return powerMode;
    }

    private void setGpuModeSetting(String packageName) {
      if (mAppProfile.getApplicationProfile(packageName, NvAppProfileSettingId.GPU_MODESET_ENABLE) != 1) {
      }
    }

    /*
     * Interface for the caller
     */
    public void setAppProfile(String packageName) {
      // Greedy initialization of App Profiles
      if (!initAppProfiles) {
        PackageManager pm = mContext.getPackageManager();
        if (pm.hasSystemFeature(FEATURE_POWER_BUDGET_CONTROL))
          pbcEnabled = true;
        if (pm.hasSystemFeature(FEATURE_FAN_ON_DEVICE))
          fanCapEnabled = true;
        Log.w(TAG, "App Profiles: Enabled");
        initAppProfiles = true;
      }
      mAppProfile.powerHint(packageName);
      setGpuModeSetting(packageName);
    }
}
