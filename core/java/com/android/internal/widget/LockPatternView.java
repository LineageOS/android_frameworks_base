/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.IntArray;
import android.util.Log;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RenderNodeAnimator;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.R;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.widget.LockPatternUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays and detects the user's unlock attempt, which is a drag of a finger
 * across regions of the screen.
 * Is also capable of displaying a static pattern in "in progress", "wrong" or
 * "correct" states.
 */
public class LockPatternView extends View {
    // Aspect to use when rendering this view
    private static final int ASPECT_SQUARE = 0; // View will be the minimum of width/height
    private static final int ASPECT_LOCK_WIDTH = 1; // Fixed width; height will be minimum of (w,h)
    private static final int ASPECT_LOCK_HEIGHT = 2; // Fixed height; width will be minimum of (w,h)

    private static final int DOT_COUNT = 9;

    private static final boolean PROFILE_DRAWING = false;
    private static final int LINE_END_ANIMATION_DURATION_MILLIS = 50;
    private static final int DOT_ACTIVATION_DURATION_MILLIS = 50;
    private static final int DOT_RADIUS_INCREASE_DURATION_MILLIS = 96;
    private static final int DOT_RADIUS_DECREASE_DURATION_MILLIS = 192;
    private static final int ALPHA_MAX_VALUE = 255;
    private static final float MIN_DOT_HIT_FACTOR = 0.2f;
    private CellState[][] mCellStates;

    private static final int CELL_ACTIVATE = 0;
    private static final int CELL_DEACTIVATE = 1;

    private int mDotSize;
    private int mDotSizeActivated;
    private final float mDotHitFactor;
    private int mPathWidth;
    private final int mLineFadeOutAnimationDurationMs;
    private final int mLineFadeOutAnimationDelayMs;
    private final int mFadePatternAnimationDurationMs;
    private final int mFadePatternAnimationDelayMs;

    private boolean mDrawingProfilingStarted = false;

    @UnsupportedAppUsage
    private final Paint mPaint = new Paint();
    private final Paint mFocusPaint = new Paint();
    @UnsupportedAppUsage
    private final Paint mPathPaint = new Paint();

    /**
     * How many milliseconds we spend animating each circle of a lock pattern
     * if the animating mode is set.  The entire animation should take this
     * constant * the length of the pattern to complete.
     */
    private static final int MILLIS_PER_CIRCLE_ANIMATING = 700;

    private byte mPatternSize = LockPatternUtils.PATTERN_SIZE_DEFAULT;

    /**
     * This can be used to avoid updating the display for very small motions or noisy panels.
     * It didn't seem to have much impact on the devices tested, so currently set to 0.
     */
    private static final float DRAG_THRESHOLD = 0.0f;
    public static final int VIRTUAL_BASE_VIEW_ID = 1;
    public static final boolean DEBUG_A11Y = false;
    private static final String TAG = "LockPatternView";

    private OnPatternListener mOnPatternListener;
    private ExternalHapticsPlayer mExternalHapticsPlayer;
    @UnsupportedAppUsage
    private ArrayList<Cell> mPattern = new ArrayList<Cell>(mPatternSize * mPatternSize);

    /**
     * Lookup table for the circles of the pattern we are currently drawing.
     * This will be the cells of the complete pattern unless we are animating,
     * in which case we use this to hold the cells we are drawing for the in
     * progress animation.
     */
    private boolean[][] mPatternDrawLookup = new boolean[mPatternSize][mPatternSize];

    /**
     * the in progress point:
     * - during interaction: where the user's finger is
     * - during animation: the current tip of the animating line
     */
    private float mInProgressX = -1;
    private float mInProgressY = -1;

    private long mAnimatingPeriodStart;
    private long[] mLineFadeStart = new long[mPatternSize * mPatternSize];

    @UnsupportedAppUsage
    private DisplayMode mPatternDisplayMode = DisplayMode.Correct;
    private boolean mInputEnabled = true;
    @UnsupportedAppUsage
    private boolean mInStealthMode = false;
    private InputMode mInputMode = InputMode.Swipe;
    private boolean mClickInputSupported = false;

    private boolean mFocusVisible = false;
    /* Index of the cell with current focus. -1 or DOT_COUNT if no cell is focused */
    private int mFocusedCellIndex = 0;
    private int mFocusColor;
    private static final float FOCUS_RING_WIDTH = 3f;
    private static final float FOCUS_RING_GAP = 6f;

    @UnsupportedAppUsage
    private boolean mPatternInProgress = false;
    private boolean mFadePattern = true;
    private boolean mVisibleDots = true;
    private boolean mShowErrorPath = true;

    private boolean mFadeClear = false;
    private int mFadeAnimationAlpha = ALPHA_MAX_VALUE;
    private final Path mPatternPath = new Path();

    @UnsupportedAppUsage
    private float mSquareWidth;
    @UnsupportedAppUsage
    private float mSquareHeight;
    private float mDotHitRadius;
    private float mDotHitMaxRadius;
    private final LinearGradient mFadeOutGradientShader;

    private final Path mCurrentPath = new Path();
    private final Rect mInvalidate = new Rect();
    private final Rect mTmpInvalidateRect = new Rect();

    private int mAspect;
    private int mRegularColor;
    private int mErrorColor;
    private int mSuccessColor;
    private int mDotColor;
    private int mDotActivatedColor;
    private boolean mKeepDotActivated;
    private boolean mEnlargeVertex;

    private final Interpolator mFastOutSlowInInterpolator;
    private final Interpolator mLinearOutSlowInInterpolator;
    private final Interpolator mStandardAccelerateInterpolator;
    private final PatternExploreByTouchHelper mExploreByTouchHelper;

    private Drawable mSelectedDrawable;
    private Drawable mNotSelectedDrawable;
    private boolean mUseLockPatternDrawable;

    private LockPatternUtils mLockPatternUtils;

    /**
     * Represents a cell in the matrix of the unlock pattern view.
     */
    public static final class Cell {
        @UnsupportedAppUsage
        final int row;
        @UnsupportedAppUsage
        final int column;

        static Cell[][] sCells;
        static {
            updateSize(LockPatternUtils.PATTERN_SIZE_DEFAULT);
        }

        /**
         * @param row The row of the cell.
         * @param column The column of the cell.
         * @param size The size of the cell.
         */
        private Cell(int row, int column, byte size) {
            checkRange(row, column, size);
            this.row = row;
            this.column = column;
        }

        public int getRow() {
            return row;
        }

        public int getColumn() {
            return column;
        }

        public static Cell of(int row, int column, byte size) {
            checkRange(row, column, size);
            return sCells[row][column];
        }

        public static void updateSize(byte size) {
            sCells = new Cell[size][size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    sCells[i][j] = new Cell(i, j, size);
                }
            }
        }

        private static void checkRange(int row, int column, byte size) {
            if (row < 0 || row > size - 1) {
                throw new IllegalArgumentException("row must be in range 0-" + (size - 1));
            }
            if (column < 0 || column > size - 1) {
                throw new IllegalArgumentException("column must be in range 0-" + (size - 1));
            }
        }
        @Override
        public String toString() {
            return "(row=" + row + ",clmn=" + column + ")";
        }
    }

    public static class CellState {
        int row;
        int col;
        boolean hwAnimating;
        CanvasProperty<Float> hwRadius;
        CanvasProperty<Float> hwCenterX;
        CanvasProperty<Float> hwCenterY;
        CanvasProperty<Paint> hwPaint;
        float radius;
        float translationY;
        float alpha = 1f;
        float activationAnimationProgress;
        public float lineEndX = Float.MIN_VALUE;
        public float lineEndY = Float.MIN_VALUE;
        @Nullable
        Animator activationAnimator;
        @Nullable
        Animator deactivationAnimator;
    }

    /**
     * How to display the current pattern.
     */
    public enum DisplayMode {

        /**
         * The pattern drawn is correct (i.e. draw it in a friendly color)
         */
        @UnsupportedAppUsage
        Correct,

        /**
         * Animate the pattern (for demo, and help).
         */
        @UnsupportedAppUsage
        Animate,

        /**
         * The pattern is wrong (i.e. draw a foreboding color)
         */
        @UnsupportedAppUsage
        Wrong
    }

    /**
     * Input behavior types of the UI
     */
    public enum InputMode {

        /**
         * A user is entering the pattern using swiping, e.g. with a finger, stylus or with
         * touch exploration support
         */
        Swipe,

        /**
         * A user is entering the pattern using click, e.g. with a finger, mouse or track pad
         */
        Click
    }

    /**
     * The call back interface for detecting patterns entered by the user.
     */
    public static interface OnPatternListener {

        /**
         * A new pattern has begun.
         *
         * @deprecated use {@link #onPatternStart(InputMode)}
         */
        @Deprecated
        default void onPatternStart() {}

        /**
         * A new pattern has begun.
         * @param inputMode The input mode that was used to enter the pattern.
         */
        default void onPatternStart(InputMode inputMode) {
            onPatternStart();
        }

        /**
         * The pattern was cleared.
         */
        default void onPatternCleared() {}

        /**
         * The user extended the pattern currently being drawn by one cell.
         * @param pattern The pattern with newly added cell.
         *
         * @deprecated use {@link #onPatternCellAdded(List<Cell>, InputMode)}
         */
        @Deprecated
        default void onPatternCellAdded(List<Cell> pattern) {}

        /**
         * The user extended the pattern currently being drawn by one cell.
         * @param pattern The pattern with newly added cell.
         * @param inputMode The input mode that was used to enter the pattern.
         */
        default void onPatternCellAdded(List<Cell> pattern, InputMode inputMode) {
            onPatternCellAdded(pattern);
        }

        /**
         * A pattern was detected from the user.
         * @param pattern The pattern.
         * @param patternSize The pattern size.
         *
         * @deprecated use {@link #onPatternDetected(List<Cell>, InputMode, byte)}
         */
        @Deprecated
        default void onPatternDetected(List<Cell> pattern, byte patternSize) {}

        /**
         * A pattern was detected from the user.
         * @param pattern The pattern.
         * @param inputMode The input mode that was used to enter the pattern.
         * @param patternSize The pattern size.
         */
        default void onPatternDetected(List<Cell> pattern, InputMode inputMode, byte patternSize) {
            onPatternDetected(pattern, patternSize);
        }
    }

    /** An external haptics player for pattern updates. */
    public interface ExternalHapticsPlayer{

        /** Perform haptic feedback when a cell is added to the pattern. */
        void performCellAddedFeedback();
    }

    public LockPatternView(Context context) {
        this(context, null);
    }

    @UnsupportedAppUsage
    public LockPatternView(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.LockPatternView,
                R.attr.lockPatternStyle, R.style.Widget_LockPatternView);

        final String aspect = a.getString(R.styleable.LockPatternView_aspect);

        if ("square".equals(aspect)) {
            mAspect = ASPECT_SQUARE;
        } else if ("lock_width".equals(aspect)) {
            mAspect = ASPECT_LOCK_WIDTH;
        } else if ("lock_height".equals(aspect)) {
            mAspect = ASPECT_LOCK_HEIGHT;
        } else {
            mAspect = ASPECT_SQUARE;
        }

        setClickable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
        updateFocusable();

        mPathPaint.setAntiAlias(true);
        mPathPaint.setDither(true);

        mRegularColor = a.getColor(R.styleable.LockPatternView_regularColor, 0);
        mErrorColor = a.getColor(R.styleable.LockPatternView_errorColor, 0);
        mSuccessColor = a.getColor(R.styleable.LockPatternView_successColor, 0);
        mDotColor = a.getColor(R.styleable.LockPatternView_dotColor, mRegularColor);
        mDotActivatedColor = a.getColor(R.styleable.LockPatternView_dotActivatedColor, mDotColor);
        mKeepDotActivated = a.getBoolean(R.styleable.LockPatternView_keepDotActivated, false);
        mEnlargeVertex = a.getBoolean(R.styleable.LockPatternView_enlargeVertexEntryArea, false);
        mFocusColor = mDotColor;

        int pathColor = a.getColor(R.styleable.LockPatternView_pathColor, mRegularColor);
        mPathPaint.setColor(pathColor);

        mPathPaint.setStyle(Paint.Style.STROKE);
        mPathPaint.setStrokeJoin(Paint.Join.ROUND);
        mPathPaint.setStrokeCap(Paint.Cap.ROUND);

        mPathWidth = getResources().getDimensionPixelSize(R.dimen.lock_pattern_dot_line_width);
        mPathPaint.setStrokeWidth(mPathWidth);

        mLineFadeOutAnimationDurationMs =
                getResources().getInteger(R.integer.lock_pattern_line_fade_out_duration);
        mLineFadeOutAnimationDelayMs =
                getResources().getInteger(R.integer.lock_pattern_line_fade_out_delay);

        mFadePatternAnimationDurationMs =
                getResources().getInteger(R.integer.lock_pattern_fade_pattern_duration);
        mFadePatternAnimationDelayMs =
                getResources().getInteger(R.integer.lock_pattern_fade_pattern_delay);

        mDotSize = getResources().getDimensionPixelSize(R.dimen.lock_pattern_dot_size);
        mDotSizeActivated = getResources().getDimensionPixelSize(
                R.dimen.lock_pattern_dot_size_activated);
        TypedValue outValue = new TypedValue();
        getResources().getValue(R.dimen.lock_pattern_dot_hit_factor, outValue, true);
        mDotHitFactor = Math.max(Math.min(outValue.getFloat(), 1f), MIN_DOT_HIT_FACTOR);

        mUseLockPatternDrawable = getResources().getBoolean(R.bool.use_lock_pattern_drawable);
        if (mUseLockPatternDrawable) {
            mSelectedDrawable = getResources().getDrawable(R.drawable.lockscreen_selected);
            mNotSelectedDrawable = getResources().getDrawable(R.drawable.lockscreen_notselected);
        }

        mPaint.setAntiAlias(true);
        mPaint.setDither(true);

        mFocusPaint.setAntiAlias(true);
        mFocusPaint.setDither(true);
        mFocusPaint.setColor(mFocusColor);
        mFocusPaint.setAlpha(255);
        mFocusPaint.setStyle(Paint.Style.STROKE);
        mFocusPaint.setStrokeWidth(FOCUS_RING_WIDTH);

        mCellStates = new CellState[mPatternSize][mPatternSize];
        for (int i = 0; i < mPatternSize; i++) {
            for (int j = 0; j < mPatternSize; j++) {
                mCellStates[i][j] = new CellState();
                mCellStates[i][j].radius = mDotSize/2;
                mCellStates[i][j].row = i;
                mCellStates[i][j].col = j;
            }
        }

        mFastOutSlowInInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in);
        mLinearOutSlowInInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.linear_out_slow_in);
        mStandardAccelerateInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_linear_in);
        mExploreByTouchHelper = new PatternExploreByTouchHelper(this);
        setAccessibilityDelegate(mExploreByTouchHelper);

        int fadeAwayGradientWidth = getResources().getDimensionPixelSize(
                R.dimen.lock_pattern_fade_away_gradient_width);
        // Set up gradient shader with the middle in point (0, 0).
        mFadeOutGradientShader = new LinearGradient(/* x0= */ -fadeAwayGradientWidth / 2f,
                /* y0= */ 0,/* x1= */ fadeAwayGradientWidth / 2f, /* y1= */ 0,
                Color.TRANSPARENT, pathColor, Shader.TileMode.CLAMP);

        a.recycle();
    }

    @UnsupportedAppUsage
    public CellState[][] getCellStates() {
        return mCellStates;
    }

    /**
     * @return Whether the view is in stealth mode.
     */
    public boolean isInStealthMode() {
        return mInStealthMode;
    }

    /**
     * @return the current pattern lockscreen size.
     */
    public byte getLockPatternSize() {
        return mPatternSize;
    }

    /**
     * Set whether the view is in stealth mode.  If true, there will be no
     * visible feedback as the user enters the pattern.
     *
     * @param inStealthMode Whether in stealth mode.
     */
    @UnsupportedAppUsage
    public void setInStealthMode(boolean inStealthMode) {
        mInStealthMode = inStealthMode;
    }

    public void setVisibleDots(boolean visibleDots) {
        mVisibleDots = visibleDots;
    }

    public boolean isVisibleDots() {
        return mVisibleDots;
    }

    public void setShowErrorPath(boolean showErrorPath) {
        mShowErrorPath = showErrorPath;
    }

    public boolean isShowErrorPath() {
        return mShowErrorPath;
    }

    /**
     * Get the current input mode
     * @return Current input mode of the view.
     */
    @VisibleForTesting
    public InputMode getInputMode() {
        return mInputMode;
    }

    /**
     * Set whether the view supports click input mode. If true, a pattern can be entered using
     * sequential clicks.
     * @param clickInputSupported Whether tap input is supported.
     */
    public void setClickInputSupported(boolean clickInputSupported) {
        mClickInputSupported = clickInputSupported;
        updateFocusable();
    }

    /**
     * Set whether the pattern should fade as it's being drawn. If
     * true, each segment of the pattern fades over time.
     */
    public void setFadePattern(boolean fadePattern) {
        mFadePattern = fadePattern;
    }

    /**
     * Set the pattern size of the lockscreen
     *
     * @param size The pattern size.
     */
    public void setLockPatternSize(byte size) {
        mPatternSize = size;
        Cell.updateSize(size);
        mCellStates = new CellState[mPatternSize][mPatternSize];
        for (int i = 0; i < mPatternSize; i++) {
            for (int j = 0; j < mPatternSize; j++) {
                mCellStates[i][j] = new CellState();
                mCellStates[i][j].radius = mDotSize / 2;
                mCellStates[i][j].row = i;
                mCellStates[i][j].col = j;
            }
        }
        mPattern = new ArrayList<Cell>(size * size);
        mLineFadeStart = new long[size * size];
        mPatternDrawLookup = new boolean[size][size];
    }

    /**
     * Set the LockPatternUtil instance used to encode a pattern to a string
     * @param utils The instance.
     */
    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    /**
     * Set the call back for pattern detection.
     * @param onPatternListener The call back.
     */
    @UnsupportedAppUsage
    public void setOnPatternListener(
            OnPatternListener onPatternListener) {
        mOnPatternListener = onPatternListener;
    }

    /**
     * Set the external haptics player for feedback on pattern detection.
     * @param player The external player.
     */
    @UnsupportedAppUsage
    public void setExternalHapticsPlayer(ExternalHapticsPlayer player) {
        mExternalHapticsPlayer = player;
    }

    /**
     * Set the pattern explicitly (rather than waiting for the user to input
     * a pattern).
     * @param displayMode How to display the pattern.
     * @param pattern The pattern.
     */
    public void setPattern(DisplayMode displayMode, List<Cell> pattern) {
        mPattern.clear();
        mPattern.addAll(pattern);
        clearPatternDrawLookup();
        for (Cell cell : pattern) {
            mPatternDrawLookup[cell.getRow()][cell.getColumn()] = true;
        }

        setDisplayMode(displayMode);
    }

    private boolean isPatternFull() {
        return mPattern.size() == DOT_COUNT;
    }

    /**
     * Set the display mode of the current pattern.  This can be useful, for
     * instance, after detecting a pattern to tell this view whether change the
     * in progress result to correct or wrong.
     * @param displayMode The display mode.
     */
    @UnsupportedAppUsage
    public void setDisplayMode(DisplayMode displayMode) {
        mPatternDisplayMode = displayMode;
        if (displayMode == DisplayMode.Animate) {
            if (mPattern.isEmpty()) {
                throw new IllegalStateException("you must have a pattern to "
                        + "animate if you want to set the display mode to animate");
            }
            mAnimatingPeriodStart = SystemClock.elapsedRealtime();
            final Cell first = mPattern.getFirst();
            mInProgressX = getCenterXForColumn(first.getColumn());
            mInProgressY = getCenterYForRow(first.getRow());
            clearPatternDrawLookup();
        } else if (displayMode == DisplayMode.Wrong && !mShowErrorPath) {
            resetPatternCellSize();
        }
        invalidate();
    }

    private boolean calculateFocusableState() {
        if (!mClickInputSupported) {
            return false;
        }
        if (!mInputEnabled || !isEnabled()) {
            return false;
        }
        if (isPatternFull()) {
            return false;
        }
        return true;
    }

    private void updateFocusable() {
        final boolean oldState = isFocusable();
        final boolean newState = calculateFocusableState();
        if (oldState != newState) {
            setFocusable(newState);
            setFocusableInTouchMode(newState);
            if (!newState) {
                mFocusVisible = false;
            }
            invalidate();
        }
    }

    public void startCellStateAnimation(CellState cellState, float startAlpha, float endAlpha,
            float startTranslationY, float endTranslationY, float startScale, float endScale,
            long delay, long duration,
            Interpolator interpolator, Runnable finishRunnable) {
        if (isHardwareAccelerated()) {
            startCellStateAnimationHw(cellState, startAlpha, endAlpha, startTranslationY,
                    endTranslationY, startScale, endScale, delay, duration, interpolator,
                    finishRunnable);
        } else {
            startCellStateAnimationSw(cellState, startAlpha, endAlpha, startTranslationY,
                    endTranslationY, startScale, endScale, delay, duration, interpolator,
                    finishRunnable);
        }
    }

    private void startCellStateAnimationSw(final CellState cellState,
            final float startAlpha, final float endAlpha,
            final float startTranslationY, final float endTranslationY,
            final float startScale, final float endScale,
            long delay, long duration, Interpolator interpolator, final Runnable finishRunnable) {
        cellState.alpha = startAlpha;
        cellState.translationY = startTranslationY;
        cellState.radius = mDotSize/2 * startScale;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setStartDelay(delay);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (float) animation.getAnimatedValue();
                cellState.alpha = (1 - t) * startAlpha + t * endAlpha;
                cellState.translationY = (1 - t) * startTranslationY + t * endTranslationY;
                cellState.radius = mDotSize/2 * ((1 - t) * startScale + t * endScale);
                invalidate();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (finishRunnable != null) {
                    finishRunnable.run();
                }
            }
        });
        animator.start();
    }

    private void startCellStateAnimationHw(final CellState cellState,
            float startAlpha, float endAlpha,
            float startTranslationY, float endTranslationY,
            float startScale, float endScale,
            long delay, long duration, Interpolator interpolator, final Runnable finishRunnable) {
        cellState.alpha = endAlpha;
        cellState.translationY = endTranslationY;
        cellState.radius = mDotSize/2 * endScale;
        cellState.hwAnimating = true;
        cellState.hwCenterY = CanvasProperty.createFloat(
                getCenterYForRow(cellState.row) + startTranslationY);
        cellState.hwCenterX = CanvasProperty.createFloat(getCenterXForColumn(cellState.col));
        cellState.hwRadius = CanvasProperty.createFloat(mDotSize/2 * startScale);
        mPaint.setColor(getDotColor());
        mPaint.setAlpha((int) (startAlpha * 255));
        cellState.hwPaint = CanvasProperty.createPaint(new Paint(mPaint));

        startRtFloatAnimation(cellState.hwCenterY,
                getCenterYForRow(cellState.row) + endTranslationY, delay, duration, interpolator);
        startRtFloatAnimation(cellState.hwRadius, mDotSize/2 * endScale, delay, duration,
                interpolator);
        startRtAlphaAnimation(cellState, endAlpha, delay, duration, interpolator,
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        cellState.hwAnimating = false;
                        if (finishRunnable != null) {
                            finishRunnable.run();
                        }
                    }
                });

        invalidate();
    }

    private void startRtAlphaAnimation(CellState cellState, float endAlpha,
            long delay, long duration, Interpolator interpolator,
            Animator.AnimatorListener listener) {
        RenderNodeAnimator animator = new RenderNodeAnimator(cellState.hwPaint,
                RenderNodeAnimator.PAINT_ALPHA, (int) (endAlpha * 255));
        animator.setDuration(duration);
        animator.setStartDelay(delay);
        animator.setInterpolator(interpolator);
        animator.setTarget(this);
        animator.addListener(listener);
        animator.start();
    }

    private void startRtFloatAnimation(CanvasProperty<Float> property, float endValue,
            long delay, long duration, Interpolator interpolator) {
        RenderNodeAnimator animator = new RenderNodeAnimator(property, endValue);
        animator.setDuration(duration);
        animator.setStartDelay(delay);
        animator.setInterpolator(interpolator);
        animator.setTarget(this);
        animator.start();
    }

    private void notifyCellAdded() {
        if (mOnPatternListener != null) {
            mOnPatternListener.onPatternCellAdded(new ArrayList(mPattern), mInputMode);
        }
    }

    private void notifyPatternStarted() {
        if (mOnPatternListener != null) {
            mOnPatternListener.onPatternStart(mInputMode);
        }
    }

    @UnsupportedAppUsage
    private void notifyPatternDetected() {
        if (mOnPatternListener != null) {
            mOnPatternListener.onPatternDetected(new ArrayList(mPattern), mInputMode, mPatternSize);
        }
    }

    private void notifyPatternCleared() {
        if (mOnPatternListener != null) {
            mOnPatternListener.onPatternCleared();
        }
    }

    /**
     * Clear the pattern.
     */
    @UnsupportedAppUsage
    public void clearPattern() {
        resetPattern();
    }

    /**
     * Clear the pattern by fading it out.
     */
    @UnsupportedAppUsage
    public void fadeClearPattern() {
        mFadeClear = true;
        startFadePatternAnimation();
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent event) {
        // Dispatch to onHoverEvent first so mPatternInProgress is up to date when the
        // helper gets the event.
        boolean handled = super.dispatchHoverEvent(event);
        handled |= mExploreByTouchHelper.dispatchHoverEvent(event);
        return handled;
    }

    /**
     * Reset all pattern state.
     */
    private void resetPattern() {
        if (mKeepDotActivated && !mPattern.isEmpty()) {
            resetPatternCellSize();
        }
        mPattern.clear();
        mPatternPath.reset();
        clearPatternDrawLookup();
        mPatternDisplayMode = DisplayMode.Correct;
        mFocusedCellIndex = 0;
        updateFocusable();
        notifyPatternCleared();
        invalidate();
        mExploreByTouchHelper.invalidateRoot();
    }

    private void resetPatternCellSize() {
        for (int i = 0; i < mCellStates.length; i++) {
            for (int j = 0; j < mCellStates[i].length; j++) {
                CellState cellState = mCellStates[i][j];
                if (cellState.activationAnimator != null) {
                    cellState.activationAnimator.cancel();
                }
                if (cellState.deactivationAnimator != null) {
                    cellState.deactivationAnimator.cancel();
                }
                cellState.activationAnimationProgress = 0f;
                cellState.radius = mDotSize / 2f;
            }
        }
    }

    /**
     * If there are any cells being drawn.
     */
    public boolean isEmpty() {
        return mPattern.isEmpty();
    }

    /**
     * Clear the pattern lookup table. Also reset the line fade start times for
     * the next attempt.
     */
    private void clearPatternDrawLookup() {
        for (int i = 0; i < mPatternSize; i++) {
            for (int j = 0; j < mPatternSize; j++) {
                mPatternDrawLookup[i][j] = false;
                mLineFadeStart[i * mPatternSize + j] = 0;
            }
        }
    }

    /**
     * Disable input (for instance when displaying a message that will
     * time out so user doesn't get view into messy state).
     * @deprecated use {@link #setEnableInput(boolean)}
     */
    @UnsupportedAppUsage
    public void disableInput() {
        setEnableInput(false);
    }

    /**
     * Enable input.
     * @deprecated use {@link #setEnableInput(boolean)}
     */
    @UnsupportedAppUsage
    public void enableInput() {
        setEnableInput(true);
    }

    /**
     * Enable or disable input (for instance when displaying a message that will
     * time out so user doesn't get view into messy state).
     * @param newState New input enablement state
     */
    public void setEnableInput(boolean newState) {
        final boolean oldState = mInputEnabled;
        if (oldState != newState) {
            mInputEnabled = newState;
            updateFocusable();
            mExploreByTouchHelper.invalidateRoot();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        final int width = w - mPaddingLeft - mPaddingRight;
        mSquareWidth = width / (float) mPatternSize;

        if (DEBUG_A11Y) Log.v(TAG, "onSizeChanged(" + w + "," + h + ")");
        final int height = h - mPaddingTop - mPaddingBottom;
        mSquareHeight = height / (float) mPatternSize;
        mExploreByTouchHelper.invalidateRoot();
        mDotHitMaxRadius = Math.min(mSquareHeight / 2, mSquareWidth / 2);
        mDotHitRadius = mDotHitMaxRadius * mDotHitFactor;

        if (mUseLockPatternDrawable) {
            mNotSelectedDrawable.setBounds(mPaddingLeft, mPaddingTop, width, height);
            mSelectedDrawable.setBounds(mPaddingLeft, mPaddingTop, width, height);
        }

        if (!mPattern.isEmpty()) {
            Cell cell = mPattern.getLast();
            mInProgressX = getCenterXForColumn(cell.column);
            mInProgressY = getCenterYForRow(cell.row);
        }
    }

    private int resolveMeasured(int measureSpec, int desired) {
        int result = 0;
        int specSize = MeasureSpec.getSize(measureSpec);
        switch (MeasureSpec.getMode(measureSpec)) {
            case MeasureSpec.UNSPECIFIED:
                result = desired;
                break;
            case MeasureSpec.AT_MOST:
                result = Math.max(specSize, desired);
                break;
            case MeasureSpec.EXACTLY:
            default:
                result = specSize;
        }
        return result;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int minimumWidth = getSuggestedMinimumWidth();
        final int minimumHeight = getSuggestedMinimumHeight();
        int viewWidth = resolveMeasured(widthMeasureSpec, minimumWidth);
        int viewHeight = resolveMeasured(heightMeasureSpec, minimumHeight);

        switch (mAspect) {
            case ASPECT_SQUARE:
                viewWidth = viewHeight = Math.min(viewWidth, viewHeight);
                break;
            case ASPECT_LOCK_WIDTH:
                viewHeight = Math.min(viewWidth, viewHeight);
                break;
            case ASPECT_LOCK_HEIGHT:
                viewWidth = Math.min(viewWidth, viewHeight);
                break;
        }
        // Log.v(TAG, "LockPatternView dimensions: " + viewWidth + "x" + viewHeight);
        setMeasuredDimension(viewWidth, viewHeight);
    }

    /**
     * Determines whether the point x, y will add a new point to the current
     * pattern (in addition to finding the cell, also makes heuristic choices
     * such as filling in gaps based on current pattern).
     * @param x The x coordinate.
     * @param y The y coordinate.
     */
    private Cell detectAndAddHit(float x, float y) {
        final Cell cell = checkForNewHit(x, y);
        if (cell != null) {

            // check for gaps in existing pattern
            final ArrayList<Cell> pattern = mPattern;
            Cell lastCell = null;
            if (!pattern.isEmpty()) {
                lastCell = pattern.getLast();
                int dRow = cell.row - lastCell.row;
                int dColumn = cell.column - lastCell.column;

                int fillInRow = lastCell.row;
                int fillInColumn = lastCell.column;

                if (dRow == 0 || dColumn == 0 || Math.abs(dRow) == Math.abs(dColumn)) {
                    while (true) {
                        fillInRow += Integer.signum(dRow);
                        fillInColumn += Integer.signum(dColumn);
                        if (fillInRow == cell.row && fillInColumn == cell.column) break;
                        Cell fillInGapCell = Cell.of(fillInRow, fillInColumn, mPatternSize);
                        if (!mPatternDrawLookup[fillInGapCell.row][fillInGapCell.column]) {
                            addCellToPattern(fillInGapCell);
                        }
                    }
                }
            }

            addCellToPattern(cell);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
            return cell;
        }
        return null;
    }

    @Override
    public boolean performHapticFeedback(int feedbackConstant, int flags) {
        if (mExternalHapticsPlayer != null) {
            mExternalHapticsPlayer.performCellAddedFeedback();
            return true;
        } else {
            return super.performHapticFeedback(feedbackConstant, flags);
        }
    }

    private void addCellToPattern(Cell cell) {
        mPatternDrawLookup[cell.getRow()][cell.getColumn()] = true;
        mPattern.add(cell);
        updateFocusable();
        if (!mInStealthMode) {
            startCellActivatedAnimation(cell);
        }
        notifyCellAdded();
        // Disable used cells for accessibility click as they get added
        if (DEBUG_A11Y) Log.v(TAG, "addCellToPattern invalidating cell because cell was added.");
        mExploreByTouchHelper.invalidateRoot();
        if (mClickInputSupported) {
            final int virtualViewId = VIRTUAL_BASE_VIEW_ID + cell.row * 3 + cell.column;
            mExploreByTouchHelper.sendEventForVirtualView(virtualViewId,
                    AccessibilityEvent.TYPE_VIEW_SELECTED);
        }
    }

    private void startFadePatternAnimation() {
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.play(createFadePatternAnimation());
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mFadeAnimationAlpha = ALPHA_MAX_VALUE;
                mFadeClear = false;
                resetPattern();
            }
        });
        animatorSet.start();

    }

    private Animator createFadePatternAnimation() {
        ValueAnimator valueAnimator = ValueAnimator.ofInt(ALPHA_MAX_VALUE, 0);
        valueAnimator.addUpdateListener(animation -> {
            mFadeAnimationAlpha = (int) animation.getAnimatedValue();
            invalidate();
        });
        valueAnimator.setInterpolator(mStandardAccelerateInterpolator);
        valueAnimator.setStartDelay(mFadePatternAnimationDelayMs);
        valueAnimator.setDuration(mFadePatternAnimationDurationMs);
        return valueAnimator;
    }

    private void startCellActivatedAnimation(Cell cell) {
        startCellActivationAnimation(cell, CELL_ACTIVATE, /* fillInGap= */ false);
    }

    private void startCellDeactivatedAnimation(Cell cell, boolean fillInGap) {
        startCellActivationAnimation(cell, CELL_DEACTIVATE, /* fillInGap= */ fillInGap);
    }

    /**
     * Start cell animation.
     * @param cell The cell to be animated.
     * @param activate Whether the cell is being activated or deactivated.
     * @param fillInGap Whether the cell is a gap cell, i.e. filled in based on current pattern.
     */
    private void startCellActivationAnimation(Cell cell, int activate, boolean fillInGap) {
        final CellState cellState = mCellStates[cell.row][cell.column];

        // When mKeepDotActivated is true, don't cancel the previous animator since it would leave
        // a dot in an in-between size if the next dot is reached before the animation is finished.
        if (cellState.activationAnimator != null && !mKeepDotActivated) {
            cellState.activationAnimator.cancel();
        }
        AnimatorSet animatorSet = new AnimatorSet();

        // When running the line end animation (see doc for createLineEndAnimation), if cell is in:
        // - activate state - use finger position at the time of hit detection
        // - deactivate state - use current position where the end was last during initial animation
        // Note that deactivate state will only come if mKeepDotActivated is themed true.
        final float startX = activate == CELL_ACTIVATE ? mInProgressX : cellState.lineEndX;
        final float startY = activate == CELL_ACTIVATE ? mInProgressY : cellState.lineEndY;
        AnimatorSet.Builder animatorSetBuilder = animatorSet
                .play(createLineDisappearingAnimation())
                .with(createLineEndAnimation(cellState, startX, startY,
                        getCenterXForColumn(cell.column), getCenterYForRow(cell.row)));
        if (mDotSize != mDotSizeActivated) {
            animatorSetBuilder.with(createDotRadiusAnimation(cellState, activate, fillInGap));
        }
        if (mDotColor != mDotActivatedColor) {
            animatorSetBuilder.with(
                    createDotActivationColorAnimation(cellState, activate, fillInGap));
        }

        if (activate == CELL_ACTIVATE) {
            animatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    cellState.activationAnimator = null;
                    invalidate();
                }
            });
            cellState.activationAnimator = animatorSet;
        } else {
            animatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    cellState.deactivationAnimator = null;
                    invalidate();
                }
            });
            cellState.deactivationAnimator = animatorSet;
        }
        animatorSet.start();
    }

    private Animator createDotActivationColorAnimation(
            CellState cellState, int activate, boolean fillInGap) {
        ValueAnimator.AnimatorUpdateListener updateListener =
                valueAnimator -> {
                    cellState.activationAnimationProgress =
                            (float) valueAnimator.getAnimatedValue();
                    invalidate();
                };
        ValueAnimator activateAnimator = ValueAnimator.ofFloat(0f, 1f);
        ValueAnimator deactivateAnimator = ValueAnimator.ofFloat(1f, 0f);
        activateAnimator.addUpdateListener(updateListener);
        deactivateAnimator.addUpdateListener(updateListener);
        activateAnimator.setInterpolator(mFastOutSlowInInterpolator);
        deactivateAnimator.setInterpolator(mLinearOutSlowInInterpolator);

        // Align dot animation duration with line fade out animation.
        activateAnimator.setDuration(DOT_ACTIVATION_DURATION_MILLIS);
        deactivateAnimator.setDuration(DOT_ACTIVATION_DURATION_MILLIS);
        AnimatorSet set = new AnimatorSet();

        if (mKeepDotActivated && !fillInGap) {
            set.play(activate == CELL_ACTIVATE ? activateAnimator : deactivateAnimator);
        } else {
            // 'activate' ignored in this case, do full deactivate -> activate cycle
            set.play(deactivateAnimator)
                    .after(mLineFadeOutAnimationDelayMs + mLineFadeOutAnimationDurationMs
                            - DOT_ACTIVATION_DURATION_MILLIS * 2)
                    .after(activateAnimator);
        }

        return set;
    }

    /**
     * On the last frame before cell activates the end point of in progress line is not aligned
     * with dot center so we execute a short animation moving the end point to exact dot center.
     */
    private Animator createLineEndAnimation(final CellState state,
            final float startX, final float startY, final float targetX, final float targetY) {
        ValueAnimator valueAnimator = ValueAnimator.ofFloat(0, 1);
        valueAnimator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            state.lineEndX = (1 - t) * startX + t * targetX;
            state.lineEndY = (1 - t) * startY + t * targetY;
            invalidate();
        });
        valueAnimator.setInterpolator(mFastOutSlowInInterpolator);
        valueAnimator.setDuration(LINE_END_ANIMATION_DURATION_MILLIS);
        return valueAnimator;
    }

    /**
     * Starts animator to fade out a line segment. It does only invalidate because all the
     * transitions are applied in {@code onDraw} method.
     */
    private Animator createLineDisappearingAnimation() {
        ValueAnimator valueAnimator = ValueAnimator.ofFloat(0, 1);
        valueAnimator.addUpdateListener(animation -> invalidate());
        valueAnimator.setStartDelay(mLineFadeOutAnimationDelayMs);
        valueAnimator.setDuration(mLineFadeOutAnimationDurationMs);
        return valueAnimator;
    }

    private Animator createDotRadiusAnimation(CellState state, int activate, boolean fillInGap) {
        float defaultRadius = mDotSize / 2f;
        float activatedRadius = mDotSizeActivated / 2f;

        ValueAnimator.AnimatorUpdateListener animatorUpdateListener =
                animation -> {
                    state.radius = (float) animation.getAnimatedValue();
                    invalidate();
                };

        ValueAnimator activationAnimator = ValueAnimator.ofFloat(defaultRadius, activatedRadius);
        activationAnimator.addUpdateListener(animatorUpdateListener);
        activationAnimator.setInterpolator(mLinearOutSlowInInterpolator);
        activationAnimator.setDuration(DOT_RADIUS_INCREASE_DURATION_MILLIS);

        ValueAnimator deactivationAnimator = ValueAnimator.ofFloat(activatedRadius, defaultRadius);
        deactivationAnimator.addUpdateListener(animatorUpdateListener);
        deactivationAnimator.setInterpolator(mFastOutSlowInInterpolator);
        deactivationAnimator.setDuration(DOT_RADIUS_DECREASE_DURATION_MILLIS);

        AnimatorSet set = new AnimatorSet();
        if (mKeepDotActivated) {
            if (mFadePattern) {
                if (fillInGap) {
                    set.playSequentially(activationAnimator, deactivationAnimator);
                } else {
                    set.play(activate == CELL_ACTIVATE ? activationAnimator : deactivationAnimator);
                }
            } else if (activate == CELL_ACTIVATE) {
                set.play(activationAnimator);
            }
        } else {
            set.playSequentially(activationAnimator, deactivationAnimator);
        }
        return set;
    }

    @Nullable
    private Cell checkForNewHit(float x, float y) {
        Cell cellHit = detectCellHit(x, y);
        if (cellHit != null && !mPatternDrawLookup[cellHit.row][cellHit.column]) {
            return cellHit;
        }
        return null;
    }

    /** Helper method to find which cell a point maps to. */
    @Nullable
    private Cell detectCellHit(float x, float y) {
        for (int row = 0; row < mPatternSize; row++) {
            for (int column = 0; column < mPatternSize; column++) {
                float centerY = getCenterYForRow(row);
                float centerX = getCenterXForColumn(column);
                float hitRadiusSquared;

                if (mEnlargeVertex) {
                    // Maximize vertex dots' hit radius for the small screen.
                    // This eases users to draw more patterns with diagonal lines, while keeps
                    // drawing patterns with vertex dots easy.
                    hitRadiusSquared =
                            isVertex(row, column)
                                    ? (mDotHitMaxRadius * mDotHitMaxRadius)
                                    : (mDotHitRadius * mDotHitRadius);
                } else {
                    hitRadiusSquared = mDotHitRadius * mDotHitRadius;
                }

                if ((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)
                        < hitRadiusSquared) {
                    return Cell.of(row, column, mPatternSize);
                }
            }
        }
        return null;
    }

    private static boolean isVertex(int row, int column) {
        return !(row == 1 || column == 1);
    }

    private static boolean verifyMotionEventSourceType(MotionEvent event, int device) {
        final int source = event.getSource();
        return (source & device) == device;
    }

    private static boolean isCellIndexInRange(int index) {
        return index >= 0 && index < DOT_COUNT;
    }

    private boolean isCellIndexPartOfPattern(int index) {
        if (!isCellIndexInRange(index)) {
            return false;
        }
        return mPatternDrawLookup[index / 3][index % 3];
    }

    /**
     * Advances the current keyboard focus until an unselected cell is found
     *
     * @param start             Cell start index for the search.
     * @param increment         Step size that's used to advance to the next cell. Can be negative
     *                          for reverse traversal.
     * @param skipInitialCell   If the initial cell should be skipped
     * @return Index of the next available cell. Out of range if no cell is available
     */
    private int findNextFocusableCell(int start, int increment, boolean skipInitialCell) {
        if (skipInitialCell) {
            start += increment;
        }
        // Proceed as long as there are cells left and the current one is already part of
        // the pattern
        while (isCellIndexInRange(start) && isCellIndexPartOfPattern(start)) {
            start += increment;
        }
        return start;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction,
            @Nullable Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);

        if (!mClickInputSupported) {
            return;
        }

        if (gainFocus) {
            mExploreByTouchHelper.invalidateRoot();
            int next;
            switch (direction) {
                // Gain focus from bottom right. Search backwards
                case FOCUS_BACKWARD, FOCUS_UP, FOCUS_LEFT -> {
                    mFocusedCellIndex = DOT_COUNT - 1;
                    next = findNextFocusableCell(mFocusedCellIndex, -1, false);
                }
                // Gain focus from top left. Search forwards
                default -> {
                    mFocusedCellIndex = 0;
                    next = findNextFocusableCell(mFocusedCellIndex, 1, false);
                }
            }
            if (isCellIndexInRange(next)) {
                mFocusedCellIndex = next;
            }
            // Focus could be regained due to resize. Focus ring should only be shown on first tab
            // or enter key
            mFocusVisible = false;
            if (DEBUG_A11Y) {
                Log.v(TAG, "Gained dir " + direction + " newIndex " + mFocusedCellIndex
                        + " visible " + mFocusVisible);
            }
            final int virtualViewId = VIRTUAL_BASE_VIEW_ID + mFocusedCellIndex;
            mExploreByTouchHelper.sendEventForVirtualView(virtualViewId,
                    AccessibilityEvent.TYPE_VIEW_FOCUSED);
        } else {
            if (DEBUG_A11Y) Log.v(TAG, "Lost   dir " + direction);
            mFocusVisible = false;
            mExploreByTouchHelper.invalidateRoot();
        }
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!mClickInputSupported || !isEnabled() || !mInputEnabled) {
            return super.onKeyDown(keyCode, event);
        }

        if (keyCode == KeyEvent.KEYCODE_TAB
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Show focus if valid and hidden
            if (!mFocusVisible && isCellIndexInRange(mFocusedCellIndex)
                    && !isCellIndexPartOfPattern(mFocusedCellIndex)) {
                if (DEBUG_A11Y) Log.v(TAG, "TabArrow index " + mFocusedCellIndex + " shown");
                mFocusVisible = true;
                invalidate();
                final int virtualViewId = VIRTUAL_BASE_VIEW_ID + mFocusedCellIndex;
                mExploreByTouchHelper.sendEventForVirtualView(virtualViewId,
                        AccessibilityEvent.TYPE_VIEW_FOCUSED);
                return true;
            }

            // If focused cell is already selected, advance focus
            int next = -1;
            switch (keyCode) {
                case KeyEvent.KEYCODE_TAB -> {
                    next = findNextFocusableCell(mFocusedCellIndex,
                            event.isShiftPressed() ? -1 : 1, true);
                }
                case KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    next = findNextFocusableCell(mFocusedCellIndex,
                            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, true);
                    if ((mFocusedCellIndex / 3) != (next / 3)) {
                        next = -1;
                    }
                }
                case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    next = findNextFocusableCell(mFocusedCellIndex,
                            keyCode == KeyEvent.KEYCODE_DPAD_UP ? -3 : 3, true);
                }
                default -> {
                    next = -1;
                }
            }

            if (isCellIndexInRange(next)) {
                if (DEBUG_A11Y) Log.v(TAG, "TabArrow index " + next);
                mFocusedCellIndex = next;
                mFocusVisible = true;
                invalidate();
                final int virtualViewId = VIRTUAL_BASE_VIEW_ID + mFocusedCellIndex;
                mExploreByTouchHelper.sendEventForVirtualView(virtualViewId,
                        AccessibilityEvent.TYPE_VIEW_FOCUSED);
                return true;
            } else {
                if (DEBUG_A11Y) {
                    Log.v(TAG, "TabArrow index " + next + " invalid. Stays " + mFocusedCellIndex);
                }
            }
            // If no next valid cell exists for tab, focus will be lost and the focus indicator
            // hidden due to subsequent onFocusChanged
        } else if ((keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9)
                || (keyCode >= KeyEvent.KEYCODE_NUMPAD_1 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9)) {
            int index;
            if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) {
                index = keyCode - KeyEvent.KEYCODE_1;
            } else {
                index = keyCode - KeyEvent.KEYCODE_NUMPAD_1;
            }
            if (DEBUG_A11Y) Log.v(TAG, "Num index" + index + " visible " + mFocusVisible);
            if (handleActionKeyboard(index)) {
                mFocusedCellIndex = index;
                final int virtualViewId = VIRTUAL_BASE_VIEW_ID + mFocusedCellIndex;
                mExploreByTouchHelper.sendEventForVirtualView(virtualViewId,
                        AccessibilityEvent.TYPE_VIEW_FOCUSED);
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (DEBUG_A11Y) {
                Log.v(TAG, "Ent index " + mFocusedCellIndex + " visible " + mFocusVisible);
            }
            if (handleActionKeyboard(mFocusedCellIndex)) {
                return true;
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        if (AccessibilityManager.getInstance(mContext).isTouchExplorationEnabled()) {
            final int action = event.getAction();
            switch (action) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    event.setAction(MotionEvent.ACTION_DOWN);
                    break;
                case MotionEvent.ACTION_HOVER_MOVE:
                    event.setAction(MotionEvent.ACTION_MOVE);
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    event.setAction(MotionEvent.ACTION_UP);
                    break;
            }
            onTouchEvent(event);
            event.setAction(action);
        }
        return super.onHoverEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mInputEnabled || !isEnabled()) {
            return false;
        }

        final boolean sourceIsMouse = verifyMotionEventSourceType(event, InputDevice.SOURCE_MOUSE);

        // Unable to switch to desired input mode. Pattern could already be valid in click mode
        // for example.
        if (!handleInputMode(sourceIsMouse ? InputMode.Click : InputMode.Swipe)) {
            return false;
        }

        if (mFocusVisible) {
            mFocusVisible = false;
            invalidate();
        }

        if (mInputMode == InputMode.Click) {
            return switch (event.getAction()) {
                // Handle ACTION_DOWN event. Otherwise, the ACTION_UP event wouldn't be received
                case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> true;
                case MotionEvent.ACTION_UP -> {
                    handleActionMouseUp(event.getX(), event.getY());
                    yield true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    handleActionCancel();
                    yield true;
                }
                default -> false;
            };
        } else {
            return switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    handleActionDown(event);
                    yield true;
                }
                case MotionEvent.ACTION_UP -> {
                    handleActionUp();
                    yield true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    handleActionMove(event);
                    yield true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    handleActionCancel();
                    yield true;
                }
                default -> false;
            };
        }
    }

    private void switchInputMode(InputMode inputMode) {
        setPatternInProgress(false);
        resetPattern();
        mInputMode = inputMode;
    }

    /**
     * Calculate inputMode for an input event and handle mode switch if required
     *
     * @param newInputMode  Desired new input mode, e.g. InputMode.Click if input comes from
     *                      a source that should be handled as a sequential input like
     *                      keyboard or mouse.
     * @return              False if input should not be processed any further.
     */
    private boolean handleInputMode(InputMode newInputMode) {
        if (DEBUG_A11Y) {
            Log.v(TAG, "handleInputMode sourceInputMode="
                    + (newInputMode == InputMode.Swipe ? "Swipe" : "Click")
                    + " currentMode=" + (mInputMode == InputMode.Swipe ? "Swipe" : "Click"));
        }
        if (!mInputEnabled || !isEnabled()) {
            return false;
        }

        if (newInputMode == InputMode.Swipe || !mClickInputSupported) {
            if (mInputMode == InputMode.Swipe) {
                return true;
            }
            // Switch to swipe mode
            // Only if current pattern is not already valid
            if (mPattern.size() >= LockPatternUtils.MIN_LOCK_PATTERN_SIZE
                    && mPatternDisplayMode != DisplayMode.Wrong) {
                return false;
            }
            switchInputMode(InputMode.Swipe);
            return true;
        }

        if (newInputMode == InputMode.Click && mClickInputSupported) {
            if (mInputMode == InputMode.Click) {
                return true;
            }
            // Switch to click mode
            // Valid pattern is already preserved by enablement check above
            switchInputMode(InputMode.Click);
            return true;
        }

        //Should never be reached
        return false;
    }

    private void setPatternInProgress(boolean progress) {
        mPatternInProgress = progress;
        if (!mClickInputSupported) {
            mExploreByTouchHelper.invalidateRoot();
        }
    }

    private void handleActionMove(MotionEvent event) {
        // Handle all recent motion events so we don't skip any cells even when the device
        // is busy...
        final float radius = mPathWidth;
        final int historySize = event.getHistorySize();
        mTmpInvalidateRect.setEmpty();
        boolean invalidateNow = false;
        for (int i = 0; i < historySize + 1; i++) {
            final float x = i < historySize ? event.getHistoricalX(i) : event.getX();
            final float y = i < historySize ? event.getHistoricalY(i) : event.getY();
            Cell hitCell = detectAndAddHit(x, y);
            final int patternSize = mPattern.size();
            if (hitCell != null && patternSize == 1) {
                setPatternInProgress(true);
                notifyPatternStarted();
            }
            // note current x and y for rubber banding of in progress patterns
            final float dx = Math.abs(x - mInProgressX);
            final float dy = Math.abs(y - mInProgressY);
            if (dx > DRAG_THRESHOLD || dy > DRAG_THRESHOLD) {
                invalidateNow = true;
            }

            if (mPatternInProgress && patternSize > 0) {
                final Cell lastCell = mPattern.getLast();
                float lastCellCenterX = getCenterXForColumn(lastCell.column);
                float lastCellCenterY = getCenterYForRow(lastCell.row);

                // Adjust for drawn segment from last cell to (x,y). Radius accounts for line width.
                float left = Math.min(lastCellCenterX, x) - radius;
                float right = Math.max(lastCellCenterX, x) + radius;
                float top = Math.min(lastCellCenterY, y) - radius;
                float bottom = Math.max(lastCellCenterY, y) + radius;

                // Invalidate between the pattern's new cell and the pattern's previous cell
                if (hitCell != null) {
                    final float width = mSquareWidth * 0.5f;
                    final float height = mSquareHeight * 0.5f;
                    final float hitCellCenterX = getCenterXForColumn(hitCell.column);
                    final float hitCellCenterY = getCenterYForRow(hitCell.row);

                    left = Math.min(hitCellCenterX - width, left);
                    right = Math.max(hitCellCenterX + width, right);
                    top = Math.min(hitCellCenterY - height, top);
                    bottom = Math.max(hitCellCenterY + height, bottom);
                }

                // Invalidate between the pattern's last cell and the previous location
                mTmpInvalidateRect.union(Math.round(left), Math.round(top),
                        Math.round(right), Math.round(bottom));
            }
        }
        mInProgressX = event.getX();
        mInProgressY = event.getY();

        // To save updates, we only invalidate if the user moved beyond a certain amount.
        if (invalidateNow) {
            mInvalidate.union(mTmpInvalidateRect);
            invalidate(mInvalidate);
            mInvalidate.set(mTmpInvalidateRect);
        }
    }

    private void handleActionUp() {
        // report pattern detected
        if (!mPattern.isEmpty()) {
            setPatternInProgress(false);
            if (mKeepDotActivated) {
                // When mKeepDotActivated is true, cancelling dot animations and resetting dot radii
                // are handled in #resetPattern(), since we want to keep the dots activated until
                // the pattern are reset.
                deactivateLastCell();
            } else {
                // When mKeepDotActivated is false, cancelling animations and resetting dot radii
                // are handled here.
                cancelLineAnimations();
            }
            notifyPatternDetected();
            invalidate();
        }
        if (PROFILE_DRAWING) {
            if (mDrawingProfilingStarted) {
                Debug.stopMethodTracing();
                mDrawingProfilingStarted = false;
            }
        }
    }

    /**
     * Handles attempt to select a cell via Keyboard event.
     * @param index Index of the cell, 0 to DOT_CNT-1
     * @return Returns if the attempt was successful.
     */
    private boolean handleActionKeyboard(int index) {
        if (!isCellIndexInRange(index)
                || (isCellIndexPartOfPattern(index) && mPatternDisplayMode != DisplayMode.Wrong)
                || !handleInputMode(InputMode.Click)) {
            return false;
        }
        final float x = getCenterXForColumn(index % 3);
        final float y = getCenterYForRow(index / 3);
        handleActionMouseUp(x, y);
        return true;
    }

    private void handleActionMouseUp(float x, float y) {
        if (mPatternDisplayMode == DisplayMode.Wrong) {
            resetPattern();
        }

        if (mFocusVisible) {
            mFocusVisible = false;
            invalidate();
        }

        final int previousPatternSize = mPattern.size();
        final Cell hitCell = detectAndAddHit(x, y);
        if (hitCell != null) {
            if (previousPatternSize == 0) {
                setPatternInProgress(true);
                mPatternDisplayMode = DisplayMode.Correct;
                notifyPatternStarted();
            }
            notifyPatternDetected();
            mFocusedCellIndex = (hitCell.row * 3) + hitCell.column;
            mInProgressX = getCenterXForColumn(hitCell.column);
            mInProgressY = getCenterYForRow(hitCell.row);
            invalidate();
        }
        if (PROFILE_DRAWING) {
            if (!mDrawingProfilingStarted) {
                Debug.startMethodTracing("LockPatternDrawing");
                mDrawingProfilingStarted = true;
            }
        }
    }

    private void deactivateLastCell() {
        Cell lastCell = mPattern.getLast();
        startCellDeactivatedAnimation(lastCell, /* fillInGap= */ false);
    }

    private void cancelLineAnimations() {
        for (int i = 0; i < mPatternSize; i++) {
            for (int j = 0; j < mPatternSize; j++) {
                CellState state = mCellStates[i][j];
                if (state.activationAnimator != null) {
                    state.activationAnimator.cancel();
                    state.activationAnimator = null;
                    state.radius = mDotSize / 2f;
                    state.lineEndX = Float.MIN_VALUE;
                    state.lineEndY = Float.MIN_VALUE;
                    state.activationAnimationProgress = 0f;
                }
            }
        }
    }

    private void handleActionDown(MotionEvent event) {
        resetPattern();
        final float x = event.getX();
        final float y = event.getY();
        final Cell hitCell = detectAndAddHit(x, y);
        if (hitCell != null) {
            setPatternInProgress(true);
            mPatternDisplayMode = DisplayMode.Correct;
            notifyPatternStarted();
        } else if (mPatternInProgress) {
            setPatternInProgress(false);
        }
        if (hitCell != null) {
            final float startX = getCenterXForColumn(hitCell.column);
            final float startY = getCenterYForRow(hitCell.row);

            final float widthOffset = mSquareWidth / 2f;
            final float heightOffset = mSquareHeight / 2f;

            invalidate((int) (startX - widthOffset), (int) (startY - heightOffset),
                    (int) (startX + widthOffset), (int) (startY + heightOffset));
        }
        mInProgressX = x;
        mInProgressY = y;
        if (PROFILE_DRAWING) {
            if (!mDrawingProfilingStarted) {
                Debug.startMethodTracing("LockPatternDrawing");
                mDrawingProfilingStarted = true;
            }
        }
    }

    private void handleActionCancel() {
        if (mPatternInProgress) {
            setPatternInProgress(false);
            resetPattern();
        }
        if (PROFILE_DRAWING) {
            if (mDrawingProfilingStarted) {
                Debug.stopMethodTracing();
                mDrawingProfilingStarted = false;
            }
        }
    }

    /**
     * Change theme colors
     * @param regularColor The dot color
     * @param successColor Color used when pattern is correct
     * @param errorColor Color used when authentication fails
     */
    public void setColors(int regularColor, int successColor, int errorColor) {
        mRegularColor = regularColor;
        mErrorColor = errorColor;
        mSuccessColor = successColor;
        mPathPaint.setColor(regularColor);
        invalidate();
    }

    /**
     * Change dot colors
     */
    public void setDotColors(int dotColor, int dotActivatedColor) {
        mDotColor = dotColor;
        mFocusPaint.setColor(dotColor);
        mDotActivatedColor = dotActivatedColor;
        invalidate();
    }

    /**
     * Keeps dot activated until the next dot gets activated.
     */
    public void setKeepDotActivated(boolean keepDotActivated) {
        mKeepDotActivated = keepDotActivated;
    }

    /**
     * Set dot sizes in dp
     */
    public void setDotSizes(int dotSizeDp, int dotSizeActivatedDp) {
        mDotSize = dotSizeDp;
        mDotSizeActivated = dotSizeActivatedDp;
    }

    /**
     * Set the stroke width of the pattern line.
     */
    public void setPathWidth(int pathWidthDp) {
        mPathWidth = pathWidthDp;
        mPathPaint.setStrokeWidth(mPathWidth);
    }

    private float getCenterXForColumn(int column) {
        return mPaddingLeft + column * mSquareWidth + mSquareWidth / 2f;
    }

    private float getCenterYForRow(int row) {
        return mPaddingTop + row * mSquareHeight + mSquareHeight / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final ArrayList<Cell> pattern = mPattern;
        final int count = pattern.size();
        final boolean[][] drawLookup = mPatternDrawLookup;

        if (mPatternDisplayMode == DisplayMode.Animate) {

            // figure out which circles to draw

            // + 1 so we pause on complete pattern
            final int oneCycle = (count + 1) * MILLIS_PER_CIRCLE_ANIMATING;
            final int spotInCycle = (int) (SystemClock.elapsedRealtime() -
                    mAnimatingPeriodStart) % oneCycle;
            final int numCircles = spotInCycle / MILLIS_PER_CIRCLE_ANIMATING;

            clearPatternDrawLookup();
            for (int i = 0; i < numCircles; i++) {
                final Cell cell = pattern.get(i);
                drawLookup[cell.getRow()][cell.getColumn()] = true;
            }

            // figure out in progress portion of ghosting line

            final boolean needToUpdateInProgressPoint = numCircles > 0
                    && numCircles < count;

            if (needToUpdateInProgressPoint) {
                final float percentageOfNextCircle =
                        ((float) (spotInCycle % MILLIS_PER_CIRCLE_ANIMATING)) /
                                MILLIS_PER_CIRCLE_ANIMATING;

                final Cell currentCell = pattern.get(numCircles - 1);
                final float centerX = getCenterXForColumn(currentCell.column);
                final float centerY = getCenterYForRow(currentCell.row);

                final Cell nextCell = pattern.get(numCircles);
                final float dx = percentageOfNextCircle *
                        (getCenterXForColumn(nextCell.column) - centerX);
                final float dy = percentageOfNextCircle *
                        (getCenterYForRow(nextCell.row) - centerY);
                mInProgressX = centerX + dx;
                mInProgressY = centerY + dy;
            }
            // TODO: Infinite loop here...
            invalidate();
        }

        final Path currentPath = mCurrentPath;
        currentPath.rewind();

        // TODO: the path should be created and cached every time we hit-detect a cell
        // only the last segment of the path should be computed here

        // draw the path of the pattern (unless we are in stealth mode)
        final boolean drawWrongPath = mPatternDisplayMode == DisplayMode.Wrong && mShowErrorPath;
        final boolean drawPath = (!mInStealthMode && mPatternDisplayMode != DisplayMode.Wrong)
                || drawWrongPath;

        if (drawPath && !mFadeClear) {
            mPathPaint.setColor(getCurrentColor(true /* partOfPattern */));

            boolean anyCircles = false;
            float lastX = 0f;
            float lastY = 0f;
            long elapsedRealtime = SystemClock.elapsedRealtime();
            for (int i = 0; i < count; i++) {
                Cell cell = pattern.get(i);

                // only draw the part of the pattern stored in
                // the lookup table (this is only different in the case
                // of animation).
                if (!drawLookup[cell.row][cell.column]) {
                    break;
                }
                anyCircles = true;

                if (mLineFadeStart[i] == 0) {
                    mLineFadeStart[i] = SystemClock.elapsedRealtime();
                }

                float centerX = getCenterXForColumn(cell.column);
                float centerY = getCenterYForRow(cell.row);
                if (i != 0) {
                    CellState state = mCellStates[cell.row][cell.column];
                    currentPath.rewind();
                    float endX;
                    float endY;
                    if (state.lineEndX != Float.MIN_VALUE && state.lineEndY != Float.MIN_VALUE) {
                        endX = state.lineEndX;
                        endY = state.lineEndY;
                    } else {
                        endX = centerX;
                        endY = centerY;
                    }
                    drawLineSegment(canvas, /* startX = */ lastX, /* startY = */ lastY, endX, endY,
                            mLineFadeStart[i], elapsedRealtime);

                    Path tempPath = new Path();
                    tempPath.moveTo(lastX, lastY);
                    tempPath.lineTo(centerX, centerY);
                    mPatternPath.addPath(tempPath);
                }
                lastX = centerX;
                lastY = centerY;
            }

            // draw path markup already around first cell in sequential input modes
            if (count == 1 && mInputMode != InputMode.Swipe) {
                final Cell cell = pattern.getFirst();
                if (mFadePattern) {
                    CellState cellState = mCellStates[cell.row][cell.column];
                    mPathPaint.setAlpha((int) (cellState.activationAnimationProgress * 255f));
                } else {
                    mPathPaint.setAlpha(255);
                }
                drawLinePoint(canvas, cell);
            }

            // draw last in progress section
            if ((mPatternInProgress || mPatternDisplayMode == DisplayMode.Animate)
                    && anyCircles) {
                currentPath.rewind();
                currentPath.moveTo(lastX, lastY);
                currentPath.lineTo(mInProgressX, mInProgressY);

                mPathPaint.setAlpha((int) (calculateLastSegmentAlpha(
                        mInProgressX, mInProgressY, lastX, lastY) * 255f));
                canvas.drawPath(currentPath, mPathPaint);
            }
        }

        if (mFadeClear) {
            mPathPaint.setAlpha(mFadeAnimationAlpha);
            if (count == 1 && mInputMode != InputMode.Swipe) {
                final Cell cell = pattern.getFirst();
                drawLinePoint(canvas, cell);
            }
            canvas.drawPath(mPatternPath, mPathPaint);
        }

        // draw the circles
        if (mVisibleDots) {
            for (int i = 0; i < mPatternSize; i++) {
                float centerY = getCenterYForRow(i);
                for (int j = 0; j < mPatternSize; j++) {
                    CellState cellState = mCellStates[i][j];
                    float centerX = getCenterXForColumn(j);
                    float translationY = cellState.translationY;

                    // Draw focus ring
                    if (!mInStealthMode && mFocusVisible && (i * 3) + j == mFocusedCellIndex) {
                        final float radius = (mDotSize / 2f) + FOCUS_RING_GAP + (FOCUS_RING_WIDTH / 2f);
                        canvas.drawCircle(centerX, centerY, radius, mFocusPaint);
                    }

                    if (mUseLockPatternDrawable) {
                        drawCellDrawable(canvas, i, j, cellState.radius, drawLookup[i][j]);
                    } else {
                        if (isHardwareAccelerated() && cellState.hwAnimating) {
                            RecordingCanvas recordingCanvas = (RecordingCanvas) canvas;
                            recordingCanvas.drawCircle(cellState.hwCenterX, cellState.hwCenterY,
                                    cellState.hwRadius, cellState.hwPaint);
                        } else {
                            drawCircle(canvas, centerX, centerY + translationY,
                                    cellState.radius, drawLookup[i][j], cellState.alpha,
                                    cellState.activationAnimationProgress);
                        }
                    }
                }
            }
        }
    }

    private void drawLinePoint(Canvas canvas, Cell cell) {
        final float x = getCenterXForColumn(cell.column);
        final float y = getCenterYForRow(cell.row);
        canvas.drawPoint(x, y, mPathPaint);
    }

    private void drawLineSegment(Canvas canvas, float startX, float startY, float endX, float endY,
            long lineFadeStart, long elapsedRealtime) {
        float fadeAwayProgress;
        final boolean drawWrongPath = mPatternDisplayMode == DisplayMode.Wrong && mShowErrorPath;
        if (mFadePattern && !drawWrongPath) {
            if (elapsedRealtime - lineFadeStart
                    >= mLineFadeOutAnimationDelayMs + mLineFadeOutAnimationDurationMs) {
                // Time for this segment animation is out so we don't need to draw it.
                return;
            }
            // Set this line segment to fade away animated.
            fadeAwayProgress = Math.max(
                    ((float) (elapsedRealtime - lineFadeStart - mLineFadeOutAnimationDelayMs))
                            / mLineFadeOutAnimationDurationMs, 0f);
            drawFadingAwayLineSegment(canvas, startX, startY, endX, endY, fadeAwayProgress);
        } else {
            mPathPaint.setAlpha(255);
            canvas.drawLine(startX, startY, endX, endY, mPathPaint);
        }
    }

    private void drawFadingAwayLineSegment(Canvas canvas, float startX, float startY, float endX,
            float endY, float fadeAwayProgress) {
        mPathPaint.setAlpha((int) (255 * (1 - fadeAwayProgress)));

        // To draw gradient segment we use mFadeOutGradientShader which has immutable coordinates
        // thus we will need to translate and rotate the canvas.
        mPathPaint.setShader(mFadeOutGradientShader);
        canvas.save();

        // First translate canvas to gradient middle point.
        float gradientMidX = endX * fadeAwayProgress + startX * (1 - fadeAwayProgress);
        float gradientMidY = endY * fadeAwayProgress + startY * (1 - fadeAwayProgress);
        canvas.translate(gradientMidX, gradientMidY);

        // Then rotate it to the direction of the segment.
        double segmentAngleRad = Math.atan((endY - startY) / (endX - startX));
        float segmentAngleDegrees = (float) Math.toDegrees(segmentAngleRad);
        if (endX - startX < 0) {
            // Arc tangent gives us angle degrees [-90; 90] thus to cover [90; 270] degrees we
            // need this hack.
            segmentAngleDegrees += 180f;
        }
        canvas.rotate(segmentAngleDegrees);

        // Pythagoras theorem.
        float segmentLength = (float) Math.hypot(endX - startX, endY - startY);

        // Draw the segment in coordinates aligned with shader coordinates.
        canvas.drawLine(/* startX= */ -segmentLength * fadeAwayProgress, /* startY= */
                0,/* stopX= */ segmentLength * (1 - fadeAwayProgress), /* stopY= */ 0, mPathPaint);

        canvas.restore();
        mPathPaint.setShader(null);
    }

    private float calculateLastSegmentAlpha(float x, float y, float lastX, float lastY) {
        float diffX = x - lastX;
        float diffY = y - lastY;
        float dist = (float) Math.sqrt(diffX*diffX + diffY*diffY);
        float frac = dist/mSquareWidth;
        return Math.min(1f, Math.max(0f, (frac - 0.3f) * 4f));
    }

    private int getDotColor() {
        if (mInStealthMode) {
            // Always use the default color in this case
            return mDotColor;
        } else if (mPatternDisplayMode == DisplayMode.Wrong) {
            // the pattern is wrong
            return mErrorColor;
        }
        return mDotColor;
    }

    private int getCurrentColor(boolean partOfPattern) {
        if (!partOfPattern
                || (mInStealthMode && mPatternDisplayMode != DisplayMode.Wrong)
                || (mPatternDisplayMode == DisplayMode.Wrong && !mShowErrorPath)) {
            // unselected circle
            return mRegularColor;
        } else if (mPatternDisplayMode == DisplayMode.Wrong) {
            // the pattern is wrong
            return mErrorColor;
        } else if (mPatternInProgress) {
            return mRegularColor;
        } else if (mPatternDisplayMode == DisplayMode.Correct ||
                mPatternDisplayMode == DisplayMode.Animate) {
            return mSuccessColor;
        } else {
            throw new IllegalStateException("unknown display mode " + mPatternDisplayMode);
        }
    }

    /**
     * @param partOfPattern Whether this circle is part of the pattern.
     */
    private void drawCircle(Canvas canvas, float centerX, float centerY, float radius,
            boolean partOfPattern, float alpha, float activationAnimationProgress) {
        final boolean drawWrongPath = mPatternDisplayMode == DisplayMode.Wrong && mShowErrorPath;
        if (mFadePattern && !mInStealthMode && !drawWrongPath) {
            int resultColor = ColorUtils.blendARGB(mDotColor, mDotActivatedColor,
                    /* ratio= */ activationAnimationProgress);
            mPaint.setColor(resultColor);
        } else if (!mFadePattern && partOfPattern){
            mPaint.setColor(mDotActivatedColor);
        } else {
            mPaint.setColor(getDotColor());
        }
        mPaint.setAlpha((int) (alpha * 255));
        canvas.drawCircle(centerX, centerY, radius, mPaint);
    }

    /**
     * @param partOfPattern Whether this circle is part of the pattern.
     */
    private void drawCellDrawable(Canvas canvas, int i, int j, float radius,
            boolean partOfPattern) {
        Rect dst = new Rect(
            (int) (mPaddingLeft + j * mSquareWidth),
            (int) (mPaddingTop + i * mSquareHeight),
            (int) (mPaddingLeft + (j + 1) * mSquareWidth),
            (int) (mPaddingTop + (i + 1) * mSquareHeight));
        float scale = radius / (mDotSize / 2);

        // Only draw on this square with the appropriate scale.
        canvas.save();
        canvas.clipRect(dst);
        canvas.scale(scale, scale, dst.centerX(), dst.centerY());
        if (!partOfPattern || scale > 1) {
            mNotSelectedDrawable.draw(canvas);
        } else {
            mSelectedDrawable.draw(canvas);
        }
        canvas.restore();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        byte[] patternBytes = LockPatternUtils.patternToByteArray(mPattern, mPatternSize);
        String patternString = patternBytes != null ? new String(patternBytes) : null;
        return new SavedState(superState,
                patternString,
                mPatternDisplayMode.ordinal(),
                mPatternSize, mInputEnabled, mInputMode.ordinal(), mInStealthMode,
                mPatternInProgress, mVisibleDots, mShowErrorPath);
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        final SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        setPattern(
                DisplayMode.Correct,
                LockPatternUtils.byteArrayToPattern(ss.getSerializedPattern().getBytes(),
                        ss.getPatternSize()));
        mPatternDisplayMode = DisplayMode.values()[ss.getDisplayMode()];
        mPatternSize = ss.getPatternSize();
        mInputEnabled = ss.isInputEnabled();
        mInputMode = InputMode.values()[ss.getInputMode()];
        mInStealthMode = ss.isInStealthMode();
        mPatternInProgress = ss.isPatternInProgress();
        mVisibleDots = ss.isVisibleDots();
        mShowErrorPath = ss.isShowErrorPath();
        updateFocusable();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        setSystemGestureExclusionRects(List.of(new Rect(left, top, right, bottom)));
    }

    /**
     * The parcelable for saving and restoring a lock pattern view.
     */
    private static class SavedState extends BaseSavedState {

        private final String mSerializedPattern;
        private final int mDisplayMode;
        private final byte mPatternSize;
        private final boolean mInputEnabled;
        private final int mInputMode;
        private final boolean mInStealthMode;
        private final boolean mPatternInProgress;
        private final boolean mVisibleDots;
        private final boolean mShowErrorPath;

        /**
         * Constructor called from {@link LockPatternView#onSaveInstanceState()}
         */
        @UnsupportedAppUsage
        private SavedState(Parcelable superState, String serializedPattern, int displayMode,
                byte patternSize, boolean inputEnabled, int inputMode, boolean inStealthMode,
                boolean patternInProgress, boolean visibleDots, boolean showErrorPath) {
            super(superState);
            mSerializedPattern = serializedPattern;
            mDisplayMode = displayMode;
            mPatternSize = patternSize;
            mInputEnabled = inputEnabled;
            mInputMode = inputMode;
            mInStealthMode = inStealthMode;
            mPatternInProgress = patternInProgress;
            mVisibleDots = visibleDots;
            mShowErrorPath = showErrorPath;
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        @UnsupportedAppUsage
        private SavedState(Parcel in) {
            super(in);
            mSerializedPattern = in.readString();
            mDisplayMode = in.readInt();
            mPatternSize = (byte) in.readByte();
            mInputEnabled = (Boolean) in.readValue(null);
            mInputMode = in.readInt();
            mInStealthMode = (Boolean) in.readValue(null);
            mPatternInProgress = in.readBoolean();
            mVisibleDots = (Boolean) in.readValue(null);
            mShowErrorPath = (Boolean) in.readValue(null);
        }

        String getSerializedPattern() {
            return mSerializedPattern;
        }

        int getDisplayMode() {
            return mDisplayMode;
        }

        byte getPatternSize() {
            return mPatternSize;
        }

        boolean isInputEnabled() {
            return mInputEnabled;
        }

        int getInputMode() {
            return mInputMode;
        }

        boolean isInStealthMode() {
            return mInStealthMode;
        }

        boolean isPatternInProgress() {
            return mPatternInProgress;
        }

        boolean isVisibleDots() {
            return mVisibleDots;
        }

        boolean isShowErrorPath() {
            return mShowErrorPath;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeString(mSerializedPattern);
            dest.writeInt(mDisplayMode);
            dest.writeByte(mPatternSize);
            dest.writeValue(mInputEnabled);
            dest.writeInt(mInputMode);
            dest.writeValue(mInStealthMode);
            dest.writeBoolean(mPatternInProgress);
            dest.writeValue(mVisibleDots);
            dest.writeValue(mShowErrorPath);
        }

        @SuppressWarnings({ "unused", "hiding" }) // Found using reflection
        public static final Parcelable.Creator<SavedState> CREATOR =
                new Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    private final class PatternExploreByTouchHelper extends ExploreByTouchHelper {
        private AccessibilityNodeProvider mAccessibilityNodeProvider = null;
        private Rect mTempRect = new Rect();

        class VirtualViewContainer {
            public VirtualViewContainer(CharSequence description) {
                this.description = description;
            }
            CharSequence description;
        };

        public PatternExploreByTouchHelper(View forView) {
            super(forView);
        }

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
            if (!mClickInputSupported) {
                return super.getAccessibilityNodeProvider(host);
            }
            if (mAccessibilityNodeProvider != null) {
                return mAccessibilityNodeProvider;
            }
            final AccessibilityNodeProvider provider = super.getAccessibilityNodeProvider(host);
            mAccessibilityNodeProvider = new AccessibilityNodeProvider() {
                @Override
                public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
                    return provider.createAccessibilityNodeInfo(virtualViewId);
                }

                @Override
                public boolean performAction(int virtualViewId, int action, Bundle arguments) {
                    boolean handled = provider.performAction(virtualViewId, action, arguments);
                    // Move focus ring together with accessibility focus frame in case user
                    // moves focus using left/right swipe
                    if (handled && action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                        int index = virtualViewId - VIRTUAL_BASE_VIEW_ID;
                        if (isCellIndexInRange(index)) {
                            final int oldIndex = mFocusedCellIndex;
                            mFocusedCellIndex = index;
                            mFocusVisible = mInputEnabled && isEnabled()
                                    && !isCellIndexPartOfPattern(index);
                            invalidate();
                            invalidateVirtualView(VIRTUAL_BASE_VIEW_ID + oldIndex);
                            invalidateVirtualView(VIRTUAL_BASE_VIEW_ID + mFocusedCellIndex);
                        }
                    } else if (handled && action
                            == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                        mFocusVisible = false;
                        invalidate();
                    }
                    return handled;
                }
            };
            return mAccessibilityNodeProvider;
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            // This must use the same hit logic for the screen to ensure consistency whether
            // accessibility is on or off.
            return getVirtualViewIdForHit(x, y);
        }

        @Override
        protected void getVisibleVirtualViews(IntArray virtualViewIds) {
            if (DEBUG_A11Y) Log.v(TAG, "getVisibleVirtualViews(len=" + virtualViewIds.size() + ")");
            if (!mPatternInProgress && !mClickInputSupported) {
                return;
            }
            for (int i = VIRTUAL_BASE_VIEW_ID; i < VIRTUAL_BASE_VIEW_ID + 9; i++) {
                // Add all views. As views are added to the pattern, we remove them
                // from notification by making them non-clickable below.
                virtualViewIds.add(i);
            }
        }

        @Override
        protected void onPopulateEventForVirtualView(int virtualViewId, AccessibilityEvent event) {
            if (DEBUG_A11Y) Log.v(TAG, "onPopulateEventForVirtualView(" + virtualViewId + ")");
            if (!isCellIndexInRange(virtualViewId - VIRTUAL_BASE_VIEW_ID)) {
                return;
            }
            // Announce this view
            event.getText().add(getTextForVirtualView(virtualViewId));
        }

        @Override
        public void onPopulateAccessibilityEvent(@NonNull View host,
                @NonNull AccessibilityEvent event) {
            super.onPopulateAccessibilityEvent(host, event);
            if (!mPatternInProgress && !mClickInputSupported) {
                CharSequence contentDescription = getContext().getText(
                        com.android.internal.R.string.lockscreen_access_pattern_area);
                event.setContentDescription(contentDescription);
            }
        }

        @Override
        protected void onPopulateNodeForVirtualView(int virtualViewId, AccessibilityNodeInfo node) {
            if (DEBUG_A11Y) Log.v(TAG, "onPopulateNodeForVirtualView(view=" + virtualViewId + ")");

            // Node and event text and content descriptions are usually
            // identical, so we'll use the exact same string as before.
            CharSequence text = getTextForVirtualView(virtualViewId);
            node.setText(text);
            node.setContentDescription(text);

            if (mPatternInProgress || mClickInputSupported) {
                node.setFocusable(true);
                node.setFocused(virtualViewId - VIRTUAL_BASE_VIEW_ID == mFocusedCellIndex);
                node.setVisibleToUser(true);

                if (isClickable(virtualViewId)) {
                    // Mark this node as of interest by making it clickable.
                    node.addAction(AccessibilityAction.ACTION_CLICK);
                    node.setClickable(true);
                }
            }

            // Compute bounds for this object
            final Rect bounds = getBoundsForVirtualView(virtualViewId);
            if (DEBUG_A11Y) Log.v(TAG, "bounds:" + bounds.toString());
            node.setBoundsInParent(bounds);
        }

        private boolean isClickable(int virtualViewId) {
            if (!isEnabled() || !mInputEnabled) {
                return false;
            }
            // Dots are clickable if they're not part of the current pattern.
            final int virtualId = virtualViewId - VIRTUAL_BASE_VIEW_ID;
            if (virtualViewId != ExploreByTouchHelper.INVALID_ID && isCellIndexInRange(virtualId)) {
                return !isCellIndexPartOfPattern(virtualId);
            }
            return false;
        }

        @Override
        protected boolean onPerformActionForVirtualView(int virtualViewId, int action,
                Bundle arguments) {
            if (DEBUG_A11Y) Log.v(TAG, "onPerformActionForVirtualView(id=" + virtualViewId
                    + ", action=" + action);
            return switch (action) {
                case AccessibilityNodeInfo.ACTION_CLICK ->
                    // Click handling should be consistent with
                    // onTouchEvent(). This ensures that the view works the
                    // same whether accessibility is turned on or off.
                    onItemClicked(virtualViewId);
                default -> {
                    if (DEBUG_A11Y) {
                        Log.v(TAG, "*** action not handled in "
                                + "onPerformActionForVirtualView(viewId="
                                + virtualViewId + "action=" + action + ")");
                    }
                    yield false;
                }
            };
        }

        boolean onItemClicked(int index) {
            if (DEBUG_A11Y) Log.v(TAG, "onItemClicked(" + index + ")");

            if (!mClickInputSupported) {
                // Since the item's checked state is exposed to accessibility
                // services through its AccessibilityNodeInfo, we need to invalidate
                // the item's virtual view. At some point in the future, the
                // framework will obtain an updated version of the virtual view.
                invalidateVirtualView(index);

                // We need to let the framework know what type of event
                // happened. Accessibility services may use this event to provide
                // appropriate feedback to the user.
                sendEventForVirtualView(index, AccessibilityEvent.TYPE_VIEW_CLICKED);

                return true;
            } else {
                final int cellIndex = index - VIRTUAL_BASE_VIEW_ID;
                return handleActionKeyboard(cellIndex);
            }
        }

        private Rect getBoundsForVirtualView(int virtualViewId) {
            int ordinal = virtualViewId - VIRTUAL_BASE_VIEW_ID;
            final Rect bounds = mTempRect;
            final int row = ordinal / 3;
            final int col = ordinal % 3;
            float centerX = getCenterXForColumn(col);
            float centerY = getCenterYForRow(row);
            float cellHitRadius = mDotHitRadius;
            bounds.left = (int) (centerX - cellHitRadius);
            bounds.right = (int) (centerX + cellHitRadius);
            bounds.top = (int) (centerY - cellHitRadius);
            bounds.bottom = (int) (centerY + cellHitRadius);
            return bounds;
        }

        private CharSequence getTextForVirtualView(int virtualViewId) {
            final Resources res = getResources();
            final int virtualId = virtualViewId - VIRTUAL_BASE_VIEW_ID;
            if (!mClickInputSupported || (virtualViewId != ExploreByTouchHelper.INVALID_ID
                    && isCellIndexInRange(virtualId) && isCellIndexPartOfPattern(virtualId))) {
                return res.getString(R.string.lockscreen_access_pattern_cell_added_verbose,
                        virtualViewId);
            } else {
                return res.getString(R.string.lockscreen_access_pattern_cell_verbose,
                        virtualViewId);
            }
        }

        /**
         * Helper method to find which cell a point maps to
         * if there's no hit.
         * @param x touch position x
         * @param y touch position y
         * @return VIRTUAL_BASE_VIEW_ID+id or 0 if no view was hit
         */
        private int getVirtualViewIdForHit(float x, float y) {
            Cell cellHit = detectCellHit(x, y);
            if (cellHit == null) {
                return ExploreByTouchHelper.INVALID_ID;
            }
            int dotId = (cellHit.row * 3 + cellHit.column) + VIRTUAL_BASE_VIEW_ID;
            boolean dotAvailable = mPatternDrawLookup[cellHit.row][cellHit.column];
            if (DEBUG_A11Y) Log.v(TAG, "getVirtualViewIdForHit(" + x + "," + y + ") => "
                    + dotId + " avail=" + dotAvailable);
            return dotId;
        }
    }
}
