package com.nvidia;

import android.util.Log;
import android.content.Context;
import android.content.Intent;
import android.os.DeadObjectException;
import android.os.ServiceManager;
import com.nvidia.NvCPLSvc.INvCPLRemoteService;

public class NvAppProfiles {
    private static final String TAG = "NvAppProfiles";

    /**
     * Unique name used for NvCPLSvc to whitelist this class
     */
    static final String NV_APP_PROFILES_NAME = "Frameworks_NvAppProfiles";
    static final boolean DEBUG = false;
    private final Context mContext;
    private INvCPLRemoteService mNvCPLSvc = null;

    /**
     * Callback class given by the NvCPLService
     */

    public NvAppProfiles(Context context) {
        mContext = context;
    }

    public int getApplicationProfile(String packageName, int settingId) {
        int result = -1;
        getNvCPLService();

        if (mNvCPLSvc == null) {
            if (DEBUG) {
                Log.d(TAG, "NvCPLSvc is null");
            }

        } else {
            try {
                result = mNvCPLSvc.getAppProfileSettingInt(packageName, settingId);
            } catch (DeadObjectException doe) {
                if (DEBUG) {
                    Log.w(TAG, "App Profile: DeadObjectException trying to get new service object");
                }

                mNvCPLSvc = null;
                getNvCPLService();
                try {
                    if (mNvCPLSvc != null) {
                        result = mNvCPLSvc.getAppProfileSettingInt(packageName, settingId);
                    }
                } catch (Exception ex) {
                }
            } catch (Exception e) {
                Log.w(TAG, "App Profile: Failed to retrieve profile. Error="+e.getMessage());
            }
        }

        return result;
    }

    public String getApplicationProfileString(String packageName, int settingId) {
        String result = null;
        getNvCPLService();
        if (mNvCPLSvc == null) {
            if (DEBUG) {
                Log.d(TAG, "NvCPLSvc is null");
            }

        } else {
            try {
                result = mNvCPLSvc.getAppProfileSettingString(packageName, settingId);
            } catch (DeadObjectException doe) {
                if (DEBUG) {
                    Log.w(TAG, "App Profile: DeadObjectException trying to get new service object");
                }

                mNvCPLSvc = null;
                getNvCPLService();
                try {
                    if (mNvCPLSvc != null) {
                        result = mNvCPLSvc.getAppProfileSettingString(packageName, settingId);
                    }
                } catch (Exception ex) {
                }
            } catch (Exception e) {
                Log.w(TAG, "App Profile: Failed to retrieve profile. Error="+e.getMessage());
            }
        }

        return result;
    }

    public void powerHint(String packageName) {
        getNvCPLService();
        if (mNvCPLSvc != null) {
            try {
                mNvCPLSvc.powerHint(packageName);
            } catch (DeadObjectException doe) {
                if (DEBUG) {
                    Log.w(TAG, "App Profile: DeadObjectException trying to get new service object");
                }

                mNvCPLSvc = null;
                getNvCPLService();
                try {
                    if (mNvCPLSvc != null) {
                        mNvCPLSvc.powerHint(packageName);
                    }
                } catch (Exception ex) {
                }
            } catch (Exception e) {
                Log.w(TAG, "App Profile: Failed powerHint(). Error="+e.getMessage());
            }
        }
    }

    private void sendNvCPLSvcIntent(Intent intent) {
        if (mNvCPLSvc != null) {
            try {
                mNvCPLSvc.handleIntent(intent);
            } catch (DeadObjectException doe) {
                if (DEBUG) {
                    Log.w(TAG, "App Profile: DeadObjectException trying to get new service object");
                }

                mNvCPLSvc = null;
                getNvCPLService();
                try {
                    if (mNvCPLSvc != null) {
                        mNvCPLSvc.handleIntent(intent);
                    }
                } catch (Exception ex) {
                }
            } catch (Exception e) {
                Log.w(TAG, "App Profile: Failed to send intent. Error="+e.getMessage());
            }
        }
    }

    public void handleIntent(Intent intent) {
        getNvCPLService();
        if (mNvCPLSvc != null) {
            try {
                mNvCPLSvc.handleIntent(intent);
            } catch (DeadObjectException doe) {
                if (DEBUG) {
                    Log.w(TAG, "App Profile: DeadObjectException trying to get new service object");
                }

                mNvCPLSvc = null;
                getNvCPLService();
                try {
                    if (mNvCPLSvc != null) {
                        mNvCPLSvc.handleIntent(intent);
                    }
                } catch (Exception ex) {
                }
            } catch (Exception e) {
                Log.w(TAG, "App Profile: Failed to handle intent. Error="+e.getMessage());
            }
        }
    }

    private void getNvCPLService() {
        if (mNvCPLSvc == null) {
            try {
                mNvCPLSvc = INvCPLRemoteService.Stub.asInterface(ServiceManager.getService("nvcpl"));
            } catch (Exception ex) {
                Log.e(TAG, "Failed to bind to service. " + ex.getMessage());
            }
        }
    }
}
