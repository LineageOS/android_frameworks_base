package com.android.server.wm.onehand;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.WindowManager;
import android.view.animation.Transformation;

import com.android.server.LocalServices;
import com.android.server.policy.WindowManagerPolicy.WindowState;
import com.android.internal.onehand.IOneHandedModeListener;

import java.io.PrintWriter;

/**
 * @hide
 */
public class OneHandedAnimatorProxy extends IOneHandedAnimatorProxy {

    private static final String TAG = "OneHandedAnimatorProxy";

    private OneHandedAnimator mAnimator = null;
    private IWindowManagerFuncs mWms = null;

    public OneHandedAnimatorProxy () {}

    @Override
    public void initialize(Context ctx, IWindowManagerFuncs wms) {
        mWms = wms;
        mAnimator = new OneHandedAnimator(ctx, mWms);

        LocalServices.addService(IOneHandedAnimatorProxy.class, this);
    }

    @Override
    public boolean stepAnimationInTransaction(long currentTime) {
        return mAnimator.stepAnimationInTransaction(currentTime);
    }

    @Override
    public void applyTransformationForRect(Rect outRect) {
        Transformation oneHandT = mAnimator.getTransformation();
        if (oneHandT != null) {
            RectF cropF = new RectF(outRect);
            oneHandT.getMatrix().mapRect(cropF);
            outRect.left = (int)(cropF.left + 0.5f);
            outRect.top = (int)(cropF.top + 0.5f);
            outRect.right = (int)cropF.right;
            outRect.bottom = (int)cropF.bottom;
        }
    }

    @Override
    public Transformation getTransformation() {
        return mAnimator.getTransformation();
    }

    @Override
    public Transformation getTransformationForWindow(WindowState win) {
        if (win.getAttrs().type == WindowManager.LayoutParams.TYPE_ONEHAND_OVERLAY) {
            if (win.getAttrs().getTitle().equals(OneHandedControlPanel.GUIDE_PANEL_WINDOW_NAME)) {
                return mAnimator.mPanel.getTransformationForGuidePanel(mAnimator.getTransformation());
            } else if (win.getAttrs().getTitle().equals(OneHandedControlPanel.CONTROL_PANEL_WINDOW_NAME)) {
                return mAnimator.mPanel.getTransformationForControlPanel(mAnimator.getTransformation());
            }
        }
        return null;
    }

    @Override
    public void notifyOutSideScreenTouch(int x, int y) {
        mAnimator.notifyOutSideScreenTouch(x, y);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        if (shouldDump()) {
            pw.println("ONE HANDED MINI-SCREEN SOLUTION (dumpsys window onehand)");
            mAnimator.dump(pw, args);
        }
    }

    @Override
    public boolean isOnehandTurnedON() {
        if (mAnimator != null) {
            return mAnimator.isOnehandTurnedOn;
        }
        return false;
    }

    @Override
    public boolean isOneHandedModeAvailable() {
        if (mAnimator != null) {
            return mAnimator.isOneHandedModeAvailable();
        }
        return false;
    }

    @Override
    public float getShrinkingScale() {
        if (mAnimator != null) {
            return mAnimator.getSavedShrinkingScale();
        }
        return 1;
    }

    @Override
    public void registerOneHandedModeListener(IOneHandedModeListener listener) {
        if (mAnimator != null) {
            mAnimator.registerOneHandedModeListener(listener);
        }
    }

    @Override
    public void unregisterOneHandedModeListener(IOneHandedModeListener listener) {
        if (mAnimator != null) {
            mAnimator.unregisterOneHandedModeListener(listener);
        }
    }
}
