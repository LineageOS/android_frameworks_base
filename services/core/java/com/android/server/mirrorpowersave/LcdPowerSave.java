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

package com.android.server.mirrorpowersave;

import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.WindowManagerPolicy;

import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;

public final class LcdPowerSave {
    private static final String TAG = LcdPowerSave.class.getSimpleName();
    private static final boolean DEBUG = false;
    // Local display state.
    private static final int LOCAL_LCD_SCREEN_OFF = 0;
    private static final int LOCAL_LCD_SCREEN_DIM = 1;
    private static final int LOCAL_LCD_SCREEN_BRIGHT = 2;

    // Default timeout in milliseconds.  This is only used until the settings
    // provider populates the actual default value.
    private static final int DEFAULT_SCREEN_OFF_TIMEOUT = 15 * 1000;

    private PowerManager mPowerManager = null;
    private WindowManagerPolicy mPolicy = null;
    private Context mContext;
    private LightsManager mLightsManager = null;
    private Light mBackLight = null;
    private HandlerThread mHandlerThread;
    private LcdOffPowerSaveHandler mHandler;
    private PowerManager.WakeLock mHoldingScreenWakeLock = null;
    private ScreenOffTimeoutSettingObserver mScreenOffTimeoutSettingObserver;
    private ScreenBrightnessSettingObserver mScreenBrightnessSettingObserver;

    private int mScreenBrightnessSettingDefault;
    private int mScreenBrightnessSetting;
    private int mScreenOffTimeoutSetting;
    private int mLocalLcdScreenState = LOCAL_LCD_SCREEN_BRIGHT;

    // The dim screen brightness.
    private int mScreenBrightnessDimConfig = 10;

    // The screen dim duration, in milliseconds.
    // This is subtracted from the end of the LCD off timeout so the
    // minimum LCD off timeout should be longer than this.
    private int mMaximumScreenDimDurationConfig;

    // The maximum screen dim time expressed as a ratio relative to the LCD
    // off timeout.  If the LCD off timeout is very short then we want the
    // dim timeout to also be quite short so that most of the time is spent on.
    // Otherwise the user won't get much LCD on time before dimming occurs.
    private float mMaximumScreenDimRatioConfig;

    private volatile boolean mIsActivateLcdPowerMode = false;
    private boolean mIsActivateLocalTouch = true;
    private boolean mIsResumeLcdWithUserActivity = true;
    private boolean mIsDropPowerKeyUp = false;

    private final Object mLock = new Object();

    private LcdPowerSave(){
        //nothing to do.
    }

    LcdPowerSave(Context context){
        mContext = context;
    }

    void systemReady() {
        mHandlerThread = new ServiceThread(
                TAG, Process.THREAD_PRIORITY_DISPLAY, false /*allowIo*/);
        mHandlerThread.start();
        mHandler = new LcdOffPowerSaveHandler(mHandlerThread.getLooper());
        mPowerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);

        // Brightness and LCD handlers
        mLightsManager = (LightsManager)LocalServices.getService(LightsManager.class);
        mBackLight = mLightsManager.getLight(LightsManager.LIGHT_ID_BACKLIGHT);
        mPolicy = (WindowManagerPolicy)LocalServices.getService(WindowManagerPolicy.class);
        readConfiguration();
    }

    void userActivity(long eventTime, int event){
        if (DEBUG) {
            Slog.i(TAG, "userActivity eventTime(" + eventTime + ")" + " event("+event+")");
        }
        synchronized (mLock) {
            if (!mIsActivateLcdPowerMode) {
                return;
            }
            if (isLocalLcdScreenStateOffLocked()) {
                // LCD is off
                if (mIsResumeLcdWithUserActivity) {
                    scheduleLcdOffLocked(true/*resume LCD*/, true/*delay*/);
                }
            } else {
                // LCD is bright
                scheduleLcdOffLocked(true/*resume LCD*/, true/*delay*/);
            }

        }
    }

    boolean interceptPowerKeyBeforeQueueingWhenLcdOff(KeyEvent event, int policyFlags) {
        if (DEBUG) {
            Slog.i(TAG, "interceptKeyBeforeQueueing : event(" + event + ")" +
                    " policyFlags(0x" + Integer.toHexString(policyFlags) + ")");
        }
        synchronized (mLock) {
            if (!mIsActivateLcdPowerMode) {
                return false;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_POWER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (isLocalLcdScreenStateOffLocked()) {
                        if (DEBUG) {
                            Slog.i(TAG, "Ignore the power key for 'go to sleep'");
                        }
                        scheduleLcdOffLocked(true/*resume LCD*/, true/*delay*/);
                        mIsDropPowerKeyUp = true;
                        return true;
                    }
                } else {
                    if (mIsDropPowerKeyUp) {
                        mIsDropPowerKeyUp = false;
                        return true;
                    }
                }
            }
            if (DEBUG) {
                printlogLocked();
            }
        }
        return false;
    }

    void interceptProximityWhenLcdOn() {
        if (DEBUG) {
            Slog.i(TAG, "InterceptProximityWhenLcdOn");
        }
        synchronized (mLock) {
            if (!mIsActivateLcdPowerMode) {
                return;
            }
            if (DEBUG) {
                Slog.i(TAG, "Schedule LCD off after Proximity becomes ineffective");
            }
            scheduleLcdOffLocked(true/*resume LCD*/, true/*delay*/);
        }
    }

    void configureLocalTouch(boolean isActivate) {
        if (DEBUG) {
            Slog.i(TAG, "config local touch : " + isActivate);
        }
        synchronized (mLock) {
            mIsActivateLocalTouch = isActivate;
        }
    }

    void configureResumeLcdWithUserActivity(boolean isActivate) {
        if (DEBUG) {
            Slog.i(TAG, "config resume LCD with user activity : " + isActivate);
        }
        synchronized (mLock) {
            mIsResumeLcdWithUserActivity = isActivate;
        }
    }

    void lcdOn() {
        synchronized (mLock) {
            if (mIsActivateLcdPowerMode) {
                scheduleLcdOffLocked(true/*resume LCD*/, true/*delay*/);
            }
        }
    }

    void lcdPowerSaving(boolean isActivate) {
        synchronized (mLock) {
            if (isActivate) {
                // Connected
                if (DEBUG) {
                    Slog.i(TAG, "activate lcd power saving");
                }
                if (!mIsActivateLcdPowerMode) {
                    // Keep screen on starts
                    if (mHoldingScreenWakeLock == null) {
                        mHoldingScreenWakeLock = mPowerManager.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ON_AFTER_RELEASE
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            TAG);
                    }
                    if (!mHoldingScreenWakeLock.isHeld()) {
                        mHoldingScreenWakeLock.acquire();
                    }
                    mPolicy.keepScreenOnStartedLw();

                    // Set setting observer
                    mScreenBrightnessSettingDefault =
                            mPowerManager.getDefaultScreenBrightnessSetting();
                    mScreenBrightnessSettingObserver =
                            new ScreenBrightnessSettingObserver(mHandler);
                    mScreenBrightnessSettingObserver.observe(mContext);
                    updateScreenBrightnessSetting();

                    mScreenOffTimeoutSettingObserver =
                            new ScreenOffTimeoutSettingObserver(mHandler);
                    mScreenOffTimeoutSettingObserver.observe(mContext);
                    updateScreenOffTimeoutSetting();

                    mLocalLcdScreenState = mPowerManager.isInteractive() ?
                            LOCAL_LCD_SCREEN_BRIGHT : LOCAL_LCD_SCREEN_OFF;
                    mIsActivateLcdPowerMode = true;
                }
                scheduleLcdOffLocked(mIsActivateLocalTouch/*resume LCD*/,
                        mIsActivateLocalTouch/*delay*/);
            } else {
                // Disconnected
                if (DEBUG) {
                    Slog.i(TAG, "de-activate lcd power saving");
                }
                if (!mIsActivateLcdPowerMode) {
                    if (DEBUG) {
                        Slog.i(TAG, "ignore the request");
                    }
                    return;
                }

                mIsActivateLcdPowerMode = false;

                mScreenOffTimeoutSettingObserver.unObserve(mContext);
                mScreenOffTimeoutSettingObserver = null;
                mScreenBrightnessSettingObserver.unObserve(mContext);
                mScreenBrightnessSettingObserver = null;
                cancelLcdOffLocked();

                mPolicy.keepScreenOnStoppedLw();
                if (mHoldingScreenWakeLock != null) {
                    if (mHoldingScreenWakeLock.isHeld()) {
                        mHoldingScreenWakeLock.release();
                    }
                }
                mHoldingScreenWakeLock = null;
            }
            if (DEBUG) {
                printlogLocked();
            }
        }
    }

    private void readConfiguration() {
        final Resources resources = mContext.getResources();
        synchronized (mLock) {
            mMaximumScreenDimDurationConfig = resources.getInteger(
                    com.android.internal.R.integer.config_maximumScreenDimDuration);
            mMaximumScreenDimRatioConfig = resources.getFraction(
                    com.android.internal.R.fraction.config_maximumScreenDimRatio, 1, 1);
            mScreenBrightnessDimConfig = resources.getInteger(
                    com.android.internal.R.integer.config_screenBrightnessDim);
        }
    }

    private void updateScreenOffTimeoutSetting() {
        synchronized (mLock) {
            final ContentResolver resolver = mContext.getContentResolver();
            mScreenOffTimeoutSetting = Settings.System.getIntForUser(resolver,
                    Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_SCREEN_OFF_TIMEOUT,
                    UserHandle.USER_CURRENT);
            if (!isLocalLcdScreenStateOffLocked() && mIsActivateLcdPowerMode) {
                scheduleLcdOffLocked(true/*resume LCD*/, true/*delay*/);
            }
        }
    }

    private void updateScreenBrightnessSetting() {
        synchronized (mLock) {
            final ContentResolver resolver = mContext.getContentResolver();
            mScreenBrightnessSetting = Settings.System.getIntForUser(resolver,
                    Settings.System.SCREEN_BRIGHTNESS, mScreenBrightnessSettingDefault,
                    UserHandle.USER_CURRENT);
        }
    }

    private boolean isLocalLcdScreenStateOffLocked() {
        return mLocalLcdScreenState == LOCAL_LCD_SCREEN_OFF;
    }

    private int getScreenDimDurationLocked(int screenOffTimeout) {
        return Math.min(mMaximumScreenDimDurationConfig,
                (int)(screenOffTimeout * mMaximumScreenDimRatioConfig));
    }

    private final class LcdOffPowerSaveHandler extends Handler {
        public LcdOffPowerSaveHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            if (DEBUG) {
                Slog.i(TAG, "handleMessage (" + msg.what + ")");
            }
            synchronized (mLock) {
                int requestState = msg.what;
                switch (requestState) {
                    case LOCAL_LCD_SCREEN_OFF:
                        //Local touch configure might be changed during screen status is off.
                        //So, the setBrightness should be called before the if condition.
                        mBackLight.setBrightness(PowerManager.BRIGHTNESS_OFF);
                        if (!isLocalLcdScreenStateOffLocked()) {
                            mLocalLcdScreenState = LOCAL_LCD_SCREEN_OFF;
                        }
                        break;
                    case LOCAL_LCD_SCREEN_BRIGHT:
                        if (mLocalLcdScreenState != LOCAL_LCD_SCREEN_BRIGHT) {
                            mBackLight.setBrightness(mScreenBrightnessSetting);
                            mLocalLcdScreenState = LOCAL_LCD_SCREEN_BRIGHT;
                        }
                        break;
                    case LOCAL_LCD_SCREEN_DIM:
                        if (mLocalLcdScreenState == LOCAL_LCD_SCREEN_BRIGHT) {
                            mBackLight.setBrightness(mScreenBrightnessDimConfig);
                            mLocalLcdScreenState = LOCAL_LCD_SCREEN_DIM;
                        }
                        break;
                    default :
                        Slog.w(TAG, "unknown msg");
                        break;
                }
                if (DEBUG) {
                    printlogLocked();
                }
            }
        }
    }

    private void scheduleLcdOffLocked(boolean isResumeLcd, boolean withDelay) {
        if (DEBUG) {
            Slog.i(TAG, "start lcd off schedule");
        }
        if (isResumeLcd) {
            cancelLcdOffLocked();
        }
        mHandler.removeMessages(LOCAL_LCD_SCREEN_DIM);
        mHandler.removeMessages(LOCAL_LCD_SCREEN_OFF);
        if (mIsActivateLcdPowerMode) {
            Message msg = mHandler.obtainMessage(LOCAL_LCD_SCREEN_OFF);
            Message dim = mHandler.obtainMessage(LOCAL_LCD_SCREEN_DIM);
            if (withDelay) {
                mHandler.sendMessageDelayed(dim, mScreenOffTimeoutSetting -
                        getScreenDimDurationLocked(mScreenOffTimeoutSetting));
                mHandler.sendMessageDelayed(msg, mScreenOffTimeoutSetting);
            } else {
                mHandler.sendMessage(msg);
            }
        }
    }

    private void cancelLcdOffLocked() {
        if (DEBUG) {
            Slog.i(TAG, "cancel lcd off");
        }
        mHandler.removeMessages(LOCAL_LCD_SCREEN_BRIGHT);
        mHandler.removeMessages(LOCAL_LCD_SCREEN_DIM);
        mHandler.removeMessages(LOCAL_LCD_SCREEN_OFF);
        Message msg = mHandler.obtainMessage(LOCAL_LCD_SCREEN_BRIGHT);
        mHandler.sendMessage(msg);
    }

    private void printlogLocked() {
        Slog.i(TAG, "== dump begin ==");
        String pwm_state = mIsActivateLcdPowerMode ? "active" : "de-active";
        Slog.i(TAG, "LCD Power Saving : " + pwm_state);
        Slog.i(TAG, "original brightness : " + mScreenBrightnessSetting);
        String brightness = Settings.System.getString(
                mContext.getContentResolver(), "screen_brightness");
        Slog.i(TAG, "system brightness : " + brightness);
        String screenState = mPowerManager.isInteractive() ? "screen on" : "screen off";
        Slog.i(TAG, "current system screen state : " + screenState);
        Slog.i(TAG, "local screen : " + mLocalLcdScreenState);
        Slog.i(TAG, "Screen Off Timeout  : " + mScreenOffTimeoutSetting);
        Slog.i(TAG, "== dump end ==");
    }

    private final class ScreenBrightnessSettingObserver extends ContentObserver {
        public ScreenBrightnessSettingObserver(Handler handler) {
            super(handler);
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS), false, this, UserHandle.USER_ALL);
        }

        void unObserve(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateScreenBrightnessSetting();
        }
    }

    private final class ScreenOffTimeoutSettingObserver extends ContentObserver {
        public ScreenOffTimeoutSettingObserver(Handler handler) {
            super(handler);
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_OFF_TIMEOUT), false, this, UserHandle.USER_ALL);
        }

        void unObserve(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateScreenOffTimeoutSetting();
        }
    }
}
