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
package com.android.systemui.navigationbar.gestural;

import static android.content.pm.ActivityInfo.CONFIG_FONT_SCALE;
import static android.view.InputDevice.SOURCE_MOUSE;
import static android.view.InputDevice.SOURCE_TOUCHPAD;
import static android.view.MotionEvent.TOOL_TYPE_FINGER;
import static android.view.MotionEvent.TOOL_TYPE_MOUSE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;

import static com.android.systemui.Flags.edgebackGestureHandlerGetRunningTasksBackground;
import static com.android.systemui.classifier.Classifier.BACK_GESTURE;
import static com.android.systemui.navigationbar.gestural.Utilities.isTrackpadThreeFingerSwipe;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_TOUCHPAD_GESTURES_DISABLED;
import static com.android.wm.shell.windowdecor.DragResizeWindowGeometry.isEdgeResizePermitted;

import static org.lineageos.internal.util.DeviceKeysConstants.Action;

import static java.util.stream.Collectors.joining;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.companion.virtualdevice.flags.Flags;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.icu.text.SimpleDateFormat;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.window.BackEvent;

import androidx.annotation.DimenRes;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.policy.GestureNavigationSettingsObserver;
import com.android.systemui.LauncherProxyService;
import com.android.systemui.contextualeducation.GestureType;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.navigationbar.gestural.domain.GestureInteractor;
import com.android.systemui.navigationbar.gestural.domain.TaskMatcher;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.NavigationEdgeBackPlugin;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.QuickStepContract.SystemUiStateFlags;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.topui.TopUiController;
import com.android.systemui.util.concurrency.BackPanelUiThread;
import com.android.systemui.util.concurrency.UiThreadContext;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.desktopmode.api.DesktopMode;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.shared.desktopmode.DesktopState;

import lineageos.providers.LineageSettings;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import kotlin.Unit;

import kotlinx.coroutines.Job;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.inject.Provider;

/**
 * Utility class to handle edge swipes for back gesture
 */
public class EdgeBackGestureHandler {

    private static final String TAG = "EdgeBackGestureHandler";
    private static final int MAX_LONG_PRESS_TIMEOUT = SystemProperties.getInt(
            "gestures.back_timeout", 250);

    private static final int MAX_NUM_LOGGED_PREDICTIONS = 10;
    private static final int MAX_NUM_LOGGED_GESTURES = 10;

    public static final boolean DEBUG_MISSING_GESTURE = false;
    public static final String DEBUG_MISSING_GESTURE_TAG = "NoBackGesture";

    private ISystemGestureExclusionListener mGestureExclusionListener =
            new ISystemGestureExclusionListener.Stub() {
                @Override
                public void onSystemGestureExclusionChanged(int displayId,
                        Region systemGestureExclusion, Region unrestrictedOrNull) {
                    if (displayId == mMainDisplayId) {
                        mUiThreadContext.getExecutor().execute(() -> {
                            mExcludeRegion.set(systemGestureExclusion);
                            mUnrestrictedExcludeRegion.set(unrestrictedOrNull != null
                                    ? unrestrictedOrNull : systemGestureExclusion);
                        });
                    }
                }
            };

    private LauncherProxyService.LauncherProxyListener mQuickSwitchListener =
            new LauncherProxyService.LauncherProxyListener() {
                @Override
                public void onPrioritizedRotation(@Surface.Rotation int rotation) {
                    mStartingQuickstepRotation = rotation;
                    updateDisabledForQuickstep(mLastReportedConfig);
                }
            };

    private TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            updateTopActivity();
        }
        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) {
            if (componentName != null) {
                mPackageName = componentName.getPackageName();
            } else {
                mPackageName = "_UNKNOWN";
            }
        }
    };

    private DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    if (DeviceConfig.NAMESPACE_SYSTEMUI.equals(properties.getNamespace())
                            && (properties.getKeyset().contains(
                                    SystemUiDeviceConfigFlags.BACK_GESTURE_ML_MODEL_THRESHOLD)
                            || properties.getKeyset().contains(
                                    SystemUiDeviceConfigFlags.USE_BACK_GESTURE_ML_MODEL)
                            || properties.getKeyset().contains(
                                    SystemUiDeviceConfigFlags.BACK_GESTURE_ML_MODEL_NAME))) {
                        updateMLModelState();
                    }
                }
            };

    private final Context mContext;
    private final UserTracker mUserTracker;
    private final LauncherProxyService mLauncherProxyService;
    private final SysUiState mSysUiState;
    private Runnable mStateChangeCallback;
    private Consumer<Boolean> mButtonForcedVisibleCallback;

    private final NavigationModeController mNavigationModeController;
    private final BackPanelController.Factory mBackPanelControllerFactory;
    private final ViewConfiguration mViewConfiguration;
    private final WindowManager mDefaultWindowManager;
    private final IWindowManager mWindowManagerService;
    private final InputManager mInputManager;
    private final Optional<Pip> mPipOptional;
    private final Optional<DesktopMode> mDesktopModeOptional;
    private final FalsingManager mFalsingManager;
    private final Configuration mLastReportedConfig = new Configuration();

    private final Point mDisplaySize = new Point();
    private final int mMainDisplayId;

    private final UiThreadContext mUiThreadContext;
    private final Handler mBgHandler;
    private final Executor mBackgroundExecutor;

    private final Rect mPipExcludedBounds = new Rect();
    private final Region mExcludeRegion = new Region();
    private final Region mDesktopModeExcludeRegion = new Region();
    private final Region mUnrestrictedExcludeRegion = new Region();
    private final Provider<BackGestureTfClassifierProvider>
            mBackGestureTfClassifierProviderProvider;
    private final Provider<LightBarController> mLightBarControllerProvider;

    private final GestureInteractor mGestureInteractor;
    private final ArraySet<ComponentName> mBlockedActivities = new ArraySet<>();
    private Job mBlockedActivitiesJob = null;

    private final JavaAdapter mJavaAdapter;

    // The left side edge width where touch down is allowed
    private int mEdgeWidthLeft;
    // The right side edge width where touch down is allowed
    private int mEdgeWidthRight;
    // The bottom gesture area height
    private float mBottomGestureHeight;
    // The slop to distinguish between horizontal and vertical motion
    private float mTouchSlop;
    // The threshold for back swipe full progress.
    private float mBackSwipeLinearThreshold;
    private float mNonLinearFactor;
    // Duration after which we consider the event as longpress.
    private final int mLongPressTimeout;
    private int mStartingQuickstepRotation = -1;
    // We temporarily disable back gesture when user is quickswitching
    // between apps of different orientations
    private boolean mDisabledForQuickstep;
    // This gets updated when the value of PipTransitionState#isInPip changes.
    private boolean mIsInPip;

    private final PointF mDownPoint = new PointF();
    private final PointF mEndPoint = new PointF();
    private AtomicBoolean mGestureBlockingActivityRunning = new AtomicBoolean();

    private boolean mThresholdCrossed = false;
    private boolean mAllowGesture = false;
    private boolean mLogGesture = false;
    private boolean mInRejectedExclusion = false;
    private boolean mIsOnLeftEdge;
    private boolean mDeferSetIsOnLeftEdge;

    private boolean mIsAttached;
    private boolean mIsGestureHandlingEnabled;
    private final Set<Integer> mTrackpadsConnected = new ArraySet<>();
    private boolean mInGestureNavMode;
    private boolean mUsingThreeButtonNav;
    private boolean mIsEnabled;
    private boolean mIsNavBarShownTransiently;
    private boolean mIsBackGestureAllowed;
    private boolean mIsLongSwipeEnabled;
    private boolean mIsTrackpadThreeFingerSwipe;
    private boolean mIsButtonForcedVisible;

    private final Map<Integer, DisplayBackGestureHandler> mDisplayBackGestureHandlers =
            new HashMap<>();

    private BackAnimation mBackAnimation;

    private int mLeftInset;
    private int mRightInset;
    @SystemUiStateFlags
    private long mSysUiFlags;
    private float mLongSwipeWidth;

    // For Tf-Lite model.
    private BackGestureTfClassifierProvider mBackGestureTfClassifierProvider;
    private Map<String, Integer> mVocab;
    private boolean mUseMLModel;
    private boolean mMLModelIsLoading;
    // minimum width below which we do not run the model
    private int mMLEnableWidth;
    private float mMLModelThreshold;
    private String mPackageName;
    private float mMLResults;

    // For debugging
    private LogArray mPredictionLog = new LogArray(MAX_NUM_LOGGED_PREDICTIONS);
    private LogArray mGestureLogInsideInsets = new LogArray(MAX_NUM_LOGGED_GESTURES);
    private LogArray mGestureLogOutsideInsets = new LogArray(MAX_NUM_LOGGED_GESTURES);
    private SimpleDateFormat mLogDateFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private Date mTmpLogDate = new Date();
    private int mLastDownEventDisplayId;


    private final DisplayManager mDisplayManager;
    private final DisplayBackGestureHandlerImpl.Factory mDisplayBackGestureHandlerFactory;
    private final DesktopState mDesktopState;

    private final GestureNavigationSettingsObserver mGestureNavigationSettingsObserver;
    private final TopUiController mTopUiController;

    private final NavigationEdgeBackPlugin.BackCallback mBackCallback =
            new NavigationEdgeBackPlugin.BackCallback() {
                @Override
                public void triggerBack(boolean isLongPress) {
                    // Notify FalsingManager that an intentional gesture has occurred.
                    mFalsingManager.isFalseTouch(BACK_GESTURE);
                    // Only inject back keycodes when ahead-of-time back dispatching is disabled.
                    if (mBackAnimation == null) {
                        boolean sendDown = sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK,
                                isLongPress ? KeyEvent.FLAG_LONG_SWIPE : 0);
                        boolean sendUp = sendEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK,
                                isLongPress ? KeyEvent.FLAG_LONG_SWIPE : 0);
                        if (DEBUG_MISSING_GESTURE) {
                            Log.d(DEBUG_MISSING_GESTURE_TAG, "Triggered back: down="
                                    + sendDown + ", up=" + sendUp);
                        }
                    } else {
                        mBackAnimation.setTriggerBack(true);
                    }

                    logGesture(mInRejectedExclusion
                            ? SysUiStatsLog.BACK_GESTURE__TYPE__COMPLETED_REJECTED
                            : SysUiStatsLog.BACK_GESTURE__TYPE__COMPLETED);
                    if (!mInRejectedExclusion) {
                        // Log successful back gesture to contextual edu stats
                        mLauncherProxyService.updateContextualEduStats(mIsTrackpadThreeFingerSwipe,
                                GestureType.BACK);
                    }
                }

                @Override
                public void cancelBack() {
                    if (mBackAnimation != null) {
                        mBackAnimation.setTriggerBack(false);
                    }
                    logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE);
                }

                @Override
                public void setTriggerBack(boolean triggerBack) {
                    if (mBackAnimation != null) {
                        mBackAnimation.setTriggerBack(triggerBack);
                    }
                }

                @Override
                public void setTriggerLongSwipe(boolean triggerLongSwipe) {
                    if (mBackAnimation != null) {
                        mBackAnimation.setTriggerLongSwipe(triggerLongSwipe);
                    }
                }
            };

    private final SysUiState.SysUiStateCallback mSysUiStateCallback =
            new SysUiState.SysUiStateCallback() {
                @Override
                public void onSystemUiStateChanged(@SystemUiStateFlags long sysUiFlags,
                        int displayId) {
                    mSysUiFlags = sysUiFlags;
                }
            };

    private final Consumer<Boolean> mOnIsInPipStateChangedListener =
            (isInPip) -> mIsInPip = isInPip;

    private final Consumer<Region> mDesktopCornersChangedListener =
            (desktopExcludeRegion) -> mDesktopModeExcludeRegion.set(desktopExcludeRegion);

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    updateIsEnabled();
                    updateCurrentUserResources();
                }
            };

    private final InputManager.InputDeviceListener mInputDeviceListener =
            new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
            if (isTrackpadDevice(deviceId)) {
                // This updates the gesture handler state and should be running on the main thread.
                mUiThreadContext.getHandler().post(() -> {
                    boolean wasEmpty = mTrackpadsConnected.isEmpty();
                    mTrackpadsConnected.add(deviceId);
                    if (wasEmpty) {
                        update();
                    }
                });
            }
        }

        @Override
        public void onInputDeviceChanged(int deviceId) { }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            // This updates the gesture handler state and should be running on the main thread.
            mUiThreadContext.getHandler().post(() -> {
                mTrackpadsConnected.remove(deviceId);
                if (mTrackpadsConnected.isEmpty()) {
                    update();
                }
            });
        }

        private void update() {
            if (mIsEnabled && !mTrackpadsConnected.isEmpty()) {
                // Don't reinitialize gesture handling due to trackpad connecting when it's
                // already set up.
                return;
            }
            updateIsEnabled();
            updateCurrentUserResources();
        }

        private boolean isTrackpadDevice(int deviceId) {
            // This is a blocking binder call that should run on a bg thread.
            InputDevice inputDevice = mInputManager.getInputDevice(deviceId);
            if (inputDevice == null) {
                return false;
            }
            return inputDevice.getSources() == (SOURCE_MOUSE | SOURCE_TOUCHPAD);
        }
    };

    /**
     * Factory for EdgeBackGestureHandler. Necessary because per-display contexts can't be injected.
     * With this, you can pass in a specific context that knows what display it is in.
     */
    @AssistedFactory
    public interface Factory {
        /**
         * Creates a new EdgeBackGestureHandler with the given context.
         */
        EdgeBackGestureHandler create(Context context);
    }

    @AssistedInject
    EdgeBackGestureHandler(
            @Assisted Context context,
            LauncherProxyService launcherProxyService,
            SysUiState sysUiState,
            @BackPanelUiThread UiThreadContext uiThreadContext,
            @Background Executor backgroundExecutor,
            @Background Handler bgHandler,
            UserTracker userTracker,
            NavigationModeController navigationModeController,
            BackPanelController.Factory backPanelControllerFactory,
            ViewConfiguration viewConfiguration,
            WindowManager windowManager,
            IWindowManager windowManagerService,
            InputManager inputManager,
            Optional<Pip> pipOptional,
            Optional<DesktopMode> desktopModeOptional,
            FalsingManager falsingManager,
            Provider<BackGestureTfClassifierProvider> backGestureTfClassifierProviderProvider,
            Provider<LightBarController> lightBarControllerProvider,
            TopUiController topUiController,
            GestureInteractor gestureInteractor,
            JavaAdapter javaAdapter,
            DisplayManager displayManager,
            DisplayBackGestureHandlerImpl.Factory displayBackGestureHandlerFactory,
            DesktopState desktopState) {
        mContext = context;
        mMainDisplayId = context.getDisplayId();
        mUiThreadContext = uiThreadContext;
        mBackgroundExecutor = backgroundExecutor;
        mBgHandler = bgHandler;
        mUserTracker = userTracker;
        mLauncherProxyService = launcherProxyService;
        mSysUiState = sysUiState;
        mNavigationModeController = navigationModeController;
        mBackPanelControllerFactory = backPanelControllerFactory;
        mViewConfiguration = viewConfiguration;
        mDefaultWindowManager = windowManager;
        mWindowManagerService = windowManagerService;
        mInputManager = inputManager;
        mPipOptional = pipOptional;
        mDesktopModeOptional = desktopModeOptional;
        mFalsingManager = falsingManager;
        mBackGestureTfClassifierProviderProvider = backGestureTfClassifierProviderProvider;
        mLightBarControllerProvider = lightBarControllerProvider;
        mGestureInteractor = gestureInteractor;
        mJavaAdapter = javaAdapter;
        mLastReportedConfig.setTo(mContext.getResources().getConfiguration());
        mDisplayManager = displayManager;
        mDisplayBackGestureHandlerFactory = displayBackGestureHandlerFactory;
        mDesktopState = desktopState;

        ComponentName recentsComponentName = ComponentName.unflattenFromString(
                context.getString(com.android.internal.R.string.config_recentsComponentName));
        if (recentsComponentName != null) {
            String recentsPackageName = recentsComponentName.getPackageName();
            PackageManager manager = context.getPackageManager();
            try {
                Resources resources = manager.getResourcesForApplication(
                        manager.getApplicationInfo(recentsPackageName,
                                PackageManager.MATCH_UNINSTALLED_PACKAGES
                                        | PackageManager.MATCH_DISABLED_COMPONENTS
                                        | PackageManager.GET_SHARED_LIBRARY_FILES));
                int resId = resources.getIdentifier(
                        "back_gesture_blocking_activities", "array", recentsPackageName);

                if (resId == 0) {
                    Log.e(TAG, "No resource found for gesture-blocking activities");
                } else {
                    String[] gestureBlockingActivities = resources.getStringArray(resId);
                    for (String gestureBlockingActivity : gestureBlockingActivities) {
                        final ComponentName component =
                                ComponentName.unflattenFromString(gestureBlockingActivity);

                        if (component != null) {
                            mGestureInteractor.addGestureBlockedMatcher(
                                    new TaskMatcher.TopActivityComponent(component),
                                    GestureInteractor.Scope.Local);
                        }
                    }
                }
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Failed to add gesture blocking activities", e);
            }
        }
        mLongPressTimeout = Math.min(MAX_LONG_PRESS_TIMEOUT,
                Flags.viewconfigurationApis()
                        ? ViewConfiguration.get(context).getLongPressTimeoutMillis()
                        : ViewConfiguration.getLongPressTimeout());

        mGestureNavigationSettingsObserver = new GestureNavigationSettingsObserver(
                mUiThreadContext.getHandler(), bgHandler, mContext,
                this::onNavigationSettingsChanged);

        updateCurrentUserResources();
        mTopUiController = topUiController;
    }

    public void setStateChangeCallback(Runnable callback) {
        mStateChangeCallback = callback;
    }

    public void setButtonForcedVisibleChangeCallback(Consumer<Boolean> callback) {
        mButtonForcedVisibleCallback = callback;
    }

    public int getEdgeWidthLeft() {
        return mEdgeWidthLeft;
    }

    public int getEdgeWidthRight() {
        return mEdgeWidthRight;
    }

    public void updateCurrentUserResources() {
        Resources res = mNavigationModeController.getCurrentUserContext().getResources();
        mEdgeWidthLeft = mGestureNavigationSettingsObserver.getLeftSensitivity(res);
        mEdgeWidthRight = mGestureNavigationSettingsObserver.getRightSensitivity(res);
        final boolean previousForcedVisible = mIsButtonForcedVisible;
        mIsButtonForcedVisible =
                mGestureNavigationSettingsObserver.areNavigationButtonForcedVisible();
        // Update this before calling mButtonForcedVisibleCallback since NavigationBar will relayout
        // and query isHandlingGestures() as a part of the callback
        mIsBackGestureAllowed = !mIsButtonForcedVisible;
        if (previousForcedVisible != mIsButtonForcedVisible
                && mButtonForcedVisibleCallback != null) {
            mButtonForcedVisibleCallback.accept(mIsButtonForcedVisible);
        }

        final DisplayMetrics dm = res.getDisplayMetrics();
        final float defaultGestureHeight = res.getDimension(
                com.android.internal.R.dimen.navigation_bar_gesture_height) / dm.density;
        final float gestureHeight = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.BACK_GESTURE_BOTTOM_HEIGHT,
                defaultGestureHeight);
        mBottomGestureHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, gestureHeight,
                dm);

        // Set the minimum bounds to activate ML to 12dp or the minimum of configured values
        mMLEnableWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12.0f, dm);
        if (mMLEnableWidth > mEdgeWidthRight) mMLEnableWidth = mEdgeWidthRight;
        if (mMLEnableWidth > mEdgeWidthLeft) mMLEnableWidth = mEdgeWidthLeft;

        mContext.getContentResolver().registerContentObserver(
                LineageSettings.System.getUriFor(LineageSettings.System.KEY_EDGE_LONG_SWIPE_ACTION),
                false, new ContentObserver(mUiThreadContext.getHandler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        mIsLongSwipeEnabled = Action.fromIntSafe(
                                LineageSettings.System.getInt(mContext.getContentResolver(),
                                        LineageSettings.System.KEY_EDGE_LONG_SWIPE_ACTION,
                                        Action.NOTHING.ordinal())) != Action.NOTHING;
                        updateLongSwipeWidth();
                    }
                });
        mContext.getContentResolver().notifyChange(LineageSettings.System.getUriFor(
                LineageSettings.System.KEY_EDGE_LONG_SWIPE_ACTION), null);

        // Reduce the default touch slop to ensure that we can intercept the gesture
        // before the app starts to react to it.
        // TODO(b/130352502) Tune this value and extract into a constant
        final float backGestureSlop = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                        SystemUiDeviceConfigFlags.BACK_GESTURE_SLOP_MULTIPLIER, 0.75f);
        mTouchSlop = mViewConfiguration.getScaledTouchSlop() * backGestureSlop;
        mBackSwipeLinearThreshold = res.getDimension(
                com.android.internal.R.dimen.navigation_edge_action_progress_threshold);
        mNonLinearFactor = getDimenFloat(res,
                com.android.internal.R.dimen.back_progress_non_linear_factor);
        updateBackAnimationThresholds();
        mBackgroundExecutor.execute(this::disableNavBarVirtualKeyHapticFeedback);
    }

    private float getDimenFloat(Resources res, @DimenRes int resId) {
        TypedValue typedValue = new TypedValue();
        res.getValue(resId, typedValue, true);
        return typedValue.getFloat();
    }

    private void onNavigationSettingsChanged() {
        boolean wasBackAllowed = isHandlingGestures();
        updateCurrentUserResources();
        if (mStateChangeCallback != null && wasBackAllowed != isHandlingGestures()) {
            mStateChangeCallback.run();
        }
    }

    private void updateTopActivity() {
        if (edgebackGestureHandlerGetRunningTasksBackground()) {
            mBackgroundExecutor.execute(() -> updateTopActivityPackageName());
        } else {
            updateTopActivityPackageName();
        }
    }

    /**
     * Called when the nav/task bar is attached.
     */
    public void onNavBarAttached() {
        mIsAttached = true;
        mLauncherProxyService.addCallback(mQuickSwitchListener);
        mSysUiState.addCallback(mSysUiStateCallback);
        mInputManager.registerInputDeviceListener(mInputDeviceListener, mBgHandler);
        int[] inputDevices = mInputManager.getInputDeviceIds();
        for (int inputDeviceId : inputDevices) {
            mInputDeviceListener.onInputDeviceAdded(inputDeviceId);
        }
        updateIsEnabled();
        mUserTracker.addCallback(mUserChangedCallback, mUiThreadContext.getExecutor());
    }

    /**
     * Called when the nav/task bar is detached.
     */
    public void onNavBarDetached() {
        mIsAttached = false;
        mLauncherProxyService.removeCallback(mQuickSwitchListener);
        mSysUiState.removeCallback(mSysUiStateCallback);
        mInputManager.unregisterInputDeviceListener(mInputDeviceListener);
        mTrackpadsConnected.clear();
        updateIsEnabled();
        mUserTracker.removeCallback(mUserChangedCallback);
    }

    /**
     * @see NavigationModeController.ModeChangedListener#onNavigationModeChanged
     */
    public void onNavigationModeChanged(int mode) {
        Trace.beginSection("EdgeBackGestureHandler#onNavigationModeChanged");
        try {
            mUsingThreeButtonNav = QuickStepContract.isLegacyMode(mode);
            mInGestureNavMode = QuickStepContract.isGesturalMode(mode);
            updateIsEnabled();
            updateCurrentUserResources();
        } finally {
            Trace.endSection();
        }
    }

    public void onNavBarTransientStateChanged(boolean isTransient) {
        mIsNavBarShownTransiently = isTransient;
    }

    /**
     * Called when a new display gets connected
     *
     * @param displayId The id associated with the connected display.
     */
    public void onDisplayAddSystemDecorations(int displayId) {
        if (mIsEnabled) {
            mUiThreadContext.runWithScissors(() -> {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    Log.w(TAG, "onDisplayAddSystemDecorations called for main display");
                    return;
                }
                Display display = mDisplayManager.getDisplay(displayId);
                if (display == null) {
                    Log.w(TAG, "onDisplayAddSystemDecorations: can't find display with id="
                            + displayId);
                    return;
                }
                if (!mDesktopState.isDesktopModeSupportedOnDisplay(display)) {
                    Log.w(TAG,
                            "onDisplayAddSystemDecorations: desktop mode not supported on display"
                                    + " with id=" + displayId);
                    return;
                }
                removeAndDisposeDisplayResource(displayId);
                try {
                    mDisplayBackGestureHandlers.put(
                            displayId, createDisplayBackGestureHandler(display));
                } catch (IllegalArgumentException e) {
                    // This can happen when a display is instantly removed after being added. Note
                    // that `isDesktopModeSupportedOnDisplay` above just returns `false` if the
                    // displayId is invalid by the time it's called, so we don't have to wrap it.
                    // Also note that if this operation fails indeed, nothing has been added to
                    // `mDisplayBackGestureHandlers`, so the state is correct afterwards.
                    Log.w(
                            TAG,
                            "Failed to initialize DisplayBackGestureHandler - display "
                                    + displayId
                                    + " already removed?",
                            e);
                }
            });
        }
    }

    /**
     * Called when a display gets disconnected
     *
     * @param displayId The id associated with the disconnected display.
     */
    public void onDisplayRemoveSystemDecorations(int displayId) {
        mUiThreadContext.runWithScissors(() -> removeAndDisposeDisplayResource(displayId));
    }

    private DisplayBackGestureHandler createDisplayBackGestureHandler(Display display) {
        Context windowContext = mContext;
        WindowManager displayWindowManager = mDefaultWindowManager;
        if (display.getDisplayId() != mMainDisplayId) {
            windowContext = mContext.createWindowContext(display,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, null);
            displayWindowManager = windowContext.getSystemService(WindowManager.class);
            if (displayWindowManager == null) {
                displayWindowManager = mDefaultWindowManager;
            }
        }
        return mDisplayBackGestureHandlerFactory.create(windowContext, displayWindowManager,
                mBackCallback, (ev) -> {
                    onInputEvent(ev);
                    return Unit.INSTANCE;
                });
    }

    private void removeAndDisposeDisplayResource(int displayId) {
        DisplayBackGestureHandler displayBackGestureHandler = mDisplayBackGestureHandlers.remove(
                displayId);
        if (displayBackGestureHandler != null) {
            displayBackGestureHandler.dispose();
        }
    }

    private void updateIsEnabled() {
        mUiThreadContext.runWithScissors(this::updateIsEnabledInner);
    }

    private void updateIsEnabledInner() {
        try {
            Trace.beginSection("EdgeBackGestureHandler#updateIsEnabled");

            mIsGestureHandlingEnabled = mInGestureNavMode || (mUsingThreeButtonNav
                    && !mTrackpadsConnected.isEmpty());
            boolean isEnabled = mIsAttached && mIsGestureHandlingEnabled;
            if (isEnabled == mIsEnabled) {
                return;
            }
            mIsEnabled = isEnabled;

            for (DisplayBackGestureHandler displayBackGestureHandler :
                    mDisplayBackGestureHandlers.values()) {
                displayBackGestureHandler.dispose();
            }
            mDisplayBackGestureHandlers.clear();

            if (!mIsEnabled) {
                mBackgroundExecutor.execute(mGestureNavigationSettingsObserver::unregister);
                if (DEBUG_MISSING_GESTURE) {
                    Log.d(DEBUG_MISSING_GESTURE_TAG, "Unregister display listener");
                }
                TaskStackChangeListeners.getInstance().unregisterTaskStackListener(
                        mTaskStackListener);
                DeviceConfig.removeOnPropertiesChangedListener(mOnPropertiesChangedListener);
                mPipOptional.ifPresent(pip -> pip.removeOnIsInPipStateChangedListener(
                        mOnIsInPipStateChangedListener));

                try {
                    mWindowManagerService.unregisterSystemGestureExclusionListener(
                            mGestureExclusionListener, mMainDisplayId);
                } catch (RemoteException | IllegalArgumentException e) {
                    Log.e(TAG, "Failed to unregister window manager callbacks", e);
                }

                if (mBlockedActivitiesJob != null) {
                    mBlockedActivitiesJob.cancel(new CancellationException());
                    mBlockedActivitiesJob = null;
                }
                mBlockedActivities.clear();
            } else {
                mBackgroundExecutor.execute(mGestureNavigationSettingsObserver::register);
                updateDisplaySize();
                if (DEBUG_MISSING_GESTURE) {
                    Log.d(DEBUG_MISSING_GESTURE_TAG, "Register display listener");
                }
                TaskStackChangeListeners.getInstance().registerTaskStackListener(
                        mTaskStackListener);
                DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                        mUiThreadContext.getExecutor()::execute, mOnPropertiesChangedListener);
                mPipOptional.ifPresent(pip -> pip.addOnIsInPipStateChangedListener(
                        mUiThreadContext.getExecutor(), mOnIsInPipStateChangedListener));
                mDesktopModeOptional.ifPresent(
                        dm -> dm.addDesktopGestureExclusionRegionListener(
                                mDesktopCornersChangedListener, mUiThreadContext.getExecutor()));

                try {
                    mWindowManagerService.registerSystemGestureExclusionListener(
                            mGestureExclusionListener, mMainDisplayId);
                } catch (RemoteException | IllegalArgumentException e) {
                    Log.e(TAG, "Failed to register window manager callbacks", e);
                }

                // Registers input event receiver and adds a nav bar panel window
                for (Display display : mDisplayManager.getDisplays()) {
                    mDisplayBackGestureHandlers.put(display.getDisplayId(),
                            createDisplayBackGestureHandler(display));
                }
                updateLongSwipeWidth();

                // Begin listening to changes in blocked activities list
                mBlockedActivitiesJob = mJavaAdapter.alwaysCollectFlow(
                        mGestureInteractor.getTopActivityBlocked(),
                        blocked -> mGestureBlockingActivityRunning.set(blocked));

            }
            // Update the ML model resources.
            updateMLModelState();
        } finally {
            Trace.endSection();
        }
    }




    public boolean isHandlingGestures() {
        return mIsEnabled && mIsBackGestureAllowed;
    }

    public boolean isButtonForcedVisible() {
        return mIsButtonForcedVisible;
    }

    private void updateLongSwipeWidth() {
        if (!mIsEnabled) {
            return;
        }

        for (DisplayBackGestureHandler displayBackGestureHandler :
                mDisplayBackGestureHandlers.values()) {
            displayBackGestureHandler.setLongSwipeEnabled(mIsLongSwipeEnabled);
        }
    }

    /**
     * Update the PiP bounds, used for exclusion calculation.
     */
    public void setPipStashExclusionBounds(Rect bounds) {
        mPipExcludedBounds.set(bounds);
    }

    private WindowManager.LayoutParams createLayoutParams() {
        Resources resources = mContext.getResources();
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.navigation_edge_panel_width),
                resources.getDimensionPixelSize(R.dimen.navigation_edge_panel_height),
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layoutParams.accessibilityTitle = mContext.getString(R.string.nav_bar_edge_panel);
        layoutParams.windowAnimations = 0;
        layoutParams.privateFlags |=
                (WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION);
        layoutParams.setTitle(TAG + mContext.getDisplayId());
        layoutParams.setFitInsetsTypes(0 /* types */);
        layoutParams.setTrustedOverlay();
        return layoutParams;
    }

    private void onInputEvent(InputEvent ev) {
        if (!(ev instanceof MotionEvent)) return;
        MotionEvent event = (MotionEvent) ev;
        onMotionEvent(event);
    }

    private void updateMLModelState() {
        boolean newState = mIsGestureHandlingEnabled && mContext.getResources().getBoolean(
                R.bool.config_useBackGestureML) && DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.USE_BACK_GESTURE_ML_MODEL, false);

        if (newState == mUseMLModel) {
            return;
        }

        mUseMLModel = newState;

        if (mUseMLModel) {
            mUiThreadContext.isCurrentThread();
            if (mMLModelIsLoading) {
                Log.d(TAG, "Model tried to load while already loading.");
                return;
            }
            mMLModelIsLoading = true;
            mBackgroundExecutor.execute(() -> loadMLModel());
        } else if (mBackGestureTfClassifierProvider != null) {
            mBackGestureTfClassifierProvider.release();
            mBackGestureTfClassifierProvider = null;
            mVocab = null;
        }
    }

    private void loadMLModel() {
        BackGestureTfClassifierProvider provider = mBackGestureTfClassifierProviderProvider.get();
        float threshold = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.BACK_GESTURE_ML_MODEL_THRESHOLD, 0.9f);
        Map<String, Integer> vocab = null;
        if (provider != null && !provider.isActive()) {
            provider.release();
            provider = null;
            Log.w(TAG, "Cannot load model because it isn't active");
        }
        if (provider != null) {
            Trace.beginSection("EdgeBackGestureHandler#loadVocab");
            vocab = provider.loadVocab(mContext.getAssets());
            Trace.endSection();
        }
        BackGestureTfClassifierProvider finalProvider = provider;
        Map<String, Integer> finalVocab = vocab;
        mUiThreadContext.getExecutor().execute(
                () -> onMLModelLoadFinished(finalProvider, finalVocab, threshold));
    }

    private void onMLModelLoadFinished(BackGestureTfClassifierProvider provider,
            Map<String, Integer> vocab, float threshold) {
        mUiThreadContext.isCurrentThread();
        mMLModelIsLoading = false;
        if (!mUseMLModel) {
            // This can happen if the user disables Gesture Nav while the model is loading.
            if (provider != null) {
                provider.release();
            }
            Log.d(TAG, "Model finished loading but isn't needed.");
            return;
        }
        mBackGestureTfClassifierProvider = provider;
        mVocab = vocab;
        mMLModelThreshold = threshold;
    }

    private int getBackGesturePredictionsCategory(int x, int y, int app) {
        BackGestureTfClassifierProvider provider = mBackGestureTfClassifierProvider;
        if (provider == null || app == -1) {
            return -1;
        }
        int distanceFromEdge;
        int location;
        if (x <= mDisplaySize.x / 2.0) {
            location = 1;  // left
            distanceFromEdge = x;
        } else {
            location = 2;  // right
            distanceFromEdge = mDisplaySize.x - x;
        }

        Object[] featuresVector = {
            new long[]{(long) mDisplaySize.x},
            new long[]{(long) distanceFromEdge},
            new long[]{(long) location},
            new long[]{(long) app},
            new long[]{(long) y},
        };

        mMLResults = provider.predict(featuresVector);
        if (mMLResults == -1) {
            return -1;
        }
        return mMLResults >= mMLModelThreshold ? 1 : 0;
    }

    private boolean isWithinInsets(int x, int y) {
        // Disallow if we are in the bottom gesture area
        if (y >= (mDisplaySize.y - mBottomGestureHeight)) {
            return false;
        }
        // If the point is way too far (twice the margin), it is
        // not interesting to us for logging purposes, nor we
        // should process it.  Simply return false and keep
        // mLogGesture = false.
        if (x > 2 * (mEdgeWidthLeft + mLeftInset)
                && x < (mDisplaySize.x - 2 * (mEdgeWidthRight + mRightInset))) {
            return false;
        }
        return true;
    }

    private boolean desktopExcludeRegionContains(int x, int y) {
        return mDesktopModeExcludeRegion.contains(x, y);
    }

    private boolean isWithinTouchRegion(MotionEvent ev) {
        // If the point is inside the PiP or desktop excluded bounds, then ignore the back gesture.
        // gesture. Also ignore (for now) if it's not on the main display.
        // TODO(b/382130680): Implement back gesture handling on connected displays
        int x = (int) ev.getX();
        int y = (int) ev.getY();
        final boolean isInsidePip = mIsInPip && mPipExcludedBounds.contains(x, y);
        final boolean isInDesktopExcludeRegion = desktopExcludeRegionContains(x, y)
                && isEdgeResizePermitted(ev);
        if (isInsidePip || isInDesktopExcludeRegion || ev.getDisplayId() != mMainDisplayId) {
            return false;
        }

        int app = -1;
        if (mVocab != null) {
            app = mVocab.getOrDefault(mPackageName, -1);
        }

        // Denotes whether we should proceed with the gesture. Even if it is false, we may want to
        // log it assuming it is not invalid due to exclusion.
        boolean withinRange = x < mEdgeWidthLeft + mLeftInset
                || x >= (mDisplaySize.x - mEdgeWidthRight - mRightInset);
        if (withinRange) {
            int results = -1;

            // Check if we are within the tightest bounds beyond which we would not need to run the
            // ML model
            boolean withinMinRange = x < mMLEnableWidth + mLeftInset
                    || x >= (mDisplaySize.x - mMLEnableWidth - mRightInset);
            if (!withinMinRange && mUseMLModel && !mMLModelIsLoading
                    && (results = getBackGesturePredictionsCategory(x, y, app)) != -1) {
                withinRange = (results == 1);
            }
        }

        // For debugging purposes
        mPredictionLog.log(String.format("Prediction [%d,%d,%d,%d,%f,%d]",
                System.currentTimeMillis(), x, y, app, mMLResults, withinRange ? 1 : 0));

        // Always allow if the user is in a transient sticky immersive state
        if (mIsNavBarShownTransiently) {
            mLogGesture = true;
            return withinRange;
        }

        if (mExcludeRegion.contains(x, y)) {
            if (withinRange) {
                // We don't have the end point for logging purposes.
                mEndPoint.x = -1;
                mEndPoint.y = -1;
                mLogGesture = true;
                logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_EXCLUDED);
            }
            return false;
        }

        mInRejectedExclusion = mUnrestrictedExcludeRegion.contains(x, y);
        mLogGesture = true;
        return withinRange;
    }

    private void cancelGesture(MotionEvent ev) {
        // Send action cancel to reset all the touch events
        mAllowGesture = false;
        mLogGesture = false;
        mInRejectedExclusion = false;
        MotionEvent cancelEv = MotionEvent.obtain(ev);
        cancelEv.setAction(MotionEvent.ACTION_CANCEL);
        mDisplayBackGestureHandlers.get(ev.getDisplayId()).onMotionEvent(cancelEv);
        dispatchToBackAnimation(cancelEv);
        cancelEv.recycle();
    }

    private void logGesture(int backType) {
        if (!mLogGesture) {
            return;
        }
        mLogGesture = false;
        String logPackageName = "";
        Map<String, Integer> vocab = mVocab;
        // Due to privacy, only top 100 most used apps by all users can be logged.
        if (mUseMLModel && vocab != null && vocab.containsKey(mPackageName)
                && vocab.get(mPackageName) < 100) {
            logPackageName = mPackageName;
        }
        SysUiStatsLog.write(SysUiStatsLog.BACK_GESTURE_REPORTED_REPORTED, backType,
                (int) mDownPoint.y, mIsOnLeftEdge
                        ? SysUiStatsLog.BACK_GESTURE__X_LOCATION__LEFT
                        : SysUiStatsLog.BACK_GESTURE__X_LOCATION__RIGHT,
                (int) mDownPoint.x, (int) mDownPoint.y,
                (int) mEndPoint.x, (int) mEndPoint.y,
                mEdgeWidthLeft + mLeftInset,
                mDisplaySize.x - (mEdgeWidthRight + mRightInset),
                mUseMLModel ? mMLResults : -2, logPackageName,
                mIsTrackpadThreeFingerSwipe ? SysUiStatsLog.BACK_GESTURE__INPUT_TYPE__TRACKPAD
                        : SysUiStatsLog.BACK_GESTURE__INPUT_TYPE__TOUCH);
    }

    private void onMotionEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        DisplayBackGestureHandler displayBackGestureHandler = mDisplayBackGestureHandlers.get(
                ev.getDisplayId());
        if (displayBackGestureHandler == null) {
            Log.e(TAG, "Received MotionEvent on unknown display");
            return;
        }


        if (action == MotionEvent.ACTION_DOWN) {
            if (DEBUG_MISSING_GESTURE) {
                Log.d(DEBUG_MISSING_GESTURE_TAG, "Start gesture: " + ev);
            }

            mIsTrackpadThreeFingerSwipe = isTrackpadThreeFingerSwipe(ev);

            // Verify if this is in within the touch region and we aren't in immersive mode, and
            // either the bouncer is showing or the notification panel is hidden
            displayBackGestureHandler.setBatchingEnabled(false);
            if (mIsTrackpadThreeFingerSwipe) {
                // Since trackpad gestures don't have zones, this will be determined later by the
                // direction of the gesture. {@code mIsOnLeftEdge} is set to false to begin with.
                mDeferSetIsOnLeftEdge = true;
                mIsOnLeftEdge = false;
            } else {
                mIsOnLeftEdge = ev.getX() <= mEdgeWidthLeft + mLeftInset;
            }
            mMLResults = 0;
            mLogGesture = false;
            mInRejectedExclusion = false;
            boolean isWithinInsets = isWithinInsets((int) ev.getX(), (int) ev.getY());
            boolean isBackAllowedCommon = !mDisabledForQuickstep && mIsBackGestureAllowed
                    && !mGestureBlockingActivityRunning.get()
                    && !QuickStepContract.isBackGestureDisabled(mSysUiFlags,
                            mIsTrackpadThreeFingerSwipe);
            if (mIsTrackpadThreeFingerSwipe) {
                // Trackpad back gestures don't have zones, so we don't need to check if the down
                // event is within insets.
                boolean trackpadGesturesEnabled =
                        (mSysUiFlags & SYSUI_STATE_TOUCHPAD_GESTURES_DISABLED) == 0;
                mAllowGesture = isBackAllowedCommon && trackpadGesturesEnabled
                        && displayBackGestureHandler.isValidTrackpadBackGesture();
            } else {
                mAllowGesture = isBackAllowedCommon && !mUsingThreeButtonNav && isWithinInsets
                        && isWithinTouchRegion(ev) && !isButtonPressFromTrackpadOrMouse(ev);
            }
            if (mAllowGesture) {
                displayBackGestureHandler.setIsLeftPanel(mIsOnLeftEdge);
                displayBackGestureHandler.onMotionEvent(ev);
                mLastDownEventDisplayId = ev.getDisplayId();
                dispatchToBackAnimation(ev);
            }
            if (mLogGesture || mIsTrackpadThreeFingerSwipe) {
                mDownPoint.set(ev.getX(), ev.getY());
                mEndPoint.set(-1, -1);
                mThresholdCrossed = false;
            }

            // For debugging purposes, only log edge points
            long curTime = System.currentTimeMillis();
            mTmpLogDate.setTime(curTime);
            String curTimeStr = mLogDateFormat.format(mTmpLogDate);
            (isWithinInsets ? mGestureLogInsideInsets : mGestureLogOutsideInsets).log(String.format(
                    "Gesture [%d [%s],alw=%B, t3fs=%B, left=%B, defLeft=%B, backAlw=%B, disbld=%B,"
                            + " qsDisbld=%b, blkdAct=%B, pip=%B,"
                            + " disp=%s, wl=%d, il=%d, wr=%d, ir=%d, excl=%s]",
                    curTime, curTimeStr, mAllowGesture, mIsTrackpadThreeFingerSwipe,
                    mIsOnLeftEdge, mDeferSetIsOnLeftEdge, mIsBackGestureAllowed,
                    QuickStepContract.isBackGestureDisabled(mSysUiFlags,
                            mIsTrackpadThreeFingerSwipe), mDisabledForQuickstep,
                    mGestureBlockingActivityRunning.get(), mIsInPip, mDisplaySize,
                    mEdgeWidthLeft, mLeftInset, mEdgeWidthRight, mRightInset,
                    displayBackGestureHandler.getExcludeRegion()));
        } else if (mAllowGesture || mLogGesture) {
            boolean mLastFrameThresholdCrossed = mThresholdCrossed;
            if (!mThresholdCrossed) {
                mEndPoint.x = (int) ev.getX();
                mEndPoint.y = (int) ev.getY();
                if (action == MotionEvent.ACTION_POINTER_DOWN && !mIsTrackpadThreeFingerSwipe) {
                    if (mAllowGesture) {
                        logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_MULTI_TOUCH);
                        if (DEBUG_MISSING_GESTURE) {
                            Log.d(DEBUG_MISSING_GESTURE_TAG, "Cancel back: multitouch");
                        }
                        // We do not support multi touch for back gesture
                        cancelGesture(ev);
                    }
                    mLogGesture = false;
                    return;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if (mIsTrackpadThreeFingerSwipe && mDeferSetIsOnLeftEdge) {
                        // mIsOnLeftEdge is determined by the relative position between the down
                        // and the current motion event for trackpad gestures instead of zoning.
                        mIsOnLeftEdge = mEndPoint.x > mDownPoint.x;
                        displayBackGestureHandler.setIsLeftPanel(mIsOnLeftEdge);
                        mDeferSetIsOnLeftEdge = false;
                    }

                    if ((ev.getEventTime() - ev.getDownTime()) > mLongPressTimeout) {
                        if (mAllowGesture) {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_LONG_PRESS);
                            cancelGesture(ev);
                            if (DEBUG_MISSING_GESTURE) {
                                Log.d(DEBUG_MISSING_GESTURE_TAG, "Cancel back [longpress]: "
                                        + ev.getEventTime()
                                        + "  " + ev.getDownTime()
                                        + "  " + mLongPressTimeout);
                            }
                        }
                        mLogGesture = false;
                        return;
                    }
                    float dx = Math.abs(ev.getX() - mDownPoint.x);
                    float dy = Math.abs(ev.getY() - mDownPoint.y);
                    if (dy > dx && dy > mTouchSlop) {
                        if (mAllowGesture) {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_VERTICAL_MOVE);
                            cancelGesture(ev);
                            if (DEBUG_MISSING_GESTURE) {
                                Log.d(DEBUG_MISSING_GESTURE_TAG, "Cancel back [vertical move]: "
                                        + dy + "  " + dx + "  " + mTouchSlop);
                            }
                        }
                        mLogGesture = false;
                        return;
                    } else if (dx > dy && dx > mTouchSlop) {
                        if (mAllowGesture) {
                            if (mBackAnimation == null) {
                                pilferPointers(ev.getDisplayId());
                            }
                            mThresholdCrossed = true;
                        } else {
                            logGesture(SysUiStatsLog.BACK_GESTURE__TYPE__INCOMPLETE_FAR_FROM_EDGE);
                        }
                    }
                }
            }

            if (mAllowGesture) {
                // forward touch
                displayBackGestureHandler.onMotionEvent(ev);
                dispatchToBackAnimation(ev);
                if (mBackAnimation != null && mThresholdCrossed && !mLastFrameThresholdCrossed) {
                    mBackAnimation.onThresholdCrossed();
                }
            }
        }
    }


    private void pilferPointers(int displayId) {
        DisplayBackGestureHandler displayBackGestureHandler = mDisplayBackGestureHandlers.get(
                displayId);
        if (displayBackGestureHandler != null) {
            // Capture inputs
            displayBackGestureHandler.pilferPointers();
            // Notify FalsingManager that an intentional gesture has occurred.
            mFalsingManager.isFalseTouch(BACK_GESTURE);
            displayBackGestureHandler.setBatchingEnabled(true);
        }
    }

    private boolean isButtonPressFromTrackpadOrMouse(MotionEvent ev) {
        boolean isSourceMouseOrTouchpad = ev.getSource() == SOURCE_MOUSE
                || ev.getSource() == SOURCE_TOUCHPAD
                || ev.getSource() == (SOURCE_MOUSE | SOURCE_TOUCHPAD);
        boolean isTooltypeMouseOrFinger = ev.getToolType(ev.getActionIndex()) == TOOL_TYPE_MOUSE
                || ev.getToolType(ev.getActionIndex()) == TOOL_TYPE_FINGER;
        return isSourceMouseOrTouchpad && isTooltypeMouseOrFinger;
    }

    private void dispatchToBackAnimation(MotionEvent event) {
        if (mBackAnimation != null) {
            mBackAnimation.onBackMotion(
                    /* touchX = */ event.getX(),
                    /* touchY = */ event.getY(),
                    /* keyAction = */ event.getActionMasked(),
                    /* swipeEdge = */ mIsOnLeftEdge ? BackEvent.EDGE_LEFT : BackEvent.EDGE_RIGHT,
                    event.getDisplayId());
        }
    }

    private void updateDisabledForQuickstep(Configuration newConfig) {
        int rotation = newConfig.windowConfiguration.getRotation();
        mDisabledForQuickstep = mStartingQuickstepRotation > -1 &&
                mStartingQuickstepRotation != rotation;
    }

    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        if (mStartingQuickstepRotation > -1) {
            updateDisabledForQuickstep(newConfig);
        }

        // TODO(b/332635834): Disable this logging once b/332635834 is fixed.
        Log.i(DEBUG_MISSING_GESTURE_TAG, "Config changed: newConfig=" + newConfig
                + " lastReportedConfig=" + mLastReportedConfig);
        final int diff = newConfig.diff(mLastReportedConfig);
        if ((diff & CONFIG_FONT_SCALE) != 0 || (diff & ActivityInfo.CONFIG_DENSITY) != 0) {
            updateCurrentUserResources();
        }
        mLastReportedConfig.updateFrom(newConfig);
        updateDisplaySize();
    }

    private void updateDisplaySize() {
        Rect bounds = mLastReportedConfig.windowConfiguration.getMaxBounds();
        mDisplaySize.set(bounds.width(), bounds.height());
        if (DEBUG_MISSING_GESTURE) {
            Log.d(DEBUG_MISSING_GESTURE_TAG, "Update display size: mDisplaySize=" + mDisplaySize);
        }

        updateBackAnimationThresholds();
        updateLongSwipeWidth();
    }

    private void updateBackAnimationThresholds() {
        if (mBackAnimation == null) {
            return;
        }
        int maxDistance = mDisplaySize.x;
        float linearDistance = Math.min(maxDistance, mBackSwipeLinearThreshold);
        //TODO(b/382774299): Make sure we're updating mBackAnimation with the right thresholds for
        // gestures on connected displays
        mBackAnimation.setSwipeThresholds(linearDistance, maxDistance, mNonLinearFactor);
    }

    private boolean sendEvent(int action, int code, int flags) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, code, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                flags | KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);

        ev.setDisplayId(mContext.getDisplay().getDisplayId());
        return mContext.getSystemService(InputManager.class)
                .injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public void setInsets(int leftInset, int rightInset) {
        mLeftInset = leftInset;
        mRightInset = rightInset;
    }

    private void disableNavBarVirtualKeyHapticFeedback() {
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .setNavBarVirtualKeyHapticFeedbackEnabled(false);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to disable navigation bar button haptics: ", e);
        }
    }

    public void dump(PrintWriter pw) {
        pw.println("EdgeBackGestureHandler:");
        pw.println("  mIsEnabled=" + mIsEnabled);
        pw.println("  mIsAttached=" + mIsAttached);
        pw.println("  mIsBackGestureAllowed=" + mIsBackGestureAllowed);
        pw.println("  mIsGestureHandlingEnabled=" + mIsGestureHandlingEnabled);
        pw.println("  mIsNavBarShownTransiently=" + mIsNavBarShownTransiently);
        pw.println("  mGestureBlockingActivityRunning=" + mGestureBlockingActivityRunning.get());
        pw.println("  mAllowGesture=" + mAllowGesture);
        pw.println("  mUseMLModel=" + mUseMLModel);
        pw.println("  mDisabledForQuickstep=" + mDisabledForQuickstep);
        pw.println("  mStartingQuickstepRotation=" + mStartingQuickstepRotation);
        pw.println("  mInRejectedExclusion=" + mInRejectedExclusion);
        pw.println("  mExcludeRegion=" + mExcludeRegion);
        pw.println("  mUnrestrictedExcludeRegion=" + mUnrestrictedExcludeRegion);
        pw.println("  mIsInPip=" + mIsInPip);
        pw.println("  mPipExcludedBounds=" + mPipExcludedBounds);
        pw.println("  mDesktopModeExclusionRegion=" + mDesktopModeExcludeRegion);
        pw.println("  mEdgeWidthLeft=" + mEdgeWidthLeft);
        pw.println("  mEdgeWidthRight=" + mEdgeWidthRight);
        pw.println("  mLeftInset=" + mLeftInset);
        pw.println("  mRightInset=" + mRightInset);
        pw.println("  mMLEnableWidth=" + mMLEnableWidth);
        pw.println("  mMLModelThreshold=" + mMLModelThreshold);
        pw.println("  mTouchSlop=" + mTouchSlop);
        pw.println("  mBottomGestureHeight=" + mBottomGestureHeight);
        pw.println("  mPredictionLog=" + String.join("\n", mPredictionLog));
        pw.println("  mGestureLogInsideInsets=" + String.join("\n", mGestureLogInsideInsets));
        pw.println("  mGestureLogOutsideInsets=" + String.join("\n", mGestureLogOutsideInsets));
        pw.println("  mTrackpadsConnected=" + mTrackpadsConnected.stream().map(
                String::valueOf).collect(joining()));
        pw.println("  mUsingThreeButtonNav=" + mUsingThreeButtonNav);
        pw.println("  mDisplayBackGestureHandlers:");
        for (Map.Entry<Integer, DisplayBackGestureHandler> displayBackGestureHandlers :
                mDisplayBackGestureHandlers.entrySet()) {
            displayBackGestureHandlers.getValue().dump("\t", pw);
        }
    }

    private void updateTopActivityPackageName() {
        ActivityManager.RunningTaskInfo runningTask =
                ActivityManagerWrapper.getInstance().getRunningTask();
        ComponentName topActivity = runningTask == null ? null : runningTask.topActivity;
        if (topActivity != null) {
            mPackageName = topActivity.getPackageName();
        } else {
            mPackageName = "_UNKNOWN";
        }
    }

    public void setBackAnimation(@Nullable BackAnimation backAnimation) {
        mBackAnimation = backAnimation;
        if (backAnimation != null) {
            final Executor uiThreadExecutor = mUiThreadContext.getExecutor();
            backAnimation.setPilferPointerCallback(
                    () -> uiThreadExecutor.execute(() -> pilferPointers(mLastDownEventDisplayId)));
            backAnimation.setTopUiRequestCallback(
                    (requestTopUi, tag) -> uiThreadExecutor.execute(
                            () -> mTopUiController.setRequestTopUi(requestTopUi, tag)));
            updateBackAnimationThresholds();
            if (mLightBarControllerProvider.get() != null) {
                mBackAnimation.setStatusBarCustomizer((appearance) ->
                        uiThreadExecutor.execute(() ->
                            mLightBarControllerProvider.get()
                                    .customizeStatusBarAppearance(appearance)));
            }
        }
    }

    private static class LogArray extends ArrayDeque<String> {
        private final int mLength;

        LogArray(int length) {
            mLength = length;
        }

        void log(String message) {
            if (size() >= mLength) {
                removeFirst();
            }
            addLast(message);
            if (DEBUG_MISSING_GESTURE) {
                Log.d(DEBUG_MISSING_GESTURE_TAG, message);
            }
        }
    }
}
