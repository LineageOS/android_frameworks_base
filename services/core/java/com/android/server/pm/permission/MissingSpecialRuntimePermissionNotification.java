/*
 * Copyright (C) 2022 GrapheneOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.pm.permission;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseLongArray;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;

import java.util.UUID;

/**
 * Shows a notification when an app tried to perform an operation that is protected by a special
 * runtime permission (currently only OTHER_SENSORS) and was denied because of it.
 *
 * The notification isn't shown if the permission was explicitly denied by the user
 * (FLAG_PERMISSION_USER_SET), and it offers a "Don't show again" action that sets that flag.
 */
public class MissingSpecialRuntimePermissionNotification {
    private static final String TAG = "SrPermissionNotif";

    private static final String ACTION_DONT_SHOW_AGAIN =
            MissingSpecialRuntimePermissionNotification.class.getName() + ".DONT_SHOW_AGAIN";

    private static final ArrayMap<String, SparseLongArray> lastShownTracker = new ArrayMap<>();

    private static final Object receiverLock = new Object();
    private static boolean receiverRegistered;

    public static void maybeShow(Context ctx, String permissionName, int uid, String packageName) {
        if (!Manifest.permission.OTHER_SENSORS.equals(permissionName)) {
            Slog.w(TAG, "unexpected permission " + permissionName);
            return;
        }

        final long timestamp = SystemClock.uptimeMillis();

        synchronized (lastShownTracker) {
            SparseLongArray uids = lastShownTracker.get(permissionName);
            if (uids == null) {
                uids = new SparseLongArray();
                lastShownTracker.put(permissionName, uids);
            } else {
                long prevTs = uids.get(uid, 0);

                if (prevTs != 0 && (timestamp - prevTs) < 30_000L) {
                    // don't spam notifications for the same app and the same permission
                    return;
                }
            }
            uids.put(uid, timestamp);
        }

        final UserHandle user = UserHandle.of(UserHandle.getUserId(uid));

        var permManager = ctx.getSystemService(PermissionManager.class);

        if (permManager == null) {
            // might happen during bootup
            Slog.e(TAG, "PermissionManager is null");
            return;
        }

        final int permFlags = permManager.getPermissionFlags(packageName, permissionName, user);

        if ((permFlags & PackageManager.FLAG_PERMISSION_USER_SET) != 0) {
            return;
        }

        var nb = new Notification.Builder(ctx, SystemNotificationChannels.MISSING_PERMISSION);
        nb.setSmallIcon(R.drawable.stat_sys_warning);

        CharSequence appLabel = null;
        {
            ApplicationInfo appInfo = LocalServices.getService(PackageManagerInternal.class)
                    .getApplicationInfo(packageName, 0, Process.SYSTEM_UID, user.getIdentifier());
            if (appInfo != null) {
                appLabel = appInfo.loadLabel(ctx.getPackageManager());
            } else if (uid < Process.FIRST_APPLICATION_UID) {
                appLabel = packageName;
            }
            if (appLabel == null) {
                Slog.d(TAG, "appLabel is null; uid: " + uid + ", pkg: " + packageName
                        + ", perm: " + permissionName);
                return;
            }
        }
        nb.setContentTitle(ctx.getString(R.string.missing_sensors_permission_title, appLabel));

        nb.setContentText(ctx.getString(R.string.missing_sensors_permission_message));
        {
            Intent intent = ctx.getPackageManager().buildRequestPermissionsIntent(
                    new String[] { permissionName });
            intent.setAction(PackageManager.ACTION_REQUEST_PERMISSIONS_FOR_OTHER);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setIdentifier(UUID.randomUUID().toString());

            var pi = PendingIntent.getActivityAsUser(ctx, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE, null, user);
            nb.setContentIntent(pi);
        }
        nb.setAutoCancel(true);

        {
            ensureReceiverRegistered(ctx);

            var i = new Intent(ACTION_DONT_SHOW_AGAIN);
            // only the system can receive it, see ensureReceiverRegistered()
            i.setPackage(ctx.getPackageName());
            i.setIdentifier(UUID.randomUUID().toString());
            i.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
            i.putExtra(Intent.EXTRA_PERMISSION_NAME, permissionName);
            i.putExtra(Intent.EXTRA_USER, user);
            var dontShowAgainPi = PendingIntent.getBroadcast(ctx, 0, i,
                    PendingIntent.FLAG_IMMUTABLE);

            var action = new Notification.Action.Builder(null,
                    ctx.getText(R.string.notification_action_dont_show_again), dontShowAgainPi);
            nb.addAction(action.build());
        }

        var notifManager = ctx.getSystemService(NotificationManager.class);
        if (notifManager == null) {
            // might happen during bootup
            Slog.e(TAG, "NotificationManager is null");
            return;
        }

        notifManager.notifyAsUser(null, SystemMessage.NOTE_MISSING_PERMISSION_OTHER_SENSORS,
                nb.build(), user);
    }

    private static void ensureReceiverRegistered(Context ctx) {
        synchronized (receiverLock) {
            if (receiverRegistered) {
                return;
            }
            // RECEIVER_NOT_EXPORTED: only broadcasts sent by the system uid are delivered, i.e.
            // the immutable PendingIntent created above.
            ctx.registerReceiver(new DontShowAgainReceiver(),
                    new IntentFilter(ACTION_DONT_SHOW_AGAIN), null,
                    BackgroundThread.getHandler(), Context.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
    }

    static class DontShowAgainReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            Bundle args = intent.getExtras();
            if (args == null) {
                return;
            }
            String packageName = args.getString(Intent.EXTRA_PACKAGE_NAME);
            String permissionName = args.getString(Intent.EXTRA_PERMISSION_NAME);
            var user = args.getParcelable(Intent.EXTRA_USER, UserHandle.class);
            if (packageName == null || permissionName == null || user == null) {
                return;
            }

            final int flag = PackageManager.FLAG_PERMISSION_USER_SET;

            ctx.getSystemService(PermissionManager.class)
                    .updatePermissionFlags(packageName, permissionName, flag, flag, user);

            ctx.getSystemService(NotificationManager.class)
                    .cancelAsUser(null, SystemMessage.NOTE_MISSING_PERMISSION_OTHER_SENSORS, user);
        }
    }
}
