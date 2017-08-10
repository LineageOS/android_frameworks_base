package com.android.server.wm.onehand;

import android.content.Context;
import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;

class OneHandedSettings {

    private static final String SETTINGS_FEATURE_ENABLED = "com.android.onehand.onehanded_mode";
    private static final String SETTINGS_YADJ = "com.android.onehand.yadj";
    private static final String SETTINGS_XADJ = "com.android.onehand.xadj";
    private static final String SETTINGS_SCALE = "com.android.onehand.scale";
    private static final String SETTINGS_GRAVITY = "com.android.onehand.gravity";

    final static Object sSync = new Object();

    static void saveGravity(Context ctx, int gravity) {
        Settings.System.putIntForUser(ctx.getContentResolver(),
                SETTINGS_GRAVITY, gravity, OneHandedAnimator.getCurrentUser());
    }

    static void saveScale(Context ctx, float scale) {
        Settings.System.putFloatForUser(ctx.getContentResolver(),
                SETTINGS_SCALE, scale, OneHandedAnimator.getCurrentUser());
    }

    static void saveXAdj(Context ctx, int xadj) {
        Settings.System.putIntForUser(ctx.getContentResolver(),
                SETTINGS_XADJ, xadj, OneHandedAnimator.getCurrentUser());
    }

    static void saveYAdj(Context ctx, int yadj) {
        Settings.System.putIntForUser(ctx.getContentResolver(),
                SETTINGS_YADJ, yadj, OneHandedAnimator.getCurrentUser());
    }

    static void setFeatureEnabled(Context ctx, boolean enabled, int userId) {
        Settings.System.putIntForUser(ctx.getContentResolver(), SETTINGS_FEATURE_ENABLED, enabled ? 1 : 0, userId);
    }

    static int getSavedGravity(Context ctx, int defaultGravity) {
        return Settings.System.getIntForUser(ctx.getContentResolver(), SETTINGS_GRAVITY, defaultGravity, OneHandedAnimator.getCurrentUser());
    }

    static float getSavedScale(Context ctx, float defaultV) {
        return Settings.System.getFloatForUser(ctx.getContentResolver(),SETTINGS_SCALE, defaultV, OneHandedAnimator.getCurrentUser());
    }

    static int getSavedXAdj(Context ctx, int defaultV) {
        return Settings.System.getIntForUser(ctx.getContentResolver(),SETTINGS_XADJ, defaultV, OneHandedAnimator.getCurrentUser());
    }

    static int getSavedYAdj(Context ctx, int defaultV) {
        return Settings.System.getIntForUser(ctx.getContentResolver(),SETTINGS_YADJ, defaultV, OneHandedAnimator.getCurrentUser());
    }

    static boolean isFeatureEnabled(Context ctx) {
        return Settings.System.getIntForUser(ctx.getContentResolver(), SETTINGS_FEATURE_ENABLED, 0, OneHandedAnimator.getCurrentUser()) != 0;
    }

    static boolean isFeatureEnabledSettingNotFound(Context ctx, int userId) {
        try {
            Settings.System.getIntForUser(ctx.getContentResolver(), SETTINGS_FEATURE_ENABLED, userId);
            return false;
        } catch (Settings.SettingNotFoundException e) {
            return true;
        }
    }

    static void registerFeatureEnableDisableObserver(Context ctx,
                            ContentObserver observer) {
        ctx.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(SETTINGS_FEATURE_ENABLED),
                true,
                observer, UserHandle.USER_ALL);
    }
}
