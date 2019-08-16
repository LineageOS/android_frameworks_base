package com.nvidia.shieldtech;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.Log;

public class NvAppNotifier {
    private static String sActionStr;
    private static Handler sHandler = null;
    private static int sPackageResId = -1;
    private static String sPackageStr;
    private static String sReceiverStr;

    public static void sendAppLaunchIntentLocked(final Context context, ComponentName component) {
        if (sPackageResId == -1) {
            sPackageResId = context.getResources().getIdentifier("nv_appnotifier_target_package", "string", context.getPackageName());
            if (sPackageResId != 0) {
                sHandler = new Handler(Looper.getMainLooper()) {
                    public void handleMessage(Message msg) {
                        NvAppNotifier.sendNotification(context, (ComponentName) msg.obj);
                    }
                };
                sPackageStr = context.getResources().getString(sPackageResId);
                sReceiverStr = context.getResources().getString(context.getResources().getIdentifier("nv_appnotifier_target_receiver", "string", context.getPackageName()));
                sActionStr = context.getResources().getString(context.getResources().getIdentifier("nv_appnotifier_action", "string", context.getPackageName()));
            }
        }
        if (sPackageResId != 0) {
            queueNotification(component);
        }
    }

    private static void queueNotification(ComponentName component) {
        sHandler.sendMessage(sHandler.obtainMessage(sPackageResId, component));
        NvHookHelper.notifyAppResume(component);
    }

    private static void sendNotification(Context context, ComponentName component) {
        try {
            Intent intent = new Intent();
            intent.setAction(sActionStr);
            intent.setData(Uri.parse(component.getPackageName()));
            intent.putExtra("ComponentName", (Parcelable) component);
            intent.setComponent(new ComponentName(sPackageStr, sReceiverStr));
            context.sendBroadcastAsUser(intent, UserHandle.CURRENT_OR_SELF);
        } catch (Exception e) {
            Log.e("NvAppNotifier", "broadcast failed: " + sPackageStr + ", " + sReceiverStr + ", " + sActionStr, e);
        }
    }
}
