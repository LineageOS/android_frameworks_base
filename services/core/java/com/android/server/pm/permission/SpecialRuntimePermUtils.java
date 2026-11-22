package com.android.server.pm.permission;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Build;
import android.os.Environment;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.EmptyArray;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

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

    private static final String OTHER_SENSORS_MIGRATION_MARKER = "other_sensors_perm_migrated";

    /**
     * Called from PackageManagerService.systemReady(), before third-party apps are started.
     *
     * OTHER_SENSORS doesn't exist on stock LineageOS, so when it's added by an OS update, apps that
     * were already installed have never been through the install-time auto-grant and would
     * suddenly lose access to sensors. Grant it to them once, if auto-grants are enabled, so that
     * updating the OS doesn't change their behavior. Newly installed apps are handled at install
     * time and system apps by AppIdPermissionPolicy.
     */
    public static void onSystemReady(Context ctx) {
        final File marker = new File(Environment.getDataSystemDirectory(),
                OTHER_SENSORS_MIGRATION_MARKER);
        if (marker.exists()) {
            return;
        }
        try {
            grantOtherSensorsToPreexistingApps(ctx);
        } catch (Exception e) {
            // will be retried on next boot
            Slog.e(TAG, "unable to grant OTHER_SENSORS to pre-existing apps", e);
            return;
        }
        try {
            if (!marker.createNewFile()) {
                Slog.w(TAG, "marker file already exists: " + marker);
            }
        } catch (IOException e) {
            Slog.e(TAG, "unable to create " + marker, e);
        }
    }

    private static void grantOtherSensorsToPreexistingApps(Context ctx) {
        final String perm = Manifest.permission.OTHER_SENSORS;
        final var pmi = LocalServices.getService(PackageManagerInternal.class);
        final var um = LocalServices.getService(UserManagerInternal.class);
        final var permManager = ctx.getSystemService(PermissionManager.class);

        final ArrayList<PackageStateInternal> candidates = new ArrayList<>();
        pmi.forEachPackageState(ps -> {
            AndroidPackage pkg = ps.getAndroidPackage();
            if (pkg != null && !ps.isSystem() && pkg.getRequestedPermissions().contains(perm)) {
                candidates.add(ps);
            }
        });

        final int userDecisionFlags = PackageManager.FLAG_PERMISSION_USER_SET
                | PackageManager.FLAG_PERMISSION_USER_FIXED
                | PackageManager.FLAG_PERMISSION_POLICY_FIXED
                | PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;

        int numGranted = 0;
        for (int userId : um.getUserIds()) {
            if (!isOtherSensorsAutoGrantEnabled(ctx, um.getProfileParentId(userId))) {
                Slog.i(TAG, "OTHER_SENSORS auto-grants are disabled for user " + userId);
                continue;
            }
            final UserHandle user = UserHandle.of(userId);
            for (PackageStateInternal ps : candidates) {
                PackageUserStateInternal userState = ps.getUserStateOrDefault(userId);
                if (!userState.isInstalled() || userState.isInstantApp()) {
                    continue;
                }
                final String pkgName = ps.getPackageName();
                try {
                    int flags = permManager.getPermissionFlags(pkgName, perm, user);
                    if ((flags & userDecisionFlags) != 0) {
                        continue;
                    }
                    permManager.grantRuntimePermission(pkgName, perm, user);
                    ++numGranted;
                } catch (Exception e) {
                    Slog.w(TAG, "unable to grant " + perm + " to " + pkgName + " in user "
                            + userId, e);
                }
            }
        }
        Slog.i(TAG, "granted OTHER_SENSORS to " + numGranted
                + " pre-existing app installation(s)");
    }

    private SpecialRuntimePermUtils() {}
}
