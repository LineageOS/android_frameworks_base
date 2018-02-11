/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.util.Log;
import android.util.DisplayMetrics;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BatteryController;

import java.text.NumberFormat;

public class BatteryPercentView extends AbstractBatteryView {
    public static final String TAG = BatteryPercentView.class.getSimpleName();

    private int mPercentOffsetY;
    private int mHeight;
    private int mWidth;

    public BatteryPercentView(Context context) {
        this(context, null, 0);
    }

    public BatteryPercentView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryPercentView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        loadDimens();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mWidth = mTextWidth;
        mHeight = getMeasuredHeight();
        setMeasuredDimension(mWidth, mHeight);
    }

    @Override
    public void draw(Canvas c) {
        final int level = mLevel;
        if (level == -1) return;

        updatePercentFontSize();
        mTextPaint.setColor(getCurrentColor(level));

        float textHeight = 0f;
        float textOffset = 0f;
        RectF bounds = null;
        String percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);
        textHeight = mTextPaint.descent() - mTextPaint.ascent();
        textOffset = (textHeight / 2) - mTextPaint.descent() + mPercentOffsetY;
        bounds = new RectF(0, 0, mWidth, mHeight);

        if (percentage != null) {
            c.drawText(percentage, mWidth, bounds.centerY() + textOffset, mTextPaint);
        }
    }

    @Override
    public void applyStyle() {
        final int level = mLevel;
        mTextSize = getResources().getDimensionPixelSize(level == 100 ?
                R.dimen.omni_battery_level_text_size_small : R.dimen.omni_battery_level_text_size);
        mTextPaint.setTextSize(mTextSize);
        Typeface font = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        mTextPaint.setTypeface(font);
        mTextPaint.setTextAlign(Paint.Align.RIGHT);
        Rect bounds = new Rect();
        String text = level == 100 ? "100%" : ".00%";
        mTextPaint.getTextBounds(text, 0, text.length(), bounds);
        mTextWidth = bounds.width();
    }

    private void updatePercentFontSize() {
        updateExtraPercentFontSize();
    }

    @Override
    public void loadDimens() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mPercentOffsetY = (int) (0.8 * metrics.density + 0.5f);
    }
}
