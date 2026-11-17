/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.view;

import static android.provider.Settings.System.ACCELEROMETER_ROTATION;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.WindowManagerGlobal;

import com.android.internal.R;

/**
 * Provides helper functions for configuring the display rotation policy.
 */
public final class RotationPolicy {
    private static final String TAG = "RotationPolicy";
    private static final int CURRENT_ROTATION = -1;

    private static int sNaturalRotation = -1;

    private RotationPolicy() {
    }

    /**
     * Gets whether the device supports rotation. In general such a
     * device has an accelerometer and has the portrait and landscape
     * features.
     *
     * @param context Context for accessing system resources.
     * @return Whether the device supports rotation.
     */
    public static boolean isRotationSupported(Context context) {
        PackageManager pm = context.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER)
                && pm.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT)
                && pm.hasSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE)
                && context.getResources().getBoolean(
                        com.android.internal.R.bool.config_supportAutoRotation);
    }

    /**
     * Returns the orientation that will be used when locking the orientation from system UI
     * with {@link #setRotationLock}.
     *
     * If the device only supports locking to its natural orientation, this will be either
     * Configuration.ORIENTATION_PORTRAIT or Configuration.ORIENTATION_LANDSCAPE,
     * otherwise Configuration.ORIENTATION_UNDEFINED if any orientation is lockable.
     */
    public static int getRotationLockOrientation(Context context) {
        if (isCurrentRotationAllowed(context)) {
            return Configuration.ORIENTATION_UNDEFINED;
        }
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final int rotation =
                context.getResources().getConfiguration().windowConfiguration.getRotation();
        final boolean rotated = rotation % 2 != 0;
        final int w = rotated ? metrics.heightPixels : metrics.widthPixels;
        final int h = rotated ? metrics.widthPixels : metrics.heightPixels;
        return w < h ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Returns true if the rotation-lock toggle should be shown in system UI.
     */
    public static boolean isRotationLockToggleVisible(Context context) {
        return isRotationSupported(context) &&
                Settings.System.getIntForUser(context.getContentResolver(),
                        Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY, 0,
                        UserHandle.USER_CURRENT) == 0;
    }

    /**
     * Requests to update value of setting key {@link Settings.Secure.DEVICE_STATE_ROTATION_LOCK} to
     * {@link autoRotate} for {@link deviceState}.
     */
    public static void requestDeviceStateAutoRotateSettingChange(int deviceState,
            boolean autoRotate) {
        try {
            IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            wm.setDeviceStateAutoRotateSetting(deviceState, autoRotate);
        } catch (RemoteException exc) {
            Log.w(TAG, "Unable to save device state auto-rotate setting: " + exc.getMessage());
        }
    }

    /**
     * Returns true if rotation lock is enabled.
     */
    public static boolean isRotationLocked(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0, UserHandle.USER_CURRENT) == 0;
    }

    /**
     * Enables or disables rotation lock from the system UI toggle.
     */
    public static void setRotationLock(Context context, final boolean enabled, String caller) {
        final int rotation = isCurrentRotationAllowed(context)
                || useCurrentRotationOnRotationLockChange(context) ? CURRENT_ROTATION
                : getNaturalRotation();
        setRotationLockAtAngle(context, enabled, rotation, caller);
    }

    /**
     * Enables or disables rotation lock at a specific rotation from system UI.
     */
    public static void setRotationLockAtAngle(Context context, final boolean enabled,
            final int rotation, String caller) {
        Settings.System.putIntForUser(context.getContentResolver(),
                Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY, 0,
                UserHandle.USER_CURRENT);

        setRotationLock(enabled, rotation, caller);
    }

    /**
     * Sets screen rotation to {@link rotation} if the value of {@link ACCELEROMETER_ROTATION} is
     * false.
     */
    public static void setRotationAtAngleIfAllowed(final int rotation, String caller) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                    wm.setRotationAtAngleIfAllowed(rotation, caller);
                } catch (RemoteException exc) {
                    Log.w(TAG, "Unable to set rotation to:" + rotation);
                }
            }
        });
    }

    /**
     * Enables or disables natural rotation lock from Accessibility settings.
     *
     * If rotation is locked for accessibility, the system UI toggle is hidden to avoid confusion.
     */
    public static void setRotationLockForAccessibility(Context context, final boolean enabled,
            String caller) {
        Settings.System.putIntForUser(context.getContentResolver(),
                Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY, enabled ? 1 : 0,
                        UserHandle.USER_CURRENT);

        setRotationLock(enabled, getNaturalRotation(), caller);
    }

    public static boolean isRotationAllowed(int rotation, int defaultRotation,
            int userRotationAngles, boolean allowAllRotations) {
        if (userRotationAngles < 0) {
            // Not set by user so use these defaults
            userRotationAngles = allowAllRotations ?
                    (1 | 2 | 4 | 8) : // All angles
                    (1 | 2 | 8); // All except 180
        }
        // Angles are relative to the default rotation
        rotation = (rotation - defaultRotation + 4) % 4;
        switch (rotation) {
            case Surface.ROTATION_0:
                return (userRotationAngles & 1) != 0;
            case Surface.ROTATION_90:
                return (userRotationAngles & 2) != 0;
            case Surface.ROTATION_180:
                return (userRotationAngles & 4) != 0;
            case Surface.ROTATION_270:
                return (userRotationAngles & 8) != 0;
        }
        return false;
    }

    public static boolean isCurrentRotationAllowed(Context context) {
        int userRotationAngles = Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION_ANGLES, -1, UserHandle.USER_CURRENT);
        boolean allowAllRotations = context.getResources().getBoolean(
                com.android.internal.R.bool.config_allowAllRotations);
        final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        try {
            return isRotationAllowed(wm.getDefaultDisplayRotation(),
                    wm.getDefaultDisplayDefaultRotation(), userRotationAngles, allowAllRotations);
        } catch (RemoteException exc) {
            Log.w(TAG, "Unable to getWindowManagerService.getDefaultDisplayRotation()");
        }
        return false;
    }

    /**
     * If false and config_allowAllRotations is false, the screen will rotate to the natural
     * orientation of the device when the auto-rotate policy is toggled.
     */
    public static boolean useCurrentRotationOnRotationLockChange(Context context) {
        return context.getResources().getBoolean(
                R.bool.config_useCurrentRotationOnRotationLockChange);
    }

    private static void setRotationLock(final boolean enabled, final int rotation,
            final String caller) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                    if (enabled) {
                        wm.freezeRotation(rotation, caller);
                    } else {
                        wm.thawRotation(caller);
                    }
                } catch (RemoteException exc) {
                    Log.w(TAG, "Unable to save auto-rotate setting");
                }
            }
        });
    }

    /**
     * Registers a listener for rotation policy changes affecting the caller's user
     */
    public static void registerRotationPolicyListener(Context context,
            RotationPolicyListener listener) {
        registerRotationPolicyListener(context, listener, UserHandle.getCallingUserId());
    }

    /**
     * Registers a listener for rotation policy changes affecting a specific user,
     * or USER_ALL for all users.
     */
    public static void registerRotationPolicyListener(Context context,
            RotationPolicyListener listener, int userHandle) {
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                Settings.System.ACCELEROMETER_ROTATION),
                false, listener.mObserver, userHandle);
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY),
                false, listener.mObserver, userHandle);
    }

    /**
     * Unregisters a listener for rotation policy changes.
     */
    public static void unregisterRotationPolicyListener(Context context,
            RotationPolicyListener listener) {
        context.getContentResolver().unregisterContentObserver(listener.mObserver);
    }

    public static int getNaturalRotation() {
        if (sNaturalRotation == -1) {
            final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            try {
                sNaturalRotation = wm.getDefaultDisplayDefaultRotation();
            } catch (RemoteException exc) {
                Log.w(TAG, "Unable to getWindowManagerService.getDefaultDisplayDefaultRotation()");
                return Surface.ROTATION_0;
            }
        }
        return sNaturalRotation;
    }

    /**
     * Listener that is invoked whenever a change occurs that might affect the rotation policy.
     */
    public static abstract class RotationPolicyListener {
        final ContentObserver mObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                RotationPolicyListener.this.onChange();
            }
        };

        public abstract void onChange();
    }
}