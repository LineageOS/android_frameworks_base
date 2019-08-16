package com.nvidia.shieldtech;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import com.nvidia.shieldtech.INvHookBinder;

public class NvHookHelper {
    private static final String TAG = "NvHookHelper";
    private static NvHookClient mClient = null;
    private static Context mContext = null;
    private static NvHookHelper mInstance = null;

    private class NvHookClient {
        private static final String SERVICE_PACKAGE = "com.nvidia.shieldtech.hooks";
        private INvHookBinder mBinder;
        private ServiceConnection mConnection;

        private NvHookClient() {
            this.mBinder = null;
            this.mConnection = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    try {
                        synchronized (NvHookClient.this) {
                            NvHookClient.this.mBinder = INvHookBinder.Stub.asInterface(binder);
                        }
                    } catch (Exception e) {
                    }
                }

                public void onServiceDisconnected(ComponentName name) {
                    try {
                        synchronized (NvHookClient.this) {
                            NvHookClient.this.mBinder = null;
                        }
                    } catch (Exception e) {
                    }
                }
            };
        }

        public void connect() {
            try {
                Intent intent = new Intent(SERVICE_PACKAGE);
                ComponentName comp = intent.resolveSystemService(NvHookHelper.mContext.getPackageManager(), 0);
                if (comp == null) {
                    Log.e(NvHookHelper.TAG, "Unable to locate ShieldTech services");
                    return;
                }
                intent.setComponent(comp);
                if (!NvHookHelper.mContext.bindServiceAsUser(intent, this.mConnection, 65, UserHandle.CURRENT_OR_SELF)) {
                    Log.w(NvHookHelper.TAG, "bindService failed");
                }
            } catch (Exception ex) {
                Log.w(NvHookHelper.TAG, "connect threw an exception", ex);
            }
        }

        public void disconnect() {
            try {
                if (this.mBinder != null) {
                    NvHookHelper.mContext.unbindService(this.mConnection);
                }
            } catch (Exception e) {
            }
        }

        public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
            try {
                if (this.mBinder != null) {
                    return this.mBinder.interceptKeyBeforeQueueing(event, policyFlags);
                }
            } catch (DeadObjectException doe) {
                Log.w(NvHookHelper.TAG, "deliverInputEvent threw an exception", doe);
                this.mBinder = null;
                connect();
            } catch (RemoteException io) {
                Log.w(NvHookHelper.TAG, "interceptKeyBeforeQueueing threw an exception", io);
            }
            return policyFlags;
        }

        public int interceptKeyBeforeDispatching(KeyEvent event, int policyFlags) {
            try {
                if (this.mBinder != null) {
                    return this.mBinder.interceptKeyBeforeDispatching(event, policyFlags);
                }
            } catch (DeadObjectException doe) {
                Log.w(NvHookHelper.TAG, "deliverInputEvent threw an exception", doe);
                this.mBinder = null;
                connect();
            } catch (RemoteException io) {
                Log.w(NvHookHelper.TAG, "interceptKeyBeforeDispatching threw an exception", io);
            }
            return policyFlags;
        }

        public int deliverInputEvent(InputEvent event, int flags) {
            try {
                if (this.mBinder != null) {
                    return this.mBinder.deliverInputEvent(event, flags);
                }
            } catch (DeadObjectException doe) {
                Log.w(NvHookHelper.TAG, "deliverInputEvent threw an exception", doe);
                this.mBinder = null;
                connect();
            } catch (RemoteException io) {
                Log.w(NvHookHelper.TAG, "deliverInputEvent threw an exception", io);
            }
            return flags;
        }

        public void notifyAppResume(ComponentName component) {
            try {
                if (this.mBinder != null) {
                    this.mBinder.notifyAppResume(component);
                }
            } catch (DeadObjectException doe) {
                Log.w(NvHookHelper.TAG, "deliverInputEvent threw an exception", doe);
                this.mBinder = null;
                connect();
            } catch (RemoteException io) {
                Log.w(NvHookHelper.TAG, "notifyAppLaunch threw an exception", io);
            }
        }

        public void notifyInputFocusChange(String packageName) {
            try {
                if (this.mBinder != null) {
                    this.mBinder.notifyInputFocusChange(packageName);
                }
            } catch (DeadObjectException doe) {
                Log.w(NvHookHelper.TAG, "deliverInputEvent threw an exception", doe);
                this.mBinder = null;
                connect();
            } catch (RemoteException io) {
                Log.w(NvHookHelper.TAG, "notifyInputFocusChange threw an exception", io);
            }
        }

        public void notifyGoToSleepReason(int reason) {
            try {
                if (this.mBinder != null) {
                    this.mBinder.notifyGoToSleepReason(reason);
                }
            } catch (DeadObjectException doe) {
                Log.w(NvHookHelper.TAG, "deliverInputEvent threw an exception", doe);
                this.mBinder = null;
                connect();
            } catch (RemoteException io) {
                Log.w(NvHookHelper.TAG, "notifyGoToSleepReason threw an exception", io);
            }
        }
    }

    public static void init(Context context) {
        try {
            if (mInstance == null) {
                mInstance = new NvHookHelper(context);
            }
        } catch (Exception e) {
            Log.w(TAG, "An exception occurred initializing ShieldTech");
        }
    }

    public static void die() {
        try {
            if (mClient != null) {
                mClient.disconnect();
            }
        } catch (Exception e) {
        }
    }

    public static int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        try {
            if (mClient != null) {
                return mClient.interceptKeyBeforeQueueing(event, policyFlags);
            }
        } catch (Exception e) {
        }
        return policyFlags;
    }

    public static int interceptKeyBeforeDispatching(KeyEvent event, int policyFlags) {
        try {
            if (mClient != null) {
                return mClient.interceptKeyBeforeDispatching(event, policyFlags);
            }
        } catch (Exception e) {
        }
        return policyFlags;
    }

    public static int deliverInputEvent(InputEvent event, int flags) {
        try {
            if (mClient != null) {
                return mClient.deliverInputEvent(event, flags);
            }
        } catch (Exception e) {
        }
        return flags;
    }

    public static void notifyAppResume(ComponentName component) {
        try {
            if (mClient != null) {
                mClient.notifyAppResume(component);
            }
        } catch (Exception e) {
        }
    }

    public static void notifyInputFocusChange(String packageName) {
        try {
            if (mClient != null) {
                mClient.notifyInputFocusChange(packageName);
            }
        } catch (Exception e) {
        }
    }

    public static void notifyGoToSleepReason(int reason) {
        try {
            if (mClient != null) {
                mClient.notifyGoToSleepReason(reason);
            }
        } catch (Exception e) {
        }
    }

    private NvHookHelper(Context context) {
        mContext = context.getApplicationContext();
        if (mContext == null) {
            mContext = context;
        }
        mClient = new NvHookClient();
        mClient.connect();
    }
}
