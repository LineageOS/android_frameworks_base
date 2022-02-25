/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.content.pm.UserInfo.FLAG_ADMIN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.trust.TrustManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.provider.Settings;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.internal.util.EmergencyAffordanceManager;
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.deviceentry.data.repository.FakeDeviceEntryRepository;
import com.android.systemui.deviceentry.shared.model.DeviceUnlockStatus;
import com.android.systemui.controls.dagger.ControlsComponent;
import com.android.systemui.display.data.repository.FakeDisplayWindowPropertiesRepository;
import com.android.systemui.globalactions.data.repository.FakeGlobalActionsRepository;
import com.android.systemui.globalactions.domain.interactor.GlobalActionsInteractor;
import com.android.systemui.globalactions.shared.model.GlobalActionType;
import com.android.systemui.globalactions.shared.model.GlobalActionsEvent;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.user.domain.interactor.UserLogoutInteractor;
import com.android.systemui.util.RingerModeLiveData;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.FakeGlobalSettings;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kotlinx.coroutines.test.TestScope;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class GlobalActionsDialogLiteTest extends SysuiTestCase {

    private static final long TIMEOUT_IN_SECONDS = 5L;

    @Mock private GlobalActions.GlobalActionsManager mWindowManagerFuncs;
    @Mock private AudioManager mAudioManager;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private BroadcastSender mBroadcastSender;
    @Mock private TelephonyListenerManager mTelephonyListenerManager;
    @Mock private Resources mResources;
    @Mock private ConfigurationController mConfigurationController;
    @Mock private ActivityStarter mActivityStarter;
    @Mock private UserTracker mUserTracker;
    @Mock private UserManager mUserManager;
    @Mock private TrustManager mTrustManager;
    @Mock private IActivityManager mActivityManager;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private StatusBarWindowController mStatusBarWindowController;
    @Mock private StatusBarWindowControllerStore mStatusBarWindowControllerStore;
    @Mock private IWindowManager mWindowManager;
    @Mock private RingerModeTracker mRingerModeTracker;
    @Mock private RingerModeLiveData mRingerModeLiveData;
    @Mock private PackageManager mPackageManager;
    @Mock private UserContextProvider mUserContextProvider;
    @Mock private VibratorHelper mVibratorHelper;
    @Mock private UserLogoutInteractor mLogoutInteractor;
    @Mock private ControlsComponent mControlsComponent;
    @Mock private PowerManager mPowerManager;
    @Mock private EmergencyAffordanceManager mEmergencyAffordanceManager;
    @Mock private ScreenshotHelper mScreenshotHelper;

    private TestableLooper mTestableLooper;
    private SecureSettings mSecureSettings;
    private FakeGlobalSettings mFakeGlobalSettings;
    private KeyguardStateController mKeyguardStateController;
    private ShadeController mShadeController;
    private LockPatternUtils mLockPatternUtils;
    private UiEventLoggerFake mUiEventLoggerFake;
    private GlobalActionsInteractor mInteractor;
    private FakeGlobalActionsRepository mRepository;
    private SelectedUserInteractor mFakeSelectedUserInteractor;
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private FakeExecutor mBackgroundExecutor;
    private FakeDeviceEntryRepository mFakeDeviceEntryRepository;
    private TestScope mTestScope;
    private GlobalActionsDialogLite.ActionsDialogLiteDelegate.Factory mDelegateFactory;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        allowTestableLooperAsMainThread();

        when(mRingerModeTracker.getRingerMode()).thenReturn(mRingerModeLiveData);
        when(mUserContextProvider.getUserContext()).thenReturn(mContext);
        when(mResources.getConfiguration()).thenReturn(
                getContext().getResources().getConfiguration());
        when(mResources.getStringArray(R.array.config_globalActionsList))
                .thenReturn(new String[]{});
        when(mStatusBarWindowControllerStore.getDefaultDisplay())
                .thenReturn(mStatusBarWindowController);
        when(mStatusBarWindowControllerStore.forDisplay(anyInt()))
                .thenReturn(mStatusBarWindowController);
        KosmosJavaAdapter kosmos = new KosmosJavaAdapter(this);
        mFakeSelectedUserInteractor = kosmos.getFakeSelectedUserInteractor();
        when(mFakeSelectedUserInteractor.getSelectedUserId()).thenReturn(0);
        mKeyguardUpdateMonitor = kosmos.getKeyguardUpdateMonitor();
        mShadeController = kosmos.getFakeShadeController();
        mSecureSettings = new FakeSettings();
        mFakeGlobalSettings = kosmos.getFakeGlobalSettings();
        mKeyguardStateController = kosmos.getKeyguardStateController();
        mLockPatternUtils = kosmos.getLockPatternUtils();
        mUiEventLoggerFake = kosmos.getUiEventLoggerFake();
        mInteractor = kosmos.getGlobalActionsInteractor();
        mRepository = kosmos.getGlobalActionsRepository();
        mBackgroundExecutor = kosmos.getFakeExecutor();
        mFakeDeviceEntryRepository = kosmos.getFakeDeviceEntryRepository();
        mTestScope = kosmos.getTestScope();
        mDelegateFactory = kosmos.getActionsDialogLiteDelegateFactory();

        ColorExtractor.GradientColors backdropColors = new ColorExtractor.GradientColors();
        backdropColors.setMainColor(Color.BLACK);
        SysuiColorExtractor colorExtractor = kosmos.getFakeSysuiColorExtractor();
        when(colorExtractor.getNeutralColors()).thenReturn(backdropColors);
    }

    @Test
    public void testShouldLogShow() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        globalActionsDialogLite.onShow(null);
        verifyLogPosted(GlobalActionsEvent.GA_POWER_MENU_OPEN, 0 /* position */);
    }

    @Test
    public void testShouldLogDismiss() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        globalActionsDialogLite.onDismiss(null);
        verifyLogPosted(GlobalActionsEvent.GA_POWER_MENU_CLOSE, 0 /* position */);
    }

    @Test
    public void testShouldLogTimeout() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        globalActionsDialogLite.onShow(null);
        verifyLogPosted(GlobalActionsEvent.GA_POWER_MENU_OPEN, 0 /* position */);
        globalActionsDialogLite.rescheduleBurnInTimeout(20); // ms
        mTestableLooper.moveTimeForward(30);
        verifyLogPosted(GlobalActionsEvent.GA_CLOSE_TIMEOUT, 1 /* position */);
    }

    @Test
    public void testPredictiveBackCallbackRegisteredAndUnregistered() throws InterruptedException {
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        GlobalActionsDialogLite.ActionsDialogLiteDelegate delegate =
                globalActionsDialogLite.createDialogDelegate();
        assertThat(delegate.mCurrentDialog).isNotNull();
        OnBackInvokedDispatcher backInvokedDispatcher =
                delegate.mCurrentDialog.getOnBackInvokedDispatcher();
        spyOn(backInvokedDispatcher);
        delegate.show(null /* expandable */);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        delegate.mCurrentDialog.setDismissOverride(null);
        ArgumentCaptor<OnBackInvokedCallback> callbackCaptor =
                ArgumentCaptor.forClass(OnBackInvokedCallback.class);
        verify(backInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT), callbackCaptor.capture());
        OnBackInvokedCallback callback = callbackCaptor.getValue();
        assertThat(callback).isNotNull();
        delegate.dismiss();

        mTestableLooper.processAllMessages();
        verify(backInvokedDispatcher).unregisterOnBackInvokedCallback(eq(callback));

        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    /**
     * This specific test case appears to be flaky.
     * b/249136797 tracks the task of root-causing and fixing it.
     */
    @FlakyTest
    @Test
    public void testPredictiveBackInvocationDismissesDialog() throws InterruptedException {
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        GlobalActionsDialogLite.ActionsDialogLiteDelegate delegate =
                globalActionsDialogLite.createDialogDelegate();
        assertThat(delegate.mCurrentDialog).isNotNull();
        OnBackInvokedDispatcher backInvokedDispatcher =
                delegate.mCurrentDialog.getOnBackInvokedDispatcher();
        spyOn(backInvokedDispatcher);
        delegate.show(null /* expandable */);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        delegate.mCurrentDialog.setDismissOverride(null);
        verifyLogPosted(GlobalActionsEvent.GA_POWER_MENU_OPEN, 0 /* position */);
        ArgumentCaptor<OnBackInvokedCallback> callbackCaptor =
                ArgumentCaptor.forClass(OnBackInvokedCallback.class);
        // SystemUIDialog will register first for an animation callback, then
        // GlobalActionsDialogLite will register the custom callback.
        verify(backInvokedDispatcher, times(2)).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT), callbackCaptor.capture());

        List<OnBackInvokedCallback> callbacks = callbackCaptor.getAllValues();
        assertThat(callbacks).hasSize(2);
        // The custom callback is at the top of the stack, i.e. the front of the list.
        callbacks.getFirst().onBackInvoked();

        verifyLogPosted(GlobalActionsEvent.GA_CLOSE_BACK, 1 /* position */);
        verifyLogPosted(GlobalActionsEvent.GA_POWER_MENU_CLOSE, 2 /* position */);

        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testSingleTap_logAndDismiss() {
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();

        GlobalActionsDialogLite.ActionsDialogLiteDelegate delegate =
                globalActionsDialogLite.createDialogDelegate();
        delegate.mGestureListener.onSingleTapUp(null);
        verifyLogPosted(GlobalActionsEvent.GA_CLOSE_TAP_OUTSIDE, 0 /* position */);
    }

    @FlakyTest(bugId = 479814486)
    @Test
    public void testTouchReschedulesBurnInTimeout() throws InterruptedException {
        final int timeout = 10000;
        mFakeGlobalSettings.putInt(Settings.Global.GLOBAL_ACTIONS_TIMEOUT_MILLIS, timeout);
        setMaxShownPowerItems(1);
        mRepository.setPossibleGlobalActions(List.of(GlobalActionType.POWER));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);
        verifyLogPosted(GlobalActionsEvent.GA_POWER_MENU_OPEN, 0);

        // Advance halfway through timeout.
        mTestableLooper.moveTimeForward(timeout / 2);
        assertThat(mUiEventLoggerFake.numLogs()).isEqualTo(1);

        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        globalActionsDialogLite.mDelegate.mCurrentDialog.dispatchTouchEvent(event);

        // Advance past original timeout.
        mTestableLooper.moveTimeForward((timeout / 2) + 1000);
        assertThat(mUiEventLoggerFake.numLogs()).isEqualTo(1);

        // Advance past final timeout.
        mTestableLooper.moveTimeForward(timeout / 2);
        verifyLogPosted(GlobalActionsEvent.GA_CLOSE_TIMEOUT, 1);

        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testSwipeDownLockscreen_logAndOpenQS() {
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        setShouldDisplayLockdown(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();

        GlobalActionsDialogLite.ActionsDialogLiteDelegate delegate =
                globalActionsDialogLite.createDialogDelegate();
        MotionEvent start = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent end = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 500, 0);
        delegate.mGestureListener.onFling(start, end, 0, 1000);
        verifyLogPosted(GlobalActionsEvent.GA_CLOSE_TAP_OUTSIDE, 0 /* position */);
        verify(mShadeController).animateExpandQs();
    }

    @Test
    public void testSwipeDown_logAndOpenNotificationShade() {
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        setShouldDisplayLockdown(true);
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();

        GlobalActionsDialogLite.ActionsDialogLiteDelegate delegate =
                globalActionsDialogLite.createDialogDelegate();
        MotionEvent start = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent end = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 500, 0);
        delegate.mGestureListener.onFling(start, end, 0, 1000);
        verifyLogPosted(GlobalActionsEvent.GA_CLOSE_TAP_OUTSIDE, 0 /* position */);
        verify(mShadeController).animateExpandShade();
    }

    @Test
    public void testSwipeDown_pastStatusBarHeight_shadeNotOpened() {
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        setShouldDisplayLockdown(true);
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();

        GlobalActionsDialogLite.ActionsDialogLiteDelegate delegate =
                globalActionsDialogLite.createDialogDelegate();
        when(mStatusBarWindowController.getStatusBarHeight()).thenReturn(100);

        // WHEN the start y is larger than the status bar height
        MotionEvent start = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 200, 0);
        MotionEvent end = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 500, 0);
        delegate.mGestureListener.onFling(start, end, 0, 1000);

        // THEN the shade isn't opened
        verify(mShadeController, never()).animateExpandShade();
    }

    @Test
    public void testShouldLogBugreportPress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.BugReportAction bugReportAction =
                globalActionsDialogLite.new BugReportAction();
        bugReportAction.onPress();
        verifyLogPosted(GlobalActionsEvent.GA_BUGREPORT_PRESS, 0 /* position */);
    }

    @Test
    public void testShouldLogBugreportLongPress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.BugReportAction bugReportAction =
                globalActionsDialogLite.new BugReportAction();
        bugReportAction.onLongPress();
        verifyLogPosted(GlobalActionsEvent.GA_BUGREPORT_LONG_PRESS, 0 /* position */);
    }

    @Test
    public void testShouldLogEmergencyDialerPress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.EmergencyDialerAction emergencyDialerAction =
                globalActionsDialogLite.new EmergencyDialerAction();
        emergencyDialerAction.onPress();
        verifyLogPosted(GlobalActionsEvent.GA_EMERGENCY_DIALER_PRESS, 0 /* position */);
    }

    @Test
    public void testShouldLogScreenshotPress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.ScreenshotAction screenshotAction =
                globalActionsDialogLite.new ScreenshotAction();
        screenshotAction.onPress();
        verifyLogPosted(GlobalActionsEvent.GA_SCREENSHOT_PRESS, 0 /* position */);
    }

    @Test
    public void testCreateActionItems_lockdownEnabled_doesShowLockdown()
            throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.EmergencyAction.class,
                GlobalActionsDialogLite.LockDownAction.class,
                GlobalActionsDialogLite.ShutDownAction.class,
                GlobalActionsDialogLite.RestartAction.class);
        assertThat(globalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(globalActionsDialogLite.mPowerItems).isEmpty();

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_lockdownDisabled_doesNotShowLockdown()
            throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        // make sure lockdown action will NOT be shown
        setShouldDisplayLockdown(false);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.EmergencyAction.class,
                GlobalActionsDialogLite.ShutDownAction.class,
                GlobalActionsDialogLite.RestartAction.class);
        assertThat(globalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(globalActionsDialogLite.mPowerItems).isEmpty();

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_emergencyDisabled_doesNotShowEmergency()
            throws InterruptedException {
        // make sure emergency action will NOT be shown
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(false);
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.LockDownAction.class,
                GlobalActionsDialogLite.ShutDownAction.class,
                GlobalActionsDialogLite.RestartAction.class);
        assertThat(globalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(globalActionsDialogLite.mPowerItems).isEmpty();

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testShouldLogLockdownPress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.LockDownAction lockDownAction =
                globalActionsDialogLite.new LockDownAction();
        lockDownAction.onPress();
        verifyLogPosted(GlobalActionsEvent.GA_LOCKDOWN_PRESS, 0 /* position */);
    }

    @Test
    public void testShouldLogShutdownPress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.ShutDownAction shutDownAction =
                globalActionsDialogLite.new ShutDownAction();
        shutDownAction.onPress();
        verifyLogPosted(GlobalActionsEvent.GA_SHUTDOWN_PRESS, 0 /* position */);
    }

    @Test
    public void testShouldLogShutdownLongPress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.ShutDownAction shutDownAction =
                globalActionsDialogLite.new ShutDownAction();
        shutDownAction.onLongPress();
        verifyLogPosted(GlobalActionsEvent.GA_SHUTDOWN_LONG_PRESS, 0 /* position */);
    }

    @Test
    public void testShouldLogRebootPress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.RestartAction restartAction =
                globalActionsDialogLite.new RestartAction();
        restartAction.onPress();
        verifyLogPosted(GlobalActionsEvent.GA_REBOOT_PRESS, 0 /* position */);
    }

    @Test
    public void testShouldLogRebootLongPress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.RestartAction restartAction =
                globalActionsDialogLite.new RestartAction();
        restartAction.onLongPress();
        verifyLogPosted(GlobalActionsEvent.GA_REBOOT_LONG_PRESS, 0 /* position */);
    }

    @Test
    public void testSendFeedbackAction_onPress_sendsBroadcast() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final ComponentName testComponent = new ComponentName("com.example", "FeedbackReceiver");
        globalActionsDialogLite.mFirstFeedbackReceiver = testComponent;
        GlobalActionsDialogLite.SendFeedbackAction feedbackAction =
                globalActionsDialogLite.new SendFeedbackAction(testComponent);

        when(mUserTracker.getUserHandle()).thenReturn(UserHandle.CURRENT);
        feedbackAction.onPress();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mBroadcastSender).sendBroadcastAsUser(
                intentCaptor.capture(), eq(UserHandle.CURRENT));
        Intent capturedIntent = intentCaptor.getValue();
        assertThat(capturedIntent.getAction()).isEqualTo(Settings.ACTION_REQUEST_FEEDBACK);
        assertThat(capturedIntent.getComponent()).isEqualTo(testComponent);
        verifyLogPosted(GlobalActionsEvent.GA_FEEDBACK_PRESS, 0 /* position */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_GLOBAL_ACTIONS_FEEDBACK_ACTION)
    public void testCreateActionItems_feedbackAction_hasReceiver_showsAction()
            throws InterruptedException {
        doReturn(new String[]{GlobalActionsDialogLite.GLOBAL_ACTION_KEY_FEEDBACK})
                .when(mResources).getStringArray(R.array.config_globalActionsList);
        setMaxShownPowerItems(1);
        mRepository.setPossibleGlobalActions(List.of(GlobalActionType.FEEDBACK));
        when(mUserTracker.getUserId()).thenReturn(0);

        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new android.content.pm.ActivityInfo();
        resolveInfo.activityInfo.packageName = "com.example";
        resolveInfo.activityInfo.name = "FeedbackReceiver";
        List<ResolveInfo> receivers = new ArrayList<>();
        receivers.add(resolveInfo);
        when(mPackageManager.queryBroadcastReceiversAsUser(
                any(Intent.class),
                eq(PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE),
                anyInt()))
                .thenAnswer(invocation -> {
                    Intent intent = invocation.getArgument(0);
                    if (Settings.ACTION_REQUEST_FEEDBACK.equals(intent.getAction())) {
                        return receivers;
                    }
                    return new ArrayList<>();
                });

        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);
        mBackgroundExecutor.runAllReady();
        mTestableLooper.processAllMessages();

        assertOneItemOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.SendFeedbackAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_GLOBAL_ACTIONS_FEEDBACK_ACTION)
    public void testCreateActionItems_feedbackAction_noReceiver_hidesAction()
            throws InterruptedException {
        doReturn(new String[]{GlobalActionsDialogLite.GLOBAL_ACTION_KEY_FEEDBACK})
                .when(mResources).getStringArray(R.array.config_globalActionsList);
        setMaxShownPowerItems(1);
        mRepository.setPossibleGlobalActions(List.of(GlobalActionType.FEEDBACK));
        when(mUserTracker.getUserId()).thenReturn(0);

        when(mPackageManager.queryBroadcastReceiversAsUser(
                any(Intent.class),
                eq(PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE),
                anyInt()))
                .thenReturn(new ArrayList<>());

        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);
        mBackgroundExecutor.runAllReady();
        mTestableLooper.processAllMessages();

        assertNoItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.SendFeedbackAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_GLOBAL_ACTIONS_FEEDBACK_ACTION)
    public void testCreateActionItems_feedbackAction_flagDisabled_hidesAction()
            throws InterruptedException {
        doReturn(new String[]{GlobalActionsDialogLite.GLOBAL_ACTION_KEY_FEEDBACK})
                .when(mResources).getStringArray(R.array.config_globalActionsList);
        setMaxShownPowerItems(1);
        mRepository.setPossibleGlobalActions(List.of(GlobalActionType.FEEDBACK));
        when(mUserTracker.getUserId()).thenReturn(0);

        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new android.content.pm.ActivityInfo();
        resolveInfo.activityInfo.packageName = "com.example";
        resolveInfo.activityInfo.name = "FeedbackReceiver";
        List<ResolveInfo> receivers = new ArrayList<>();
        receivers.add(resolveInfo);
        when(mPackageManager.queryBroadcastReceiversAsUser(
                any(Intent.class),
                eq(PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE),
                anyInt()))
                .thenAnswer(invocation -> {
                    Intent intent = invocation.getArgument(0);
                    if (Settings.ACTION_REQUEST_FEEDBACK.equals(intent.getAction())) {
                        return receivers;
                    }
                    return new ArrayList<>();
                });
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);
        mBackgroundExecutor.runAllReady();
        mTestableLooper.processAllMessages();

        assertNoItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.SendFeedbackAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testOnLockScreen_disableSmartLock() throws InterruptedException {
        int expectedUser = 100;
        doReturn(expectedUser).when(mFakeSelectedUserInteractor).getSelectedUserId();
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        // When entering power menu from lockscreen, with smart lock enabled
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);
        globalActionsDialogLite.showOrHideDialog(true, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        // Then smart lock will be disabled
        verify(mLockPatternUtils).requireCredentialEntry(eq(expectedUser));

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(true, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testBugreportAction_whenDebugMode_shouldOfferBugreportButtonBeforeProvisioning() {
        UserInfo currentUser = mockCurrentUser(FLAG_ADMIN);

        when(mUserTracker.getUserInfo()).thenReturn(currentUser);
        when(mUserTracker.getUserId()).thenReturn(currentUser.id);
        mSecureSettings.putIntForUser(Settings.Secure.BUGREPORT_IN_POWER_MENU, 1,
                currentUser.id);

        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.BugReportAction bugReportAction =
                globalActionsDialogLite.new BugReportAction();
        assertThat(bugReportAction.showBeforeProvisioning()).isTrue();
    }

    @Test
    public void testBugreportAction_whenUserIsNotAdmin_noBugReportActionBeforeProvisioning() {
        UserInfo currentUser = mockCurrentUser(0);

        when(mUserTracker.getUserInfo()).thenReturn(currentUser);
        mSecureSettings.putIntForUser(Settings.Secure.BUGREPORT_IN_POWER_MENU, 1,
                currentUser.id);

        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.BugReportAction bugReportAction =
                globalActionsDialogLite.new BugReportAction();
        assertThat(bugReportAction.showBeforeProvisioning()).isFalse();
    }

    @Test
    public void testInteractor_onShow() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        globalActionsDialogLite.onShow(null);
        assertThat(mInteractor.isVisible().getValue()).isTrue();
    }

    @Test
    public void testInteractor_onDismiss() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        globalActionsDialogLite.onDismiss(null);
        assertThat(mInteractor.isVisible().getValue()).isFalse();
    }

    @Test
    public void testShouldLogSystemUpdatePress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.SystemUpdateAction systemUpdateAction =
                globalActionsDialogLite.new SystemUpdateAction();
        systemUpdateAction.onPress();
        verifyLogPosted(GlobalActionsEvent.GA_SYSTEM_UPDATE_PRESS, 0 /* position */);
    }

    @Test
    public void testCreateActionItems_systemUpdateEnabled_doesShowSystemUpdate()
            throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(5);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART,
                GlobalActionType.SYSTEM_UPDATE
        ));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.EmergencyAction.class,
                GlobalActionsDialogLite.LockDownAction.class,
                GlobalActionsDialogLite.ShutDownAction.class,
                GlobalActionsDialogLite.RestartAction.class,
                GlobalActionsDialogLite.SystemUpdateAction.class);
        assertThat(globalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(globalActionsDialogLite.mPowerItems).isEmpty();

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_systemUpdateDisabled_doesNotShowSystemUpdateAction()
            throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(5);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertNoItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.SystemUpdateAction.class);
        assertThat(globalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(globalActionsDialogLite.mPowerItems).isEmpty();

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_systemUpdateEnabled_locked_showsSystemUpdate()
            throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(5);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART,
                GlobalActionType.SYSTEM_UPDATE
        ));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        // Show dialog with keyguard showing
        globalActionsDialogLite.showOrHideDialog(true, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertOneItemOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.SystemUpdateAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(true, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_systemUpdateEnabled_notProvisioned_noSystemUpdate()
            throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(5);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART,
                GlobalActionType.SYSTEM_UPDATE
        ));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        // Show dialog with keyguard showing
        globalActionsDialogLite.showOrHideDialog(false, false, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertNoItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.SystemUpdateAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, false, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @FlakyTest(bugId = 479814486)
    @Test
    public void userSwitching_dismissDialog() throws InterruptedException {
        setMaxShownPowerItems(2);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        mTestableLooper.processAllMessages();
        assertThat(globalActionsDialogLite.mDelegate.isShowing()).isTrue();

        ArgumentCaptor<UserTracker.Callback> captor =
                ArgumentCaptor.forClass(UserTracker.Callback.class);

        verify(mUserTracker).addCallback(captor.capture(), eq(mBackgroundExecutor));

        captor.getValue().onBeforeUserSwitching(100);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testShouldLogStandbyPress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.StandbyAction standbyAction =
                globalActionsDialogLite.new StandbyAction();
        standbyAction.onPress();
        verifyLogPosted(GlobalActionsEvent.GA_STANDBY_PRESS, 0 /* position */);
    }

    @Test
    public void testCreateActionItems_standbyEnabled_doesShowStandby() {
        // Test like a TV, which only has standby and shut down
        setMaxShownPowerItems(2);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.STANDBY,
                GlobalActionType.POWER
        ));
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        globalActionsDialogLite.createActionItems();

        assertItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.StandbyAction.class,
                GlobalActionsDialogLite.ShutDownAction.class);
        assertThat(globalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(globalActionsDialogLite.mPowerItems).isEmpty();
    }

    @Test
    public void testCreateActionItems_standbyDisabled_doesNotShowStandbyAction()
            throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(5);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCKDOWN,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        setShouldDisplayLockdown(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertNoItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.StandbyAction.class);
        assertThat(globalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(globalActionsDialogLite.mPowerItems).isEmpty();

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_standbyEnabled_locked_showsStandby()
            throws InterruptedException {
        // Test like a TV, which only has standby and shut down
        setMaxShownPowerItems(2);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.STANDBY,
                GlobalActionType.POWER
        ));
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        // Show dialog with keyguard showing and provisioned
        globalActionsDialogLite.showOrHideDialog(true, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertOneItemOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.StandbyAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(true, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_standbyEnabled_notProvisioned_showsStandby()
            throws InterruptedException {
        // Test like a TV, which only has standby and shut down.
        setMaxShownPowerItems(2);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.STANDBY,
                GlobalActionType.POWER
        ));
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        // Show dialog without keyguard showing and not provisioned
        globalActionsDialogLite.showOrHideDialog(false, false, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertOneItemOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.StandbyAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, false, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_noneTv_actionsNotFocusableAndClickable()
            throws InterruptedException {
        // Test like a TV, which only has standby and shut down.
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)).thenReturn(false);
        setMaxShownPowerItems(2);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.STANDBY,
                GlobalActionType.POWER
        ));
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);
        mTestableLooper.processAllMessages();
        assertThat(globalActionsDialogLite.mDelegate.isShowing()).isTrue();

        final GlobalActionsDialogLite.SinglePressAction action =
                (GlobalActionsDialogLite.SinglePressAction) globalActionsDialogLite.mItems.get(0);
        assertThat(action.mIconView.isClickable()).isFalse();
        assertThat(action.mIconView.isFocusable()).isFalse();
        assertThat(action.mIconView.performClick()).isFalse();
        assertThat(globalActionsDialogLite.mDelegate.isShowing()).isTrue();

        final GlobalActionsDialogLite.SinglePressAction action1 =
                (GlobalActionsDialogLite.SinglePressAction) globalActionsDialogLite.mItems.get(1);
        assertThat(action1.mIconView.isClickable()).isFalse();
        assertThat(action1.mIconView.isFocusable()).isFalse();
        assertThat(action1.mIconView.performClick()).isFalse();
        assertThat(globalActionsDialogLite.mDelegate.isShowing()).isTrue();

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @FlakyTest(bugId = 479814486)
    @Test
    public void testCreateActionItems_tv_actionsFocusableAndClickable()
            throws InterruptedException {
        // Test like a TV, which only has standby and shut down.
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)).thenReturn(true);
        setMaxShownPowerItems(2);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.STANDBY,
                GlobalActionType.POWER
        ));
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);
        verifyLogPosted(GlobalActionsEvent.GA_POWER_MENU_OPEN, 0 /* position */);
        assertThat(globalActionsDialogLite.mDelegate.isShowing()).isTrue();

        final GlobalActionsDialogLite.SinglePressAction action =
                (GlobalActionsDialogLite.SinglePressAction) globalActionsDialogLite.mItems.get(0);
        assertThat(action.mIconView.isClickable()).isTrue();
        assertThat(action.mIconView.isFocusable()).isTrue();

        final GlobalActionsDialogLite.SinglePressAction action1 =
                (GlobalActionsDialogLite.SinglePressAction) globalActionsDialogLite.mItems.get(1);
        assertThat(action1.mIconView.isClickable()).isTrue();
        assertThat(action1.mIconView.isFocusable()).isTrue();

        assertThat(action.mIconView.performClick()).isTrue();
        verifyLogPosted(GlobalActionsEvent.GA_POWER_MENU_CLOSE, 1 /* position */);
        verifyLogPosted(GlobalActionsEvent.GA_STANDBY_PRESS, 2 /* position */);

        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testShouldLogLockPress() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.LockAction lockAction =
                globalActionsDialogLite.new LockAction();
        lockAction.onPress();
        verifyLogPosted(GlobalActionsEvent.GA_LOCK_PRESS, 0 /* position */);
    }

    @Test
    public void testCreateActionItems_lockEnabled_doesShowLock() throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCK,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.EmergencyAction.class,
                GlobalActionsDialogLite.LockAction.class,
                GlobalActionsDialogLite.ShutDownAction.class,
                GlobalActionsDialogLite.RestartAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_keyguardShowing_doesNotShowLock()
            throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCK,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(true, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertNoItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.LockAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(true, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_deviceNotProvisioned_doesNotShowLock()
            throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCK,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, false, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertNoItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.LockAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, false, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_noLockScreen_doesNotShowLock() throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCK,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        when(mKeyguardStateController.isMethodSecure()).thenReturn(false);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertNoItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.LockAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testCreateActionItems_deviceLocked_doesNotShowLock() throws InterruptedException {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
                .thenReturn(true);
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCK,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        // Clear the dismiss override so we don't have behavior after dismissing the dialog
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);

        assertNoItemsOfType(globalActionsDialogLite.mItems,
                GlobalActionsDialogLite.LockAction.class);

        // Hide dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        mTestableLooper.processAllMessages();
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    @Test
    public void testLockActionOnPress_doesNotChangeStrongAuth() {
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        GlobalActionsDialogLite.LockAction lockAction =
                globalActionsDialogLite.new LockAction();

        lockAction.onPress();

        // Verify that requireStrongAuth is never called with any arguments during the lock action.
        verify(mLockPatternUtils, never()).requireStrongAuth(anyInt(), anyInt());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_INSET_FOCUS_RINGS)
    public void testMyAdapter_getView_insetFocusRing_setsFocusListener() {
        mockConfigurationForInsetFocusRings(/* enableConfig= */ true);

        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        GlobalActionsDialogLite.MyAdapter adapter = globalActionsDialogLite.new MyAdapter();
        ViewGroup parent = new LinearLayout(mContext);
        View view = adapter.getView(/* position= */ 0, /* convertView= */ null, parent);

        View.OnFocusChangeListener listener = view.getOnFocusChangeListener();
        assertThat(listener).isNotNull();

        ImageView icon = view.findViewById(com.android.internal.R.id.icon);
        assertThat(icon.getForeground()).isNull();

        listener.onFocusChange(view, true);
        assertThat(icon.getForeground()).isNotNull();

        listener.onFocusChange(view, false);
        assertThat(icon.getForeground()).isNull();
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_INSET_FOCUS_RINGS)
    public void testMyAdapter_getView_insetFocusRing_whenFlagDisabled_doesNotSetFocusListener() {
        mockConfigurationForInsetFocusRings(/* enableConfig= */ true);

        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        GlobalActionsDialogLite.MyAdapter adapter = globalActionsDialogLite.new MyAdapter();
        ViewGroup parent = new LinearLayout(mContext);
        View view = adapter.getView(/* position= */ 0, /* convertView= */ null, parent);

        View.OnFocusChangeListener listener = view.getOnFocusChangeListener();
        assertThat(listener).isNull();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_INSET_FOCUS_RINGS)
    public void testMyAdapter_getView_insetFocusRing_whenConfigDisabled_doesNotSetFocusListener() {
        mockConfigurationForInsetFocusRings(/* enableConfig= */ false);

        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        GlobalActionsDialogLite.MyAdapter adapter = globalActionsDialogLite.new MyAdapter();
        ViewGroup parent = new LinearLayout(mContext);
        View view = adapter.getView(/* position= */ 0, /* convertView= */ null, parent);

        View.OnFocusChangeListener listener = view.getOnFocusChangeListener();
        assertThat(listener).isNull();
    }

    @Test
    public void testMyAdapter_getView_setsAccessibilityRoleButton() {
        setMaxShownPowerItems(1);
        mRepository.setPossibleGlobalActions(List.of(GlobalActionType.POWER));
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);

        GlobalActionsDialogLite.MyAdapter adapter = globalActionsDialogLite.new MyAdapter();
        ViewGroup parent = new LinearLayout(mContext);

        View view = adapter.getView(/* position= */ 0, /* convertView= */ null, parent);
        View.AccessibilityDelegate delegate = view.getAccessibilityDelegate();
        assertThat(delegate).isNotNull();

        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        delegate.onInitializeAccessibilityNodeInfo(view, info);

        assertThat(info.getClassName().toString()).isEqualTo(Button.class.getName());
    }

    private void mockConfigurationForInsetFocusRings(boolean enableConfig) {
        setMaxShownPowerItems(1);
        mRepository.setPossibleGlobalActions(List.of(GlobalActionType.POWER));
        when(mResources.getBoolean(com.android.internal.R.bool.config_enableInsetFocusRingsInSuw))
                .thenReturn(enableConfig);
    }

    @Test
    public void testDeviceGoesFromEnteredToLocked_dismissesDialog() throws InterruptedException {
        setMaxShownPowerItems(4);
        mRepository.setPossibleGlobalActions(List.of(
                GlobalActionType.EMERGENCY,
                GlobalActionType.LOCK,
                GlobalActionType.POWER,
                GlobalActionType.RESTART
        ));
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        GlobalActionsDialogLite globalActionsDialogLite = createGlobalActionsDialogLite();
        final var latch = new CountDownLatch(1);
        globalActionsDialogLite.setDismissLatchForTesting(latch);

        // Device is unlocked at first
        mFakeDeviceEntryRepository.getDeviceUnlockStatus().setValue(
                new DeviceUnlockStatus(true, null));

        // Show dialog
        globalActionsDialogLite.showOrHideDialog(false, true, null, Display.DEFAULT_DISPLAY);
        assertThat(globalActionsDialogLite.mDelegate).isNotNull();
        assertThat(globalActionsDialogLite.mDelegate.mCurrentDialog).isNotNull();
        globalActionsDialogLite.mDelegate.mCurrentDialog.setDismissOverride(null);
        mTestableLooper.processAllMessages();

        // Then device gets locked
        mFakeDeviceEntryRepository.getDeviceUnlockStatus().setValue(
                new DeviceUnlockStatus(false, null));
        mTestScope.getTestScheduler().runCurrent();
        mTestableLooper.processAllMessages();

        // Dialog should be dismissed
        final boolean completed = latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            fail("Timed out waiting for delegate to be dismissed");
        }
        assertThat(globalActionsDialogLite.mDelegate).isNull();
    }

    /**
     * Verifies the given event was logged at the given position.
     *
     * @param event    the event to verify.
     * @param position the position of the event in the log.
     */
    private void verifyLogPosted(GlobalActionsEvent event, int position) {
        mTestableLooper.processAllMessages();
        assertThat(mUiEventLoggerFake.numLogs()).isAtLeast(position + 1);
        assertThat(mUiEventLoggerFake.eventId(position)).isEqualTo(event.getId());
    }

    @SafeVarargs
    private static <T> void assertItemsOfType(List<T> stuff, Class<? extends T>... classes) {
        assertThat(stuff).hasSize(classes.length);
        for (int i = 0; i < stuff.size(); i++) {
            assertThat(stuff.get(i)).isInstanceOf(classes[i]);
        }
    }

    private static <T> void assertNoItemsOfType(List<T> stuff, Class<? extends T> klass) {
        for (int i = 0; i < stuff.size(); i++) {
            assertThat(stuff.get(i)).isNotInstanceOf(klass);
        }
    }

    private static <T> void assertOneItemOfType(List<T> stuff, Class<? extends T> klass) {
        List<?> classes = stuff.stream().map((item) -> item.getClass()).toList();
        assertThat(classes).containsNoDuplicates();
        assertThat(classes).contains(klass);
    }

    private static UserInfo mockCurrentUser(int flags) {
        return new UserInfo(10, "A User", flags);
    }

    /**
     * Sets the value of {@link GlobalActionsDialogLite#getMaxShownPowerItems} by setting up the
     * {@link #mResources}.
     *
     * @param value the value to set
     */
    private void setMaxShownPowerItems(int value) {
        when(mResources.getInteger(com.android.systemui.res.R.integer.power_menu_lite_max_columns))
                .thenReturn(value);
        when(mResources.getInteger(com.android.systemui.res.R.integer.power_menu_lite_max_rows))
                .thenReturn(1);
    }

    /**
     * Sets the value of {@link GlobalActionsDialogLite#shouldDisplayLockdown} by setting up the
     * relevant mocks.
     *
     * @param enabled whether the Lockdown option should be displayed.
     */
    private void setShouldDisplayLockdown(boolean enabled) {
        when(mUserTracker.getUserInfo()).thenReturn(mockCurrentUser(0));
        when(mKeyguardStateController.isMethodSecure()).thenReturn(enabled);
        final int state = enabled
                ? LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED
                : LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;
        when(mLockPatternUtils.getStrongAuthForUser(anyInt())).thenReturn(state);
    }

    private GlobalActionsDialogLite createGlobalActionsDialogLite() {
        GlobalActionsDialogLite globalActionsDialogLite = new GlobalActionsDialogLite(
                mContext,
                mWindowManagerFuncs,
                mAudioManager,
                mLockPatternUtils,
                mBroadcastDispatcher,
                mTelephonyListenerManager,
                mFakeGlobalSettings,
                mSecureSettings,
                mVibratorHelper,
                mResources,
                mConfigurationController,
                mActivityStarter,
                mUserTracker,
                mKeyguardStateController,
                mUserManager,
                mTrustManager,
                mActivityManager,
                null,
                mMetricsLogger,
                mStatusBarWindowControllerStore,
                mWindowManager,
                mBackgroundExecutor,
                mUiEventLoggerFake,
                mRingerModeTracker,
                new Handler(mTestableLooper.getLooper()),
                mPackageManager,
                mShadeController,
                mLogoutInteractor,
                mInteractor,
                mControlsComponent,
                () -> new FakeDisplayWindowPropertiesRepository(mContext),
                mPowerManager,
                mBroadcastSender,
                mEmergencyAffordanceManager,
                mScreenshotHelper,
                mDelegateFactory);
        globalActionsDialogLite.setZeroDialogPressDelayForTesting();
        return globalActionsDialogLite;
    }
}
