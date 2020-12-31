package com.nvidia;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.nvidia.NvCPLSvc.INvCPLRemoteService;

public class NvAppProfiles {
    /**
     * Unique name used for NvCPLSvc to whitelist this class
     */
    static final String NV_APP_PROFILES_NAME = "Frameworks_NvAppProfiles";
    static final boolean DEBUG = false;
    private static final String TAG = "NvAppProfiles";
    private final Context mContext;
    private INvCPLRemoteService mNvCPLSvc = null;

    /**
     * Callback class given by the NvCPLService
     */

    public NvAppProfiles(Context context) {
        mContext = context;
    }

    public int getApplicationProfile(String packageName, int settingId) {
        getNvCPLService();
        if (mNvCPLSvc != null) {
            try {
                return mNvCPLSvc.getAppProfileSettingInt(packageName, settingId);
            } catch (RemoteException ex) {
                Log.w(TAG, "Failed to retrieve profile setting. Error=" + ex.getMessage());
            }
        }

        return -1;
    }

    public String getApplicationProfileString(String packageName, int settingId) {
        getNvCPLService();
        if (mNvCPLSvc != null) {
            try {
                return mNvCPLSvc.getAppProfileSettingString(packageName, settingId);
            } catch (RemoteException ex) {
                Log.w(TAG, "Failed to retrieve profile setting. Error=" + ex.getMessage());
            }
        }

        return null;
    }

    public void powerHint(String packageName) {
        getNvCPLService();
        if (mNvCPLSvc != null) {
            try {
                mNvCPLSvc.powerHint(packageName);
            } catch (RemoteException ex) {
                Log.w(TAG, "Failed to send power hint. Error=" + ex.getMessage());
            }
        }
    }

    private void getNvCPLService() {
        if (mNvCPLSvc == null) {
            try {
                mNvCPLSvc = INvCPLRemoteService.Stub.asInterface(
                        ServiceManager.getService("nvcpl"));
            } catch (Exception e) {
                Log.e(TAG, "Failed to bind to service. " + e.getMessage());
            }
        }
    }
}
