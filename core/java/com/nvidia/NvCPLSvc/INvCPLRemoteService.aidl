/*
 * Copyright (c) 2012-2014, NVIDIA Corporation.  All rights reserved.
 *
 * NVIDIA Corporation and its licensors retain all intellectual property and
 * proprietary rights in and to this software and related documentation.  Any
 * use, reproduction, disclosure or distribution of this software and related
 * documentation without an express license agreement from NVIDIA Corporation
 * is strictly prohibited.
 */

package com.nvidia.NvCPLSvc;

import android.content.Intent;
import java.util.List;

import com.nvidia.NvCPLSvc.NvAppProfile;
import com.nvidia.NvCPLSvc.NvSaverAppInfo;

/** @hide */
interface INvCPLRemoteService {
    IBinder getToolsApiInterface(String str);
    String getAppProfileSettingString(String pkgName, int settingId);
    int getAppProfileSettingInt(String pkgName, int settingId);
    int getAppProfileSettingBoolean(String pkgName, int settingId);
    byte[] getAppProfileSetting3DVStruct(String pkgName);
    void handleIntent(in Intent intent);
    boolean setNvSaverAppInfo(String pkgName, int list);
    boolean setNvSaverAppInfoAll(in List<NvSaverAppInfo> appList);
    List<NvSaverAppInfo> getNvSaverAppInfo(int i);
    boolean setAppProfileSetting(String packageName, int typeId, int settingId, String value);
    int getActiveProfileType(String packageName);
    int[] getProfileTypes(String str);
    boolean setActiveProfileType(String packageName, int typeId);
    NvAppProfile[] getAppProfiles(in String[] strArr);
    String getDeviceSerial();
    void powerHint(String str);
}
