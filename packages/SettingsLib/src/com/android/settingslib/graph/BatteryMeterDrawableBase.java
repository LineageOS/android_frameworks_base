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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Path.FillType;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import com.android.settingslib.R;
import com.android.settingslib.Utils;

public class BatteryMeterDrawableBase extends Drawable {

    private static final float ASPECT_RATIO = .58f;
    private static final float CIRCLE_ASPECT_RATIO = 1.0f;
    public static final String TAG = BatteryMeterDrawableBase.class.getSimpleName();
    private static final float RADIUS_RATIO = 1.0f / 17f;

    // Values for the different battery styles
    public static final int BATTERY_STYLE_PORTRAIT = 0;
    public static final int BATTERY_STYLE_LANDSCAPE = 1;
    public static final int BATTERY_STYLE_CIRCLE = 2;
    public static final int BATTERY_STYLE_DOTTED_CIRCLE = 3;
    public static final int BATTERY_STYLE_TEXT = 4;
    public static final int BATTERY_STYLE_HIDDEN = 5;

    protected final Context mContext;
    protected final Paint mFramePaint;
    protected final Paint mBatteryPaint;
    protected final Paint mWarningTextPaint;
    protected final Paint mTextPaint;
    protected final Paint mBoltPaint;
    protected final Paint mPlusPaint;
    protected float mButtonHeightFraction;

    private int mLevel = -1;
    private boolean mCharging;
    private boolean mPowerSaveEnabled;
    private boolean mShowPercent;
    private int mMeterStyle;

    private static final boolean SINGLE_DIGIT_PERCENT = false;

    private static final int FULL = 96;

    private static final float BOLT_LEVEL_THRESHOLD = 0.3f;  // opaque bolt below this fraction

    private final int[] mColors;
    private int mIntrinsicWidth;
    private int mIntrinsicHeight;

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

    private DashPathEffect mPathEffect;

    private boolean mCircleShowPercentInside;

    public BatteryMeterDrawableBase(Context context, int frameColor) {
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

        mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBatteryPaint.setDither(true);

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

        mChargeColor = Utils.getDefaultColor(mContext, R.color.meter_consumed_color);

        mBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBoltPaint.setColor(Utils.getDefaultColor(mContext, R.color.batterymeter_bolt_color));
        mBoltPoints = loadPoints(res, R.array.batterymeter_bolt_points);

        mPlusPaint = new Paint(mBoltPaint);
        mPlusPoints = loadPoints(res, R.array.batterymeter_plus_points);

        mPathEffect = new DashPathEffect(new float[]{3,2},0);

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

    public void setMeterStyle(int style) {
        mMeterStyle = style;
        updateSize();
        postInvalidate();
    }

    public int getMeterStyle() {
        return mMeterStyle;
    }

    // an approximation of View.postInvalidate()
    protected void postInvalidate() {
        unscheduleSelf(this::invalidateSelf);
        scheduleSelf(this::invalidateSelf, 0);
    }

    public void refresh() {
        postInvalidate();
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

        if (mMeterStyle != BATTERY_STYLE_LANDSCAPE) {
            mHeight = (bounds.bottom - mPadding.bottom) - (bounds.top + mPadding.top);
            mWidth = (bounds.right - mPadding.right) - (bounds.left + mPadding.left);
        } else {
            mHeight = (bounds.right - mPadding.right) - (bounds.left + mPadding.left);
            mWidth = (bounds.bottom - mPadding.bottom) - (bounds.top + mPadding.top);
        }

        switch (mMeterStyle) {
            case BATTERY_STYLE_PORTRAIT:
            case BATTERY_STYLE_LANDSCAPE:
                mIntrinsicWidth = mContext.getResources().getDimensionPixelSize(R.dimen.battery_width);
                mIntrinsicHeight = mContext.getResources().getDimensionPixelSize(R.dimen.battery_height);
            default:
                mIntrinsicWidth = mContext.getResources().getDimensionPixelSize(R.dimen.battery_height);
                mIntrinsicHeight = mContext.getResources().getDimensionPixelSize(R.dimen.battery_height);
        }
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
        switch (mMeterStyle) {
            case BATTERY_STYLE_PORTRAIT:
                drawRectangle(c, false);
                break;
            case BATTERY_STYLE_LANDSCAPE:
                drawRectangle(c, true);
                break;
            case BATTERY_STYLE_CIRCLE:
            case BATTERY_STYLE_DOTTED_CIRCLE:
                drawCircle(c);
                break;
            default:
                drawRectangle(c, false);
                break;
        }
    }

    private void drawRectangle(Canvas c, boolean horizontal) {
        final int level = mLevel;
        final Rect bounds = getBounds();

        if (level == -1) return;

        float drawFrac = (float) level / 100f;
        final int height = mHeight;
        final int width = (int) (getAspectRatio() * mHeight);
        final int px = (mWidth - width) / 2;
        final int buttonHeight = Math.round((horizontal ? width : height) * mButtonHeightFraction);
        final int left = mPadding.left + bounds.left;
        final int top = bounds.bottom - mPadding.bottom - height;

        mFramePaint.setStrokeWidth(0);
        mFramePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        mBatteryPaint.setStrokeWidth(0);
        mBatteryPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mFrame.set(left, top, width + left, height + top);
        mFrame.offset(px, 0);

        // button-frame: area above the battery body
        if (horizontal) {
            mButtonFrame.set(
                    width - buttonHeight - mFrame.left,
                    mFrame.top + Math.round(height * 0.28f),
                    mFrame.right,
                    mFrame.bottom - Math.round(height * 0.28f));
        } else {
            mButtonFrame.set(
                    mFrame.left + Math.round(width * 0.28f),
                    mFrame.top,
                    mFrame.right - Math.round(width * 0.28f),
                    mFrame.top + buttonHeight);
        }

        // frame: battery body area
        if (horizontal) {
            mFrame.right -= buttonHeight;
        } else {
            mFrame.top += buttonHeight;
        }

        // set the battery charging color
        mBatteryPaint.setColor(batteryColorForLevel(level));

        if (level >= FULL) {
            drawFrac = 1f;
        } else if (level <= mCriticalLevel) {
            drawFrac = 0f;
        }

        final float levelTop;
        if (drawFrac == 1f) {
            if (horizontal) {
                    levelTop = mButtonFrame.right;
            } else {
                    levelTop = mButtonFrame.top;
            }
        } else {
            if (horizontal) {
                    levelTop = (mFrame.right - (mFrame.width() * (1f - drawFrac)));
            } else {
                    levelTop = (mFrame.top + (mFrame.height() * (1f - drawFrac)));
            }
        }

        // define the battery shape
        mShapePath.reset();
        final float radius = getRadiusRatio() * (mFrame.height() + buttonHeight);
        mShapePath.setFillType(FillType.WINDING);
        mShapePath.addRoundRect(mFrame, radius, radius, Direction.CW);
        mShapePath.addRect(mButtonFrame, Direction.CW);

        if (mCharging) {
            // define the bolt shape
            // Shift right by 1px for maximal bolt-goodness
            final float bl = mFrame.left + mFrame.width() / (horizontal ? 9f : (4f + 1));
            final float bt = mFrame.top + mFrame.height() / (horizontal ? (4f + 1) : 6f);
            final float br = mFrame.right - mFrame.width() / (horizontal ? 6f : (4f + 1));
            final float bb = mFrame.bottom - mFrame.height() / (horizontal ? 7f : 10f);
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
            final float full = horizontal ? 0.60f : 0.38f;
            final float nofull = horizontal ? 0.75f : 0.5f;
            final float single = horizontal ? 0.86f : 0.75f;
            mTextPaint.setTextSize(height *
                    (SINGLE_DIGIT_PERCENT ? single
                            : (mLevel == 100 ? full : nofull)));
            mTextHeight = -mTextPaint.getFontMetrics().ascent;
            pctText = String.valueOf(SINGLE_DIGIT_PERCENT ? (level / 10) : level != 100 ? level : "");
            pctX = mWidth * 0.5f;
            pctY = (mHeight + mTextHeight) * 0.47f;
            if (horizontal) {
                pctOpaque = level <= 25; // Switch at 25% for landscape icon
            } else {
                pctOpaque = levelTop > pctY;
            }
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
        if (horizontal) {
            mFrame.right = levelTop;
        } else {
            mFrame.top = levelTop;
        }
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

    private void drawCircle(Canvas c) {
        final int level = mLevel;
        final Rect bounds = getBounds();

        if (level == -1) return;

        final int circleSize = Math.min(mWidth, mHeight);
        float strokeWidth = circleSize / 6.5f;

        mFramePaint.setStrokeWidth(strokeWidth);
        mFramePaint.setStyle(Paint.Style.STROKE);

        mBatteryPaint.setStrokeWidth(strokeWidth);
        mBatteryPaint.setStyle(Paint.Style.STROKE);

        if (mMeterStyle == BATTERY_STYLE_DOTTED_CIRCLE) {
            mBatteryPaint.setPathEffect(mPathEffect);
        } else {
            mBatteryPaint.setPathEffect(null);
        }

        mFrame.set(
                strokeWidth / 2.0f + mPadding.left,
                strokeWidth / 2.0f,
                circleSize - strokeWidth / 2.0f + mPadding.left,
                circleSize - strokeWidth / 2.0f);

        // set the battery charging color
        mBatteryPaint.setColor(batteryColorForLevel(level));

        if (mCharging) {
            // define the bolt shape
            // Shift right by 1px for maximal bolt-goodness
            final float bl = mFrame.left + mFrame.width() / 3.0f;
            final float bt = mFrame.top + mFrame.height() / 3.4f;
            final float br = mFrame.right - mFrame.width() / 4.0f;
            final float bb = mFrame.bottom - mFrame.height() / 5.6f;
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
            c.drawPath(mBoltPath, mBoltPaint);
        }

        // draw thin gray ring first
        c.drawArc(mFrame, 270, 360, false, mFramePaint);

        // draw colored arc representing charge level
        if (level > 0) {
            c.drawArc(mFrame, 270, 3.6f * level, false, mBatteryPaint);
        }

        final int height = mHeight;
        final int width = (int) (getAspectRatio() * mHeight);
        // compute percentage text
        float pctX = 0, pctY = 0;
        String pctText = null;
        if (!mCharging) {
            mTextPaint.setColor(getColorForLevel(level));
            final float full = 0.30f;
            final float nofull =  0.52f;
            final float single =  0.86f;
            mTextPaint.setTextSize(height *
                    (SINGLE_DIGIT_PERCENT ? single
                            : (mLevel == 100 ? full : nofull)));
            mTextHeight = -mTextPaint.getFontMetrics().ascent;
            pctText = level > mCriticalLevel ? (String.valueOf(SINGLE_DIGIT_PERCENT ? (level / 10) : (level != 100 && mCircleShowPercentInside ? level : "")))
                    : mWarningString;
            pctX = mWidth * 0.5f;
            pctY = (mHeight + mTextHeight) * 0.47f;

            c.drawText(pctText, pctX, pctY, mTextPaint);
        }
    }

    // Some stuff required by Drawable.
    @Override
    public void setAlpha(int alpha) {
    }

    public void showPercentInsideCircle(boolean show) {
        mCircleShowPercentInside = show;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mFramePaint.setColorFilter(colorFilter);
        mBatteryPaint.setColorFilter(colorFilter);
        mWarningTextPaint.setColorFilter(colorFilter);
        mBoltPaint.setColorFilter(colorFilter);
        mPlusPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return 0;
    }

    public int getCriticalLevel() {
        return mCriticalLevel;
    }

    protected float getAspectRatio() {
        if (mMeterStyle != BATTERY_STYLE_PORTRAIT
                && mMeterStyle != BATTERY_STYLE_LANDSCAPE) {
            return CIRCLE_ASPECT_RATIO;
        }
        return ASPECT_RATIO;
    }

    protected float getRadiusRatio() {
        return RADIUS_RATIO;
    }
}
