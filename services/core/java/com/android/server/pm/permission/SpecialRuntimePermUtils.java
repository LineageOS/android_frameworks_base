package com.android.server.pm.permission;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.EmptyArray;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

/**
 * "Special runtime permissions" are runtime permissions that are granted by default at install
 * time (to preserve app compatibility) and can then be revoked by the user like any other runtime
 * permission. They are granted regardless of the app's targetSdk.
 */
public class SpecialRuntimePermUtils {
    private static final String TAG = SpecialRuntimePermUtils.class.getSimpleName();

    private static final ArraySet<String> specialRuntimePermissions = new ArraySet<>(new String[] {
            Manifest.permission.OTHER_SENSORS,
    });

    public static boolean isSpecialRuntimePermission(String permission) {
        return specialRuntimePermissions.contains(permission);
    }

    public static String[] getAll() {
        return specialRuntimePermissions.toArray(EmptyArray.STRING);
    }

    public static boolean shouldAutoGrant(Context ctx, String packageName, int userId, String perm) {
        if (!isSpecialRuntimePermission(perm)) {
            return false;
        }

        if (Manifest.permission.OTHER_SENSORS.equals(perm)) {
            if (ActivityManager.getService() == null) {
                // a failsafe: should never happen
                Slog.d(TAG, "AMS is null");
                if (Build.isDebuggable()) {
                    throw new IllegalStateException();
                }
                return false;
            }

            var um = LocalServices.getService(UserManagerInternal.class);
            // use parent profile settings for work profile
            int userIdForSettings = um.getProfileParentId(userId);

            return isOtherSensorsAutoGrantEnabled(ctx, userIdForSettings);
        }

        return true;
    }

    static boolean isOtherSensorsAutoGrantEnabled(Context ctx, int userId) {
        return Settings.Secure.getIntForUser(ctx.getContentResolver(),
                Settings.Secure.AUTO_GRANT_OTHER_SENSORS_PERMISSION, 1, userId) != 0;
    }

    private SpecialRuntimePermUtils() {}
}
