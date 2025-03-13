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
 * limitations under the License.
 */

package com.android.systemui;

import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.view.KeyEvent.KEYCODE_BACK;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.window.BackEvent.EDGE_NONE;

import static com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler.DEBUG_MISSING_GESTURE;
import static com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler.DEBUG_MISSING_GESTURE_TAG;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_AWAKE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_COMMUNAL_HUB_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DEVICE_DOZING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DEVICE_DREAMING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DUAL_SHADE_ENABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_FREEFORM_ACTIVE_IN_DESKTOP_MODE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NAVIGATION_BAR_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_GOING_AWAY;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_VOICE_INTERACTION_WINDOW_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_WAKEFULNESS_TRANSITION;
import static com.android.systemui.shared.system.QuickStepContract.addInterface;
import static com.android.window.flags.Flags.betterDeskDeactivationInRecentsTransition;

import android.annotation.FloatRange;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;

import com.android.app.displaylib.PerDisplayRepository;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.util.ScreenshotRequest;
import com.android.systemui.LauncherProxyService.LauncherProxyListener;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.contextualeducation.GestureType;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.display.data.repository.DisplayRepository;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardWmStateRefactor;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.ui.view.InWindowLauncherUnlockAnimationManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.model.SysUiState.SysUiStateCallback;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.navigationbar.views.NavigationBar;
import com.android.systemui.navigationbar.views.NavigationBarView;
import com.android.systemui.navigationbar.views.buttons.KeyButtonView;
import com.android.systemui.process.ProcessWrapper;
import com.android.systemui.recents.ScreenPinningRequest;
import com.android.systemui.scene.domain.interactor.SceneInteractor;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.shade.display.StatusBarTouchShadeDisplayPolicy;
import com.android.systemui.shade.display.domain.interactor.ShadeExpansionTargetDisplayInteractor;
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor;
import com.android.systemui.shared.recents.ILauncherProxy;
import com.android.systemui.shared.recents.ISystemUiProxy;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.QuickStepContract.SystemUiStateFlags;
import com.android.systemui.shared.system.actioncorner.ActionCornerConstants.Action;
import com.android.systemui.shared.system.smartspace.ISysuiUnlockAnimationController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.StatusBarWindowCallback;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.unfold.progress.UnfoldTransitionProgressForwarder;
import com.android.systemui.user.domain.interactor.HeadlessSystemUserMode;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.shared.desktopmode.DesktopState;
import com.android.wm.shell.sysui.ShellInterface;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Class to send information from SysUI to Launcher with a binder.
 */
@SysUISingleton
public class LauncherProxyService implements CallbackController<LauncherProxyListener>,
        NavigationModeController.ModeChangedListener, Dumpable {

    @VisibleForTesting
    static final String ACTION_QUICKSTEP = "android.intent.action.QUICKSTEP_SERVICE";

    public static final String TAG_OPS = "LauncherProxyService";
    private static final long BACKOFF_MILLIS = 1000;
    private static final long DEFERRED_CALLBACK_MILLIS = 5000;
    // Max backoff caps at 5 mins
    private static final long MAX_BACKOFF_MILLIS = 10 * 60 * 1000;

    private final Context mContext;
    private final Executor mMainExecutor;
    private final ShellInterface mShellInterface;
    private final Lazy<ShadeViewController> mShadeViewControllerLazy;
    private final PerDisplayRepository<SysUiState> mPerDisplaySysUiStateRepository;
    private final DisplayRepository mDisplayRepository;
    private final HeadlessSystemUserMode mHeadlessSystemUserMode;
    private SysUiState mDefaultDisplaySysUIState;
    private final Handler mHandler;
    private final Lazy<NavigationBarController> mNavBarControllerLazy;
    private final ScreenPinningRequest mScreenPinningRequest;
    private final NotificationShadeWindowController mStatusBarWinController;
    private final Provider<SceneInteractor> mSceneInteractor;
    private final StatusBarTouchShadeDisplayPolicy mShadeDisplayPolicy;

    private final ShadeExpansionTargetDisplayInteractor mShadeExpansionTargetDisplayInteractor;

    private final Runnable mConnectionRunnable = () ->
            internalConnectToCurrentUser("runnable: startConnectionToCurrentUser");
    private final ComponentName mRecentsComponentName;
    private final List<LauncherProxyListener> mConnectionCallbacks = new ArrayList<>();
    private final Intent mQuickStepIntent;
    private final ScreenshotHelper mScreenshotHelper;
    private final CommandQueue mCommandQueue;
    private final UserTracker mUserTracker;
    private final ISysuiUnlockAnimationController mSysuiUnlockAnimationController;
    private final Optional<UnfoldTransitionProgressForwarder> mUnfoldTransitionProgressForwarder;
    private final UiEventLogger mUiEventLogger;
    private final DisplayTracker mDisplayTracker;
    private Region mActiveNavBarRegion;

    private final BroadcastDispatcher mBroadcastDispatcher;
    private final BackAnimation mBackAnimation;

    private final DesktopState mDesktopState;
    private final ShadeModeInteractor mShadeModeInteractor;

    private ILauncherProxy mLauncherProxy;
    private int mConnectionBackoffAttempts;
    private boolean mBound;
    private boolean mIsEnabled;
    // This is set to false when the launcher service is requested to be bound until it is notified
    // that the previous service has been cleaned up in ILauncherProxy#onUnbind(). It is also set to
    // true after a 1000ms timeout by mDeferredBindAfterTimedOutCleanup.
    private boolean mIsPrevServiceCleanedUp = true;

    private boolean mIsSystemOrVisibleBgUser;
    private int mCurrentBoundedUserId = -1;
    private int mNavBarMode = NAV_BAR_MODE_3BUTTON;

    @VisibleForTesting
    public ISystemUiProxy mSysUiProxy = new ISystemUiProxy.Stub() {
        @Override
        public void startScreenPinning(int taskId) {
            verifyCallerAndClearCallingIdentityPostMain("startScreenPinning", () ->
                    mScreenPinningRequest.showPrompt(taskId, false /* allowCancel */));
        }

        @Override
        public void stopScreenPinning() {
            verifyCallerAndClearCallingIdentityPostMain("stopScreenPinning", () -> {
                try {
                    ActivityTaskManager.getService().stopSystemLockTaskMode();
                } catch (RemoteException e) {
                    Log.e(TAG_OPS, "Failed to stop screen pinning");
                }
            });
        }

        @Override
        public void onStatusBarTouchEvent(MotionEvent event) {
            moveShadeWindowIfNeeded(event);
            if (shouldIgnoreEvent(event)) {
                Log.d(TAG_OPS, "Ignoring launcher swipe event for legacy shade due to touch event"
                        + " on display without notification shade");
                return;
            }
            verifyCallerAndClearCallingIdentityPostMain("onStatusBarTouchEvent", () -> {
                if (SceneContainerFlag.isEnabled()) {
                    //TODO(b/329863123) implement latency tracking for shade scene
                    Log.i(TAG_OPS, "Scene container enabled. Latency tracking not started.");

                    int action = event.getActionMasked();
                    if (action == ACTION_DOWN) {
                        // If scene framework is enabled, set the scene container window to
                        // visible so we can start dispatching touches to it.
                        mSceneInteractor.get().onRemoteUserInputStarted("launcher swipe");
                    }
                    mStatusBarWinController.getWindowRootView().dispatchTouchEvent(event);
                } else {
                    mShadeViewControllerLazy.get().handleExternalTouch(event);
                }
            });
        }

        @VisibleForTesting
        public void moveShadeWindowIfNeeded(MotionEvent event) {
            Trace.beginSection("LauncherProxyService#moveShadeWindowIfNeeded");
            // TODO: b/407496148 - Refactor to use DisplayMetricsRepository instead
            final DisplayInfo displayInfo = new DisplayInfo();
            mDisplayTracker.getDisplay(event.getDisplayId()).getDisplayInfo(displayInfo);
            // Gestures on the launcher homescreen always open the notifications shade.
            mShadeExpansionTargetDisplayInteractor.setExpansionIntentForNotificationElement(
                    event.getDisplayId());
            Trace.endSection();
        }

        @VisibleForTesting
        public boolean shouldIgnoreEvent(MotionEvent event) {
            if (!SceneContainerFlag.isEnabled()) {
                // For legacy shade case, don't attempt to handle touch events on display that
                // doesn't have the shade. They're handled with SceneContainerFlag enabled.
                return event.getDisplayId() != mShadeDisplayPolicy.getDisplayId().getValue();
            }
            return false;
        }

        @Override
        public void onStatusBarTrackpadEvent(MotionEvent event) {
            moveShadeWindowIfNeeded(event);
            verifyCallerAndClearCallingIdentityPostMain("onStatusBarTrackpadEvent", () -> {
                if (SceneContainerFlag.isEnabled()) {
                    int action = event.getActionMasked();
                    if (action == ACTION_DOWN) {
                        // If scene framework is enabled, set the scene container window to
                        // visible so we can start dispatching touches to it.
                        mSceneInteractor.get().onRemoteUserInputStarted("trackpad swipe");
                    }
                    mStatusBarWinController.getWindowRootView().dispatchTouchEvent(event);
                } else {
                    mShadeViewControllerLazy.get().handleExternalTouch(event);
                }
            });
        }

        @Override
        public void animateNavBarLongPress(boolean isTouchDown, boolean shrink, long durationMs) {
            verifyCallerAndClearCallingIdentityPostMain("animateNavBarLongPress", () ->
                    notifyAnimateNavBarLongPress(isTouchDown, shrink, durationMs));
        }

        @Override
        public void setOverrideHomeButtonLongPress(long duration, float slopMultiplier,
                boolean haptic) {
            verifyCallerAndClearCallingIdentityPostMain("setOverrideHomeButtonLongPress",
                    () -> notifySetOverrideHomeButtonLongPress(duration, slopMultiplier, haptic));
        }

        @Override
        public void onBackEvent(@Nullable KeyEvent keyEvent, int displayId) throws RemoteException {
            if (mBackAnimation != null && keyEvent != null) {
                if (DEBUG_MISSING_GESTURE && keyEvent.isCanceled()) {
                    Log.d(DEBUG_MISSING_GESTURE_TAG,
                            "Cancel back [launcher key event]: " + keyEvent);
                }
                mBackAnimation.setTriggerBack(!keyEvent.isCanceled());
                mBackAnimation.onBackMotion(/* touchX */ 0, /* touchY */ 0, keyEvent.getAction(),
                        EDGE_NONE, displayId);
            } else {
                onKeyEvent(KEYCODE_BACK, displayId);
            }
        }

        @Override
        public void onKeyEvent(int keycode, int displayId) {
            verifyCallerAndClearCallingIdentityPostMain(
                    "onKeyEvent " + KeyEvent.keyCodeToString(keycode) + " displayId=" + displayId,
                    () -> {
                        sendEvent(KeyEvent.ACTION_DOWN, keycode, displayId);
                        sendEvent(KeyEvent.ACTION_UP, keycode, displayId);
                    });
        }

        @Override
        public void onLongPressKeyEvent(int keycode, int displayId) throws RemoteException {
            verifyCallerAndClearCallingIdentityPostMain("onLongPressKeyEvent", () -> {
                sendEvent(KeyEvent.ACTION_DOWN, keycode, displayId, 0, 0);
                mHandler.postDelayed(() -> {
                    sendEvent(KeyEvent.ACTION_DOWN, keycode, displayId, 1,
                            KeyEvent.FLAG_LONG_PRESS);
                    sendEvent(KeyEvent.ACTION_UP, keycode, displayId, 0,
                            KeyEvent.FLAG_CANCELED);
                }, ViewConfiguration.getLongPressTimeout());
            });
        }

        @Override
        public void onImeSwitcherPressed() {
            // TODO(b/204901476) We're intentionally using the default display for now since
            // Launcher/Taskbar isn't display aware.
            mContext.getSystemService(InputMethodManager.class)
                    .onImeSwitchButtonClickFromSystem(mDisplayTracker.getDefaultDisplayId());
            mUiEventLogger.log(KeyButtonView.NavBarButtonEvent.NAVBAR_IME_SWITCHER_BUTTON_TAP);
        }

        @Override
        public void onImeSwitcherLongPress() {
            // TODO(b/204901476) We're intentionally using the default display for now since
            // Launcher/Taskbar isn't display aware.
            mContext.getSystemService(InputMethodManager.class)
                    .showInputMethodPickerFromSystem(true /* showAuxiliarySubtypes */,
                            InputMethodManager.IM_PICKER_ENTRY_POINT_DEFAULT,
                            mDisplayTracker.getDefaultDisplayId());
            mUiEventLogger.log(
                    KeyButtonView.NavBarButtonEvent.NAVBAR_IME_SWITCHER_BUTTON_LONGPRESS);
        }

        @Override
        public void updateContextualEduStats(boolean isTrackpadGesture, String gestureType) {
            verifyCallerAndClearCallingIdentityPostMain("updateContextualEduStats",
                    () -> mHandler.post(() -> LauncherProxyService.this.updateContextualEduStats(
                            isTrackpadGesture, GestureType.valueOf(gestureType))));
        }

        @Override
        public void setHomeRotationEnabled(boolean enabled) {
            verifyCallerAndClearCallingIdentityPostMain("setHomeRotationEnabled", () ->
                    mHandler.post(() -> notifyHomeRotationEnabled(enabled)));
        }

        @Override
        public void notifyTaskbarStatus(boolean visible, boolean stashed) {
            verifyCallerAndClearCallingIdentityPostMain("notifyTaskbarStatus", () ->
                    onTaskbarStatusUpdated(visible, stashed));
        }

        @Override
        public void notifyTaskbarAutohideSuspend(boolean suspend) {
            verifyCallerAndClearCallingIdentityPostMain("notifyTaskbarAutohideSuspend", () ->
                    onTaskbarAutohideSuspend(suspend));
        }

        @Override
        public void notifyRecentsButtonPositionChanged(Rect position) {
            verifyCallerAndClearCallingIdentityPostMain("notifyRecentsButtonPositionChanged", () ->
                    onRecentsButtonPositionChanged(position));
        }

        private boolean sendEvent(int action, int code, int displayId, int repeat, int flags) {
            long when = SystemClock.uptimeMillis();
            final KeyEvent ev = new KeyEvent(when, when, action, code, repeat,
                    0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                    flags | KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);
            ev.setDisplayId(displayId);
            return InputManagerGlobal.getInstance()
                    .injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        }

        private boolean sendEvent(int action, int code, int displayId) {
            return sendEvent(action, code, displayId, 0, 0);
        }

        @Override
        public void onOverviewShownDeprecated(boolean fromHome) {
            if (betterDeskDeactivationInRecentsTransition()) return;
            verifyCallerAndClearCallingIdentityPostMain("onOverviewShownDeprecated", () -> {
                for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
                    mConnectionCallbacks.get(i).onOverviewShown();
                }
            });
        }

        @Override
        public void onOverviewShown(int displayId) {
            if (!betterDeskDeactivationInRecentsTransition()) return;
            verifyCallerAndClearCallingIdentityPostMain("onOverviewShown", () -> {
                for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
                    mConnectionCallbacks.get(i).onOverviewShown();
                }
                mShellInterface.onOverviewShown(displayId);
            });
        }

        @Override
        public void onOverviewHidden(int displayId) {
            if (!betterDeskDeactivationInRecentsTransition()) return;
            verifyCallerAndClearCallingIdentityPostMain("onOverviewHidden", () -> {
                mShellInterface.onOverviewHidden(displayId);
            });
        }

        @Override
        public void onAssistantProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {
            verifyCallerAndClearCallingIdentityPostMain("onAssistantProgress", () ->
                    notifyAssistantProgress(progress));
        }

        @Override
        public void onAssistantGestureCompletion(float velocity) {
            verifyCallerAndClearCallingIdentityPostMain("onAssistantGestureCompletion", () ->
                    notifyAssistantGestureCompletion(velocity));
        }

        @Override
        public void startAssistant(Bundle bundle) {
            verifyCallerAndClearCallingIdentityPostMain("startAssistant", () ->
                    notifyStartAssistant(bundle));
        }

        @Override
        public void setAssistantOverridesRequested(int[] invocationTypes) {
            verifyCallerAndClearCallingIdentityPostMain("setAssistantOverridesRequested", () ->
                    notifyAssistantOverrideRequested(invocationTypes));
        }

        @Override
        public void notifyAccessibilityButtonClicked(int displayId) {
            verifyCallerAndClearCallingIdentity("notifyAccessibilityButtonClicked", () ->
                    AccessibilityManager.getInstance(mContext).notifyAccessibilityButtonClicked(
                            displayId));
        }

        @Override
        public void notifyAccessibilityButtonLongClicked() {
            verifyCallerAndClearCallingIdentity("notifyAccessibilityButtonLongClicked", () ->
                    AccessibilityManager.getInstance(mContext)
                            .notifyAccessibilityButtonLongClicked(
                                    mDisplayTracker.getDefaultDisplayId()));
        }

        @Override
        public void notifyPrioritizedRotation(@Surface.Rotation int rotation) {
            verifyCallerAndClearCallingIdentityPostMain("notifyPrioritizedRotation", () ->
                    notifyPrioritizedRotationInternal(rotation));
        }

        @Override
        public void takeScreenshot(ScreenshotRequest request) {
            mScreenshotHelper.takeScreenshot(request, mHandler, null);
        }

        @Override
        public void expandNotificationPanel() {
            verifyCallerAndClearCallingIdentityPostMain("expandNotificationPanel",
                    () -> mCommandQueue.handleSystemKey(new KeyEvent(KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN)));
        }

        @Override
        public void toggleNotificationPanel() {
            verifyCallerAndClearCallingIdentityPostMain("toggleNotificationPanel", () ->
                    mCommandQueue.toggleNotificationsPanel());
        }

        @Override
        public void toggleQuickSettingsPanel() {
            verifyCallerAndClearCallingIdentityPostMain("toggleQuickSettingsPanel", () ->
                    mCommandQueue.toggleQuickSettingsPanel());
        }

        private boolean verifyCaller(String reason) {
            final int callerId = Binder.getCallingUserHandle().getIdentifier();
            if (callerId != mCurrentBoundedUserId || mLauncherProxy == null) {
                Log.w(TAG_OPS, "Launcher called sysui with invalid user: " + callerId + ", reason: "
                        + reason);
                return false;
            }
            return true;
        }

        private <T> T verifyCallerAndClearCallingIdentity(String reason, Supplier<T> supplier) {
            if (!verifyCaller(reason)) {
                return null;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return supplier.get();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void verifyCallerAndClearCallingIdentity(String reason, Runnable runnable) {
            verifyCallerAndClearCallingIdentity(reason, () -> {
                runnable.run();
                return null;
            });
        }

        private void verifyCallerAndClearCallingIdentityPostMain(String reason, Runnable runnable) {
            verifyCallerAndClearCallingIdentity(reason, () -> mHandler.post(runnable));
        }
    };

    private final Runnable mDeferredConnectionCallback = () -> {
        Log.w(TAG_OPS, "Binder supposed established connection but actual connection to service "
            + "timed out, trying again");
        retryConnectionWithBackoff();
    };

    private final Runnable mDeferredBindAfterTimedOutCleanup = () -> {
        Log.w(TAG_OPS, "Timed out waiting for previous service to clean up, binding to new one");
        mIsPrevServiceCleanedUp = true;
        maybeBindService();
    };

    private final BroadcastReceiver mUserEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Objects.equals(intent.getAction(), Intent.ACTION_USER_UNLOCKED)) {
                // Start the launcher connection to the launcher service
                // Connect if user hasn't connected yet
                if (getProxy() == null) {
                    startConnectionToCurrentUser();
                }
            }
        }
    };

    private final BroadcastReceiver mLauncherStateChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // If adding, bind immediately
            if (Objects.equals(intent.getAction(), ACTION_PACKAGE_ADDED)) {
                updateEnabledAndBinding();
                return;
            }

            // ACTION_PACKAGE_CHANGED
            String[] compsList = intent.getStringArrayExtra(EXTRA_CHANGED_COMPONENT_NAME_LIST);
            if (compsList == null) {
                return;
            }

            // Only rebind for TouchInteractionService component from launcher
            ResolveInfo ri = context.getPackageManager()
                    .resolveService(new Intent(ACTION_QUICKSTEP), 0);
            if (ri == null) {
                return;
            }
            String interestingComponent = ri.serviceInfo.name;
            for (String component : compsList) {
                if (interestingComponent.equals(component)) {
                    Log.i(TAG_OPS, "Rebinding for component [" + component + "] change");
                    updateEnabledAndBinding();
                    return;
                }
            }
        }
    };

    private final ServiceConnection mLauncherServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG_OPS, "Launcher proxy service connected");
            mConnectionBackoffAttempts = 0;
            mHandler.removeCallbacks(mDeferredConnectionCallback);
            try {
                service.linkToDeath(mLauncherServiceDeathRcpt, 0);
            } catch (RemoteException e) {
                // Failed to link to death (process may have died between binding and connecting),
                // just unbind the service for now and retry again
                Log.e(TAG_OPS, "Lost connection to launcher service", e);
                disconnectFromLauncherService("Lost connection to launcher service");
                retryConnectionWithBackoff();
                return;
            }

            if (!Flags.launcherProxyServiceShortReconnect()) {
                // NOTE: When the flag is enabled, `mCurrentBoundedUSerId` is set when service
                // binding starts.
                mCurrentBoundedUserId = mUserTracker.getUserId();
            }
            mLauncherProxy = ILauncherProxy.Stub.asInterface(service);

            Bundle params = new Bundle();
            addInterface(mSysUiProxy, params);
            addInterface(mSysuiUnlockAnimationController, params);
            addInterface(mUnfoldTransitionProgressForwarder.orElse(null), params);
            // Add all the interfaces exposed by the shell
            mShellInterface.createExternalInterfaces(params);

            try {
                Log.d(TAG_OPS, "LauncherProxyService connected, initializing launcher proxy");
                mLauncherProxy.onInitialize(params);
            } catch (RemoteException e) {
                mCurrentBoundedUserId = -1;
                Log.e(TAG_OPS, "Failed to call onInitialize()", e);
            }
            dispatchNavButtonBounds();

            // Force-update the systemui state flags
            updateSystemUiStateFlags();
            notifySysUiStateFlagsForAllDisplays();
            notifyConnectionChanged();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG_OPS, "Null binding of '" + name + "', try reconnecting");
            mCurrentBoundedUserId = -1;
            retryConnectionWithBackoff();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG_OPS, "Binding died of '" + name + "', try reconnecting");
            mCurrentBoundedUserId = -1;
            retryConnectionWithBackoff();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG_OPS, "Service disconnected");
            // Do nothing
            mCurrentBoundedUserId = -1;
        }
    };

    /** Propagates the flags for all displays to be notified to Launcher. */
    @VisibleForTesting
    public void notifySysUiStateFlagsForAllDisplays() {
        var displays = mDisplayRepository.getDisplayIds().getValue();
        for (int displayId : displays) {
            var state = mPerDisplaySysUiStateRepository.get(displayId);
            if (state != null) {
                notifySystemUiStateFlags(state.getFlags(), displayId);
            }
        }
    }

    private final StatusBarWindowCallback mStatusBarWindowCallback = this::onStatusBarStateChanged;

    // This is the death handler for the binder from the launcher service
    private final IBinder.DeathRecipient mLauncherServiceDeathRcpt
            = this::cleanupAfterDeath;

    private final IVoiceInteractionSessionListener mVoiceInteractionSessionListener =
            new IVoiceInteractionSessionListener.Stub() {
        @Override
        public void onVoiceSessionShown() {
            // Do nothing
        }

        @Override
        public void onVoiceSessionHidden() {
            // Do nothing
        }

        @Override
        public void onVoiceSessionWindowVisibilityChanged(boolean visible) {
            mContext.getMainExecutor().execute(() ->
                    LauncherProxyService.this.onVoiceSessionWindowVisibilityChanged(visible));
        }

        @Override
        public void onSetUiHints(Bundle hints) {
            // Do nothing
        }

        @Override
        public void onSetInvocationEffectEnabled(boolean enabled) {
            // Do nothing
        }
    };

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    if (Flags.launcherProxyServiceShortReconnect()
                            && newUser == mCurrentBoundedUserId) {
                        return;
                    }
                    mConnectionBackoffAttempts = 0;
                    internalConnectToCurrentUser("User changed");
                }
            };

    private final SysUiStateCallback mSysUiStateCallback =
            new SysUiStateCallback() {
                @Override
                public void onSystemUiStateChanged(long sysUiFlags, int displayId) {
                    notifySystemUiStateFlags(sysUiFlags, displayId);
                }
            };
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Inject
    public LauncherProxyService(Context context,
            @Main Executor mainExecutor,
            CommandQueue commandQueue,
            ShellInterface shellInterface,
            Lazy<NavigationBarController> navBarControllerLazy,
            Lazy<ShadeViewController> shadeViewControllerLazy,
            ScreenPinningRequest screenPinningRequest,
            NavigationModeController navModeController,
            NotificationShadeWindowController statusBarWinController,
            PerDisplayRepository<SysUiState> perDisplaySysUiStateRepository,
            Provider<SceneInteractor> sceneInteractor,
            StatusBarTouchShadeDisplayPolicy shadeDisplayPolicy,
            ShadeExpansionTargetDisplayInteractor shadeExpansionTargetDisplayInteractor,
            UserTracker userTracker,
            UserManager userManager,
            WakefulnessLifecycle wakefulnessLifecycle,
            UiEventLogger uiEventLogger,
            DisplayTracker displayTracker,
            KeyguardUnlockAnimationController sysuiUnlockAnimationController,
            InWindowLauncherUnlockAnimationManager inWindowLauncherUnlockAnimationManager,
            AssistUtils assistUtils,
            DumpManager dumpManager,
            Optional<UnfoldTransitionProgressForwarder> unfoldTransitionProgressForwarder,
            BroadcastDispatcher broadcastDispatcher,
            Optional<BackAnimation> backAnimation,
            ProcessWrapper processWrapper,
            DisplayRepository displayRepository,
            DesktopState desktopState,
            HeadlessSystemUserMode headlessSystemUserMode,
            ShadeModeInteractor shadeModeInteractor
    ) {
        mHeadlessSystemUserMode = headlessSystemUserMode;
        // b/241601880: This component should only be running for primary users or
        // secondaryUsers when visibleBackgroundUsers are supported.
        boolean isSystemUser = processWrapper.isSystemUser();
        boolean isVisibleBackgroundUser =
                userManager.isVisibleBackgroundUsersSupported() && !userManager.isUserForeground();
        if (!isSystemUser && isVisibleBackgroundUser) {
            Log.d(TAG_OPS, "Initialization for visibleBackgroundUser");
        }
        mIsSystemOrVisibleBgUser = isSystemUser || isVisibleBackgroundUser;
        if (!mIsSystemOrVisibleBgUser) {
            Log.wtf(TAG_OPS, "Unexpected initialization for non-system foreground user",
                    new Throwable());
        }

        mContext = context;
        mMainExecutor = mainExecutor;
        mShellInterface = shellInterface;
        mShadeViewControllerLazy = shadeViewControllerLazy;
        mHandler = new Handler();
        mNavBarControllerLazy = navBarControllerLazy;
        mScreenPinningRequest = screenPinningRequest;
        mStatusBarWinController = statusBarWinController;
        mSceneInteractor = sceneInteractor;
        mShadeDisplayPolicy = shadeDisplayPolicy;
        mShadeExpansionTargetDisplayInteractor = shadeExpansionTargetDisplayInteractor;
        mUserTracker = userTracker;
        mConnectionBackoffAttempts = 0;
        mRecentsComponentName = ComponentName.unflattenFromString(context.getString(
                com.android.internal.R.string.config_recentsComponentName));
        mQuickStepIntent = new Intent(ACTION_QUICKSTEP)
                .setPackage(mRecentsComponentName.getPackageName());
        mPerDisplaySysUiStateRepository = perDisplaySysUiStateRepository;
        mDisplayRepository = displayRepository;
        mDefaultDisplaySysUIState = perDisplaySysUiStateRepository.get(Display.DEFAULT_DISPLAY);
        mDefaultDisplaySysUIState.addCallback(mSysUiStateCallback);
        mUiEventLogger = uiEventLogger;
        mDisplayTracker = displayTracker;
        mUnfoldTransitionProgressForwarder = unfoldTransitionProgressForwarder;
        mBroadcastDispatcher = broadcastDispatcher;
        mBackAnimation = backAnimation.orElse(null);
        mDesktopState = desktopState;
        mShadeModeInteractor = shadeModeInteractor;

        if (!KeyguardWmStateRefactor.isEnabled()) {
            mSysuiUnlockAnimationController = sysuiUnlockAnimationController;
        } else {
            mSysuiUnlockAnimationController = inWindowLauncherUnlockAnimationManager;
        }

        dumpManager.registerDumpable(getClass().getSimpleName(), this);

        // Listen for nav bar mode changes
        mNavBarMode = navModeController.addListener(this);

        // Listen for launcher package changes
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(mRecentsComponentName.getPackageName(),
                PatternMatcher.PATTERN_LITERAL);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        mContext.registerReceiver(mLauncherStateChangedReceiver, filter);

        mBroadcastDispatcher.registerReceiver(mUserEventReceiver,
                new IntentFilter(Intent.ACTION_USER_UNLOCKED),
                null /* executor */, UserHandle.ALL);

        // Listen for status bar state changes
        statusBarWinController.registerCallback(mStatusBarWindowCallback);
        mScreenshotHelper = new ScreenshotHelper(context);

        commandQueue.addCallback(new CommandQueue.Callbacks() {

            // Listen for tracing state changes
            @Override
            public void onTracingStateChanged(boolean enabled) {
                // TODO(b/286509643) Cleanup callers of this; Unused downstream
            }

            @Override
            public void moveFocusedTaskToStageSplit(int displayId, boolean leftOrTop) {
                if (mLauncherProxy != null) {
                    try {
                        if (mDesktopState.canEnterDesktopMode()
                                && (mDefaultDisplaySysUIState.getFlags()
                                & SYSUI_STATE_FREEFORM_ACTIVE_IN_DESKTOP_MODE) != 0) {
                            return;
                        }
                        mLauncherProxy.enterStageSplitFromRunningApp(displayId, leftOrTop);
                    } catch (RemoteException e) {
                        Log.w(TAG_OPS, "Unable to enter stage split from the current running app");
                    }
                }
            }
        });
        mCommandQueue = commandQueue;

        // Listen for user setup
        mUserTracker.addCallback(mUserChangedCallback, mMainExecutor);

        wakefulnessLifecycle.addObserver(mWakefulnessLifecycleObserver);
        // Connect to the service
        updateEnabledAndBinding();

        // Listen for assistant changes
        assistUtils.registerVoiceInteractionSessionListener(mVoiceInteractionSessionListener);
    }

    public boolean isSystemOrVisibleBgUser() {
        return mIsSystemOrVisibleBgUser;
    }

    public void onVoiceSessionWindowVisibilityChanged(boolean visible) {
        mDefaultDisplaySysUIState.setFlag(SYSUI_STATE_VOICE_INTERACTION_WINDOW_SHOWING, visible)
                .commitUpdate(mContext.getDisplayId());
    }

    private void updateEnabledAndBinding() {
        updateEnabledState();
        startConnectionToCurrentUser();
    }

    private void updateSysUIStateForNavbars() {
        var displays = mDisplayRepository.getDisplayIds().getValue();
        for (int displayId : displays) {
            updateSysUIStateForNavbarWithDisplayId(displayId);
        }
    }

    private void updateSysUIStateForNavbarWithDisplayId(int displayId) {
        final NavigationBar navBarFragment =
                mNavBarControllerLazy.get().getNavigationBar(displayId);
        final NavigationBarView navBarView =
                mNavBarControllerLazy.get().getNavigationBarView(displayId);
        if (SysUiState.DEBUG) {
            Log.d(TAG_OPS, "Updating sysui state flags: navBarFragment=" + navBarFragment
                    + " navBarView=" + navBarView
                    + " shadeViewController=" + mShadeViewControllerLazy.get());
        }

        final SysUiState displaySysuiState = mPerDisplaySysUiStateRepository.get(displayId);
        if (displaySysuiState == null) return;

        if (navBarFragment != null) {
            navBarFragment.updateSystemUiStateFlags();
        }
        if (navBarView != null) {
            navBarView.updateDisabledSystemUiStateFlags(displaySysuiState);
        }

        displaySysuiState.setFlag(SYSUI_STATE_NAVIGATION_BAR_DISABLED,
                !mNavBarControllerLazy.get().canCreateNavBarOrTaskBar(displayId))
                .commitUpdate();
    }

    /** Force updates SystemUI state flags prior to sending them to Launcher. */
    public void updateSystemUiStateFlags() {
        updateSysUIStateForNavbars();
        mDefaultDisplaySysUIState.setFlag(SYSUI_STATE_DUAL_SHADE_ENABLED,
                mShadeModeInteractor.isDualShade());
        mShadeViewControllerLazy.get().updateSystemUiStateFlags();
        if (mStatusBarWinController != null) {
            mStatusBarWinController.notifyStateChangedCallbacks();
        }
    }

    private void notifySystemUiStateFlags(@SystemUiStateFlags long flags, int displayId) {
        if (SysUiState.DEBUG) {
            Log.d(TAG_OPS, "Notifying sysui state change to launcher service: proxy="
                    + mLauncherProxy + " display=" + displayId + " flags="
                    + QuickStepContract.getSystemUiStateString(flags) + " displayId=" + displayId);
        }
        try {
            if (mLauncherProxy != null) {
                mLauncherProxy.onSystemUiStateChanged(flags, displayId);
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to notify sysui state change", e);
        }
    }

    private void onStatusBarStateChanged(boolean keyguardShowing, boolean keyguardOccluded,
            boolean keyguardGoingAway, boolean bouncerShowing, boolean isDozing,
            boolean panelExpanded, boolean isDreaming, boolean communalShowing) {
        mDefaultDisplaySysUIState.setFlag(SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING,
                        keyguardShowing && !keyguardOccluded)
                .setFlag(SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED,
                        keyguardShowing && keyguardOccluded)
                .setFlag(SYSUI_STATE_STATUS_BAR_KEYGUARD_GOING_AWAY,
                        keyguardGoingAway)
                .setFlag(SYSUI_STATE_BOUNCER_SHOWING, bouncerShowing)
                .setFlag(SYSUI_STATE_DEVICE_DOZING, isDozing)
                .setFlag(SYSUI_STATE_DEVICE_DREAMING, isDreaming)
                .setFlag(SYSUI_STATE_COMMUNAL_HUB_SHOWING, communalShowing)
                .commitUpdate(mContext.getDisplayId());
        if (SceneContainerFlag.isEnabled()) {
            mDefaultDisplaySysUIState.setFlag(SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED,
                    panelExpanded);
        }
    }

    /**
     * Sets the navbar region which can receive touch inputs
     */
    public void onActiveNavBarRegionChanges(Region activeRegion) {
        mActiveNavBarRegion = activeRegion;
        dispatchNavButtonBounds();
    }

    private void dispatchNavButtonBounds() {
        if (mLauncherProxy != null && mActiveNavBarRegion != null) {
            try {
                mLauncherProxy.onActiveNavBarRegionChanges(mActiveNavBarRegion);
            } catch (RemoteException e) {
                Log.e(TAG_OPS, "Failed to call onActiveNavBarRegionChanges()", e);
            }
        }
    }

    /**
     * Notifies the launcher that an action corner has been activated, sending the corresponding
     * action and display id.
     */
    public void onActionCornerActivated(@Action int action, int displayId) {
        try {
            if (mLauncherProxy != null) {
                mLauncherProxy.onActionCornerActivated(action, displayId);
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call onActionCornerActivated()", e);
        }
    }

    public void cleanupAfterDeath() {
        mIsPrevServiceCleanedUp = true;
        startConnectionToCurrentUser();
    }

    public void startConnectionToCurrentUser() {
        Log.v(TAG_OPS, "startConnectionToCurrentUser: connection is restarted");
        if (mHandler.getLooper() != Looper.myLooper()) {
            mHandler.post(mConnectionRunnable);
        } else {
            internalConnectToCurrentUser("startConnectionToCurrentUser");
        }
    }

    private void internalConnectToCurrentUser(String reason) {
        if (!mIsSystemOrVisibleBgUser) {
            // This should not happen, but if any per-user SysUI component has a dependency on OPS,
            // then this could get triggered
            Log.w(TAG_OPS,
                    "Skipping connection to launcher service due to non-system foreground user "
                            + "caller");
            return;
        }
        disconnectFromLauncherService(reason);

        // If user has not setup yet or already connected, do not try to connect
        if (!isEnabled()) {
            Log.v(TAG_OPS, "Cannot attempt connection, is enabled " + isEnabled());
            return;
        }
        mHandler.removeCallbacks(mConnectionRunnable);

        maybeBindService();
    }

    private void maybeBindService() {
        if (!mIsPrevServiceCleanedUp) {
            Log.w(TAG_OPS, "Skipping connection to TouchInteractionService until previous"
                    + " instance is cleaned up.");
            if (!mHandler.hasCallbacks(mDeferredConnectionCallback)) {
                mHandler.postDelayed(mDeferredBindAfterTimedOutCleanup, BACKOFF_MILLIS);
            }
            return;
        }

        // Avoid creating TouchInteractionService because the System user in HSUM mode does not
        // interact with UI elements
        UserHandle currentUser = UserHandle.of(mUserTracker.getUserId());
        if (mHeadlessSystemUserMode.isHeadlessSystemUserMode() && currentUser.isSystem()) {
            Log.w(TAG_OPS,
                    "Skipping connection to TouchInteractionService for the System user in HSUM "
                            + "mode.");
            return;
        }
        try {
            mBound = mContext.bindServiceAsUser(mQuickStepIntent,
                    mLauncherServiceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                    currentUser);
        } catch (SecurityException e) {
            Log.e(TAG_OPS, "Unable to bind because of security error", e);
        }
        if (mBound) {
            if (Flags.launcherProxyServiceShortReconnect()) {
                mCurrentBoundedUserId = mUserTracker.getUserId();
            }
            mIsPrevServiceCleanedUp = false;
            // Ensure that connection has been established even if it thinks it is bound
            mHandler.postDelayed(mDeferredConnectionCallback, DEFERRED_CALLBACK_MILLIS);
        } else {
            // Retry after exponential backoff timeout
            retryConnectionWithBackoff();
        }
    }

    private void retryConnectionWithBackoff() {
        if (mHandler.hasCallbacks(mConnectionRunnable)) {
            return;
        }
        final long timeoutMs = (long) Math.min(
                Math.scalb(BACKOFF_MILLIS, mConnectionBackoffAttempts), MAX_BACKOFF_MILLIS);
        mHandler.postDelayed(mConnectionRunnable, timeoutMs);
        mConnectionBackoffAttempts++;
        Log.w(TAG_OPS, "Failed to connect on attempt " + mConnectionBackoffAttempts
                + " will try again in " + timeoutMs + "ms");
    }

    @Override
    public void addCallback(@NonNull LauncherProxyListener listener) {
        if (!mConnectionCallbacks.contains(listener)) {
            mConnectionCallbacks.add(listener);
        }
        listener.onConnectionChanged(mLauncherProxy != null);
    }

    @Override
    public void removeCallback(@NonNull LauncherProxyListener listener) {
        mConnectionCallbacks.remove(listener);
    }

    public boolean shouldShowSwipeUpUI() {
        return isEnabled() && !QuickStepContract.isLegacyMode(mNavBarMode);
    }

    public boolean isEnabled() {
        return mIsEnabled;
    }

    public ILauncherProxy getProxy() {
        return mLauncherProxy;
    }

    private void disconnectFromLauncherService(String disconnectReason) {
        Log.d(TAG_OPS, "disconnectFromLauncherService bound?: " + mBound +
                " currentProxy: " + mLauncherProxy + " disconnectReason: " + disconnectReason,
                new Throwable());
        if (mBound) {
            // Always unbind the service (ie. if called through onNullBinding or onBindingDied)
            mContext.unbindService(mLauncherServiceConnection);
            mBound = false;
            if (Flags.launcherProxyServiceShortReconnect()) {
                mCurrentBoundedUserId = -1;
            }
            if (mLauncherProxy != null) {
                try {
                    mLauncherProxy.onUnbind(new IRemoteCallback.Stub() {
                        @Override
                        public void sendResult(Bundle data) throws RemoteException {
                            // Received Launcher reply, try to bind anew.
                            mIsPrevServiceCleanedUp = true;
                            if (mHandler.hasCallbacks(mDeferredBindAfterTimedOutCleanup)) {
                                mHandler.removeCallbacks(mDeferredBindAfterTimedOutCleanup);
                                maybeBindService();
                            }
                        }
                    });
                } catch (RemoteException e) {
                    Log.w(TAG_OPS, "disconnectFromLauncherService failed to notify Launcher");
                    mIsPrevServiceCleanedUp = true;
                }
            } else if (Flags.launcherProxyServiceShortReconnect()) {
                mHandler.removeCallbacks(mDeferredConnectionCallback);
                mIsPrevServiceCleanedUp = true;
            }
        }

        if (mLauncherProxy != null) {
            mLauncherProxy.asBinder().unlinkToDeath(mLauncherServiceDeathRcpt, 0);
            mLauncherProxy = null;
            notifyConnectionChanged();
        }
    }

    /**
     * Updates contextual education stats when a gesture is triggered
     * @param isTrackpadGesture indicates if the gesture is triggered by trackpad
     * @param gestureType type of gesture triggered
     */
    public void updateContextualEduStats(boolean isTrackpadGesture, GestureType gestureType) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).updateContextualEduStats(isTrackpadGesture, gestureType);
        }
    }

    private void notifyHomeRotationEnabled(boolean enabled) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onHomeRotationEnabled(enabled);
        }
    }

    private void onTaskbarStatusUpdated(boolean visible, boolean stashed) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onTaskbarStatusUpdated(visible, stashed);
        }
    }

    private void onTaskbarAutohideSuspend(boolean suspend) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onTaskbarAutohideSuspend(suspend);
        }
    }

    public void onRecentsButtonPositionChanged(Rect position) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onRecentsButtonPositionChanged(position);
        }
    }

    private void notifyConnectionChanged() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onConnectionChanged(mLauncherProxy != null);
        }
    }

    private void notifyPrioritizedRotationInternal(@Surface.Rotation int rotation) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onPrioritizedRotation(rotation);
        }
    }

    private void notifyAssistantProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onAssistantProgress(progress);
        }
    }

    private void notifyAssistantGestureCompletion(float velocity) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onAssistantGestureCompletion(velocity);
        }
    }

    private void notifyStartAssistant(Bundle bundle) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).startAssistant(bundle);
        }
    }

    private void notifyAssistantOverrideRequested(int[] invocationTypes) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).setAssistantOverridesRequested(invocationTypes);
        }
    }

    private void notifyAnimateNavBarLongPress(boolean isTouchDown, boolean shrink,
            long durationMs) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).animateNavBarLongPress(isTouchDown, shrink, durationMs);
        }
    }

    private void notifySetOverrideHomeButtonLongPress(long duration, float slopMultiplier,
            boolean haptic) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i)
                    .setOverrideHomeButtonLongPress(duration, slopMultiplier, haptic);
        }
    }

    public void notifyAssistantVisibilityChanged(float visibility) {
        try {
            if (mLauncherProxy != null) {
                mLauncherProxy.onAssistantVisibilityChanged(visibility);
            } else {
                Log.e(TAG_OPS, "Failed to get launcher proxy for assistant visibility.");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call notifyAssistantVisibilityChanged()", e);
        }
    }

    private final WakefulnessLifecycle.Observer mWakefulnessLifecycleObserver =
            new WakefulnessLifecycle.Observer() {
                @Override
                public void onStartedWakingUp() {
                    mDefaultDisplaySysUIState
                            .setFlag(SYSUI_STATE_AWAKE, true)
                            .setFlag(SYSUI_STATE_WAKEFULNESS_TRANSITION, true)
                            .commitUpdate(mContext.getDisplayId());
                }

                @Override
                public void onFinishedWakingUp() {
                    mDefaultDisplaySysUIState
                            .setFlag(SYSUI_STATE_AWAKE, true)
                            .setFlag(SYSUI_STATE_WAKEFULNESS_TRANSITION, false)
                            .commitUpdate(mContext.getDisplayId());
                }

                @Override
                public void onStartedGoingToSleep() {
                    mDefaultDisplaySysUIState
                            .setFlag(SYSUI_STATE_AWAKE, false)
                            .setFlag(SYSUI_STATE_WAKEFULNESS_TRANSITION, true)
                            .commitUpdate(mContext.getDisplayId());
                }

                @Override
                public void onFinishedGoingToSleep() {
                    mDefaultDisplaySysUIState
                            .setFlag(SYSUI_STATE_AWAKE, false)
                            .setFlag(SYSUI_STATE_WAKEFULNESS_TRANSITION, false)
                            .commitUpdate(mContext.getDisplayId());
                }
            };

    public void notifyToggleRecentApps() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onToggleRecentApps();
        }
    }

    public void disable(int displayId, int state1, int state2, boolean animate) {
        try {
            if (mLauncherProxy != null) {
                mLauncherProxy.disable(displayId, state1, state2, animate);
            } else {
                Log.e(TAG_OPS, "Failed to get launcher proxy for disable flags.");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call disable()", e);
        }
    }

    public void onRotationProposal(int rotation, boolean isValid) {
        try {
            if (mLauncherProxy != null) {
                mLauncherProxy.onRotationProposal(rotation, isValid);
            } else {
                Log.e(TAG_OPS, "Failed to get launcher proxy for proposing rotation.");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call onRotationProposal()", e);
        }
    }

    public void onSystemBarAttributesChanged(int displayId, int behavior) {
        try {
            if (mLauncherProxy != null) {
                mLauncherProxy.onSystemBarAttributesChanged(displayId, behavior);
            } else {
                Log.e(TAG_OPS, "Failed to get launcher proxy for system bar attr change.");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call onSystemBarAttributesChanged()", e);
        }
    }

    public void onNavButtonsDarkIntensityChanged(float darkIntensity) {
        try {
            if (mLauncherProxy != null) {
                mLauncherProxy.onNavButtonsDarkIntensityChanged(darkIntensity);
            } else {
                Log.e(TAG_OPS, "Failed to get launcher proxy to update nav buttons dark intensity");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call onNavButtonsDarkIntensityChanged()", e);
        }
    }

    public void onNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {
        try {
            if (mLauncherProxy != null) {
                mLauncherProxy.onNavigationBarLumaSamplingEnabled(displayId, enable);
            } else {
                Log.e(TAG_OPS, "Failed to get launcher proxy to enable/disable nav bar luma"
                        + "sampling");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call onNavigationBarLumaSamplingEnabled()", e);
        }
    }

    private void updateEnabledState() {
        final int currentUser = mUserTracker.getUserId();
        mIsEnabled = mContext.getPackageManager().resolveServiceAsUser(mQuickStepIntent,
                MATCH_SYSTEM_ONLY, currentUser) != null;
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(TAG_OPS + " state:");
        pw.print("  isConnected="); pw.println(mLauncherProxy != null);
        pw.print("  mIsEnabled="); pw.println(isEnabled());
        pw.print("  mRecentsComponentName="); pw.println(mRecentsComponentName);
        pw.print("  mQuickStepIntent="); pw.println(mQuickStepIntent);
        pw.print("  mBound="); pw.println(mBound);
        pw.print("  mCurrentBoundedUserId="); pw.println(mCurrentBoundedUserId);
        pw.print("  mConnectionBackoffAttempts="); pw.println(mConnectionBackoffAttempts);
        pw.print("  mActiveNavBarRegion="); pw.println(mActiveNavBarRegion);
        pw.print("  mNavBarMode="); pw.println(mNavBarMode);
        pw.print("  mIsPrevServiceCleanedUp="); pw.println(mIsPrevServiceCleanedUp);
        mDefaultDisplaySysUIState.dump(pw, args);
    }

    public interface LauncherProxyListener {
        default void onConnectionChanged(boolean isConnected) {}
        default void onPrioritizedRotation(@Surface.Rotation int rotation) {}
        default void onOverviewShown() {}
        /** Notify the recents app (overview) is started by 3-button navigation. */
        default void onToggleRecentApps() {}
        default void onHomeRotationEnabled(boolean enabled) {}
        default void onTaskbarStatusUpdated(boolean visible, boolean stashed) {}
        default void onTaskbarAutohideSuspend(boolean suspend) {}
        default void onAssistantProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {}
        default void onAssistantGestureCompletion(float velocity) {}
        default void onRecentsButtonPositionChanged(Rect position) {}
        default void startAssistant(Bundle bundle) {}
        default void setAssistantOverridesRequested(int[] invocationTypes) {}
        default void animateNavBarLongPress(boolean isTouchDown, boolean shrink, long durationMs) {}
        /** Set override of home button long press duration, touch slop multiplier, and haptic. */
        default void setOverrideHomeButtonLongPress(
                long override, float slopMultiplier, boolean haptic) {}
        /** Updates contextual education stats when target gesture type is triggered. */
        default void updateContextualEduStats(
                boolean isTrackpadGesture, GestureType gestureType) {}
    }

    /**
     * Shuts down this service at the end of a testcase.
     * <p>
     * The in-production service is never shuts down, and it was not designed with testing in mind.
     * This unregisters the mechanisms by which the service will be revived after a testcase.
     * <p>
     * NOTE: This is a stop-gap introduced when first added some tests to this class. It should
     * probably be replaced by proper lifecycle management on this class.
     */
    @VisibleForTesting()
    void shutdownForTest() {
        mContext.unregisterReceiver(mLauncherStateChangedReceiver);
        mIsEnabled = false;
        mHandler.removeCallbacks(mConnectionRunnable);
        disconnectFromLauncherService("Shutdown for test");
    }
}
