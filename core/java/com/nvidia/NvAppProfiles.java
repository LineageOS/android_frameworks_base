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
    private IBinder mNvCPLSvcBinder = null;

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

    public void setPowerMode(int index) {
        if (DEBUG) Log.w(TAG, "Setting power mode: " + String.valueOf(index));

        Intent intent = new Intent();
        intent.setClassName(NvConstants.NvCPLSvc, NvConstants.NvCPLService);
        intent.putExtra(NvConstants.NvOrigin, 1);
        intent.putExtra(NvConstants.NvPowerMode , String.valueOf(index));

        handleIntent(intent);
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

    public void handleIntent(Intent intent) {
        getNvCPLService();
        if (mNvCPLSvc != null) {
            try {
                mNvCPLSvc.handleIntent(intent);
            } catch (RemoteException ex) {
                Log.w(TAG, "Failed to handle intent. Error=" + ex.getMessage());
            }
        }
    }

    private void getNvCPLService() {
        if (mNvCPLSvc == null || mNvCPLSvcBinder == null || !mNvCPLSvcBinder.isBinderAlive()) {
            mNvCPLSvcBinder = ServiceManager.getService("nvcpl");
            mNvCPLSvc = INvCPLRemoteService.Stub.asInterface(mNvCPLSvcBinder);
        }
    }
}
