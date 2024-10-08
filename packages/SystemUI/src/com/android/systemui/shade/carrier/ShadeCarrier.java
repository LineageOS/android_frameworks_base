/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade.carrier;

import android.annotation.StyleRes;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.Utils;
import com.android.settingslib.graph.SignalDrawable;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernShadeCarrierGroupMobileView;
import com.android.systemui.util.LargeScreenUtils;

import java.util.Objects;

public class ShadeCarrier extends LinearLayout {

    private View mMobileGroup;
    private TextView mCarrierText;
    private ImageView mMobileSignal;
    private ImageView mMobileRoaming;
    private ModernShadeCarrierGroupMobileView mModernMobileView;
    private View mSpacer;
    @Nullable
    private CellSignalState mLastSignalState;
    private boolean mMobileSignalInitialized = false;
    private boolean mIsSingleCarrier;

    public ShadeCarrier(Context context) {
        super(context);
    }

    public ShadeCarrier(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ShadeCarrier(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ShadeCarrier(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMobileGroup = findViewById(R.id.mobile_combo);
        mMobileRoaming = findViewById(R.id.mobile_roaming);
        mMobileSignal = findViewById(R.id.mobile_signal);
        mCarrierText = findViewById(R.id.shade_carrier_text);
        mSpacer = findViewById(R.id.spacer);
        updateResources();
    }

    /** Removes a ModernStatusBarMobileView from the ViewGroup. */
    public void removeModernMobileView() {
        if (mModernMobileView != null) {
            removeView(mModernMobileView);
            mModernMobileView = null;
        }
    }

    /** Adds a ModernStatusBarMobileView to the ViewGroup. */
    public void addModernMobileView(ModernShadeCarrierGroupMobileView mobileView) {
        mModernMobileView = mobileView;
        mMobileGroup.setVisibility(View.GONE);
        mSpacer.setVisibility(View.GONE);
        mCarrierText.setVisibility(View.GONE);
        addView(mobileView);
    }

    /**
     * Update the state of this view
     * @param state the current state of the signal for this view
     * @param isSingleCarrier whether there is a single carrier being shown in the container
     * @return true if the state was actually changed
     */
    public boolean updateState(CellSignalState state, boolean isSingleCarrier) {
        if (Objects.equals(state, mLastSignalState) && isSingleCarrier == mIsSingleCarrier) {
            return false;
        }
        mLastSignalState = state;
        mIsSingleCarrier = isSingleCarrier;
        final boolean visible = state.visible && !isSingleCarrier;
        mMobileGroup.setVisibility(visible ? View.VISIBLE : View.GONE);
        mSpacer.setVisibility(isSingleCarrier ? View.VISIBLE : View.GONE);
        if (visible) {
            mMobileRoaming.setVisibility(state.roaming ? View.VISIBLE : View.GONE);
            ColorStateList colorStateList = Utils.getColorAttr(mContext,
                    android.R.attr.textColorPrimary);
            mMobileRoaming.setImageTintList(colorStateList);
            mMobileSignal.setImageTintList(colorStateList);

            if (!mMobileSignalInitialized) {
                mMobileSignalInitialized = true;
                mMobileSignal.setImageDrawable(new SignalDrawable(mContext));
            }
            mMobileSignal.setImageLevel(state.mobileSignalIconId);
            StringBuilder contentDescription = new StringBuilder();
            if (state.contentDescription != null) {
                contentDescription.append(state.contentDescription).append(", ");
            }
            if (state.roaming) {
                contentDescription
                        .append(mContext.getString(R.string.data_connection_roaming))
                        .append(", ");
            }
            // TODO: show mobile data off/no internet text for 5 seconds before carrier text
            if (hasValidTypeContentDescription(state.typeContentDescription)) {
                contentDescription.append(state.typeContentDescription);
            }
            mMobileSignal.setContentDescription(contentDescription);
        }
        return true;
    }

    private boolean hasValidTypeContentDescription(@Nullable String typeContentDescription) {
        return TextUtils.equals(typeContentDescription,
                mContext.getString(R.string.data_connection_no_internet))
                || TextUtils.equals(typeContentDescription,
                mContext.getString(
                        com.android.settingslib.R.string.cell_data_off_content_description))
                || TextUtils.equals(typeContentDescription,
                mContext.getString(
                        com.android.settingslib.R.string.not_default_data_content_description));
    }

    public void updateColors(ColorStateList colorStateList) {
        final boolean visible = !mIsSingleCarrier;
        if (visible) {
            mMobileRoaming.setImageTintList(colorStateList);
            mMobileSignal.setImageTintList(colorStateList);
        }
    }

    @VisibleForTesting
    View getRSSIView() {
        return mMobileGroup;
    }

    public void setCarrierText(CharSequence text) {
        mCarrierText.setText(text);
    }

    public void updateTextAppearance(@StyleRes int resId) {
        mCarrierText.setTextAppearance(resId);
        if (mModernMobileView != null) {
            mModernMobileView.updateTextAppearance(resId);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    private void updateResources() {
        boolean useLargeScreenHeader =
                LargeScreenUtils.shouldUseLargeScreenShadeHeader(getResources());
        mCarrierText.setMaxEms(
                useLargeScreenHeader
                        ? Integer.MAX_VALUE
                        : getResources().getInteger(R.integer.shade_carrier_max_em)
        );
    }
}
