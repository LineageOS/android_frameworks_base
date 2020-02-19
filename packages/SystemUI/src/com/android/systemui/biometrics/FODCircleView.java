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

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.R;

import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

public class FODCircleView extends View {
    private final int mPositionX;
    private final int mPositionY;
    private final int mSize;
    private final int mDreamingMaxOffset;
    private final boolean mShouldBoostBrightness;
    private final Drawable mIconDrawable;
    private final Paint mPaintCircle = new Paint();
    private final Paint mPaintIconBackground = new Paint();
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
    private final WindowManager mWindowManager;

    private IFingerprintInscreen mFingerprintInscreenDaemon;

    private int mIconPositionX;
    private int mIconPositionY;
    private int mDimAmount;
    private int mDreamingOffsetX;
    private int mDreamingOffsetY;

    private int mColor;
    private int mColorBackground;

    private boolean mIsBouncer;
    private boolean mIsDreaming;
    private boolean mIsShowing;
    private boolean mIsCircleShowing;

    private Handler mHandler;

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

            if (isBouncer) {
                hide();
            } else if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
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

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon == null) {
            throw new RuntimeException("Unable to get IFingerprintInscreen");
        }

        try {
            mShouldBoostBrightness = daemon.shouldBoostBrightness();
            mPositionX = daemon.getPositionX();
            mPositionY = daemon.getPositionY();
            mSize = daemon.getSize();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve FOD circle position or size");
        }

        Resources res = context.getResources();

        mColor = res.getColor(R.color.config_fodColor);
        mColorBackground = res.getColor(R.color.config_fodColorBackground);

        mPaintCircle.setAntiAlias(true);
        mPaintCircle.setColor(mColor);
        mPaintIconBackground.setAntiAlias(true);
        mPaintIconBackground.setColor(mColorBackground);
        mIconDrawable = res.getDrawable(R.drawable.fod_icon_default);

        mWindowManager = context.getSystemService(WindowManager.class);

        mDreamingMaxOffset = (int) (mSize * 0.1f);

        mHandler = new Handler(Looper.getMainLooper());

        mParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        mParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.setTitle("Fingerprint on display");
        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        setBackgroundColor(Color.TRANSPARENT);

        mWindowManager.addView(this, mParams);

        updatePosition();
        hide();

        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
        mUpdateMonitor.registerCallback(mMonitorCallback);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float radius = mSize / 2.0f;
        float centerX = (float) mIconPositionX + radius;
        float centerY = (float) mIconPositionY + radius;

        if (mIsCircleShowing) {
            canvas.drawARGB(mDimAmount, 0, 0, 0);
            canvas.drawCircle(centerX, centerY, radius, mPaintCircle);
        } else {
            if (mIsDreaming) {
                centerX += mDreamingOffsetX;
                centerY += mDreamingOffsetY;
            } else {
                canvas.drawCircle(centerX, centerY, radius, mPaintIconBackground);
            }

            int halfWidth = mIconDrawable.getIntrinsicWidth() / 2;
            int halfHeight = mIconDrawable.getIntrinsicHeight() / 2;
            mIconDrawable.setBounds((int) centerX - halfWidth, (int) centerY - halfHeight,
                    (int) centerX + halfWidth, (int) centerY + halfHeight);
            mIconDrawable.draw(canvas);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newIsInside = (x > mIconPositionX && x < mIconPositionX + mSize) &&
                (y > mIconPositionY && y < mIconPositionY + mSize);

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

    public void showCircle() {
        mIsCircleShowing = true;

        setKeepScreenOn(true);

        setDim(true);
        updateAlpha();

        dispatchPress();
    }

    public void hideCircle() {
        mIsCircleShowing = false;

        dispatchRelease();

        setDim(false);
        updateAlpha();

        setKeepScreenOn(false);
    }

    public void show() {
        if (!mUpdateMonitor.isScreenOn()) {
            // Keyguard is shown just after screen turning off
            return;
        }

        if (mIsBouncer) {
            // Ignore show calls when Keyguard pin screen is being shown
            return;
        }

        mIsShowing = true;

        updatePosition();

        dispatchShow();
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        mIsShowing = false;

        setVisibility(View.GONE);
        hideCircle();
        dispatchHide();
    }

    private void updateAlpha() {
        if (mIsCircleShowing) {
            mIconDrawable.setAlpha(255);
        } else {
            mIconDrawable.setAlpha(mIsDreaming ? 128 : 255);
        }

        invalidate();
    }

    private void updatePosition() {
        Display defaultDisplay = mWindowManager.getDefaultDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        int rotation = defaultDisplay.getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                mIconPositionX = mPositionX;
                mIconPositionY = mPositionY;
                break;
            case Surface.ROTATION_90:
                mIconPositionX = mPositionY;
                mIconPositionY = mPositionX;
                break;
            case Surface.ROTATION_180:
                mIconPositionX = mPositionX;
                mIconPositionY = size.y - mPositionY - mSize;
                break;
            case Surface.ROTATION_270:
                mIconPositionX = size.x - mPositionY - mSize;
                mIconPositionY = mPositionX;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }

        invalidate();
    }

    private void setDim(boolean dim) {
        int dimAmount = 0;
        if (dim) {
            int curBrightness = Settings.System.getInt(getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 100);

            IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
            try {
                dimAmount = daemon.getDimAmount(curBrightness);
            } catch (RemoteException e) {
                // do nothing
            }

            if (mShouldBoostBrightness) {
                mParams.screenBrightness = 1.0f;
            }
        } else {
            mParams.screenBrightness = 0.0f;
        }

        mDimAmount = dimAmount;
        invalidate();
        mWindowManager.updateViewLayout(this, mParams);
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
}
