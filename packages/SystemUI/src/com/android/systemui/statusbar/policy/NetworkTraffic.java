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
import android.net.TrafficStats;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Message;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;

import com.android.systemui.R;

import cyanogenmod.providers.CMSettings.Secure;

public class NetworkTraffic extends TextView {
    private static final int MODE_DISABLED = 0;
    private static final int MODE_UPSTREAM_ONLY = 1;
    private static final int MODE_DOWNSTREAM_ONLY = 2;
    private static final int MODE_UPSTREAM_AND_DOWNSTREAM = 3;
    private static final int REFRESH_INTERVAL = 1000;

    private int mMode = MODE_DISABLED;
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
    private SettingsObserver mObserver;
    private Drawable mDrawable;

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long timeDelta = SystemClock.elapsedRealtime() - mLastUpdateTime;

            if (timeDelta < REFRESH_INTERVAL * .95) {
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
                setVisibility(GONE);
            } else if (!isConnectionAvailable()) {
                clearHandlerCallbacks();
                setVisibility(GONE);
            } else {
                // Get information for uplink ready so the line return can be added
                StringBuilder output = new StringBuilder();
                if (shouldShowUpstream()) {
                    output.append(formatOutput(timeDelta, txData));
                }

                // Ensure text size is where it needs to be
                int textSize;
                if (mMode == MODE_UPSTREAM_AND_DOWNSTREAM) {
                    output.append("\n");
                    textSize = mTextSizeMulti;
                } else {
                    textSize = mTextSizeSingle;
                }

                // Add information for downlink if it's called for
                if (shouldShowDownstream()) {
                    output.append(formatOutput(timeDelta, rxData));
                }

                // Update view if there's anything new to show
                if (!output.toString().contentEquals(getText())) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) textSize);
                    setText(output.toString());
                }
                setVisibility(VISIBLE);
            }

            // Post delayed message to refresh in ~1000ms
            mTotalRxBytes = newTotalRxBytes;
            mTotalTxBytes = newTotalTxBytes;
            clearHandlerCallbacks();
            mTrafficHandler.sendEmptyMessageDelayed(0, REFRESH_INTERVAL);
        }

        private boolean shouldShowUpstream() {
            return mMode == MODE_UPSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
        }

        private boolean shouldShowDownstream() {
            return mMode == MODE_DOWNSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
        }

        private String formatOutput(long timeDelta, long data) {
            long speed = (long) (data / (timeDelta / 1000f));
            final Formatter.BytesResult result = Formatter.formatBytes(
                    mContext.getResources(), speed, 0);
            return mContext.getString(R.string.network_traffic_format,
                    result.value, result.units);
        }

        private boolean shouldHide(long rxData, long txData, long timeDelta) {
            if (!mAutoHide) {
                return false;
            }
            long speedTxKB = (long) (txData / (timeDelta / 1000f)) / 1024;
            long speedRxKB = (long) (rxData / (timeDelta / 1000f)) / 1024;

            if (shouldShowUpstream() && speedTxKB > mAutoHideThreshold) {
                return false;
            }
            if (shouldShowDownstream() && speedRxKB > mAutoHideThreshold) {
                return false;
            }
            return true;
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                updateViewState();
            }
        }
    };

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Secure.getUriFor(Secure.NETWORK_TRAFFIC_MODE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Secure.getUriFor(Secure.NETWORK_TRAFFIC_AUTOHIDE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Secure.getUriFor(Secure.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD),
                    false, this, UserHandle.USER_ALL);
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    public NetworkTraffic(Context context) {
        this(context, null);
    }

    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

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

        mObserver = new SettingsObserver(mTrafficHandler);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            mContext.registerReceiver(mIntentReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            mObserver.observe();
        }
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mObserver.unobserve();
            mAttached = false;
        }
    }

    private boolean isConnectionAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetworkInfo() != null;
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mMode = Secure.getIntForUser(resolver, Secure.NETWORK_TRAFFIC_MODE,
                0, UserHandle.USER_CURRENT);
        mAutoHide = Secure.getIntForUser(resolver, Secure.NETWORK_TRAFFIC_AUTOHIDE,
                0, UserHandle.USER_CURRENT) == 1;
        mAutoHideThreshold = Secure.getIntForUser(resolver,
                Secure.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD, 10, UserHandle.USER_CURRENT);

        if (mMode != MODE_DISABLED) {
            updateTrafficDrawable();
        }
        updateViewState();
    }

    private void updateViewState() {
        if (mMode != MODE_DISABLED && isConnectionAvailable()) {
            if (mAttached) {
                mTotalRxBytes = TrafficStats.getTotalRxBytes();
                mLastUpdateTime = SystemClock.elapsedRealtime();
                mTrafficHandler.sendEmptyMessage(1);
            }
        } else {
            clearHandlerCallbacks();
            setVisibility(GONE);
        }
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeMessages(0);
        mTrafficHandler.removeMessages(1);
    }

    private void updateTrafficDrawable() {
        final int drawableResId;
        if (mMode == MODE_UPSTREAM_AND_DOWNSTREAM) {
            drawableResId = R.drawable.stat_sys_network_traffic_updown;
        } else if (mMode == MODE_UPSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_up;
        } else if (mMode == MODE_DOWNSTREAM_ONLY) {
            drawableResId = R.drawable.stat_sys_network_traffic_down;
        } else {
            drawableResId = 0;
        }
        mDrawable = drawableResId != 0 ? getResources().getDrawable(drawableResId) : null;
        setCompoundDrawablesWithIntrinsicBounds(null, null, mDrawable, null);
        updateTrafficDrawableColor();
    }

    private void updateTrafficDrawableColor() {
        if (mDrawable != null) {
            mDrawable.setColorFilter(mIconTint, PorterDuff.Mode.SRC_ATOP);
        }
    }

    public void setDarkIntensity(float darkIntensity) {
        mIconTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mLightModeFillColor, mDarkModeFillColor);
        setTextColor(mIconTint);
        updateTrafficDrawableColor();
    }
}
