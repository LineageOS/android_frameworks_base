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
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Path.FillType;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
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

    private static final int IGNORE_BATTERY_STYLES = -1;

    protected final Context mContext;
    protected final Paint mFramePaint;
    protected final Paint mBatteryPaint;
    private final Paint mClearPaint;
    protected final Paint mWarningTextPaint;
    protected Paint mTextPaint;
    protected Paint mTextAndBoltPaint;
    protected final Paint mBoltPaint;
    protected final Paint mPlusPaint;
    protected float mButtonHeightFraction;
    private float mTextX, mTextY; // precalculated position for drawText() to appear centered

    private LayerDrawable mBatteryDrawable;
    private Drawable mBoltDrawable;
    private Drawable mFrameDrawable;
    private StopMotionVectorDrawable mLevelDrawable;

    private int mCurrentBackgroundColor = 0;
    private int mLevel = -1;
    private int mTextGravity;

    private boolean mCharging;
    private boolean mInitialized;
    private boolean mPowerSaveEnabled;
    private boolean mShowPercent;
    private boolean mUseVectorDrawables;

    private static final boolean SINGLE_DIGIT_PERCENT = false;

    private static final int FULL = 96;

    private static final float BOLT_LEVEL_THRESHOLD = 0.3f;  // opaque bolt below this fraction

    private final int[] mColors;
    private final int mIntrinsicWidth;
    private final int mIntrinsicHeight;

    private float mSubpixelSmoothingLeft;
    private float mSubpixelSmoothingRight;
    private float mTextHeight, mWarningTextHeight;
    private int mIconTint = Color.WHITE;
    private float mOldDarkIntensity = -1f;

    private int mHeight;
    private int mWidth;
    private String mWarningString;
    private final int mCriticalLevel;
    private int mChargeColor;
    private final float[] mBoltPoints;
    private final Path mBoltPath = new Path();
    private final float[] mPlusPoints;
    private final Path mPlusPath = new Path();

    private final Rect mPadding = new Rect();
    private final RectF mFrame = new RectF();
    private final RectF mButtonFrame = new RectF();
    private final RectF mBoltFrame = new RectF();
    private final RectF mPlusFrame = new RectF();

    private final Path mShapePath = new Path();
    private final Path mClipPath = new Path();
    private final Path mTextPath = new Path();

    public BatteryMeterDrawableBase(Context context, int frameColor) {
        // Ignore custom battery styles in all cases except for status bar icon
        this(context, frameColor, IGNORE_BATTERY_STYLES);
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
        mButtonHeightFraction = context.getResources().getFraction(
                R.fraction.battery_button_height_fraction, 1, 1);
        mSubpixelSmoothingLeft = context.getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_left, 1, 1);
        mSubpixelSmoothingRight = context.getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_right, 1, 1);

        mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFramePaint.setColor(frameColor);
        mFramePaint.setDither(true);
        mFramePaint.setStrokeWidth(0);
        mFramePaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBatteryPaint.setDither(true);
        mBatteryPaint.setStrokeWidth(0);
        mBatteryPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        if (style == IGNORE_BATTERY_STYLES) {
            mUseVectorDrawables = false;

            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            mTextPaint.setTypeface(font);
            mTextPaint.setTextAlign(Paint.Align.CENTER);

            mWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            font = Typeface.create("sans-serif", Typeface.BOLD);
            mWarningTextPaint.setTypeface(font);
            mWarningTextPaint.setTextAlign(Paint.Align.CENTER);
            if (mColors.length > 1) {
                mWarningTextPaint.setColor(mColors[1]);
            }
        } else {
            mUseVectorDrawables = true;

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
            mTextAndBoltPaint.setColor(Utils.getDefaultColor(mContext,
                    R.color.batterymeter_bolt_color));

            mWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            font = Typeface.create("sans-serif", Typeface.BOLD);
            mWarningTextPaint.setTypeface(font);
            mWarningTextPaint.setTextAlign(getPaintAlignmentFromGravity(mTextGravity));
        }

        mChargeColor = Utils.getDefaultColor(mContext, R.color.meter_consumed_color);

        mClearPaint = new Paint();
        mClearPaint.setColor(0);

        mBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBoltPaint.setColor(Utils.getDefaultColor(mContext, R.color.batterymeter_bolt_color));
        mBoltPoints = loadPoints(res, R.array.batterymeter_bolt_points);

        mPlusPaint = new Paint(mBoltPaint);
        mPlusPoints = loadPoints(res, R.array.batterymeter_plus_points);

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
        mWarningTextHeight = -mWarningTextPaint.getFontMetrics().ascent;
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
        mCurrentBackgroundColor = backgroundColor;
        if (mUseVectorDrawables) {
            // Make bolt fully opaque for increased visibility
            mBoltDrawable.setTint(0xff000000 | fillColor);
            mFrameDrawable.setTint(backgroundColor);
            updateBoltDrawableLayer(mBatteryDrawable, mBoltDrawable);
        }
        mFramePaint.setColor(backgroundColor);
        mBoltPaint.setColor(fillColor);
        mPlusPaint.setColor(fillColor);
        mChargeColor = fillColor;
        invalidateSelf();
    }

    protected int batteryColorForLevel(int level) {
        return mCharging ? mChargeColor : getColorForLevel(level);
    }

    @Override
    public void draw(Canvas c) {
        if (!mUseVectorDrawables) {
            drawBattery(c);
        } else {
            drawBatteryWithVectorDrawables(c);
        }
    }

    private void drawBattery(Canvas c) {
        final int level = mLevel;
        final Rect bounds = getBounds();

        if (level == -1) return;

        float drawFrac = (float) level / 100f;
        final int height = mHeight;
        final int width = (int) (getAspectRatio() * mHeight);
        final int px = (mWidth - width) / 2;
        final int buttonHeight = Math.round(height * mButtonHeightFraction);
        final int left = mPadding.left + bounds.left;
        final int top = bounds.bottom - mPadding.bottom - height;

        mFrame.set(left, top, width + left, height + top);
        mFrame.offset(px, 0);

        // button-frame: area above the battery body
        mButtonFrame.set(
                mFrame.left + Math.round(width * 0.28f),
                mFrame.top,
                mFrame.right - Math.round(width * 0.28f),
                mFrame.top + buttonHeight);

        // frame: battery body area
        mFrame.top += buttonHeight;

        // set the battery charging color
        mBatteryPaint.setColor(batteryColorForLevel(level));

        if (level >= FULL) {
            drawFrac = 1f;
        } else if (level <= mCriticalLevel) {
            drawFrac = 0f;
        }

        final float levelTop = drawFrac == 1f ? mButtonFrame.top
                : (mFrame.top + (mFrame.height() * (1f - drawFrac)));

        // define the battery shape
        mShapePath.reset();
        final float radius = getRadiusRatio() * (mFrame.height() + buttonHeight);
        mShapePath.setFillType(FillType.WINDING);
        mShapePath.addRoundRect(mFrame, radius, radius, Direction.CW);
        mShapePath.addRect(mButtonFrame, Direction.CW);

        if (mCharging) {
            // define the bolt shape
            // Shift right by 1px for maximal bolt-goodness
            final float bl = mFrame.left + mFrame.width() / 4f + 1;
            final float bt = mFrame.top + mFrame.height() / 6f;
            final float br = mFrame.right - mFrame.width() / 4f + 1;
            final float bb = mFrame.bottom - mFrame.height() / 10f;
            if (mBoltFrame.left != bl || mBoltFrame.top != bt
                    || mBoltFrame.right != br || mBoltFrame.bottom != bb) {
                mBoltFrame.set(bl, bt, br, bb);
                mBoltPath.reset();
                mBoltPath.moveTo(
                        mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                        mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
                for (int i = 2; i < mBoltPoints.length; i += 2) {
                    mBoltPath.lineTo(
                            mBoltFrame.left + mBoltPoints[i] * mBoltFrame.width(),
                            mBoltFrame.top + mBoltPoints[i + 1] * mBoltFrame.height());
                }
                mBoltPath.lineTo(
                        mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                        mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
            }

            float boltPct = (mBoltFrame.bottom - levelTop) / (mBoltFrame.bottom - mBoltFrame.top);
            boltPct = Math.min(Math.max(boltPct, 0), 1);
            if (boltPct <= BOLT_LEVEL_THRESHOLD) {
                // draw the bolt if opaque
                c.drawPath(mBoltPath, mBoltPaint);
            } else {
                // otherwise cut the bolt out of the overall shape
                mShapePath.op(mBoltPath, Path.Op.DIFFERENCE);
            }
        } else if (mPowerSaveEnabled) {
            // define the plus shape
            final float pw = mFrame.width() * 2 / 3;
            final float pl = mFrame.left + (mFrame.width() - pw) / 2;
            final float pt = mFrame.top + (mFrame.height() - pw) / 2;
            final float pr = mFrame.right - (mFrame.width() - pw) / 2;
            final float pb = mFrame.bottom - (mFrame.height() - pw) / 2;
            if (mPlusFrame.left != pl || mPlusFrame.top != pt
                    || mPlusFrame.right != pr || mPlusFrame.bottom != pb) {
                mPlusFrame.set(pl, pt, pr, pb);
                mPlusPath.reset();
                mPlusPath.moveTo(
                        mPlusFrame.left + mPlusPoints[0] * mPlusFrame.width(),
                        mPlusFrame.top + mPlusPoints[1] * mPlusFrame.height());
                for (int i = 2; i < mPlusPoints.length; i += 2) {
                    mPlusPath.lineTo(
                            mPlusFrame.left + mPlusPoints[i] * mPlusFrame.width(),
                            mPlusFrame.top + mPlusPoints[i + 1] * mPlusFrame.height());
                }
                mPlusPath.lineTo(
                        mPlusFrame.left + mPlusPoints[0] * mPlusFrame.width(),
                        mPlusFrame.top + mPlusPoints[1] * mPlusFrame.height());
            }

            float boltPct = (mPlusFrame.bottom - levelTop) / (mPlusFrame.bottom - mPlusFrame.top);
            boltPct = Math.min(Math.max(boltPct, 0), 1);
            if (boltPct <= BOLT_LEVEL_THRESHOLD) {
                // draw the bolt if opaque
                c.drawPath(mPlusPath, mPlusPaint);
            } else {
                // otherwise cut the bolt out of the overall shape
                mShapePath.op(mPlusPath, Path.Op.DIFFERENCE);
            }
        }

        // compute percentage text
        boolean pctOpaque = false;
        float pctX = 0, pctY = 0;
        String pctText = null;
        if (!mCharging && !mPowerSaveEnabled && level > mCriticalLevel && mShowPercent) {
            mTextPaint.setColor(getColorForLevel(level));
            mTextPaint.setTextSize(height *
                    (SINGLE_DIGIT_PERCENT ? 0.75f
                            : (mLevel == 100 ? 0.38f : 0.5f)));
            mTextHeight = -mTextPaint.getFontMetrics().ascent;
            pctText = String.valueOf(SINGLE_DIGIT_PERCENT ? (level / 10) : level);
            pctX = mWidth * 0.5f;
            pctY = (mHeight + mTextHeight) * 0.47f;
            pctOpaque = levelTop > pctY;
            if (!pctOpaque) {
                mTextPath.reset();
                mTextPaint.getTextPath(pctText, 0, pctText.length(), pctX, pctY, mTextPath);
                // cut the percentage text out of the overall shape
                mShapePath.op(mTextPath, Path.Op.DIFFERENCE);
            }
        }

        // draw the battery shape background
        c.drawPath(mShapePath, mFramePaint);

        // draw the battery shape, clipped to charging level
        mFrame.top = levelTop;
        mClipPath.reset();
        mClipPath.addRect(mFrame, Path.Direction.CCW);
        mShapePath.op(mClipPath, Path.Op.INTERSECT);
        c.drawPath(mShapePath, mBatteryPaint);

        if (!mCharging && !mPowerSaveEnabled) {
            if (level <= mCriticalLevel) {
                // draw the warning text
                final float x = mWidth * 0.5f;
                final float y = (mHeight + mWarningTextHeight) * 0.48f;
                c.drawText(mWarningString, x, y, mWarningTextPaint);
            } else if (pctOpaque) {
                // draw the percentage text
                c.drawText(pctText, pctX, pctY, mTextPaint);
            }
        }
    }

    private void drawBatteryWithVectorDrawables(Canvas c) {
        final int level = mLevel;

        if (!mInitialized) {
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

        mTextAndBoltPaint.setColor(getColorForLevel(level));

        // Make sure we don't draw the charge indicator if not plugged in
        final Drawable d = mBatteryDrawable.findDrawableByLayerId(R.id.battery_charge_indicator);
        if (d instanceof BitmapDrawable) {
            // In case we are using a BitmapDrawable, which we should be unless something bad
            // happened, we need to change the paint rather than the alpha in case the blendMode
            // has been set to clear. Clear always clears regardless of alpha level ;)
            final BitmapDrawable bd = (BitmapDrawable) d;
            bd.getPaint().set(mCharging ? mTextAndBoltPaint : mClearPaint);
        } else {
            d.setAlpha(mCharging ? 255 : 0);
        }

        // Now draw the level indicator
        // Set the level and tint color of the fill drawable
        mLevelDrawable.setCurrentFraction(level / 100f);
        mLevelDrawable.setTint(getColorForLevel(level));
        mBatteryDrawable.draw(c);

        // If chosen by options, draw percentage text in the middle
        // Always skip percentage when 100, so that the layout doesn't break
        if (!mCharging) {
            drawPercentageText(c);
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

    // Some stuff required by Drawable.
    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mFramePaint.setColorFilter(colorFilter);
        mBatteryPaint.setColorFilter(colorFilter);
        mWarningTextPaint.setColorFilter(colorFilter);
        mBoltPaint.setColorFilter(colorFilter);
        mPlusPaint.setColorFilter(colorFilter);
        mTextAndBoltPaint.setColorFilter(colorFilter);
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
