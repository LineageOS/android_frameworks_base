/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.notification;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED;
import static android.app.NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED;
import static android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED;
import static android.app.NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID;
import static android.app.NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS;
import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;
import static android.content.pm.PackageManager.FEATURE_TELECOM;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.os.Build.VERSION_CODES.O_MR1;
import static android.os.Build.VERSION_CODES.P;
import static android.os.Process.INVALID_UID;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
import static android.service.notification.Condition.SOURCE_CONTEXT;
import static android.service.notification.Condition.SOURCE_USER_ACTION;
import static android.service.notification.Condition.STATE_TRUE;
import static android.service.notification.Flags.splitSoundVibrationForNotificationBreakthrough;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS;

import static com.android.server.notification.Flags.FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER;
import static com.android.server.notification.NotificationManagerService.TAG;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AppLockInternal;
import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.IUriGrantsManager;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.app.StatsManager;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.role.RoleManager;
import android.app.usage.UsageStatsManagerInternal;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.ICompanionDeviceManager;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;
import android.platform.test.rule.LimitDevicesRule;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.DeviceEffectsApplier;
import android.service.notification.INotificationListener;
import android.service.notification.NotificationListenerFilter;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenPolicy;
import android.telecom.TelecomManager;
import android.testing.TestWithLooperRule;
import android.testing.TestableContentResolver;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.config.sysui.TestableFlagResolver;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.logging.InstanceIdSequenceFake;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;
import com.android.server.UiServiceTestCase;
import com.android.server.bitmapoffload.BitmapOffloadInternal;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;
import com.android.server.notification.NotificationManagerService.NotificationAssistants;
import com.android.server.notification.NotificationManagerService.NotificationListeners;
import com.android.server.notification.NotificationManagerService.PostNotificationTrackerFactory;
import com.android.server.notification.ZenModeHelper.Callback;
import com.android.server.personalcontext.PersonalContextManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.security.authenticationpolicy.SecureLockDeviceServiceInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.utils.quota.MultiRateLimiter;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import com.google.android.collect.Lists;
import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

@SmallTest
@RunWithLooper
@RunWith(ParameterizedAndroidJunit4.class)
@SuppressLint("GuardedBy") // It's ok for this test to access guarded methods from the service.
public class NotificationManagerServiceZenTest extends UiServiceTestCase {
    private static final String TEST_CHANNEL_ID = "NotificationManagerServiceTestChannelId";

    private static final AutomaticZenRule SOME_ZEN_RULE =
            new AutomaticZenRule.Builder("rule", Uri.parse("uri"))
                    .setOwner(new ComponentName("pkg", "cls"))
                    .build();

    private static final int MAX_CHANNELS_CREATED_BY_NLS_FOR_TESTING = 10;
    private static final String MISSING_PACKAGE = "MISSING!";

    @ClassRule
    public static final LimitDevicesRule sLimitDevicesRule = new LimitDevicesRule();

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private TestableNotificationManagerService mService;
    private INotificationManager mBinderService;
    private NotificationManagerInternal mInternalService;
    private ShortcutHelper mShortcutHelper;
    @Mock
    private IPackageManager mPackageManager;
    @Mock
    private PackageManager mPackageManagerClient;
    @Mock
    private PermissionPolicyInternal mPermissionPolicyInternal;
    @Mock
    private WindowManagerInternal mWindowManagerInternal;
    @Mock
    private PermissionHelper mPermissionHelper;
    private NotificationChannelLoggerFake mLogger = new NotificationChannelLoggerFake();
    @Rule(order = Integer.MAX_VALUE)
    public TestWithLooperRule mlooperRule = new TestWithLooperRule();
    private TestableLooper mTestableLooper;
    @Mock private PreferencesHelper mPreferencesHelper;
    AtomicFile mPolicyFile;
    File mFile;
    AtomicFile mRulesFile;
    File mFile2;
    @Mock
    private NotificationUsageStats mUsageStats;
    @Mock
    private UsageStatsManagerInternal mAppUsageStats;
    @Mock
    private AudioManager mAudioManager;
    @Mock
    private LauncherApps mLauncherApps;
    @Mock
    private ShortcutServiceInternal mShortcutServiceInternal;
    @Mock
    private UserManager mUserManager;
    @Mock
    ActivityManager mActivityManager;
    @Mock
    TelecomManager mTelecomManager;
    @Mock
    Resources mResources;
    @Mock
    RankingHandler mRankingHandler;
    @Mock
    ActivityManagerInternal mAmi;
    @Mock
    JobSchedulerInternal mJsi;
    @Mock
    private Looper mMainLooper;
    @Mock
    private NotificationManager mMockNm;
    @Mock
    private PermissionManager mPermissionManager;
    @Mock
    private DevicePolicyManagerInternal mDevicePolicyManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private LightsManager mLightsManager;
    @Mock
    private BitmapOffloadInternal mBitmapOffloader;
    @Mock
    private PersonalContextManagerInternal mPersonalContextManagerInternal;

    private final ArrayList<WakeLock> mAcquiredWakeLocks = new ArrayList<>();
    private UiEventLoggerFake mUiEventLogger;

    @Mock
    private NotificationListeners mListeners;
    @Mock
    private NotificationListenerFilter mNlf;
    @Mock private NotificationAssistants mAssistants;
    @Mock private ConditionProviders mConditionProviders;
    private ManagedServices.ManagedServiceInfo mListener;
    @Mock private ICompanionDeviceManager mCompanionMgr;
    @Mock SnoozeHelper mSnoozeHelper;
    GroupHelper mGroupHelper;
    @Mock
    IBinder mPermOwner;
    @Mock
    IActivityManager mAm;
    @Mock
    ActivityTaskManagerInternal mAtm;
    @Mock
    IUriGrantsManager mUgm;
    @Mock
    UriGrantsManagerInternal mUgmInternal;
    @Mock
    AppOpsManager mAppOpsManager;
    @Mock
    UserManager mUm;
    @Mock
    UserManagerInternal mUmInternal;
    @Mock
    AppLockInternal mAppLockInternal;
    @Mock
    NotificationHistoryManager mHistoryManager;
    @Mock
    StatsManager mStatsManager;
    @Mock
    AlarmManager mAlarmManager;
    @Mock
    SecureLockDeviceServiceInternal mSecureLockDeviceServiceInternal;
    @Mock
    MultiRateLimiter mToastRateLimiter;
    BroadcastReceiver mPackageIntentReceiver;
    BroadcastReceiver mUserIntentReceiver;
    NotificationRecordLoggerFake mNotificationRecordLogger = new NotificationRecordLoggerFake();
    TestableNotificationManagerService.StrongAuthTrackerFake mStrongAuthTracker;

    TestableFlagResolver mTestFlagResolver = new TestableFlagResolver();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    private InstanceIdSequence mNotificationInstanceIdSequence = new InstanceIdSequenceFake(
            1 << 30);
    @Mock
    StatusBarManagerInternal mStatusBar;

    @Mock
    NotificationAttentionHelper mAttentionHelper;

    private NotificationManagerService.WorkerHandler mWorkerHandler;
    private Handler mBroadcastsHandler;

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return FlagsParameterization.allCombinationsOf(android.service.notification.Flags
                .FLAG_SPLIT_SOUND_VIBRATION_FOR_NOTIFICATION_BREAKTHROUGH);
    }

    public NotificationManagerServiceZenTest(FlagsParameterization flags) {
        mSetFlagsRule.setFlagsParameterization(flags);
    }

    @Before
    public void setUpNMS() throws Exception {
        // Shell permisssions will override permissions of our app, so add all necessary permissions
        // for this test here:
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                "android.permission.WRITE_ALLOWLISTED_DEVICE_CONFIG",
                "android.permission.READ_DEVICE_CONFIG",
                "android.permission.READ_CONTACTS");

        mUiEventLogger = new UiEventLoggerFake();
        when(mActivityManager.getUidImportance(anyInt())).thenReturn(IMPORTANCE_VISIBLE);

        SparseArray<Set<String>> appLockedPackages = new SparseArray<>();
        Set<String> lockedPackages = new ArraySet<>(1);
        lockedPackages.add(mPkg);
        appLockedPackages.put(mUserId, lockedPackages);
        when(mAppLockInternal.getAppLockEnabledPackages()).thenReturn(appLockedPackages);

        DeviceIdleInternal deviceIdleInternal = mock(DeviceIdleInternal.class);
        when(deviceIdleInternal.getNotificationAllowlistDuration()).thenReturn(3000L);

        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, mUmInternal);
        LocalServices.removeServiceForTest(UriGrantsManagerInternal.class);
        LocalServices.addService(UriGrantsManagerInternal.class, mUgmInternal);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mWindowManagerInternal);
        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mStatusBar);
        LocalServices.removeServiceForTest(DeviceIdleInternal.class);
        LocalServices.addService(DeviceIdleInternal.class, deviceIdleInternal);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mAmi);
        LocalServices.removeServiceForTest(JobSchedulerInternal.class);
        LocalServices.addService(JobSchedulerInternal.class, mJsi);
        LocalServices.removeServiceForTest(PermissionPolicyInternal.class);
        LocalServices.addService(PermissionPolicyInternal.class, mPermissionPolicyInternal);
        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);
        LocalServices.addService(ShortcutServiceInternal.class, mShortcutServiceInternal);
        LocalServices.removeServiceForTest(SecureLockDeviceServiceInternal.class);
        LocalServices.addService(SecureLockDeviceServiceInternal.class,
                mSecureLockDeviceServiceInternal);
        LocalServices.removeServiceForTest(PersonalContextManagerInternal.class);
        LocalServices.addService(PersonalContextManagerInternal.class,
                mPersonalContextManagerInternal);
        LocalServices.removeServiceForTest(AppLockInternal.class);
        LocalServices.addService(AppLockInternal.class, mAppLockInternal);
        mContext.addMockSystemService(Context.ALARM_SERVICE, mAlarmManager);
        mContext.addMockSystemService(NotificationManager.class, mMockNm);
        mContext.addMockSystemService(RoleManager.class, mock(RoleManager.class));
        mContext.addMockSystemService(Context.LAUNCHER_APPS_SERVICE, mLauncherApps);
        mContext.addMockSystemService(Context.USER_SERVICE, mUm);
        mContext.addMockSystemService(Context.ACCESSIBILITY_SERVICE,
                mock(AccessibilityManager.class));
        mContext.addMockSystemService(WallpaperManager.class, mock(WallpaperManager.class));

        doNothing().when(mContext).sendBroadcast(any(), anyString());
        doNothing().when(mContext).sendBroadcastAsUser(any(), any());
        doNothing().when(mContext).sendBroadcastAsUser(any(), any(), any());
        doNothing().when(mContext).sendBroadcastMultiplePermissions(any(), any(), any(), any());
        doReturn(mContext).when(mContext).createContextAsUser(eq(mUser), anyInt());

        TestableContentResolver cr = mock(TestableContentResolver.class);
        when(mContext.getContentResolver()).thenReturn(cr);
        doNothing().when(cr).registerContentObserver(any(), anyBoolean(), any(), anyInt());

        when(mAppOpsManager.checkOpNoThrow(
                AppOpsManager.OP_SYSTEM_EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS, mUid,
                mPkg)).thenReturn(AppOpsManager.MODE_IGNORED);

        // Use this testable looper.
        mTestableLooper = TestableLooper.get(this);
        // MockPackageManager - default returns ApplicationInfo with matching calling UID
        mContext.setMockPackageManager(mPackageManagerClient);

        when(mPackageManager.getPackageUid(eq(mPkg), anyLong(), eq(mUserId))).thenReturn(mUid);
        when(mPackageManager.getPackageUid(eq(MISSING_PACKAGE), anyLong(), anyInt()))
                .thenReturn(INVALID_UID);
        when(mPackageManager.getApplicationInfo(anyString(), anyLong(), anyInt()))
                .thenAnswer((Answer<ApplicationInfo>) invocation -> {
                    Object[] args = invocation.getArguments();
                    return getApplicationInfo((String) args[0], mUid);
                });
        when(mPackageManagerClient.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenAnswer((Answer<ApplicationInfo>) invocation -> {
                    Object[] args = invocation.getArguments();
                    return getApplicationInfo((String) args[0], mUid);
                });
        when(mPackageManagerClient.getPackageUidAsUser(any(), anyInt())).thenReturn(mUid);
        when(mLightsManager.getLight(anyInt())).thenReturn(mock(LogicalLight.class));
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(false);
        when(mUgmInternal.newUriPermissionOwner(anyString())).thenReturn(mPermOwner);
        when(mPackageManager.getPackagesForUid(mUid)).thenReturn(new String[]{mPkg});
        when(mPackageManagerClient.getPackagesForUid(anyInt())).thenReturn(new String[]{mPkg});
        when(mAtm.getTaskToShowPermissionDialogOn(anyString(), anyInt()))
                .thenReturn(INVALID_TASK_ID);
        mContext.addMockSystemService(AppOpsManager.class, mock(AppOpsManager.class));
        // Defaults: when asked about profile IDs or parent profile ID for any user, return itself
        when(mUm.getProfileIds(anyInt(), anyBoolean())).thenAnswer(
                invocationOnMock -> new int[]{(int) invocationOnMock.getArguments()[0]});
        when(mUm.getEnabledProfileIds(anyInt())).thenAnswer(
                invocationOnMock -> new int[]{(int) invocationOnMock.getArguments()[0]});
        when(mUmInternal.getProfileIds(anyInt(), anyBoolean())).thenAnswer(
                invocationOnMock -> new int[]{(int) invocationOnMock.getArguments()[0]});
        when(mUmInternal.getProfileParentId(anyInt())).then(returnsFirstArg());
        when(mAmi.getCurrentUserId()).thenReturn(mUserId);

        when(mPackageManagerClient.hasSystemFeature(FEATURE_TELECOM)).thenReturn(true);

        // write to a test file; the system file isn't readable from tests
        mFile = new File(mContext.getCacheDir(), "test.xml");
        mFile.createNewFile();
        final String preupgradeXml = "<notification-policy></notification-policy>";
        mPolicyFile = new AtomicFile(mFile);
        FileOutputStream fos = mPolicyFile.startWrite();
        fos.write(preupgradeXml.getBytes());
        mPolicyFile.finishWrite(fos);
        mFile2 = new File(mContext.getCacheDir(), "test2.xml");
        mFile2.createNewFile();
        mRulesFile = new AtomicFile(mFile2);

        // Setup managed services
        when(mListeners.setPackageOrComponentEnabled(any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(true);
        when(mListeners.setPackageOrComponentEnabled(any(), anyInt(), anyBoolean(), anyBoolean(),
                anyBoolean())).thenReturn(true);
        when(mAssistants.setPackageOrComponentEnabled(any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(true);
        when(mAssistants.setPackageOrComponentEnabled(any(), anyInt(), anyBoolean(), anyBoolean(),
                anyBoolean())).thenReturn(true);
        when(mConditionProviders.setPackageOrComponentEnabled(any(), anyInt(), anyBoolean(),
                anyBoolean())).thenReturn(true);
        when(mConditionProviders.setPackageOrComponentEnabled(any(), anyInt(), anyBoolean(),
                anyBoolean(), anyBoolean())).thenReturn(true);
        when(mNlf.isTypeAllowed(anyInt())).thenReturn(true);
        when(mNlf.isPackageAllowed(any())).thenReturn(true);
        when(mNlf.isPackageAllowed(null)).thenReturn(true);
        when(mListeners.getNotificationListenerFilter(any())).thenReturn(mNlf);
        mListener = mListeners.new ManagedServiceInfo(
                null, new ComponentName(mPkg, "test_class"),
                mUserId, true, null, 0, 123);
        ComponentName defaultComponent = ComponentName.unflattenFromString("config/device");
        ArraySet<ComponentName> components = new ArraySet<>();
        components.add(defaultComponent);
        when(mListeners.getDefaultComponents()).thenReturn(components);
        when(mConditionProviders.getDefaultPackages())
                .thenReturn(new ArraySet<>(Arrays.asList("config")));
        when(mAssistants.getDefaultComponents()).thenReturn(components);
        when(mAssistants.queryPackageForServices(
                anyString(), anyInt(), anyInt())).thenReturn(components);
        when(mListeners.checkServiceTokenLocked(null)).thenReturn(mListener);
        ManagedServices.Config listenerConfig = new ManagedServices.Config();
        listenerConfig.xmlTag = NotificationListeners.TAG_ENABLED_NOTIFICATION_LISTENERS;
        when(mListeners.getConfig()).thenReturn(listenerConfig);
        ManagedServices.Config assistantConfig = new ManagedServices.Config();
        assistantConfig.xmlTag = NotificationAssistants.TAG_ENABLED_NOTIFICATION_ASSISTANTS;
        when(mAssistants.getConfig()).thenReturn(assistantConfig);
        ManagedServices.Config dndConfig = new ManagedServices.Config();
        dndConfig.xmlTag = ConditionProviders.TAG_ENABLED_DND_APPS;
        when(mConditionProviders.getConfig()).thenReturn(dndConfig);

        when(mAssistants.isAdjustmentAllowed(anyInt(), anyString())).thenReturn(true);

        // Use the real PowerManager to back up the mock w.r.t. creating WakeLocks.
        // This is because 1) we need a mock to verify() calls and tracking the created WakeLocks,
        // but 2) PowerManager and WakeLock perform their own checks (e.g. correct arguments, don't
        // call release twice, etc) and we want the test to fail if such misuse happens, too.
        PowerManager realPowerManager = mContext.getSystemService(PowerManager.class);
        when(mPowerManager.newWakeLock(anyInt(), anyString())).then(
                (Answer<WakeLock>) invocation -> {
                    WakeLock wl = realPowerManager.newWakeLock(invocation.getArgument(0),
                            invocation.getArgument(1));
                    mAcquiredWakeLocks.add(wl);
                    return wl;
                });

        // TODO (b/291907312): remove feature flag
        // NOTE: Prefer using the @EnableFlags annotation where possible. Do not add any android.app
        //  flags here.
        mSetFlagsRule.disableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);

        initNMS();
    }

    private void initNMS() throws Exception {
        initNMS(SystemService.PHASE_BOOT_COMPLETED);
    }

    @SuppressLint("MissingPermission")
    private void initNMS(int upToBootPhase) throws Exception {
        mService = new TestableNotificationManagerService(mContext, mTestableLooper);

        // apps allowed as convos
        mService.setStringArrayResourceValue(PKG_O);

        when(mUmInternal.isUserInitialized(anyInt())).thenReturn(true);

        mWorkerHandler = spy(mService.new WorkerHandler(mTestableLooper.getLooper()));
        mBroadcastsHandler = new Handler(mTestableLooper.getLooper());
        mGroupHelper = spy(mService.getGroupHelper());

        mService.init(mWorkerHandler, mRankingHandler, mBroadcastsHandler, mPackageManager,
                mPackageManagerClient, mLightsManager, mListeners, mAssistants, mConditionProviders,
                mCompanionMgr, mSnoozeHelper, mUsageStats, mPolicyFile, mRulesFile,
                mActivityManager, mGroupHelper, mAm, mAtm, mAppUsageStats, mDevicePolicyManager,
                mUgm, mUgmInternal, mAppOpsManager, mHistoryManager, mStatsManager, mAmi,
                mToastRateLimiter, mPermissionHelper, mock(UsageStatsManagerInternal.class),
                mTelecomManager, mLogger, mTestFlagResolver, mPermissionManager, mPowerManager,
                mock(PostNotificationTrackerFactory.class), mUiEventLogger, mBitmapOffloader,
                new NotificationListenerStats(MAX_CHANNELS_CREATED_BY_NLS_FOR_TESTING,
                        MAX_CHANNELS_CREATED_BY_NLS_FOR_TESTING),
                mNotificationRecordLogger,
                mNotificationInstanceIdSequence, new TestPreferencesHelperFactory());

        mService.setAttentionHelper(mAttentionHelper);
        mService.setLockPatternUtils(mock(LockPatternUtils.class));

        // make sure PreferencesHelper doesn't try to interact with any real caches
        PreferencesHelper prefHelper = spy(mService.mPreferencesHelper);
        doNothing().when(prefHelper).invalidateNotificationChannelCache();
        doNothing().when(prefHelper).invalidateNotificationChannelGroupCache();
        mService.setPreferencesHelper(prefHelper);

        // Return first true for RoleObserver main-thread check
        when(mMainLooper.isCurrentThread()).thenReturn(true).thenReturn(false);

        if (upToBootPhase >= SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY, mMainLooper);
        }

        Mockito.reset(mHistoryManager);
        verify(mHistoryManager, never()).onBootPhaseAppsCanStart();

        if (upToBootPhase >= SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            mService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START, mMainLooper);
            verify(mHistoryManager).onBootPhaseAppsCanStart();
        }

        mStrongAuthTracker = mService.new StrongAuthTrackerFake(mContext);
        mService.setStrongAuthTracker(mStrongAuthTracker);

        mShortcutHelper = mService.getShortcutHelper();
        mShortcutHelper.setLauncherApps(mLauncherApps);
        mShortcutHelper.setShortcutServiceInternal(mShortcutServiceInternal);
        mShortcutHelper.setUserManager(mUserManager);

        // Capture PackageIntentReceiver
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        ArgumentCaptor<IntentFilter> intentFilterCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);

        verify(mContext, atLeastOnce()).registerReceiverAsUser(broadcastReceiverCaptor.capture(),
                any(), intentFilterCaptor.capture(), any(), any());
        verify(mContext, atLeastOnce()).registerReceiver(broadcastReceiverCaptor.capture(),
                intentFilterCaptor.capture(), anyInt());
        verify(mContext, atLeastOnce()).registerReceiver(broadcastReceiverCaptor.capture(),
                intentFilterCaptor.capture());
        List<BroadcastReceiver> broadcastReceivers = broadcastReceiverCaptor.getAllValues();
        List<IntentFilter> intentFilters = intentFilterCaptor.getAllValues();

        for (int i = 0; i < intentFilters.size(); i++) {
            final IntentFilter filter = intentFilters.get(i);
            if (filter.hasAction(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED)
                    && filter.hasAction(Intent.ACTION_PACKAGES_UNSUSPENDED)
                    && filter.hasAction(Intent.ACTION_PACKAGES_SUSPENDED)) {
                mPackageIntentReceiver = broadcastReceivers.get(i);
            }
            if (filter.hasAction(Intent.ACTION_USER_STOPPED)
                    || filter.hasAction(Intent.ACTION_USER_SWITCHED)
                    || filter.hasAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                    || filter.hasAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)) {
                // There may be multiple receivers, get the NMS one
                if (broadcastReceivers.get(i).toString().contains(
                        NotificationManagerService.class.getName())) {
                    mUserIntentReceiver = broadcastReceivers.get(i);
                }
            }
        }
        assertNotNull("package intent receiver should exist", mPackageIntentReceiver);
        assertNotNull("User receiver should exist", mUserIntentReceiver);

        // Set the testable bubble extractor
        RankingHelper rankingHelper = mService.getRankingHelper();
        BubbleExtractor extractor = rankingHelper.findExtractor(BubbleExtractor.class);
        extractor.setActivityManager(mActivityManager);

        // Tests call directly into the Binder.
        mBinderService = mService.getBinderService();
        mInternalService = mService.getInternalService();

        clearInvocations(mRankingHandler);
        when(mPermissionHelper.hasPermission(anyInt())).thenReturn(true);

        var checker = mock(TestableNotificationManagerService.ComponentPermissionChecker.class);
        mService.permissionChecker = checker;
        when(checker.check(anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(PackageManager.PERMISSION_DENIED);
    }

    @After
    public void tearDown() throws Exception {
        if (mFile != null) mFile.delete();

        mService.clearNotifications();
        if (mTestableLooper != null) {
            mTestableLooper.processAllMessages();
        }

        try {
            mService.onDestroy();
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "failed to destroy", e);
            // can throw if a broadcast receiver was never registered
        }

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
        if (mWorkerHandler != null) {
            // Remove scheduled messages that would be processed when the test is already
            // done, and could cause issues, for example, messages that remove/cancel shown
            // toasts (this causes problematic interactions with mocks when they're no longer
            // working as expected).
            mWorkerHandler.removeCallbacksAndMessages(null);
        }
        if (mBroadcastsHandler != null) {
            mBroadcastsHandler.removeCallbacksAndMessages(null);
        }

        if (mTestableLooper != null) {
            // Must remove static reference to this test object to prevent leak (b/261039202)
            mTestableLooper.remove(this);
        }
    }

    public void waitForIdle() {
        if (mTestableLooper != null) {
            mTestableLooper.processAllMessages();
        }
    }

    private void moveTimeForwardAndWaitForIdle(final long timeMs) {
        mTestableLooper.moveTimeForward(timeMs);
        waitForIdle();
    }

    private static Intent isIntentWithAction(String wantedAction) {
        return argThat(
                intent -> intent != null && wantedAction.equals(intent.getAction())
        );
    }

    /** Prepares for a zen-related test that uses a mocked {@link ZenModeHelper}. */
    private ZenModeHelper setUpMockZenTest() {
        ZenModeHelper zenModeHelper = mock(ZenModeHelper.class);
        mService.setZenHelper(zenModeHelper);
        when(mConditionProviders.isPackageOrComponentAllowed(anyString(), anyInt()))
                .thenReturn(true);
        when(zenModeHelper.getActivityInfo(any())).thenReturn(new ActivityInfo());
        when(zenModeHelper.getServiceInfo(any())).thenReturn(new ServiceInfo());
        if (splitSoundVibrationForNotificationBreakthrough()) {
            when(zenModeHelper.getNotificationPolicy(any())).thenReturn(
                    getSplitSoundVibrationNotificationPolicy()
            );
        }
        return zenModeHelper;
    }

    private Policy getSplitSoundVibrationNotificationPolicy() {
        return new NotificationManager.Policy(
                0, 0, 0,
                NotificationManager.Policy.SUPPRESSED_EFFECTS_UNSET,
                NotificationManager.Policy.STATE_UNSET,
                NotificationManager.Policy.CONVERSATION_SENDERS_UNSET,
                0, 0 // sound and vibration
        );
    }

    /** Prepares for a zen-related test that uses the real {@link ZenModeHelper}. */
    private void setUpRealZenTest() throws Exception {
        when(mConditionProviders.isPackageOrComponentAllowed(anyString(), anyInt()))
                .thenReturn(true);

        int iconResId = 79;
        String iconResName = "icon_79";
        String pkg = mContext.getPackageName();
        ApplicationInfo appInfoSpy = spy(new ApplicationInfo());
        appInfoSpy.icon = iconResId;
        when(appInfoSpy.loadLabel(any())).thenReturn("Test App");
        when(mPackageManagerClient.getApplicationInfo(eq(pkg), anyInt())).thenReturn(appInfoSpy);

        when(mResources.getResourceName(eq(iconResId))).thenReturn(iconResName);
        when(mResources.getIdentifier(eq(iconResName), any(), any())).thenReturn(iconResId);
        when(mPackageManagerClient.getResourcesForApplication(eq(pkg))).thenReturn(mResources);

        // Ensure that there is a zen configuration for the user running the test (won't be
        // USER_SYSTEM if running on HSUM).
        mService.mZenModeHelper.onUserSwitched(mUserId);
    }


    @Test
    public void testSetDndAccessForUser() throws Exception {
        UserHandle user = UserHandle.of(mContext.getUserId() + 10);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        mBinderService.setNotificationPolicyAccessGrantedForUser(
                c.getPackageName(), user.getIdentifier(), true);

        verify(mContext, times(1)).sendBroadcastAsUser(any(), eq(user), any());
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.getPackageName(), user.getIdentifier(), true, true);
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(mListeners, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetDndAccess() throws Exception {
        ComponentName c = ComponentName.unflattenFromString("package/Component");

        mBinderService.setNotificationPolicyAccessGranted(c.getPackageName(), true);

        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.getPackageName(), mContext.getUserId(), true, true);
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(mListeners, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetDndAccess_onLowRam() throws Exception {
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        mBinderService.setNotificationPolicyAccessGranted(c.getPackageName(), true);

        verify(mListeners).migrateToXml();
        verify(mConditionProviders).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mAssistants).migrateToXml();
        verify(mAssistants).resetDefaultAssistantsIfNecessary();
    }

    @Test
    public void testSetDndAccess_doesNothingOnLowRam_exceptWatch() throws Exception {
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(true);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        ComponentName c = ComponentName.unflattenFromString("package/Component");

        mBinderService.setNotificationPolicyAccessGranted(c.getPackageName(), true);

        verify(mListeners, never()).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.getPackageName(), mContext.getUserId(), true, true);
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetNotificationPolicy_preP_setOldFields() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        Policy userPolicy =
                new Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(zenModeHelper.getNotificationPolicy(any())).thenReturn(userPolicy);

        Policy appPolicy = new Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_SCREEN_OFF);

        int expected = SUPPRESSED_EFFECT_BADGE
                | SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_SCREEN_OFF
                | SUPPRESSED_EFFECT_PEEK | SUPPRESSED_EFFECT_LIGHTS
                | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, O_MR1);

        assertEquals(expected, actual);
    }

    @Test
    public void testSetNotificationPolicy_preP_setNewFields() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        Policy userPolicy =
                new Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(zenModeHelper.getNotificationPolicy(any())).thenReturn(userPolicy);

        Policy appPolicy = new Policy(0, 0, 0,
                SUPPRESSED_EFFECT_NOTIFICATION_LIST);

        int expected = SUPPRESSED_EFFECT_BADGE;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, O_MR1);

        assertEquals(expected, actual);
    }

    @Test
    public void testSetNotificationPolicy_preP_setOldNewFields() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        Policy userPolicy =
                new Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(zenModeHelper.getNotificationPolicy(any())).thenReturn(userPolicy);

        Policy appPolicy = new Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_STATUS_BAR);

        int expected =
                SUPPRESSED_EFFECT_BADGE | SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_PEEK;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, O_MR1);

        assertEquals(expected, actual);
    }

    @Test
    public void testSetNotificationPolicy_P_setOldFields() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        Policy userPolicy =
                new Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(zenModeHelper.getNotificationPolicy(any())).thenReturn(userPolicy);

        Policy appPolicy = new Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_SCREEN_OFF);

        int expected = SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_SCREEN_OFF
                | SUPPRESSED_EFFECT_PEEK | SUPPRESSED_EFFECT_AMBIENT
                | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, P);

        assertEquals(expected, actual);
    }

    @Test
    public void testSetNotificationPolicy_P_setNewFields() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        Policy userPolicy =
                new Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(zenModeHelper.getNotificationPolicy(any())).thenReturn(userPolicy);

        Policy appPolicy = new Policy(0, 0, 0,
                SUPPRESSED_EFFECT_NOTIFICATION_LIST | SUPPRESSED_EFFECT_AMBIENT
                        | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT);

        int expected = SUPPRESSED_EFFECT_NOTIFICATION_LIST | SUPPRESSED_EFFECT_SCREEN_OFF
                | SUPPRESSED_EFFECT_AMBIENT | SUPPRESSED_EFFECT_LIGHTS
                | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, P);

        assertEquals(expected, actual);
    }

    @Test
    public void testSetNotificationPolicy_P_setOldNewFields() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        Policy userPolicy =
                new Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(zenModeHelper.getNotificationPolicy(any())).thenReturn(userPolicy);

        Policy appPolicy = new Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_STATUS_BAR);

        int expected =  SUPPRESSED_EFFECT_STATUS_BAR;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, P);

        assertEquals(expected, actual);

        appPolicy = new Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_AMBIENT
                        | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT);

        expected =  SUPPRESSED_EFFECT_SCREEN_OFF | SUPPRESSED_EFFECT_AMBIENT
                | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
        actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, P);

        assertEquals(expected, actual);
    }

    @Test
    public void clearDefaultDnDPackageShouldEnableIt() throws RemoteException {
        ArrayMap<Boolean, ArrayList<ComponentName>> changed = new ArrayMap<>();
        changed.put(true, new ArrayList<>());
        changed.put(false, new ArrayList<>());

        when(mAssistants.resetComponents(anyString(), anyInt())).thenReturn(changed);
        when(mListeners.resetComponents(anyString(), anyInt())).thenReturn(changed);
        mService.getBinderService().clearData("pkgName", 0, false);
        verify(mConditionProviders, times(1)).resetPackage(
                        eq("pkgName"), eq(0));
    }

    @Test
    public void testAutomaticZenRuleValidation_policyFilterAgreement() throws Exception {
        setUpMockZenTest();
        ComponentName owner = new ComponentName(mContext, this.getClass());
        ZenPolicy zenPolicy = new ZenPolicy.Builder().allowAlarms(true).build();
        boolean isEnabled = true;
        AutomaticZenRule badRule = new AutomaticZenRule("test", owner, owner, mock(Uri.class),
                zenPolicy, NotificationManager.INTERRUPTION_FILTER_NONE, isEnabled);

        IllegalArgumentException expected = assertThrows(IllegalArgumentException.class,
                () -> mBinderService.addAutomaticZenRule(badRule, mContext.getPackageName(),
                        false));
        assertThat(expected).hasMessageThat().isEqualTo(
                "ZenPolicy is only applicable to INTERRUPTION_FILTER_PRIORITY filters");

        AutomaticZenRule goodRule = new AutomaticZenRule("test", owner, owner, mock(Uri.class),
                zenPolicy, NotificationManager.INTERRUPTION_FILTER_PRIORITY, isEnabled);
        mBinderService.addAutomaticZenRule(goodRule, mContext.getPackageName(), false);

        goodRule = new AutomaticZenRule("test", owner, owner, mock(Uri.class),
                null, NotificationManager.INTERRUPTION_FILTER_NONE, isEnabled);
        mBinderService.addAutomaticZenRule(goodRule, mContext.getPackageName(), false);
    }

    @Test
    public void testAddAutomaticZenRule_systemCallTakesPackageFromOwner() throws Exception {
        mService.isSystemUid = true;

        ZenModeHelper zenModeHelper = setUpMockZenTest();
        when(mPmi.isSameApp(eq("com.android.settings"), anyLong(), eq(mUid), eq(mUserId)))
                .thenReturn(true);

        ComponentName owner = new ComponentName("android", "ProviderName");
        ZenPolicy zenPolicy = new ZenPolicy.Builder().allowAlarms(true).build();
        boolean isEnabled = true;
        AutomaticZenRule rule = new AutomaticZenRule("test", owner, owner, mock(Uri.class),
                zenPolicy, NotificationManager.INTERRUPTION_FILTER_PRIORITY, isEnabled);
        mBinderService.addAutomaticZenRule(rule, "com.android.settings", false);

        // verify that zen mode helper gets passed in a package name of "android"
        verify(zenModeHelper).addAutomaticZenRule(any(), eq("android"), eq(rule),
                eq(ZenModeConfig.ORIGIN_SYSTEM), anyString(), anyInt());
    }

    @Test
    public void testAddAutomaticZenRule_systemAppIdCallTakesPackageFromOwner() throws Exception {
        // The multi-user case: where the calling uid doesn't match the system uid, but the calling
        // *appid* is the system.
        when(mPmi.isSameApp("com.android.settings", 0L, mUid, mUserId))
                .thenReturn(true);
        mService.isSystemUid = false;
        mService.isSystemAppId = true;
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        ComponentName owner = new ComponentName("android", "ProviderName");
        ZenPolicy zenPolicy = new ZenPolicy.Builder().allowAlarms(true).build();
        boolean isEnabled = true;
        AutomaticZenRule rule = new AutomaticZenRule("test", owner, owner, mock(Uri.class),
                zenPolicy, NotificationManager.INTERRUPTION_FILTER_PRIORITY, isEnabled);
        mBinderService.addAutomaticZenRule(rule, "com.android.settings", false);

        // verify that zen mode helper gets passed in a package name of "android"
        verify(zenModeHelper).addAutomaticZenRule(any(), eq("android"), eq(rule),
                eq(ZenModeConfig.ORIGIN_SYSTEM), anyString(), anyInt());
    }

    @Test
    public void testAddAutomaticZenRule_nonSystemCallTakesPackageFromArg() throws Exception {
        mService.isSystemUid = false;
        mService.isSystemAppId = false;
        when(mPmi.isSameApp(eq("another.package"), anyLong(), eq(mUid), anyInt()))
                .thenReturn(true);

        ZenModeHelper zenModeHelper = setUpMockZenTest();
        ComponentName owner = new ComponentName("android", "ProviderName");
        ZenPolicy zenPolicy = new ZenPolicy.Builder().allowAlarms(true).build();
        boolean isEnabled = true;
        AutomaticZenRule rule = new AutomaticZenRule("test", owner, owner, mock(Uri.class),
                zenPolicy, NotificationManager.INTERRUPTION_FILTER_PRIORITY, isEnabled);
        mBinderService.addAutomaticZenRule(rule, "another.package", false);

        // verify that zen mode helper gets passed in the package name from the arg, not the owner
        verify(zenModeHelper).addAutomaticZenRule(any(), eq("another.package"), eq(rule),
                eq(ZenModeConfig.ORIGIN_APP), anyString(), anyInt());
    }

    @Test
    public void testAddAutomaticZenRule_typeManagedCanBeUsedByDeviceOwners() throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.setCallerIsNormalPackage();

        AutomaticZenRule rule = new AutomaticZenRule.Builder("rule", Uri.parse("uri"))
                .setType(AutomaticZenRule.TYPE_MANAGED)
                .setOwner(new ComponentName(mPkg, "cls"))
                .build();
        when(mDevicePolicyManager.isActiveDeviceOwner(anyInt())).thenReturn(true);

        mBinderService.addAutomaticZenRule(rule, mPkg, /* fromUser= */ false);

        verify(zenModeHelper).addAutomaticZenRule(any(), eq(mPkg), eq(rule), anyInt(), any(),
                anyInt());
    }

    @Test
    public void testAddAutomaticZenRule_typeManagedCanBeUsedBySystem() throws Exception {
        addAutomaticZenRule_restrictedRuleTypeCanBeUsedBySystem(AutomaticZenRule.TYPE_MANAGED);
    }

    @Test
    public void testAddAutomaticZenRule_typeManagedCannotBeUsedByRegularApps() throws Exception {
        addAutomaticZenRule_restrictedRuleTypeCannotBeUsedByRegularApps(
                AutomaticZenRule.TYPE_MANAGED);
    }

    @Test
    public void testAddAutomaticZenRule_typeBedtimeCanBeUsedByWellbeing() throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.setCallerIsNormalPackage();
        when(mResources
                .getString(R.string.config_systemWellbeing))
                .thenReturn(mPkg);
        when(mContext.getResources()).thenReturn(mResources);

        AutomaticZenRule rule = new AutomaticZenRule.Builder("rule", Uri.parse("uri"))
                .setType(AutomaticZenRule.TYPE_BEDTIME)
                .setOwner(new ComponentName(mPkg, "cls"))
                .build();

        mBinderService.addAutomaticZenRule(rule, mPkg, /* fromUser= */ false);

        verify(zenModeHelper).addAutomaticZenRule(any(), eq(mPkg), eq(rule), anyInt(), any(),
                anyInt());
    }

    @Test
    public void testAddAutomaticZenRule_typeBedtimeCanBeUsedBySystem() throws Exception {
        addAutomaticZenRule_restrictedRuleTypeCanBeUsedBySystem(AutomaticZenRule.TYPE_BEDTIME);
    }

    @Test
    public void testAddAutomaticZenRule_typeBedtimeCannotBeUsedByRegularApps() throws Exception {
        addAutomaticZenRule_restrictedRuleTypeCannotBeUsedByRegularApps(
                AutomaticZenRule.TYPE_BEDTIME);
    }

    private void addAutomaticZenRule_restrictedRuleTypeCanBeUsedBySystem(
            @AutomaticZenRule.Type int ruleType) throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.isSystemUid = true;

        AutomaticZenRule rule = new AutomaticZenRule.Builder("rule", Uri.parse("uri"))
                .setType(ruleType)
                .setOwner(new ComponentName(mPkg, "cls"))
                .build();
        when(mDevicePolicyManager.isActiveDeviceOwner(anyInt())).thenReturn(true);

        mBinderService.addAutomaticZenRule(rule, mPkg, /* fromUser= */ false);

        verify(zenModeHelper).addAutomaticZenRule(any(), eq(mPkg), eq(rule), anyInt(), any(),
                anyInt());
    }

    private void addAutomaticZenRule_restrictedRuleTypeCannotBeUsedByRegularApps(
            @AutomaticZenRule.Type int ruleType) {
        mService.setCallerIsNormalPackage();
        setUpMockZenTest();

        AutomaticZenRule rule = new AutomaticZenRule.Builder("rule", Uri.parse("uri"))
                .setType(ruleType)
                .setOwner(new ComponentName(mPkg, "cls"))
                .build();
        when(mDevicePolicyManager.isActiveDeviceOwner(anyInt())).thenReturn(false);

        IllegalArgumentException expected = assertThrows(IllegalArgumentException.class,
                () -> mBinderService.addAutomaticZenRule(rule, mPkg, /* fromUser= */ false));
        assertThat(expected).hasMessageThat().contains("can use AutomaticZenRules with TYPE_");
    }

    @Test
    public void addAutomaticZenRule_fromUser_mappedToOriginUser() throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.isSystemUid = true;
        when(mPmi.isSameApp("pkg", 0L, mUid, mUserId)).thenReturn(true);

        mBinderService.addAutomaticZenRule(SOME_ZEN_RULE, "pkg", /* fromUser= */ true);

        verify(zenModeHelper).addAutomaticZenRule(any(), eq("pkg"), eq(SOME_ZEN_RULE),
                eq(ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI), anyString(), anyInt());
    }

    @Test
    public void addAutomaticZenRule_fromSystemNotUser_mappedToOriginSystem() throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.isSystemUid = true;
        when(mPmi.isSameApp("pkg", 0L, mUid, mUserId)).thenReturn(true);

        mBinderService.addAutomaticZenRule(SOME_ZEN_RULE, "pkg", /* fromUser= */ false);

        verify(zenModeHelper).addAutomaticZenRule(any(), eq("pkg"), eq(SOME_ZEN_RULE),
                eq(ZenModeConfig.ORIGIN_SYSTEM), anyString(), anyInt());
    }

    @Test
    public void addAutomaticZenRule_fromApp_mappedToOriginApp() throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.setCallerIsNormalPackage();
        when(mPmi.isSameApp("pkg", 0L, mUid, mUserId)).thenReturn(true);

        mBinderService.addAutomaticZenRule(SOME_ZEN_RULE, "pkg", /* fromUser= */ false);

        verify(zenModeHelper).addAutomaticZenRule(any(), eq("pkg"), eq(SOME_ZEN_RULE),
                eq(ZenModeConfig.ORIGIN_APP), anyString(), anyInt());
    }

    @Test
    public void addAutomaticZenRule_fromAppFromUser_blocked() throws Exception {
        setUpMockZenTest();
        mService.setCallerIsNormalPackage();

        assertThrows(SecurityException.class, () ->
                mBinderService.addAutomaticZenRule(SOME_ZEN_RULE, "pkg", /* fromUser= */ true));
    }

    @Test
    public void updateAutomaticZenRule_fromUserFromSystem_allowed() throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.isSystemUid = true;

        mBinderService.updateAutomaticZenRule("id", SOME_ZEN_RULE, /* fromUser= */ true);

        verify(zenModeHelper).updateAutomaticZenRule(any(), eq("id"), eq(SOME_ZEN_RULE),
                eq(ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI), anyString(), anyInt());
    }

    @Test
    public void updateAutomaticZenRule_fromUserFromApp_blocked() throws Exception {
        setUpMockZenTest();
        mService.setCallerIsNormalPackage();

        assertThrows(SecurityException.class, () ->
                mBinderService.addAutomaticZenRule(SOME_ZEN_RULE, "pkg", /* fromUser= */ true));
    }

    @Test
    public void removeAutomaticZenRule_fromUserFromSystem_allowed() throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.isSystemUid = true;

        mBinderService.removeAutomaticZenRule("id", /* fromUser= */ true);

        verify(zenModeHelper).removeAutomaticZenRule(any(), eq("id"),
                eq(ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI), anyString(), anyInt());
    }

    @Test
    public void removeAutomaticZenRule_fromUserFromApp_blocked() throws Exception {
        setUpMockZenTest();
        mService.setCallerIsNormalPackage();

        assertThrows(SecurityException.class, () ->
                mBinderService.removeAutomaticZenRule("id", /* fromUser= */ true));
    }

    @Test
    public void setAutomaticZenRuleState_fromAppWithConditionFromUser_originUserInApp()
            throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.setCallerIsNormalPackage();

        Condition withSourceUser = new Condition(Uri.parse("uri"), "summary", STATE_TRUE,
                SOURCE_USER_ACTION);
        mBinderService.setAutomaticZenRuleState("id", withSourceUser);

        verify(zenModeHelper).setAutomaticZenRuleState(any(), eq("id"), eq(withSourceUser),
                eq(ZenModeConfig.ORIGIN_USER_IN_APP), anyInt());
    }

    @Test
    public void setAutomaticZenRuleState_fromAppWithConditionNotFromUser_originApp()
            throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.setCallerIsNormalPackage();

        Condition withSourceContext = new Condition(Uri.parse("uri"), "summary", STATE_TRUE,
                SOURCE_CONTEXT);
        mBinderService.setAutomaticZenRuleState("id", withSourceContext);

        verify(zenModeHelper).setAutomaticZenRuleState(any(), eq("id"), eq(withSourceContext),
                eq(ZenModeConfig.ORIGIN_APP), anyInt());
    }

    @Test
    public void setAutomaticZenRuleState_fromSystemWithConditionFromUser_originUserInSystemUi()
            throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.isSystemUid = true;

        Condition withSourceContext = new Condition(Uri.parse("uri"), "summary", STATE_TRUE,
                SOURCE_USER_ACTION);
        mBinderService.setAutomaticZenRuleState("id", withSourceContext);

        verify(zenModeHelper).setAutomaticZenRuleState(any(), eq("id"), eq(withSourceContext),
                eq(ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI), anyInt());
    }
    @Test
    public void setAutomaticZenRuleState_fromSystemWithConditionNotFromUser_originSystem()
            throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.isSystemUid = true;

        Condition withSourceContext = new Condition(Uri.parse("uri"), "summary", STATE_TRUE,
                SOURCE_CONTEXT);
        mBinderService.setAutomaticZenRuleState("id", withSourceContext);

        verify(zenModeHelper).setAutomaticZenRuleState(any(), eq("id"), eq(withSourceContext),
                eq(ZenModeConfig.ORIGIN_SYSTEM), anyInt());
    }


    @Test
    public void getAutomaticZenRules_fromSystem_readsWithCurrentUser() throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.isSystemUid = true;

        // Representative used to verify getCallingZenUser().
        mBinderService.getAutomaticZenRules();

        verify(zenModeHelper).getAutomaticZenRules(eq(UserHandle.CURRENT), anyInt());
    }

    @Test
    public void getAutomaticZenRules_fromNormalPackage_readsWithBinderUser() throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.setCallerIsNormalPackage();

        // Representative used to verify getCallingZenUser().
        mBinderService.getAutomaticZenRules();

        verify(zenModeHelper).getAutomaticZenRules(eq(Binder.getCallingUserHandle()), anyInt());
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_ENABLE_DND_SYNC)
    public void internalService_getAutomaticZenRules() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        UserHandle user = UserHandle.of(1);

        mService.getInternalService().getAutomaticZenRules(user);

        verify(zenModeHelper).getAutomaticZenRules(eq(user), anyInt());
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_ENABLE_DND_SYNC)
    public void internalService_isAutomaticZenRuleActive() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        UserHandle user = UserHandle.of(1);
        String id = "id";

        mService.getInternalService().isAutomaticZenRuleActive(user, id);

        verify(zenModeHelper).getAutomaticZenRuleState(eq(user), eq(id), anyInt());
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_ENABLE_DND_SYNC)
    public void internalService_isManualZenRuleActive() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        UserHandle user = UserHandle.of(1);

        mService.getInternalService().isManualZenRuleActive(user);

        verify(zenModeHelper).getManualZenMode(user);
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_ENABLE_DND_SYNC)
    public void internalService_setManualZenRuleActive() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        UserHandle user = UserHandle.of(1);

        mService.getInternalService().setManualZenRuleActive(user, true);

        verify(zenModeHelper).setManualZenMode(
                eq(user),
                eq(ZEN_MODE_IMPORTANT_INTERRUPTIONS),
                isNull(),
                anyInt(),
                anyString(),
                isNull(),
                anyInt());
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_ENABLE_DND_SYNC)
    public void internalService_setAutomaticZenRuleActive() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        UserHandle user = UserHandle.of(1);
        String id = "id";
        AutomaticZenRule rule =
                new AutomaticZenRule(
                        "name",
                        /* owner= */ null,
                        /* configurationActivity= */ null,
                        /* conditionId= */ Uri.EMPTY,
                        /* policy= */ null,
                        /* interruptionFilter= */ NotificationManager
                        .INTERRUPTION_FILTER_PRIORITY,
                        /* enabled= */ true);
        when(zenModeHelper.getAutomaticZenRule(eq(user), eq(id), anyInt())).thenReturn(rule);

        mService.getInternalService().setAutomaticZenRuleActive(user, id, true);

        // Verify that the condition is set to true.
        ArgumentCaptor<Condition> conditionCaptor = ArgumentCaptor.forClass(Condition.class);
        verify(zenModeHelper).setAutomaticZenRuleState(
                eq(user),
                eq(id),
                conditionCaptor.capture(),
                anyInt(),
                anyInt());
        assertThat(conditionCaptor.getValue().state).isEqualTo(STATE_TRUE);
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_ENABLE_DND_SYNC)
    public void internalService_addCallback() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        Callback callback = new Callback();

        mService.getInternalService().addZenModeCallback(callback);

        verify(zenModeHelper).addCallback(callback);
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_ENABLE_DND_SYNC)
    public void internalService_hasZenModeConfig() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        UserHandle user = UserHandle.of(1);

        mService.getInternalService().hasZenModeConfig(user);

        verify(zenModeHelper).hasZenModeConfig(user);
    }

    @Test
    public void onZenModeChanged_sendsBroadcasts() throws Exception {
        when(mAmi.getCurrentUserId()).thenReturn(100);
        when(mUmInternal.getProfileIds(eq(100), anyBoolean())).thenReturn(new int[]{100, 101, 102});
        when(mConditionProviders.getAllowedPackages(anyInt())).then(new Answer<List<String>>() {
            @Override
            public List<String> answer(InvocationOnMock invocation) {
                int userId = invocation.getArgument(0);
                switch (userId) {
                    case 100:
                        return Lists.newArrayList("a", "b", "c");
                    case 101:
                        return Lists.newArrayList();
                    case 102:
                        return Lists.newArrayList("b");
                    default:
                        throw new IllegalArgumentException(
                                "Why would you ask for packages of userId " + userId + "?");
                }
            }
        });
        Context context100 = mock(Context.class);
        doReturn(context100).when(mContext).createContextAsUser(eq(UserHandle.of(100)), anyInt());
        Context context101 = mock(Context.class);
        doReturn(context101).when(mContext).createContextAsUser(eq(UserHandle.of(101)), anyInt());
        Context context102 = mock(Context.class);
        doReturn(context102).when(mContext).createContextAsUser(eq(UserHandle.of(102)), anyInt());

        mService.getBinderService().setZenMode(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS, null,
                "testing!", false);
        waitForIdle();

        // Verify broadcasts per user: registered receivers first, then DND packages.
        InOrder inOrder = inOrder(context100, context101, context102);

        inOrder.verify(context100).sendBroadcastMultiplePermissions(
                eqIntent(new Intent(ACTION_INTERRUPTION_FILTER_CHANGED)
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)),
                eq(new String[0]), eq(new String[0]), eq(new String[] {"a", "b", "c"}));
        inOrder.verify(context100).sendBroadcast(
                eqIntent(new Intent(ACTION_INTERRUPTION_FILTER_CHANGED)
                        .setPackage("a")
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT)));
        inOrder.verify(context100).sendBroadcast(
                eqIntent(new Intent(ACTION_INTERRUPTION_FILTER_CHANGED)
                        .setPackage("b")
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT)));
        inOrder.verify(context100).sendBroadcast(
                eqIntent(new Intent(ACTION_INTERRUPTION_FILTER_CHANGED)
                        .setPackage("c")
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT)));

        inOrder.verify(context101).sendBroadcastMultiplePermissions(
                eqIntent(new Intent(ACTION_INTERRUPTION_FILTER_CHANGED)
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)),
                eq(new String[0]), eq(new String[0]), eq(new String[] {}));

        inOrder.verify(context102).sendBroadcastMultiplePermissions(
                eqIntent(new Intent(ACTION_INTERRUPTION_FILTER_CHANGED)
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)),
                eq(new String[0]), eq(new String[0]), eq(new String[] {"b"}));
        inOrder.verify(context102).sendBroadcast(
                eqIntent(new Intent(ACTION_INTERRUPTION_FILTER_CHANGED)
                        .setPackage("b")
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT)));
    }

    @Test
    public void onAutomaticRuleStatusChanged_sendsBroadcastToRuleOwner() throws Exception {
        mService.mZenModeHelper.getCallbacks().forEach(c -> c.onAutomaticRuleStatusChanged(
                mUserId, "rule.owner.pkg", "rule_id", AUTOMATIC_RULE_STATUS_ACTIVATED));

        Intent expected = new Intent(ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED)
                .setPackage("rule.owner.pkg")
                .putExtra(EXTRA_AUTOMATIC_ZEN_RULE_ID, "rule_id")
                .putExtra(EXTRA_AUTOMATIC_ZEN_RULE_STATUS, AUTOMATIC_RULE_STATUS_ACTIVATED)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

        verify(mContext).sendBroadcastAsUser(eqIntent(expected), eq(UserHandle.of(mUserId)));
    }

    @Test
    public void onConfigApplied_sendsInternalZenChangedBroadcast() throws Exception {
        mService.mZenModeHelper.getCallbacks().forEach(c -> c.onConfigApplied());

        Intent expected = new Intent(NotificationManager.ACTION_ZEN_CONFIGURATION_CHANGED_INTERNAL)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

        verify(mContext).sendBroadcastAsUser(eqIntent(expected), eq(UserHandle.ALL),
                eq(Manifest.permission.MANAGE_NOTIFICATIONS));
    }

    @Test
    public void testLocaleChangedCallsUpdateDefaultZenModeRules() throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.mLocaleChangeReceiver.onReceive(mContext,
                new Intent(Intent.ACTION_LOCALE_CHANGED));

        verify(zenModeHelper).updateZenRulesOnLocaleChange();
    }

    @Test
    @EnableFlags(Flags.FLAG_USE_SSM_USER_SWITCH_SIGNAL)
    public void onUserSwitched_updatesZenModeAndChannelsBypassingDnd() {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.setPreferencesHelper(mPreferencesHelper);

        UserInfo prevUser = new UserInfo();
        prevUser.id = 10;
        UserInfo newUser = new UserInfo();
        newUser.id = 20;

        mService.onUserSwitching(new TargetUser(prevUser), new TargetUser(newUser));

        InOrder inOrder = inOrder(mPreferencesHelper, mService.mZenModeHelper);
        inOrder.verify(zenModeHelper).onUserSwitched(eq(20));
        inOrder.verify(mPreferencesHelper).syncHasPriorityChannels();
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisableFlags(Flags.FLAG_USE_SSM_USER_SWITCH_SIGNAL)
    public void onUserSwitched_broadcast_updatesZenModeAndChannelsBypassingDnd() {
        Intent intent = new Intent(Intent.ACTION_USER_SWITCHED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, 20);
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.setPreferencesHelper(mPreferencesHelper);

        mUserIntentReceiver.onReceive(mContext, intent);

        InOrder inOrder = inOrder(mPreferencesHelper, mService.mZenModeHelper);
        inOrder.verify(zenModeHelper).onUserSwitched(eq(20));
        inOrder.verify(mPreferencesHelper).syncHasPriorityChannels();
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @EnableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void onUserStopped_callBackToListeners() {
        Intent intent = new Intent(Intent.ACTION_USER_STOPPED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, 20);

        mUserIntentReceiver.onReceive(mContext, intent);

        verify(mConditionProviders).onUserStopped(eq(20));
        verify(mListeners).onUserStopped(eq(20));
        verify(mAssistants).onUserStopped(eq(20));
    }

    @Test
    @DisableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void isNotificationPolicyAccessGranted_invalidPackage() throws Exception {
        final String notReal = "NOT REAL";
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(notReal), anyInt())).thenThrow(
                PackageManager.NameNotFoundException.class);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(notReal)).isFalse();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(notReal), eq(mUserId));
        verify(checker, never()).check(any(), anyInt(), anyInt(), anyBoolean());
        verify(mConditionProviders, never()).isPackageOrComponentAllowed(eq(notReal), anyInt());
        verify(mListeners, never()).isComponentEnabledForPackage(any());
        verify(mDevicePolicyManager, never()).isActiveDeviceOwner(anyInt());
    }

    @Test
    @EnableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void isNotificationPolicyAccessGranted_invalidPackage_concurrent_multiUser()
                throws Exception {
        final String notReal = "NOT REAL";
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(notReal), anyInt())).thenThrow(
                PackageManager.NameNotFoundException.class);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(notReal)).isFalse();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(notReal), eq(mUserId));
        verify(checker, never()).check(any(), anyInt(), anyInt(), anyBoolean());
        verify(mConditionProviders, never()).isPackageOrComponentAllowed(eq(notReal), anyInt());
        verify(mListeners, never()).isComponentEnabledForPackage(any(), anyInt());
        verify(mDevicePolicyManager, never()).isActiveDeviceOwner(anyInt());
    }

    @Test
    @DisableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void isNotificationPolicyAccessGranted_hasPermission() throws Exception {
        final String packageName = "target";
        final int uid = 123;
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(packageName), anyInt())).thenReturn(uid);
        when(checker.check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(packageName)).isTrue();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(packageName), eq(mUserId));
        verify(checker).check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true);
        verify(mConditionProviders, never()).isPackageOrComponentAllowed(eq(packageName), anyInt());
        verify(mListeners, never()).isComponentEnabledForPackage(any());
        verify(mDevicePolicyManager, never()).isActiveDeviceOwner(anyInt());
    }

    @Test
    @EnableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void isNotificationPolicyAccessGranted_hasPermission_concurrent_multiUser()
                throws Exception {
        final String packageName = "target";
        final int uid = 123;
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(packageName), anyInt())).thenReturn(uid);
        when(checker.check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(packageName)).isTrue();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(packageName), eq(mUserId));
        verify(checker).check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true);
        verify(mConditionProviders, never()).isPackageOrComponentAllowed(eq(packageName), anyInt());
        verify(mListeners, never()).isComponentEnabledForPackage(any(), anyInt());
        verify(mDevicePolicyManager, never()).isActiveDeviceOwner(anyInt());
    }

    @Test
    @DisableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void isNotificationPolicyAccessGranted_isConditionProvider() throws Exception {
        final String packageName = "target";
        final int uid = 123;
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(packageName), anyInt())).thenReturn(uid);
        when(mConditionProviders.isPackageOrComponentAllowed(eq(packageName), anyInt()))
                .thenReturn(true);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(packageName)).isTrue();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(packageName), eq(mUserId));
        verify(checker).check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true);
        verify(mConditionProviders).isPackageOrComponentAllowed(eq(packageName), eq(mUserId));
        verify(mListeners, never()).isComponentEnabledForPackage(any());
        verify(mDevicePolicyManager, never()).isActiveDeviceOwner(anyInt());
    }

    @Test
    @EnableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void isNotificationPolicyAccessGranted_isConditionProvider_concurrent_multiUser()
                throws Exception {
        final String packageName = "target";
        final int uid = 123;
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(packageName), anyInt())).thenReturn(uid);
        when(mConditionProviders.isPackageOrComponentAllowed(eq(packageName), anyInt()))
                .thenReturn(true);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(packageName)).isTrue();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(packageName), eq(mUserId));
        verify(checker).check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true);
        verify(mConditionProviders).isPackageOrComponentAllowed(eq(packageName), eq(mUserId));
        verify(mListeners, never()).isComponentEnabledForPackage(any(), anyInt());
        verify(mDevicePolicyManager, never()).isActiveDeviceOwner(anyInt());
    }

    @Test
    @DisableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void isNotificationPolicyAccessGranted_isDeviceOwner() throws Exception {
        final String packageName = "target";
        final int uid = 123;
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(packageName), anyInt())).thenReturn(uid);
        when(mDevicePolicyManager.isActiveDeviceOwner(uid)).thenReturn(true);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(packageName)).isTrue();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(packageName), eq(mUserId));
        verify(checker).check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true);
        verify(mConditionProviders).isPackageOrComponentAllowed(eq(packageName), eq(mUserId));
        verify(mListeners, never()).isComponentEnabledForPackage(packageName);
        verify(mDevicePolicyManager).isActiveDeviceOwner(uid);
    }

    @Test
    @EnableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void isNotificationPolicyAccessGranted_isDeviceOwner_concurrent_multiUser()
            throws Exception {
        final String packageName = "target";
        final int uid = 123;
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(packageName), anyInt())).thenReturn(uid);
        when(mDevicePolicyManager.isActiveDeviceOwner(uid)).thenReturn(true);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(packageName)).isTrue();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(packageName), eq(mUserId));
        verify(checker).check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true);
        verify(mConditionProviders).isPackageOrComponentAllowed(eq(packageName), eq(mUserId));
        verify(mListeners, never()).isComponentEnabledForPackage(packageName, mUserId);
        verify(mDevicePolicyManager).isActiveDeviceOwner(uid);
    }

    /**
     * b/292163859
     */
    @Test
    @DisableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void isNotificationPolicyAccessGranted_callerIsDeviceOwner() throws Exception {
        final String packageName = "target";
        final int uid = 123;
        final int callingUid = Binder.getCallingUid();
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(packageName), anyInt())).thenReturn(uid);
        when(mDevicePolicyManager.isActiveDeviceOwner(callingUid)).thenReturn(true);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(packageName)).isFalse();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(packageName), eq(mUserId));
        verify(checker).check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true);
        verify(mConditionProviders).isPackageOrComponentAllowed(eq(packageName), eq(mUserId));
        verify(mListeners, never()).isComponentEnabledForPackage(packageName);
        verify(mDevicePolicyManager).isActiveDeviceOwner(uid);
        verify(mDevicePolicyManager, never()).isActiveDeviceOwner(callingUid);
    }

    /**
     * b/292163859
     */
    @Test
    @EnableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void isNotificationPolicyAccessGranted_callerIsDeviceOwner_concurrent_multiUser()
                throws Exception {
        final String packageName = "target";
        final int uid = 123;
        final int callingUid = Binder.getCallingUid();
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(packageName), anyInt())).thenReturn(uid);
        when(mDevicePolicyManager.isActiveDeviceOwner(callingUid)).thenReturn(true);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(packageName)).isFalse();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(packageName), eq(mUserId));
        verify(checker).check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true);
        verify(mConditionProviders).isPackageOrComponentAllowed(eq(packageName), eq(mUserId));
        verify(mListeners, never()).isComponentEnabledForPackage(packageName, mUserId);
        verify(mDevicePolicyManager).isActiveDeviceOwner(uid);
        verify(mDevicePolicyManager, never()).isActiveDeviceOwner(callingUid);
    }

    @Test
    @DisableFlags(FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER)
    public void isNotificationPolicyAccessGranted_notGranted() throws Exception {
        final String packageName = "target";
        final int uid = 123;
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(packageName), anyInt())).thenReturn(uid);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(packageName)).isFalse();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(packageName), eq(mUserId));
        verify(checker).check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true);
        verify(mConditionProviders).isPackageOrComponentAllowed(eq(packageName), eq(mUserId));
        verify(mListeners, never()).isComponentEnabledForPackage(any());
        verify(mListeners, never()).isComponentEnabledForPackage(any(), anyInt());
        verify(mDevicePolicyManager).isActiveDeviceOwner(uid);
    }

    @Test
    @EnableFlags({FLAG_MANAGED_SERVICES_CONCURRENT_MULTIUSER})
    public void isNotificationPolicyAccessGranted_notGranted_concurrent_multiUser()
                throws Exception {
        final String packageName = "target";
        final int uid = 123;
        final var checker = mService.permissionChecker;

        when(mPackageManagerClient.getPackageUidAsUser(eq(packageName), anyInt())).thenReturn(uid);

        assertThat(mBinderService.isNotificationPolicyAccessGranted(packageName)).isFalse();
        verify(mPackageManagerClient).getPackageUidAsUser(eq(packageName), eq(mUserId));
        verify(checker).check(Manifest.permission.MANAGE_NOTIFICATIONS, uid, -1, true);
        verify(mConditionProviders).isPackageOrComponentAllowed(eq(packageName), eq(mUserId));
        verify(mListeners, never()).isComponentEnabledForPackage(any());
        verify(mListeners, never()).isComponentEnabledForPackage(any(), anyInt());
        verify(mDevicePolicyManager).isActiveDeviceOwner(uid);
    }

    @Test
    public void testResetDefaultDnd() {
        TestableNotificationManagerService service = spy(mService);
        UserInfo user = new UserInfo(0, "owner", 0);
        when(mUm.getAliveUsers()).thenReturn(List.of(user));
        doReturn(false).when(service).isDNDMigrationDone(anyInt());

        service.resetDefaultDndIfNecessary();

        verify(mConditionProviders, times(1)).removeDefaultFromConfig(user.id);
        verify(mConditionProviders, times(1)).resetDefaultFromConfig();
        verify(service, times(1)).allowDndPackages(user.id);
        verify(service, times(1)).setDNDMigrationDone(user.id);
    }

    @Test
    public void setDeviceEffectsApplier_succeeds() throws Exception {
        initNMS(SystemService.PHASE_SYSTEM_SERVICES_READY);

        mInternalService.setDeviceEffectsApplier(mock(DeviceEffectsApplier.class));

        mService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START, mMainLooper);
        // No exception!
    }

    @Test
    public void setDeviceEffectsApplier_tooLate_throws() throws Exception {
        initNMS(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        assertThrows(IllegalStateException.class, () ->
                mInternalService.setDeviceEffectsApplier(mock(DeviceEffectsApplier.class)));
    }

    @Test
    public void setDeviceEffectsApplier_calledTwice_throws() throws Exception {
        initNMS(SystemService.PHASE_SYSTEM_SERVICES_READY);

        mInternalService.setDeviceEffectsApplier(mock(DeviceEffectsApplier.class));
        assertThrows(IllegalStateException.class, () ->
                mInternalService.setDeviceEffectsApplier(mock(DeviceEffectsApplier.class)));
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setNotificationPolicy_mappedToImplicitRule() throws RemoteException {
        mService.setCallerIsNormalPackage();
        when(mPmi.isSameApp("package", 0L, mUid, mUserId)).thenReturn(true);

        ZenModeHelper zenModeHelper = setUpMockZenTest();

        NotificationManager.Policy policy = (splitSoundVibrationForNotificationBreakthrough())
                ? getSplitSoundVibrationNotificationPolicy()
                : new NotificationManager.Policy(0, 0, 0);
        mBinderService.setNotificationPolicy("package", policy, false);

        verify(zenModeHelper).applyGlobalPolicyAsImplicitZenRule(any(), eq("package"), anyInt(),
                eq(policy));
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    @EnableFlags(android.service.notification.Flags
            .FLAG_SPLIT_SOUND_VIBRATION_FOR_NOTIFICATION_BREAKTHROUGH)
    public void setNotificationPolicy_mappedToImplicitRule_splitSoundVibration_defaultCtor()
            throws RemoteException {
        mService.setCallerIsNormalPackage();
        when(mPmi.isSameApp("package", 0L, mUid, mUserId)).thenReturn(true);

        ZenModeHelper zenModeHelper = setUpMockZenTest();

        NotificationManager.Policy policy = new NotificationManager.Policy(0, 0, 0);
        mBinderService.setNotificationPolicy("package", policy, false);

        NotificationManager.Policy expectedPolicy = getSplitSoundVibrationNotificationPolicy();

        verify(zenModeHelper).applyGlobalPolicyAsImplicitZenRule(any(), eq("package"), anyInt(),
                eq(expectedPolicy));
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setNotificationPolicy_systemCaller_setsGlobalPolicy() throws RemoteException {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.isSystemUid = true;
        when(mPmi.isSameApp("package", 0L, mUid, mUserId)).thenReturn(true);

        NotificationManager.Policy policy = (splitSoundVibrationForNotificationBreakthrough())
                ? getSplitSoundVibrationNotificationPolicy()
                : new NotificationManager.Policy(0, 0, 0);
        mBinderService.setNotificationPolicy("package", policy, false);

        verify(zenModeHelper).setNotificationPolicy(any(), eq(policy), anyInt(), anyInt());
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    @EnableFlags(android.service.notification.Flags
            .FLAG_SPLIT_SOUND_VIBRATION_FOR_NOTIFICATION_BREAKTHROUGH)
    public void
    setNotificationPolicy_systemCaller_setsGlobalPolicy_splitSoundVibration_defaultCtor()
            throws RemoteException {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.isSystemUid = true;
        when(mPmi.isSameApp("package", 0L, mUid, mUserId)).thenReturn(true);

        NotificationManager.Policy policy = new NotificationManager.Policy(0, 0, 0);
        mBinderService.setNotificationPolicy("package", policy, false);

        NotificationManager.Policy expectedPolicy = getSplitSoundVibrationNotificationPolicy();
        verify(zenModeHelper).setNotificationPolicy(any(), eq(expectedPolicy), anyInt(), anyInt());
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setNotificationPolicy_watchCompanionApp_setsGlobalPolicy()
            throws RemoteException {
        setNotificationPolicy_dependingOnCompanionAppDevice_maySetGlobalPolicy(
                AssociationRequest.DEVICE_PROFILE_WATCH, true,
                splitSoundVibrationForNotificationBreakthrough());
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    @EnableFlags(android.service.notification.Flags
            .FLAG_SPLIT_SOUND_VIBRATION_FOR_NOTIFICATION_BREAKTHROUGH)
    public void
    setNotificationPolicy_watchCompanionApp_setsGlobalPolicy_splitSoundVibration_defaultCtor()
            throws RemoteException {
        setNotificationPolicy_dependingOnCompanionAppDevice_maySetGlobalPolicy(
                AssociationRequest.DEVICE_PROFILE_WATCH, true, false);
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setNotificationPolicy_autoCompanionApp_setsGlobalPolicy()
            throws RemoteException {
        setNotificationPolicy_dependingOnCompanionAppDevice_maySetGlobalPolicy(
                AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION, true,
                splitSoundVibrationForNotificationBreakthrough());
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    @EnableFlags(android.service.notification.Flags
            .FLAG_SPLIT_SOUND_VIBRATION_FOR_NOTIFICATION_BREAKTHROUGH)
    public void
    setNotificationPolicy_autoCompanionApp_setsGlobalPolicy_splitSoundVibration_defaultCtor()
            throws RemoteException {
        setNotificationPolicy_dependingOnCompanionAppDevice_maySetGlobalPolicy(
                AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION, true, false);
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setNotificationPolicy_otherCompanionApp_doesNotSetGlobalPolicy()
            throws RemoteException {
        setNotificationPolicy_dependingOnCompanionAppDevice_maySetGlobalPolicy(
                AssociationRequest.DEVICE_PROFILE_NEARBY_DEVICE_STREAMING, false,
                splitSoundVibrationForNotificationBreakthrough());
    }

    private void setNotificationPolicy_dependingOnCompanionAppDevice_maySetGlobalPolicy(
            @AssociationRequest.DeviceProfile String deviceProfile, boolean canSetGlobalPolicy,
            boolean splitSoundVibrationPolicy)
            throws RemoteException {
        mService.setCallerIsNormalPackage();
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        when(mCompanionMgr.getAssociations(anyString(), anyInt()))
                .thenReturn(ImmutableList.of(
                        new AssociationInfo.Builder(1, mUserId, "package")
                                .setDisplayName("My connected device")
                                .setDeviceProfile(deviceProfile)
                                .build()));
        when(mPmi.isSameApp("package", 0L, mUid, mUserId)).thenReturn(true);

        NotificationManager.Policy policy = (splitSoundVibrationPolicy)
                ? getSplitSoundVibrationNotificationPolicy()
                : new Policy(0, 0, 0);
        mBinderService.setNotificationPolicy("package", policy, false);

        NotificationManager.Policy expectedPolicy =
                (splitSoundVibrationForNotificationBreakthrough())
                        ? getSplitSoundVibrationNotificationPolicy()
                        : new Policy(0, 0, 0);

        if (canSetGlobalPolicy) {
            verify(zenModeHelper).setNotificationPolicy(any(), eq(expectedPolicy), anyInt(),
                    anyInt());
        } else {
            verify(zenModeHelper).applyGlobalPolicyAsImplicitZenRule(any(), anyString(), anyInt(),
                    eq(expectedPolicy));
        }
    }

    @Test
    @DisableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setNotificationPolicy_withoutCompat_setsGlobalPolicy() throws RemoteException {
        mService.setCallerIsNormalPackage();

        ZenModeHelper zenModeHelper = setUpMockZenTest();

        when(mPmi.isSameApp("package", 0L, mUid, mUserId)).thenReturn(true);

        NotificationManager.Policy policy = (splitSoundVibrationForNotificationBreakthrough())
                ? getSplitSoundVibrationNotificationPolicy()
                : new NotificationManager.Policy(0, 0, 0);
        mBinderService.setNotificationPolicy("package", policy, false);

        verify(zenModeHelper).setNotificationPolicy(any(), eq(policy), anyInt(), anyInt());
    }

    @Test
    @DisableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    @EnableFlags(android.service.notification.Flags
            .FLAG_SPLIT_SOUND_VIBRATION_FOR_NOTIFICATION_BREAKTHROUGH)
    public void
    setNotificationPolicy_withoutCompat_setsGlobalPolicy_splitSoundVibration_defaultCtor()
            throws RemoteException {
        mService.setCallerIsNormalPackage();

        ZenModeHelper zenModeHelper = setUpMockZenTest();

        when(mPmi.isSameApp("package", 0L, mUid, mUserId)).thenReturn(true);

        NotificationManager.Policy policy = new Policy(0, 0, 0);
        mBinderService.setNotificationPolicy("package", policy, false);

        NotificationManager.Policy expectedPolicy = getSplitSoundVibrationNotificationPolicy();

        verify(zenModeHelper).setNotificationPolicy(any(), eq(expectedPolicy), anyInt(), anyInt());
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void getNotificationPolicy_mappedFromImplicitRule() throws RemoteException {
        mService.setCallerIsNormalPackage();

        ZenModeHelper zenModeHelper = setUpMockZenTest();

        when(mPmi.getPackageUid("package", 0L, mUserId)).thenReturn(mUid);

        mBinderService.getNotificationPolicy("package");

        verify(zenModeHelper).getNotificationPolicyFromImplicitZenRule(any(), eq("package"));
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setInterruptionFilter_mappedToImplicitRule() throws RemoteException {
        mService.setCallerIsNormalPackage();

        ZenModeHelper zenModeHelper = setUpMockZenTest();

        when(mPmi.isSameApp("package", 0L, mUid, mUserId)).thenReturn(true);

        mBinderService.setInterruptionFilter("package", INTERRUPTION_FILTER_PRIORITY, false);

        verify(zenModeHelper).applyGlobalZenModeAsImplicitZenRule(any(), eq("package"), anyInt(),
                eq(ZEN_MODE_IMPORTANT_INTERRUPTIONS));
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setInterruptionFilter_systemCaller_setsGlobalPolicy() throws RemoteException {
        mService.setCallerIsNormalPackage();
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.isSystemUid = true;
        when(mPmi.isSameApp("package", 0L, mUid, mUserId)).thenReturn(true);

        mBinderService.setInterruptionFilter("package", INTERRUPTION_FILTER_PRIORITY, false);

        verify(zenModeHelper).setManualZenMode(any(), eq(ZEN_MODE_IMPORTANT_INTERRUPTIONS),
                eq(null), eq(ZenModeConfig.ORIGIN_SYSTEM), anyString(), eq("package"), anyInt());
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setInterruptionFilter_watchCompanionApp_setsGlobalZen() throws RemoteException {
        setInterruptionFilter_dependingOnCompanionAppDevice_maySetGlobalZen(
                AssociationRequest.DEVICE_PROFILE_WATCH, true);
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setInterruptionFilter_autoCompanionApp_setsGlobalZen() throws RemoteException {
        setInterruptionFilter_dependingOnCompanionAppDevice_maySetGlobalZen(
                AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION, true);
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setInterruptionFilter_otherCompanionApp_doesNotSetGlobalZen()
            throws RemoteException {
        setInterruptionFilter_dependingOnCompanionAppDevice_maySetGlobalZen(
                AssociationRequest.DEVICE_PROFILE_NEARBY_DEVICE_STREAMING, false);
    }

    private void setInterruptionFilter_dependingOnCompanionAppDevice_maySetGlobalZen(
            @AssociationRequest.DeviceProfile String deviceProfile, boolean canSetGlobalPolicy)
            throws RemoteException {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        mService.setCallerIsNormalPackage();
        when(mCompanionMgr.getAssociations(anyString(), anyInt()))
                .thenReturn(ImmutableList.of(
                        new AssociationInfo.Builder(1, mUserId, "package")
                                .setDisplayName("My connected device")
                                .setDeviceProfile(deviceProfile)
                                .build()));
        when(mPmi.isSameApp("package", 0L, mUid, mUserId)).thenReturn(true);

        mBinderService.setInterruptionFilter("package", INTERRUPTION_FILTER_PRIORITY, false);

        if (canSetGlobalPolicy) {
            verify(zenModeHelper).setManualZenMode(any(), eq(ZEN_MODE_IMPORTANT_INTERRUPTIONS),
                    eq(null), eq(ZenModeConfig.ORIGIN_APP), anyString(), eq("package"), anyInt());
        } else {
            verify(zenModeHelper).applyGlobalZenModeAsImplicitZenRule(any(), anyString(), anyInt(),
                    eq(ZEN_MODE_IMPORTANT_INTERRUPTIONS));
        }
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void requestInterruptionFilterFromListener_fromApp_doesNotSetGlobalZen()
            throws Exception {
        mService.setCallerIsNormalPackage();
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        ManagedServices.ManagedServiceInfo info = mock(ManagedServices.ManagedServiceInfo.class);
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(info);
        info.component = new ComponentName("pkg", "cls");

        mBinderService.requestInterruptionFilterFromListener(mock(INotificationListener.class),
                INTERRUPTION_FILTER_PRIORITY);

        verify(zenModeHelper).applyGlobalZenModeAsImplicitZenRule(any(), eq("pkg"),
                eq(mUid), eq(ZEN_MODE_IMPORTANT_INTERRUPTIONS));
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void requestInterruptionFilterFromListener_fromSystem_setsGlobalZen()
            throws Exception {
        mService.isSystemUid = true;
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        ManagedServices.ManagedServiceInfo info = mock(ManagedServices.ManagedServiceInfo.class);
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(info);
        info.component = new ComponentName("pkg", "cls");

        mBinderService.requestInterruptionFilterFromListener(mock(INotificationListener.class),
                INTERRUPTION_FILTER_PRIORITY);

        verify(zenModeHelper).setManualZenMode(any(),
                eq(ZEN_MODE_IMPORTANT_INTERRUPTIONS), eq(null), eq(ZenModeConfig.ORIGIN_SYSTEM),
                anyString(), eq("pkg"), eq(mUid));
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void updateAutomaticZenRule_implicitRuleWithoutCPS_disallowedFromApp() throws Exception {
        setUpRealZenTest();
        mService.setCallerIsNormalPackage();
        assertThat(mBinderService.getAutomaticZenRules().getList()).isEmpty();

        // Create an implicit zen rule by calling setNotificationPolicy from an app.
        mBinderService.setNotificationPolicy(mPkg, new Policy(0, 0, 0), false);
        assertThat(mBinderService.getAutomaticZenRules().getList()).hasSize(1);
        AutomaticZenRule.AzrWithId rule = getOnlyElement(
                (List<AutomaticZenRule.AzrWithId>) mBinderService.getAutomaticZenRules().getList());
        assertThat(rule.mRule.getOwner()).isNull();
        assertThat(rule.mRule.getConfigurationActivity()).isNull();

        // Now try to update said rule (e.g. disable it). Should fail.
        // We also validate the exception message because NPE could be thrown by all sorts of test
        // issues (e.g. misconfigured mocks).
        rule.mRule.setEnabled(false);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> mBinderService.updateAutomaticZenRule(rule.mId, rule.mRule, false));
        assertThat(e.getMessage()).isEqualTo(
                "Rule must have a ConditionProviderService and/or configuration activity");
    }

    @Test
    @EnableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void updateAutomaticZenRule_implicitRuleWithoutCPS_allowedFromSystem() throws Exception {
        setUpRealZenTest();
        mService.setCallerIsNormalPackage();
        assertThat(mBinderService.getAutomaticZenRules().getList()).isEmpty();

        // Create an implicit zen rule by calling setNotificationPolicy from an app.
        mBinderService.setNotificationPolicy(mPkg, new Policy(0, 0, 0), false);
        assertThat(mBinderService.getAutomaticZenRules().getList()).hasSize(1);
        AutomaticZenRule.AzrWithId rule = getOnlyElement(
                (List<AutomaticZenRule.AzrWithId>) mBinderService.getAutomaticZenRules().getList());
        assertThat(rule.mRule.getOwner()).isNull();
        assertThat(rule.mRule.getConfigurationActivity()).isNull();

        // Now update said rule from Settings (e.g. disable it). Should work!
        mService.isSystemUid = true;
        rule.mRule.setEnabled(false);
        mBinderService.updateAutomaticZenRule(rule.mId, rule.mRule, false);

        AutomaticZenRule.AzrWithId updatedRule = getOnlyElement(
                (List<AutomaticZenRule.AzrWithId>) mBinderService.getAutomaticZenRules().getList());
        assertThat(updatedRule.mRule.isEnabled()).isFalse();
    }

    @Test
    public void setNotificationPolicy_fromSystemApp_appliesPriorityChannelsAllowed()
            throws Exception {
        setUpRealZenTest();
        // Start with hasPriorityChannels=true, allowPriorityChannels=true ("default").
        mService.mZenModeHelper.setNotificationPolicy(UserHandle.CURRENT,
                new Policy(0, 0, 0, 0, Policy.policyState(true, true), 0),
                ZenModeConfig.ORIGIN_SYSTEM, Process.SYSTEM_UID);

        // The caller will supply states with "wrong" hasPriorityChannels.
        int stateBlockingPriorityChannels = Policy.policyState(false, false);
        mBinderService.setNotificationPolicy(mPkg,
                new Policy(1, 0, 0, 0, stateBlockingPriorityChannels, 0), false);

        // hasPriorityChannels is untouched and allowPriorityChannels was updated.
        assertThat(mBinderService.getNotificationPolicy(mPkg).priorityCategories).isEqualTo(1);
        assertThat(mBinderService.getNotificationPolicy(mPkg).state).isEqualTo(
                Policy.policyState(true, false));

        // Same but setting allowPriorityChannels to true.
        int stateAllowingPriorityChannels = Policy.policyState(false, true);
        mBinderService.setNotificationPolicy(mPkg,
                new Policy(2, 0, 0, 0, stateAllowingPriorityChannels, 0), false);

        assertThat(mBinderService.getNotificationPolicy(mPkg).priorityCategories).isEqualTo(2);
        assertThat(mBinderService.getNotificationPolicy(mPkg).state).isEqualTo(
                Policy.policyState(true, true));
    }

    @Test
    @DisableCompatChanges(NotificationManagerService.MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES)
    public void setNotificationPolicy_fromRegularAppThatCanModifyPolicy_ignoresState()
            throws Exception {
        setUpRealZenTest();
        // Start with hasPriorityChannels=true, allowPriorityChannels=true ("default").
        mService.mZenModeHelper.setNotificationPolicy(UserHandle.CURRENT,
                new Policy(0, 0, 0, 0, Policy.policyState(true, true), 0),
                ZenModeConfig.ORIGIN_SYSTEM, Process.SYSTEM_UID);
        mService.setCallerIsNormalPackage();

        mBinderService.setNotificationPolicy(mPkg,
                new Policy(1, 0, 0, 0, Policy.policyState(false, false), 0), false);

        // Policy was updated but the attempt to change state was ignored (it's a @hide API).
        assertThat(mBinderService.getNotificationPolicy(mPkg).priorityCategories).isEqualTo(1);
        assertThat(mBinderService.getNotificationPolicy(mPkg).state).isEqualTo(
                Policy.policyState(true, true));
    }

    @Test
    @EnableFlags(android.service.notification.Flags
            .FLAG_SPLIT_SOUND_VIBRATION_FOR_NOTIFICATION_BREAKTHROUGH)
    public void setNotificationPolicy_splitSoundVibration_overwritesGranularSoundAndVibration()
            throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        int priorityCategories = Policy.PRIORITY_CATEGORY_ALARMS | Policy.PRIORITY_CATEGORY_CALLS;
        // current policy -- only allow vibration for alarms
        Policy currentUserPolicy = new Policy(
                priorityCategories, 0, 0, 0, 0, 0,
                Policy.PRIORITY_CATEGORY_CALLS, priorityCategories);
        when(zenModeHelper.getNotificationPolicy(any())).thenReturn(currentUserPolicy);

        // new policy -- allow all breakthrough for both alarms and calls
        Policy appPolicy = new Policy(
                priorityCategories, 0, 0, 0, 0, 0,
                priorityCategories, priorityCategories);
        mBinderService.setNotificationPolicy(mPkg, appPolicy, false);

        ArgumentCaptor<Policy> policyCaptor = ArgumentCaptor.forClass(Policy.class);
        verify(zenModeHelper).setNotificationPolicy(any(), policyCaptor.capture(), anyInt(),
                anyInt());

        Policy resultingPolicy = policyCaptor.getValue();
        assertThat(resultingPolicy.allowSoundForPriorityCategory).isEqualTo(priorityCategories);
        assertThat(resultingPolicy.allowVibrationForPriorityCategory).isEqualTo(priorityCategories);
    }

    @Test
    @EnableFlags(android.service.notification.Flags
            .FLAG_SPLIT_SOUND_VIBRATION_FOR_NOTIFICATION_BREAKTHROUGH)
    public void
    setNotificationPolicy_splitSoundVibration_enable_preservesGranularSoundAndVibration()
            throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        int priorityCategories = Policy.PRIORITY_CATEGORY_ALARMS | Policy.PRIORITY_CATEGORY_CALLS;
        // current policy -- only allow vibration for alarms
        Policy currentUserPolicy = new Policy(
                priorityCategories, 0, 0, 0, 0, 0,
                Policy.PRIORITY_CATEGORY_CALLS, priorityCategories);
        when(zenModeHelper.getNotificationPolicy(any())).thenReturn(currentUserPolicy);

        // new policy -- no granular settings
        Policy appPolicy = new Policy(priorityCategories, 0, 0, 0, 0);
        mBinderService.setNotificationPolicy(mPkg, appPolicy, false);

        ArgumentCaptor<Policy> policyCaptor = ArgumentCaptor.forClass(Policy.class);
        verify(zenModeHelper).setNotificationPolicy(any(), policyCaptor.capture(), anyInt(),
                anyInt());

        Policy resultingPolicy = policyCaptor.getValue();
        assertThat(resultingPolicy.allowSoundForPriorityCategory).isEqualTo(
                Policy.PRIORITY_CATEGORY_CALLS);
        assertThat(resultingPolicy.allowVibrationForPriorityCategory)
                .isEqualTo(priorityCategories);
    }

    @Test
    @EnableFlags(android.service.notification.Flags
            .FLAG_SPLIT_SOUND_VIBRATION_FOR_NOTIFICATION_BREAKTHROUGH)
    public void
    setNotificationPolicy_splitSoundVibration_disable_overwritesGranularSoundAndVibration()
            throws Exception {
        ZenModeHelper zenModeHelper = setUpMockZenTest();
        int priorityCategories = Policy.PRIORITY_CATEGORY_ALARMS | Policy.PRIORITY_CATEGORY_CALLS;
        // current policy -- only allow vibration for calls
        Policy currentUserPolicy = new Policy(
                priorityCategories, 0, 0, 0, 0, 0,
                Policy.PRIORITY_CATEGORY_ALARMS, priorityCategories);
        when(zenModeHelper.getNotificationPolicy(any())).thenReturn(currentUserPolicy);

        // new policy -- no granular settings
        int newPriorityCategories =
                Policy.PRIORITY_CATEGORY_CALLS | Policy.PRIORITY_CATEGORY_REMINDERS;
        Policy appPolicy = new Policy(newPriorityCategories, 0, 0, 0, 0);
        mBinderService.setNotificationPolicy(mPkg, appPolicy, false);

        ArgumentCaptor<Policy> policyCaptor = ArgumentCaptor.forClass(Policy.class);
        verify(zenModeHelper).setNotificationPolicy(any(), policyCaptor.capture(), anyInt(),
                anyInt());

        Policy resultingPolicy = policyCaptor.getValue();
        assertThat(resultingPolicy.allowSoundForPriorityCategory).isEqualTo(
                Policy.PRIORITY_CATEGORY_REMINDERS);
        assertThat(resultingPolicy.allowVibrationForPriorityCategory).isEqualTo(
                newPriorityCategories);
    }


    @Test
    public void testGetEffectsSuppressor_noSuppressor() throws Exception {
        when(mUmInternal.getProfileIds(anyInt(), anyBoolean())).thenReturn(new int[]{mUserId});
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(mListener);
        assertThat(mBinderService.getEffectsSuppressor()).isNull();
    }

    @Test
    public void testGetEffectsSuppressor_suppressorSameApp() throws Exception {
        when(mUmInternal.getProfileIds(anyInt(), anyBoolean())).thenReturn(new int[]{mUserId});
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(mListener);
        mService.isSystemUid = false;
        mService.isSystemAppId = false;
        mBinderService.requestHintsFromListener(mock(INotificationListener.class),
                HINT_HOST_DISABLE_EFFECTS);
        assertThat(mBinderService.getEffectsSuppressor()).isEqualTo(mListener.component);
    }

    @Test
    public void testGetEffectsSuppressor_suppressorDiffApp() throws Exception {
        when(mUmInternal.getProfileIds(anyInt(), anyBoolean())).thenReturn(new int[]{mUserId});
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(mListener);
        mService.isSystemUid = false;
        mService.isSystemAppId = false;
        mBinderService.requestHintsFromListener(mock(INotificationListener.class),
                HINT_HOST_DISABLE_EFFECTS);
        when(mPmi.isSameApp(anyString(), anyLong(), anyInt(), anyInt())).thenReturn(false);
        assertThat(mBinderService.getEffectsSuppressor()).isEqualTo(null);
    }

    @Test
    public void testGetEffectsSuppressor_suppressorDiffAppSystemCaller() throws Exception {
        when(mUmInternal.getProfileIds(anyInt(), anyBoolean())).thenReturn(new int[]{mUserId});
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(mListener);
        mService.isSystemUid = true;
        mBinderService.requestHintsFromListener(mock(INotificationListener.class),
                HINT_HOST_DISABLE_EFFECTS);
        when(mPmi.isSameApp(anyString(), anyLong(), anyInt(), anyInt())).thenReturn(false);
        assertThat(mBinderService.getEffectsSuppressor()).isEqualTo(mListener.component);
    }

    @Test
    public void requestHintsFromListener_changingEffectsButNotSuppressor_noBroadcast()
            throws Exception {
        when(mUmInternal.getProfileIds(anyInt(), anyBoolean())).thenReturn(new int[]{mUserId});
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(mListener);
        INotificationListener token = mock(INotificationListener.class);
        mService.isSystemUid = true;

        mBinderService.requestHintsFromListener(token, HINT_HOST_DISABLE_CALL_EFFECTS);
        moveTimeForwardAndWaitForIdle(500); // more than ZEN_BROADCAST_DELAY

        verify(mContext, times(1)).sendBroadcastMultiplePermissions(
                isIntentWithAction(ACTION_EFFECTS_SUPPRESSOR_CHANGED), any(), any(), any());

        // Same suppressor suppresses something else.
        mBinderService.requestHintsFromListener(token, HINT_HOST_DISABLE_NOTIFICATION_EFFECTS);
        moveTimeForwardAndWaitForIdle(500); // more than ZEN_BROADCAST_DELAY

        // Still 1 total calls (the previous one).
        verify(mContext, times(1)).sendBroadcastMultiplePermissions(
                isIntentWithAction(ACTION_EFFECTS_SUPPRESSOR_CHANGED), any(), any(), any());
    }

    @Test
    public void requestHintsFromListener_changingSuppressor_throttlesBroadcast() throws Exception {
        when(mUmInternal.getProfileIds(anyInt(), anyBoolean())).thenReturn(new int[]{mUserId});
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(mListener);
        INotificationListener token = mock(INotificationListener.class);
        mService.isSystemUid = true;

        // Several updates in quick succession.
        mBinderService.requestHintsFromListener(token, HINT_HOST_DISABLE_CALL_EFFECTS);
        mBinderService.clearRequestedListenerHints(token);
        mBinderService.requestHintsFromListener(token, HINT_HOST_DISABLE_NOTIFICATION_EFFECTS);
        mBinderService.clearRequestedListenerHints(token);
        mBinderService.requestHintsFromListener(token, HINT_HOST_DISABLE_CALL_EFFECTS);
        mBinderService.clearRequestedListenerHints(token);
        mBinderService.requestHintsFromListener(token, HINT_HOST_DISABLE_NOTIFICATION_EFFECTS);

        // No broadcasts yet!
        verify(mContext, never()).sendBroadcastMultiplePermissions(any(), any(), any(), any());

        moveTimeForwardAndWaitForIdle(500); // more than ZEN_BROADCAST_DELAY

        // Only one broadcast after idle time.
        verify(mContext, times(1)).sendBroadcastMultiplePermissions(
                isIntentWithAction(ACTION_EFFECTS_SUPPRESSOR_CHANGED), any(), any(), any());
    }
}
