package com.nvidia.NvCPLSvc;

import android.content.Intent;
import java.util.List;

/** @hide */
interface INvCPLRemoteService {
    int getActiveProfileType(String str);
    byte[] getAppProfileSetting3DVStruct(String str);
    int getAppProfileSettingBoolean(String str, int i);
    int getAppProfileSettingInt(String str, int i);
    String getAppProfileSettingString(String str, int i);
    NvAppProfile[] getAppProfiles(in String[] strArr);
    String getDeviceSerial();
    List<NvSaverAppInfo> getNvSaverAppInfo(int i);
    int[] getProfileTypes(String str);
    IBinder getToolsApiInterface(String str);
    void handleIntent(in Intent intent);
    void powerHint(String str);
    boolean setActiveProfileType(String str, int i);
    boolean setAppProfileSetting(String str, int i, int i2, String str2);
    boolean setNvSaverAppInfo(String str, int i);
    boolean setNvSaverAppInfoAll(List<NvSaverAppInfo> list);
}
