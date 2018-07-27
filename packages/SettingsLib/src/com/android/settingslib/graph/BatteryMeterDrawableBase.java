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

package com.android.settingslib.graph;

import android.animation.ArgbEvaluator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;

import com.android.settingslib.R;
import com.android.settingslib.Utils;

import org.lineageos.internal.graphics.drawable.StopMotionVectorDrawable;

public class BatteryMeterDrawableBase extends Drawable {

    private static final float ASPECT_RATIO = .58f;
    public static final String TAG = BatteryMeterDrawableBase.class.getSimpleName();
    private static final float RADIUS_RATIO = 1.0f / 17f;

    // Values for the different battery styles
    public static final int BATTERY_STYLE_PORTRAIT = 0;
    public static final int BATTERY_STYLE_LANDSCAPE = 1;
    public static final int BATTERY_STYLE_CIRCLE = 2;
    public static final int BATTERY_STYLE_TEXT = 3;

    protected final Context mContext;
    private Paint mClearPaint;
    private Paint mTextAndBoltPaint;
    protected Paint mWarningTextPaint;

    private int mLevel = -1;
    private boolean mCharging;
    private boolean mPowerSaveEnabled;
    private boolean mShowPercent;

    private static final boolean SINGLE_DIGIT_PERCENT = false;

    private final int[] mColors;
    private final int mIntrinsicWidth;
    private final int mIntrinsicHeight;

    private int mIconTint = Color.WHITE;
    private float mOldDarkIntensity = -1f;

    private int mHeight;
    private int mWidth;
    private String mWarningString;
    private final int mCriticalLevel;
    private int mChargeColor;

    private final Rect mPadding = new Rect();

    private float mTextX, mTextY; // precalculated position for drawText() to appear centered

    private boolean mInitialized;

    private LayerDrawable mBatteryDrawable;
    private Drawable mFrameDrawable;
    private StopMotionVectorDrawable mLevelDrawable;
    private Drawable mBoltDrawable;

    private int mTextGravity;

    private int mCurrentBackgroundColor = 0;
    private int mCurrentFillColor = 0;

    public BatteryMeterDrawableBase(Context context, int frameColor) {
        // Portrait is the default drawable style
        this(context, frameColor, BATTERY_STYLE_PORTRAIT);
    }

    public BatteryMeterDrawableBase(Context context, int frameColor, int style) {
        mContext = context;
        final Resources res = context.getResources();
        TypedArray levels = res.obtainTypedArray(R.array.batterymeter_color_levels);
        TypedArray colors = res.obtainTypedArray(R.array.batterymeter_color_values);

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

        mWarningString = context.getString(R.string.battery_meter_very_low_overlay_symbol);
        mCriticalLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);

        loadBatteryDrawables(res, style);

        // Load text gravity and blend mode
        final int[] attrs = new int[] { android.R.attr.gravity, R.attr.blendMode };
        final int resId = getBatteryDrawableStyleResourceForStyle(style);
        PorterDuff.Mode xferMode = PorterDuff.Mode.XOR;
        if (resId != 0) {
            TypedArray a = mContext.obtainStyledAttributes(resId, attrs);
            mTextGravity = a.getInt(0, Gravity.CENTER);
            xferMode = PorterDuff.intToMode(a.getInt(1, PorterDuff.modeToInt(PorterDuff.Mode.XOR)));
            a.recycle();
        } else {
            mTextGravity = Gravity.CENTER;
        }

        mTextAndBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        mTextAndBoltPaint.setTypeface(font);
        mTextAndBoltPaint.setTextAlign(getPaintAlignmentFromGravity(mTextGravity));
        mTextAndBoltPaint.setXfermode(new PorterDuffXfermode(xferMode));
        mTextAndBoltPaint.setColor(Utils.getDefaultColor(mContext, R.color.batterymeter_bolt_color));

        mWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        font = Typeface.create("sans-serif", Typeface.BOLD);
        mWarningTextPaint.setTypeface(font);
        mWarningTextPaint.setTextAlign(getPaintAlignmentFromGravity(mTextGravity));

        mClearPaint = new Paint();
        mClearPaint.setColor(0);

        mIntrinsicWidth = context.getResources().getDimensionPixelSize(R.dimen.battery_width);
        mIntrinsicHeight = context.getResources().getDimensionPixelSize(R.dimen.battery_height);
    }

    @Override
    public int getIntrinsicHeight() {
        return mIntrinsicHeight;
    }

    @Override
    public int getIntrinsicWidth() {
        return mIntrinsicWidth;
    }

    public void setShowPercent(boolean show) {
        mShowPercent = show;
        postInvalidate();
    }

    public void setCharging(boolean val) {
        mCharging = val;
        postInvalidate();
    }

    public boolean getCharging() {
        return mCharging;
    }

    public void setBatteryLevel(int val) {
        mLevel = val;
        postInvalidate();
    }

    public int getBatteryLevel() {
        return mLevel;
    }

    public void setPowerSave(boolean val) {
        mPowerSaveEnabled = val;
        postInvalidate();
    }

    // an approximation of View.postInvalidate()
    protected void postInvalidate() {
        unscheduleSelf(this::invalidateSelf);
        scheduleSelf(this::invalidateSelf, 0);
    }

    private static float[] loadPoints(Resources res, int pointArrayRes) {
        final int[] pts = res.getIntArray(pointArrayRes);
        int maxX = 0, maxY = 0;
        for (int i = 0; i < pts.length; i += 2) {
            maxX = Math.max(maxX, pts[i]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        final float[] ptsF = new float[pts.length];
        for (int i = 0; i < pts.length; i += 2) {
            ptsF[i] = (float) pts[i] / maxX;
            ptsF[i + 1] = (float) pts[i + 1] / maxY;
        }
        return ptsF;
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        updateSize();
    }

    private void updateSize() {
        final Rect bounds = getBounds();

        mHeight = (bounds.bottom - mPadding.bottom) - (bounds.top + mPadding.top);
        mWidth = (bounds.right - mPadding.right) - (bounds.left + mPadding.left);
        mWarningTextPaint.setTextSize(mHeight * 0.75f);
    }

    @Override
    public boolean getPadding(Rect padding) {
        if (mPadding.left == 0
            && mPadding.top == 0
            && mPadding.right == 0
            && mPadding.bottom == 0) {
            return super.getPadding(padding);
        }

        padding.set(mPadding);
        return true;
    }

    public void setPadding(int left, int top, int right, int bottom) {
        mPadding.left = left;
        mPadding.top = top;
        mPadding.right = right;
        mPadding.bottom = bottom;

        updateSize();
    }

    private int getColorForLevel(int percent) {
        // If we are in power save mode, always use the normal color.
        if (mPowerSaveEnabled) {
            return mIconTint;
        }
        int thresh, color = 0;
        for (int i = 0; i < mColors.length; i += 2) {
            thresh = mColors[i];
            color = mColors[i + 1];
            if (percent <= thresh) {

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

    public void setColors(int fillColor, int backgroundColor) {
        mIconTint = fillColor;

        // Make bolt fully opaque for increased visibility
        mBoltDrawable.setTint(0xff000000 | fillColor);
        mFrameDrawable.setTint(backgroundColor);
        updateBoltDrawableLayer(mBatteryDrawable, mBoltDrawable);

        mChargeColor = fillColor;
        invalidateSelf();
    }

    protected int batteryColorForLevel(int level) {
        return mCharging ? mChargeColor : getColorForLevel(level);
    }

    @Override
    public void draw(Canvas c) {
        if (!mInitialized) {
            init();
        }

        drawBattery(c);
    }

    // Some stuff required by Drawable.
    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mWarningTextPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return 0;
    }

    public int getCriticalLevel() {
        return mCriticalLevel;
    }

    protected float getAspectRatio() {
        return ASPECT_RATIO;
    }

    protected float getRadiusRatio() {
        return RADIUS_RATIO;
    }

    private void loadBatteryDrawables(Resources res, int style) {
        final int drawableResId = getBatteryDrawableResourceForStyle(style);
        mBatteryDrawable = (LayerDrawable) mContext.getDrawable(drawableResId);
        mFrameDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_frame);
        mFrameDrawable.setTint(mCurrentBackgroundColor != 0
                ? mCurrentBackgroundColor : res.getColor(R.color.batterymeter_frame_color));
        // Set the animated vector drawable we will be stop-animating
        final Drawable levelDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_fill);
        mLevelDrawable = new StopMotionVectorDrawable(levelDrawable);
        mBoltDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_charge_indicator);
    }

    private int getBatteryDrawableResourceForStyle(final int style) {
        switch (style) {
            case BATTERY_STYLE_CIRCLE:
                return R.drawable.ic_battery_circle;
            case BATTERY_STYLE_LANDSCAPE:
                return R.drawable.ic_battery_landscape;
            case BATTERY_STYLE_PORTRAIT:
                return R.drawable.ic_battery_portrait;
            default:
                return 0;
        }
    }

    private int getBatteryDrawableStyleResourceForStyle(final int style) {
        switch (style) {
            case BATTERY_STYLE_CIRCLE:
                return R.style.BatteryMeterViewDrawable_Circle;
            case BATTERY_STYLE_LANDSCAPE:
                return R.style.BatteryMeterViewDrawable_Landscape;
            case BATTERY_STYLE_PORTRAIT:
                return R.style.BatteryMeterViewDrawable_Portrait;
            default:
                return R.style.BatteryMeterViewDrawable;
        }
    }

    /**
     * Initializes all size dependent variables
     */
    private void init() {
        // Not much we can do with zero width or height, we'll get another pass later
        if (mWidth <= 0 || mHeight <= 0) return;

        final float widthDiv2 = mWidth / 2f;
        // text size is width / 2 - 2dp for wiggle room
        final float textSize = widthDiv2 - mContext.getResources().getDisplayMetrics().density * 2;
        mTextAndBoltPaint.setTextSize(textSize);
        mWarningTextPaint.setTextSize(textSize);

        Rect iconBounds = new Rect(0, 0, mWidth, mHeight);
        mBatteryDrawable.setBounds(iconBounds);

        // Calculate text position
        Rect bounds = new Rect();
        mTextAndBoltPaint.getTextBounds("99", 0, "99".length(), bounds);
        final boolean isRtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        // Compute mTextX based on text gravity
        if ((mTextGravity & Gravity.START) == Gravity.START) {
            mTextX = isRtl ? mWidth : 0;
        } else if ((mTextGravity & Gravity.END) == Gravity.END) {
            mTextX = isRtl ? 0 : mWidth;
        } else if ((mTextGravity & Gravity.LEFT) == Gravity.LEFT) {
            mTextX = 0;
        } else if ((mTextGravity & Gravity.RIGHT) == Gravity.RIGHT) {
            mTextX = mWidth;
        } else {
            mTextX = widthDiv2;
        }

        // Compute mTextY based on text gravity
        if ((mTextGravity & Gravity.TOP) == Gravity.TOP) {
            mTextY = bounds.height();
        } else if ((mTextGravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
            mTextY = mHeight;
        } else {
            mTextY = widthDiv2 + bounds.height() / 2.0f;
        }

        updateBoltDrawableLayer(mBatteryDrawable, mBoltDrawable);

        mInitialized = true;
    }

    // Creates a BitmapDrawable of the bolt so we can make use of
    // the XOR xfer mode with vector-based drawables
    private void updateBoltDrawableLayer(LayerDrawable batteryDrawable, Drawable boltDrawable) {
        BitmapDrawable newBoltDrawable;
        if (boltDrawable instanceof BitmapDrawable) {
            newBoltDrawable = (BitmapDrawable) boltDrawable.mutate();
        } else {
            Bitmap boltBitmap = createBoltBitmap(boltDrawable);
            if (boltBitmap == null) {
                // Not much to do with a null bitmap so keep original bolt for now
                return;
            }
            Rect bounds = boltDrawable.getBounds();
            newBoltDrawable = new BitmapDrawable(mContext.getResources(), boltBitmap);
            newBoltDrawable.setBounds(bounds);
        }
        newBoltDrawable.getPaint().set(mTextAndBoltPaint);
        batteryDrawable.setDrawableByLayerId(R.id.battery_charge_indicator, newBoltDrawable);
    }

    private Bitmap createBoltBitmap(Drawable boltDrawable) {
        // Not much we can do with zero width or height, we'll get another pass later
        if (mWidth <= 0 || mHeight <= 0) return null;

        Bitmap bolt;
        if (!(boltDrawable instanceof BitmapDrawable)) {
            Rect iconBounds = new Rect(0, 0, mWidth, mHeight);
            bolt = Bitmap.createBitmap(iconBounds.width(), iconBounds.height(),
                    Bitmap.Config.ARGB_8888);
            if (bolt != null) {
                Canvas c = new Canvas(bolt);
                c.drawColor(-1, PorterDuff.Mode.CLEAR);
                boltDrawable.draw(c);
            }
        } else {
            bolt = ((BitmapDrawable) boltDrawable).getBitmap();
        }

        return bolt;
    }

    private void drawBattery(Canvas canvas) {
        final int level = mLevel;

        mTextAndBoltPaint.setColor(getColorForLevel(level));

        // Make sure we don't draw the charge indicator if not plugged in
        final Drawable d = mBatteryDrawable.findDrawableByLayerId(R.id.battery_charge_indicator);
        if (d instanceof BitmapDrawable) {
            // In case we are using a BitmapDrawable, which we should be unless something bad
            // happened, we need to change the paint rather than the alpha in case the blendMode
            // has been set to clear.  Clear always clears regardless of alpha level ;)
            final BitmapDrawable bd = (BitmapDrawable) d;
            bd.getPaint().set(mCharging ? mTextAndBoltPaint : mClearPaint);
        } else {
            d.setAlpha(mCharging ? 255 : 0);
        }

        // Now draw the level indicator
        // Set the level and tint color of the fill drawable
        mLevelDrawable.setCurrentFraction(level / 100f);
        mLevelDrawable.setTint(getColorForLevel(level));
        mBatteryDrawable.draw(canvas);

        // If chosen by options, draw percentage text in the middle
        // Always skip percentage when 100, so layout doesnt break
        if (!mCharging) {
            drawPercentageText(canvas);
        }
    }

    private void drawPercentageText(Canvas canvas) {
        final int level = mLevel;
        if ((level > mCriticalLevel || mPowerSaveEnabled) && mShowPercent && level != 100) {
            // Draw the percentage text
            String pctText = String.valueOf(SINGLE_DIGIT_PERCENT ? (level / 10) : level);
            mTextAndBoltPaint.setColor(getColorForLevel(level));
            canvas.drawText(pctText, mTextX, mTextY, mTextAndBoltPaint);
        } else if (level <= mCriticalLevel) {
            // Draw the warning text
            mWarningTextPaint.setColor(mPowerSaveEnabled
                    ? mColors[mColors.length - 1]
                    : mColors[1]);
            canvas.drawText(mWarningString, mTextX, mTextY, mWarningTextPaint);
        }
    }

    private Paint.Align getPaintAlignmentFromGravity(int gravity) {
        final boolean isRtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if ((gravity & Gravity.START) == Gravity.START) {
            return isRtl ? Paint.Align.RIGHT : Paint.Align.LEFT;
        }
        if ((gravity & Gravity.END) == Gravity.END) {
            return isRtl ? Paint.Align.LEFT : Paint.Align.RIGHT;
        }
        if ((gravity & Gravity.LEFT) == Gravity.LEFT) return Paint.Align.LEFT;
        if ((gravity & Gravity.RIGHT) == Gravity.RIGHT) return Paint.Align.RIGHT;

        // Default to center
        return Paint.Align.CENTER;
    }
}
