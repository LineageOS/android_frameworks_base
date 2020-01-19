/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.screenshot;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.android.systemui.R;

/**
 * Draws a selection rectangle while taking screenshot
 */
public class ScreenshotSelectorView extends FrameLayout implements View.OnTouchListener {
    private final Paint mPaintSelection, mPaintBackground;
    private final Paint mPaintSelectionBorder, mPaintSelectionCircle;
    private Rect mSelectionRect;

    private ResizingHandle mResizingHandle = ResizingHandle.INVALID;
    private final int mBorderWidth;
    private final int mCircleRadius;
    private final int mTouchSlop;

    private boolean mIsFirstSelection;
    private int mMovingOffsetX;
    private int mMovingOffsetY;
    private boolean mIsMoving;

    private OnSelectionListener mListener;

    private enum ResizingHandle {
        INVALID,
        LEFT,
        TOP_LEFT,
        TOP,
        TOP_RIGHT,
        RIGHT,
        BOTTOM_RIGHT,
        BOTTOM,
        BOTTOM_LEFT;

        public boolean isValid() {
            return this != INVALID;
        }

        public boolean isLeft() {
            return this == LEFT || this == TOP_LEFT || this == BOTTOM_LEFT;
        }

        public boolean isTop() {
            return this == TOP || this == TOP_LEFT || this == TOP_RIGHT;
        }

        public boolean isRight() {
            return this == RIGHT || this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }

        public boolean isBottom() {
            return this == BOTTOM || this == BOTTOM_RIGHT || this == BOTTOM_LEFT;
        }
    }

    public ScreenshotSelectorView(Context context) {
        this(context, null);
    }

    public ScreenshotSelectorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
        mCircleRadius = (int) context.getResources()
                .getDimension(R.dimen.global_screenshot_selector_circle_radius);
        mBorderWidth = (int) context.getResources()
                .getDimension(R.dimen.global_screenshot_selector_line_width);

        mPaintBackground = new Paint(Color.BLACK);
        mPaintBackground.setAlpha(160);
        mPaintSelection = new Paint(Color.TRANSPARENT);
        mPaintSelection.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        mPaintSelectionBorder = new Paint();
        mPaintSelectionBorder.setStyle(Paint.Style.STROKE);
        mPaintSelectionBorder.setStrokeWidth(mBorderWidth);
        mPaintSelectionBorder.setColor(Color.WHITE);
        mPaintSelectionBorder.setAntiAlias(true);
        mPaintSelectionCircle = new Paint();
        mPaintSelectionCircle.setColor(Color.WHITE);
        mPaintSelectionCircle.setAntiAlias(true);

        setOnTouchListener(this);
        setWillNotDraw(false);
    }

    public void startSelection(int x, int y) {
        mSelectionRect = new Rect(x, y, x, y);
        invalidate();
    }

    public Rect getSelectionRect() {
        return mSelectionRect;
    }

    public void sortSelectionRect() {
        // The coordinates of the rect can end up being unsorted if the
        // user drags one side over the opposite side. Fix it
        mSelectionRect.sort();
    }

    public void stopSelection() {
        mSelectionRect = null;
    }

    public void delegateSelection() {
        if (mListener != null) {
            mListener.onSelectionChanged(mSelectionRect, mIsFirstSelection);
        }
    }

    public void setSelectionListener(OnSelectionListener listener) {
        mListener = listener;
    }

    private boolean isTouchingPoint(int px, int py, int x, int y) {
        return x >= px - mTouchSlop && x <= px + mTouchSlop &&
                y >= py - mTouchSlop && y <= py + mTouchSlop;
    }

    private boolean isTouchingLine(boolean vertical, int sl, int el, int o, int x, int y) {
        int fx = x;
        int fy = y;

        if (vertical) {
            fx = y;
            fy = x;
        }

        return fy >= o - mTouchSlop && fy <= o + mTouchSlop &&
                fx >= sl && fx <= el;
    }

    private ResizingHandle getTouchedResizingHandle(int x, int y) {
        if (isTouchingPoint(mSelectionRect.left, mSelectionRect.top, x, y)) {
            return ResizingHandle.TOP_LEFT;
        } else if (isTouchingPoint(mSelectionRect.right, mSelectionRect.top, x, y)) {
            return ResizingHandle.TOP_RIGHT;
        } else if (isTouchingPoint(mSelectionRect.right, mSelectionRect.bottom, x, y)) {
            return ResizingHandle.BOTTOM_RIGHT;
        } else if (isTouchingPoint(mSelectionRect.left, mSelectionRect.bottom, x, y)) {
            return ResizingHandle.BOTTOM_LEFT;
        } else if (isTouchingLine(true, mSelectionRect.top, mSelectionRect.bottom,
                mSelectionRect.left, x, y)) {
            return ResizingHandle.LEFT;
        } else if (isTouchingLine(false, mSelectionRect.left, mSelectionRect.right,
                mSelectionRect.top, x, y)) {
            return ResizingHandle.TOP;
        } else if (isTouchingLine(true, mSelectionRect.top, mSelectionRect.bottom,
                mSelectionRect.right, x, y)) {
            return ResizingHandle.RIGHT;
        } else if (isTouchingLine(false, mSelectionRect.left, mSelectionRect.right,
                mSelectionRect.bottom, x, y)) {
            return ResizingHandle.BOTTOM;
        }

        return ResizingHandle.INVALID;
    }

    public boolean isInsideSelection(int x, int y) {
        return mSelectionRect.contains(x, y);
    }

    private void resizeSelection(ResizingHandle resizingHandle, int x, int y) {
        if (resizingHandle.isLeft()) {
            mSelectionRect.left = x;
        }

        if (resizingHandle.isTop()) {
            mSelectionRect.top = y;
        }

        if (resizingHandle.isRight()) {
            mSelectionRect.right = x;
        }

        if (resizingHandle.isBottom()) {
            mSelectionRect.bottom = y;
        }

        invalidate();
    }

    private void setMovingOffset(int x, int y) {
        mMovingOffsetX = x - mSelectionRect.left;
        mMovingOffsetY = y - mSelectionRect.top;
    }

    private void moveSelection(int x, int y) {
        int left = x - mMovingOffsetX;
        int top = y - mMovingOffsetY;
        int right = left + mSelectionRect.width();
        int bottom = top + mSelectionRect.height();

        if (left >= 0 && right < getMeasuredWidth()) {
            mSelectionRect.left = left;
            mSelectionRect.right = right;
        }

        if (top >= 0 && bottom < getMeasuredHeight()) {
            mSelectionRect.top = top;
            mSelectionRect.bottom = bottom;
        }

        invalidate();
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(getLeft(), getTop(), getRight(), getBottom(), mPaintBackground);
        if (mSelectionRect != null) {
            canvas.drawRect(mSelectionRect, mPaintSelection);
            canvas.drawRect(mSelectionRect, mPaintSelectionBorder);
            canvas.drawCircle(mSelectionRect.left, mSelectionRect.bottom,
                    mCircleRadius, mPaintSelectionCircle);
            canvas.drawCircle(mSelectionRect.right, mSelectionRect.bottom,
                    mCircleRadius, mPaintSelectionCircle);
            canvas.drawCircle(mSelectionRect.right, mSelectionRect.top,
                    mCircleRadius, mPaintSelectionCircle);
            canvas.drawCircle(mSelectionRect.left, mSelectionRect.top,
                    mCircleRadius, mPaintSelectionCircle);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int x = (int) event.getX();
        int y = (int) event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (mSelectionRect == null) {
                    startSelection(x, y);
                    mIsFirstSelection = true;
                    mResizingHandle = ResizingHandle.BOTTOM_RIGHT;
                } else {
                    mResizingHandle = getTouchedResizingHandle(x, y);
                    if (mResizingHandle.isValid()) {
                        resizeSelection(mResizingHandle, x, y);
                    } else if (isInsideSelection(x, y)) {
                        mIsMoving = true;
                        setMovingOffset(x, y);
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mResizingHandle.isValid()) {
                    resizeSelection(mResizingHandle, x, y);
                } else if (mIsMoving) {
                    moveSelection(x, y);
                }
                break;
            case MotionEvent.ACTION_UP:
                sortSelectionRect();
                delegateSelection();
                mResizingHandle = ResizingHandle.INVALID;
                mIsFirstSelection = false;
                mIsMoving = false;
                break;
        }

        return true;
    }

    public interface OnSelectionListener {
        void onSelectionChanged(Rect rect, boolean firstSelection);
    }
}
