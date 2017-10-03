/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.shade;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.systemui.display.data.repository.FakeDisplayRepositoryKt.display;
import static com.android.systemui.log.LogBufferHelperKt.logcatLogBuffer;

import static com.google.common.truth.Truth.assertThat;

import static kotlinx.coroutines.flow.FlowKt.emptyFlow;
import static kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.dagger.KeyguardStatusBarViewComponent;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.brightness.data.repository.BrightnessMirrorShowingRepository;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.common.domain.interactor.SysUIStateDisplaysInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor;
import com.android.systemui.deviceentry.ui.view.UdfpsAccessibilityOverlayOverlappingTouchHandlingView;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FakeFeatureFlagsClassic;
import com.android.systemui.flags.Flags;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.haptics.msdl.FakeMSDLPlayer;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.data.repository.FakeKeyguardClockRepository;
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository;
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.domain.interactor.NaturalScrollingSettingObserver;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.keyguard.ui.transitions.BlurConfig;
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.KeyguardTouchHandlingViewModel;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager;
import com.android.systemui.media.controls.ui.controller.KeyguardMediaController;
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.qs.composefragment.QSFragmentCompose;
import com.android.systemui.res.R;
import com.android.systemui.screenrecord.ScreenRecordUxController;
import com.android.systemui.shade.data.repository.FakeShadeRepository;
import com.android.systemui.shade.data.repository.ShadeAnimationRepository;
import com.android.systemui.shade.data.repository.ShadeRepository;
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor;
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractorLegacyImpl;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.shade.domain.interactor.ShadeInteractorImpl;
import com.android.systemui.shade.domain.interactor.ShadeInteractorLegacyImpl;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.QsFrameTranslateController;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.gesture.StatusBarLongPressGestureDetector;
import com.android.systemui.statusbar.notification.ConversationNotificationManager;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinatorLogger;
import com.android.systemui.statusbar.notification.data.repository.NotificationsKeyguardViewStateRepository;
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor;
import com.android.systemui.statusbar.notification.domain.interactor.NotificationsKeyguardInteractor;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.HeadsUpTouchHelper;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.KeyguardStatusBarViewController;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ShadeTouchableRegionManager;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.TapAgainViewController;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController;
import com.android.systemui.statusbar.policy.data.repository.FakeUserSetupRepository;
import com.android.systemui.unfold.SysUIUnfoldComponent;
import com.android.systemui.user.domain.interactor.UserSwitcherInteractor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.utils.windowmanager.WindowManagerProvider;
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor;
import com.android.wm.shell.animation.FlingAnimationUtils;

import dagger.Lazy;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.test.TestScope;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public class NotificationPanelViewControllerBaseTest extends SysuiTestCase {

    protected static final int SPLIT_SHADE_FULL_TRANSITION_DISTANCE = 400;
    protected static final int NOTIFICATION_SCRIM_TOP_PADDING_IN_SPLIT_SHADE = 50;
    protected static final int PANEL_WIDTH = 500; // Random value just for the test.

    @Mock protected CentralSurfaces mCentralSurfaces;
    @Mock protected NotificationStackScrollLayout mNotificationStackScrollLayout;
    @Mock protected HeadsUpManager mHeadsUpManager;
    @Mock protected NotificationGutsManager mGutsManager;
    @Mock protected KeyguardStatusBarView mKeyguardStatusBar;
    @Mock protected HeadsUpTouchHelper.Callback mHeadsUpCallback;
    @Mock protected KeyguardUpdateMonitor mUpdateMonitor;
    @Mock protected KeyguardBypassController mKeyguardBypassController;
    @Mock protected DozeParameters mDozeParameters;
    @Mock protected ScreenOffAnimationController mScreenOffAnimationController;
    @Mock protected NotificationPanelView mView;
    @Mock protected DynamicPrivacyController mDynamicPrivacyController;
    @Mock protected ShadeTouchableRegionManager mShadeTouchableRegionManager;
    @Mock protected KeyguardStateController mKeyguardStateController;
    @Mock protected DozeLog mDozeLog;
    private final ShadeLogger mShadeLog = new ShadeLogger(logcatLogBuffer());
    @Mock protected CommandQueue mCommandQueue;
    @Mock protected VibratorHelper mVibratorHelper;
    @Mock protected LatencyTracker mLatencyTracker;
    @Mock protected AccessibilityManager mAccessibilityManager;
    @Mock protected MetricsLogger mMetricsLogger;
    @Mock protected Resources mResources;
    @Mock protected Configuration mConfiguration;
    @Mock protected MediaHierarchyManager mMediaHierarchyManager;
    @Mock protected ConversationNotificationManager mConversationNotificationManager;
    @Mock protected StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock protected KeyguardStatusBarViewComponent.Factory mKeyguardStatusBarViewComponentFactory;
    @Mock protected KeyguardStatusBarViewComponent mKeyguardStatusBarViewComponent;
    @Mock protected KeyguardStatusBarViewController mKeyguardStatusBarViewController;
    @Mock protected LightBarController mLightBarController;
    @Mock protected NotificationStackScrollLayoutController
            mNotificationStackScrollLayoutController;
    @Mock protected NotificationShadeDepthController mNotificationShadeDepthController;
    @Mock protected LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock protected ScrimController mScrimController;
    @Mock protected MediaDataManager mMediaDataManager;
    @Mock protected AmbientState mAmbientState;
    @Mock protected UiEventLogger mUiEventLogger;
    @Mock protected KeyguardMediaController mKeyguardMediaController;
    @Mock protected NavigationModeController mNavigationModeController;
    @Mock protected NavigationBarController mNavigationBarController;
    @Mock protected QuickSettingsControllerImpl mQsController;
    @Mock protected ShadeHeaderController mShadeHeaderController;
    @Mock protected TapAgainViewController mTapAgainViewController;
    @Mock protected KeyguardIndicationController mKeyguardIndicationController;
    @Mock protected FragmentService mFragmentService;
    @Mock protected FragmentHostManager mFragmentHostManager;
    @Mock protected IStatusBarService mStatusBarService;
    @Mock protected NotificationRemoteInputManager mNotificationRemoteInputManager;
    @Mock protected ScreenRecordUxController mScreenRecordUxController;
    @Mock protected LockscreenGestureLogger mLockscreenGestureLogger;
    @Mock protected DumpManager mDumpManager;
    @Mock protected NotificationsQSContainerController mNotificationsQSContainerController;
    @Mock protected QsFrameTranslateController mQsFrameTranslateController;
    @Mock protected KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    @Mock protected NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock protected SysUiState mSysUiState;
    @Mock protected NotificationListContainer mNotificationListContainer;
    @Mock protected UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock protected QS mQs;
    @Mock protected QSFragmentCompose mQSFragment;
    @Mock protected ViewParent mViewParent;
    @Mock protected ViewTreeObserver mViewTreeObserver;
    @Mock protected DreamingToLockscreenTransitionViewModel
            mDreamingToLockscreenTransitionViewModel;
    @Mock protected KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    @Mock protected KeyguardTouchHandlingViewModel.Factory mKeyguardTouchHandlingViewModelFactory;
    @Mock protected AlternateBouncerInteractor mAlternateBouncerInteractor;
    @Mock protected MotionEvent mDownMotionEvent;
    @Mock protected CoroutineDispatcher mMainDispatcher;
    @Captor
    protected ArgumentCaptor<NotificationStackScrollLayout.OnEmptySpaceClickListener>
            mEmptySpaceClickListenerCaptor;
    @Mock protected ActivityStarter mActivityStarter;
    @Mock protected DeviceEntryFaceAuthInteractor mDeviceEntryFaceAuthInteractor;
    @Mock private JavaAdapter mJavaAdapter;
    @Mock private CastController mCastController;
    @Mock private SharedNotificationContainerInteractor mSharedNotificationContainerInteractor;
    @Mock protected ActiveNotificationsInteractor mActiveNotificationsInteractor;
    @Mock private KeyguardClockPositionAlgorithm mKeyguardClockPositionAlgorithm;
    @Mock private NaturalScrollingSettingObserver mNaturalScrollingSettingObserver;
    @Mock private LargeScreenHeaderHelper mLargeScreenHeaderHelper;
    @Mock private StatusBarLongPressGestureDetector mStatusBarLongPressGestureDetector;
    @Mock protected SysUIStateDisplaysInteractor mSysUIStateDisplaysInteractor;
    @Mock private WindowManagerProvider mWindowManagerProvider;
    protected final int mMaxUdfpsBurnInOffsetY = 5;
    protected FakeFeatureFlagsClassic mFeatureFlags = new FakeFeatureFlagsClassic();
    protected KeyguardClockInteractor mKeyguardClockInteractor;
    protected FakeKeyguardRepository mFakeKeyguardRepository;
    protected FakeKeyguardClockRepository mFakeKeyguardClockRepository;
    protected KeyguardInteractor mKeyguardInteractor;
    protected ShadeAnimationInteractor mShadeAnimationInteractor;
    protected KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);
    protected TestScope mTestScope = mKosmos.getTestScope();
    protected ShadeInteractor mShadeInteractor;
    protected PowerInteractor mPowerInteractor;
    protected NotificationPanelViewController.TouchHandler mTouchHandler;
    protected ConfigurationController mConfigurationController;
    protected SysuiStatusBarStateController mStatusBarStateController;
    protected NotificationPanelViewController mNotificationPanelViewController;
    protected View.AccessibilityDelegate mAccessibilityDelegate;
    protected NotificationsQuickSettingsContainer mNotificationContainerParent;
    protected List<View.OnAttachStateChangeListener> mOnAttachStateChangeListeners;
    protected Handler mMainHandler;
    protected View.OnLayoutChangeListener mLayoutChangeListener;
    protected ShadeRepository mShadeRepository;
    protected FakeMSDLPlayer mMSDLPlayer = mKosmos.getMsdlPlayer();
    protected WindowRootViewBlurInteractor mWindowRootViewBlurInteractor;

    protected BrightnessMirrorShowingRepository mBrightnessMirrorShowingRepository =
            mKosmos.getBrightnessMirrorShowingRepository();

    protected final FalsingManagerFake mFalsingManager = new FalsingManagerFake();
    protected final Optional<SysUIUnfoldComponent> mSysUIUnfoldComponent = Optional.empty();
    protected final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    protected final ShadeExpansionStateManager mShadeExpansionStateManager =
            new ShadeExpansionStateManager();

    protected QuickSettingsControllerImpl mQuickSettingsController;
    @Mock protected Lazy<NotificationPanelViewController> mNotificationPanelViewControllerLazy;

    protected FragmentHostManager.FragmentListener mFragmentListener;

    @Rule(order = 200)
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Before
    public void setup() {
        mFeatureFlags.set(Flags.LOCKSCREEN_ENABLE_LANDSCAPE, false);
        mFeatureFlags.set(Flags.QS_USER_DETAIL_SHORTCUT, false);

        mMainDispatcher = getMainDispatcher();
        mWindowRootViewBlurInteractor = mKosmos.getWindowRootViewBlurInteractor();
        mFakeKeyguardRepository = mKosmos.getKeyguardRepository();
        mFakeKeyguardClockRepository = new FakeKeyguardClockRepository();
        mKeyguardClockInteractor = mKosmos.getKeyguardClockInteractor();
        mKeyguardInteractor = mKosmos.getKeyguardInteractor();
        mShadeRepository = new FakeShadeRepository();
        mShadeAnimationInteractor = new ShadeAnimationInteractorLegacyImpl(
                new ShadeAnimationRepository(), mShadeRepository, mock(ShadeWindowLogger.class));
        mPowerInteractor = mKosmos.getPowerInteractor();
        when(mKeyguardTransitionInteractor.isInTransitionWhere(any(), any())).thenReturn(
                MutableStateFlow(false));
        when(mKeyguardTransitionInteractor.isInTransition(any(), any()))
                .thenReturn(emptyFlow());
        when(mKeyguardTransitionInteractor.getCurrentKeyguardState()).thenReturn(
                MutableStateFlow(KeyguardState.LOCKSCREEN));
        when(mDeviceEntryFaceAuthInteractor.isBypassEnabled()).thenReturn(MutableStateFlow(false));
        DeviceEntryUdfpsInteractor deviceEntryUdfpsInteractor =
                mock(DeviceEntryUdfpsInteractor.class);
        when(deviceEntryUdfpsInteractor.isUdfpsSupported()).thenReturn(MutableStateFlow(false));

        mShadeInteractor = new ShadeInteractorImpl(
                mTestScope.getBackgroundScope(),
                mKosmos.getDeviceProvisioningInteractor(),
                mDozeParameters,
                mFakeKeyguardRepository,
                mKeyguardTransitionInteractor,
                mPowerInteractor,
                new FakeUserSetupRepository(),
                mock(UserSwitcherInteractor.class),
                new ShadeInteractorLegacyImpl(
                        mTestScope.getBackgroundScope(),
                        mFakeKeyguardRepository,
                        mKosmos.getKeyguardTransitionInteractor(),
                        mShadeRepository,
                        mKosmos.getShadeConfigRepository()
                ),
                mKosmos.getSceneInteractor(),
                mKosmos.getShadeStatusBarComponentsInteractor());
        SystemClock systemClock = new FakeSystemClock();
        mStatusBarStateController = new StatusBarStateControllerImpl(
                mUiEventLogger,
                mJavaAdapter,
                () -> mKeyguardInteractor,
                () -> mKeyguardTransitionInteractor,
                () -> mShadeInteractor,
                () -> mKosmos.getDeviceUnlockedInteractor(),
                () -> mKosmos.getSceneInteractor(),
                () -> mKosmos.getKeyguardClockInteractor(),
                () -> mKosmos.getSceneBackInteractor(),
                () -> mKosmos.getAlternateBouncerInteractor(),
                () -> mKosmos.getDeviceEntryInteractor());

        when(mHeadsUpCallback.getContext()).thenReturn(mContext);
        when(mView.getResources()).thenReturn(mResources);
        when(mView.getWidth()).thenReturn(PANEL_WIDTH);
        when(mResources.getConfiguration()).thenReturn(mConfiguration);
        mConfiguration.orientation = ORIENTATION_PORTRAIT;
        when(mResources.getDisplayMetrics()).thenReturn(mDisplayMetrics);
        mDisplayMetrics.density = 100;
        when(mResources.getBoolean(R.bool.config_enableNotificationShadeDrag)).thenReturn(true);
        when(mResources.getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y))
                .thenReturn(mMaxUdfpsBurnInOffsetY);
        when(mResources.getDimensionPixelSize(R.dimen.notifications_top_padding_split_shade))
                .thenReturn(NOTIFICATION_SCRIM_TOP_PADDING_IN_SPLIT_SHADE);
        when(mResources.getDimensionPixelSize(R.dimen.notification_panel_margin_horizontal))
                .thenReturn(10);
        when(mResources.getDimensionPixelSize(R.dimen.split_shade_full_transition_distance))
                .thenReturn(SPLIT_SHADE_FULL_TRANSITION_DISTANCE);
        when(mView.getContext()).thenReturn(getContext());
        when(mView.findViewById(R.id.keyguard_header)).thenReturn(mKeyguardStatusBar);
        when(mView.findViewById(R.id.notification_stack_scroller))
                .thenReturn(mNotificationStackScrollLayout);
        when(mNotificationStackScrollLayoutController.getHeight()).thenReturn(1000);
        when(mNotificationStackScrollLayoutController.getHeadsUpCallback())
                .thenReturn(mHeadsUpCallback);
        ViewGroup rootView = mock(ViewGroup.class);
        when(rootView.isVisibleToUser()).thenReturn(true);
        when(mView.getRootView()).thenReturn(rootView);
        mNotificationContainerParent = new NotificationsQuickSettingsContainer(getContext(), null);
        mNotificationContainerParent.onFinishInflate();
        when(mView.findViewById(R.id.notification_container_parent))
                .thenReturn(mNotificationContainerParent);
        when(mFragmentService.getFragmentHostManager(mView)).thenReturn(mFragmentHostManager);
        FlingAnimationUtils.Builder flingAnimationUtilsBuilder = new FlingAnimationUtils.Builder(
                mDisplayMetrics);
        when(mQs.getView()).thenReturn(mView);
        when(mQSFragment.getView()).thenReturn(mView);
        when(mNaturalScrollingSettingObserver.isNaturalScrollingEnabled()).thenReturn(true);
        doAnswer(invocation -> {
            mFragmentListener = invocation.getArgument(1);
            return null;
        }).when(mFragmentHostManager).addTagListener(eq(QS.TAG), any());
        doAnswer((Answer<Void>) invocation -> {
            mTouchHandler = invocation.getArgument(0);
            return null;
        }).when(mView).setOnTouchListener(any(NotificationPanelViewController.TouchHandler.class));

        var displayMock = display();
        when(mView.getDisplay()).thenReturn(displayMock);
        // Any edge transition
        when(mKeyguardTransitionInteractor.transition(any()))
                .thenReturn(emptyFlow());
        when(mKeyguardTransitionInteractor.transition(any(), any()))
                .thenReturn(emptyFlow());

        // Dreaming->Lockscreen
        when(mDreamingToLockscreenTransitionViewModel.getLockscreenAlpha())
                .thenReturn(emptyFlow());
        when(mDreamingToLockscreenTransitionViewModel.lockscreenTranslationY(anyInt()))
                .thenReturn(emptyFlow());

        NotificationsKeyguardViewStateRepository notifsKeyguardViewStateRepository =
                new NotificationsKeyguardViewStateRepository();
        NotificationsKeyguardInteractor notifsKeyguardInteractor =
                new NotificationsKeyguardInteractor(notifsKeyguardViewStateRepository);
        NotificationWakeUpCoordinator coordinator =
                new NotificationWakeUpCoordinator(
                        mKosmos.getTestScope().getBackgroundScope(),
                        mDumpManager,
                        mock(HeadsUpManager.class),
                        new StatusBarStateControllerImpl(
                                new UiEventLoggerFake(),
                                mJavaAdapter,
                                () -> mKeyguardInteractor,
                                () -> mKeyguardTransitionInteractor,
                                () -> mShadeInteractor,
                                () -> mKosmos.getDeviceUnlockedInteractor(),
                                () -> mKosmos.getSceneInteractor(),
                                () -> mKosmos.getKeyguardClockInteractor(),
                                () -> mKosmos.getSceneBackInteractor(),
                                () -> mKosmos.getAlternateBouncerInteractor(),
                                () -> mKosmos.getDeviceEntryInteractor()),
                        mKeyguardBypassController,
                        mDozeParameters,
                        mScreenOffAnimationController,
                        new NotificationWakeUpCoordinatorLogger(logcatLogBuffer()),
                        notifsKeyguardInteractor,
                        mKosmos.getCommunalInteractor(),
                        mKosmos.getPulseExpansionInteractor());
        mConfigurationController = new ConfigurationControllerImpl(mContext);
        PulseExpansionHandler expansionHandler = new PulseExpansionHandler(
                mContext,
                coordinator,
                mKeyguardBypassController,
                mHeadsUpManager,
                mConfigurationController,
                mStatusBarStateController,
                mFalsingManager,
                mShadeInteractor,
                mLockscreenShadeTransitionController,
                mDumpManager);
        when(mKeyguardStatusBarViewComponentFactory.build(any(), any()))
                .thenReturn(mKeyguardStatusBarViewComponent);
        when(mKeyguardStatusBarViewComponent.getKeyguardStatusBarViewController())
                .thenReturn(mKeyguardStatusBarViewController);
        when(mNotificationRemoteInputManager.isRemoteInputActive())
                .thenReturn(false);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mNotificationShadeWindowController).batchApplyWindowLayoutParams(any());
        when(mNotificationShadeWindowController.getWindowRootView()).thenReturn(rootView);
        doAnswer(invocation -> {
            mLayoutChangeListener = invocation.getArgument(0);
            return null;
        }).when(mView).addOnLayoutChangeListener(any());

        when(mView.getViewTreeObserver()).thenReturn(mViewTreeObserver);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ViewTreeObserver.OnGlobalLayoutListener gll = invocation.getArgument(0);
                gll.onGlobalLayout();
                return null;
            }
        }).when(mViewTreeObserver).addOnGlobalLayoutListener(any());
        when(mView.getParent()).thenReturn(mViewParent);
        when(mDownMotionEvent.getAction()).thenReturn(MotionEvent.ACTION_DOWN);
        when(mSysUiState.setFlag(anyLong(), anyBoolean())).thenReturn(mSysUiState);

        mMainHandler = new Handler(Looper.getMainLooper());

        UdfpsAccessibilityOverlayOverlappingTouchHandlingView touchHandlingView =
                mock(UdfpsAccessibilityOverlayOverlappingTouchHandlingView.class);
        when(mView.requireViewById(R.id.keyguard_long_press))
                .thenReturn(touchHandlingView);

        Resources longPressHandlingViewRes = mock(Resources.class);
        when(touchHandlingView.getResources()).thenReturn(longPressHandlingViewRes);
        when(longPressHandlingViewRes.getString(anyInt())).thenReturn("");

        mNotificationPanelViewController = new NotificationPanelViewController(
                mView,
                coordinator, expansionHandler, mDynamicPrivacyController, mKeyguardBypassController,
                mFalsingManager, new FalsingCollectorFake(),
                mKeyguardStateController,
                mStatusBarStateController,
                mNotificationShadeWindowController,
                mDozeLog, mDozeParameters, mCommandQueue, mVibratorHelper,
                mLatencyTracker, mPowerManager, mAccessibilityManager, 0, mUpdateMonitor,
                mMetricsLogger,
                mShadeLog,
                mConfigurationController,
                () -> flingAnimationUtilsBuilder, mShadeTouchableRegionManager,
                mConversationNotificationManager, mMediaHierarchyManager,
                mStatusBarKeyguardViewManager,
                mGutsManager,
                mNotificationsQSContainerController,
                mNotificationStackScrollLayoutController,
                mKeyguardStatusBarViewComponentFactory,
                mLockscreenShadeTransitionController,
                mScrimController,
                mMediaDataManager,
                mNotificationShadeDepthController,
                mAmbientState,
                mKeyguardMediaController,
                mTapAgainViewController,
                mNavigationModeController,
                mNavigationBarController,
                mQsController,
                mFragmentService,
                mStatusBarService,
                mShadeHeaderController,
                mScreenOffAnimationController,
                mLockscreenGestureLogger,
                mShadeExpansionStateManager,
                mShadeRepository,
                mSysUIUnfoldComponent,
                mSysUiState,
                mSysUIStateDisplaysInteractor,
                mKeyguardUnlockAnimationController,
                mKeyguardIndicationController,
                mNotificationListContainer,
                mUnlockedScreenOffAnimationController,
                systemClock,
                mKeyguardClockInteractor,
                mAlternateBouncerInteractor,
                mDreamingToLockscreenTransitionViewModel,
                mMainDispatcher,
                mKeyguardTransitionInteractor,
                mDumpManager,
                mKeyguardTouchHandlingViewModelFactory,
                mKeyguardInteractor,
                mActivityStarter,
                mSharedNotificationContainerInteractor,
                mActiveNotificationsInteractor,
                mShadeAnimationInteractor,
                mDeviceEntryFaceAuthInteractor,
                new ResourcesSplitShadeStateController(),
                mPowerInteractor,
                mKeyguardClockPositionAlgorithm,
                mMSDLPlayer,
                mBrightnessMirrorShowingRepository,
                new BlurConfig(0f, 0f),
                () -> mKosmos.getFakeShadeDisplaysRepository(),
                mWindowRootViewBlurInteractor,
                mContext);
        mNotificationPanelViewController.initDependencies(
                mCentralSurfaces,
                null,
                () -> {},
                mHeadsUpManager);
        mNotificationPanelViewController.setTrackingStartedListener(() -> {});
        mNotificationPanelViewController.setOpenCloseListener(
                new OpenCloseListener() {
                    @Override
                    public void onClosingFinished() {}

                    @Override
                    public void onOpenStarted() {}
                });
        // Create a set to which the class will add all animators used, so that we can
        // verify that they are all stopped.
        mNotificationPanelViewController.mTestSetOfAnimatorsUsed = new HashSet<>();
        ArgumentCaptor<View.OnAttachStateChangeListener> onAttachStateChangeListenerArgumentCaptor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);
        verify(mView, atLeast(1)).addOnAttachStateChangeListener(
                onAttachStateChangeListenerArgumentCaptor.capture());
        mOnAttachStateChangeListeners = onAttachStateChangeListenerArgumentCaptor.getAllValues();

        ArgumentCaptor<View.AccessibilityDelegate> accessibilityDelegateArgumentCaptor =
                ArgumentCaptor.forClass(View.AccessibilityDelegate.class);
        verify(mView).setAccessibilityDelegate(accessibilityDelegateArgumentCaptor.capture());
        mAccessibilityDelegate = accessibilityDelegateArgumentCaptor.getValue();
        mNotificationPanelViewController.getStatusBarStateController()
                .addCallback(mNotificationPanelViewController.getStatusBarStateListener());
        mNotificationPanelViewController.getShadeHeadsUpTracker()
                .setHeadsUpAppearanceController(mock(HeadsUpAppearanceController.class));
        verify(mNotificationStackScrollLayoutController)
                .setOnEmptySpaceClickListener(mEmptySpaceClickListenerCaptor.capture());

        when(mNotificationPanelViewControllerLazy.get())
                .thenReturn(mNotificationPanelViewController);
        mQuickSettingsController = new QuickSettingsControllerImpl(
                mNotificationPanelViewControllerLazy,
                mView,
                mQsFrameTranslateController,
                expansionHandler,
                mNotificationRemoteInputManager,
                mStatusBarKeyguardViewManager,
                mLightBarController,
                mNotificationStackScrollLayoutController,
                mLockscreenShadeTransitionController,
                mNotificationShadeDepthController,
                mShadeHeaderController,
                mShadeTouchableRegionManager,
                () -> mStatusBarLongPressGestureDetector,
                mKosmos.getSystemUiReferenceDisplaySubcomponentRepository(),
                () -> mKosmos.getShadeDisplaysInteractor(),
                mKeyguardStateController,
                mKeyguardBypassController,
                mScrimController,
                mMediaDataManager,
                mMediaHierarchyManager,
                mAmbientState,
                mScreenRecordUxController,
                mFalsingManager,
                mAccessibilityManager,
                mLockscreenGestureLogger,
                mMetricsLogger,
                () -> mKosmos.getInteractionJankMonitor(),
                mShadeLog,
                mDumpManager,
                mDeviceEntryFaceAuthInteractor,
                mShadeRepository,
                mShadeInteractor,
                mActiveNotificationsInteractor,
                mJavaAdapter,
                mCastController,
                new ResourcesSplitShadeStateController(),
                () -> mKosmos.getCommunalTransitionViewModel(),
                () -> mLargeScreenHeaderHelper,
                mWindowManagerProvider
        );
    }

    @After
    public void tearDown() {
        List<Animator> leakedAnimators = null;
        if (mNotificationPanelViewController != null) {
            mNotificationPanelViewController.cancelHeightAnimator();
            leakedAnimators = mNotificationPanelViewController.mTestSetOfAnimatorsUsed.stream()
                    .filter(Animator::isRunning).toList();
            mNotificationPanelViewController.mTestSetOfAnimatorsUsed.forEach(Animator::cancel);
        }
        if (mMainHandler != null) {
            mMainHandler.removeCallbacksAndMessages(null);
        }
        if (leakedAnimators != null) {
            assertThat(leakedAnimators).isEmpty();
        }
    }

    protected void enableSplitShade(boolean enabled) {
        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(enabled);
        mNotificationPanelViewController.updateResources();
    }

    protected boolean onTouchEvent(MotionEvent ev) {
        return mNotificationPanelViewController.handleExternalTouch(ev);
    }

    protected CoroutineDispatcher getMainDispatcher() {
        return mMainDispatcher;
    }
}
