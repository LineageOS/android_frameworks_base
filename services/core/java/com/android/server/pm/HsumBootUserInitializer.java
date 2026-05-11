/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.pm;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.multiuser.Flags;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Dumpable;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.ActivityManagerService;
import com.android.server.utils.Slogf;
import com.android.server.utils.TimingsTraceAndSlog;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * Class responsible for booting the device in the proper user on headless system user mode.
 *
 */
public final class HsumBootUserInitializer {

    private static final String TAG = HsumBootUserInitializer.class.getSimpleName();

    // NOTE: this class is small enough that it's ok to set DEBUG dynamically (it doesn't increase
    // the binary too much and they're only called during boot). But if the number of Slogf.d()
    // calls grows too much, we should change it to false.
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * Property used to override (for development purposes, on debuggable builds) the resource
     * configs used by {@link #designateMainUserOnBoot(Context)}
     */
    @VisibleForTesting
    static final String SYSPROP_DESIGNATE_MAIN_USER = "fw.designate_main_user_on_boot";

    // Lazy-instantiated on createInstance()
    private static @Nullable Dumpable sDumpable;

    private final UserManagerService mUms;
    private final ActivityManagerService mAms;
    private final PackageManagerService mPms;
    private final ContentResolver mContentResolver;
    // TODO(b/322150148): Change the type to HsuDeviceProvisioner and remove the cast once the flag
    // is completely pushed.
    private final ContentObserver mDeviceProvisionedObserver;

    /** Whether it should create a main user on first boot. */
    private final boolean mShouldDesignateMainUser;

    /** Whether it should create an initial user, but without setting it as the main user. */
    private final boolean mShouldCreateInitialUser;

    /** Static factory method for creating a {@link HsumBootUserInitializer} instance. */
    public static @Nullable HsumBootUserInitializer createInstance(UserManagerService ums,
            ActivityManagerService ams, PackageManagerService pms, ContentResolver contentResolver,
            Context context) {

        if (!UserManager.isHeadlessSystemUserMode()) {
            return null;
        }
        var instance = new HsumBootUserInitializer(ums, ams, pms, contentResolver,
                designateMainUserOnBoot(context), createInitialUserOnBoot(context));
        setDumpable(instance, context);
        return instance;
    }

    @VisibleForTesting
    HsumBootUserInitializer(UserManagerService ums, ActivityManagerService ams,
            PackageManagerService pms, ContentResolver contentResolver,
            boolean shouldDesignateMainUser, boolean shouldCreateInitialUser) {
        mUms = ums;
        mAms = ams;
        mPms = pms;
        mContentResolver = contentResolver;
        mShouldDesignateMainUser = shouldDesignateMainUser;
        mShouldCreateInitialUser = shouldCreateInitialUser;
        mDeviceProvisionedObserver = (Flags.hsuDeviceProvisioner()
                    ? new HsuDeviceProvisioner(new Handler(Looper.getMainLooper()), contentResolver)
                    : new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            boolean isDeviceProvisioned = isDeviceProvisioned();
                            if (DEBUG) {
                                Slogf.d(
                                        TAG,
                                        "onChange(%b): isDeviceProvisioned=%b",
                                        selfChange,
                                        isDeviceProvisioned);
                            }
                            // Set USER_SETUP_COMPLETE for the (headless) system user only when the
                            // device
                            // has been set up at least once.
                            if (isDeviceProvisioned) {
                                Slogf.i(TAG, "Marking USER_SETUP_COMPLETE for system user");
                                Settings.Secure.putInt(
                                        mContentResolver, Settings.Secure.USER_SETUP_COMPLETE, 1);
                                mContentResolver.unregisterContentObserver(
                                        mDeviceProvisionedObserver);
                            }
                        }
                    });
    }

    // TODO(b/409650316): remove after flag's completely pushed
    private void preCreateInitialUserFlagInit(TimingsTraceAndSlog t) {
        if (DEBUG) {
            Slogf.d(TAG, "preCreateInitialUserFlagInit())");
        }

        if (mShouldDesignateMainUser) {
            t.traceBegin("createMainUserIfNeeded");
            preCreateInitialUserCreateMainUserIfNeeded();
            t.traceEnd();
        }
    }

    // TODO(b/409650316): remove after flag's completely pushed
    private void preCreateInitialUserCreateMainUserIfNeeded() {
        final int mainUser = mUms.getMainUserId();
        if (mainUser != UserHandle.USER_NULL) {
            if (DEBUG) {
                Slogf.d(TAG, "Found existing MainUser, userId=%d", mainUser);
            }
            return;
        }

        Slogf.i(TAG, "Creating a new MainUser");
        try {
            final UserInfo newInitialUser = mUms.createUserInternalUnchecked(
                    /* name= */ null, // null will appear as "Owner" in on-demand localisation
                    UserManager.USER_TYPE_FULL_SECONDARY,
                    UserInfo.FLAG_ADMIN | UserInfo.FLAG_MAIN,
                    /* parentId= */ UserHandle.USER_NULL,
                    /* preCreate= */ false,
                    /* disallowedPackages= */ null,
                    /* token= */ null,
                    /* devicePolicyUserRestrictions= */ null);
            if (newInitialUser != null) {
                Slogf.i(TAG, "Successfully created MainUser, userId=%d", newInitialUser.id);
            } else {
                // Should never happen in production, but it does on HsumBootUserInitiliazerTest
                // (we could "fix" it by mocking the call, but it doesn't hurt to check anyways)
                Slogf.wtf(TAG, "createUserEvenWhenDisallowed() returned null");
            }
        } catch (UserManager.CheckedUserOperationException e) {
            Slogf.wtf(TAG, "Initial bootable MainUser creation failed", e);
        }
    }

    /**
     * Initialize this object, and create MainUser if needed.
     *
     * <p>Should be called before PHASE_SYSTEM_SERVICES_READY as services' setups may require
     * MainUser, but probably after PHASE_LOCK_SETTINGS_READY since that may be needed for user
     * creation.
     */
    public void init(TimingsTraceAndSlog t) {
        if (DEBUG) {
            Slogf.d(TAG, "init(): mShouldDesignateMainUser=%b, shouldCreateInitialUser=%b, "
                    + "Flags.createInitialUser=%b",
                    mShouldDesignateMainUser, mShouldCreateInitialUser, Flags.createInitialUser());
        } else {
            Slogf.i(TAG, "Initializing");
        }

        if (!Flags.createInitialUser()) {
            preCreateInitialUserFlagInit(t);
            return;
        }

        t.traceBegin("getMainUserId");
        int mainUserId = mUms.getMainUserId();
        t.traceEnd();

        if (mShouldDesignateMainUser) {
            designateMainUserIfNeeded(t, mainUserId);
            return;
        }

        t.traceBegin("demoteMainUserIfNeeded");
        demoteMainUserIfNeeded(t, mainUserId);
        t.traceEnd();

        if (mShouldCreateInitialUser) {
            createAdminUserIfNeeded(t);
            return;
        }
        if (DEBUG) {
            Slogf.d(TAG, "Not checking if initial user exists (should be handled externally)");
        }
    }

    private void designateMainUserIfNeeded(TimingsTraceAndSlog t, @UserIdInt int mainUserId) {
        // Always tracing as it used to be done by the caller - removing it (as createInitialUser
        // also traces) could break existing performance tests (for that same reason, the name in
        // trace call is not changed)
        t.traceBegin("createMainUserIfNeeded");
        try {
            if (mainUserId != UserHandle.USER_NULL) {
                if (DEBUG) {
                    Slogf.d(TAG, "designateMainUserIfNeeded(): found MainUser (userId=%d)",
                            mainUserId);
                }
                return;
            }
            if (!promoteAdminUserToMainUserIfNeeded(t)) {
                createInitialUser(/* isMainUser= */ true);
            }
        } finally {
            t.traceEnd();
        }
    }

    private boolean promoteAdminUserToMainUserIfNeeded(TimingsTraceAndSlog t) {
        t.traceBegin("promoteAdminUserToMainUserIfNeeded");
        try {
            // TODO(b/419086491): use getUsers(Filter)
            var users = mUms.getUsers(/* excludeDying= */ true);
            int numberUsers = users.size();
            for (int i = 0; i < numberUsers; i++) {
                var user = users.get(i);
                if (user.isFull() && user.isAdmin()) {
                    Slogf.i(TAG, "Promoting admin user (%d) as main user", user.id);
                    if (!mUms.setMainUser(user.id)) {
                        Slogf.e(TAG, "Failed to promote admin user (%d) as main user", user.id);
                        continue;
                    }
                    return true;
                }
            }
            if (DEBUG) {
                Slogf.d(TAG, "No existing admin user was promoted as main user (users=%s)", users);
            }
            return false;
        } finally {
            t.traceEnd();
        }
    }

    private void demoteMainUserIfNeeded(TimingsTraceAndSlog t, @UserIdInt int mainUserId) {
        if (mainUserId == UserHandle.USER_NULL) {
            if (DEBUG) {
                Slogf.d(TAG, "demoteMainUserIfNeeded(): didn't find MainUser");
            }
            return;
        }
        t.traceBegin("demoteMainUserIfNeeded");
        try {
            Slogf.i(TAG, "Demoting main user (%d)", mainUserId);
            if (!mUms.demoteMainUser()) {
                Slogf.wtf(TAG, "Failed to demote main user");
            }
        } finally {
            t.traceEnd();
        }
    }

    private void createAdminUserIfNeeded(TimingsTraceAndSlog t) {
        t.traceBegin("createAdminUserIfNeeded");
        try {
            // TODO(b/419086491): use getUsers(Filter)
            int[] userIds = mUms.getUserIds();
            if (userIds != null && userIds.length > 1) {
                if (DEBUG) {
                    Slogf.d(TAG, "createAdminUserIfNeeded(): already have more than 1 user (%s)",
                            Arrays.toString(userIds));
                }
                return;
            }
            createInitialUser(/* isMainUser= */ false);
        } finally {
            t.traceEnd();
        }
    }

    private void createInitialUser(boolean isMainUser) {
        String logName;
        int flags = UserInfo.FLAG_ADMIN;
        if (isMainUser) {
            flags |= UserInfo.FLAG_MAIN;
            logName = "MainUser";
        } else {
            logName = "admin user";
        }
        Slogf.i(TAG, "Creating %s", logName);
        try {
            final UserInfo newInitialUser = mUms.createUserInternalUnchecked(
                    /* name= */ null, // null will appear as "Owner" in on-demand localisation
                    UserManager.USER_TYPE_FULL_SECONDARY,
                    flags,
                    /* parentId= */ UserHandle.USER_NULL,
                    /* preCreate= */ false,
                    /* disallowedPackages= */ null,
                    /* token= */ null,
                    /* devicePolicyUserRestrictions= */ null);
            Slogf.i(TAG, "Successfully created %s, userId=%d", logName, newInitialUser.id);
            mUms.setBootUserIdUnchecked(newInitialUser.id);
        } catch (UserManager.CheckedUserOperationException e) {
            Slogf.wtf(TAG, e, "Initial bootable %s creation failed", logName);
        }
    }

    /**
     * Put the device into the correct user state: unlock the system and switch to the boot user.
     *
     * <p>Should only call once PHASE_THIRD_PARTY_APPS_CAN_START is reached to ensure that
     * privileged apps have had the chance to set the boot user, if applicable.
     */
    public void systemRunning(TimingsTraceAndSlog t) {
        observeDeviceProvisioning();
        unlockSystemUser(t);

        try {
            t.traceBegin("getBootUser");
            final int bootUser = mUms.getBootUser(/* waitUntilSet= */ mPms
                    .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, /* version= */0));
            t.traceEnd();
            t.traceBegin("switchToBootUser-" + bootUser);
            switchToBootUser(bootUser);
            t.traceEnd();
        } catch (UserManager.CheckedUserOperationException e) {
            Slogf.wtf(TAG, "Failed to switch to boot user since there isn't one.");
        }
    }


    /**
     * Creates a static / permanent reference to a {@code Dumpable}.
     *
     * <p>That {@code Dumpable} will never be GC'ed and will dump both the static state (which is
     * inferred from config / system properties) and the effective state of the {@code instance}
     * (but without keeping a reference to it, so it can be GC'ed after boot).
     */
    private static Dumpable setDumpable(HsumBootUserInitializer instance, Context context) {
        if (sDumpable != null) {
            Slogf.e(TAG, "setDumpable(%s): already set (as %s)", instance, sDumpable);
            return sDumpable;
        }
        String name = instance.toString();
        WeakReference<HsumBootUserInitializer> ref = new WeakReference<>(instance);
        sDumpable = new Dumpable() {

            @Override
            public String getDumpableName() {
                return HsumBootUserInitializer.class.getSimpleName();
            }

            @Override
            public void dump(PrintWriter pw, String[] args) {
                HsumBootUserInitializer.dump(pw, context);
                var self = ref.get();
                if (self == null) {
                    pw.printf("Effective state not available (%s has been GC'ed already)\n", name);
                    return;
                }
                self.dump(pw);
            }

        };
        return sDumpable;
    }

    @Nullable
    public static Dumpable getDumpable() {
        return sDumpable;
    }

    // Dumps static static - will always be available
    private static void dump(PrintWriter pw, Context context) {
        var res = context.getResources();

        pw.print("Designate main user on boot: ");
        pw.println(designateMainUserOnBoot(context));
        pw.print("  config_designateMainUser: ");
        pw.print(res.getBoolean(R.bool.config_designateMainUser));
        pw.print(" config_isMainUserPermanentAdmin: ");
        pw.print(res.getBoolean(R.bool.config_isMainUserPermanentAdmin));
        pw.print(" " + SYSPROP_DESIGNATE_MAIN_USER + ": ");
        pw.print(SystemProperties.get(SYSPROP_DESIGNATE_MAIN_USER, "N/A"));
        pw.print(" flag_demote_main_user: ");
        pw.println(Flags.demoteMainUser());

        pw.print("Create initial user on boot: ");
        pw.println(createInitialUserOnBoot(context));
        pw.print("  config_createInitialUser: ");
        pw.println(res.getBoolean(R.bool.config_createInitialUser));
    }

    // Dumps internal static - will only be available until it's garbage collected
    private void dump(PrintWriter pw) {
        pw.println("Effective state:");
        pw.print("  mDeviceProvisionedObserver="); pw.println(mDeviceProvisionedObserver);
        pw.print("  mShouldDesignateMainUser="); pw.println(mShouldDesignateMainUser);
        pw.print("  mShouldCreateInitialUser="); pw.println(mShouldCreateInitialUser);
    }

    @VisibleForTesting
    void observeDeviceProvisioning() {
        if (Flags.hsuDeviceProvisioner()) {
            ((HsuDeviceProvisioner) mDeviceProvisionedObserver).init();
            return;
        }
        if (isDeviceProvisioned()) {
            return;
        }

        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                false,
                mDeviceProvisionedObserver
        );
    }

    private boolean isDeviceProvisioned() {
        try {
            return Settings.Global.getInt(mContentResolver,
                    Settings.Global.DEVICE_PROVISIONED) == 1;
        } catch (Exception e) {
            Slogf.wtf(TAG, "DEVICE_PROVISIONED setting not found.", e);
            return false;
        }
    }

    // NOTE: Mostly copied from Automotive's InitialUserSetter
    // TODO(b/266158156): Refactor how starting/unlocking works for the System.
    private void unlockSystemUser(TimingsTraceAndSlog t) {
        Slogf.i(TAG, "Unlocking system user");
        t.traceBegin("unlock-system-user");
        try {
            // This is for force changing state into RUNNING_LOCKED. Otherwise unlock does not
            // update the state and USER_SYSTEM unlock happens twice.
            t.traceBegin("am.startUser");
            final boolean started = mAms.startUserInBackgroundWithListener(UserHandle.USER_SYSTEM,
                            /* listener= */ null);
            t.traceEnd();
            if (!started) {
                Slogf.w(TAG, "could not restart system user in background; trying unlock instead");
                t.traceBegin("am.unlockUser");
                final boolean unlocked = mAms.unlockUser(UserHandle.USER_SYSTEM, /* token= */ null,
                        /* secret= */ null, /* listener= */ null);
                t.traceEnd();
                if (!unlocked) {
                    Slogf.w(TAG, "could not unlock system user either");
                }
            }
        } finally {
            t.traceEnd();
        }
    }

    private void switchToBootUser(@UserIdInt int bootUserId) {
        Slogf.i(TAG, "Switching to boot user %d", bootUserId);
        if (bootUserId == UserHandle.USER_SYSTEM) {
            // System user is already the foreground user, so onUserSwitching() will not be called
            // for the system user to record the last entered foreground time. Therefore explicitly
            // set the time now.
            mUms.setLastEnteredForegroundTimeToNow(bootUserId);
        }
        final boolean started = mAms.startUserInForegroundWithListener(bootUserId,
                /* unlockListener= */ null);
        if (!started) {
            Slogf.wtf(TAG, "Failed to start user %d in foreground", bootUserId);
        }
    }

    // Methods below are used to create the parameters used in the factory method and used to be
    // defined on SystemServer, but were moved here so they can be unit tested, as SystemServer is
    // not included on FrameworksMockingServicesTests (and besides, it makes more sense to define
    // the logic here than on SystemServer itself).

    @VisibleForTesting
    static boolean designateMainUserOnBoot(Context context) {
        var res = context.getResources();
        boolean defaultValue = res.getBoolean(R.bool.config_designateMainUser)
                || res.getBoolean(R.bool.config_isMainUserPermanentAdmin);
        if (DEBUG) {
            Slogf.d(TAG, "designateMainUserOnBoot(): defaultValue=%b (because "
                    + "config_designateMainUser=%b and config_isMainUserPermanentAdmin=%b)",
                    defaultValue,
                    res.getBoolean(R.bool.config_designateMainUser),
                    res.getBoolean(R.bool.config_isMainUserPermanentAdmin));
        }
        // Ignore devices that should not create a main user while flag is not ramped up yet
        // TODO(b/402486365): remove this workaround after flag is ramped up
        if (!Flags.demoteMainUser() && res.getBoolean(R.bool.config_createInitialUser)
                && !defaultValue) {
            Slogf.i(TAG, "designateMainUserOnBoot(): overriding defaultValue to true (because "
                    + "Flags.demoteMainUser()=%b and config_createInitialUser=%b)",
                    Flags.demoteMainUser(), res.getBoolean(R.bool.config_createInitialUser));
            defaultValue = true;
        }
        if (!Build.isDebuggable()) {
            return defaultValue;
        }
        return SystemProperties.getBoolean(SYSPROP_DESIGNATE_MAIN_USER, defaultValue);
    }

    @VisibleForTesting
    static boolean createInitialUserOnBoot(Context context) {
        return context.getResources().getBoolean(R.bool.config_createInitialUser);
    }
}
