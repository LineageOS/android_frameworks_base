package com.android.server.wm.onehand;

import static com.android.server.wm.onehand.IOneHandedAnimatorProxy.DEBUG;

import com.android.server.LocalServices;
import com.android.internal.onehand.IOneHandedModeListener;

import android.R;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IUserSwitchObserver;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManagerInternal;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IRemoteCallback;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.view.animation.Transformation;
import android.view.animation.TranslateAnimation;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.WindowManager;
import java.util.List;

import java.io.PrintWriter;

class OneHandedAnimator {

    private static final String TAG = "OneHandAnimator";

    private static final boolean LOCAL_DEBUG =
            "eng".equals(SystemProperties.get("ro.build.type", ""));
    private static final boolean VERBOSE_DEBUG = false;

    private static final long TRANSIT_DURATION = 350;

    private static final String ACTION_ONEHAND_TRIGGER_EVENT =
            "com.android.server.wm.onehand.intent.action.ONEHAND_TRIGGER_EVENT";

    private static final String EXTRA_ALIGNMENT_STATE = "alignment_state";
    private static final int EXTRA_ALIGNMENT_STATE_UNALIGNED = -1;
    private static final int EXTRA_ALIGNMENT_STATE_LEFT = 0;
    private static final int EXTRA_ALIGNMENT_STATE_RIGHT = 1;

    private static final String EXTRA_VERTICAL_POSITION = "vertical_position";

    private static final int MSG_ONEHAND_TURNED_OFF = 1399;
    private static final long DELAY_TIME = 700;

    private static int sCurrentUser = 0;
    static int getCurrentUser() {
        return sCurrentUser;
    }

    /**
     * mMode is initialized as OFF
     */
    private final OneHandedMode mMode = new OneHandedMode();

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final IOneHandedAnimatorProxy.IWindowManagerFuncs mWindowManager;

    private final InputManagerInternal mInputManager;
    private final BatteryManagerInternal mBatteryManager;

    private final DisplayInfo mDefaultDisplayInfo = new DisplayInfo();

    private final Handler mH;
    private final HandlerThread mMainThread;

    private boolean mPointerMappingSuspended = false;

    private Animation mTransitAnimation = null;
    private Transformation mTransitTransformation = null;

    final OneHandedControlPanel mPanel;

    private volatile boolean mIsRacedAccessibilityEnabled = false;

    private final OneHandOperationMonitor mOperationMonitor;

    // save the mapping parameters for debug
    private int mInputLastOffsetX = 0;
    private int mInputLastOffsetY = 0;
    private float mInputLastScale = 1;

    volatile boolean isOnehandTurnedOn = false;

    private final RemoteCallbackList<IOneHandedModeListener> mOneHandedModeListeners =
            new RemoteCallbackList<>();

    private void notifyModeChange(OneHandedMode curMode, OneHandedMode lastMode) {
        mPanel.notifyModeChanged(curMode, lastMode);

        notifyModeChangeToListenersIfNeeded(curMode, lastMode);
    }

    private void notifyModeChangeToListenersIfNeeded(
            OneHandedMode curMode, OneHandedMode lastMode) {
        final boolean onEnter = (lastMode.isOffMode() && !curMode.isOffMode());
        final boolean onExit = (!lastMode.isOffMode() && curMode.isOffMode());

        if (!(onEnter || onExit)) {
            return;
        }

        final int size = mOneHandedModeListeners.beginBroadcast();
        if (LOCAL_DEBUG) Slog.v(TAG, "notifyModeChangeToListenersIfNeeded: onEnter=" + onEnter
                + " onExit=" + onExit + " listeners count=" + size);

        for (int i = 0; i < size; i++) {
            final IOneHandedModeListener listener = mOneHandedModeListeners.getBroadcastItem(i);
            try {
                if (onEnter) {
                    listener.onEnterOneHandedMode();
                } else if (onExit) {
                    listener.onExitFromOneHandedMode();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Error delivering one handed mode state changed.", e);
            }
        }
        mOneHandedModeListeners.finishBroadcast();
        if (LOCAL_DEBUG) Slog.v(TAG, "finish");
    }

    // OneHandAnimator is an animator of WindowAnimator, and will cooperate with WMS.
    // mWindowManager.mWindowMap will be the best object for synchronization
    private Object getSyncRoot() {
        return mWindowManager.getSyncRoot();
    }

    private boolean updateDefaultDisplayInfoLocked() {
        ((WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE))
            .getDefaultDisplay().getDisplayInfo(mDefaultDisplayInfo);
        return true;
    }

    OneHandedAnimator(Context ctx, IOneHandedAnimatorProxy.IWindowManagerFuncs wms) {
        // One Handed UI Elements uses Material Dark theme.
        DisplayManager dm = (DisplayManager)ctx.getSystemService(Context.DISPLAY_SERVICE);
        mContext = ctx.createDisplayContext(dm.getDisplay(Display.DEFAULT_DISPLAY));
        mContext.setTheme(R.style.Theme_Material);

        mDisplayManager = (DisplayManager)mContext.getSystemService(
                Context.DISPLAY_SERVICE);
        mWindowManager = wms;

        mInputManager = LocalServices.getService(InputManagerInternal.class);
        mBatteryManager = LocalServices.getService(BatteryManagerInternal.class);

        mOperationMonitor = new OneHandOperationMonitor(mContext);

        mMainThread = new HandlerThread("OneHandAnimator");
        mMainThread.start();
        mH = new Handler(mMainThread.getLooper()){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what){
                    case MSG_ONEHAND_TURNED_OFF:
                        if (LOCAL_DEBUG) Slog.d(TAG, "set isOnehandTurnedOn false");
                        isOnehandTurnedOn = false;
                        break;
                }
            }
        };

        mPanel = new OneHandedControlPanel(mContext, this, mMainThread.getLooper());

        updateDefaultDisplayInfoLocked();

        registerObservers();

        updateIsRacedAccessibilityEnabled();

        if (DEBUG) Slog.v(TAG, "OneHandAnimator ready to run!");
    }

    private void prepareAnimationLocked(OneHandedMode lastMode) {
        float startTransX = 0;
        float startTransY = 0;
        float startScaleX = 1;
        float startScaleY = 1;

        Transformation start = null;

        if (mTransitTransformation != null) {
            /* There are transition animation which is on going
             * Use current transition state as the start of the animation
             */
            start = mTransitTransformation;

            float[] values = new float[9];
            start.getMatrix().getValues(values);

            startTransX = values[Matrix.MTRANS_X];
            startTransY = values[Matrix.MTRANS_Y];
            startScaleX = values[Matrix.MSCALE_X];
            startScaleY = values[Matrix.MSCALE_Y];
        } else {
            /* No transition animation is running, use previous dock state
             * as the start of the animation
             */
            int[] startTrans = new int[2];
            float[] startScale = new float[1];
            getTransformationArgsForModeLocked(lastMode, startTrans, startScale);
            startTransX = startTrans[0];
            startTransY = startTrans[1];
            startScaleX = startScale[0];
            startScaleY = startScale[0];
            mTransitTransformation = new Transformation();

            mTransitTransformation.getMatrix()
                            .postScale(startScaleX, startScaleY);
            mTransitTransformation.getMatrix()
                            .postTranslate(startTransX, startTransY);
        }

        int[] toTrans = new int[2];
        float[] toScale = new float[1];

        getTransformationArgsForModeLocked(mMode, toTrans, toScale);

        TranslateAnimation ta = new TranslateAnimation(
                startTransX, toTrans[0], startTransY, toTrans[1]);

        ScaleAnimation sa = new ScaleAnimation(
                startScaleX, toScale[0], startScaleY,toScale[0]);

        AnimationSet anim = new AnimationSet(true);
        anim.addAnimation(sa);
        anim.addAnimation(ta);

        anim.setDuration(TRANSIT_DURATION);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());

        int w = mDefaultDisplayInfo.logicalWidth;
        int h = mDefaultDisplayInfo.logicalHeight;
        anim.initialize(w, h, w, h);
        anim.start();

        if (DEBUG) Slog.v(TAG, "Animation prepared from(" + startTransX +
                ", " + startTransY + ", " + startScaleX +")" +
                " to ( " + toTrans[0] + ", " + toTrans[1] +
                ", " + toScale[0] + ")");

        mTransitAnimation = anim;
    }

    boolean stepAnimationInTransaction(long now) {
        /* stepAnimationInTransaction() is called only in WindowAnimator.animateLocked()
           And WindowManagerService.mWindowMap is locked for sure. We keep the
           synchronization here just in case if we decided not to use mWindowMap as the synchronization object */
        synchronized(getSyncRoot()) {
            boolean more = false;
            if (mTransitAnimation != null) {
                mTransitTransformation.getMatrix().reset();
                more = mTransitAnimation.getTransformation(
                        now, mTransitTransformation);
                if (!more) {
                    mTransitAnimation = null;
                    mTransitTransformation = null;

                    if (mMode.isOffMode()) {
                        mPanel.remove();
                    }
                }
            }
            return more;
        }
    }

    private void scheduleWindowAnimationLocked() {
        mWindowManager.scheduleAnimation();
    }

    private void getTransformationArgsForModeLocked(OneHandedMode mode,
                                int[] outTrans, float[] outScale) {
        getTransformationArgsForMode(mode, outTrans, outScale,
                mDefaultDisplayInfo.logicalWidth,
                mDefaultDisplayInfo.logicalHeight);
    }

    void getTransformationArgsForMode(OneHandedMode mode,
                                int[] outTrans, float[] outScale,
                                int containerWidth, int containerHeight) {

        if (outTrans.length != 2) {
            throw new RuntimeException("Wrong array size");
        }
        if (outScale.length != 1) {
            throw new RuntimeException("Wrong array size");
        }

        Rect screenRect = new Rect(0, 0, containerWidth
                                       , containerHeight);

        int scaledW = (int)(screenRect.width() * mode.getScale() + 0.5f);
        int scaledH = (int)(screenRect.height() * mode.getScale() + 0.5f);

        Rect result = new Rect();
        Gravity.apply(mode.getGravity(), scaledW, scaledH,
                screenRect, mode.xAdj, mode.yAdj, result);

        outTrans[0] = result.left;
        outTrans[1] = result.top;
        outScale[0] = mode.getScale();

        if (VERBOSE_DEBUG) Slog.v(TAG, "Computing trans for mode: [" + mode + "] result = " + result);
    }

    Transformation getTransformation() {
        synchronized(getSyncRoot()) {
            return getTransformationLocked();
        }
    }

    private Transformation getTransformationLocked() {
        Transformation result = null;
        if (mTransitTransformation == null) {
            if (mMode.isOffMode()) {
                if (VERBOSE_DEBUG) Slog.v(TAG, "getTransformationLocked return null because off mode");
                return null;
            }
            Transformation t = new Transformation();

            int[] trans = new int[2];
            float[] scale = new float[1];

            getTransformationArgsForModeLocked(mMode, trans, scale);
            t.getMatrix().postScale(scale[0], scale[0]);
            t.getMatrix().postTranslate(trans[0], trans[1]);
            if (VERBOSE_DEBUG) Slog.v(TAG, "Returning direct transform");
            result = t;
        } else {
            if (VERBOSE_DEBUG) Slog.v(TAG, "Returning transition transform");
            result = new Transformation();
            result.set(mTransitTransformation);
        }

        if (VERBOSE_DEBUG) Slog.v(TAG, "getTransformationLocked Transform = " + result.getMatrix().toString());

        if (result.getMatrix().isIdentity() && result.getAlpha() == 1)
            return null; // no need to continue apply the transformation from us
        else
            return result;
    }

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {

        @Override
        public void onDisplayAdded(int displayId) {
            if (VERBOSE_DEBUG) Slog.v(TAG, "onDisplayAdded: displayId=" + displayId);
            if (doesRacedDisplayExist()) {
                setOneHandedMode(null, false);
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (VERBOSE_DEBUG) Slog.v(TAG, "onDisplayRemoved: displayId=" + displayId);
            if (doesRacedDisplayExist()) {
                setOneHandedMode(null, false);
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            synchronized(getSyncRoot()) {
                if (VERBOSE_DEBUG) Slog.v(TAG, "onDisplayChanged: displayId=" + displayId);

                updateDefaultDisplayInfoLocked();
                if (mDefaultDisplayInfo.logicalWidth
                        > mDefaultDisplayInfo.logicalHeight) {
                    setOneHandedMode(null /* Turn OFF */, true);
                } else {
                    scheduleWindowAnimationLocked();
                    updatePointerMappingParametersLocked();
                }
            }
        }
    };

    private void registerObservers() {
        // If the settings disabled us, then stop mini-screen mode
        OneHandedSettings.registerFeatureEnableDisableObserver(
                mContext,
                new ContentObserver(mH) {
                    @Override
                    public void onChange(boolean selfChange) {
                        boolean featureEnabled = OneHandedSettings.isFeatureEnabled(mContext);
                        if (!featureEnabled) {
                            setOneHandedMode(null, false);
                        }
                    }
                });

        registerUserSetupCompleteObserver();
        registerMagnificationSettingsObserver();
        registerAccessibilityServicesStateChangeListener();

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                    if (LOCAL_DEBUG) {
                        Slog.d(TAG, "ACTION_SCREEN_OFF");
                    }
                    setOneHandedMode(null, true);

                } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                    if (LOCAL_DEBUG) {
                        Slog.d(TAG, "ACTION_BATTERY_CHANGED");
                    }
                    if (isPoweredByWireless()) {
                        setOneHandedMode(null, false);
                    }

                } else if (action.equals(Intent.ACTION_DREAMING_STARTED)) {
                    if (LOCAL_DEBUG) {
                        Slog.d(TAG, "ACTION_DREAMING_STARTED");
                    }
                    setOneHandedMode(null, false);

                } else if (action.equals(ACTION_ONEHAND_TRIGGER_EVENT)) {
                    if (LOCAL_DEBUG) {
                        Slog.d(TAG, "ACTION_ONEHAND_TRIGGER_EVENT");
                    }

                    boolean enterOneHandedMode = false;
                    boolean exitOneHandedMode = false;
                    synchronized(getSyncRoot()) {
                        boolean featureEnabled = OneHandedSettings.isFeatureEnabled(mContext);
                        if (!featureEnabled) {
                            return;
                        }

                        int alignmentState = intent.getIntExtra(
                                    EXTRA_ALIGNMENT_STATE,
                                    EXTRA_ALIGNMENT_STATE_UNALIGNED);

                        if (mMode.isOffMode()) {
                            if (alignmentState == EXTRA_ALIGNMENT_STATE_UNALIGNED) {
                                return;
                            }

                            int xAdj = OneHandedSettings.getSavedXAdj(mContext, 0);
                            int yAdj = OneHandedSettings.getSavedYAdj(mContext, 0);

                            float scale = getSavedShrinkingScale();
                            int gravity = alignmentState == EXTRA_ALIGNMENT_STATE_LEFT
                                    ? (Gravity.LEFT | Gravity.BOTTOM) : (Gravity.RIGHT | Gravity.BOTTOM);

                            if (intent.hasExtra(EXTRA_VERTICAL_POSITION)) {
                                updateDefaultDisplayInfoLocked();
                                yAdj = mDefaultDisplayInfo.logicalHeight
                                        - intent.getIntExtra(EXTRA_VERTICAL_POSITION, 0)
                                        - (int)(mDefaultDisplayInfo.logicalHeight * scale);
                            }

                            OneHandedMode newMode = new OneHandedMode(xAdj, yAdj, scale, gravity);

                            if (setOneHandedMode(newMode, false)) {
                                OneHandedSettings.saveGravity(mContext, newMode.getGravity());
                                OneHandedSettings.saveYAdj(mContext, newMode.yAdj);
                                enterOneHandedMode = true;
                            }

                        } else {
                            if (alignmentState != EXTRA_ALIGNMENT_STATE_UNALIGNED) {
                                return;
                            }

                            if (setOneHandedMode(null, false)) {
                                exitOneHandedMode = true;
                            }
                        }
                    }

                    // Push without sync root lock to avoid deadlock.
                    if (enterOneHandedMode) {
                        mOperationMonitor.pushEnter();
                    }
                    if (exitOneHandedMode) {
                        // Assume that the event was caused by tapping home button.
                        mOperationMonitor.pushExitByHomeButtonTouch();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(ACTION_ONEHAND_TRIGGER_EVENT);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(receiver, filter, null, mH);

        try {
            ActivityManagerNative.getDefault().registerUserSwitchObserver(
                    new IUserSwitchObserver.Stub() {
                        @Override
                        public void onUserSwitching(int newUserId, IRemoteCallback reply) {}
                        @Override
                        public void onLockedBootComplete(int newUserId) throws RemoteException {}
                        @Override
                        public void onForegroundProfileSwitch(int v) {}

                        @Override
                        public void onUserSwitchComplete(int newUserId) throws RemoteException {
                            sCurrentUser = ActivityManager.getCurrentUser();

                            mH.post(new Runnable() {
                                public void run() {
                                    updateIsRacedAccessibilityEnabled();
                                    setOneHandedMode(null, false);
                                }
                            });
                        }
                    }, TAG);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void registerUserSetupCompleteObserver() {
        ContentObserver userSetupCompleteObserver = new ContentObserver(mH) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
                if (userId == UserHandle.USER_ALL) return;

                if (isUserSetupCompleted(userId)
                        && OneHandedSettings.isFeatureEnabledSettingNotFound(mContext, userId)) {
                    OneHandedSettings.setFeatureEnabled(mContext, true, userId);
                }
            }
        };

        mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.USER_SETUP_COMPLETE),
                false, userSetupCompleteObserver, UserHandle.USER_ALL);
    }

    private void registerMagnificationSettingsObserver() {
        ContentObserver magnificationObserver = new ContentObserver(mH) {
            @Override
            public void onChange(boolean selfChange) {
                updateIsRacedAccessibilityEnabled();
                if (mIsRacedAccessibilityEnabled) {
                    setOneHandedMode(null, false);
                }
            }
        };

        mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED),
                false, magnificationObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED),
                false, magnificationObserver, UserHandle.USER_ALL);
    }

    private void registerAccessibilityServicesStateChangeListener() {
        AccessibilityManager am =
                (AccessibilityManager) mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        am.addAccessibilityServicesStateChangeListener(
                new AccessibilityManager.AccessibilityServicesStateChangeListener() {
            @Override
            public void onAccessibilityServicesStateChanged(AccessibilityManager manager) {
                updateIsRacedAccessibilityEnabled();
                if (mIsRacedAccessibilityEnabled) {
                    setOneHandedMode(null, false);
                }
            }
        }, mH);
    }

    private boolean isPoweredByWireless() {
        if (mBatteryManager == null) {
            // Avoid NPE in tests works on other than SystemServer process.
            // Normally it will never be null.
            return false;
        }

        return mBatteryManager.isPowered(BatteryManager.BATTERY_PLUGGED_WIRELESS);
    }

    private boolean isUserSetupCompleted() {
        return isUserSetupCompleted(getCurrentUser());
    }

    private boolean isUserSetupCompleted(int userId) {
        return (Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE,
                0, userId) != 0);
    }

    private void updateIsRacedAccessibilityEnabled() {
        mIsRacedAccessibilityEnabled = (
                isAccessibilityDisplayMagnificationEnabled()
                || isAccessibilityDisplayMagnificationNavbarEnabled()
                || isRacedAccessibilityServiceEnabled());
    }

    private boolean isAccessibilityDisplayMagnificationEnabled() {
        return (Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                0, getCurrentUser()) != 0);
    }

    private boolean isAccessibilityDisplayMagnificationNavbarEnabled() {
        return (Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED,
                0, getCurrentUser()) != 0);
    }

    private boolean isRacedAccessibilityServiceEnabled() {
        AccessibilityManager am =
                (AccessibilityManager) mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> serviceInfos =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        final int racedCapability = (
                AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION
                | AccessibilityServiceInfo.CAPABILITY_CAN_CONTROL_MAGNIFICATION);

        for (AccessibilityServiceInfo info : serviceInfos) {
            if ((info.getCapabilities() & racedCapability) != 0) {
                return true;
            }
        }

        return false;
    }

    private boolean doesRacedDisplayExist() {
        Display[] displays = mDisplayManager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION);

        for (Display display : displays) {
            if (display.getType() == Display.TYPE_WIFI
                    || display.getType() == Display.TYPE_VIRTUAL) {
                return true;
            }
        }
        return false;
    }

    private boolean isOneHandedModeAvailableLocked() {
        return !(mDefaultDisplayInfo.logicalWidth
                > mDefaultDisplayInfo.logicalHeight //in landscape mode.
                || isPoweredByWireless()
                || !isUserSetupCompleted()
                || mIsRacedAccessibilityEnabled
                || doesRacedDisplayExist());
    }

    boolean setOneHandedMode(OneHandedMode mode, boolean immediate) {

        if (mode == null) {
            mode = new OneHandedMode(); // Empty OneHandedMode means OFF.
        }

        if (!OneHandedSettings.isFeatureEnabled(mContext)
                && !mode.isOffMode()) {
            if (DEBUG) Slog.v(TAG, "Request of " + mode +
                    " is ignored because we are disabled by settings.");
            return false;
        }

        synchronized(getSyncRoot()) {
            if (!mMode.equals(mode)) {

                updateDefaultDisplayInfoLocked();

                if (!mode.isOffMode() && !isOneHandedModeAvailableLocked()) {
                    return false; // Reject the entering request.
                }

                OneHandedMode lastMode = new OneHandedMode(mMode);

                if (lastMode.isOffMode() && !mode.isOffMode()) {
                    mDisplayManager.registerDisplayListener(mDisplayListener, mH);
                    if (!updateDefaultDisplayInfoLocked()) {
                        mDisplayManager.unregisterDisplayListener(mDisplayListener);
                        return false;
                    }
                    mPanel.install();
                }

                if (!lastMode.isOffMode() && mode.isOffMode()) {
                    mDisplayManager.unregisterDisplayListener(mDisplayListener);
                    if (immediate) {
                        mPanel.remove();
                    }
                }

                mMode.set(mode);

                if (!immediate) {
                    prepareAnimationLocked(lastMode);
                }

                scheduleWindowAnimationLocked();

                updatePointerMappingParametersLocked();

                notifyModeChange(mMode, lastMode);
            }
        }
        return true;
    }

    void suspendPointerMappingUpgration() {
        synchronized(getSyncRoot()) {
            mPointerMappingSuspended = true;
            if (DEBUG) Slog.v(TAG, "pointer mapping suspended");
        }
    }

    void unsuspendPointerMappingUpgration() {
        synchronized(getSyncRoot()) {
            if (DEBUG) Slog.v(TAG, "pointer mapping unsuspended");
            mPointerMappingSuspended = false;
            updatePointerMappingParametersLocked();
        }
    }

    private void updatePointerMappingParametersLocked() {
        if (mInputManager == null) {
            // Avoid NPE in tests works on other than SystemServer process.
            // Normally it will never be null.
            return;
        }

        if (!mPointerMappingSuspended) {
            int[] trans = new int[2];
            float[] scale = new float[1];

            getTransformationArgsForModeLocked(mMode, trans, scale);

            if (DEBUG) Slog.v(TAG, "updating pointer mapping parames dock=" +
                     mMode + " [" + (-trans[0]) + ", " +
                    (-trans[1]) + ", " + (1/scale[0]) + "]");

            mInputManager.updatePointerMappingParameters(
                    -trans[0], -trans[1], 1 / scale[0],
                    mDefaultDisplayInfo.logicalWidth,
                    mDefaultDisplayInfo.logicalHeight);

            mInputLastOffsetX = -trans[0];
            mInputLastOffsetY = -trans[1];
            mInputLastScale = 1 / scale[0];

            if (mH == null || (scale[0] != 1.0f)) {
                if (LOCAL_DEBUG) Slog.v(TAG, "mH: " + mH + " set isOnehandTurnedOn: "
                        + (scale[0] != 1.0f) + " scale[0]: " + scale[0]);
                isOnehandTurnedOn = (scale[0] != 1.0f);
                mH.removeMessages(MSG_ONEHAND_TURNED_OFF);
            } else {
                if (LOCAL_DEBUG) Slog.v(TAG, "SendEmptyMessage to set isOnehandTurnedOn false scale[0]: "
                        + scale[0]);
                mH.removeMessages(MSG_ONEHAND_TURNED_OFF);
                mH.sendEmptyMessageDelayed(MSG_ONEHAND_TURNED_OFF, DELAY_TIME);
            }
        } else {
            if (DEBUG) Slog.v(TAG, "updating pointer mapping susppended");
        }
    }

    OneHandedMode getOneHandMode() {
        return new OneHandedMode(mMode);
    }

    void notifyOutSideScreenTouch(final int x, final int y) {
        mPanel.notifyOutsideScreenTouch(x, y);
    }

    boolean isOneHandedModeAvailable() {
        synchronized(getSyncRoot()) {
            if (!OneHandedSettings.isFeatureEnabled(mContext)) {
                return false;
            }

            updateDefaultDisplayInfoLocked();
            return isOneHandedModeAvailableLocked();
        }
    }

    float getSavedShrinkingScale() {
        synchronized(getSyncRoot()) {
            return OneHandedSettings.getSavedScale(mContext, OneHandedControlPanel.DEFAULT_SCALE);
        }
    }

    void registerOneHandedModeListener(IOneHandedModeListener listener) {
        if (LOCAL_DEBUG) Slog.v(TAG, "registerOneHandedModeListener: " + listener.asBinder());
        mOneHandedModeListeners.register(listener);
    }

    void unregisterOneHandedModeListener(IOneHandedModeListener listener) {
        if (LOCAL_DEBUG) Slog.v(TAG, "unregisterOneHandedModeListener: " + listener.asBinder());
        mOneHandedModeListeners.unregister(listener);
    }

    void dump(PrintWriter pw, String[] args) {
        pw.print  ("  Persisted Settings:");
        pw.print  (" enabled="); pw.print(OneHandedSettings.isFeatureEnabled(mContext));
        pw.print  (" xadj="); pw.print(OneHandedSettings.getSavedXAdj(mContext, 0));
        pw.print  (" yadj="); pw.print(OneHandedSettings.getSavedYAdj(mContext, 0));
        pw.print  (" scale="); pw.println(getSavedShrinkingScale());
        pw.print  (" gravity="); pw.println(OneHandedSettings.getSavedGravity(mContext, Gravity.LEFT | Gravity.BOTTOM));

        pw.print  ("  Current Mode: "); pw.println(mMode.toString());
        pw.print  ("  Current input pointer mapping:");
        pw.print  (" offsetX="); pw.print(mInputLastOffsetX);
        pw.print  (" offsetY="); pw.print(mInputLastOffsetY);
        pw.print  (" Scale=");   pw.println(mInputLastScale);

        Transformation t = this.getTransformation();
        pw.print  ("  Last transformation:"); pw.println(t == null ? "null" : t.toString());

        pw.println("  Internal status:");
        pw.print  ("    mPointerMappingSuspended="); pw.println(mPointerMappingSuspended);
        pw.print  ("    mDefaultDisplayInfo="); pw.println(mDefaultDisplayInfo.toString());
        pw.print  ("    mIsRacedAccessibilityEnabled="); pw.println(mIsRacedAccessibilityEnabled);
        pw.print  ("    Number of listeners="); pw.println(mOneHandedModeListeners.getRegisteredCallbackCount());

        mPanel.dump(pw, args);
    }
}
