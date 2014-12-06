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
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Message;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;

import lineageos.providers.LineageSettings;

import org.lineageos.platform.internal.R;

public class NetworkTraffic extends TextView {
    private static final String TAG = "NetworkTraffic";

    private static final int MODE_DISABLED = 0;
    private static final int MODE_UPSTREAM_ONLY = 1;
    private static final int MODE_DOWNSTREAM_ONLY = 2;
    private static final int MODE_UPSTREAM_AND_DOWNSTREAM = 3;

    private static final int MESSAGE_TYPE_PERIODIC_REFRESH = 0;
    private static final int MESSAGE_TYPE_UPDATE_VIEW = 1;

    private static final int REFRESH_INTERVAL = 2000;

    private static final boolean mShowUnits = false; // to become a setting

    private static final int BANDWIDTH_UNIT_KBPS = 1;
    private static final int BANDWIDTH_UNIT_MBPS = 2;

    private int mBwUnit = BANDWIDTH_UNIT_MBPS;

    private int mMode = MODE_DISABLED;
    private boolean mStatusBarIsVisible;
    private long mTxKbps;
    private long mRxKbps;
    private long mLastTxBytesTotal;
    private long mLastRxBytesTotal;
    private long mLastUpdateTime;
    private int mTextSizeSingle;
    private int mTextSizeMulti;
    private boolean mAutoHide;
    private int mAutoHideThreshold;
    private int mDarkModeFillColor;
    private int mLightModeFillColor;
    private int mIconTint = Color.WHITE;
    private SettingsObserver mObserver;
    private Drawable mDrawable;

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

        mDarkModeFillColor = context.getColor(
                com.android.systemui.R.color.dark_mode_icon_color_dual_tone_fill);
        mLightModeFillColor = context.getColor(
                com.android.systemui.R.color.light_mode_icon_color_dual_tone_fill);

        mStatusBarIsVisible = false;

        mObserver = new SettingsObserver(mTrafficHandler);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.d(TAG, "in onAttachedToWindow()");
        mContext.registerReceiver(mIntentReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        mObserver.observe();
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mDarkReceiver);
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Log.d(TAG, "in onDetachedFromWindow()");
        mContext.unregisterReceiver(mIntentReceiver);
        mObserver.unobserve();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mDarkReceiver);
    }

    public void setStatusBarVisibility(boolean isVisible) {
        if (mStatusBarIsVisible != isVisible) {
            mStatusBarIsVisible = isVisible;
            updateViewState();
        }
    }

    private DarkReceiver mDarkReceiver = new DarkReceiver() {
        @Override
        public void onDarkChanged(Rect area, float darkIntensity, int tint) {
            Log.d(TAG, "in onDarkChanged()");
            mIconTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                    mLightModeFillColor, mDarkModeFillColor);
            setTextColor(mIconTint);
            updateTrafficDrawableColor();
        }
    };

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long now = SystemClock.elapsedRealtime();
            long timeDelta = now - mLastUpdateTime;
            if (msg.what == MESSAGE_TYPE_PERIODIC_REFRESH
                    && timeDelta >= REFRESH_INTERVAL * 0.95f) {
                // Update counters
                mLastUpdateTime = now;
                long txBytes = TrafficStats.getTotalTxBytes() - mLastTxBytesTotal;
                long rxBytes = TrafficStats.getTotalRxBytes() - mLastRxBytesTotal;
                mTxKbps = (long) (txBytes * 8f / (timeDelta / 1000f) / 1000f);
                mRxKbps = (long) (rxBytes * 8f / (timeDelta / 1000f) / 1000f);
                mLastTxBytesTotal += txBytes;
                mLastRxBytesTotal += rxBytes;
            }

            final boolean enabled = mMode != MODE_DISABLED && isConnectionAvailable();
            final boolean showUpstream =
                    mMode == MODE_UPSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
            final boolean showDownstream =
                    mMode == MODE_DOWNSTREAM_ONLY || mMode == MODE_UPSTREAM_AND_DOWNSTREAM;
            final boolean shouldHide = mAutoHide && (!showUpstream || mTxKbps < 100)
                    && (!showDownstream || mRxKbps < 100);

            Log.d(TAG, "in handleMessage() "
                    + " what=" + msg.what
                    + " enabled=" + enabled
                    + " shouldHide=" + shouldHide
                    + " mStatusBarIsVisible=" + mStatusBarIsVisible
            );

            if (!enabled || shouldHide) {
                setText("");
                setVisibility(GONE);
            } else {
                // Get information for uplink ready so the line return can be added
                StringBuilder output = new StringBuilder();
                if (showUpstream) {
                    output.append(formatOutput(mTxKbps));
                }

                // Ensure text size is where it needs to be
                int textSize;
                if (showUpstream && showDownstream) {
                    output.append("\n");
                    textSize = mTextSizeMulti;
                } else {
                    textSize = mTextSizeSingle;
                }

                // Add information for downlink if it's called for
                if (showDownstream) {
                    output.append(formatOutput(mRxKbps));
                }

                // Update view if there's anything new to show
                if (!output.toString().contentEquals(getText())) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) textSize);
                    setText(output.toString());
                }
                setVisibility(VISIBLE);
            }

            // Schedule period refresh
            mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
            if (enabled && mStatusBarIsVisible) {
                mTrafficHandler.sendEmptyMessageDelayed(MESSAGE_TYPE_PERIODIC_REFRESH,
                        REFRESH_INTERVAL);
            }
        }

        private String formatOutput(long kbps) {
            final String value;
            final String unit;
            if (mBwUnit == BANDWIDTH_UNIT_KBPS) {
                value = String.format("%d", kbps);
                unit = mContext.getString(R.string.kilobitspersecond_short);
            } else { // Mbps
                value = String.format("%.1f", (float) kbps / 1000f);
                unit = mContext.getString(R.string.megabitspersecond_short);
            }
            if (mShowUnits) {
                return value + " " + unit;
            } else {
                return value;
            }
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
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_MODE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD),
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

    private boolean isConnectionAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetworkInfo() != null;
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mMode = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_MODE, 0, UserHandle.USER_CURRENT);
        mAutoHide = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE, 0, UserHandle.USER_CURRENT) == 1;
        mAutoHideThreshold = LineageSettings.Secure.getIntForUser(resolver,
                LineageSettings.Secure.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD, 10,
                UserHandle.USER_CURRENT);

        if (mMode != MODE_DISABLED) {
            updateTrafficDrawable();
        }
        updateViewState();
    }

    private void updateViewState() {
        mTrafficHandler.sendEmptyMessage(MESSAGE_TYPE_UPDATE_VIEW);
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeMessages(MESSAGE_TYPE_PERIODIC_REFRESH);
        mTrafficHandler.removeMessages(MESSAGE_TYPE_UPDATE_VIEW);
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
}
