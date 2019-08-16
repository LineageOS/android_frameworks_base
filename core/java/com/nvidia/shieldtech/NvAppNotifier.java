/*
 * Copyright (c) 2012-2016 NVIDIA Corporation.  All rights reserved.
 *
 */

package com.nvidia.shieldtech;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.Log;

/**
 * @hide
 *
 */
public class NvAppNotifier {
    private static int sPackageResId = -1;
    private static String sPackageStr;
    private static String sReceiverStr;
    private static String sActionStr;
    private static Handler sHandler = null;

    /*
     * Construct an intent to notify that an app has been launched
     * Must be safe to use while caller is holding lock
     * Uses it's own handler
     */
    public static void sendAppLaunchIntentLocked(final Context context, final ComponentName component) {
        if (sPackageResId == -1) {
            // one time check whether this is supported on this platform, result of 0 means not found
            sPackageResId = context.getResources().getIdentifier("nv_appnotifier_target_package", "string", context.getPackageName());
            if (sPackageResId != 0) {
                sHandler = new Handler(Looper.getMainLooper()) {
                    @Override
                    public void handleMessage(Message msg) {
                        sendNotification(context, (ComponentName)msg.obj);
                    }
                };
                sPackageStr = context.getResources().getString(sPackageResId);
                int receiverId = context.getResources().getIdentifier("nv_appnotifier_target_receiver", "string", context.getPackageName());
                sReceiverStr = context.getResources().getString(receiverId);
                int actionId = context.getResources().getIdentifier("nv_appnotifier_action", "string", context.getPackageName());
                sActionStr = context.getResources().getString(actionId);
            }
        }
        if (sPackageResId != 0) {
            queueNotification(component);
        }
    }

    private static void queueNotification(ComponentName component) {
        sHandler.sendMessage(sHandler.obtainMessage(sPackageResId, component));
        com.nvidia.shieldtech.NvHookHelper.notifyAppResume(component);
    }

    private static void sendNotification(Context context, ComponentName component) {
        try {
            Intent intent = new Intent();
            intent.setAction(sActionStr);
            intent.setData(Uri.parse(component.getPackageName()));
            intent.putExtra("ComponentName", component);
            intent.setComponent(new ComponentName(sPackageStr, sReceiverStr));
            context.sendBroadcastAsUser (intent, UserHandle.CURRENT_OR_SELF);
        } catch (Exception e) {
            Log.e("NvAppNotifier", "broadcast failed: " + sPackageStr + ", " + sReceiverStr + ", " + sActionStr, e);
        }
    }
}
