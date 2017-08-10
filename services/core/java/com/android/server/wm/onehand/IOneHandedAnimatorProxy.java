package com.android.server.wm.onehand;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Build;
import android.util.Slog;
import android.view.MagnificationSpec;
import android.view.SurfaceControl;
import android.view.animation.Transformation;

import com.android.server.policy.WindowManagerPolicy.WindowState;
import com.android.internal.onehand.IOneHandedModeListener;

import java.io.PrintWriter;

/**
 * @hide
 */
public abstract class IOneHandedAnimatorProxy {

    public static final boolean DEBUG = false;

    public interface IWindowManagerFuncs {
        Object getSyncRoot();
        void scheduleAnimation();
    }

    abstract public void initialize(Context ctx, IWindowManagerFuncs wms);

    abstract public boolean stepAnimationInTransaction(long currentTime);

    abstract public void applyTransformationForRect(Rect outRect);

    abstract public Transformation getTransformation();

    abstract public Transformation getTransformationForWindow(WindowState win);

    abstract public void notifyOutSideScreenTouch(int x, int y);

    abstract public boolean isOnehandTurnedON();

    abstract public boolean isOneHandedModeAvailable();

    abstract public float getShrinkingScale();

    abstract public void registerOneHandedModeListener(IOneHandedModeListener listener);

    abstract public void unregisterOneHandedModeListener(IOneHandedModeListener listener);

    abstract public void dump(PrintWriter pw, String[] args);

    private static final String PROXY_CLASS_NAME = "com.android.server.wm.onehand.OneHandedAnimatorProxy";

    private static final String TAG = "IOneHandedAnimatorProxy";

    public static Object mSyncRoot = new Object();

    public static IOneHandedAnimatorProxy sInstance = null;

    private static final boolean IS_SUPPORTED
            = Resources.getSystem().getBoolean(com.android.internal.R.bool.config_onehanded_mode);

    public static IOneHandedAnimatorProxy create(Context ctx, IWindowManagerFuncs wms) {
        synchronized (mSyncRoot) {
            if (sInstance == null) {
                IOneHandedAnimatorProxy proxy = null;
                if (!IS_SUPPORTED) {
                    proxy = new EmptyProxy();
                } else {
                    try {
                        @SuppressWarnings("rawtypes")
                        Class proxyClass = ctx.getClassLoader().loadClass(PROXY_CLASS_NAME);
                        proxy = (IOneHandedAnimatorProxy)proxyClass.newInstance();
                    } catch (Exception ex) {
                        Slog.d(TAG, PROXY_CLASS_NAME + " could not be loaded", ex);
                        proxy = new EmptyProxy();
                    }
                }
                proxy.initialize(ctx, wms);

                sInstance = proxy;
            }
        }
        return sInstance;
    }

    public static void notifyOutSideScreenTouchFromNative(int x, int y) {
        synchronized (mSyncRoot) {
            if (sInstance != null) {
                sInstance.notifyOutSideScreenTouch(x, y);
            }
        }
    }

    protected boolean shouldDump() {
        return !"user".equals(Build.TYPE);
    }

    /**
     *  EmptyPorxy is used in products that does not supports OneHanded mini-screen solution.
     *  The methods of this class do nothing or return values that will not change the
     *  routine of WindowManagerService/WindowStateAnimator or any other existing codes.
     */
    private static class EmptyProxy extends IOneHandedAnimatorProxy {
        @Override
        public void initialize(Context ctx, IWindowManagerFuncs wms) {
        }

        @Override
        public boolean stepAnimationInTransaction(long currentTime) {
            return false; // EmptyProxy does not require the animation to be continued.
        }

        @Override
        public void applyTransformationForRect(Rect outRect) {
        }

        @Override
        public Transformation getTransformation() {
            return null; // EmptyProxy never provide transformations that may affect the surface.
        }

        @Override
        public void notifyOutSideScreenTouch(int x, int y) {
        }

        @Override
        public void dump(PrintWriter pw, String[] args) {
            if (shouldDump()) {
                pw.println("ONE HANED mini-screen solution is not supported.");
            }
        }

        @Override
        public Transformation getTransformationForWindow(WindowState win) {
            return null;  // EmptyProxy never provide transformations that may affect the surface.
        }

        @Override
        public boolean isOnehandTurnedON() {
            return false;
        }

        @Override
        public boolean isOneHandedModeAvailable() {
            return false;
        }

        @Override
        public float getShrinkingScale() {
            return 1;
        }

        @Override
        public void registerOneHandedModeListener(IOneHandedModeListener listener) {
        }

        @Override
        public void unregisterOneHandedModeListener(IOneHandedModeListener listener) {
        }
    }
}
