/*
 * Copyright (C) 2014 The Linux Foundation. All rights reserved.
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
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

class BlurLayer {
    private static final boolean DEBUG = true;
    private static final String TAG = BlurLayer.class.getSimpleName();

    /** Actual surface that blurs */
    private SurfaceControl mBlurSurface;

    /** Last values passed to mBlurSurface.setSize() */
    private int mW, mH;

    /** True after mBlurSurface.show() has been called, false after mBlurSurface.hide(). */
    private boolean mShowing = false;

    BlurLayer(int w, int h, int layer, String name) {
        mW = w;
        mH = h;

        SurfaceControl.openTransaction();
        try {
            mBlurSurface = new SurfaceControl(new SurfaceSession(), TAG + "_" + name, 16, 16,
                    PixelFormat.OPAQUE, SurfaceControl.FX_SURFACE_BLUR | SurfaceControl.HIDDEN);
            mBlurSurface.setLayerStack(0);
            mBlurSurface.setPosition(0, 0);
            mBlurSurface.setSize(mW, mH);
            mBlurSurface.setLayer(layer);
        } catch (Exception e) {
            Slog.e(TAG, "Exception creating BlurLayer surface", e);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    void setSize(int w, int h) {
        if (mBlurSurface == null || (mW == w && mH == h)) {
            return;
        }

        SurfaceControl.openTransaction();
        try {
            mBlurSurface.setSize(w, h);
            mW = w;
            mH = h;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failure setting setSize immediately", e);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    void show() {
        if (mBlurSurface == null || mShowing) {
            return;
        }

        SurfaceControl.openTransaction();
        try {
            mBlurSurface.show();
            mShowing = true;
        } catch (RuntimeException e) {
             Slog.w(TAG, "Failure show()", e);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    void hide() {
        if (mBlurSurface == null || !mShowing) {
            return;
        }

        SurfaceControl.openTransaction();
        try {
            mBlurSurface.hide();
            mShowing = false;
        } catch (RuntimeException e) {
             Slog.w(TAG, "Failure hide()", e);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }
}

