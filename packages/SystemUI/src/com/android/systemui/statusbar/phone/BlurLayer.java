/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.android.systemui.statusbar.phone;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

class BlurLayer {
    private static final boolean DEBUG = true;
    private static final String TAG = BlurLayer.class.getSimpleName();

    /** Actual surface that blurs */
    private SurfaceControl mBlurSurface;

    /** Last value passed to mBlurSurface.setBlur() */
    private float mBlur = 0;

    /** Last value passed to mBlurSurface.setLayer() */
    private int mLayer = -1;

    /** Next values to pass to mBlurSurface.setPosition() and mBlurSurface.setSize() */
    private final Rect mBounds = new Rect();

    /** Last values passed to mBlurSurface.setPosition() and mBlurSurface.setSize() */
    private final Rect mLastBounds = new Rect();

    /** True after mBlurSurface.show() has been called, false after mBlurSurface.hide(). */
    private boolean mShowing = false;

    /** Value of mBlur when beginning transition to mTargetBlur */
    private float mStartBlur = 0;

    /** Final value of mBlur following transition */
    private float mTargetBlur = 0;

    /** Time in units of SystemClock.uptimeMillis() at which the current transition started */
    private long mStartTime;

    /** Time in milliseconds to take to transition from mStartBlur to mTargetBlur */
    private long mDuration;

    private final String mName;

    private boolean mDestroyed = false;

    BlurLayer(String name) {
        mName = name;
    }

    private void constructSurface() {
        SurfaceControl.openTransaction();
        try {
            mBlurSurface = new SurfaceControl(new SurfaceSession(), mName,
                    16, 16, PixelFormat.OPAQUE,
                    SurfaceControl.FX_SURFACE_BLUR | SurfaceControl.HIDDEN);
            mBlurSurface.setLayerStack(0);
            adjustBounds();
            adjustBlur(mBlur);
            adjustLayer(mLayer);
        } catch (Exception e) {
            Slog.e(TAG, "Exception creating Blur surface", e);
        } finally {
            SurfaceControl.closeTransaction();
        }

    }

    /** @param bounds The new bounds to set */
    void setBounds(Rect bounds) {
        mBounds.set(bounds);
        if (isBlurring() && !mLastBounds.equals(bounds)) {
            try {
                SurfaceControl.openTransaction();
                adjustBounds();
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failure setting size", e);
            } finally {
                SurfaceControl.closeTransaction();
            }
        }
    }

    /**
     * NOTE: Must be called with Surface transaction open.
     */
    private void adjustBounds() {
        if (mBlurSurface != null) {
            mBlurSurface.setPosition(mBounds.left, mBounds.top);
            mBlurSurface.setSize(mBounds.width(), mBounds.height());
        }

        mLastBounds.set(mBounds);
    }

    void setLayer(int layer) {
        if (mLayer == layer) {
            return;
        }
        mLayer = layer;
        adjustLayer(layer);
    }

    private void adjustLayer(int layer) {
        if (mBlurSurface != null) {
            mBlurSurface.setLayer(layer);
        }
    }

    /** Return true if blur layer is showing */
    private boolean isBlurring() {
        return mTargetBlur != 0;
    }

    /** Return true if in a transition period */
    private boolean isAnimating() {
        return mTargetBlur != mBlur;
    }

    void setBlur(float blur) {
        if (mBlur == blur) {
            return;
        }
        mBlur = blur;
        adjustBlur(blur);
    }

    private void adjustBlur(float blur) {
        if (DEBUG) Slog.v(TAG, "setBlur blur=" + blur);
        try {
            if (mBlurSurface != null) {
                mBlurSurface.setBlur(blur);
            }
            if (blur == 0 && mShowing) {
                if (DEBUG) Slog.v(TAG, "setBlur hiding");
                if (mBlurSurface != null) {
                    mBlurSurface.hide();
                    mShowing = false;
                }
            } else if (blur > 0 && !mShowing) {
                if (DEBUG) Slog.v(TAG, "setBlur showing");
                if (mBlurSurface != null) {
                    mBlurSurface.show();
                    mShowing = true;
                }
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failure setting blur immediately", e);
        }
    }

    /**
     * @param duration The time to test.
     * @return True if the duration would lead to an earlier end to the current animation.
     */
    private boolean durationEndsEarlier(long duration) {
        return SystemClock.uptimeMillis() + duration < mStartTime + mDuration;
    }

    /** Jump to the end of the animation.
     * NOTE: Must be called with Surface transaction open. */
    void show() {
        if (isAnimating()) {
            if (DEBUG) Slog.v(TAG, "show: immediate");
            show(mLayer, mTargetBlur, 0);
        }
    }

    /**
     * Begin an animation to a new blur value.
     * NOTE: Must be called with Surface transaction open.
     *
     * @param layer The layer to set the surface to.
     * @param blur The blur value to end at.
     * @param duration How long to take to get there in milliseconds.
     */
    void show(int layer, float blur, long duration) {
        if (DEBUG) Slog.v(TAG, "show: layer=" + layer + " blur=" + blur
                + " duration=" + duration + ", mDestroyed=" + mDestroyed);
        if (mDestroyed) {
            Slog.e(TAG, "show: no Surface");
            // Make sure isAnimating() returns false.
            mTargetBlur = mBlur = 0;
            return;
        }

        if (mBlurSurface == null) {
            constructSurface();
        }

        if (!mLastBounds.equals(mBounds)) {
            adjustBounds();
        }
        setLayer(layer);

        long curTime = SystemClock.uptimeMillis();
        final boolean animating = isAnimating();
        if ((animating && (mTargetBlur != blur || durationEndsEarlier(duration)))
                || (!animating && mBlur != blur)) {
            if (duration <= 0) {
                // No animation required, just set values.
                setBlur(blur);
            } else {
                // Start or continue animation with new parameters.
                mStartBlur = mBlur;
                mStartTime = curTime;
                mDuration = duration;
            }
        }
        mTargetBlur = blur;
        if (DEBUG) Slog.v(TAG, "show: mStartBlur=" + mStartBlur + " mStartTime="
                + mStartTime + " mTargetBlur=" + mTargetBlur);
    }

    /** Immediate hide.
     * NOTE: Must be called with Surface transaction open. */
    void hide() {
        if (mShowing) {
            if (DEBUG) Slog.v(TAG, "hide: immediate");
            hide(0);
        }
    }

    /**
     * Gradually fade to transparent.
     * NOTE: Must be called with Surface transaction open.
     *
     * @param duration Time to fade in milliseconds.
     */
    void hide(long duration) {
        if (mShowing && (mTargetBlur != 0 || durationEndsEarlier(duration))) {
            if (DEBUG) Slog.v(TAG, "hide: duration=" + duration);
            show(mLayer, 0, duration);
        }
    }

    /** Cleanup */
    void destroySurface() {
        if (DEBUG) Slog.v(TAG, "destroySurface.");
        if (mBlurSurface != null) {
            mBlurSurface.destroy();
            mBlurSurface = null;
        }
        mDestroyed = true;
    }
}

