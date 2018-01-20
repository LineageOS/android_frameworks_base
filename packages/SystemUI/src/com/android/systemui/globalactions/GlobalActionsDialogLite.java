/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.systemui.globalactions;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_GLOBAL_ACTIONS;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;
import static com.android.systemui.shared.system.BlurUtils.isVolumeAndPowerBlurEnabled;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.IActivityManager;
import android.app.WallpaperManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.sysprop.TelephonyProperties;
import android.telecom.TelecomManager;
import android.telephony.ServiceState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.ArraySet;
import android.view.ContextThemeWrapper;
import android.view.GestureDetector;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.app.animation.Interpolators;
import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.EmergencyAffordanceManager;
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Flags;
import com.android.systemui.FontStyles;
import com.android.systemui.MultiListLayout.MultiListAdapter;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.animation.TransitionAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.display.data.repository.DisplayWindowPropertiesRepository;
import com.android.systemui.display.shared.model.DisplayWindowProperties;
import com.android.systemui.globalactions.domain.interactor.GlobalActionsInteractor;
import com.android.systemui.globalactions.shared.model.GlobalActionType;
import com.android.systemui.globalactions.shared.model.GlobalActionsEvent;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor;
import com.android.systemui.plugins.GlobalActions.GlobalActionsManager;
import com.android.systemui.plugins.GlobalActionsPanelPlugin;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.scrim.ScrimDrawable;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeDisplayAware;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.DialogDelegate;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.topui.TopUiController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.user.domain.interactor.UserLogoutInteractor;
import com.android.systemui.util.EmergencyDialerConstants;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor;

import dagger.Lazy;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import org.lineageos.internal.util.PowerMenuUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that may show depending
 * on whether the keyguard is showing, and whether the device is provisioned.
 */
public class GlobalActionsDialogLite implements DialogInterface.OnDismissListener,
        DialogInterface.OnShowListener,
        ConfigurationController.ConfigurationListener,
        GlobalActionsPanelPlugin.Callbacks,
        LifecycleOwner {

    public static final String SYSTEM_DIALOG_REASON_KEY = "reason";
    public static final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    public static final String SYSTEM_DIALOG_REASON_DREAM = "dream";

    @VisibleForTesting
    static final String GLOBAL_ACTION_KEY_FEEDBACK = "feedback";

    private static final boolean DEBUG = false;

    private static final String TAG = "GlobalActionsDialogLite";

    private static final String INTERACTION_JANK_TAG = "global_actions";

    private static final boolean SHOW_SILENT_TOGGLE = true;

    private static final String RESTART_ACTION_KEY_RESTART = "restart";
    private static final String RESTART_ACTION_KEY_RESTART_RECOVERY = "restart_recovery";
    private static final String RESTART_ACTION_KEY_RESTART_BOOTLOADER = "restart_bootloader";
    private static final String RESTART_ACTION_KEY_RESTART_DOWNLOAD = "restart_download";
    private static final String RESTART_ACTION_KEY_RESTART_FASTBOOT = "restart_fastboot";

    // See NotificationManagerService#scheduleDurationReachedLocked
    private static final long TOAST_FADE_TIME = 333;
    // See NotificationManagerService.LONG_DELAY
    private static final int TOAST_VISIBLE_TIME = 3500;

    private static final int DIALOG_WINDOW_TYPE = TYPE_STATUS_BAR_SUB_PANEL;

    @NonNull
    private final Context mContext;
    @NonNull
    private final GlobalActionsManager mWindowManagerFuncs;
    @NonNull
    private final AudioManager mAudioManager;
    @NonNull
    private final LockPatternUtils mLockPatternUtils;
    @NonNull
    private final BroadcastDispatcher mBroadcastDispatcher;
    @NonNull
    private final TelephonyListenerManager mTelephonyListenerManager;
    @NonNull
    private final GlobalSettings mGlobalSettings;
    @NonNull
    private final SecureSettings mSecureSettings;
    @NonNull
    private final Resources mResources;
    @NonNull
    private final ConfigurationController mConfigurationController;
    @NonNull
    private final ActivityStarter mActivityStarter;
    @NonNull
    private final UserTracker mUserTracker;
    @NonNull
    private final KeyguardStateController mKeyguardStateController;
    @NonNull
    private final UserManager mUserManager;
    @NonNull
    private final TrustManager mTrustManager;
    @NonNull
    private final IActivityManager mIActivityManager;
    @Nullable
    private final TelecomManager mTelecomManager;
    @NonNull
    private final MetricsLogger mMetricsLogger;
    @NonNull
    private final StatusBarWindowControllerStore mStatusBarWindowControllerStore;
    @NonNull
    private final IWindowManager mIWindowManager;
    @NonNull
    private final Executor mBackgroundExecutor;
    @NonNull
    private final UiEventLogger mUiEventLogger;
    @NonNull
    private final RingerModeTracker mRingerModeTracker;
    @NonNull
    private final PackageManager mPackageManager;
    @NonNull
    private final ShadeController mShadeController;
    @NonNull
    private final UserLogoutInteractor mLogoutInteractor;
    @NonNull
    private final GlobalActionsInteractor mInteractor;
    @NonNull
    private final Lazy<DisplayWindowPropertiesRepository> mDisplayWindowPropertiesRepositoryLazy;
    @NonNull
    private final PowerManager mPowerManager;
    @NonNull
    private final BroadcastSender mBroadcastSender;
    @NonNull
    private final ActionsDialogLiteDelegate.Factory mDelegateFactory;
    @NonNull
    private final Handler mHandler;
    private final boolean mHasTelephonyCalling;
    private final boolean mIsTv;
    private final boolean mHasVibrator;
    private final boolean mShowSilentToggle;
    private final boolean mTranslucentPowerMenu;
    @NonNull
    private final EmergencyAffordanceManager mEmergencyAffordanceManager;
    @NonNull
    private final ScreenshotHelper mScreenshotHelper;

    // Used for RingerModeTracker
    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

    @VisibleForTesting
    final ArrayList<Action> mItems = new ArrayList<>();
    @VisibleForTesting
    final ArrayList<Action> mOverflowItems = new ArrayList<>();
    @VisibleForTesting
    final ArrayList<Action> mPowerItems = new ArrayList<>();
    @VisibleForTesting
    final ArrayList<Action> mRestartItems = new ArrayList<>();

    @NonNull
    private Handler mMainHandler;

    @VisibleForTesting
    @Nullable
    ActionsDialogLiteDelegate mDelegate;

    private Action mSilentModeAction;
    private ToggleAction mAirplaneModeOn;

    private MyAdapter mAdapter;
    private MyOverflowAdapter mOverflowAdapter;
    private MyPowerOptionsAdapter mPowerAdapter;
    private MyRestartOptionsAdapter mRestartAdapter;

    private boolean mKeyguardShowing = false;
    private boolean mDeviceProvisioned = false;
    private ToggleState mAirplaneState = ToggleState.Off;
    private boolean mIsWaitingForEcmExit = false;
    private int mDialogPressDelay = DIALOG_PRESS_DELAY; // ms
    private int mSmallestScreenWidthDp;
    private int mOrientation;
    private int mGlobalActionDialogTimeout;
    /**
     * Latch used in testing to wait for the delegate to be dismissed. This will be unset after it
     * is notified.
     */
    @Nullable
    private CountDownLatch mDismissLatchForTesting;

    private final UserTracker.Callback mOnUserSwitched = new UserTracker.Callback() {
        @Override
        public void onBeforeUserSwitching(int newUser) {
            // Dismiss the dialog as soon as we start switching. This will schedule a message
            // in a handler so it will be pretty quick.
            dismissDialog();
        }

        @Override
        public void onUserChanged(int newUser, @NonNull Context userContext) {
            updateFeedbackReceiverState();
        }
    };

    @VisibleForTesting
    @Nullable
    ComponentName mFirstFeedbackReceiver;
    private static final Intent FEEDBACK_INTENT = new Intent(Settings.ACTION_REQUEST_FEEDBACK);

    private boolean isFeedbackActionEnabled() {
        return Flags.globalActionsFeedbackAction()
                && Arrays.asList(getDefaultActions()).contains(GLOBAL_ACTION_KEY_FEEDBACK);
    }

    private void updateFeedbackReceiverState() {
        if (!isFeedbackActionEnabled()) {
            return;
        }

        mBackgroundExecutor.execute(
                () -> {
                    final List<ResolveInfo> receivers =
                            mPackageManager.queryBroadcastReceiversAsUser(
                                    FEEDBACK_INTENT,
                                    PackageManager.MATCH_DIRECT_BOOT_AWARE
                                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                                    mUserTracker.getUserId());
                    Log.d(TAG, "send feedback receivers: " + receivers);

                    final ComponentName firstReceiver;
                    if (receivers.isEmpty()) {
                        firstReceiver = null;
                    } else {
                        final ResolveInfo ri = receivers.get(0);
                        firstReceiver = new ComponentName(ri.activityInfo.packageName,
                                ri.activityInfo.name);
                    }

                    if (Objects.equals(mFirstFeedbackReceiver, firstReceiver)) {
                        return;
                    }
                    mMainHandler.post(() -> {
                        if (!Objects.equals(mFirstFeedbackReceiver, firstReceiver)) {
                            mFirstFeedbackReceiver = firstReceiver;
                            if (mDelegate != null && mDelegate.isShowing()) {
                                mDelegate.refreshDialog();
                            }
                        }
                    });
                });
    }

    /**
     * @param context everything needs a context :(
     */
    @Inject
    public GlobalActionsDialogLite(
            @ShadeDisplayAware @NonNull Context context,
            @NonNull GlobalActionsManager windowManagerFuncs,
            @NonNull AudioManager audioManager,
            @NonNull LockPatternUtils lockPatternUtils,
            @NonNull BroadcastDispatcher broadcastDispatcher,
            @NonNull TelephonyListenerManager telephonyListenerManager,
            @NonNull GlobalSettings globalSettings,
            @NonNull SecureSettings secureSettings,
            @NonNull VibratorHelper vibrator,
            @ShadeDisplayAware @NonNull Resources resources,
            @ShadeDisplayAware @NonNull ConfigurationController configurationController,
            @NonNull ActivityStarter activityStarter,
            @NonNull UserTracker userTracker,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull UserManager userManager,
            @NonNull TrustManager trustManager,
            @NonNull IActivityManager iActivityManager,
            @Nullable TelecomManager telecomManager,
            @NonNull MetricsLogger metricsLogger,
            @NonNull StatusBarWindowControllerStore statusBarWindowControllerStore,
            @NonNull IWindowManager iWindowManager,
            @Background @NonNull Executor backgroundExecutor,
            @NonNull UiEventLogger uiEventLogger,
            @NonNull RingerModeTracker ringerModeTracker,
            @Main @NonNull Handler handler,
            @NonNull PackageManager packageManager,
            @NonNull ShadeController shadeController,
            @NonNull UserLogoutInteractor logoutInteractor,
            @NonNull GlobalActionsInteractor interactor,
            @NonNull Lazy<DisplayWindowPropertiesRepository> displayWindowPropertiesRepository,
            @NonNull PowerManager powerManager,
            @NonNull BroadcastSender broadcastSender,
            @ShadeDisplayAware @NonNull EmergencyAffordanceManager emergencyAffordanceManager,
            @ShadeDisplayAware @NonNull ScreenshotHelper screenshotHelper,
            @NonNull ActionsDialogLiteDelegate.Factory delegateFactory) {
        mContext = context;
        mWindowManagerFuncs = windowManagerFuncs;
        mAudioManager = audioManager;
        mLockPatternUtils = lockPatternUtils;
        mTelephonyListenerManager = telephonyListenerManager;
        mKeyguardStateController = keyguardStateController;
        mBroadcastDispatcher = broadcastDispatcher;
        mBroadcastSender = broadcastSender;
        mGlobalSettings = globalSettings;
        mSecureSettings = secureSettings;
        mResources = resources;
        mConfigurationController = configurationController;
        mActivityStarter = activityStarter;
        mUserTracker = userTracker;
        mUserManager = userManager;
        mTrustManager = trustManager;
        mIActivityManager = iActivityManager;
        mTelecomManager = telecomManager;
        mMetricsLogger = metricsLogger;
        mUiEventLogger = uiEventLogger;
        mStatusBarWindowControllerStore = statusBarWindowControllerStore;
        mIWindowManager = iWindowManager;
        mBackgroundExecutor = backgroundExecutor;
        mRingerModeTracker = ringerModeTracker;
        mMainHandler = handler;
        mSmallestScreenWidthDp = resources.getConfiguration().smallestScreenWidthDp;
        mOrientation = resources.getConfiguration().orientation;
        mShadeController = shadeController;
        mLogoutInteractor = logoutInteractor;
        mInteractor = interactor;
        mDisplayWindowPropertiesRepositoryLazy = displayWindowPropertiesRepository;
        mPowerManager = powerManager;
        mPackageManager = packageManager;
        mDelegateFactory = delegateFactory;
        mTranslucentPowerMenu =
                resources.getBoolean(
                        com.android.systemui.res.R.bool.config_translucentStandalonePowerMenu);

        mHandler = new Handler(mMainHandler.getLooper()) {
            public void handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MESSAGE_TIMEOUT_DISMISS:
                        mUiEventLogger.log(GlobalActionsEvent.GA_CLOSE_TIMEOUT);
                        // fallthrough
                    case MESSAGE_DISMISS:
                        if (mDelegate != null) {
                            if (SYSTEM_DIALOG_REASON_DREAM.equals(msg.obj)) {
                                // Hide instantly.
                                mDelegate.hide();
                            }
                            boolean dismissWithoutAnimation = msg.arg1 == 1;
                            if (dismissWithoutAnimation) {
                                mDelegate.dismissWithoutAnimation();
                            } else {
                                mDelegate.dismiss();
                            }
                            mDelegate = null;
                        }
                        if (mDismissLatchForTesting != null) {
                            mDismissLatchForTesting.countDown();
                            mDismissLatchForTesting = null;
                        }
                        break;
                    case MESSAGE_REFRESH:
                        refreshSilentMode();
                        mAdapter.notifyDataSetChanged();
                        break;
                }
            }
        };

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter);

        mHasTelephonyCalling = packageManager.hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_CALLING);
        mIsTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK);

        updateFeedbackReceiverState(); // Initial check

        // get notified of phone state changes
        mTelephonyListenerManager.addServiceStateListener(mPhoneStateListener);
        mGlobalSettings.registerContentObserverAsync(
                mGlobalSettings.getUriFor(Settings.Global.AIRPLANE_MODE_ON), true,
                mAirplaneModeObserver, this::onAirplaneModeChanged);
        mGlobalSettings.registerContentObserverAsync(
                mGlobalSettings.getUriFor(Settings.Global.GLOBAL_ACTIONS_TIMEOUT_MILLIS), true,
                mGlobalActionsTimeoutObserver);

        mHasVibrator = vibrator.hasVibrator();

        mShowSilentToggle = SHOW_SILENT_TOGGLE && !resources.getBoolean(
                R.bool.config_useFixedVolume);
        if (mShowSilentToggle) {
            mRingerModeTracker.getRingerMode().observe(this, ringer ->
                    mHandler.sendEmptyMessage(MESSAGE_REFRESH)
            );
        }

        mEmergencyAffordanceManager = emergencyAffordanceManager;
        mScreenshotHelper = screenshotHelper;

        mConfigurationController.addCallback(this);
    }

    /**
     * Clean up callbacks
     */
    public void destroy() {
        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        mTelephonyListenerManager.removeServiceStateListener(mPhoneStateListener);
        mGlobalSettings.unregisterContentObserverAsync(mAirplaneModeObserver);
        mGlobalSettings.unregisterContentObserverAsync(mGlobalActionsTimeoutObserver);
        mConfigurationController.removeCallback(this);
        if (mShowSilentToggle) {
            mRingerModeTracker.getRingerMode().removeObservers(this);
        }
    }

    /**
     * Show the global actions dialog (creating if necessary) or hide it if it's already showing.
     *
     * @param keyguardShowing     True if keyguard is showing
     * @param isDeviceProvisioned True if device is provisioned
     * @param expandable          The expandable from which we should animate the dialog when
     *                            showing it
     * @param displayId           Display that should show the dialog
     */
    public void showOrHideDialog(boolean keyguardShowing, boolean isDeviceProvisioned,
            @Nullable Expandable expandable, int displayId) {
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = isDeviceProvisioned;
        if (mDelegate != null && mDelegate.isShowing()) {
            // In order to force global actions to hide on the same affordance press, we must
            // register a call to onGlobalActionsShown() first to prevent the default actions
            // menu from showing. This will be followed by a subsequent call to
            // onGlobalActionsHidden() on dismiss()
            mWindowManagerFuncs.onGlobalActionsShown();
            mDelegate.dismiss();
            mDelegate = null;
            if (mDismissLatchForTesting != null) {
                mDismissLatchForTesting.countDown();
                mDismissLatchForTesting = null;
            }
        } else {
            handleShow(expandable, displayId);
        }
    }

    /**
     * Show the global actions dialog (creating if necessary). Will do nothing if already showing
     *
     * @param keyguardShowing     True if keyguard is showing
     * @param isDeviceProvisioned True if device is provisioned
     * @param expandable          The expandable from which we should animate the dialog when
     *                            showing it
     * @param displayId           Display that should show the dialog
     */
    public void showDialog(boolean keyguardShowing, boolean isDeviceProvisioned,
            @Nullable Expandable expandable, int displayId
    ) {
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = isDeviceProvisioned;
        if (mDelegate == null || !mDelegate.isShowing()) {
            handleShow(expandable, displayId);
        }
    }

    /**
     * Dismiss the global actions dialog, if it's currently shown
     */
    public void dismissDialog() {
        mHandler.removeMessages(MESSAGE_DISMISS);
        mHandler.sendEmptyMessage(MESSAGE_DISMISS);
    }

    private void handleShow(@Nullable Expandable expandable, int displayId) {
        SystemUIDialog dialog = createDialog(displayId);
        prepareDialog();

        WindowManager.LayoutParams attrs = dialog.getWindow().getAttributes();
        attrs.setTitle("GlobalActionsDialogLite");
        attrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        if (isVolumeAndPowerBlurEnabled() && mTranslucentPowerMenu) {
            attrs.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            attrs.setBlurBehindRadius(mContext.getResources().getDimensionPixelSize(
                    com.android.systemui.res.R.dimen.global_actions_blur_radius));
        }
        dialog.getWindow().setAttributes(attrs);
        // Don't acquire soft keyboard focus, to avoid destroying state when capturing bugreports
        dialog.getWindow().addFlags(FLAG_ALT_FOCUSABLE_IM);

        mUserTracker.addCallback(mOnUserSwitched, mBackgroundExecutor);
        mDelegate.show(expandable);
        mWindowManagerFuncs.onGlobalActionsShown();

        rescheduleBurnInTimeout(mGlobalActionDialogTimeout);
    }

    private boolean shouldShowAction(Action action) {
        if (mKeyguardShowing && !action.showDuringKeyguard()) {
            return false;
        }
        if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
            return false;
        }
        return action.shouldShow();
    }

    /**
     * Returns the maximum number of power menu items to show based on which GlobalActions
     * layout is being used.
     */
    private int getMaxShownPowerItems() {
        return mResources.getInteger(com.android.systemui.res.R.integer.power_menu_lite_max_columns)
                * mResources.getInteger(
                    com.android.systemui.res.R.integer.power_menu_lite_max_rows);
    }

    /**
     * Add a power menu action item for to either the main or overflow items lists, depending on
     * whether controls are enabled and whether the max number of shown items has been reached.
     */
    private void addActionItem(Action action) {
        if (mItems.size() < getMaxShownPowerItems()) {
            mItems.add(action);
        } else {
            mOverflowItems.add(action);
        }
    }

    @NonNull
    private String[] getDefaultActions() {
        return mResources.getStringArray(R.array.config_globalActionsList);
    }

    private void addIfShouldShowAction(List<Action> actions, Action action) {
        if (shouldShowAction(action)) {
            actions.add(action);
        }
    }

    private boolean shouldShowRestartSubmenu() {
        return PowerMenuUtils.isAdvancedRestartPossible(mContext);
    }

    @VisibleForTesting
    String[] getRestartActions() {
        return mResources.getStringArray(
                org.lineageos.platform.internal.R.array.config_restartActionsList);
    }

    @VisibleForTesting
    void createActionItems() {
        // Simple toggle style if there's no vibrator, otherwise use a tri-state
        if (!mHasVibrator) {
            mSilentModeAction = new SilentModeToggleAction();
        } else {
            mSilentModeAction = new SilentModeTriStateAction(mAudioManager, mHandler);
        }
        mAirplaneModeOn = new AirplaneModeAction();
        onAirplaneModeChanged();
        onGlobalActionsTimeoutChanged();

        mItems.clear();
        mOverflowItems.clear();
        mPowerItems.clear();
        mRestartItems.clear();

        List<GlobalActionType> actionTypes = mInteractor.getPossibleGlobalActions();
        String[] restartActions = getRestartActions();

        ShutDownAction shutdownAction = new ShutDownAction();
        RestartAction restartAction = new RestartAction();
        RestartSystemAction sysAction = new RestartSystemAction();
        RestartRecoveryAction recAction = new RestartRecoveryAction();
        RestartBootloaderAction blAction = new RestartBootloaderAction();
        RestartDownloadAction dlAction = new RestartDownloadAction();
        RestartFastbootAction fbAction = new RestartFastbootAction();
        ArraySet<String> addedRestartKeys = new ArraySet<>();
        List<Action> tempActions = new ArrayList<>();
        CurrentUserProvider currentUser = new CurrentUserProvider();
        final UserInfo currentUserInfo = currentUser.get();

        // Make sure emergency affordance action is first
        boolean handledEmergencyAffordance = false;
        if (mEmergencyAffordanceManager.needsEmergencyAffordance()) {
            addIfShouldShowAction(tempActions, new EmergencyAffordanceAction());
            handledEmergencyAffordance = true;
        }

        for (String actionKey : restartActions) {
            if (!addedRestartKeys.add(actionKey)) {
                continue;
            }
            if (RESTART_ACTION_KEY_RESTART.equals(actionKey)) {
                addIfShouldShowAction(mRestartItems, sysAction);
            } else if (RESTART_ACTION_KEY_RESTART_RECOVERY.equals(actionKey)) {
                addIfShouldShowAction(mRestartItems, recAction);
            } else if (RESTART_ACTION_KEY_RESTART_BOOTLOADER.equals(actionKey)) {
                addIfShouldShowAction(mRestartItems, blAction);
            } else if (RESTART_ACTION_KEY_RESTART_DOWNLOAD.equals(actionKey)) {
                addIfShouldShowAction(mRestartItems, dlAction);
            } else if (RESTART_ACTION_KEY_RESTART_FASTBOOT.equals(actionKey)) {
                addIfShouldShowAction(mRestartItems, fbAction);
            }
        }

        for (int i = 0; i < actionTypes.size(); i++) {
            GlobalActionType actionType = actionTypes.get(i);
            switch (actionType) {
                case POWER:
                    addIfShouldShowAction(tempActions, shutdownAction);
                    break;
                case AIRPLANE:
                    addIfShouldShowAction(tempActions, mAirplaneModeOn);
                    break;
                case BUGREPORT:
                    if (shouldDisplayBugReport(currentUserInfo)) {
                        addIfShouldShowAction(tempActions, new BugReportAction());
                    }
                    break;
                case FEEDBACK:
                    if (Flags.globalActionsFeedbackAction() && (mFirstFeedbackReceiver != null)) {
                        addIfShouldShowAction(
                                tempActions, new SendFeedbackAction(mFirstFeedbackReceiver));
                    }
                    break;
                case SILENT:
                    if (mShowSilentToggle) {
                        addIfShouldShowAction(tempActions, mSilentModeAction);
                    }
                    break;
                case USERS:
                    if (SystemProperties.getBoolean("fw.power_user_switcher", false)) {
                        addUserActions(tempActions, currentUserInfo);
                    }
                    break;
                case SETTINGS:
                    addIfShouldShowAction(tempActions, getSettingsAction());
                    break;
                case LOCKDOWN:
                    if (shouldDisplayLockdown(currentUserInfo)) {
                        addIfShouldShowAction(tempActions, new LockDownAction());
                    }
                    break;
                case LOCK:
                    addIfShouldShowAction(tempActions, new LockAction());
                    break;
                case VOICEASSIST:
                    addIfShouldShowAction(tempActions, getVoiceAssistAction());
                    break;
                case ASSIST:
                    addIfShouldShowAction(tempActions, getAssistAction());
                    break;
                case RESTART:
                    addIfShouldShowAction(tempActions, restartAction);
                    break;
                case SCREENSHOT:
                    addIfShouldShowAction(tempActions, new ScreenshotAction());
                    break;
                case LOGOUT:
                    if (mLogoutInteractor.isLogoutEnabled().getValue()) {
                        addIfShouldShowAction(tempActions, new LogoutAction());
                    }
                    break;
                case SYSTEM_UPDATE:
                    addIfShouldShowAction(tempActions, new SystemUpdateAction());
                    break;
                case STANDBY:
                    addIfShouldShowAction(tempActions, new StandbyAction());
                    break;
                case EMERGENCY:
                    // Only add the standard EmergencyDialerAction if the
                    // EmergencyAffordanceAction was NOT already handled.
                    if (!handledEmergencyAffordance) {
                        if (shouldDisplayEmergency()) {
                            addIfShouldShowAction(tempActions, new EmergencyDialerAction());
                        }
                    }
                    break;
                default:
                    Log.e(TAG, "Unknown global action type: " + actionType.getConfigKey());
                    break;
            }
        }

        // replace power and restart with a single power options action, if needed
        if (tempActions.contains(shutdownAction) && tempActions.contains(restartAction)
                && tempActions.size() > getMaxShownPowerItems()) {
            // transfer shutdown and restart to their own list of power actions
            int powerOptionsIndex = Math.min(tempActions.indexOf(restartAction),
                    tempActions.indexOf(shutdownAction));
            tempActions.remove(shutdownAction);
            tempActions.remove(restartAction);
            mPowerItems.add(shutdownAction);
            mPowerItems.add(restartAction);

            // add the PowerOptionsAction after Emergency, if present
            tempActions.add(powerOptionsIndex, new PowerOptionsAction());
        }

        for (int i = 0; i < tempActions.size(); i++) {
            addActionItem(tempActions.get(i));
        }
    }

    private void onRefresh() {
        // re-allocate actions between main and overflow lists
        this.createActionItems();
    }

    private void initDialogItems() {
        createActionItems();
        mAdapter = new MyAdapter();
        mOverflowAdapter = new MyOverflowAdapter();
        mPowerAdapter = new MyPowerOptionsAdapter();
        mRestartAdapter = new MyRestartOptionsAdapter();
    }


    /**
     * Create the global actions dialog delegate.
     *
     * @return A new dialog delegate.
     */
    @VisibleForTesting
    ActionsDialogLiteDelegate createDialogDelegate() {
        createDialog(mContext.getDisplayId());
        return mDelegate;
    }

    private Context getContextForDisplay(int displayId) {
        try {
            DisplayWindowProperties properties = mDisplayWindowPropertiesRepositoryLazy.get().get(
                    displayId,
                    DIALOG_WINDOW_TYPE);
            return properties.getContext();
        } catch (Exception e) {
            Log.e(TAG, "Couldn't get context for displayId=" + displayId);
            return mContext;
        }
    }

    /**
     * Create the global actions dialog with a specific context.
     *
     * @return A new dialog.
     */
    SystemUIDialog createDialog(int displayId) {
        final Context context = getContextForDisplay(displayId);
        initDialogItems();

        mDelegate = mDelegateFactory.create(
                context,
                mAdapter,
                mOverflowAdapter,
                mPowerAdapter,
                mRestartAdapter,
                mStatusBarWindowControllerStore.forDisplay(context.getDisplayId()),
                mKeyguardShowing,
                this::onRefresh,
                () -> rescheduleBurnInTimeout(mGlobalActionDialogTimeout)
        );
        SystemUIDialog dialog = mDelegate.createDialog();

        dialog.setOnDismissListener(this);
        dialog.setOnShowListener(this);

        return dialog;
    }

    void rescheduleBurnInTimeout(int timeout) {
        if (timeout > 0) {
            mHandler.removeMessages(MESSAGE_TIMEOUT_DISMISS);
            mHandler.sendEmptyMessageDelayed(MESSAGE_TIMEOUT_DISMISS, timeout);
        }
    }

    private boolean shouldDisplayLockdown(UserInfo user) {
        if (user == null) {
            return false;
        }

        int userId = user.id;

        // Lockdown is meaningless without a place to go.
        if (!mKeyguardStateController.isMethodSecure()) {
            return false;
        }

        // Only show the lockdown button if the device isn't locked down (for whatever reason).
        int state = mLockPatternUtils.getStrongAuthForUser(userId);
        return (state == STRONG_AUTH_NOT_REQUIRED
                || state == SOME_AUTH_REQUIRED_AFTER_USER_REQUEST);
    }

    private boolean shouldDisplayEmergency() {
        // Emergency calling requires a telephony radio with voice calling capabilities.
        return mHasTelephonyCalling;
    }

    private boolean shouldDisplayBugReport(@Nullable UserInfo user) {
        return user != null && user.isAdmin()
                && mSecureSettings.getIntForUser(Settings.Secure.BUGREPORT_IN_POWER_MENU, 0,
                user.id) != 0;
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        if (mDelegate != null && mDelegate.isShowing()
                && (newConfig.smallestScreenWidthDp != mSmallestScreenWidthDp
                || newConfig.orientation != mOrientation)) {
            mSmallestScreenWidthDp = newConfig.smallestScreenWidthDp;
            mOrientation = newConfig.orientation;
            mDelegate.refreshDialog();
        }
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.Callbacks#dismissGlobalActionsMenu()}, which is
     * called when the quick access wallet requests dismissal.
     */
    @Override
    public void dismissGlobalActionsMenu() {
        dismissDialog();
    }

    private boolean isTv() {
        return mIsTv;
    }

    private final class PowerOptionsAction extends SinglePressAction {
        private PowerOptionsAction() {
            super(com.android.systemui.res.R.drawable.ic_settings_power,
                    R.string.global_action_power_options);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            if (mDelegate != null) {
                mDelegate.showPowerOptionsMenu();
            }
        }
    }

    @VisibleForTesting
    final class ShutDownAction extends SinglePressAction implements LongPressAction {
        ShutDownAction() {
            super(com.android.systemui.res.R.drawable.ic_global_actions_power_off,
                    R.string.global_action_power_off);
        }

        @Override
        public boolean onLongPress() {
            // don't actually trigger the reboot if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return false;
            }
            mUiEventLogger.log(GlobalActionsEvent.GA_SHUTDOWN_LONG_PRESS);
            if (!mUserManager.hasUserRestrictionForUser(UserManager.DISALLOW_SAFE_BOOT,
                    mUserTracker.getUserHandle())) {
                mWindowManagerFuncs.reboot(true, null);
                return true;
            }
            return false;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            // don't actually trigger the shutdown if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return;
            }
            mUiEventLogger.log(GlobalActionsEvent.GA_SHUTDOWN_PRESS);
            // shutdown by making sure radio and power are handled accordingly.
            mWindowManagerFuncs.shutdown();
        }
    }

    @VisibleForTesting
    abstract class EmergencyAction extends SinglePressAction {
        EmergencyAction(int iconResId, int messageResId) {
            super(iconResId, messageResId);
        }

        @Override
        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = super.create(context, convertView, parent, inflater);
            int textColor = getEmergencyTextColor(context);
            int iconColor = getEmergencyIconColor(context);
            int backgroundColor = getEmergencyBackgroundColor(context);
            TextView messageView = v.findViewById(R.id.message);
            messageView.setTextColor(textColor);
            messageView.setSelected(true); // necessary for marquee to work
            ImageView icon = v.findViewById(R.id.icon);
            icon.getDrawable().setTint(iconColor);
            icon.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
            v.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
            return v;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }
    }

    private int getEmergencyTextColor(Context context) {
        return context.getResources().getColor(R.color.materialColorOnSurface);
    }

    private int getEmergencyIconColor(Context context) {
        return context.getResources()
                .getColor(com.android.systemui.res.R.color.global_actions_lite_emergency_icon);

    }

    private int getEmergencyBackgroundColor(Context context) {
        return context.getResources().getColor(
                com.android.systemui.res.R.color.global_actions_lite_emergency_background
        );
    }

    private class EmergencyAffordanceAction extends EmergencyAction {
        EmergencyAffordanceAction() {
            super(R.drawable.emergency_icon,
                    R.string.global_action_emergency);
        }

        @Override
        public void onPress() {
            mEmergencyAffordanceManager.performEmergencyCall();
        }
    }

    @VisibleForTesting
    class EmergencyDialerAction extends EmergencyAction {
        EmergencyDialerAction() {
            super(com.android.systemui.res.R.drawable.ic_global_actions_emergency,
                    R.string.global_action_emergency);
        }

        @Override
        public void onPress() {
            mMetricsLogger.action(MetricsEvent.ACTION_EMERGENCY_DIALER_FROM_POWER_MENU);
            mUiEventLogger.log(GlobalActionsEvent.GA_EMERGENCY_DIALER_PRESS);
            if (mTelecomManager != null) {
                // Close shade so user sees the activity
                mShadeController.cancelExpansionAndCollapseShade();
                Intent intent = mTelecomManager.createLaunchEmergencyDialerIntent(
                        null /* number */);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra(EmergencyDialerConstants.EXTRA_ENTRY_TYPE,
                        EmergencyDialerConstants.ENTRY_TYPE_POWER_MENU);
                mContext.startActivityAsUser(intent, mUserTracker.getUserHandle());
            }
        }
    }

    @VisibleForTesting
    final class RestartAction extends SinglePressAction implements LongPressAction {
        RestartAction() {
            super(com.android.systemui.res.R.drawable.ic_global_actions_restart,
                    shouldShowRestartSubmenu()
                            ? com.android.systemui.res.R.string.global_action_restart_more
                            : R.string.global_action_restart);
        }

        @Override
        public boolean onLongPress() {
            // don't actually trigger the reboot if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return false;
            }
            mUiEventLogger.log(GlobalActionsEvent.GA_REBOOT_LONG_PRESS);
            if (!mUserManager.hasUserRestrictionForUser(UserManager.DISALLOW_SAFE_BOOT,
                    mUserTracker.getUserHandle())) {
                mWindowManagerFuncs.reboot(true, null);
                return true;
            }
            return false;
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }

        @Override
        public void onPress() {
            // don't actually trigger the reboot if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return;
            }
            mUiEventLogger.log(GlobalActionsEvent.GA_REBOOT_PRESS);
            if (mDelegate != null && shouldShowRestartSubmenu()) {
                mDelegate.showRestartOptionsMenu();
            } else {
                mWindowManagerFuncs.reboot(false, null);
            }
        }
    }

    private final class RestartSystemAction extends SinglePressAction implements LongPressAction {
        RestartSystemAction() {
            super(R.drawable.ic_restart,
                    com.android.systemui.res.R.string.global_action_restart_system);
        }

        @Override
        public boolean onLongPress() {
            if (!mUserManager.hasUserRestrictionForUser(UserManager.DISALLOW_SAFE_BOOT,
                    mUserTracker.getUserHandle())) {
                mWindowManagerFuncs.reboot(true, null);
                return true;
            }
            return false;
        }

        @Override public boolean showDuringKeyguard() { return true; }
        @Override public boolean showBeforeProvisioning() { return true; }
        @Override public void onPress() { mWindowManagerFuncs.reboot(false, null); }
    }

    private abstract class RestartReasonAction extends SinglePressAction {
        RestartReasonAction(int icon, int label) { super(icon, label); }
        @Override public boolean showDuringKeyguard() { return true; }
        @Override public boolean showBeforeProvisioning() { return true; }
        abstract String getReason();
        @Override public void onPress() { mWindowManagerFuncs.reboot(false, getReason()); }
    }

    private final class RestartRecoveryAction extends RestartReasonAction {
        RestartRecoveryAction() { super(com.android.systemui.res.R.drawable.ic_lock_restart_recovery,
                com.android.systemui.res.R.string.global_action_restart_recovery); }
        @Override String getReason() { return PowerManager.REBOOT_RECOVERY; }
    }

    private final class RestartBootloaderAction extends RestartReasonAction {
        RestartBootloaderAction() { super(com.android.systemui.res.R.drawable.ic_lock_restart_bootloader,
                com.android.systemui.res.R.string.global_action_restart_bootloader); }
        @Override String getReason() { return PowerManager.REBOOT_BOOTLOADER; }
    }

    private final class RestartFastbootAction extends RestartReasonAction {
        RestartFastbootAction() { super(com.android.systemui.res.R.drawable.ic_lock_restart_fastboot,
                com.android.systemui.res.R.string.global_action_restart_fastboot); }
        @Override String getReason() { return PowerManager.REBOOT_FASTBOOT; }
    }

    private final class RestartDownloadAction extends RestartReasonAction {
        RestartDownloadAction() { super(com.android.systemui.res.R.drawable.ic_lock_restart_bootloader,
                com.android.systemui.res.R.string.global_action_restart_download); }
        @Override String getReason() { return PowerManager.REBOOT_DOWNLOAD; }
    }

    @VisibleForTesting
    class ScreenshotAction extends SinglePressAction {
        ScreenshotAction() {
            super(com.android.systemui.res.R.drawable.ic_global_actions_screenshot,
                    R.string.global_action_screenshot);
        }

        @Override
        public void onPress() {
            // Add a little delay before executing, to give the
            // dialog a chance to go away before it takes a
            // screenshot.
            // TODO: instead, omit global action dialog layer
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mScreenshotHelper.takeScreenshot(SCREENSHOT_GLOBAL_ACTIONS, mHandler, null);
                    mMetricsLogger.action(MetricsEvent.ACTION_SCREENSHOT_POWER_MENU);
                    mUiEventLogger.log(GlobalActionsEvent.GA_SCREENSHOT_PRESS);
                }
            }, mDialogPressDelay);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        @Override
        public boolean shouldShow() {
            // Include screenshot in power menu for legacy nav because it is not accessible
            // through Recents in that mode
            return is2ButtonNavigationEnabled();
        }

        boolean is2ButtonNavigationEnabled() {
            return NAV_BAR_MODE_2BUTTON == mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_navBarInteractionMode);
        }
    }

    @VisibleForTesting
    class BugReportAction extends SinglePressAction implements LongPressAction {

        BugReportAction() {
            super(com.android.systemui.res.R.drawable.ic_global_actions_bugreport,
                    R.string.bugreport_title);
        }

        @Override
        public void onPress() {
            // don't actually trigger the bugreport if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return;
            }
            Trace.instantForTrack(Trace.TRACE_TAG_APP, "bugreport", "BugReportAction#onPress");
            Log.d(TAG, "BugReportAction#onPress");
            // Add a little delay before executing, to give the
            // dialog a chance to go away before it takes a
            // screenshot.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Take an "interactive" bugreport.
                        mMetricsLogger.action(
                                MetricsEvent.ACTION_BUGREPORT_FROM_POWER_MENU_INTERACTIVE);
                        mUiEventLogger.log(GlobalActionsEvent.GA_BUGREPORT_PRESS);
                        if (!mIActivityManager.launchBugReportHandlerApp()) {
                            Log.w(TAG, "Bugreport handler could not be launched");
                            Trace.instantForTrack(Trace.TRACE_TAG_APP, "bugreport",
                                    "BugReportAction#requestingInteractiveBugReport");
                            Log.d(TAG, "BugReportAction#requestingInteractiveBugReport");
                            mIActivityManager.requestInteractiveBugReport();
                        }
                    } catch (RemoteException e) {
                    }
                }
            }, mDialogPressDelay);
        }

        @Override
        public boolean onLongPress() {
            // don't actually trigger the bugreport if we are running stability
            // tests via monkey
            if (ActivityManager.isUserAMonkey()) {
                return false;
            }
            try {
                // Take a "full" bugreport.
                mMetricsLogger.action(MetricsEvent.ACTION_BUGREPORT_FROM_POWER_MENU_FULL);
                mUiEventLogger.log(GlobalActionsEvent.GA_BUGREPORT_LONG_PRESS);
                Trace.instantForTrack(Trace.TRACE_TAG_APP, "bugreport",
                        "BugReportAction#requestingFullBugReport");
                Log.d(TAG, "BugReportAction#requestingFullBugReport");
                mIActivityManager.requestFullBugReport();
            } catch (RemoteException e) {
            }
            return false;
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return Build.isDebuggable() && mSecureSettings.getIntForUser(
                    Settings.Secure.BUGREPORT_IN_POWER_MENU, 0, mUserTracker.getUserId()) != 0
                    && mUserTracker.getUserInfo().isAdmin();
        }
    }

    private final class LogoutAction extends SinglePressAction {
        private LogoutAction() {
            super(com.android.systemui.res.R.drawable.ic_global_actions_logout,
                    R.string.global_action_logout);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        @Override
        public void onPress() {
            // Add a little delay before executing, to give the dialog a chance to go away before
            // switching user
            mHandler.postDelayed(mLogoutInteractor::logOut, mDialogPressDelay);
        }
    }

    // TODO: b/457788378 - Remove VisibleForTesting once the test is fixed.
    @VisibleForTesting
    class SendFeedbackAction extends SinglePressAction {
        private final ComponentName mReceiver;

        SendFeedbackAction(ComponentName receiver) {
            super(com.android.systemui.res.R.drawable.ic_send_feedback,
                    R.string.global_action_feedback);
            mReceiver = receiver;
        }

        @Override
        public void onPress() {
            mUiEventLogger.log(GlobalActionsEvent.GA_FEEDBACK_PRESS);
            final Intent intent =
                    new Intent(FEEDBACK_INTENT)
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                            .setComponent(mReceiver);
            mBroadcastSender.sendBroadcastAsUser(intent, mUserTracker.getUserHandle());
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }
    }

    @VisibleForTesting
    final class SystemUpdateAction extends SinglePressAction {

        SystemUpdateAction() {
            super(com.android.settingslib.R.drawable.ic_system_update,
                    com.android.settingslib.R.string.system_update_settings_list_item_title);
        }

        @Override
        public void onPress() {
            mUiEventLogger.log(GlobalActionsEvent.GA_SYSTEM_UPDATE_PRESS);
            launchSystemUpdate();
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        private void launchSystemUpdate() {
            Intent intent = new Intent(Settings.ACTION_SYSTEM_UPDATE_SETTINGS);
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            // postStartActivityDismissingKeyguard is used for showing keyguard
            // input/pin/password screen if lockscreen is secured, before sending the intent.
            mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
        }
    }

    @VisibleForTesting
    class StandbyAction extends SinglePressAction {
        StandbyAction() {
            super(R.drawable.ic_standby, R.string.global_action_standby);
        }

        @Override
        public void onPress() {
            // Add a little delay before executing, to give the dialog a chance to go away before
            // going to sleep. Otherwise, we see screen flicker randomly.
            mHandler.postDelayed(() -> {
                mUiEventLogger.log(GlobalActionsEvent.GA_STANDBY_PRESS);
                mBackgroundExecutor.execute(() -> {
                    Log.d(TAG, "going to sleep due to power button press");
                    mPowerManager.goToSleep(SystemClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
                });
            }, mDialogPressDelay);
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return true;
        }
    }

    private Action getSettingsAction() {
        return new SinglePressAction(R.drawable.ic_settings,
                R.string.global_action_settings) {

            @Override
            public void onPress() {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getAssistAction() {
        return new SinglePressAction(R.drawable.ic_action_assist_focused,
                R.string.global_action_assist) {
            @Override
            public void onPress() {
                Intent intent = new Intent(Intent.ACTION_ASSIST);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    private Action getVoiceAssistAction() {
        return new SinglePressAction(R.drawable.ic_voice_search,
                R.string.global_action_voice_assist) {
            @Override
            public void onPress() {
                Intent intent = new Intent(Intent.ACTION_VOICE_ASSIST);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivity(intent);
            }

            @Override
            public boolean showDuringKeyguard() {
                return true;
            }

            @Override
            public boolean showBeforeProvisioning() {
                return true;
            }
        };
    }

    @VisibleForTesting
    class LockDownAction extends SinglePressAction {
        LockDownAction() {
            super(com.android.systemui.res.R.drawable.ic_global_actions_lockdown,
                    R.string.global_action_lockdown);
        }

        @Override
        public void onPress() {
            mLockPatternUtils.requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN,
                    UserHandle.USER_ALL);
            mUiEventLogger.log(GlobalActionsEvent.GA_LOCKDOWN_PRESS);
            try {
                mIWindowManager.lockNow(null);
                // Lock profiles (if any) on the background thread.
                mBackgroundExecutor.execute(GlobalActionsDialogLite.this::lockProfiles);
            } catch (RemoteException e) {
                Log.e(TAG, "Error while trying to lock device.", e);
            }
        }

        @Override
        public boolean showDuringKeyguard() {
            return true;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    @VisibleForTesting
    class LockAction extends SinglePressAction {
        LockAction() {
            super(com.android.systemui.res.R.drawable.ic_global_actions_lockdown,
                    R.string.global_action_unrestricted_lock);
        }

        @Override
        public void onPress() {
            mUiEventLogger.log(GlobalActionsEvent.GA_LOCK_PRESS);
            try {
                mIWindowManager.lockNow(null);
                // Lock profiles (if any) on the background thread.
                mBackgroundExecutor.execute(GlobalActionsDialogLite.this::lockProfiles);
            } catch (RemoteException e) {
                Log.e(TAG, "Error while trying to lock device.", e);
            }
        }

        @Override
        public boolean showDuringKeyguard() {
            return false;
        }

        @Override
        public boolean showBeforeProvisioning() {
            return false;
        }

        @Override
        public boolean shouldShow() {
            // Only show the lock button when the device can show a lock screen and the device is
            // unlocked.
            return mKeyguardStateController.isMethodSecure()
                    && mKeyguardStateController.isUnlocked();
        }
    }


    private void lockProfiles() {
        final int currentUserId = mUserTracker.getUserId();
        final int[] profileIds = mUserManager.getEnabledProfileIds(currentUserId);
        for (final int id : profileIds) {
            if (id != currentUserId) {
                mTrustManager.setDeviceLockedForUser(id, true);
            }
        }
    }

    /**
     * Non-thread-safe current user provider that caches the result - helpful when a method needs
     * to fetch it an indeterminate number of times.
     */
    private class CurrentUserProvider {
        private UserInfo mUserInfo = null;
        private boolean mFetched = false;

        @Nullable
        UserInfo get() {
            if (!mFetched) {
                mFetched = true;
                mUserInfo = mUserTracker.getUserInfo();
            }
            return mUserInfo;
        }
    }

    private void addUserActions(List<Action> actions, UserInfo currentUser) {
        if (mUserManager.isUserSwitcherEnabled()) {
            List<UserInfo> users = mUserManager.getUsers();
            for (final UserInfo user : users) {
                if (user.isUiSwitchableHumanUser()) {
                    boolean isCurrentUser = currentUser == null
                            ? user.id == 0 : (currentUser.id == user.id);
                    Drawable icon = user.iconPath != null ? Drawable.createFromPath(user.iconPath)
                            : null;
                    SinglePressAction switchToUser = new SinglePressAction(
                            R.drawable.ic_menu_cc, icon,
                            (user.name != null ? user.name : "Primary")
                                    + (isCurrentUser ? " \u2714" : "")) {
                        public void onPress() {
                            try {
                                mIActivityManager.switchUser(user.id);
                            } catch (RemoteException re) {
                                Log.e(TAG, "Couldn't switch user " + re);
                            }
                        }

                        public boolean showDuringKeyguard() {
                            return true;
                        }

                        public boolean showBeforeProvisioning() {
                            return false;
                        }
                    };
                    addIfShouldShowAction(actions, switchToUser);
                }
            }
        }
    }

    private void prepareDialog() {
        refreshSilentMode();
        mAirplaneModeOn.updateState(mAirplaneState);
        mAdapter.notifyDataSetChanged();
        mLifecycle.setCurrentState(Lifecycle.State.RESUMED);
    }

    private void refreshSilentMode() {
        if (!mHasVibrator) {
            Integer value = mRingerModeTracker.getRingerMode().getValue();
            final boolean silentModeOn = value != null && value != AudioManager.RINGER_MODE_NORMAL;
            ((ToggleAction) mSilentModeAction).updateState(
                    silentModeOn ? ToggleState.On : ToggleState.Off);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDismiss(DialogInterface dialog) {
        if (mDelegate != null && mDelegate.mCurrentDialog == dialog) {
            mDelegate = null;
        }
        if (mDismissLatchForTesting != null) {
            mDismissLatchForTesting.countDown();
            mDismissLatchForTesting = null;
        }
        mUiEventLogger.log(GlobalActionsEvent.GA_POWER_MENU_CLOSE);
        mWindowManagerFuncs.onGlobalActionsHidden();
        mLifecycle.setCurrentState(Lifecycle.State.CREATED);
        mInteractor.onDismissed();
        mUserTracker.removeCallback(mOnUserSwitched);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onShow(DialogInterface dialog) {
        mMetricsLogger.visible(MetricsEvent.POWER_MENU);
        mUiEventLogger.log(GlobalActionsEvent.GA_POWER_MENU_OPEN);
        mInteractor.onShown();
    }

    /**
     * The adapter used for power menu items shown in the global actions dialog.
     */
    public class MyAdapter extends MultiListAdapter {
        private boolean mShowingRestartOptions = false;

        public boolean isShowingRestartOptions() {
            return mShowingRestartOptions;
        }

        public void setShowingRestartOptions(boolean show) {
            mShowingRestartOptions = show;
        }

        private int countItems(boolean separated) {
            int count = 0;
            List<Action> items = mShowingRestartOptions ? mRestartItems : mItems;
            for (int i = 0; i < items.size(); i++) {
                final Action action = items.get(i);

                if (action.shouldBeSeparated() == separated) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int countSeparatedItems() {
            return countItems(true);
        }

        @Override
        public int countListItems() {
            return countItems(false);
        }

        @Override
        public int getCount() {
            return countSeparatedItems() + countListItems();
        }

        @Override
        public boolean isEnabled(int position) {
            return getItem(position).isEnabled();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public Action getItem(int position) {
            int filteredPos = 0;
            List<Action> items = mShowingRestartOptions ? mRestartItems : mItems;
            for (int i = 0; i < items.size(); i++) {
                final Action action = items.get(i);
                if (!shouldShowAction(action)) {
                    continue;
                }
                if (filteredPos == position) {
                    return action;
                }
                filteredPos++;
            }

            throw new IllegalArgumentException("position " + position
                    + " out of range of showable actions"
                    + ", filtered count=" + getCount()
                    + ", keyguardShowing=" + mKeyguardShowing
                    + ", provisioned=" + mDeviceProvisioned);
        }

        /**
         * Get the row ID for an item
         *
         * @param position The position of the item within the adapter's data set
         */
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            Context context = parent.getContext();
            View view = action.create(context, convertView, parent, LayoutInflater.from(context));

            if (Flags.enableInsetFocusRings()
                    && mResources.getBoolean(
                            com.android.internal.R.bool.config_enableInsetFocusRingsInSuw)) {
                Drawable focusIndicator =
                        context.getDrawable(
                                com.android.systemui.res.R.drawable
                                        .global_focus_indicator_rounded_rectangle);
                view.setOnFocusChangeListener(
                        (v, hasFocus) -> {
                            View icon = v.findViewById(com.android.internal.R.id.icon);
                            if (icon != null) {
                                icon.setForeground(hasFocus ? focusIndicator.mutate() : null);
                            }
                        });
            }

            // Add AccessibilityDelegate to set the role to Button
            view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(
                        View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });

            view.setOnClickListener(v -> onClickItem(position));
            if (action instanceof LongPressAction) {
                view.setOnLongClickListener(v -> onLongClickItem(position));
            }
            return view;
        }

        @Override
        public boolean onLongClickItem(int position) {
            final Action action = mAdapter.getItem(position);
            if (action instanceof LongPressAction) {
                if (mDelegate != null) {
                    // Usually clicking an item shuts down the phone, locks, or starts an activity.
                    // We don't want to animate back into the power button when that happens, so we
                    // disable the dialog animation before dismissing.
                    mDelegate.dismissWithoutAnimation();
                } else {
                    Log.w(TAG, "Action long-clicked while mDialog is null.");
                }
                return ((LongPressAction) action).onLongPress();
            }
            return false;
        }

        @Override
        public void onClickItem(int position) {
            Action item = mAdapter.getItem(position);
            if (!(item instanceof SilentModeTriStateAction)) {
                if (mDelegate != null) {
                    // don't dismiss the dialog if we're opening the power options menu
                    if (!(item instanceof PowerOptionsAction)
                            && !(item instanceof RestartAction && shouldShowRestartSubmenu())) {
                        // Usually clicking an item shuts down the phone, locks, or starts an
                        // activity. We don't want to animate back into the power button when that
                        // happens, so we disable the dialog animation before dismissing.
                        mDelegate.dismissWithoutAnimation();
                    }
                } else {
                    Log.w(TAG, "Action clicked while mDialog is null.");
                }
                item.onPress();
            }
        }

        @Override
        public boolean shouldBeSeparated(int position) {
            return getItem(position).shouldBeSeparated();
        }
    }

    /**
     * The adapter used for items in the overflow menu.
     */
    public class MyPowerOptionsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mPowerItems.size();
        }

        @Override
        public Action getItem(int position) {
            return mPowerItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            if (action == null) {
                Log.w(TAG, "No power options action found at position: " + position);
                return null;
            }
            int viewLayoutResource = com.android.systemui.res.R.layout.global_actions_power_item;
            View view = convertView != null ? convertView
                    : LayoutInflater.from(mContext).inflate(viewLayoutResource, parent, false);
            view.setOnClickListener(v -> onClickItem(position));
            if (action instanceof LongPressAction) {
                view.setOnLongClickListener(v -> onLongClickItem(position));
            }
            ImageView icon = view.findViewById(R.id.icon);
            TextView messageView = view.findViewById(R.id.message);
            messageView.setSelected(true); // necessary for marquee to work
            messageView.setTypeface(
                    Typeface.create(FontStyles.GSF_LABEL_LARGE_EMPHASIZED, Typeface.NORMAL));

            icon.setImageDrawable(action.getIcon(mContext));
            icon.setScaleType(ScaleType.CENTER_CROP);

            if (action.getMessage() != null) {
                messageView.setText(action.getMessage());
            } else {
                messageView.setText(action.getMessageResId());
            }
            return view;
        }

        private boolean onLongClickItem(int position) {
            final Action action = getItem(position);
            if (action instanceof LongPressAction) {
                if (mDelegate != null) {
                    // Usually clicking an item shuts down the phone, locks, or starts an activity.
                    // We don't want to animate back into the power button when that happens, so we
                    // disable the dialog animation before dismissing.
                    mDelegate.dismissWithoutAnimation();
                } else {
                    Log.w(TAG, "Action long-clicked while mDialog is null.");
                }
                return ((LongPressAction) action).onLongPress();
            }
            return false;
        }

        private void onClickItem(int position) {
            Action item = getItem(position);
            if (!(item instanceof SilentModeTriStateAction)) {
                if (mDelegate != null) {
                    // Usually clicking an item shuts down the phone, locks, or starts an activity.
                    // We don't want to animate back into the power button when that happens, so we
                    // disable the dialog animation before dismissing.
                    mDelegate.dismissWithoutAnimation();
                } else {
                    Log.w(TAG, "Action clicked while mDialog is null.");
                }
                item.onPress();
            }
        }
    }

    public class MyRestartOptionsAdapter extends MyPowerOptionsAdapter {
        @Override
        public int getCount() {
            return mRestartItems.size();
        }

        @Override
        public Action getItem(int position) {
            return mRestartItems.get(position);
        }
    }

    /**
     * The adapter used for items in the power options menu, triggered by the PowerOptionsAction.
     */
    public class MyOverflowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mOverflowItems.size();
        }

        @Override
        public Action getItem(int position) {
            return mOverflowItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            if (action == null) {
                Log.w(TAG, "No overflow action found at position: " + position);
                return null;
            }
            int viewLayoutResource = com.android.systemui.res.R.layout.controls_more_item;
            View view = convertView != null ? convertView
                    : LayoutInflater.from(mContext).inflate(viewLayoutResource, parent, false);
            TextView textView = (TextView) view;
            if (action.getMessageResId() != 0) {
                textView.setText(action.getMessageResId());
            } else {
                textView.setText(action.getMessage());
            }
            textView.setTypeface(
                    Typeface.create(FontStyles.GSF_LABEL_LARGE_EMPHASIZED, Typeface.NORMAL));

            return textView;
        }

        boolean onLongClickItem(int position) {
            final Action action = getItem(position);
            if (action instanceof LongPressAction) {
                if (mDelegate != null) {
                    // Usually clicking an item shuts down the phone, locks, or starts an activity.
                    // We don't want to animate back into the power button when that happens, so we
                    // disable the dialog animation before dismissing.
                    mDelegate.dismissWithoutAnimation();
                } else {
                    Log.w(TAG, "Action long-clicked while mDialog is null.");
                }
                return ((LongPressAction) action).onLongPress();
            }
            return false;
        }

        void onClickItem(int position) {
            Action item = getItem(position);
            if (!(item instanceof SilentModeTriStateAction)) {
                if (mDelegate != null) {
                    // Usually clicking an item shuts down the phone, locks, or starts an activity.
                    // We don't want to animate back into the power button when that happens, so we
                    // disable the dialog animation before dismissing.
                    mDelegate.dismissWithoutAnimation();
                } else {
                    Log.w(TAG, "Action clicked while mDialog is null.");
                }
                item.onPress();
            }
        }
    }

    // note: the scheme below made more sense when we were planning on having
    // 8 different things in the global actions dialog.  seems overkill with
    // only 3 items now, but may as well keep this flexible approach so it will
    // be easy should someone decide at the last minute to include something
    // else, such as 'enable wifi', or 'enable bluetooth'

    /**
     * What each item in the global actions dialog must be able to support.
     */
    public interface Action {
        /**
         * @return Text that will be announced when dialog is created.  null for none.
         */
        CharSequence getLabelForAccessibility(Context context);

        /**
         * Create the item's view
         */
        View create(Context context, View convertView, ViewGroup parent, LayoutInflater inflater);

        /**
         * Handle a regular press
         */
        void onPress();

        /**
         * @return whether this action should appear in the dialog when the keyguard is showing.
         */
        boolean showDuringKeyguard();

        /**
         * @return whether this action should appear in the dialog before the
         * device is provisioned.f
         */
        boolean showBeforeProvisioning();

        /**
         * @return whether this action is enabled
         */
        boolean isEnabled();

        /**
         * @return whether this action should be in a separate section
         */
        default boolean shouldBeSeparated() {
            return false;
        }

        /**
         * Return the id of the message associated with this action, or 0 if it doesn't have one.
         */
        int getMessageResId();

        /**
         * Return the icon drawable for this action.
         */
        Drawable getIcon(Context context);

        /**
         * Return the message associated with this action, or null if it doesn't have one.
         */
        CharSequence getMessage();

        /**
         * @return whether the action should be visible
         */
        default boolean shouldShow() {
            return true;
        }
    }

    /**
     * An action that also supports long press.
     */
    private interface LongPressAction extends Action {
        boolean onLongPress();
    }

    /**
     * A single press action maintains no state, just responds to a press and takes an action.
     */

    @VisibleForTesting
    abstract class SinglePressAction implements Action {
        private final int mIconResId;
        private final Drawable mIcon;
        private final int mMessageResId;
        private final CharSequence mMessage;
        @VisibleForTesting
        ImageView mIconView;

        SinglePressAction(int iconResId, int messageResId) {
            mIconResId = iconResId;
            mMessageResId = messageResId;
            mMessage = null;
            mIcon = null;
            mIconView = null;
        }

        SinglePressAction(int iconResId, Drawable icon, CharSequence message) {
            mIconResId = iconResId;
            mMessageResId = 0;
            mMessage = message;
            mIcon = icon;
        }

        public boolean isEnabled() {
            return true;
        }

        public String getStatus() {
            return null;
        }

        public abstract void onPress();

        public CharSequence getLabelForAccessibility(Context context) {
            if (mMessage != null) {
                return mMessage;
            } else {
                return context.getString(mMessageResId);
            }
        }

        public int getMessageResId() {
            return mMessageResId;
        }

        public CharSequence getMessage() {
            return mMessage;
        }

        @Override
        public Drawable getIcon(Context context) {
            if (mIcon != null) {
                return mIcon;
            } else {
                return context.getDrawable(mIconResId);
            }
        }

        public View create(
                Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
            View v = inflater.inflate(getGridItemLayoutResource(), parent, false /* attach */);
            // ConstraintLayout flow needs an ID to reference
            v.setId(View.generateViewId());

            mIconView = v.findViewById(R.id.icon);
            TextView messageView = v.findViewById(R.id.message);
            messageView.setTypeface(
                    Typeface.create(FontStyles.GSF_LABEL_LARGE_EMPHASIZED, Typeface.NORMAL));
            messageView.setSelected(true); // necessary for marquee to work
            mIconView.setImageDrawable(getIcon(context));
            mIconView.setScaleType(ScaleType.CENTER_CROP);
            if (isTv()) {
                mIconView.setFocusable(true);
                mIconView.setClickable(true);
                mIconView.setBackground(mContext.getDrawable(
                        com.android.systemui.res.R.drawable.global_actions_lite_button_background));
                mIconView.setOnClickListener(i -> onClick());
                if (mItems.get(0) == this) {
                    mIconView.requestFocus();
                }
            }

            if (mMessage != null) {
                messageView.setText(mMessage);
            } else {
                messageView.setText(mMessageResId);
            }

            return v;
        }

        private void onClick() {
            if (mDelegate != null) {
                // don't dismiss the dialog if we're opening the power options menu
                if (!(this instanceof PowerOptionsAction)) {
                    // Usually clicking an item shuts down the phone, locks, or starts an
                    // activity. We don't want to animate back into the power button when that
                    // happens, so we disable the dialog animation before dismissing.
                    mDelegate.dismissWithoutAnimation();
                }
            } else {
                Log.w(TAG, "Action icon clicked while mDialog is null.");
            }
            onPress();
        }
    }

    private int getGridItemLayoutResource() {
        return com.android.systemui.res.R.layout.global_actions_grid_item_lite;
    }

    private enum ToggleState {
        Off(false),
        TurningOn(true),
        TurningOff(true),
        On(false);

        private final boolean mInTransition;

        ToggleState(boolean intermediate) {
            mInTransition = intermediate;
        }

        public boolean inTransition() {
            return mInTransition;
        }
    }

    /**
     * A toggle action knows whether it is on or off, and displays an icon and status message
     * accordingly.
     */
    private abstract static class ToggleAction implements Action {

        ToggleState mState = ToggleState.Off;

        // prefs
        int mEnabledIconResId;
        int mDisabledIconResId;
        int mMessageResId;
        int mEnabledStatusMessageResId;
        int mDisabledStatusMessageResId;

        /**
         * @param enabledIconResId           The icon for when this action is on.
         * @param disabledIconResId          The icon for when this action is off.
         * @param message                    The general information message, e.g 'Silent Mode'
         * @param enabledStatusMessageResId  The on status message, e.g 'sound disabled'
         * @param disabledStatusMessageResId The off status message, e.g. 'sound enabled'
         */
        ToggleAction(int enabledIconResId,
                int disabledIconResId,
                int message,
                int enabledStatusMessageResId,
                int disabledStatusMessageResId) {
            mEnabledIconResId = enabledIconResId;
            mDisabledIconResId = disabledIconResId;
            mMessageResId = message;
            mEnabledStatusMessageResId = enabledStatusMessageResId;
            mDisabledStatusMessageResId = disabledStatusMessageResId;
        }

        /**
         * Override to make changes to resource IDs just before creating the View.
         */
        void willCreate() {

        }

        @Override
        public CharSequence getLabelForAccessibility(Context context) {
            return context.getString(mMessageResId);
        }

        private boolean isOn() {
            return mState == ToggleState.On || mState == ToggleState.TurningOn;
        }

        @Override
        public CharSequence getMessage() {
            return null;
        }

        @Override
        public int getMessageResId() {
            return isOn() ? mEnabledStatusMessageResId : mDisabledStatusMessageResId;
        }

        private int getIconResId() {
            return isOn() ? mEnabledIconResId : mDisabledIconResId;
        }

        @Override
        public Drawable getIcon(Context context) {
            return context.getDrawable(getIconResId());
        }

        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            willCreate();

            View v = inflater.inflate(com.android.systemui.res.R.layout.global_actions_grid_item_v2,
                    parent, false /* attach */);
            ViewGroup.LayoutParams p = v.getLayoutParams();
            p.width = WRAP_CONTENT;
            v.setLayoutParams(p);

            ImageView icon = v.findViewById(R.id.icon);
            TextView messageView = v.findViewById(R.id.message);

            final boolean enabled = isEnabled();

            if (messageView != null) {
                messageView.setTypeface(
                        Typeface.create(FontStyles.GSF_LABEL_LARGE_EMPHASIZED, Typeface.NORMAL));
                messageView.setText(getMessageResId());
                messageView.setEnabled(enabled);
                messageView.setSelected(true); // necessary for marquee to work
            }

            if (icon != null) {
                icon.setImageDrawable(context.getDrawable(getIconResId()));
                icon.setEnabled(enabled);
            }

            v.setEnabled(enabled);

            return v;
        }

        public final void onPress() {
            if (mState.inTransition()) {
                Log.w(TAG, "shouldn't be able to toggle when in transition");
                return;
            }

            final boolean nowOn = !(mState == ToggleState.On);
            onToggle(nowOn);
            changeStateFromPress(nowOn);
        }

        public boolean isEnabled() {
            return !mState.inTransition();
        }

        /**
         * Implementations may override this if their state can be in on of the intermediate states
         * until some notification is received (e.g airplane mode is 'turning off' until we know the
         * wireless connections are back online
         *
         * @param buttonOn Whether the button was turned on or off
         */
        void changeStateFromPress(boolean buttonOn) {
            mState = buttonOn ? ToggleState.On : ToggleState.Off;
        }

        abstract void onToggle(boolean on);

        public void updateState(ToggleState state) {
            mState = state;
        }
    }

    private class AirplaneModeAction extends ToggleAction {
        AirplaneModeAction() {
            super(
                    R.drawable.ic_lock_airplane_mode,
                    R.drawable.ic_lock_airplane_mode_off,
                    R.string.global_actions_toggle_airplane_mode,
                    R.string.global_actions_airplane_mode_on_status,
                    R.string.global_actions_airplane_mode_off_status);
        }

        void onToggle(boolean on) {
            if (mHasTelephonyCalling && TelephonyProperties.in_ecm_mode().orElse(false)) {
                mIsWaitingForEcmExit = true;
                // Launch ECM exit dialog
                Intent ecmDialogIntent =
                        new Intent(TelephonyManager.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null);
                ecmDialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(ecmDialogIntent);
            } else {
                changeAirplaneModeSystemSetting(on);
            }
        }

        @Override
        void changeStateFromPress(boolean buttonOn) {
            if (!mHasTelephonyCalling) return;

            // In ECM mode airplane state cannot be changed
            if (!TelephonyProperties.in_ecm_mode().orElse(false)) {
                mState = buttonOn ? ToggleState.TurningOn : ToggleState.TurningOff;
                mAirplaneState = mState;
            }
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private class SilentModeToggleAction extends ToggleAction {
        SilentModeToggleAction() {
            super(R.drawable.ic_audio_vol_mute,
                    R.drawable.ic_audio_vol,
                    R.string.global_action_toggle_silent_mode,
                    R.string.global_action_silent_mode_on_status,
                    R.string.global_action_silent_mode_off_status);
        }

        void onToggle(boolean on) {
            if (on) {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            } else {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            }
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }
    }

    private static class SilentModeTriStateAction implements Action, View.OnClickListener {

        private static final int[] ITEM_IDS = {R.id.option1, R.id.option2, R.id.option3};

        private final AudioManager mAudioManager;
        private final Handler mHandler;

        SilentModeTriStateAction(AudioManager audioManager, Handler handler) {
            mAudioManager = audioManager;
            mHandler = handler;
        }

        private int ringerModeToIndex(int ringerMode) {
            // They just happen to coincide
            return ringerMode;
        }

        private int indexToRingerMode(int index) {
            // They just happen to coincide
            return index;
        }

        @Override
        public CharSequence getLabelForAccessibility(Context context) {
            return null;
        }

        @Override
        public int getMessageResId() {
            return 0;
        }

        @Override
        public CharSequence getMessage() {
            return null;
        }

        @Override
        public Drawable getIcon(Context context) {
            return null;
        }


        public View create(Context context, View convertView, ViewGroup parent,
                LayoutInflater inflater) {
            View v = inflater.inflate(R.layout.global_actions_silent_mode, parent, false);

            int selectedIndex = ringerModeToIndex(mAudioManager.getRingerMode());
            for (int i = 0; i < 3; i++) {
                View itemView = v.findViewById(ITEM_IDS[i]);
                itemView.setSelected(selectedIndex == i);
                // Set up click handler
                itemView.setTag(i);
                itemView.setOnClickListener(this);
            }
            return v;
        }

        public void onPress() {
        }

        public boolean showDuringKeyguard() {
            return true;
        }

        public boolean showBeforeProvisioning() {
            return false;
        }

        public boolean isEnabled() {
            return true;
        }

        public void onClick(View v) {
            if (!(v.getTag() instanceof Integer)) return;

            int index = (Integer) v.getTag();
            mAudioManager.setRingerMode(indexToRingerMode(index));
            mHandler.sendEmptyMessageDelayed(MESSAGE_DISMISS, DIALOG_DISMISS_DELAY);
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                String reason = intent.getStringExtra(SYSTEM_DIALOG_REASON_KEY);
                if (!SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS.equals(reason)) {
                    // These broadcasts are usually received when locking the device, swiping up to
                    // home (which collapses the shade), etc. In those cases, we usually don't want
                    // to animate this dialog back into the view, so we disable the exit animations.
                    mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_DISMISS,
                            1 /* dismissWithoutAnimation */, 0 /* unused */, reason));
                }
            } else if (TelephonyManager.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED.equals(action)) {
                // Airplane mode can be changed after ECM exits if airplane toggle button
                // is pressed during ECM mode
                if (!(intent.getBooleanExtra(TelephonyManager.EXTRA_PHONE_IN_ECM_STATE, false))
                        && mIsWaitingForEcmExit) {
                    mIsWaitingForEcmExit = false;
                    changeAirplaneModeSystemSetting(true);
                }
            }
        }
    };

    private final TelephonyCallback.ServiceStateListener mPhoneStateListener =
            new TelephonyCallback.ServiceStateListener() {
                @Override
                public void onServiceStateChanged(@NonNull ServiceState serviceState) {
                    if (!mHasTelephonyCalling) return;
                    if (mAirplaneModeOn == null) {
                        Log.d(TAG, "Service changed before actions created");
                        return;
                    }
                    final boolean inAirplaneMode =
                            serviceState.getState() == ServiceState.STATE_POWER_OFF;
                    mAirplaneState = inAirplaneMode ? ToggleState.On : ToggleState.Off;
                    mAirplaneModeOn.updateState(mAirplaneState);
                    mAdapter.notifyDataSetChanged();
                    mOverflowAdapter.notifyDataSetChanged();
                    mPowerAdapter.notifyDataSetChanged();
                }
            };

    private final ContentObserver mAirplaneModeObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            onAirplaneModeChanged();
        }
    };

    private final ContentObserver mGlobalActionsTimeoutObserver =
            new ContentObserver(mMainHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    onGlobalActionsTimeoutChanged();
                }
            };

    private static final int MESSAGE_DISMISS = 0;
    private static final int MESSAGE_REFRESH = 1;
    private static final int MESSAGE_TIMEOUT_DISMISS = 2;
    private static final int DIALOG_DISMISS_DELAY = 300; // ms
    private static final int DIALOG_PRESS_DELAY = 850; // ms

    @VisibleForTesting
    void setZeroDialogPressDelayForTesting() {
        mDialogPressDelay = 0; // ms
    }

    /**
     * Sets a latch used to wait for the delegate to be dismissed. It will be unset after it is
     * notified.
     *
     * @param latch the latch to set.
     */
    @VisibleForTesting
    void setDismissLatchForTesting(@NonNull CountDownLatch latch) {
        mDismissLatchForTesting = latch;
    }

    private void onAirplaneModeChanged() {
        // Let the service state callbacks handle the state.
        if (mHasTelephonyCalling || mAirplaneModeOn == null) return;

        boolean airplaneModeOn = mGlobalSettings.getInt(
                Settings.Global.AIRPLANE_MODE_ON,
                0) == 1;
        mAirplaneState = airplaneModeOn ? ToggleState.On : ToggleState.Off;
        mAirplaneModeOn.updateState(mAirplaneState);
    }

    private void onGlobalActionsTimeoutChanged() {
        int defaultTimeout = mContext.getResources().getInteger(
                R.integer.config_globalActionsDialogTimeout);
        mGlobalActionDialogTimeout = mGlobalSettings.getInt(
                Settings.Global.GLOBAL_ACTIONS_TIMEOUT_MILLIS,
                defaultTimeout);
    }

    /**
     * Change the airplane mode system setting
     */
    private void changeAirplaneModeSystemSetting(boolean on) {
        mGlobalSettings.putInt(Settings.Global.AIRPLANE_MODE_ON, on ? 1 : 0);
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("state", on);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        if (!mHasTelephonyCalling) {
            mAirplaneState = on ? ToggleState.On : ToggleState.Off;
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @VisibleForTesting
    public static final class ActionsDialogLiteDelegate implements DialogDelegate<SystemUIDialog>,
            ColorExtractor.OnColorsChangedListener {

        @NonNull
        private final Context mContext;
        @NonNull
        private final MyAdapter mAdapter;
        @NonNull
        private final MyOverflowAdapter mOverflowAdapter;
        @NonNull
        private final MyPowerOptionsAdapter mPowerOptionsAdapter;
        @NonNull
        private final MyRestartOptionsAdapter mRestartOptionsAdapter;
        @NonNull
        private final StatusBarWindowController mStatusBarWindowController;
        private final boolean mKeyguardShowing;
        @NonNull
        private final Runnable mOnRefreshCallback;
        @NonNull
        private final Runnable mRescheduleBurnInTimeout;
        @NonNull
        private final SysuiColorExtractor mColorExtractor;
        @NonNull
        private final LightBarController mLightBarController;
        @NonNull
        private final KeyguardStateController mKeyguardStateController;
        @NonNull
        private final TopUiController mTopUiController;
        @NonNull
        private final UiEventLogger mUiEventLogger;
        @NonNull
        private final ShadeController mShadeController;
        @NonNull
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        @NonNull
        private final LockPatternUtils mLockPatternUtils;
        @NonNull
        private final SelectedUserInteractor mSelectedUserInteractor;
        @NonNull
        private final AccessibilityManager mAccessibilityManager;
        @NonNull
        private final DialogTransitionAnimator mDialogTransitionAnimator;
        @NonNull
        private final SystemUIDialog.Factory mSystemUIDialogFactory;
        @NonNull
        private final GestureDetector mGestureDetector;
        @NonNull
        private final DeviceEntryInteractor mDeviceEntryInteractor;

        @VisibleForTesting
        @Nullable
        SystemUIDialog mCurrentDialog;
        private GlobalActionsLayoutLite mGlobalActionsLayout;
        @Nullable
        private ScrimDrawable mBackgroundDrawable;
        @Nullable
        private Dialog mPowerOptionsDialog;
        @Nullable
        private Dialog mRestartOptionsDialog;
        @Nullable
        private ListPopupWindow mOverflowPopup;
        private float mInitialWindowDimAmount;

        private final OnBackInvokedCallback mOnBackInvokedCallback;

        @VisibleForTesting
        final GestureDetector.SimpleOnGestureListener mGestureListener =
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(@NonNull MotionEvent e) {
                        // All gestures begin with this message, so continue listening
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(@NonNull MotionEvent e) {
                        // Close without opening shade
                        mUiEventLogger.log(GlobalActionsEvent.GA_CLOSE_TAP_OUTSIDE);
                        if (mCurrentDialog != null) {
                            mCurrentDialog.cancel();
                        }
                        return false;
                    }

                    @Override
                    public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2,
                            float distanceX,
                            float distanceY) {
                        if (distanceY < 0 && distanceY > distanceX
                                && e1 != null
                                && e1.getY() <= mStatusBarWindowController.getStatusBarHeight()) {
                            // Downwards scroll from top
                            openShadeAndDismiss();
                            return true;
                        }
                        return false;
                    }

                    @Override
                    public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2,
                            float velocityX,
                            float velocityY) {
                        if (velocityY > 0 && Math.abs(velocityY) > Math.abs(velocityX)
                                && e1 != null
                                && e1.getY() <= mStatusBarWindowController.getStatusBarHeight()) {
                            // Downwards fling from top
                            openShadeAndDismiss();
                            return true;
                        }
                        return false;
                    }
                };

        @AssistedInject
        ActionsDialogLiteDelegate(
                @Assisted @NonNull Context context,
                @Assisted @NonNull MyAdapter adapter,
                @Assisted @NonNull MyOverflowAdapter overflowAdapter,
                @Assisted @NonNull MyPowerOptionsAdapter powerAdapter,
                @Assisted @NonNull MyRestartOptionsAdapter restartAdapter,
                @Assisted @NonNull StatusBarWindowController statusBarWindowController,
                @Assisted boolean keyguardShowing,
                @Assisted("onRefreshCallback") @NonNull Runnable onRefreshCallback,
                @Assisted("rescheduleBurnInTimeout") @NonNull Runnable rescheduleBurnInTimeout,
                @NonNull SysuiColorExtractor sysuiColorExtractor,
                @NonNull LightBarController lightBarController,
                @NonNull KeyguardStateController keyguardStateController,
                @NonNull TopUiController topUiController,
                @NonNull UiEventLogger uiEventLogger,
                @NonNull ShadeController shadeController,
                @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
                @NonNull LockPatternUtils lockPatternUtils,
                @NonNull SelectedUserInteractor selectedUserInteractor,
                @NonNull AccessibilityManager accessibilityManager,
                @NonNull DialogTransitionAnimator dialogTransitionAnimator,
                @NonNull SystemUIDialog.Factory systemUIDialogFactory,
                @NonNull DeviceEntryInteractor deviceEntryInteractor) {
            mContext = context;
            mAdapter = adapter;
            mOverflowAdapter = overflowAdapter;
            mPowerOptionsAdapter = powerAdapter;
            mRestartOptionsAdapter = restartAdapter;
            mStatusBarWindowController = statusBarWindowController;
            mKeyguardShowing = keyguardShowing;
            mOnRefreshCallback = onRefreshCallback;
            mRescheduleBurnInTimeout = rescheduleBurnInTimeout;
            mColorExtractor = sysuiColorExtractor;
            mLightBarController = lightBarController;
            mKeyguardStateController = keyguardStateController;
            mTopUiController = topUiController;
            mUiEventLogger = uiEventLogger;
            mShadeController = shadeController;
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mLockPatternUtils = lockPatternUtils;
            mSelectedUserInteractor = selectedUserInteractor;
            mAccessibilityManager = accessibilityManager;
            mDialogTransitionAnimator = dialogTransitionAnimator;
            mSystemUIDialogFactory = systemUIDialogFactory;
            mGestureDetector = new GestureDetector(context, mGestureListener);
            mDeviceEntryInteractor = deviceEntryInteractor;
            mOnBackInvokedCallback = () -> {
                logOnBackInvocation();
                if (mAdapter.isShowingRestartOptions()) {
                    mAdapter.setShowingRestartOptions(false);
                    mGlobalActionsLayout.updateList();
                } else {
                    dismiss();
                }
            };
        }

        @NonNull
        public SystemUIDialog createDialog() {
            // We set dismissOnDeviceLock to false because we have a custom broadcast receiver to
            // dismiss this dialog when the device is locked.
            SystemUIDialog dialog = mSystemUIDialogFactory.create(this, mContext,
                    com.android.systemui.res.R.style.Theme_SystemUI_Dialog_GlobalActionsLite,
                    false /* dismissOnDeviceLock */, true /* shouldAcsdDismissDialog */);
            mCurrentDialog = dialog;
            return dialog;
        }

        @Override
        public void onCreate(@NonNull SystemUIDialog dialog, @Nullable Bundle savedInstanceState) {
            final Window window = dialog.getWindow();
            window.setTitle(dialog.getContext().getString(
                    com.android.systemui.res.R.string.accessibility_quick_settings_power_menu));
            initializeLayout(dialog);
            mInitialWindowDimAmount = window.getAttributes().dimAmount;
        }

        @Override
        public int getWidth(@NonNull SystemUIDialog dialog) {
            return MATCH_PARENT;
        }

        @Override
        public int getHeight(@NonNull SystemUIDialog dialog) {
            return MATCH_PARENT;
        }

        @Override
        public boolean dispatchTouchEvent(@NonNull SystemUIDialog dialog,
                @NonNull MotionEvent motionEvent) {
            mRescheduleBurnInTimeout.run();
            return false;
        }

        @Override
        public boolean onTouchEvent(@NonNull SystemUIDialog dialog, @NonNull MotionEvent event) {
            return mGestureDetector.onTouchEvent(event);
        }

        private void openShadeAndDismiss() {
            mUiEventLogger.log(GlobalActionsEvent.GA_CLOSE_TAP_OUTSIDE);
            if (mKeyguardStateController.isShowing()) {
                // match existing lockscreen behavior to open QS when swiping from status bar
                mShadeController.animateExpandQs();
            } else {
                // otherwise, swiping down should expand notification shade
                mShadeController.animateExpandShade();
            }
            dismiss();
        }

        @NonNull
        private ListPopupWindow createPowerOverflowPopup(@NonNull SystemUIDialog dialog) {
            GlobalActionsPopupMenu popup = new GlobalActionsPopupMenu(
                    new ContextThemeWrapper(
                            dialog.getContext(),
                            com.android.systemui.res.R.style.Control_ListPopupWindow
                    ), false /* isDropDownMode */);
            popup.setOnItemClickListener(
                    (parent, view, position, id) -> mOverflowAdapter.onClickItem(position));
            popup.setOnItemLongClickListener(
                    (parent, view, position, id) -> mOverflowAdapter.onLongClickItem(position));
            View overflowButton = dialog
                    .findViewById(com.android.systemui.res.R.id.global_actions_overflow_button);
            popup.setAnchorView(overflowButton);
            popup.setAdapter(mOverflowAdapter);
            return popup;
        }

        public void showPowerOptionsMenu() {
            mPowerOptionsDialog = GlobalActionsPowerDialog.create(mContext, mPowerOptionsAdapter);
            mPowerOptionsDialog.show();
        }

        public void showRestartOptionsMenu() {
            mAdapter.setShowingRestartOptions(true);
            mGlobalActionsLayout.updateList();
        }

        private void showPowerOverflowMenu(@NonNull SystemUIDialog dialog) {
            mOverflowPopup = createPowerOverflowPopup(dialog);
            mOverflowPopup.show();
        }

        private void initializeLayout(@NonNull SystemUIDialog dialog) {
            dialog.setContentView(com.android.systemui.res.R.layout.global_actions_grid_lite);
            fixNavBarClipping(dialog);

            mGlobalActionsLayout =
                    dialog.findViewById(com.android.systemui.res.R.id.global_actions_view);
            mGlobalActionsLayout.setListViewAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public boolean dispatchPopulateAccessibilityEvent(
                        @NonNull View host, @NonNull AccessibilityEvent event) {
                    // Populate the title here, just as Activity does
                    event.getText().add(dialog.getContext().getString(R.string.global_actions));
                    return true;
                }
            });
            mGlobalActionsLayout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            mGlobalActionsLayout.setRotationListener(this::onRotate);
            mGlobalActionsLayout.setAdapter(mAdapter);
            final WindowRootViewBlurInteractor blurInteractor = dialog.getBlurInteractor();
            collectFlow(mGlobalActionsLayout, blurInteractor.isBlurCurrentlySupported(),
                    mGlobalActionsLayout::setIsBlurSupported);
            mGlobalActionsLayout.setIsBlurSupported(
                    blurInteractor.isBlurCurrentlySupported().getValue());
            ViewGroup container =
                    dialog.findViewById(com.android.systemui.res.R.id.global_actions_container);
            container.setOnTouchListener((v, event) -> {
                mGestureDetector.onTouchEvent(event);
                return v.onTouchEvent(event);
            });

            View overflowButton = dialog.findViewById(
                    com.android.systemui.res.R.id.global_actions_overflow_button);
            if (overflowButton != null) {
                if (mOverflowAdapter.getCount() > 0) {
                    overflowButton.setOnClickListener((view) -> showPowerOverflowMenu(dialog));
                    LinearLayout.LayoutParams params =
                            (LinearLayout.LayoutParams) mGlobalActionsLayout.getLayoutParams();
                    params.setMarginEnd(0);
                    mGlobalActionsLayout.setLayoutParams(params);
                } else {
                    overflowButton.setVisibility(View.GONE);
                    LinearLayout.LayoutParams params =
                            (LinearLayout.LayoutParams) mGlobalActionsLayout.getLayoutParams();
                    params.setMarginEnd(dialog.getContext().getResources().getDimensionPixelSize(
                            com.android.systemui.res.R.dimen.global_actions_side_margin));
                    mGlobalActionsLayout.setLayoutParams(params);
                }
            }

            if (mBackgroundDrawable == null) {
                mBackgroundDrawable = new ScrimDrawable();
            }
            // If user entered from the lock screen and smart lock was enabled, disable it
            int user = mSelectedUserInteractor.getSelectedUserId();
            boolean userHasTrust = mKeyguardUpdateMonitor.getUserHasTrust(user);
            if (mKeyguardShowing && userHasTrust) {
                mLockPatternUtils.requireCredentialEntry(user);
                showSmartLockDisabledMessage(dialog.getContext(), container);
            }
        }

        private void fixNavBarClipping(@NonNull SystemUIDialog dialog) {
            ViewGroup content = dialog.findViewById(android.R.id.content);
            content.setClipChildren(false);
            content.setClipToPadding(false);
            ViewGroup contentParent = (ViewGroup) content.getParent();
            contentParent.setClipChildren(false);
            contentParent.setClipToPadding(false);
        }

        private void showSmartLockDisabledMessage(@NonNull Context context,
                @NonNull ViewGroup container) {
            // Since power menu is the top window, make a Toast-like view that will show up
            View message = LayoutInflater.from(context).inflate(
                    com.android.systemui.res.R.layout.global_actions_toast, container, false);

            // Set up animation
            final int visibleTime = mAccessibilityManager.getRecommendedTimeoutMillis(
                    TOAST_VISIBLE_TIME, AccessibilityManager.FLAG_CONTENT_TEXT);
            message.setVisibility(View.VISIBLE);
            message.setAlpha(0f);
            container.addView(message);

            // Fade in
            message.animate()
                    .alpha(1f)
                    .setDuration(TOAST_FADE_TIME)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // Then fade out
                            message.animate()
                                    .alpha(0f)
                                    .setDuration(TOAST_FADE_TIME)
                                    .setStartDelay(visibleTime)
                                    .setListener(null);
                        }
                    });
        }

        @Override
        public void onStart(@NonNull SystemUIDialog dialog) {
            mGlobalActionsLayout.updateList();
            mLightBarController.setGlobalActionsVisible(true);

            mColorExtractor.addOnColorsChangedListener(this);
            GradientColors colors = mColorExtractor.getNeutralColors();
            updateColors(dialog, colors, false /* animate */);
            dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, mOnBackInvokedCallback);
            if (DEBUG) Log.d(TAG, "OnBackInvokedCallback handler registered");

            if (SceneContainerFlag.isEnabled()) {
                dismissWhenUnlockStatusChanges(mDeviceEntryInteractor.isUnlocked().getValue());
            }
        }

        /**
         * Dismisses the dialog when the unlock status changes.
         *
         * @param initialValue The initial unlock status of the device.
         */
        private void dismissWhenUnlockStatusChanges(boolean initialValue) {
            collectFlow(
                    mGlobalActionsLayout,
                    mDeviceEntryInteractor.isUnlocked(),
                    newValue -> {
                        if (newValue != initialValue) {
                            dismiss();
                        }
                    });
        }

        /**
         * Updates background and system bars according to current GradientColors.
         *
         * @param dialog  Dialog to update.
         * @param colors  Colors and hints to use.
         * @param animate Interpolates gradient if true, just sets otherwise.
         */
        private void updateColors(@NonNull SystemUIDialog dialog, @NonNull GradientColors colors,
                boolean animate) {
            if (mBackgroundDrawable == null) {
                return;
            }
            mBackgroundDrawable.setColor(Color.BLACK, animate);
            View decorView = dialog.getWindow().getDecorView();
            if (colors.supportsDarkText()) {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            } else {
                decorView.setSystemUiVisibility(0);
            }
        }

        @Override
        public void onStop(@NonNull SystemUIDialog dialog) {
            mLightBarController.setGlobalActionsVisible(false);
            mColorExtractor.removeOnColorsChangedListener(this);
            dialog.getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback(mOnBackInvokedCallback);
            if (DEBUG) Log.d(TAG, "OnBackInvokedCallback handler unregistered");
        }

        private void logOnBackInvocation() {
            mUiEventLogger.log(GlobalActionsEvent.GA_CLOSE_BACK);
            if (DEBUG) Log.d(TAG, "onBack invoked");
        }

        public void show(@Nullable Expandable expandable) {
            if (mCurrentDialog == null) {
                return;
            }
            DialogTransitionAnimator.Controller controller =
                    expandable != null ? expandable.dialogTransitionController(
                            new DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                    INTERACTION_JANK_TAG)) : null;
            if (controller != null) {
                if (TransitionAnimator.Companion.dynamicTargetResolutionEnabled()) {
                    mDialogTransitionAnimator.show(mCurrentDialog,
                            expandable::dialogTransitionController, controller.getCuj());
                } else {
                    mDialogTransitionAnimator.show(mCurrentDialog, controller);
                }
            } else {
                mCurrentDialog.show();
            }
            mTopUiController.setRequestTopUi(true, TAG);

            // By default this dialog windowAnimationStyle is null, and therefore windowAnimations
            // should be equal to 0 which means we need to animate the dialog in-window. If it's not
            // equal to 0, it means it has been overridden to animate (e.g. by the
            // DialogTransitionAnimator) so we don't run the animation.
            boolean shouldAnimateInWindow =
                    mCurrentDialog.getWindow().getAttributes().windowAnimations == 0;
            if (shouldAnimateInWindow) {
                startAnimation(mCurrentDialog, true /* isEnter */, null /* then */);

                // Override the dialog dismiss so that we can animate in-window before dismissing
                // the dialog.
                mCurrentDialog.setDismissOverride(() ->
                        startAnimation(mCurrentDialog, false /* isEnter */, /* then */ () -> {
                            if (mCurrentDialog != null) {
                                mCurrentDialog.setDismissOverride(null);
                            }

                            // Hide then dismiss to instantly dismiss.
                            hide();
                            dismiss();
                        })
                );
            }
        }

        public boolean isShowing() {
            return mCurrentDialog != null && mCurrentDialog.isShowing();
        }

        /** Run either the enter or exit animation, then run {@code then}. */
        private void startAnimation(@NonNull SystemUIDialog dialog, boolean isEnter,
                @Nullable Runnable then) {
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);

            // Note: these specs should be the same as in popup_enter_material and
            // popup_exit_material.
            float translationPx;
            Resources resources = dialog.getContext().getResources();
            if (isEnter) {
                translationPx = resources.getDimension(R.dimen.popup_enter_animation_from_y_delta);
                animator.setInterpolator(Interpolators.STANDARD);
                animator.setDuration(resources.getInteger(R.integer.config_activityDefaultDur));
            } else {
                translationPx = resources.getDimension(R.dimen.popup_exit_animation_to_y_delta);
                animator.setInterpolator(Interpolators.STANDARD_ACCELERATE);
                animator.setDuration(resources.getInteger(R.integer.config_activityShortDur));
            }

            Window window = dialog.getWindow();
            final int rotation = dialog.getContext().getDisplay().getRotation();

            animator.addUpdateListener(valueAnimator -> {
                float progress = (float) valueAnimator.getAnimatedValue();

                float alpha = isEnter ? progress : 1 - progress;
                mGlobalActionsLayout.setAlpha(alpha);
                window.setDimAmount(mInitialWindowDimAmount * alpha);

                // TODO(b/213872558): Support devices that don't have their power button on the
                // right.
                float translation =
                        isEnter ? translationPx * (1 - progress) : translationPx * progress;
                switch (rotation) {
                    case Surface.ROTATION_0:
                        mGlobalActionsLayout.setTranslationX(translation);
                        break;
                    case Surface.ROTATION_90:
                        mGlobalActionsLayout.setTranslationY(-translation);
                        break;
                    case Surface.ROTATION_180:
                        mGlobalActionsLayout.setTranslationX(-translation);
                        break;
                    case Surface.ROTATION_270:
                        mGlobalActionsLayout.setTranslationY(translation);
                        break;
                }
            });

            animator.addListener(new AnimatorListenerAdapter() {
                private int mPreviousLayerType;

                @Override
                public void onAnimationStart(@NonNull Animator animation, boolean isReverse) {
                    mPreviousLayerType = mGlobalActionsLayout.getLayerType();
                    mGlobalActionsLayout.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mGlobalActionsLayout.setLayerType(mPreviousLayerType, null);
                    if (then != null) {
                        then.run();
                    }
                }
            });

            animator.start();
        }

        public void hide() {
            if (mCurrentDialog == null) {
                return;
            }
            mCurrentDialog.hide();
        }

        public void dismiss() {
            if (mCurrentDialog == null) {
                return;
            }
            onDismiss();
            mCurrentDialog.dismiss();
        }

        public void dismissWithoutAnimation() {
            if (mCurrentDialog == null) {
                return;
            }
            onDismiss();
            mCurrentDialog.dismissWithoutAnimation();
        }

        private void onDismiss() {
            mAdapter.setShowingRestartOptions(false);
            dismissOverflow();
            dismissPowerOptions();
            dismissRestartOptions();
            mTopUiController.setRequestTopUi(false, TAG);
        }

        private void dismissOverflow() {
            if (mOverflowPopup != null) {
                mOverflowPopup.dismiss();
            }
        }

        private void dismissPowerOptions() {
            if (mPowerOptionsDialog != null) {
                mPowerOptionsDialog.dismiss();
            }
        }

        private void dismissRestartOptions() {
            if (mRestartOptionsDialog != null) {
                mRestartOptionsDialog.dismiss();
            }
        }

        @Override
        public void onColorsChanged(ColorExtractor extractor, int which) {
            if (mCurrentDialog == null) {
                return;
            }
            if (mKeyguardShowing) {
                if ((WallpaperManager.FLAG_LOCK & which) != 0) {
                    updateColors(mCurrentDialog, extractor.getColors(WallpaperManager.FLAG_LOCK),
                            true /* animate */);
                }
            } else {
                if ((WallpaperManager.FLAG_SYSTEM & which) != 0) {
                    updateColors(mCurrentDialog, extractor.getColors(WallpaperManager.FLAG_SYSTEM),
                            true /* animate */);
                }
            }
        }

        public void refreshDialog() {
            mOnRefreshCallback.run();

            // Dismiss the dropdown menus.
            dismissOverflow();
            dismissPowerOptions();
            dismissRestartOptions();

            // Update the list as the max number of items per row has probably changed.
            mGlobalActionsLayout.updateList();
        }

        private void onRotate(int from, int to) {
            refreshDialog();
        }

        @AssistedFactory
        public interface Factory {

            @NonNull
            ActionsDialogLiteDelegate create(
                    @NonNull Context context,
                    @NonNull MyAdapter adapter,
                    @NonNull MyOverflowAdapter overflowAdapter,
                    @NonNull MyPowerOptionsAdapter powerAdapter,
                    @NonNull MyRestartOptionsAdapter restartAdapter,
                    @NonNull StatusBarWindowController statusBarWindowController,
                    boolean keyguardShowing,
                    @Assisted("onRefreshCallback") @NonNull Runnable onRefreshCallback,
                    @Assisted("rescheduleBurnInTimeout") @NonNull Runnable rescheduleBurnInTimeout);
        }
    }
}
