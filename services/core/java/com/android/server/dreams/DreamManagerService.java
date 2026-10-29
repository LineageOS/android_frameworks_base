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

package com.android.server.dreams;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.os.BatteryManager.EXTRA_CHARGING_STATUS;
import static android.service.dreams.Flags.allowDreamWithChargeLimit;
import static android.service.dreams.Flags.cleanupDreamSettingsOnUninstall;
import static android.service.dreams.Flags.dreamHandlesBeingObscured;
import static android.service.dreams.Flags.dreamsSwitcher;
import static android.service.dreams.Flags.dreamsV2;
import static android.service.dreams.Flags.systemDreamDeathRecipient;

import static com.android.server.wm.ActivityInterceptorCallback.DREAM_MANAGER_ORDERED_ID;

import android.annotation.EnforcePermission;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IAppTask;
import android.app.TaskInfo;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.database.ContentObserver;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.health.BatteryChargingState;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.DreamItem;
import android.service.dreams.DreamManagerInternal;
import android.service.dreams.DreamPlaylist;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.dreams.IDreamManagerListener;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.input.InputManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Service api for managing dreams.
 *
 * @hide
 */
public final class DreamManagerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "DreamManagerService";

    private static final String DOZE_WAKE_LOCK_TAG = "dream:doze";
    private static final String DREAM_WAKE_LOCK_TAG = "dream:dream";

    /** Constants for the when to activate dreams. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({DREAM_DISABLED, DREAM_ON_DOCK, DREAM_ON_CHARGE, DREAM_ON_POSTURED})
    public @interface WhenToDream {}

    private static final int DREAM_DISABLED = 0;
    private static final int DREAM_ON_DOCK = 1 << 0;
    private static final int DREAM_ON_CHARGE = 1 << 1;
    private static final int DREAM_ON_POSTURED = 1 << 2;

    /**
     * Battery percentage at which the device stops charging when the charge limit feature is
     * enabled.
     */
    @VisibleForTesting
    static final int CHARGE_LIMIT_PERCENTAGE = 80;

    private final Object mLock = new Object();

    private final Context mContext;
    private final Handler mHandler;
    private final DreamController mController;
    private final SparseArray<DreamUserData> mUserData = new SparseArray<>();
    private final Injector mInjector;
    private final PowerManager mPowerManager;
    private final UiModeManager mUiModeManager;
    private final PowerManagerInternal mPowerManagerInternal;
    private final BatteryManagerInternal mBatteryManagerInternal;
    private final PowerManager.WakeLock mDozeWakeLock;
    private final ActivityTaskManagerInternal mAtmInternal;
    private final PackageManagerInternal mPmInternal;
    private final UserManager mUserManager;
    private final UiEventLogger mUiEventLogger;
    private final DreamUiEventLogger mDreamUiEventLogger;
    private final ComponentName mAmbientDisplayComponent;
    private final boolean mDismissDreamOnActivityStart;
    private final boolean mDreamsOnlyEnabledForDockUser;
    private final boolean mDreamsEnabledByDefaultConfig;
    private final boolean mDreamsActivatedOnChargeByDefault;
    private final boolean mDreamsActivatedOnDockByDefault;
    private final boolean mDreamsActivatedOnPosturedByDefault;
    private final boolean mOnlyDreamOnWirelessChargingDefault;
    private final boolean mKeepDreamingWhenUnpluggingDefault;
    private final boolean mDreamsDisabledByAmbientModeSuppressionConfig;
    private final boolean mSupportDreamWirelessChargingRestriction;

    private final CopyOnWriteArrayList<DreamManagerInternal.DreamManagerStateListener>
            mDreamManagerStateListeners = new CopyOnWriteArrayList<>();

    private final DreamPlaylistUpdater mDreamPlaylistUpdater;

    @GuardedBy("mLock")
    private DreamRecord mCurrentDream;

    private boolean mForceAmbientDisplayEnabled;
    private SettingsObserver mSettingsObserver;
    private boolean mDreamsEnabledSetting;
    @WhenToDream private int mWhenToDream;

    /**
     * If true, the user has enabled the setting to only dream when charging wirelessly.
     */
    private boolean mOnlyDreamOnWirelessChargingSetting;
    private boolean mIsDocked;
    private boolean mIsCharging;
    private boolean mIsWirelessCharging;
    private boolean mIsPostured;

    // A temporary dream component that, when present, takes precedence over user configured dream
    // component.
    @GuardedBy("mLock")
    private ComponentName mSystemDreamComponent;

    @GuardedBy("mLock")
    private IBinder mSystemDreamComponentToken;

    @GuardedBy("mLock")
    private IBinder.DeathRecipient mSystemDreamComponentDeathRecipient;

    private ComponentName mDreamOverlayServiceName;

    private final AmbientDisplayConfiguration mDozeConfig;

    /** Stores {@link PerUserPackageMonitor} to monitor dream uninstalls. */
    private final SparseArray<PackageMonitor> mPackageMonitors = new SparseArray<>();

    private final ActivityInterceptorCallback mActivityInterceptorCallback =
            new ActivityInterceptorCallback() {
                @Nullable
                @Override
                public ActivityInterceptResult onInterceptActivityLaunch(
                        @NonNull ActivityInterceptorInfo info) {
                    return null;
                }

                @Override
                public void onActivityLaunched(
                        TaskInfo taskInfo,
                        ActivityInfo activityInfo,
                        ActivityInterceptorInfo info) {
                    final int activityType = taskInfo.getActivityType();
                    final boolean activityAllowed =
                            activityType == ACTIVITY_TYPE_HOME
                                    || activityType == ACTIVITY_TYPE_DREAM
                                    || activityType == ACTIVITY_TYPE_ASSISTANT;

                    boolean shouldRequestAwaken;
                    synchronized (mLock) {
                        shouldRequestAwaken =
                                mCurrentDream != null
                                        && !mCurrentDream.isWaking
                                        && !mCurrentDream.isDozing
                                        && !activityAllowed;
                    }

                    if (shouldRequestAwaken) {
                        requestAwakenInternal(
                                "stopping dream due to activity start: " + activityInfo.name);
                    }
                }
            };

    /**
     * Receiver for the {@link Intent#ACTION_BATTERY_CHANGED} broadcast.
     */
    private final BroadcastReceiver mBatteryChangedReceived = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent batteryChangedIntent) {
            if (allowDreamWithChargeLimit()) {
                updateChargingStatus(batteryChangedIntent);
            } else {
                mIsCharging = mBatteryManagerInternal.isPowered(BatteryManager.BATTERY_PLUGGED_ANY);
                mIsWirelessCharging = mBatteryManagerInternal.isPowered(
                        BatteryManager.BATTERY_PLUGGED_WIRELESS);
            }
        }
    };

    private final BroadcastReceiver mDockStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_DOCK_EVENT.equals(intent.getAction())) {
                int dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                mIsDocked = dockState != Intent.EXTRA_DOCK_STATE_UNDOCKED;
            }
        }
    };

    private final BroadcastReceiver mLocaleChangedReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final int userId = mInjector.getCurrentUser();
                    final DreamUserData userData = getOrCreateUserData(userId);
                    if (userData != null) {
                        userData.mMetadataProvider.invalidateCache();
                        userData.mResolver.invalidate();
                    }
                    notifyPlaylistChanged(userId);
                }
            };

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            refreshSettings(userId, uri);
        }
    }

    @VisibleForTesting
    void refreshSettings(int userId, @Nullable Uri uri) {
        updateWhenToDreamSettings();
        if (dreamsSwitcher()) {
            if (uri == null
                    || Settings.Secure.getUriFor(Settings.Secure.SCREENSAVER_COMPONENTS).equals(uri)
                    || Settings.Secure.getUriFor(Settings.Secure.SCREENSAVER_ACTIVE_COMPONENT)
                            .equals(uri)
                    || Settings.Secure.getUriFor(Settings.Secure.SCREENSAVER_DEFAULT_COMPONENT)
                            .equals(uri)) {
                final DreamUserData userData = getOrCreateUserData(userId);
                if (userData != null) {
                    userData.mResolver.invalidate();
                }
                notifyPlaylistChanged(userId);
            }
        }
    }

    private final class PerUserPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageRemoved(String packageName, int uid) {
            super.onPackageRemoved(packageName, uid);
            final int userId = getChangingUserId();
            if (cleanupDreamSettingsOnUninstall()) {
                updateDreamOnPackageRemoved(packageName, userId);
            }
            invalidateAndNotify(packageName);
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            super.onPackageAdded(packageName, uid);
            invalidateAndNotify(packageName);
        }

        @Override
        public void onPackageModified(String packageName) {
            super.onPackageModified(packageName);
            invalidateAndNotify(packageName);
        }

        @Override
        public void onPackagesSuspended(String[] packages) {
            super.onPackagesSuspended(packages);
            invalidateAndNotify(packages);
        }

        @Override
        public void onPackagesUnsuspended(String[] packages) {
            super.onPackagesUnsuspended(packages);
            invalidateAndNotify(packages);
        }

        @Override
        public void onPackageAppeared(String packageName, int reason) {
            super.onPackageAppeared(packageName, reason);
            invalidateAndNotify(packageName);
        }

        @Override
        public void onPackageDisappeared(String packageName, int reason) {
            super.onPackageDisappeared(packageName, reason);
            invalidateAndNotify(packageName);
        }

        private void invalidateAndNotify(String... packages) {
            if (dreamsSwitcher()) {
                final int userId = getChangingUserId();
                final DreamUserData userData = getOrCreateUserData(userId);
                if (userData != null) {
                    for (String pkg : packages) {
                        userData.mMetadataProvider.invalidatePackage(pkg);
                        userData.mResolver.onPackageChanged(pkg);
                    }
                }
                notifyPlaylistChanged(userId);
            }
        }
    }

    public DreamManagerService(Context context) {
        this(new DefaultInjector(context, new DreamHandler(FgThread.get().getLooper())));
    }

    @VisibleForTesting
    DreamManagerService(Injector injector) {
        super(injector.getContext());
        mContext = injector.getContext();
        mHandler = injector.getHandler();
        mController = injector.getDreamController(mControllerListener);
        mInjector = injector;

        mPowerManager = mContext.getSystemService(PowerManager.class);
        mPowerManagerInternal = getLocalService(PowerManagerInternal.class);
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        mAtmInternal = getLocalService(ActivityTaskManagerInternal.class);
        mPmInternal = getLocalService(PackageManagerInternal.class);
        mUserManager = mContext.getSystemService(UserManager.class);
        mDozeWakeLock = mPowerManager.newWakeLock(PowerManager.DOZE_WAKE_LOCK, DOZE_WAKE_LOCK_TAG);
        mDozeConfig = injector.getDozeConfig();
        mUiEventLogger = new UiEventLoggerImpl();
        mDreamUiEventLogger = new DreamUiEventLoggerImpl(
                mContext.getResources().getStringArray(R.array.config_loggable_dream_prefixes));
        mAmbientDisplayComponent =
                ComponentName.unflattenFromString(mDozeConfig.ambientDisplayComponent());
        mDreamsOnlyEnabledForDockUser =
                mContext.getResources().getBoolean(R.bool.config_dreamsOnlyEnabledForDockUser);
        mDismissDreamOnActivityStart = mContext.getResources().getBoolean(
                R.bool.config_dismissDreamOnActivityStart);

        mDreamsEnabledByDefaultConfig = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dreamsEnabledByDefault);
        mDreamsActivatedOnChargeByDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnSleepByDefault);
        mDreamsActivatedOnDockByDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnDockByDefault);
        mDreamsActivatedOnPosturedByDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnPosturedByDefault);
        mOnlyDreamOnWirelessChargingDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_onlyDreamWhenWirelessChargingDefault);
        mSettingsObserver = new SettingsObserver(mHandler);
        mKeepDreamingWhenUnpluggingDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_keepDreamingWhenUnplugging);
        mDreamsDisabledByAmbientModeSuppressionConfig = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dreamsDisabledByAmbientModeSuppressionConfig);
        mSupportDreamWirelessChargingRestriction = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_supportDreamWirelessChargingRestriction);

        mBatteryManagerInternal = getLocalService(BatteryManagerInternal.class);
        mSystemDreamComponentDeathRecipient = new SystemDreamComponentDeathRecipient();
        mDreamPlaylistUpdater = new DreamPlaylistUpdater(this::getOrCreateResolver, mHandler,
                this::onDreamPlaylistChanged);
    }

    private DreamComponentsResolver getOrCreateResolver(int userId) {
        final DreamUserData userData = getOrCreateUserData(userId);
        return userData != null ? userData.mResolver : null;
    }

    private DreamUserData getOrCreateUserData(int userId) {
        DreamUserData userData;
        synchronized (mLock) {
            userData = mUserData.get(userId);
        }
        if (userData != null) {
            return userData;
        }

        Context userContext = null;
        try {
            userContext = mContext.createContextAsUser(UserHandle.of(userId), 0);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create context for user " + userId, e);
            return null;
        }
        DreamMetadataProvider metadataProvider = new DreamMetadataProvider(userContext);
        DreamRepository repository = new DreamRepositoryImpl(userContext, metadataProvider);
        DreamValidator dreamValidator = new DreamValidator(userContext);
        DreamComponentsResolver resolver =
                mInjector.getDreamComponentsResolver(
                        dreamValidator,
                        userId,
                        mDozeConfig,
                        LocalServices.getService(UserManagerInternal.class),
                        mDreamsOnlyEnabledForDockUser,
                        repository);
        DreamUserData newUserData = new DreamUserData(userContext, resolver, repository,
                metadataProvider);

        synchronized (mLock) {
            userData = mUserData.get(userId);
            if (userData == null) {
                userData = newUserData;
                mUserData.put(userId, userData);
            }
        }
        return userData;
    }

    @VisibleForTesting
    void onDreamPlaylistChanged(int userId, DreamPlaylist playlist) {
        if (userId == mInjector.getCurrentUser()) {
            synchronized (mLock) {
                final DreamItem activeDreamItem = playlist.getActiveDream();
                final ComponentName activeDream = activeDreamItem != null
                        ? activeDreamItem.componentName : null;
                if (activeDream != null
                        && isDreamingInternal()
                        && !currentDreamCanDozeLocked()
                        && mCurrentDream.userId == userId
                        && !Objects.equals(mCurrentDream.name, activeDream)) {
                    startDreamLocked(
                            activeDream,
                            false /*isPreviewMode*/,
                            false /*canDoze*/,
                            userId,
                            "playlist changed");
                }
            }
        }
    }

    @Override
    public void onStart() {
        publishBinderService(DreamService.DREAM_SERVICE, new BinderService(mContext));
        publishLocalService(DreamManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            if (Build.IS_DEBUGGABLE) {
                SystemProperties.addChangeCallback(mSystemPropertiesChanged);
            }

            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DOZE_DOUBLE_TAP_GESTURE), false,
                    mDozeEnabledObserver, UserHandle.USER_ALL);
            writePulseGestureEnabled();

            if (mDismissDreamOnActivityStart) {
                mAtmInternal.registerActivityStartInterceptor(
                        DREAM_MANAGER_ORDERED_ID,
                        mActivityInterceptorCallback);
            }

            mContext.registerReceiver(
                    mDockStateReceiver, new IntentFilter(Intent.ACTION_DOCK_EVENT));

            if (dreamsSwitcher()) {
                mContext.registerReceiver(
                        mLocaleChangedReceiver, new IntentFilter(Intent.ACTION_LOCALE_CHANGED));
            }

            // Broadcast is sticky so we don't need to query state directly.
            IntentFilter batteryChangedIntentFilter = new IntentFilter();
            batteryChangedIntentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
            batteryChangedIntentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            mContext.registerReceiver(mBatteryChangedReceived, batteryChangedIntentFilter);

            mSettingsObserver = new SettingsObserver(mHandler);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCREENSAVER_ACTIVATE_ON_POSTURED),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCREENSAVER_ENABLED),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCREENSAVER_RESTRICT_TO_WIRELESS_CHARGING),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCREENSAVER_COMPONENTS),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCREENSAVER_ACTIVE_COMPONENT),
                    false, mSettingsObserver, UserHandle.USER_ALL);

            if (!allowDreamWithChargeLimit()) {
                // We don't get an initial broadcast for the battery state, so we have to initialize
                // directly from BatteryManager.
                mIsCharging = mBatteryManagerInternal.isPowered(BatteryManager.BATTERY_PLUGGED_ANY);
                mIsWirelessCharging = mBatteryManagerInternal.isPowered(
                        BatteryManager.BATTERY_PLUGGED_WIRELESS);
            }

            updateWhenToDreamSettings();
        }
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        updateWhenToDreamSettings();

        mHandler.post(() -> {
            writePulseGestureEnabled();
            synchronized (mLock) {
                stopDreamLocked(false /*immediate*/, "user switched");
            }
        });
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        super.onUserStarting(user);
        if (cleanupDreamSettingsOnUninstall() || dreamsSwitcher()) {
            mHandler.post(() -> {
                final int userId = user.getUserIdentifier();
                if (!mPackageMonitors.contains(userId)) {
                    final PackageMonitor monitor = new PerUserPackageMonitor();
                    monitor.register(mContext, UserHandle.of(userId), mHandler);
                    mPackageMonitors.put(userId, monitor);
                } else {
                    Slog.w(TAG, "Package monitor already registered for " + userId);
                }
            });
        }
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        super.onUserStopping(user);
        final int userId = user.getUserIdentifier();
        synchronized (mLock) {
            mUserData.remove(userId);
        }
        mDreamPlaylistUpdater.clearCache(userId);
        if (cleanupDreamSettingsOnUninstall() || dreamsSwitcher()) {
            mHandler.post(() -> {
                final PackageMonitor monitor = mPackageMonitors.removeReturnOld(
                        userId);
                if (monitor != null) {
                    monitor.unregister();
                }
            });
        }
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        super.onUserUnlocked(user);
        if (dreamsSwitcher()) {
            final int userId = user.getUserIdentifier();
            mHandler.post(
                    () -> {
                        final DreamUserData userData = getOrCreateUserData(userId);
                        if (userData != null) {
                            userData.mMetadataProvider.invalidateCache();
                            userData.mResolver.invalidate();
                        }
                        notifyPlaylistChanged(userId, true /* immediate */);
                    });
        }
    }

    private void dumpInternal(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("DREAM MANAGER (dumpsys dreams)");
            pw.println();
            pw.println("mCurrentDream=" + mCurrentDream);
            pw.println("mForceAmbientDisplayEnabled=" + mForceAmbientDisplayEnabled);
            pw.println("mDreamsOnlyEnabledForDockUser=" + mDreamsOnlyEnabledForDockUser);
            pw.println("mDreamsEnabledSetting=" + mDreamsEnabledSetting);
            pw.println("mDreamsActivatedOnDockByDefault=" + mDreamsActivatedOnDockByDefault);
            pw.println("mDreamsActivatedOnChargeByDefault=" + mDreamsActivatedOnChargeByDefault);
            pw.println("mDreamsActivatedOnPosturedByDefault="
                    + mDreamsActivatedOnPosturedByDefault);
            pw.println("mOnlyDreamOnWirelessChargingSetting="
                    + mOnlyDreamOnWirelessChargingSetting);
            pw.println("mOnlyDreamOnWirelessChargingDefault="
                    + mOnlyDreamOnWirelessChargingDefault);
            pw.println("mIsDocked=" + mIsDocked);
            pw.println("mIsCharging=" + mIsCharging);
            pw.println("mWhenToDream=" + mWhenToDream);
            pw.println("mKeepDreamingWhenUnpluggingDefault=" + mKeepDreamingWhenUnpluggingDefault);
            pw.println("getDozeComponent()=" + getDozeComponent());
            pw.println("mDreamOverlayServiceName="
                    + ComponentName.flattenToShortString(mDreamOverlayServiceName));
            pw.println();

            if (dreamsSwitcher()) {
                mDreamPlaylistUpdater.dump(pw);
                for (int i = 0; i < mUserData.size(); i++) {
                    mUserData.valueAt(i).mResolver.dump(pw);
                    mUserData.valueAt(i).mMetadataProvider.dump(pw);
                }
            }

            DumpUtils.dumpAsync(mHandler, (pw1, prefix) -> mController.dump(pw1), pw, "", 200);
        }
    }

    private void updateChargingStatus(Intent batteryChangedIntent) {
        mIsWirelessCharging = mBatteryManagerInternal.isPowered(
                BatteryManager.BATTERY_PLUGGED_WIRELESS);

        if (mBatteryManagerInternal.isPowered(BatteryManager.BATTERY_PLUGGED_ANY)) {
            mIsCharging = true;
        } else {
            // When charge limit is enabled and the device is at the charge limit battery %, the
            // device stops charging entirely and the plug type is reported as BATTERY_PLUGGED_NONE.
            // Check if the feature is enabled so that the device can still dream when charge limit
            // is active.

            final ContentResolver resolver = mContext.getContentResolver();
            final boolean isChargeLimitEnabled = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.CHARGE_OPTIMIZATION_MODE, /*default=*/ 0,
                    UserHandle.USER_CURRENT) != 0;

            int chargingStatus = batteryChangedIntent.getIntExtra(EXTRA_CHARGING_STATUS,
                    BatteryChargingState.NORMAL);
            final boolean isChargeLimitActive =
                    mBatteryManagerInternal.getBatteryLevel() >= CHARGE_LIMIT_PERCENTAGE
                            && chargingStatus == BatteryChargingState.LONG_LIFE;

            mIsCharging = isChargeLimitEnabled && isChargeLimitActive;
        }
    }

    private void updateWhenToDreamSettings() {
        synchronized (mLock) {
            final ContentResolver resolver = mContext.getContentResolver();

            mOnlyDreamOnWirelessChargingSetting = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.SCREENSAVER_RESTRICT_TO_WIRELESS_CHARGING,
                    mOnlyDreamOnWirelessChargingDefault ? 1 : 0, UserHandle.USER_CURRENT) != 0;

            mWhenToDream = DREAM_DISABLED;

            if (Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                    mDreamsActivatedOnChargeByDefault ? 1 : 0,
                    UserHandle.USER_CURRENT) != 0) {
                mWhenToDream |= DREAM_ON_CHARGE;
            }

            if (Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                    mDreamsActivatedOnDockByDefault ? 1 : 0,
                    UserHandle.USER_CURRENT) != 0) {
                mWhenToDream |= DREAM_ON_DOCK;
            }

            if (Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.SCREENSAVER_ACTIVATE_ON_POSTURED,
                    mDreamsActivatedOnPosturedByDefault ? 1 : 0,
                    UserHandle.USER_CURRENT) != 0) {
                mWhenToDream |= DREAM_ON_POSTURED;
            }

            mDreamsEnabledSetting = (Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.SCREENSAVER_ENABLED,
                    mDreamsEnabledByDefaultConfig ? 1 : 0,
                    UserHandle.USER_CURRENT) != 0);
        }
    }

    private void reportKeepDreamingWhenUnpluggingChanged(boolean keepDreaming) {
        notifyDreamStateListeners(
                listener -> listener.onKeepDreamingWhenUnpluggingChanged(keepDreaming));
    }

    private void reportDreamingStarted() {
        notifyDreamStateListeners(listener -> listener.onDreamingStarted());
    }

    private void reportDreamingStopped() {
        notifyDreamStateListeners(listener -> listener.onDreamingStopped());
    }

    private void notifyDreamStateListeners(
            Consumer<DreamManagerInternal.DreamManagerStateListener> notifier) {
        mHandler.post(() -> {
            for (DreamManagerInternal.DreamManagerStateListener listener
                    : mDreamManagerStateListeners) {
                notifier.accept(listener);
            }
        });
    }

    /** Whether a real dream is occurring. */
    private boolean isDreamingInternal() {
        synchronized (mLock) {
            return mCurrentDream != null && !mCurrentDream.isPreview
                    && !mCurrentDream.isWaking;
        }
    }

    /** Whether a doze is occurring. */
    private boolean isDozingInternal() {
        synchronized (mLock) {
            return mCurrentDream != null && mCurrentDream.isDozing;
        }
    }

    /** Whether a real dream, or a dream preview is occurring. */
    private boolean isDreamingOrInPreviewInternal() {
        synchronized (mLock) {
            return mCurrentDream != null && !mCurrentDream.isWaking;
        }
    }

    @GuardedBy("mLock")
    private boolean currentDreamCanDozeLocked() {
        return mCurrentDream != null && mCurrentDream.canDoze;
    }

    @VisibleForTesting
    boolean dreamConditionActiveInternal() {
        synchronized (mLock) {
            return dreamConditionActiveInternalLocked();
        }
    }

    private boolean dreamConditionActiveInternalLocked() {
        if ((mWhenToDream & DREAM_ON_CHARGE) == DREAM_ON_CHARGE) {
            if (dreamsV2() && mSupportDreamWirelessChargingRestriction
                    && mOnlyDreamOnWirelessChargingSetting) {
                return mIsWirelessCharging;
            } else {
                return mIsCharging;
            }
        }

        if ((mWhenToDream & DREAM_ON_DOCK) == DREAM_ON_DOCK) {
            // Don't check wireless charging on dock as wireless charging is mutually exclusive with
            // docking.
            return mIsDocked;
        }

        if ((mWhenToDream & DREAM_ON_POSTURED) == DREAM_ON_POSTURED) {
            if (dreamsV2() && mSupportDreamWirelessChargingRestriction
                    && mOnlyDreamOnWirelessChargingSetting && !mIsWirelessCharging) {
                return false;
            }
            return mIsPostured;
        }

        return false;
    }

    @VisibleForTesting
    boolean dreamsEnabled() {
        return mDreamsEnabledSetting;
    }

    /** Whether dreaming can start given user settings and the current dock/charge state. */
    @VisibleForTesting
    boolean canStartDreamingInternal(boolean isScreenOn) {
        synchronized (mLock) {
            // Can't start dreaming if we are already dreaming and the dream has focus. If we are
            // dreaming but the dream does not have focus, then the dream can be brought to the
            // front so it does have focus.
            if (isScreenOn && isDreamingInternal() && dreamIsFrontmost()) {
                return false;
            }

            if ((mUiModeManager.getActiveProjectionTypes()
                    & UiModeManager.PROJECTION_TYPE_AUTOMOTIVE) != 0) {
                // Don't dream when connected to Android Auto unit as dreams can't start anyways.
                return false;
            }

            if (!mDreamsEnabledSetting) {
                return false;
            }

            final int userId = mInjector.getCurrentUser();

            if (!mUserManager.isUserUnlocked(userId)) {
                return false;
            }

            if (mDreamsDisabledByAmbientModeSuppressionConfig
                    && mPowerManagerInternal.isAmbientDisplaySuppressed()) {
                // Don't dream if Bedtime (or something else) is suppressing ambient.
                Slog.i(TAG, "Can't start dreaming because ambient is suppressed.");
                return false;
            }

            if (chooseDreamForUser(false /*doze*/, userId) == null) {
                Slog.i(TAG, "Can't start dreaming because no dream is configured.");
                return false;
            }

            // All dream prerequisites fulfilled, check if device state matches "when to dream"
            // setting.
            return dreamConditionActiveInternalLocked();
        }
    }

    private boolean dreamIsFrontmost() {
        // Dreams were always considered frontmost before they began tracking whether they are
        // obscured.
        return !dreamHandlesBeingObscured() || mController.dreamIsFrontmost();
    }

    protected void requestStartDreamFromShell() {
        requestDreamInternal();
    }

    private void requestDreamInternal() {
        if (isDreamingInternal() && !dreamIsFrontmost() && mController.bringDreamToFront()) {
            return;
        }

        // Ask the power manager to nap.  It will eventually call back into
        // startDream() if/when it is appropriate to start dreaming.
        // Because napping could cause the screen to turn off immediately if the dream
        // cannot be started, we keep one eye open and gently poke user activity.
        long time = SystemClock.uptimeMillis();
        mPowerManager.userActivity(time, /* noChangeLights= */ true);
        mPowerManagerInternal.nap(time, /* allowWake= */ true);
    }

    private void requestAwakenInternal(String reason) {
        // Treat an explicit request to awaken as user activity so that the
        // device doesn't immediately go to sleep if the timeout expired,
        // for example when being undocked.
        long time = SystemClock.uptimeMillis();
        mPowerManager.userActivity(time, false /*noChangeLights*/);
        stopDreamInternal(false /*immediate*/, reason);
    }

    private void finishSelfInternal(IBinder token, boolean immediate) {
        if (DEBUG) {
            Slog.d(TAG, "Dream finished: " + token + ", immediate=" + immediate);
        }

        // Note that a dream finishing and self-terminating is not
        // itself considered user activity.  If the dream is ending because
        // the user interacted with the device then user activity will already
        // have been poked so the device will stay awake a bit longer.
        // If the dream is ending on its own for other reasons and no wake
        // locks are held and the user activity timeout has expired then the
        // device may simply go to sleep.
        synchronized (mLock) {
            if (mCurrentDream != null && mCurrentDream.token == token) {
                stopDreamLocked(immediate, "finished self");
            }
        }
    }

    private void testDreamInternal(ComponentName dream, int userId) {
        synchronized (mLock) {
            startDreamLocked(dream, true /*isPreviewMode*/, false /*canDoze*/, userId,
                    "test dream" /*reason*/);
        }
    }

    @VisibleForTesting
    void startDreamInternal(boolean doze, String reason) {
        final int userId = mInjector.getCurrentUser();
        final ComponentName dream = chooseDreamForUser(doze, userId);
        if (dream != null) {
            synchronized (mLock) {
                startDreamLocked(dream, false /*isPreviewMode*/, doze, userId, reason);
            }
        }
    }

    protected void requestStopDreamFromShell() {
        stopDreamInternal(false, "stopping dream from shell");
    }

    void requestGetDreamPlaylistFromShell(PrintWriter pw) {
        if (!dreamsSwitcher()) {
            pw.println("Dream switcher feature not enabled");
            return;
        }
        final int userId = mInjector.getCurrentUser();
        final DreamPlaylist playlist = getDreamPlaylistInternal(userId);
        pw.println("Dream Playlist for user " + userId + ":");
        pw.println(playlist);
    }

    void requestNextDreamFromShell(PrintWriter pw) {
        if (!dreamsSwitcher()) {
            pw.println("Dream switcher feature not enabled");
            return;
        }
        final int userId = mInjector.getCurrentUser();
        final DreamPlaylist playlist = getDreamPlaylistInternal(userId);
        if (playlist == null || playlist.getDreams().isEmpty()) {
            pw.println("No dreams in playlist");
            return;
        }
        final DreamItem nextDreamItem = playlist.getNextDream();
        if (nextDreamItem == null) {
            pw.println("No next dream");
            return;
        }
        final ComponentName nextDream = nextDreamItem.componentName;
        if (setActiveDreamInternal(nextDream, userId)) {
            pw.println("Set active dream to: " + nextDream.flattenToShortString());
        } else {
            pw.println("Failed to set active dream to: " + nextDream.flattenToShortString());
        }
    }

    void requestPreviousDreamFromShell(PrintWriter pw) {
        if (!dreamsSwitcher()) {
            pw.println("Dream switcher feature not enabled");
            return;
        }
        final int userId = mInjector.getCurrentUser();
        final DreamPlaylist playlist = getDreamPlaylistInternal(userId);
        if (playlist == null || playlist.getDreams().isEmpty()) {
            pw.println("No dreams in playlist");
            return;
        }
        final DreamItem prevDreamItem = playlist.getPreviousDream();
        if (prevDreamItem == null) {
            pw.println("No previous dream");
            return;
        }
        final ComponentName prevDream = prevDreamItem.componentName;
        if (setActiveDreamInternal(prevDream, userId)) {
            pw.println("Set active dream to: " + prevDream.flattenToShortString());
        } else {
            pw.println("Failed to set active dream to: " + prevDream.flattenToShortString());
        }
    }

    @VisibleForTesting
    void stopDreamInternal(boolean immediate, String reason) {
        synchronized (mLock) {
            stopDreamLocked(immediate, reason);
        }
    }

    @VisibleForTesting
    void startDozingInternal(IBinder token, int screenState,
            @Display.StateReason int reason, float screenBrightness,
            boolean useNormalBrightnessForDoze) {
        Slog.d(TAG, "Dream requested to start dozing: " + token
                + ", screenState=" + Display.stateToString(screenState)
                + ", reason=" + Display.stateReasonToString(reason)
                + ", screenBrightness=" + screenBrightness
                + ", useNormalBrightnessForDoze=" + useNormalBrightnessForDoze);

        synchronized (mLock) {
            if (mCurrentDream != null && mCurrentDream.token == token && mCurrentDream.canDoze) {
                mCurrentDream.dozeScreenState = screenState;
                mCurrentDream.dozeScreenBrightness = screenBrightness;
                mPowerManagerInternal.setDozeOverrideFromDreamManager(
                        screenState, reason, screenBrightness, useNormalBrightnessForDoze);
                if (!mCurrentDream.isDozing) {
                    mCurrentDream.isDozing = true;
                    mDozeWakeLock.acquire();
                }
            }
        }
    }

    private void stopDozingInternal(IBinder token) {
        if (DEBUG) {
            Slog.d(TAG, "Dream requested to stop dozing: " + token);
        }

        synchronized (mLock) {
            if (mCurrentDream != null && mCurrentDream.token == token && mCurrentDream.isDozing) {
                mCurrentDream.isDozing = false;
                mDozeWakeLock.release();
                mPowerManagerInternal.setDozeOverrideFromDreamManager(
                        Display.STATE_UNKNOWN,
                        Display.STATE_REASON_DREAM_MANAGER,
                        PowerManager.BRIGHTNESS_INVALID_FLOAT,
                        /* useNormalBrightnessForDoze= */ false);
            }
        }
    }

    private void forceAmbientDisplayEnabledInternal(boolean enabled) {
        if (DEBUG) {
            Slog.d(TAG, "Force ambient display enabled: " + enabled);
        }

        synchronized (mLock) {
            mForceAmbientDisplayEnabled = enabled;
        }
    }

    @VisibleForTesting
    void setDevicePosturedInternal(boolean isPostured) {
        Slog.d(TAG, "Device postured: " + isPostured);
        synchronized (mLock) {
            mIsPostured = isPostured;
            mHandler.post(() -> mPowerManagerInternal.setDevicePostured(isPostured));
        }
    }

    /**
     * If doze is true, returns the doze component for the user. Otherwise, returns the system dream
     * component, if present. Otherwise, returns the first valid user configured dream component.
     */
    private ComponentName chooseDreamForUser(boolean doze, int userId) {
        final DreamComponentsResolver resolver = getOrCreateResolver(userId);
        return resolver != null ? resolver.resolve(doze, mForceAmbientDisplayEnabled,
                mSystemDreamComponent) : null;
    }

    private ComponentName[] getDreamComponentsForUser(int userId) {
        final DreamComponentsResolver resolver = getOrCreateResolver(userId);
        return resolver != null ? resolver.getDreamComponents() : new ComponentName[0];
    }

    private void updateDreamOnPackageRemoved(String packageName, int userId) {
        final String names = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_COMPONENTS,
                userId);
        final ComponentName[] componentNames =
                DreamComponentNameUtils.fromCommaSeparatedString(names);

        // Filter out any components in the removed package.
        final ComponentName[] filteredComponents =
                Arrays.stream(componentNames)
                        .filter((componentName -> !isSamePackage(packageName, componentName)))
                        .toArray(ComponentName[]::new);
        if (filteredComponents.length != componentNames.length) {
            setDreamComponentsForUser(userId, filteredComponents);
        }
    }

    private static boolean isSamePackage(String packageName, ComponentName componentName) {
        if (packageName == null || componentName == null) {
            return false;
        }
        return TextUtils.equals(componentName.getPackageName(), packageName);
    }

    private void setDreamComponentsForUser(int userId, ComponentName[] componentNames) {
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_COMPONENTS,
                DreamComponentNameUtils.toCommaSeparatedString(componentNames),
                userId);
    }

    @VisibleForTesting
    boolean setActiveDreamInternal(ComponentName componentName, int userId) {
        if (componentName != null) {
            final DreamUserData userData = getOrCreateUserData(userId);
            final DreamPlaylist playlist = userData != null
                    ? userData.mResolver.getDreamPlaylist(mSystemDreamComponent) : null;
            if (playlist == null || !playlist.contains(componentName)) {
                Slog.w(
                        TAG,
                        "Attempted to set active dream component that is not in the playlist: "
                                + componentName
                                + " (playlist="
                                + playlist
                                + ", userId="
                                + userId
                                + ")");
                return false;
            }
        }

        return Settings.Secure.putStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_ACTIVE_COMPONENT,
                componentName != null ? componentName.flattenToString() : null,
                userId);
    }

    @VisibleForTesting
    void setSystemDreamComponentInternal(ComponentName component, IBinder token) {
        if (systemDreamDeathRecipient() && component != null && token == null) {
            throw new IllegalArgumentException("System dream component requires a non-null token.");
        }

        synchronized (mLock) {
            if (Objects.equals(mSystemDreamComponent, component)
                    && Objects.equals(mSystemDreamComponentToken, token)) {
                return;
            }

            if (systemDreamDeathRecipient()) {
                releaseSystemDreamTokenLocked();
                if (token != null && !attachSystemDreamDeathRecipientLocked(token)) {
                    Slog.w(TAG, "System dream component client died before linkToDeath. Clearing.");
                    component = null;
                    token = null;
                }
            }

            final ComponentName previousComponent = mSystemDreamComponent;
            mSystemDreamComponent = component;
            mSystemDreamComponentToken = token;

            if (!Objects.equals(previousComponent, mSystemDreamComponent)) {
                handleSystemDreamChangedLocked();
            }
        }
    }

    /**
     * Helper to release the lifecycle token for the system dream component. This must be called
     * while holding mLock.
     */
    @GuardedBy("mLock")
    private void releaseSystemDreamTokenLocked() {
        if (mSystemDreamComponentToken != null) {
            mSystemDreamComponentToken.unlinkToDeath(mSystemDreamComponentDeathRecipient, 0);
            mSystemDreamComponentToken = null;
        }
    }

    /**
     * Attempts to link the death recipient. Returns false if the binder is already dead.
     */
    @GuardedBy("mLock")
    private boolean attachSystemDreamDeathRecipientLocked(IBinder token) {
        try {
            token.linkToDeath(mSystemDreamComponentDeathRecipient, 0);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Handles side effects of the system dream component changing. This must be called while
     * holding mLock.
     */
    @GuardedBy("mLock")
    private void handleSystemDreamChangedLocked() {
        reportKeepDreamingWhenUnpluggingChanged(shouldKeepDreamingWhenUnplugging());

        if (dreamsSwitcher()) {
            final int userId = mInjector.getCurrentUser();
            final DreamUserData userData = mUserData.get(userId);
            if (userData != null) {
                userData.mResolver.invalidate();
            }
            mHandler.post(() -> notifyPlaylistChanged(userId, true /* immediate */));
        } else {
            // Switch dream if currently dreaming and not dozing.
            if (isDreamingInternal() && !currentDreamCanDozeLocked()) {
                startDreamInternal(
                        false /*doze*/,
                        (mSystemDreamComponent == null ? "clear" : "set")
                                + " system dream component");
            }
        }
    }

    private boolean shouldKeepDreamingWhenUnplugging() {
        return mKeepDreamingWhenUnpluggingDefault && mSystemDreamComponent == null;
    }

    private ComponentName getDefaultDreamComponentForUser(int userId) {
        final DreamUserData userData = getOrCreateUserData(userId);
        return userData != null ? userData.mResolver.getDefaultDreamComponent() : null;
    }

    private ComponentName getDozeComponent() {
        return getDozeComponent(mInjector.getCurrentUser());
    }

    private ComponentName getDozeComponent(int userId) {
        final DreamComponentsResolver resolver = getOrCreateResolver(userId);
        return resolver != null ? resolver.getDozeComponent(mForceAmbientDisplayEnabled) : null;
    }


    @GuardedBy("mLock")
    private void startDreamLocked(final ComponentName name,
            final boolean isPreviewMode, final boolean canDoze, final int userId,
            final String reason) {
        if (mCurrentDream != null
                && !mCurrentDream.isWaking
                && Objects.equals(mCurrentDream.name, name)
                && mCurrentDream.isPreview == isPreviewMode
                && mCurrentDream.canDoze == canDoze
                && mCurrentDream.userId == userId) {
            Slog.i(TAG, "Already in target dream.");
            return;
        }

        Slog.i(TAG, "Entering dreamland.");

        if (mCurrentDream != null && mCurrentDream.isDozing) {
            stopDozingInternal(mCurrentDream.token);
        }

        mCurrentDream = new DreamRecord(name, userId, isPreviewMode, canDoze);

        if (!mCurrentDream.name.equals(mAmbientDisplayComponent)) {
            // TODO(b/213906448): Remove when metrics based on new atom are fully rolled out.
            mUiEventLogger.log(DreamUiEventLogger.DreamUiEventEnum.DREAM_START);
            mDreamUiEventLogger.log(DreamUiEventLogger.DreamUiEventEnum.DREAM_START,
                    mCurrentDream.name.flattenToString());
        }

        PowerManager.WakeLock wakeLock = mPowerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, DREAM_WAKE_LOCK_TAG);
        final Binder dreamToken = mCurrentDream.token;
        mHandler.post(wakeLock.wrap(() -> {
            mAtmInternal.notifyActiveDreamChanged(name);
            mController.startDream(dreamToken, name, isPreviewMode, canDoze, userId, wakeLock,
                    mDreamOverlayServiceName, reason);
        }));
    }

    @GuardedBy("mLock")
    private void stopDreamLocked(final boolean immediate, String reason) {
        if (mCurrentDream != null) {
            if (immediate) {
                Slog.i(TAG, "Leaving dreamland.");
                cleanupDreamLocked();
            } else if (mCurrentDream.isWaking) {
                return; // already waking
            } else {
                Slog.i(TAG, "Gently waking up from dream.");
                mCurrentDream.isWaking = true;
            }

            mHandler.post(() -> mController.stopDream(immediate, reason));
        }
    }

    @GuardedBy("mLock")
    private void cleanupDreamLocked() {
        mHandler.post(() -> mAtmInternal.notifyActiveDreamChanged(null));

        if (mCurrentDream == null) {
            return;
        }

        if (!mCurrentDream.name.equals(mAmbientDisplayComponent)) {
            // TODO(b/213906448): Remove when metrics based on new atom are fully rolled out.
            mUiEventLogger.log(DreamUiEventLogger.DreamUiEventEnum.DREAM_STOP);
            mDreamUiEventLogger.log(DreamUiEventLogger.DreamUiEventEnum.DREAM_STOP,
                    mCurrentDream.name.flattenToString());
        }
        if (mCurrentDream.isDozing) {
            mPowerManager.wakeUp(
                    SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_DOZE_STOPPED,
                    "android.server.dreams:requestAwaken");
            mDozeWakeLock.release();
        }
        mCurrentDream = null;
    }

    private void checkPermission(String permission) {
        if (mContext.checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    private void writePulseGestureEnabled() {
        ComponentName name = getDozeComponent();
        final int userId = mInjector.getCurrentUser();
        final DreamComponentsResolver resolver = getOrCreateResolver(userId);
        boolean dozeEnabled = resolver != null && resolver.isValid(name);
        LocalServices.getService(InputManagerInternal.class).setPulseGestureEnabled(dozeEnabled);
    }

    private final DreamController.Listener mControllerListener = new DreamController.Listener() {
        @Override
        public void onDreamStarted(Binder token) {
            // Note that this event is distinct from DreamManagerService#startDreamLocked as it
            // tracks the DreamService attach point from DreamController, closest to the broadcast
            // of ACTION_DREAMING_STARTED.

            reportDreamingStarted();
        }

        @Override
        public void onDreamStopped(Binder token) {
            synchronized (mLock) {
                if (mCurrentDream != null && mCurrentDream.token == token) {
                    cleanupDreamLocked();
                }
            }

            reportDreamingStopped();
        }
    };

    private final ContentObserver mDozeEnabledObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            writePulseGestureEnabled();
        }
    };

    /**
     * A helper interface to inject dependencies into {@link DreamManagerService}.
     * @hide
     */
    @VisibleForTesting
    interface Injector {
        Context getContext();
        Handler getHandler();
        AmbientDisplayConfiguration getDozeConfig();
        DreamController getDreamController(DreamController.Listener controllerListener);

        DreamComponentsResolver getDreamComponentsResolver(
                DreamValidator dreamValidator,
                int userId,
                AmbientDisplayConfiguration dozeConfig,
                UserManagerInternal userManagerInternal,
                boolean dreamsOnlyEnabledForDockUser,
                DreamRepository dreamRepository);

        @UserIdInt int getCurrentUser();
    }

    private static final class DefaultInjector implements Injector {
        private final Context mContext;
        private final Handler mHandler;

        DefaultInjector(Context context, Handler handler) {
            mContext = context;
            mHandler = handler;
        }

        @Override
        public Context getContext() {
            return mContext;
        }

        @Override
        public Handler getHandler() {
            return mHandler;
        }

        @Override
        public AmbientDisplayConfiguration getDozeConfig() {
            return new AmbientDisplayConfiguration(mContext);
        }

        @Override
        public DreamController getDreamController(DreamController.Listener controllerListener) {
            return new DreamController(mContext, mHandler, controllerListener);
        }

        @Override
        public DreamComponentsResolver getDreamComponentsResolver(
                DreamValidator dreamValidator,
                int userId,
                AmbientDisplayConfiguration dozeConfig,
                UserManagerInternal userManagerInternal,
                boolean dreamsOnlyEnabledForDockUser,
                DreamRepository dreamRepository) {
            return new DreamComponentsResolver(
                    userId,
                    dozeConfig,
                    userManagerInternal,
                    dreamsOnlyEnabledForDockUser,
                    dreamRepository,
                    dreamValidator);
        }

        @Override
        public @UserIdInt int getCurrentUser() {
            return ActivityManager.getCurrentUser();
        }
    }

    private static final class DreamUserData {
        final Context mContext;
        final DreamMetadataProvider mMetadataProvider;
        final DreamRepository mRepository;
        final DreamComponentsResolver mResolver;

        DreamUserData(Context context, DreamComponentsResolver resolver, DreamRepository repository,
                DreamMetadataProvider metadataProvider) {
            mContext = context;
            mResolver = resolver;
            mRepository = repository;
            mMetadataProvider = metadataProvider;
        }
    }

    /**
     * Handler for asynchronous operations performed by the dream manager.
     * Ensures operations to {@link DreamController} are single-threaded.
     */
    private static final class DreamHandler extends Handler {
        public DreamHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }
    }

    private DreamPlaylist getDreamPlaylistInternal(int userId) {
        ComponentName systemDreamComponent;
        synchronized (mLock) {
            systemDreamComponent = mSystemDreamComponent;
        }
        final DreamComponentsResolver resolver = getOrCreateResolver(userId);
        return resolver != null ? resolver.getDreamPlaylist(systemDreamComponent) : null;
    }

    private final class BinderService extends IDreamManager.Stub {

        BinderService(Context context) {
            super(PermissionEnforcer.fromContext(context));
        }

        @Override // Binder call
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
            final long ident = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args, @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) throws RemoteException {
            new DreamShellCommand(DreamManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        @Override // Binder call
        public ComponentName[] getDreamComponents() {
            return getDreamComponentsForUser(UserHandle.getCallingUserId());
        }

        @Override // Binder call
        public ComponentName[] getDreamComponentsForUser(int userId) {
            checkPermission(android.Manifest.permission.READ_DREAM_STATE);
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "getDreamComponents", null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return DreamManagerService.this.getDreamComponentsForUser(userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void setDreamComponents(ComponentName[] componentNames) {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            final int userId = UserHandle.getCallingUserId();
            final long ident = Binder.clearCallingIdentity();
            try {
                setDreamComponentsForUser(userId, componentNames);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void setDreamComponentsForUser(int userId, ComponentName[] componentNames) {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "setDreamComponents", null);

            final long ident = Binder.clearCallingIdentity();
            try {
                DreamManagerService.this.setDreamComponentsForUser(userId, componentNames);
                if (dreamsSwitcher()) {
                    // Also clear the active dream if one exists, to force the dream to fallback to
                    // the first available dream in the playlist.
                    DreamManagerService.this.setActiveDreamInternal(null, userId);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @EnforcePermission(android.Manifest.permission.WRITE_DREAM_STATE)
        @Override // Binder call
        public boolean setActiveDream(ComponentName componentName, int userId) {
            setActiveDream_enforcePermission();
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "setActiveDream", null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return DreamManagerService.this.setActiveDreamInternal(componentName, userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void setSystemDreamComponent(ComponentName componentName, IBinder token) {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                DreamManagerService.this.setSystemDreamComponentInternal(componentName, token);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void registerDreamOverlayService(ComponentName overlayComponent) {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            // Store the overlay service component so that it can be passed to the dream when it is
            // invoked.
            mDreamOverlayServiceName = overlayComponent;
        }

        @Override // Binder call
        public ComponentName getDefaultDreamComponentForUser(int userId) {
            checkPermission(android.Manifest.permission.READ_DREAM_STATE);
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "getDefaultDreamComponent", null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return DreamManagerService.this.getDefaultDreamComponentForUser(userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isDreaming() {
            checkPermission(android.Manifest.permission.READ_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                return isDreamingInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isDreamingOrInPreview() {
            checkPermission(android.Manifest.permission.READ_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                return isDreamingOrInPreviewInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }


        @Override // Binder call
        public void dream() {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                requestDreamInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean canStartDreaming(boolean isScreenOn) {
            checkPermission(android.Manifest.permission.READ_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                return canStartDreamingInternal(isScreenOn);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void testDream(int userId, ComponentName dream) {
            if (dream == null) {
                throw new IllegalArgumentException("dream must not be null");
            }
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "testDream", null);

            final int currentUserId = mInjector.getCurrentUser();
            if (userId != currentUserId) {
                // This check is inherently prone to races but at least it's something.
                Slog.w(TAG, "Aborted attempt to start a test dream while a different "
                        + " user is active: userId=" + userId
                        + ", currentUserId=" + currentUserId);
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                testDreamInternal(dream, userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void awaken() {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                requestAwakenInternal("request awaken");
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void finishSelf(IBinder token, boolean immediate) {
            // Requires no permission, called by Dream from an arbitrary process.
            if (token == null) {
                throw new IllegalArgumentException("token must not be null");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                finishSelfInternal(token, immediate);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void finishSelfOneway(IBinder token, boolean immediate) {
            // Requires no permission, called by Dream from an arbitrary process.
            if (token == null) {
                throw new IllegalArgumentException("token must not be null");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                finishSelfInternal(token, immediate);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void startDozing(
                IBinder token, int screenState, @Display.StateReason int reason,
                float screenBrightness, boolean useNormalBrightnessForDoze) {
            // Requires no permission, called by Dream from an arbitrary process.
            if (token == null) {
                throw new IllegalArgumentException("token must not be null");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                startDozingInternal(token, screenState, reason, screenBrightness,
                        useNormalBrightnessForDoze);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void startDozingOneway(
                IBinder token, int screenState, @Display.StateReason int reason,
                float screenBrightness, boolean useNormalBrightnessForDoze) {
            // Requires no permission, called by Dream from an arbitrary process.
            if (token == null) {
                throw new IllegalArgumentException("token must not be null");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                startDozingInternal(token, screenState, reason, screenBrightness,
                        useNormalBrightnessForDoze);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void stopDozing(IBinder token) {
            // Requires no permission, called by Dream from an arbitrary process.
            if (token == null) {
                throw new IllegalArgumentException("token must not be null");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                stopDozingInternal(token);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void forceAmbientDisplayEnabled(boolean enabled) {
            checkPermission(android.Manifest.permission.DEVICE_POWER);

            final long ident = Binder.clearCallingIdentity();
            try {
                forceAmbientDisplayEnabledInternal(enabled);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void startDreamActivity(@NonNull Intent intent) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            // We post here, because startDreamActivity and setDreamAppTask have to run
            // synchronously and DreamController#setDreamAppTask has to run on mHandler.
            mHandler.post(
                    () -> {
                        final Binder dreamToken;
                        final String dreamPackageName;
                        synchronized (mLock) {
                            if (mCurrentDream == null) {
                                Slog.e(
                                        TAG,
                                        "Attempt to start DreamActivity, but the device is not"
                                                + " dreaming. Aborting without starting the"
                                                + " DreamActivity.");
                                return;
                            }
                            dreamToken = mCurrentDream.token;
                            dreamPackageName = mCurrentDream.name.getPackageName();
                        }

                        if (!canLaunchDreamActivity(
                                dreamPackageName, intent.getPackage(), callingUid)) {
                            Slog.e(
                                    TAG,
                                    "The dream activity can be started only when the device is"
                                            + " dreaming and only by the active dream package.");
                            return;
                        }

                        final IAppTask appTask =
                                mAtmInternal.startDreamActivity(intent, callingUid, callingPid);
                        if (appTask == null) {
                            Slog.e(TAG, "Could not start dream activity.");
                            stopDreamInternal(true, "DreamActivity not started");
                            return;
                        }
                        mController.setDreamAppTask(dreamToken, appTask);
                    });
        }

        @Override
        public void setDreamIsObscured(boolean isObscured) {
            if (!dreamHandlesBeingObscured()) {
                return;
            }

            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> mController.setDreamIsObscured(isObscured));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void setDevicePostured(boolean isPostured) {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                setDevicePosturedInternal(isPostured);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void setScreensaverEnabled(boolean enabled) {
            checkPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS);
            final UserHandle userHandle = getCallingUserHandle();
            final long ident = Binder.clearCallingIdentity();
            try {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.SCREENSAVER_ENABLED, enabled ? 1 : 0,
                        userHandle.getIdentifier());
                mPowerManagerInternal.updateSettings();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @EnforcePermission(android.Manifest.permission.READ_DREAM_STATE)
        @Override // Binder call
        public void registerListener(IDreamManagerListener listener, int userId) {
            registerListener_enforcePermission();

            if (!dreamsSwitcher()) {
                Slog.e(TAG, "Dreams switcher flag is not enabled, ignoring register request.");
                return;
            }

            // Validate the incoming user id, also checks if the caller has permission to
            // query this user id.
            userId =
                    ActivityManager.handleIncomingUser(
                            Binder.getCallingPid(),
                            Binder.getCallingUid(),
                            userId,
                            /* allowAll= */ false,
                            /* requireFull= */ false,
                            /* name= */ "registerListener",
                            /* callerPackage= */ null);

            final long ident = Binder.clearCallingIdentity();
            try {
                DreamManagerService.this.registerListener(listener, userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @EnforcePermission(android.Manifest.permission.READ_DREAM_STATE)
        @Override // Binder call
        public void unregisterListener(IDreamManagerListener listener, int userId) {
            unregisterListener_enforcePermission();

            if (!dreamsSwitcher()) {
                Slog.e(TAG, "Dreams switcher flag is not enabled, ignoring unregister request.");
                return;
            }

            userId =
                    ActivityManager.handleIncomingUser(
                            Binder.getCallingPid(),
                            Binder.getCallingUid(),
                            userId,
                            /* allowAll= */ false,
                            /* requireFull= */ false,
                            /* name= */ "unregisterListener",
                            /* callerPackage= */ null);

            final long ident = Binder.clearCallingIdentity();
            try {
                DreamManagerService.this.unregisterListener(listener, userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @EnforcePermission(android.Manifest.permission.READ_DREAM_STATE)
        @Override // Binder call
        public DreamPlaylist getDreamPlaylist(int userId) {
            getDreamPlaylist_enforcePermission();

            if (!dreamsSwitcher()) {
                Slog.e(TAG, "Dreams switcher flag is not enabled, ignoring playlist request.");
                return null;
            }

            userId =
                    ActivityManager.handleIncomingUser(
                            Binder.getCallingPid(),
                            Binder.getCallingUid(),
                            userId,
                            /* allowAll= */ false,
                            /* requireFull= */ false,
                            /* name= */ "getDreamPlaylist",
                            /* callerPackage= */ null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return getDreamPlaylistInternal(userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        boolean canLaunchDreamActivity(String dreamPackageName, String packageName,
                int callingUid) {
            if (dreamPackageName == null || packageName == null) {
                Slog.e(TAG, "Cannot launch dream activity due to invalid state. dream component= "
                        + dreamPackageName + ", packageName=" + packageName);
                return false;
            }
            if (!mPmInternal.isSameApp(packageName, callingUid, UserHandle.getUserId(callingUid))) {
                Slog.e(TAG, "Cannot launch dream activity because package="
                        + packageName + " does not match callingUid=" + callingUid);
                return false;
            }
            if (packageName.equals(dreamPackageName)) {
                return true;
            }
            Slog.e(TAG, "Dream packageName does not match active dream. Package " + packageName
                    + " does not match " + dreamPackageName);
            return false;
        }

    }

    private final class LocalService extends DreamManagerInternal {
        @Override
        public void startDream(boolean doze, String reason) {
            startDreamInternal(doze, reason);
        }

        @Override
        public void stopDream(boolean immediate, String reason) {
            stopDreamInternal(immediate, reason);
        }

        @Override
        public void stopDreamWithoutWaking(boolean immediate, String reason) {
            synchronized (mLock) {
                // Stop dozing first, so that cleaning up the dream doesn't wake the device up.
                if (mCurrentDream != null && mCurrentDream.isDozing) {
                    stopDozingInternal(mCurrentDream.token);
                }
                stopDreamLocked(immediate, reason);
            }
        }

        @Override
        public boolean isDreaming() {
            return isDreamingInternal();
        }

        @Override
        public boolean canStartDreaming(boolean isScreenOn) {
            return canStartDreamingInternal(isScreenOn);
        }

        @Override
        public boolean dreamConditionActive() {
            return dreamConditionActiveInternal();
        }

        @Override
        public void requestDream() {
            requestDreamInternal();
        }

        @Override
        public void registerDreamManagerStateListener(DreamManagerStateListener listener) {
            mDreamManagerStateListeners.add(listener);
            // Initialize the listener's state.
            listener.onKeepDreamingWhenUnpluggingChanged(shouldKeepDreamingWhenUnplugging());
        }

        @Override
        public void unregisterDreamManagerStateListener(DreamManagerStateListener listener) {
            mDreamManagerStateListeners.remove(listener);
        }
    }

    private static final class DreamRecord {
        public final Binder token = new Binder();
        public final ComponentName name;
        public final int userId;
        public final boolean isPreview;
        public final boolean canDoze;
        public boolean isDozing = false;
        public boolean isWaking = false;
        public int dozeScreenState = Display.STATE_UNKNOWN;
        public float dozeScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;

        DreamRecord(ComponentName name, int userId, boolean isPreview, boolean canDoze) {
            this.name = name;
            this.userId = userId;
            this.isPreview = isPreview;
            this.canDoze = canDoze;
        }

        @Override
        public String toString() {
            return "DreamRecord{"
                    + "token=" + token
                    + ", name=" + name
                    + ", userId=" + userId
                    + ", isPreview=" + isPreview
                    + ", canDoze=" + canDoze
                    + ", isDozing=" + isDozing
                    + ", isWaking=" + isWaking
                    + ", dozeScreenState=" + dozeScreenState
                    + ", dozeScreenBrightness=" + dozeScreenBrightness
                    + '}';
        }
    }

    private final Runnable mSystemPropertiesChanged = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "System properties changed");
            synchronized (mLock) {
                if (mCurrentDream != null &&  mCurrentDream.name != null && mCurrentDream.canDoze
                        && !mCurrentDream.name.equals(getDozeComponent())) {
                    // May have updated the doze component, wake up
                    mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                            "android.server.dreams:SYSPROP");
                }
            }
        }
    };

    private final class SystemDreamComponentDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            Slog.w(TAG, "System dream component client died. Clearing system dream.");
            setSystemDreamComponentInternal(null, null);
        }
    }

    void registerListener(IDreamManagerListener listener, int userId) {
        mDreamPlaylistUpdater.registerListener(listener, userId);
    }

    void unregisterListener(IDreamManagerListener listener, int userId) {
        mDreamPlaylistUpdater.unregisterListener(listener, userId);
    }

    private void notifyPlaylistChanged(int userId) {
        notifyPlaylistChanged(userId, false /* immediate */);
    }

    private void notifyPlaylistChanged(int userId, boolean immediate) {
        ComponentName systemDreamComponent;
        synchronized (mLock) {
            systemDreamComponent = mSystemDreamComponent;
        }
        if (immediate) {
            mDreamPlaylistUpdater.refreshImmediately(userId, systemDreamComponent);
        } else {
            mDreamPlaylistUpdater.refresh(userId, systemDreamComponent);
        }
    }
}
