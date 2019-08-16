/**
 * Copyright (c) 2016, NVIDIA CORPORATION.  All rights reserved.
 *
 * NVIDIA CORPORATION and its licensors retain all intellectual property
 * and proprietary rights in and to this software, related documentation
 * and any modifications thereto.  Any use, reproduction, disclosure or
 * distribution of this software and related documentation without an express
 * license agreement from NVIDIA CORPORATION is strictly prohibited.
 */

package com.nvidia.shieldtech;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;

/**
 * {@hide}
 */
public class NvHookHelper {
    private static final String TAG = "NvHookHelper";

    private static final String SERVICE_PACKAGE = "com.nvidia.shieldtech.hooks";

    public static void init(Context context) {
        try {
            if (!sInitialized) {
                Context aContext = context.getApplicationContext();
                if (aContext == null) aContext = context;
                Intent intent = new Intent(SERVICE_PACKAGE);
                ComponentName comp = intent.resolveSystemService(aContext.getPackageManager(), 0);
                if (comp != null) {
                    intent.setComponent(comp);
                    if (aContext.bindServiceAsUser(intent, sConnection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, UserHandle.CURRENT_OR_SELF)) {
                        sInitialized = true;
                    } else {
                        Log.e(TAG, "bindService failed");
                    }
                } else {
                    Log.e(TAG, "Unable to locate ShieldTech services");
                }
            }
        } catch (Exception ex) {
            // We don't allow *any* exceptions to be thrown to the Input Manager
            Log.e(TAG, "An exception occurred initializing NvHookHelper");
        }
    }

    public static void die() {
        // G.N.D.N.
    }

    public static int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        try {
            if (sBinder != null) return sBinder.interceptKeyBeforeQueueing(event, policyFlags);
        } catch (RemoteException io) {
            Log.w(TAG, "interceptKeyBeforeQueueing threw an exception", io);
        } catch (Exception ex) {
            // We don't allow *any* exceptions to be thrown to the Input Manager
        }
        return policyFlags;
    }

    public static int interceptKeyBeforeDispatching(KeyEvent event, int policyFlags) {
        try {
            if (sBinder != null) return sBinder.interceptKeyBeforeDispatching(event, policyFlags);
        } catch (RemoteException io) {
            Log.w(TAG, "interceptKeyBeforeDispatching threw an exception", io);
        } catch (Exception ex) {
            // We don't allow *any* exceptions to be thrown to the Input Manager
        }
        return policyFlags;
    }

    public static int deliverInputEvent(InputEvent event, int flags) {
        try {
            if (sBinder != null) return sBinder.deliverInputEvent(event, flags);
        } catch (RemoteException io) {
            Log.w(TAG, "deliverInputEvent threw an exception", io);
        } catch (Exception ex) {
            // We don't allow *any* exceptions to be thrown to the Input Manager
        }
        return flags;
    }

    public static void notifyAppResume(ComponentName component) {
        try {
            if (sBinder != null) sBinder.notifyAppResume(component);
        } catch (RemoteException io) {
            Log.w(TAG, "notifyAppLaunch threw an exception", io);
        } catch (Exception ex) {
            // We don't allow *any* exceptions to be thrown to the Input Manager
        }
    }

    public static void notifyInputFocusChange(String packageName) {
        try {
            if (sBinder != null) sBinder.notifyInputFocusChange(packageName);
        } catch (RemoteException io) {
            Log.w(TAG, "notifyInputFocusChange threw an exception", io);
        } catch (Exception ex) {
            // We don't allow *any* exceptions to be thrown to the Input Manager
        }
    }

    public static void notifyGoToSleepReason(int reason) {
        try {
            if (sBinder != null) sBinder.notifyGoToSleepReason(reason);
        } catch (RemoteException io) {
            Log.w(TAG, "notifyGoToSleepReason threw an exception", io);
        } catch (Exception ex) {
            // We don't allow *any* exceptions to be thrown to the Input Manager
        }
    }

    /////////////////////////////////////////////////////////////////////////
    //
    // Private Members
    //
    /////////////////////////////////////////////////////////////////////////

    private static boolean sInitialized = false;
    private static INvHookBinder sBinder = null;

    private static ServiceConnection sConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                synchronized (sConnection) {
                    // Due to the nature of NvHook, we must allow blocking for our AIDL calls
                    Binder.allowBlocking(binder);
                    sBinder = INvHookBinder.Stub.asInterface(binder);
                }
            } catch (Exception ex) {
                // We don't allow *any* exceptions to be thrown to the Input Manager
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            try {
                synchronized (sConnection) {
                    sBinder = null;
                }
            } catch (Exception ex) {
                // We don't allow *any* exceptions to be thrown to the Input Manager
            }
        }
    };
}
