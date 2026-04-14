/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar.notification.row;

import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;

import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.statusbar.IStatusBarService;
import com.android.settingslib.notification.ConversationIconFactory;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.people.widget.PeopleSpaceWidgetManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeDisplayAware;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.collection.BundleEntryAdapter;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.collection.render.NotifGutsViewListener;
import com.android.systemui.statusbar.notification.collection.render.NotifGutsViewManager;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.promoted.domain.interactor.PackageDemotionInteractor;
import com.android.systemui.statusbar.notification.row.icon.AppIconProvider;
import com.android.systemui.statusbar.notification.row.icon.NotificationIconStyleProvider;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.wmshell.BubblesManager;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

import java.util.List;
import java.util.Optional;

import javax.inject.Inject;

/**
 * Handles various NotificationGuts related tasks, such as binding guts to a row, opening and
 * closing guts, and keeping track of the currently exposed notification guts.
 */
@SysUISingleton
public class NotificationGutsManager implements NotifGutsViewManager, CoreStartable {
    private static final String TAG = "NotificationGutsManager";

    // Must match constant in Settings. Used to highlight preferences when linking to Settings.
    private static final String EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key";

    private final MetricsLogger mMetricsLogger;
    private final Context mContext;
    private final AccessibilityManager mAccessibilityManager;
    private final HighPriorityProvider mHighPriorityProvider;
    private final ChannelEditorDialogController mChannelEditorDialogController;
    private final PackageDemotionInteractor mPackageDemotionInteractor;
    private final OnUserInteractionCallback mOnUserInteractionCallback;

    // Dependencies:
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final StatusBarStateController mStatusBarStateController;
    private final IStatusBarService mStatusBarService;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final AssistantFeedbackController mAssistantFeedbackController;

    // which notification is currently being longpress-examined by the user
    private NotificationGuts mNotificationGutsExposed;
    private NotificationMenuRowPlugin.MenuItem mGutsMenuItem;
    private NotificationPresenter mPresenter;
    private NotificationActivityStarter mNotificationActivityStarter;
    private NotificationListContainer mListContainer;
    private OnSettingsClickListener mOnSettingsClickListener;

    private final Handler mMainHandler;
    private final Handler mBgHandler;
    private final JavaAdapter mJavaAdapter;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private Runnable mOpenRunnable;
    private final INotificationManager mNotificationManager;
    private final AppIconProvider mAppIconProvider;
    private final NotificationIconStyleProvider mIconStyleProvider;
    private final PeopleSpaceWidgetManager mPeopleSpaceWidgetManager;

    private final UserManager mUserManager;

    private final LauncherApps mLauncherApps;
    private final ShortcutManager mShortcutManager;
    private final UserContextProvider mContextTracker;
    private final UiEventLogger mUiEventLogger;
    private final ShadeController mShadeController;
    private final WindowRootViewVisibilityInteractor mWindowRootViewVisibilityInteractor;
    private NotifGutsViewListener mGutsListener;
    private final HeadsUpManager mHeadsUpManager;
    private final ActivityStarter mActivityStarter;
    private final ActivityManagerWrapper mActivityManagerWrapper;

    @Inject
    public NotificationGutsManager(
            @ShadeDisplayAware Context context,
            @Main Handler mainHandler,
            @Background Handler bgHandler,
            JavaAdapter javaAdapter,
            AccessibilityManager accessibilityManager,
            HighPriorityProvider highPriorityProvider,
            INotificationManager notificationManager,
            AppIconProvider appIconProvider,
            NotificationIconStyleProvider iconStyleProvider,
            UserManager userManager,
            PeopleSpaceWidgetManager peopleSpaceWidgetManager,
            LauncherApps launcherApps,
            ShortcutManager shortcutManager,
            ChannelEditorDialogController channelEditorDialogController,
            PackageDemotionInteractor packageDemotionInteractor,
            UserContextProvider contextTracker,
            AssistantFeedbackController assistantFeedbackController,
            Optional<BubblesManager> bubblesManagerOptional,
            UiEventLogger uiEventLogger,
            OnUserInteractionCallback onUserInteractionCallback,
            ShadeController shadeController,
            WindowRootViewVisibilityInteractor windowRootViewVisibilityInteractor,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            StatusBarStateController statusBarStateController,
            IStatusBarService statusBarService,
            DeviceProvisionedController deviceProvisionedController,
            MetricsLogger metricsLogger,
            HeadsUpManager headsUpManager,
            ActivityStarter activityStarter,
            ActivityManagerWrapper activityManagerWrapper) {
        mContext = context;
        mMainHandler = mainHandler;
        mBgHandler = bgHandler;
        mJavaAdapter = javaAdapter;
        mAccessibilityManager = accessibilityManager;
        mHighPriorityProvider = highPriorityProvider;
        mNotificationManager = notificationManager;
        mAppIconProvider = appIconProvider;
        mIconStyleProvider = iconStyleProvider;
        mUserManager = userManager;
        mPeopleSpaceWidgetManager = peopleSpaceWidgetManager;
        mLauncherApps = launcherApps;
        mShortcutManager = shortcutManager;
        mContextTracker = contextTracker;
        mChannelEditorDialogController = channelEditorDialogController;
        mPackageDemotionInteractor = packageDemotionInteractor;
        mAssistantFeedbackController = assistantFeedbackController;
        mBubblesManagerOptional = bubblesManagerOptional;
        mUiEventLogger = uiEventLogger;
        mOnUserInteractionCallback = onUserInteractionCallback;
        mShadeController = shadeController;
        mWindowRootViewVisibilityInteractor = windowRootViewVisibilityInteractor;
        mLockscreenUserManager = notificationLockscreenUserManager;
        mStatusBarStateController = statusBarStateController;
        mStatusBarService = statusBarService;
        mDeviceProvisionedController = deviceProvisionedController;
        mMetricsLogger = metricsLogger;
        mHeadsUpManager = headsUpManager;
        mActivityStarter = activityStarter;
        mActivityManagerWrapper = activityManagerWrapper;
    }

    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer,
            OnSettingsClickListener onSettingsClick) {
        mPresenter = presenter;
        mListContainer = listContainer;
        mOnSettingsClickListener = onSettingsClick;
    }

    public void setNotificationActivityStarter(
            NotificationActivityStarter notificationActivityStarter) {
        mNotificationActivityStarter = notificationActivityStarter;
    }

    @Override
    public void start() {
        mJavaAdapter.alwaysCollectFlow(
                mWindowRootViewVisibilityInteractor.isLockscreenOrShadeVisible(),
                this::onLockscreenShadeVisibilityChanged);
    }

    private void onLockscreenShadeVisibilityChanged(boolean visible) {
        if (!visible) {
            closeAndSaveGuts(
                    /* removeLeavebehind= */ true ,
                    /* force= */ true,
                    /* removeControls= */ true,
                    /* x= */ -1,
                    /* y= */ -1,
                    /* resetMenu= */ true);
        }
    }

    /**
     * Sends an intent to open the notification settings for a particular package and optional
     * channel.
     */
    public static final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args";

    private void startAppNotificationSettingsActivity(String packageName, final int appUid,
            final NotificationChannel channel, ExpandableNotificationRow row) {
        final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        intent.putExtra(Settings.EXTRA_APP_UID, appUid);

        if (channel != null) {
            final Bundle args = new Bundle();
            intent.putExtra(EXTRA_FRAGMENT_ARG_KEY, channel.getId());
            args.putString(EXTRA_FRAGMENT_ARG_KEY, channel.getId());
            intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, args);
        }
        mNotificationActivityStarter.startNotificationGutsIntent(intent, appUid, row);
    }

    private void startAppDetailsSettingsActivity(String packageName, final int appUid,
            ExpandableNotificationRow row) {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", packageName, null));
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        intent.putExtra(Settings.EXTRA_APP_UID, appUid);
        mNotificationActivityStarter.startNotificationGutsIntent(intent, appUid, row);
    }

    protected void startAppOpsSettingsActivity(String pkg, int uid, ArraySet<Integer> ops,
            ExpandableNotificationRow row) {
        if (ops.contains(OP_SYSTEM_ALERT_WINDOW)) {
            if (ops.contains(OP_CAMERA) || ops.contains(OP_RECORD_AUDIO)) {
                startAppDetailsSettingsActivity(pkg, uid, row);
            } else {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_OVERLAY_PERMISSION);
                intent.setData(Uri.fromParts("package", pkg, null));
                mNotificationActivityStarter.startNotificationGutsIntent(intent, uid, row);
            }
        } else if (ops.contains(OP_CAMERA) || ops.contains(OP_RECORD_AUDIO)) {
            Intent intent = new Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, pkg);
            mNotificationActivityStarter.startNotificationGutsIntent(intent, uid, row);
        }
    }

    private void startConversationSettingsActivity(int uid, ExpandableNotificationRow row) {
        final Intent intent = new Intent(Settings.ACTION_CONVERSATION_SETTINGS);
        mNotificationActivityStarter.startNotificationGutsIntent(intent, uid, row);
    }

    @VisibleForTesting
    protected boolean bindGuts(final ExpandableNotificationRow row,
            NotificationMenuRowPlugin.MenuItem item) {

        StatusBarNotification sbn = NotificationBundleUi.isEnabled()
                ? row.getEntryAdapter().getSbn()
                : row.getEntryLegacy().getSbn();
        NotificationListenerService.Ranking ranking  = NotificationBundleUi.isEnabled()
                ? row.getEntryAdapter().getRanking()
                : row.getEntryLegacy().getRanking();

        if ((sbn == null || ranking == null) && !row.isBundle()) {
            // only valid for notification rows
            return false;
        }

        row.setGutsView(item);
        if (sbn != null) {
            row.setTag(sbn.getPackageName());
        }
        row.getGuts().setClosedListener((NotificationGuts g) -> {
            row.onGutsClosed();
            if (!g.willBeRemoved() && !row.isRemoved()) {
                mListContainer.onHeightChanged(
                        row, !mPresenter.isPresenterFullyCollapsed() /* needsAnimation */);
            }
            if (mNotificationGutsExposed == g) {
                mNotificationGutsExposed = null;
                mGutsMenuItem = null;
            }
            if (mGutsListener != null) {
                if (NotificationBundleUi.isEnabled()) {
                    mGutsListener.onGutsClose(row.getEntryAdapter());
                    row.updateBubbleButton();
                } else {
                    mGutsListener.onGutsClose(row.getEntryLegacy());
                }
            }
            if(NotificationBundleUi.isEnabled()) {
                row.getEntryAdapter().setInlineControlsShown(false);
            } else {
                mHeadsUpManager.setGutsShown(row.getEntryLegacy(), false);
            }
        });

        Object gutsContent = item.getGutsContent();

        try {
            if (gutsContent instanceof NotificationSnooze ns) {
                initializeSnoozeView(row, sbn, ranking, ns);
            } else if (gutsContent instanceof NotificationInfo ni) {
                initializeNotificationInfo(row, sbn, ranking, ni);
            } else if (gutsContent instanceof NotificationConversationInfo nci) {
                initializeConversationNotificationInfo(
                        row, sbn, ranking, nci);
            } else if (gutsContent instanceof PartialConversationInfo pci) {
                initializePartialConversationNotificationInfo(row, sbn, ranking, pci);
            } else if (gutsContent instanceof FeedbackInfo fi) {
                initializeFeedbackInfo(row, sbn, ranking, fi);
            } else if (gutsContent instanceof PromotedPermissionGutsContent ppgc) {
                initializeDemoteView(sbn, ppgc);
            } else if (gutsContent instanceof BundledNotificationInfo bni) {
                initializeBundledNotificationInfo(row, sbn, ranking, bni);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "error binding guts", e);
            return false;
        }
    }

    /**
     * Sets up the {@link NotificationSnooze} inside the notification row's guts.
     *
     * @param row view to set up the guts for
     * @param notificationSnoozeView view to set up/bind within {@code row}
     */
    private void initializeSnoozeView(
            final ExpandableNotificationRow row,
            final StatusBarNotification sbn,
            final NotificationListenerService.Ranking ranking,
            NotificationSnooze notificationSnoozeView) {
        NotificationGuts guts = row.getGuts();

        notificationSnoozeView.setSnoozeListener(mListContainer.getSwipeActionHelper());
        notificationSnoozeView.setStatusBarNotification(sbn);
        notificationSnoozeView.setSnoozeOptions(ranking.getSnoozeCriteria());
        guts.setHeightChangedListener((NotificationGuts g) -> {
            mListContainer.onHeightChanged(row, row.isShown() /* needsAnimation */);
        });
    }

    /**
     * Sets up the {@link NotificationSnooze} inside the notification row's guts.
     *
     * @param demoteGuts view to set up/bind within {@code row}
     */
    private void initializeDemoteView(
            @NonNull StatusBarNotification sbn,
            @NonNull PromotedPermissionGutsContent demoteGuts) {
        demoteGuts.setOnDemoteAction(v -> {
            try {
                mNotificationManager.setCanBePromoted(
                        sbn.getPackageName(), sbn.getUid(), false, true);
                mPackageDemotionInteractor.onPackageDemoted(sbn.getPackageName(), sbn.getUid());
                mUiEventLogger.log(NotificationControlsEvent.NOTIFICATION_DEMOTION_COMMIT);
            } catch (RemoteException e) {
                Log.e(TAG, "Couldn't revoke live update permission", e);
            }
        });
    }

    /**
     * Sets up the {@link FeedbackInfo} inside the notification row's guts.
     *
     * @param row view to set up the guts for
     * @param feedbackInfo view to set up/bind within {@code row}
     */
    private void initializeFeedbackInfo(
            final ExpandableNotificationRow row,
            final StatusBarNotification sbn,
            final NotificationListenerService.Ranking ranking,
            FeedbackInfo feedbackInfo) {
        if (mAssistantFeedbackController.getFeedbackIcon(ranking) == null) {
            return;
        }
        UserHandle userHandle = sbn.getUser();
        PackageManager pmUser = CentralSurfaces.getPackageManagerForUser(mContext,
                userHandle.getIdentifier());

        feedbackInfo.bindGuts(pmUser, sbn, ranking, row, mAssistantFeedbackController,
                mStatusBarService, this);
    }

    /**
     * Sets up the {@link BundledNotificationInfo} inside the notification row's guts.
     * @param row view to set up the guts for
     * @param notificationInfoView view to set up/bind within {@code row}
     */
    @VisibleForTesting
    void initializeBundledNotificationInfo(
            final ExpandableNotificationRow row,
            final StatusBarNotification sbn,
            final NotificationListenerService.Ranking ranking,
            NotificationInfo notificationInfoView) throws Exception {
        NotificationGuts guts = row.getGuts();
        String packageName = sbn.getPackageName();
        UserHandle userHandle = sbn.getUser();
        PackageManager pmUser = CentralSurfaces.getPackageManagerForUser(
                mContext, userHandle.getIdentifier());

        NotificationInfo.OnSettingsClickListener onSettingsClick =
                (View v, NotificationChannel channel, int appUid) -> {
                    mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_NOTE_INFO);
                    guts.resetFalsingCheck();
                    mOnSettingsClickListener.onSettingsClick(sbn.getKey());
                    startBundleSettingsActivity(appUid, row);
                };

        NotificationInfo.OnFeedbackClickListener onNasFeedbackClick = (View v, Intent intent) -> {
            guts.resetFalsingCheck();
            mUiEventLogger.log(NotificationFeedbackEvent.NOTIFICATION_FEEDBACK_BUNDLE,
                    sbn.getInstanceId());
            mNotificationActivityStarter.startNotificationGutsIntent(intent, sbn.getUid(), row);
        };

        notificationInfoView.bindNotification(
                pmUser,
                mNotificationManager,
                mAppIconProvider,
                mIconStyleProvider,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                mPackageDemotionInteractor,
                packageName,
                ranking,
                sbn,
                NotificationBundleUi.isEnabled() ? null : row.getEntryLegacy(),
                NotificationBundleUi.isEnabled() ? row.getEntryAdapter() : null,
                onSettingsClick,
                null,
                onNasFeedbackClick,
                mUiEventLogger,
                mDeviceProvisionedController.isDeviceProvisioned(),
                NotificationBundleUi.isEnabled()
                        ? !row.getEntryAdapter().isBlockable()
                        : row.getIsNonblockable(),
                row.canViewBeDismissed(),
                NotificationBundleUi.isEnabled()
                        ? row.getEntryAdapter().isHighPriority()
                        : mHighPriorityProvider.isHighPriority(row.getEntryLegacy()),
                mAssistantFeedbackController,
                mMetricsLogger,
                row.getDismissButtonOnClickListener());
    }

    private void startBundleSettingsActivity(final int appUid,
            ExpandableNotificationRow row) {
        final Intent intent = new Intent(Settings.ACTION_NOTIFICATION_BUNDLES);
        mNotificationActivityStarter.startNotificationGutsIntent(intent, appUid, row);
    }

    /**
     * Sets up the {@link NotificationInfo} inside the notification row's guts.
     * @param row view to set up the guts for
     * @param notificationInfoView view to set up/bind within {@code row}
     */
    @VisibleForTesting
    void initializeNotificationInfo(
            final ExpandableNotificationRow row,
            final StatusBarNotification sbn,
            final NotificationListenerService.Ranking ranking,
            NotificationInfo notificationInfoView) throws Exception {
        NotificationGuts guts = row.getGuts();
        String packageName = sbn.getPackageName();
        // Settings link is only valid for notifications that specify a non-system user
        NotificationInfo.OnSettingsClickListener onSettingsClick = null;
        UserHandle userHandle = sbn.getUser();
        PackageManager pmUser = CentralSurfaces.getPackageManagerForUser(
                mContext, userHandle.getIdentifier());
        final NotificationInfo.OnAppSettingsClickListener onAppSettingsClick =
                (View v, Intent intent) -> {
                    mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_APP_NOTE_SETTINGS);
                    guts.resetFalsingCheck();
                    mNotificationActivityStarter.startNotificationGutsIntent(intent, sbn.getUid(),
                            row);
                };

        if (!userHandle.equals(UserHandle.ALL)
                || mLockscreenUserManager.getCurrentUserId() == UserHandle.USER_SYSTEM) {
            onSettingsClick = (View v, NotificationChannel channel, int appUid) -> {
                mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_NOTE_INFO);
                guts.resetFalsingCheck();
                mOnSettingsClickListener.onSettingsClick(sbn.getKey());
                startAppNotificationSettingsActivity(packageName, appUid, channel, row);
            };
        }

        NotificationInfo.OnFeedbackClickListener onNasFeedbackClick = (View v, Intent intent) -> {
            guts.resetFalsingCheck();
            mNotificationActivityStarter.startNotificationGutsIntent(intent, sbn.getUid(), row);
        };

        notificationInfoView.bindNotification(
                pmUser,
                mNotificationManager,
                mAppIconProvider,
                mIconStyleProvider,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                mPackageDemotionInteractor,
                packageName,
                ranking,
                sbn,
                NotificationBundleUi.isEnabled() ? null : row.getEntryLegacy(),
                NotificationBundleUi.isEnabled() ? row.getEntryAdapter() : null,
                onSettingsClick,
                onAppSettingsClick,
                onNasFeedbackClick,
                mUiEventLogger,
                mDeviceProvisionedController.isDeviceProvisioned(),
                NotificationBundleUi.isEnabled()
                        ? !row.getEntryAdapter().isBlockable()
                        : row.getIsNonblockable(),
                row.canViewBeDismissed(),
                NotificationBundleUi.isEnabled()
                        ? row.getEntryAdapter().isHighPriority()
                        : mHighPriorityProvider.isHighPriority(row.getEntryLegacy()),
                mAssistantFeedbackController,
                mMetricsLogger,
                row.getDismissButtonOnClickListener());
    }

    /**
     * Sets up the {@link PartialConversationInfo} inside the notification row's guts.
     * @param row view to set up the guts for
     * @param notificationInfoView view to set up/bind within {@code row}
     */
    @VisibleForTesting
    void initializePartialConversationNotificationInfo(
            final ExpandableNotificationRow row,
            final StatusBarNotification sbn,
            final NotificationListenerService.Ranking ranking,
            PartialConversationInfo notificationInfoView) throws Exception {
        NotificationGuts guts = row.getGuts();
        String packageName = sbn.getPackageName();
        // Settings link is only valid for notifications that specify a non-system user
        NotificationInfo.OnSettingsClickListener onSettingsClick = null;
        UserHandle userHandle = sbn.getUser();
        PackageManager pmUser = CentralSurfaces.getPackageManagerForUser(
                mContext, userHandle.getIdentifier());

        if (!userHandle.equals(UserHandle.ALL)
                || mLockscreenUserManager.getCurrentUserId() == UserHandle.USER_SYSTEM) {
            onSettingsClick = (View v, NotificationChannel channel, int appUid) -> {
                mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_NOTE_INFO);
                guts.resetFalsingCheck();
                mOnSettingsClickListener.onSettingsClick(sbn.getKey());
                startAppNotificationSettingsActivity(packageName, appUid, channel, row);
            };
        }

        NotificationInfo.OnFeedbackClickListener onNasFeedbackClick = (View v, Intent intent) -> {
            guts.resetFalsingCheck();
            mNotificationActivityStarter.startNotificationGutsIntent(intent, sbn.getUid(), row);
        };

        notificationInfoView.bindNotification(
                pmUser,
                mNotificationManager,
                mChannelEditorDialogController,
                packageName,
                ranking,
                sbn,
                onSettingsClick,
                onNasFeedbackClick,
                mDeviceProvisionedController.isDeviceProvisioned(),
                NotificationBundleUi.isEnabled()
                        ? !row.getEntryAdapter().isBlockable()
                        : row.getIsNonblockable());
    }

    /**
     * Sets up the {@link NotificationConversationInfo} inside the notification row's guts.
     * @param row view to set up the guts for
     * @param notificationInfoView view to set up/bind within {@code row}
     */
    @VisibleForTesting
    void initializeConversationNotificationInfo(
            final ExpandableNotificationRow row,
            final StatusBarNotification sbn,
            final NotificationListenerService.Ranking ranking,
            NotificationConversationInfo notificationInfoView) throws Exception {
        NotificationGuts guts = row.getGuts();
        String packageName = sbn.getPackageName();
        // Settings link is only valid for notifications that specify a non-system user
        NotificationConversationInfo.OnSettingsClickListener onSettingsClick = null;
        UserHandle userHandle = sbn.getUser();
        PackageManager pmUser = CentralSurfaces.getPackageManagerForUser(
                mContext, userHandle.getIdentifier());

        final NotificationConversationInfo.OnConversationSettingsClickListener
                onConversationSettingsListener =
                () -> startConversationSettingsActivity(sbn.getUid(), row);

        if (!userHandle.equals(UserHandle.ALL)
                || mLockscreenUserManager.getCurrentUserId() == UserHandle.USER_SYSTEM) {
            onSettingsClick = (View v, NotificationChannel channel, int appUid) -> {
                mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_NOTE_INFO);
                guts.resetFalsingCheck();
                mOnSettingsClickListener.onSettingsClick(sbn.getKey());
                startAppNotificationSettingsActivity(packageName, appUid, channel, row);
            };
        }
        ConversationIconFactory iconFactoryLoader = new ConversationIconFactory(mContext,
                mLauncherApps, pmUser, IconDrawableFactory.newInstance(mContext, false),
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.notification_guts_conversation_icon_size));

        NotificationInfo.OnFeedbackClickListener onNasFeedbackClick = (View v, Intent intent) -> {
            guts.resetFalsingCheck();
            mUiEventLogger.log(NotificationFeedbackEvent.NOTIFICATION_FEEDBACK_CONVERSATION,
                    sbn.getInstanceId());
            mNotificationActivityStarter.startNotificationGutsIntent(intent, sbn.getUid(), row);
        };

        notificationInfoView.bindNotification(
                mShortcutManager,
                pmUser,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mNotificationManager,
                mOnUserInteractionCallback,
                packageName,
                NotificationBundleUi.isEnabled() ? null : row.getEntryLegacy(),
                NotificationBundleUi.isEnabled() ? row.getEntryAdapter() : null,
                ranking,
                sbn,
                onSettingsClick,
                onNasFeedbackClick,
                iconFactoryLoader,
                mContextTracker.getUserContext(),
                mDeviceProvisionedController.isDeviceProvisioned(),
                mMainHandler,
                mBgHandler,
                onConversationSettingsListener,
                mBubblesManagerOptional,
                mShadeController,
                row.canViewBeDismissed(),
                row.getDismissButtonOnClickListener());
    }

    /**
     * Closes guts or notification menus that might be visible and saves any changes if applicable
     * (see {@link NotificationGuts.GutsContent#shouldBeSavedOnClose}).
     *
     * @param removeLeavebehinds true if leavebehinds (e.g. snooze) should be closed.
     * @param force true if guts should be closed regardless of state (used for snooze only).
     * @param removeControls true if controls (e.g. info) should be closed.
     * @param x if closed based on touch location, this is the x touch location.
     * @param y if closed based on touch location, this is the y touch location.
     * @param resetMenu if any notification menus that might be revealed should be closed.
     */
    public void closeAndSaveGuts(boolean removeLeavebehinds, boolean force, boolean removeControls,
            int x, int y, boolean resetMenu) {
        if (mNotificationGutsExposed != null) {
            mNotificationGutsExposed.removeCallbacks(mOpenRunnable);
            mNotificationGutsExposed.closeControls(removeLeavebehinds, removeControls, x, y, force);
        }
        if (resetMenu && mListContainer != null) {
            mListContainer.resetExposedMenuView(false /* animate */, true /* force */);
        }
    }

    /**
     * Closes all guts that might be visible without saving changes.
     */
    public void closeAndUndoGuts() {
        if (mNotificationGutsExposed != null) {
            mNotificationGutsExposed.removeCallbacks(mOpenRunnable);
            mNotificationGutsExposed.closeControls(
                    /* x = */ -1,
                    /* y = */ -1,
                    /* save = */ false,
                    /* force = */ false);
        }
    }

    /**
     * Returns the exposed NotificationGuts or null if none are exposed.
     */
    public NotificationGuts getExposedGuts() {
        return mNotificationGutsExposed;
    }

    @VisibleForTesting
    public void setExposedGuts(NotificationGuts guts) {
        mNotificationGutsExposed = guts;
    }

    /**
     * Opens guts on the given ExpandableNotificationRow {@code view}. This handles opening guts for
     * the normal half-swipe and long-press use cases via a circular reveal. When the blocking
     * helper needs to be shown on the row, this will skip the circular reveal.
     *
     * @param view ExpandableNotificationRow to open guts on
     * @param x x coordinate of origin of circular reveal
     * @param y y coordinate of origin of circular reveal
     * @param menuItem MenuItem the guts should display
     * @return true if guts was opened
     */
    public boolean openGuts(
            View view,
            int x,
            int y,
            NotificationMenuRowPlugin.MenuItem menuItem) {
        if (menuItem.getGutsContent() instanceof NotificationGuts.GutsContent gutsContent) {
            if (gutsContent.needsFalsingProtection()) {
                if (mStatusBarStateController instanceof StatusBarStateControllerImpl) {
                    ((StatusBarStateControllerImpl) mStatusBarStateController)
                            .setLeaveOpenOnKeyguardHide(true);
                }

                Runnable r = () -> mMainHandler.post(
                        () -> openGutsInternal(view, x, y, menuItem));
                // If the bouncer shows, it will block the TOUCH_UP event from reaching the notif,
                // so explicitly mark it as unpressed here to reset the touch animation.
                view.setPressed(false);
                mActivityStarter.executeRunnableDismissingKeyguard(
                        r,
                        null /* cancelAction */,
                        false /* dismissShade */,
                        true /* afterKeyguardGone */,
                        true /* deferred */);
                return true;
                /**
                 * When {@link CentralSurfaces} doesn't exist, falling through to call
                 * {@link #openGutsInternal(View,int,int,NotificationMenuRowPlugin.MenuItem)}.
                 */
            }
        }
        return openGutsInternal(view, x, y, menuItem);
    }

    @VisibleForTesting
    boolean openGutsInternal(
            View view,
            int x,
            int y,
            NotificationMenuRowPlugin.MenuItem menuItem) {

        if (!(view instanceof ExpandableNotificationRow)) {
            return false;
        }

        if (view.getWindowToken() == null) {
            Log.e(TAG, "Trying to show notification guts, but not attached to window");
            return false;
        }

        if (mActivityManagerWrapper.isLockTaskKioskModeActive()) {
            // If the device is locked in kiosk mode, the user should not be able to access
            // notification guts to change any settings.
            return false;
        }

        final ExpandableNotificationRow row = (ExpandableNotificationRow) view;
        if (affectedByWorkProfileLock(row)) {
            return false;
        }

        if (row.isNotificationRowLongClickable()) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        if (row.areGutsExposed()) {
            closeAndSaveGuts(false /* removeLeavebehind */, false /* force */,
                    true /* removeControls */, -1 /* x */, -1 /* y */,
                    true /* resetMenu */);
            return false;
        }

        row.ensureGutsInflated();
        NotificationGuts guts = row.getGuts();
        mNotificationGutsExposed = guts;
        if (!bindGuts(row, menuItem)) {
            // exception occurred trying to fill in all the data, bail.
            return false;
        }


        // Assume we are a status_bar_notification_row
        if (guts == null) {
            // This view has no guts. Examples are the more card or the dismiss all view
            return false;
        }

        // ensure that it's laid but not visible until actually laid out
        guts.setVisibility(View.INVISIBLE);
        // Post to ensure the the guts are properly laid out.
        mOpenRunnable = new Runnable() {
            @Override
            public void run() {
                if (row.getWindowToken() == null) {
                    Log.e(TAG, "Trying to show notification guts in post(), but not attached to "
                            + "window");
                    return;
                }
                guts.setVisibility(View.VISIBLE);

                final boolean needsFalsingProtection =
                        (mStatusBarStateController.getState() == StatusBarState.KEYGUARD &&
                                !mAccessibilityManager.isTouchExplorationEnabled());

                guts.openControls(
                        x,
                        y,
                        needsFalsingProtection,
                        row::onGutsOpened);

                if (mGutsListener != null) {
                    if(NotificationBundleUi.isEnabled()) {
                        mGutsListener.onGutsOpen(row.getEntryAdapter(), guts);
                    } else {
                        mGutsListener.onGutsOpen(row.getEntryLegacy(), guts);
                    }
                }

                row.closeRemoteInput();
                mListContainer.onHeightChanged(row, true /* needsAnimation */);
                mGutsMenuItem = menuItem;
                if(NotificationBundleUi.isEnabled()) {
                    row.getEntryAdapter().setInlineControlsShown(true);
                } else {
                    mHeadsUpManager.setGutsShown(row.getEntryLegacy(), true);
                }
            }
        };
        guts.post(mOpenRunnable);
        return true;
    }

    boolean affectedByWorkProfileLock(ExpandableNotificationRow row) {
        if (NotificationBundleUi.isEnabled()
                && row.getEntryAdapter() instanceof BundleEntryAdapter) {
            return false;
        }
        int userId = NotificationBundleUi.isEnabled()
            ? row.getEntryAdapter().getSbn().getNormalizedUserId()
            : row.getEntryLegacy().getSbn().getNormalizedUserId();
        return mUserManager.isManagedProfile(userId)
                && mLockscreenUserManager.isLockscreenPublicMode(userId);
    }

    /**
     * @param gutsListener the listener for open and close guts events
     */
    public void setGutsListener(NotifGutsViewListener gutsListener) {
        mGutsListener = gutsListener;
    }

    public interface OnSettingsClickListener {
        public void onSettingsClick(String key);
    }
}
