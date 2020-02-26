/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.tuner.TunerService;

import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

public class FODCircleView extends ImageView implements Handler.Callback, TunerService.Tunable {

    private final String SCREEN_BRIGHTNESS = "system:" + Settings.System.SCREEN_BRIGHTNESS;

    private final int mPositionX;
    private final int mPositionY;
    private final int mSize;
    private final int mDreamingMaxOffset;
    private final int mNavigationBarSize;
    private final int MSG_HBM_OFF = 1001;
    private final int MSG_HBM_ON = 1002;
    private final boolean mShouldBoostBrightness;
    private final Paint mPaintFingerprint = new Paint();
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
    private final WindowManager mWindowManager;

    private IFingerprintInscreen mFingerprintInscreenDaemon;

    private int mCurDim;
    private int mDreamingOffsetX;
    private int mDreamingOffsetY;

    private int mColor;
    private int mColorBackground;
    private int mCurrentBrightness;

    private int mHbmOffDelay = 0;
    private int mHbmOnDelay = 0;

    private boolean mIsBouncer;
    private boolean mIsDreaming;
    private boolean mSupportsAlwaysOnHbm;
    private boolean mIsShowing;
    private boolean mIsCircleShowing;

    private float mCurrentDimAmount = 0.0f;

    private Handler mHandler;

    private LockPatternUtils mLockPatternUtils;

    private Timer mBurnInProtectionTimer;

    private IFingerprintInscreenCallback mFingerprintInscreenCallback =
            new IFingerprintInscreenCallback.Stub() {
        @Override
        public void onFingerDown() {
            mHandler.post(() -> showCircle());
        }

        @Override
        public void onFingerUp() {
            mHandler.post(() -> hideCircle());
        }
    };

    private KeyguardUpdateMonitor mUpdateMonitor;

    private KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            mIsDreaming = dreaming;
            updateAlpha();

            if (dreaming) {
                mBurnInProtectionTimer = new Timer();
                mBurnInProtectionTimer.schedule(new BurnInProtectionTask(), 0, 60 * 1000);
            } else if (mBurnInProtectionTimer != null) {
                mBurnInProtectionTimer.cancel();
            }
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            mIsBouncer = isBouncer;
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                if (isPinOrPattern(mUpdateMonitor.getCurrentUser()) || !isBouncer) {
                    show();
                } else {
                    hide();
                }
            } else {
                hide();
            }
        }

        @Override
        public void onScreenTurnedOff() {
            hide();
        }

        @Override
        public void onScreenTurnedOn() {
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
            }
        }
    };

    public FODCircleView(Context context) {
        super(context);

        setScaleType(ScaleType.CENTER);

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon == null) {
            throw new RuntimeException("Unable to get IFingerprintInscreen");
        }

         vendor.lineage.biometrics.fingerprint.inscreen.V1_1.IFingerprintInscreen
                    daemonV1_1 = getFingerprintInScreenDaemonV1_1(daemon);

        try {
            mShouldBoostBrightness = daemon.shouldBoostBrightness();
            mPositionX = daemon.getPositionX();
            mPositionY = daemon.getPositionY();
            mSize = daemon.getSize();
            if (daemonV1_1 != null) {
                mSupportsAlwaysOnHbm = daemonV1_1.supportsAlwaysOnHBM();
                mHbmOnDelay = daemonV1_1.getHbmOnDelay();
                mHbmOffDelay = daemonV1_1.getHbmOffDelay();
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve FOD circle position or size");
        }

        Resources res = context.getResources();

        mColor = res.getColor(R.color.config_fodColor);
        mColorBackground = res.getColor(R.color.config_fodColorBackground);

        mPaintFingerprint.setAntiAlias(true);
        mPaintFingerprint.setColor(mColorBackground);

        mWindowManager = context.getSystemService(WindowManager.class);

        mNavigationBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);

        mDreamingMaxOffset = (int) (mSize * 0.1f);

        mHandler = new Handler(Looper.getMainLooper(), this);

        mParams.height = mSize;
        mParams.width = mSize;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.setTitle("Fingerprint on display");
        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_DIM_BEHIND |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;

        mWindowManager.addView(this, mParams);

        updatePosition();
        hide();

        mLockPatternUtils = new LockPatternUtils(mContext);

        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
        mUpdateMonitor.registerCallback(mMonitorCallback);

        getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            float drawingDimAmount = mParams.dimAmount;
            if (!mSupportsAlwaysOnHbm) {
                if (mCurrentDimAmount == 0.0f && drawingDimAmount > 0.0f) {
                    dispatchPress();
                    mCurrentDimAmount = drawingDimAmount;
                } else if (mCurrentDimAmount > 0.0f && drawingDimAmount == 0.0f) {
                    mCurrentDimAmount = drawingDimAmount;
                }
            }
        });
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        mCurrentBrightness = newValue != null ?  Integer.parseInt(newValue) : 0;
        setDim(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawCircle(mSize / 2, mSize / 2, mSize / 2.0f, mPaintFingerprint);
        super.onDraw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newIsInside = (x > 0 && x < mSize) && (y > 0 && y < mSize);

        if (event.getAction() == MotionEvent.ACTION_DOWN && newIsInside) {
            showCircle();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            hideCircle();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            return true;
        }

        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updatePosition();
    }

    public IFingerprintInscreen getFingerprintInScreenDaemon() {
        if (mFingerprintInscreenDaemon == null) {
            try {
                mFingerprintInscreenDaemon = IFingerprintInscreen.getService();
                if (mFingerprintInscreenDaemon != null) {
                    mFingerprintInscreenDaemon.setCallback(mFingerprintInscreenCallback);
                    mFingerprintInscreenDaemon.asBinder().linkToDeath((cookie) -> {
                        mFingerprintInscreenDaemon = null;
                    }, 0);
                }
            } catch (NoSuchElementException | RemoteException e) {
                // do nothing
            }
        }
        return mFingerprintInscreenDaemon;
    }

    public vendor.lineage.biometrics.fingerprint.inscreen.V1_1.IFingerprintInscreen
        getFingerprintInScreenDaemonV1_1(IFingerprintInscreen daemon) {
        if (daemon == null) return null;
        return vendor.lineage.biometrics.fingerprint.inscreen.V1_1.IFingerprintInscreen.castFrom(
                   daemon);
    }

    public void dispatchPress() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onPress();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchRelease() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onRelease();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchShow() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onShowFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchHide() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onHideFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void switchHbm(boolean enable) {
        if (mShouldBoostBrightness) {
            if (enable) {
                mParams.screenBrightness = 1.0f;
            } else {
                mParams.screenBrightness = 0.0f;
            }
            mWindowManager.updateViewLayout(this, mParams);
        }

        vendor.lineage.biometrics.fingerprint.inscreen.V1_1.IFingerprintInscreen daemonV1_1 =
                getFingerprintInScreenDaemonV1_1(getFingerprintInScreenDaemon());

        try {
            if (daemonV1_1 != null) {
                daemonV1_1.switchHbm(enable);
            }
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void showCircle() {
        mIsCircleShowing = true;

        setKeepScreenOn(true);

        if (!mSupportsAlwaysOnHbm) {
            setDim(true);
        } else {
            dispatchPress();
            setColorFilter(Color.argb(0, 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
        }
        updateAlpha();

        mPaintFingerprint.setColor(mColor);
        setImageResource(R.drawable.fod_icon_pressed);
        invalidate();
    }

    public void hideCircle() {
        mIsCircleShowing = false;

        mPaintFingerprint.setColor(mColorBackground);
        setImageResource(R.drawable.fod_icon_default);
        invalidate();

        dispatchRelease();

        if (!mSupportsAlwaysOnHbm) {
            setDim(false);
        } else {
            setColorFilter(Color.argb(mCurDim, 0, 0, 0),
                    PorterDuff.Mode.SRC_ATOP);
            invalidate();
        }
        updateAlpha();

        setKeepScreenOn(false);
    }

    public void show() {
        if (!mUpdateMonitor.isScreenOn()) {
            // Keyguard is shown just after screen turning off
            return;
        }

        if (mIsBouncer && !isPinOrPattern(mUpdateMonitor.getCurrentUser())) {
            // Ignore show calls when Keyguard password screen is being shown
            return;
        }

        mIsShowing = true;

        updatePosition();

        dispatchShow();
        if (mSupportsAlwaysOnHbm) {
            Dependency.get(TunerService.class).addTunable(this, SCREEN_BRIGHTNESS);
            setDim(true);
            mHandler.sendEmptyMessageDelayed(MSG_HBM_ON, mHbmOnDelay);
        }
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        mIsShowing = false;

        if (mSupportsAlwaysOnHbm) {
            mHandler.sendEmptyMessageDelayed(MSG_HBM_OFF, mHbmOffDelay);
            if (mHandler.hasMessages(MSG_HBM_ON)) {
                mHandler.removeMessages(MSG_HBM_ON);
            }
            setDim(false);
            Dependency.get(TunerService.class).removeTunable(this);
        }
        setVisibility(View.GONE);
        hideCircle();
        dispatchHide();
    }

    private void updateAlpha() {
        if (mIsCircleShowing) {
            setAlpha(1.0f);
        } else {
            setAlpha(mIsDreaming ? 0.5f : 1.0f);
        }
    }

    private void updatePosition() {
        Display defaultDisplay = mWindowManager.getDefaultDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        int rotation = defaultDisplay.getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                mParams.x = mPositionX;
                mParams.y = mPositionY;
                break;
            case Surface.ROTATION_90:
                mParams.x = mPositionY;
                mParams.y = mPositionX;
                break;
            case Surface.ROTATION_180:
                mParams.x = mPositionX;
                mParams.y = size.y - mPositionY - mSize;
                break;
            case Surface.ROTATION_270:
                mParams.x = size.x - mPositionY - mSize - mNavigationBarSize;
                mParams.y = mPositionX;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }

        if (mIsDreaming) {
            mParams.x += mDreamingOffsetX;
            mParams.y += mDreamingOffsetY;
        }

        mWindowManager.updateViewLayout(this, mParams);
    }

    private void setDim(boolean dim) {
        if (dim) {
            int dimAmount = 0;

            if (!mSupportAlwaysOnHbm) {
                mCurrentBrightness = Settings.System.getInt(getContext().getContentResolver(),
                       Settings.System.SCREEN_BRIGHTNESS, 100);
            }

            IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
            try {
                dimAmount = daemon.getDimAmount(mCurrentBrightness);
            } catch (RemoteException e) {
                // do nothing
            }

            mParams.dimAmount = dimAmount / 255.0f;
            if (mSupportsAlwaysOnHbm) {
                mCurDim = dimAmount;
                setColorFilter(Color.argb(dimAmount, 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
            }
        } else {
            mParams.dimAmount = 0.0f;
        }

        mWindowManager.updateViewLayout(this, mParams);
    }

    private boolean isPinOrPattern(int userId) {
        int passwordQuality = mLockPatternUtils.getActivePasswordQuality(userId);
        switch (passwordQuality) {
            // PIN
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
            // Pattern
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                return true;
        }

        return false;
    }

    private class BurnInProtectionTask extends TimerTask {
        @Override
        public void run() {
            long now = System.currentTimeMillis() / 1000 / 60;

            mDreamingOffsetX = (int) (now % (mDreamingMaxOffset * 4));
            if (mDreamingOffsetX > mDreamingMaxOffset * 2) {
                mDreamingOffsetX = mDreamingMaxOffset * 4 - mDreamingOffsetX;
            }

            // Let y to be not synchronized with x, so that we get maximum movement
            mDreamingOffsetY = (int) ((now + mDreamingMaxOffset / 3) % (mDreamingMaxOffset * 2));
            if (mDreamingOffsetY > mDreamingMaxOffset * 2) {
                mDreamingOffsetY = mDreamingMaxOffset * 4 - mDreamingOffsetY;
            }

            mDreamingOffsetX -= mDreamingMaxOffset;
            mDreamingOffsetY -= mDreamingMaxOffset;

            mHandler.post(() -> updatePosition());
        }
    };

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_HBM_OFF: {
                switchHbm(false);
            } break;
            case MSG_HBM_ON: {
                switchHbm(true);
            } break;

        }
        return true;
    }
}
