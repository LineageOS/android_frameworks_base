package com.nvidia.NvCPLSvc;

import android.content.Intent;
import java.util.List;

import com.nvidia.NvCPLSvc.NvAppProfile;
import com.nvidia.NvCPLSvc.NvSaverAppInfo;

/** @hide */
interface INvCPLRemoteService {
    int getActiveProfileType(String packageName);
    byte[] getAppProfileSetting3DVStruct(String pkgName);
    String getAppProfileSettingString(String pkgName, int settingId);
    int getAppProfileSettingInt(String pkgName, int settingId);
    int getAppProfileSettingBoolean(String pkgName, int settingId);
    NvAppProfile[] getAppProfiles(in String[] strArr);
    String getDeviceSerial();
    List<NvSaverAppInfo> getNvSaverAppInfo(int i);
    int[] getProfileTypes(String str);
    IBinder getToolsApiInterface(String str);
    void handleIntent(in Intent intent);
    void powerHint(String str);
    boolean setActiveProfileType(String packageName, int typeId);
    boolean setAppProfileSetting(String packageName, int typeId, int settingId, String value);
    boolean setNvSaverAppInfoAll(in List<NvSaverAppInfo> appList);
    boolean setNvSaverAppInfo(String pkgName, int list);
}
