/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2019 The LineageOS Project
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
package com.android.settingslib.graph

import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.TypedValue
import com.android.settingslib.R
import com.android.settingslib.Utils
import kotlin.math.max
import kotlin.math.min

class CircleBatteryDrawable(private val mContext: Context, frameColor: Int) : Drawable() {
    private val mFramePaint: Paint
    private val mBatteryPaint: Paint
    private val mWarningTextPaint: Paint
    private val mTextPaint: Paint
    private val mBoltPaint: Paint
    private val mPlusPaint: Paint
    private val mPowerSavePaint: Paint
    private val mColors: IntArray
    private var mIntrinsicWidth: Int
    private var mIntrinsicHeight: Int
    private var mHeight = 0
    private var mWidth = 0
    private val mWarningString: String
    private val criticalLevel: Int
    private val mBoltPoints: FloatArray
    private val mBoltPath = Path()
    private val mPadding = Rect()
    private val mFrame = RectF()
    private val mBoltFrame = RectF()

    override fun getIntrinsicHeight() = mIntrinsicHeight

    override fun getIntrinsicWidth() = mIntrinsicWidth

    var charging = false
        set(value) {
            field = value
            postInvalidate()
        }

    var powerSaveEnabled = false
        set(value) {
            field = value
            postInvalidate()
        }

    var showPercent = false
        set(value) {
            field = value
            postInvalidate()
        }

    var batteryLevel = -1
        set(value) {
            field = value
            postInvalidate()
        }

    private var mChargeColor: Int

    // Dual tone implies that battery level is a clipped overlay over top of the whole shape
    private var mDualTone = false

    // an approximation of View.postInvalidate()
    private fun postInvalidate() {
        unscheduleSelf { invalidateSelf() }
        scheduleSelf({ invalidateSelf() }, 0)
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, right, bottom)
        updateSize()
    }

    private fun updateSize() {
        val res = mContext.resources
        mHeight = bounds.bottom - mPadding.bottom - (bounds.top + mPadding.top)
        mWidth = bounds.right - mPadding.right - (bounds.left + mPadding.left)
        mWarningTextPaint.textSize = mHeight * 0.75f
        mIntrinsicHeight = res.getDimensionPixelSize(R.dimen.battery_height)
        mIntrinsicWidth = res.getDimensionPixelSize(R.dimen.battery_height)
    }

    override fun getPadding(padding: Rect): Boolean {
        if (mPadding.left == 0 &&
            mPadding.top == 0 &&
            mPadding.right == 0 &&
            mPadding.bottom == 0
        ) {
            return super.getPadding(padding)
        }
        padding.set(mPadding)
        return true
    }

    private fun getColorForLevel(percent: Int): Int {
        var thresh: Int
        var color = 0
        var i = 0
        while (i < mColors.size) {
            thresh = mColors[i]
            color = mColors[i + 1]
            if (percent <= thresh) { // Respect tinting for "normal" level
                return if (i == mColors.size - 2) {
                    Color.WHITE
                } else {
                    color
                }
            }
            i += 2
        }
        return color
    }

    private fun batteryColorForLevel(level: Int) =
        if (charging || powerSaveEnabled) {
            mChargeColor
        } else {
            getColorForLevel(
                level
            )
        }

    fun setColors(fgColor: Int, bgColor: Int, singleToneColor: Int) {
        val fillColor = if (mDualTone) fgColor else singleToneColor

        mFramePaint.setColor(bgColor);
        mBoltPaint.setColor(fillColor);
        mChargeColor = fillColor;

        invalidateSelf()
    }

    override fun draw(c: Canvas) {
        if (batteryLevel == -1) return
        val circleSize = min(mWidth, mHeight)
        val strokeWidth = circleSize / 6.5f
        mFramePaint.strokeWidth = strokeWidth
        mFramePaint.style = Paint.Style.STROKE
        mBatteryPaint.strokeWidth = strokeWidth
        mBatteryPaint.style = Paint.Style.STROKE
        mPowerSavePaint.strokeWidth = strokeWidth
        mFrame[
            strokeWidth / 2.0f + mPadding.left, strokeWidth / 2.0f,
            circleSize - strokeWidth / 2.0f + mPadding.left
        ] = circleSize - strokeWidth / 2.0f
        // set the battery charging color
        mBatteryPaint.color = batteryColorForLevel(batteryLevel)
        if (charging) { // define the bolt shape
            val bl = mFrame.left + mFrame.width() / 3.0f
            val bt = mFrame.top + mFrame.height() / 3.4f
            val br = mFrame.right - mFrame.width() / 4.0f
            val bb = mFrame.bottom - mFrame.height() / 5.6f
            if (mBoltFrame.left != bl ||
                mBoltFrame.top != bt ||
                mBoltFrame.right != br ||
                mBoltFrame.bottom != bb
            ) {
                mBoltFrame[bl, bt, br] = bb
                mBoltPath.reset()
                mBoltPath.moveTo(
                    mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                    mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height()
                )
                var i = 2
                while (i < mBoltPoints.size) {
                    mBoltPath.lineTo(
                        mBoltFrame.left + mBoltPoints[i] * mBoltFrame.width(),
                        mBoltFrame.top + mBoltPoints[i + 1] * mBoltFrame.height()
                    )
                    i += 2
                }
                mBoltPath.lineTo(
                    mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                    mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height()
                )
            }
            c.drawPath(mBoltPath, mBoltPaint)
        }
        // draw thin gray ring first
        c.drawArc(mFrame, 270f, 360f, false, mFramePaint)
        // draw colored arc representing charge level
        if (batteryLevel > 0) {
            if (!charging && powerSaveEnabled) {
                c.drawArc(mFrame, 270f, 3.6f * batteryLevel, false, mPowerSavePaint)
            } else {
                c.drawArc(mFrame, 270f, 3.6f * batteryLevel, false, mBatteryPaint)
            }
        }
        // compute percentage text
        if (!charging && batteryLevel != 100 && showPercent) {
            mTextPaint.color = getColorForLevel(batteryLevel)
            mTextPaint.textSize = mHeight * 0.52f
            val mTextHeight = -mTextPaint.fontMetrics.ascent
            val pctText = if (batteryLevel > criticalLevel) batteryLevel.toString() else mWarningString
            val pctX = mWidth * 0.5f
            val pctY = (mHeight + mTextHeight) * 0.47f
            c.drawText(pctText, pctX, pctY, mTextPaint)
        }
    }

    // Some stuff required by Drawable.
    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {
        mFramePaint.colorFilter = colorFilter
        mBatteryPaint.colorFilter = colorFilter
        mWarningTextPaint.colorFilter = colorFilter
        mBoltPaint.colorFilter = colorFilter
        mPlusPaint.colorFilter = colorFilter
    }

    override fun getOpacity() = PixelFormat.UNKNOWN

    companion object {
        private fun loadPoints(
            res: Resources,
            pointArrayRes: Int
        ): FloatArray {
            val pts = res.getIntArray(pointArrayRes)
            var maxX = 0
            var maxY = 0
            run {
                var i = 0
                while (i < pts.size) {
                    maxX = max(maxX, pts[i])
                    maxY = max(maxY, pts[i + 1])
                    i += 2
                }
            }
            val ptsF = FloatArray(pts.size)
            var i = 0
            while (i < pts.size) {
                ptsF[i] = pts[i].toFloat() / maxX
                ptsF[i + 1] = pts[i + 1].toFloat() / maxY
                i += 2
            }
            return ptsF
        }
    }

    init {
        val res = mContext.resources
        val levels = res.obtainTypedArray(R.array.batterymeter_color_levels)
        val colors = res.obtainTypedArray(R.array.batterymeter_color_values)
        mColors = IntArray(2 * levels.length())
        for (i in 0 until levels.length()) {
            mColors[2 * i] = levels.getInt(i, 0)
            if (colors.getType(i) == TypedValue.TYPE_ATTRIBUTE) {
                mColors[2 * i + 1] = Utils.getColorAttrDefaultColor(
                    mContext,
                    colors.getThemeAttributeId(i, 0)
                )
            } else {
                mColors[2 * i + 1] = colors.getColor(i, 0)
            }
        }
        levels.recycle()
        colors.recycle()
        mWarningString = res.getString(R.string.battery_meter_very_low_overlay_symbol)
        criticalLevel = res.getInteger(
            com.android.internal.R.integer.config_criticalBatteryWarningLevel
        )
        mFramePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mFramePaint.color = frameColor
        mFramePaint.isDither = true
        mBatteryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mBatteryPaint.isDither = true
        mTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mTextPaint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        mTextPaint.textAlign = Paint.Align.CENTER
        mWarningTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mWarningTextPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        mWarningTextPaint.textAlign = Paint.Align.CENTER
        if (mColors.size > 1) {
            mWarningTextPaint.color = mColors[1]
        }
        mChargeColor = Utils.getColorStateListDefaultColor(mContext, R.color.meter_consumed_color)
        mBoltPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mBoltPaint.color = Utils.getColorStateListDefaultColor(
            mContext,
            R.color.batterymeter_bolt_color
        )
        mBoltPoints =
            loadPoints(res, R.array.batterymeter_bolt_points)
        mPlusPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mPlusPaint.color = Utils.getColorStateListDefaultColor(
            mContext,
            R.color.batterymeter_plus_color
        )
        mPowerSavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mPowerSavePaint.color = mPlusPaint.color
        mPowerSavePaint.style = Paint.Style.STROKE
        mIntrinsicWidth = res.getDimensionPixelSize(R.dimen.battery_width)
        mIntrinsicHeight = res.getDimensionPixelSize(R.dimen.battery_height)

        mDualTone = res.getBoolean(com.android.internal.R.bool.config_batterymeterDualTone)
    }
}
