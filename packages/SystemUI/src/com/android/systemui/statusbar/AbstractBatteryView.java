/*
 *  Copyright (C) 2015-2018 The OmniROM Project
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

package com.android.systemui.statusbar;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import com.android.settingslib.Utils;

import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.IconLogger;

import java.text.NumberFormat;

public class AbstractBatteryView extends View implements IBatteryView,
        BatteryController.BatteryStateChangeCallback,
        DarkIconDispatcher.DarkReceiver {
    public static final String TAG = AbstractBatteryView.class.getSimpleName();

    protected BatteryController mBatteryController;
    protected boolean mPowerSaveEnabled;
    protected boolean mShowPercent;
    protected boolean mPercentInside;
    protected final int mCriticalLevel;
    protected int mFrameColor;
    protected int mChargeColor;
    protected final float[] mBoltPoints;
    protected boolean mChargingImage;
    protected int mDarkModeBackgroundColor;
    protected int mDarkModeFillColor;
    protected int mLightModeBackgroundColor;
    protected int mLightModeFillColor;
    protected final Paint mBoltPaint;
    protected final Paint mTextPaint;
    protected int mTextSize;
    protected boolean mChargeColorEnable;
    protected int mTextWidth;
    protected float mDarkIntensity;
    protected int mLevel;
    protected boolean mCharging;
    protected boolean mPlugged;
    private final int[] mColors;
    private int mIconTint = Color.WHITE;
    private TextView mBatteryPercentView;
    private int mTextColor;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBatteryController = Dependency.get(BatteryController.class);
        mBatteryController.addCallback(this);
        mPowerSaveEnabled = mBatteryController.isPowerSave();
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mBatteryController.removeCallback(this);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
    }

    public AbstractBatteryView(Context context) {
        this(context, null, 0);
    }

    public AbstractBatteryView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AbstractBatteryView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources res = context.getResources();
        TypedArray levels = res.obtainTypedArray(com.android.settingslib.R.array.batterymeter_color_levels);
        TypedArray colors = res.obtainTypedArray(com.android.settingslib.R.array.batterymeter_color_values);

        final int N = levels.length();
        mColors = new int[2 * N];
        for (int i=0; i < N; i++) {
            mColors[2 * i] = levels.getInt(i, 0);
            if (colors.getType(i) == TypedValue.TYPE_ATTRIBUTE) {
                mColors[2 * i + 1] = Utils.getColorAttr(context, colors.getThemeAttributeId(i, 0));
            } else {
                mColors[2 * i + 1] = colors.getColor(i, 0);
            }
        }
        levels.recycle();
        colors.recycle();

        mFrameColor = getResources().getColor(R.color.meter_background_color);
        mCriticalLevel = getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        mChargeColor = mFrameColor;
        mBoltPoints = loadBoltPoints();
        mBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBoltPaint.setColor(mFrameColor);

        Context dualToneDarkTheme = new ContextThemeWrapper(context,
                Utils.getThemeAttr(context, R.attr.darkIconTheme));
        Context dualToneLightTheme = new ContextThemeWrapper(context,
                Utils.getThemeAttr(context, R.attr.lightIconTheme));
        mDarkModeBackgroundColor = Utils.getColorAttr(dualToneDarkTheme, R.attr.backgroundColor);
        mDarkModeFillColor = Utils.getColorAttr(dualToneDarkTheme, R.attr.fillColor);
        mLightModeBackgroundColor = Utils.getColorAttr(dualToneLightTheme, R.attr.backgroundColor);
        mLightModeFillColor = Utils.getColorAttr(dualToneLightTheme, R.attr.fillColor);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface font = Typeface.create("sans-serif-medium", Typeface.BOLD);
        mTextPaint.setTypeface(font);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextSize = getResources().getDimensionPixelSize(R.dimen.omni_battery_level_text_size);
        mTextPaint.setTextSize(mTextSize);
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mLevel = level;
        mCharging = charging;
        mPlugged = pluggedIn;
        updatePercentText();
        postInvalidate();
    }

    @Override
    public void setPercentInside(boolean percentInside) {
        mShowPercent = percentInside;
        mPercentInside = percentInside;
    }

    @Override
    public void setChargingImage(boolean chargingImage) {
        mChargingImage = chargingImage;
    }

    @Override
    public void setChargingColor(int chargingColor) {
        mChargeColor = chargingColor;
    }

    @Override
    public void setChargingColorEnable(boolean value) {
        mChargeColorEnable = value;
    }

    protected boolean isWideDisplay() {
        return mShowPercent && !mPercentInside;
    }

    protected boolean showChargingImage() {
        return mCharging && mChargingImage;
    }

    protected int getCurrentColor(int level) {
        if (mCharging && mChargeColorEnable) {
            return mChargeColor;
        }
        return getColorForLevel(level);
    }

    private int getColorForLevel(int level) {
        // If we are in power save mode, always use the normal color.
        if (mPowerSaveEnabled) {
            return mIconTint;
        }
        int thresh, color = 0;
        for (int i = 0; i < mColors.length; i += 2) {
            thresh = mColors[i];
            color = mColors[i + 1];
            if (level <= thresh) {

                // Respect tinting for "normal" level
                if (i == mColors.length - 2) {
                    return mIconTint;
                } else {
                    return color;
                }
            }
        }
        return color;
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mPowerSaveEnabled = mBatteryController.isPowerSave();
        postInvalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    protected float[] loadBoltPoints() {
        final int[] pts = getResources().getIntArray(com.android.settingslib.R.array.batterymeter_bolt_points);
        int maxX = 0, maxY = 0;
        for (int i = 0; i < pts.length; i += 2) {
            maxX = Math.max(maxX, pts[i]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        final float[] ptsF = new float[pts.length];
        for (int i = 0; i < pts.length; i += 2) {
            ptsF[i] = (float)pts[i] / maxX;
            ptsF[i + 1] = (float)pts[i + 1] / maxY;
        }
        return ptsF;
    }

    @Override
    public void applyStyle() {
    }

    protected int getColorForDarkIntensity(float darkIntensity, int lightColor, int darkColor) {
        return (int) ArgbEvaluator.getInstance().evaluate(darkIntensity, lightColor, darkColor);
    }

    protected void updateExtraPercentFontSize() {
        final int level = mLevel;
        mTextSize = getResources().getDimensionPixelSize(level == 100 ?
                R.dimen.omni_battery_level_text_size_small : R.dimen.omni_battery_level_text_size);
        mTextPaint.setTextSize(mTextSize);
        Rect bounds = new Rect();
        String text = level == 100 ? "100%" : ".00%";
        mTextPaint.getTextBounds(text, 0, text.length(), bounds);
        mTextWidth = bounds.width();
        requestLayout();
    }

    @Override
    public void loadDimens() {
        if (mBatteryPercentView != null) {
            FontSizeUtils.updateFontSize(mBatteryPercentView, R.dimen.qs_time_expanded_size);
        }
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mDarkIntensity = darkIntensity;
        float intensity = DarkIconDispatcher.isInArea(area, this) ? darkIntensity : 0;
        int foreground = getColorForDarkIntensity(intensity, mLightModeFillColor,
                mDarkModeFillColor);
        int background = getColorForDarkIntensity(intensity, mLightModeBackgroundColor,
                mDarkModeBackgroundColor);
        mFrameColor = background;
        mChargeColor = foreground;
        mIconTint = foreground;
        mBoltPaint.setColor(foreground);
        setTextColor(foreground);
        postInvalidate();
    }

    public void setFillColor(int color) {
        if (mLightModeFillColor == color) {
            return;
        }
        mLightModeFillColor = color;
        onDarkChanged(new Rect(), mDarkIntensity, DarkIconDispatcher.DEFAULT_ICON_TINT);
    }

    public void setPercentTextView(TextView percentTextView) {
        mBatteryPercentView = percentTextView;
        setTextColor(mTextColor);
        updatePercentText();
    }

    private void updatePercentText() {
        if (mBatteryPercentView != null) {
            mBatteryPercentView.setText(
                    NumberFormat.getPercentInstance().format(mLevel / 100f));
        }
    }

    private void setTextColor(int color) {
        mTextColor = color;
        if (mBatteryPercentView != null) {
            mBatteryPercentView.setTextColor(color);
        }
    }
}
