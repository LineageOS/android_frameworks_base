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

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.widget.RelativeLayout;

public class NetworkTrafficLayout extends RelativeLayout {
    private static final String TAG = "NetworkTrafficLayout";

    private boolean mStatusBarIsVisible;
    private NetworkTraffic mNetworkTraffic;

    public NetworkTrafficLayout(Context context) {
        this(context, null);
    }

    public NetworkTrafficLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkTrafficLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mStatusBarIsVisible = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.d(TAG, "in onAttachedToWindow()");
        mNetworkTraffic = (NetworkTraffic) findViewById(com.android.systemui.R.id.network_traffic);
        setOnSystemUiVisibilityChangeListener(mSystemUiVisibilityChangeListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Log.d(TAG, "in onDetachedFromWindow()");
        setOnSystemUiVisibilityChangeListener(null);
        setStatusBarVisibility(getSystemUiVisibility());
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        Log.d(TAG, "in onVisibilityAggregated, isVisible = " + isVisible);
        super.onVisibilityAggregated(isVisible);
        if (mStatusBarIsVisible != isVisible) {
            mStatusBarIsVisible = isVisible;
            updateNetworkTraffic();
        }
    }

    private View.OnSystemUiVisibilityChangeListener mSystemUiVisibilityChangeListener =
            new View.OnSystemUiVisibilityChangeListener() {
        @Override
        public void onSystemUiVisibilityChange(int visibility) {
            Log.d(TAG, "in onSystemUiVisibilityChange, visibility = " + visibility);
            setStatusBarVisibility(visibility);
        }
    };

    private void setStatusBarVisibility(int visibility) {
        final boolean isVisible =
                (visibility & SYSTEM_UI_FLAG_FULLSCREEN) == 0
                || (visibility & SYSTEM_UI_FLAG_LOW_PROFILE) != 0;
        if (mStatusBarIsVisible != isVisible) {
            mStatusBarIsVisible = isVisible;
            updateNetworkTraffic();
        }
    }

    private void updateNetworkTraffic() {
        mNetworkTraffic.setStatusBarVisibility(mStatusBarIsVisible);
    }
}
