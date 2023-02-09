package com.android.server.pm.permission;

import android.content.Context;
import android.util.ArraySet;
import android.util.EmptyArray;

/**
 * "Special runtime permissions" are runtime permissions that are granted by default at install
 * time (to preserve app compatibility) and can then be revoked by the user like any other runtime
 * permission. They are granted regardless of the app's targetSdk.
 */
public class SpecialRuntimePermUtils {
    private static final String TAG = SpecialRuntimePermUtils.class.getSimpleName();

    private static final ArraySet<String> specialRuntimePermissions = new ArraySet<>(new String[] {
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

        return true;
    }

    private SpecialRuntimePermUtils() {}
}
