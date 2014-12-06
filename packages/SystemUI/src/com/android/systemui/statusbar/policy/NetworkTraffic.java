/**
 * Copyright (C) 2017 The LineageOS project
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

package com.android.systemui.statusbar.policy;

import android.animation.ArgbEvaluator;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;

import cyanogenmod.providers.CMSettings;

import java.lang.StringBuilder;
import java.text.DecimalFormat;

public class NetworkTraffic extends TextView {

    private static final int MASK_UP = 0x00000001;     // Least valuable bit
    private static final int MASK_DOWN = 0x00000002;   // Second least valuable bit
    private static final int MASK_PERIOD = 0xFFFF0000; // Most valuable 16 bits

    private static final int KB = 1024;
    private static final int MB = KB * KB;
    private static final int GB = MB * KB;

    private static DecimalFormat decimalFormat = new DecimalFormat("##0.#");
    static {
        decimalFormat.setMaximumIntegerDigits(3);
        decimalFormat.setMaximumFractionDigits(1);
    }

    private int mState = 0;
    private boolean mAttached;
    private long mTotalRxBytes;
    private long mTotalTxBytes;
    private long mLastUpdateTime;
    private int mTextSizeSingle;
    private int mTextSizeMulti;
    private boolean mAutoHide;
    private int mAutoHideThreshold;
    private int mDarkModeBackgroundColor;
    private int mDarkModeFillColor;
    private int mLightModeBackgroundColor;
    private int mLightModeFillColor;
    private int mIconTint = Color.WHITE;

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long timeDelta = SystemClock.elapsedRealtime() - mLastUpdateTime;

            if (timeDelta < getInterval(mState) * .95) {
                if (msg.what != 1) {
                    // View was just updated, nothing further to do
                    return;
                }
                if (timeDelta < 1) {
                    // Can't div by 0 so make sure the value displayed is minimal
                    timeDelta = Long.MAX_VALUE;
                }
            }
            mLastUpdateTime = SystemClock.elapsedRealtime();

            // Calculate the data rate from the change in total bytes and time
            long newTotalRxBytes = TrafficStats.getTotalRxBytes();
            long newTotalTxBytes = TrafficStats.getTotalTxBytes();
            long rxData = newTotalRxBytes - mTotalRxBytes;
            long txData = newTotalTxBytes - mTotalTxBytes;

            if (shouldHide(rxData, txData, timeDelta)) {
                setText("");
                setVisibility(View.GONE);
            } else if (!getConnectAvailable()) {
                clearHandlerCallbacks();
                setVisibility(View.GONE);
            } else {
                // Get information for uplink ready so the line return can be added
                StringBuilder output = new StringBuilder();
                if (isSet(mState, MASK_UP)) {
                    output.append(formatOutput(timeDelta, txData));
                }

                // Ensure text size is where it needs to be
                int textSize;
                if (isSet(mState, MASK_UP + MASK_DOWN)) {
                    output.append("\n");
                    textSize = mTextSizeMulti;
                } else {
                    textSize = mTextSizeSingle;
                }

                // Add information for downlink if it's called for
                if (isSet(mState, MASK_DOWN)) {
                    output.append(formatOutput(timeDelta, rxData));
                }

                // Update view if there's anything new to show
                if (!output.toString().contentEquals(getText())) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) textSize);
                    setText(output.toString());
                }
                setVisibility(View.VISIBLE);
            }

            // Post delayed message to refresh in ~1000ms
            mTotalRxBytes = newTotalRxBytes;
            mTotalTxBytes = newTotalTxBytes;
            clearHandlerCallbacks();
            mTrafficHandler.sendEmptyMessageDelayed(0, getInterval(mState));
        }

        private String formatOutput(long timeDelta, long data) {
            long speed = (long) (data / (timeDelta / 1000f));
            if (speed < KB) {
                return decimalFormat.format(speed) + " B/s";
            } else if (speed < MB) {
                return decimalFormat.format(speed / (float) KB) + " kB/s";
            } else if (speed < GB) {
                return decimalFormat.format(speed / (float) MB) + " MB/s";
            }
            return decimalFormat.format(speed / (float) GB) + " GB/s";
        }

        private boolean shouldHide(long rxData, long txData, long timeDelta) {
            long speedTxKB = (long) (txData / (timeDelta / 1000f)) / KB;
            long speedRxKB = (long) (rxData / (timeDelta / 1000f)) / KB;
            return mAutoHide &&
                    (isSet(mState, MASK_DOWN) && speedRxKB <= mAutoHideThreshold ||
                    isSet(mState, MASK_UP) && speedTxKB <= mAutoHideThreshold ||
                    isSet(mState, MASK_UP + MASK_DOWN) &&
                    speedRxKB <= mAutoHideThreshold && speedTxKB <= mAutoHideThreshold);
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            Uri uri = CMSettings.Secure.getUriFor(CMSettings.Secure.NETWORK_TRAFFIC_STATE);
            resolver.registerContentObserver(uri, false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(CMSettings.Secure
                    .getUriFor(CMSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(CMSettings.Secure
                    .getUriFor(CMSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD), false,
                    this, UserHandle.USER_ALL);
        }

        /*
         *  @hide
         */
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context) {
        this(context, null);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources resources = getResources();
        mTextSizeSingle = resources.getDimensionPixelSize(R.dimen.net_traffic_single_text_size);
        mTextSizeMulti = resources.getDimensionPixelSize(R.dimen.net_traffic_multi_text_size);
        mDarkModeBackgroundColor =
                context.getColor(R.color.dark_mode_icon_color_dual_tone_background);
        mDarkModeFillColor = context.getColor(R.color.dark_mode_icon_color_dual_tone_fill);
        mLightModeBackgroundColor =
                context.getColor(R.color.light_mode_icon_color_dual_tone_background);
        mLightModeFillColor = context.getColor(R.color.light_mode_icon_color_dual_tone_fill);
        Handler mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            mContext.registerReceiver(mIntentReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                updateSettings();
            }
        }
    };

    private boolean getConnectAvailable() {
        ConnectivityManager connManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = (connManager != null) ? connManager.getActiveNetworkInfo() : null;
        return network != null;
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mAutoHide = CMSettings.Secure.getIntForUser(resolver,
                CMSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE, 0,
                UserHandle.USER_CURRENT) == 1;

        mAutoHideThreshold = CMSettings.Secure.getIntForUser(resolver,
                CMSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD, 10,
                UserHandle.USER_CURRENT);

        mState = CMSettings.Secure.getInt(resolver, CMSettings.Secure.NETWORK_TRAFFIC_STATE, 0);

        if (isSet(mState, MASK_UP) || isSet(mState, MASK_DOWN)) {
            if (getConnectAvailable()) {
                if (mAttached) {
                    mTotalRxBytes = TrafficStats.getTotalRxBytes();
                    mLastUpdateTime = SystemClock.elapsedRealtime();
                    mTrafficHandler.sendEmptyMessage(1);
                }
                setVisibility(View.VISIBLE);
                updateTrafficDrawable(mIconTint);
                return;
            }
        } else {
            clearHandlerCallbacks();
        }
        setVisibility(View.GONE);
    }

    private static boolean isSet(int intState, int intMask) {
        return (intState & intMask) == intMask;
    }

    private static int getInterval(int intState) {
        int intInterval = intState >>> 16;
        return (intInterval >= 250 && intInterval <= 32750) ? intInterval : 1000;
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeMessages(0);
        mTrafficHandler.removeMessages(1);
    }

    private void updateTrafficDrawable(int trafcolor) {
        int intTrafficDrawable;
        Drawable drawTrafficIcon = null;
        if (isSet(mState, MASK_UP + MASK_DOWN)) {
            intTrafficDrawable = R.drawable.stat_sys_network_traffic_updown;
        } else if (isSet(mState, MASK_UP)) {
            intTrafficDrawable = R.drawable.stat_sys_network_traffic_up;
        } else if (isSet(mState, MASK_DOWN)) {
            intTrafficDrawable = R.drawable.stat_sys_network_traffic_down;
        } else {
            intTrafficDrawable = 0;
        }
        if (intTrafficDrawable != 0) {
            drawTrafficIcon = getResources().getDrawable(intTrafficDrawable);
            drawTrafficIcon.setColorFilter(trafcolor, PorterDuff.Mode.SRC_ATOP);
        }
        setCompoundDrawablesWithIntrinsicBounds(null, null, drawTrafficIcon, null);
    }

    public void setDarkIntensity(float darkIntensity) {
        mIconTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mLightModeFillColor, mDarkModeFillColor);
        setTextColor(mIconTint);
        updateSettings();
    }
}
