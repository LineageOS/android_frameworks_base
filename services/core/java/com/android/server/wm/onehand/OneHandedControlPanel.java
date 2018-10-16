package com.android.server.wm.onehand;

import static com.android.server.wm.onehand.IOneHandedAnimatorProxy.DEBUG;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnClickListener;
import android.view.View.OnLayoutChangeListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.animation.Transformation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.android.server.wm.onehand.IOneHandedAnimatorProxy;

import java.io.PrintWriter;

class OneHandedControlPanel {

    private static final String TAG = "OneHandedControlPanel";

    private static final String ACTION_ONEHANDED_MODE_SETUP =
            "com.android.onehand.intent.action.ONEHANDED_MODE_SETUP";

    private static final float SCREEN_SIZE = 6f; // in inches
    static final float MIN_SCALE = 4f / SCREEN_SIZE;
    static final float DEFAULT_SCALE = 0.75f;

    static final String CONTROL_PANEL_WINDOW_NAME = "ONEHAND control panel";
    static final String GUIDE_PANEL_WINDOW_NAME = "ONEHAND guide panel";

    private final Context mContext;
    private final OneHandedAnimator mAnimator;

    private final float mProtectZonePadding;

    private View mControlPanelRoot = null;
    private ImageView mDragIndicator = null;
    private ImageView mZoom = null;
    private ImageView mMoveIndicator = null;

    private View mGuidePanelRoot = null;
    private View mGuide = null;

    private final Transformation mTmpControlPanelTrans = new Transformation();
    private final Transformation mTmpGuidePanelTrans = new Transformation();
    private float mLastTargetScale = 1;
    private volatile int mControlPanelLength = 0;
    private volatile int mGuideBottom = 0;

    private final float[] mTmpMatrixValues = new float[9];

    private final DisplayInfo mDefaultDisplayInfo = new DisplayInfo();

    private final OneHandOperationMonitor mOperationMonitor;

    private final Handler mH;

    private boolean mInstalled = false;

    private class MyHandler extends Handler {
        static final int MSG_INSTALL = 1;
        static final int MSG_REMOVE = 2;
        static final int MSG_RECREATE_PANELS = 3;
        static final int MSG_MODE_CHANGED = 4;
        static final int MSG_OUTSIDE_SCREEN_TOUCH = 5;

        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            int what = msg.what;
            switch (what) {
                case MSG_INSTALL:
                    handleInstall();
                    break;
                case MSG_REMOVE:
                    handleRemove();
                    break;
                case MSG_RECREATE_PANELS:
                    handleRecreatePanels();
                    break;
                case MSG_MODE_CHANGED:
                    handleModeChange((OneHandedMode)msg.obj);
                    break;
                case MSG_OUTSIDE_SCREEN_TOUCH:
                    handleOutsideScreenTouch(msg.arg1, msg.arg2);
                    break;
            }
        }
    }

    OneHandedControlPanel(Context ctx, OneHandedAnimator animator, Looper mainLooper) {
        mContext = ctx;
        mAnimator = animator;

        mProtectZonePadding = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.config_onehandProtectZonePadding);

        mH = new MyHandler(mainLooper);
        mOperationMonitor = new OneHandOperationMonitor(mContext);
    }

    private void handleInstall() {
        if (DEBUG) Slog.v(TAG, "handleInstall: mInstalled=" + mInstalled);

        WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        if (!wm.getDefaultDisplay().getDisplayInfo(mDefaultDisplayInfo)) {
            Slog.e(TAG, "Failed get display info");
            return;
        }

        if (mGuidePanelRoot == null)
            createGuidePanel();
        if (mControlPanelRoot == null)
            createControlPanel();

        if (!mInstalled) {
            wm.addView(mGuidePanelRoot, createLpForGuidePanel());
            wm.addView(mControlPanelRoot, createLpForControlPanel());
        } else {
            wm.updateViewLayout(mGuidePanelRoot, createLpForGuidePanel());
            wm.updateViewLayout(mControlPanelRoot, createLpForControlPanel());
        }

        mInstalled = true;
    }

    private void handleRemove() {
        if (DEBUG) Slog.v(TAG, "handleRemove: mInstalled=" + mInstalled);
        if (mInstalled) {
            WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(mGuidePanelRoot);
            wm.removeView(mControlPanelRoot);
            mGuidePanelRoot = null;
            mControlPanelRoot = null;
            mInstalled = false;
        }
    }

    private void handleRecreatePanels() {
        if (DEBUG) Slog.v(TAG, "handleRecreatePanels: mInstalled=" + mInstalled
                + ", mGuidePanelRoot=" + mGuidePanelRoot);

        // Recreate panels to reflect config to each view.
        if (mInstalled) {
            handleRemove();
            handleInstall();
            handleModeChange(mAnimator.getOneHandMode());
        }
    }

    private void handleModeChange(OneHandedMode curMode) {
        if (DEBUG) Slog.v(TAG, "handleModeChange: curMode=" + curMode);

        if (mControlPanelRoot == null || curMode.isOffMode())
            return;

        adjustMode(curMode);

        updateViewPosition(curMode, mMoveIndicator,
                com.android.internal.R.drawable.onehand_move_horizontally_left,
                com.android.internal.R.drawable.onehand_move_horizontally_right);

        updateViewPosition(curMode, mZoom,
                com.android.internal.R.drawable.onehand_screen_resize_left,
                com.android.internal.R.drawable.onehand_screen_resize_right);
    }

    private void adjustMode(OneHandedMode curMode) {
        OneHandedMode newMode = new OneHandedMode(curMode);

        newMode.yAdj = getAdjustedYAdj(newMode.getScale(), newMode.yAdj);
        newMode.setScale(getAdjustedScale(newMode.getScale(), newMode.yAdj));

        if (!newMode.equals(curMode)) {
            if (DEBUG) Slog.v(TAG, "adjustMode: curMode=" + curMode + " newMode=" + newMode);

            OneHandedSettings.saveYAdj(mContext, newMode.yAdj);
            OneHandedSettings.saveScale(mContext, newMode.getScale());
            mAnimator.setOneHandedMode(newMode, false);
        }
    }

    private void updateViewPosition(OneHandedMode curMode, ImageView view, int leftResId, int rightResId) {
        boolean dockingLeft =
                ((RelativeLayout.LayoutParams)view.getLayoutParams())
                    .getRules()[RelativeLayout.ALIGN_PARENT_LEFT] == RelativeLayout.TRUE;

        // We are already in correct side, so nothing need to be done
        if (dockingLeft == curMode.hasGravity(Gravity.RIGHT)) {
            if (DEBUG) Slog.v(TAG, "View is already in correct side, so nothing need to do");
            return;
        }

        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams)view.getLayoutParams();

        if (curMode.hasGravity(Gravity.RIGHT)) {
            lp.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            lp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
            view.setImageResource(leftResId);
        } else {
            lp.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
            lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            view.setImageResource(rightResId);
        }

        view.requestLayout();
    }

    Transformation getTransformationForGuidePanel(Transformation oneHandedTrans) {
        if (oneHandedTrans == null || oneHandedTrans.getMatrix() == null || !mInstalled)
            return null;

        float alpha = computeAlphaFromTransformation(oneHandedTrans);
        float alphaToHide = computeAlphaToHideGuidePanel(oneHandedTrans);
        mTmpGuidePanelTrans.setAlpha(alpha * alphaToHide);

        return mTmpGuidePanelTrans;
    }

    private float computeAlphaToHideGuidePanel(Transformation oneHandedTrans) {
        if (oneHandedTrans == null || oneHandedTrans.getMatrix() == null) {
            return 1f;
        }

        int guideBottom = mGuideBottom;
        float controlPanelTop = applyTransformationY(oneHandedTrans, -mControlPanelLength);

        if (guideBottom <= 0 || controlPanelTop > guideBottom) {
            return 1f;
        }

        // Alpha gradually decreases as the control panel approaches the top edge of device screen.
        // The decrease is quickly in early stage. And the decrease is slowly in late stage.
        float earlyStage = 2f * (controlPanelTop / guideBottom) - 1f;
        float lateStage = (controlPanelTop / guideBottom) / 2f;
        float alpha = Math.max(earlyStage, lateStage);
        return Math.max(alpha, 0f);
    }

    private float applyTransformationY(Transformation oneHandedTrans, int y) {
        float[] matValues = mTmpMatrixValues;
        oneHandedTrans.getMatrix().getValues(matValues);
        return y * matValues[Matrix.MSCALE_Y] + matValues[Matrix.MTRANS_Y];
    }

    private void handleOutsideScreenTouch(int x, int y) {
        if (DEBUG) Slog.v(TAG, "handleOutsideScreenTouch: x=" + x + ", y="+y);

        OneHandedMode curMode = mAnimator.getOneHandMode();

        if (curMode.isOffMode())
            return;

        Rect guideBounds = getBoundsOnScreen(mGuide);
        Point touchPositon = getInverseTransformedPosition(x, y);

        if(guideBounds.contains(touchPositon.x, touchPositon.y)) {
            launchOneHandedModeSetupActivity();
            return;
        }

        mOperationMonitor.pushExitByOutsideScreenTouch();
        mAnimator.setOneHandedMode(null, false);
    }

    private Rect getBoundsOnScreen(View view) {
        int[] outLocation = new int[2];
        view.getLocationOnScreen(outLocation);

        return new Rect(outLocation[0], outLocation[1], outLocation[0] + view.getWidth(),
                outLocation[1] + view.getHeight());
    }

    private Point getInverseTransformedPosition(int x, int y) {
        int[] trans = new int[2];
        float[] scale = new float[1];

        mAnimator.getTransformationArgsForMode(mAnimator.getOneHandMode(), trans, scale,
                mDefaultDisplayInfo.logicalWidth, mDefaultDisplayInfo.logicalHeight);

        return new Point(Math.round(x * scale[0] + trans[0]), Math.round(y * scale[0] + trans[1]));
    }

    private void launchOneHandedModeSetupActivity() {
        Intent intent = new Intent(ACTION_ONEHANDED_MODE_SETUP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    private int getAdjustedYAdj(float curScale, int curYAdj) {
        float curWindowHeight = (mDefaultDisplayInfo.logicalHeight + mControlPanelLength) * curScale;

        if (curWindowHeight + curYAdj > mDefaultDisplayInfo.logicalHeight) {
            curYAdj = (int)(mDefaultDisplayInfo.logicalHeight - curWindowHeight);
        }

        if (curYAdj < 0) {
            curYAdj = 0;
        }
        return curYAdj;
    }

    private float getAdjustedScale(float newScale, int curYAdj) {
        if (newScale < MIN_SCALE)
            return MIN_SCALE;

        float maxHeightScale = (float)(mDefaultDisplayInfo.logicalHeight - curYAdj)
                / (float)(mDefaultDisplayInfo.logicalHeight + mControlPanelLength);

        float maxWidthScale = (float)mDefaultDisplayInfo.logicalWidth
                / (float)(mDefaultDisplayInfo.logicalWidth + mControlPanelLength);

        float maxScale = Math.min(maxHeightScale, maxWidthScale);
        if (maxScale > 0 && newScale > maxScale)
            return maxScale;

        return newScale;
    }

    private void createGuidePanel() {
        View root = View.inflate(mContext, com.android.internal.R.layout.onehand_guide_panel, null);
        mGuide = root.findViewById(com.android.internal.R.id.onehand_guide);
        mGuide.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                mGuideBottom = getBoundsOnScreen(mGuide).bottom;
            }
        });
        mGuidePanelRoot = root;
    }

    private WindowManager.LayoutParams createLpForGuidePanel() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.width = mDefaultDisplayInfo.logicalWidth;
        lp.height = mDefaultDisplayInfo.logicalHeight;
        lp.gravity = Gravity.LEFT | Gravity.TOP;
        lp.type = WindowManager.LayoutParams.TYPE_ONEHAND_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
        lp.setTitle(GUIDE_PANEL_WINDOW_NAME);
        lp.format = PixelFormat.TRANSLUCENT;
        lp.x = 0;
        lp.y = 0;

        return lp;
    }

    private void createControlPanel() {
        // Creating the root view
        FrameLayout root = new FrameLayout(mContext) {
            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                recreatePanels();
            }
        };

        // Creating the BAR container
        final RelativeLayout r = new RelativeLayout(mContext);
        root.addView(r, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        r.setOnTouchListener(new OnTouchListener() {
            OneHandedMode mDownMode = new OneHandedMode();
            PointF mDownPoint = new PointF();

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch(event.getActionMasked()) {
                    case MotionEvent.ACTION_UP:
                        OneHandedMode curMode = mAnimator.getOneHandMode();

                        if (curMode.isOffMode())
                            break;

                        if(getProtectZone(v).contains(event.getX(), event.getY()))
                            break; // do not interact with the touch inside protect zone

                        mOperationMonitor.pushExitByOutsideScreenTouch();
                        mAnimator.setOneHandedMode(null, false);
                        break;
                }
                return true;
            }
        });
        r.setMotionEventSplittingEnabled(false);

        // An indicator to tell the user that this bar is dragable.
        final ImageView dragIndicator = new ImageView(mContext);
        dragIndicator.setBackgroundResource(com.android.internal.R.drawable.onehand_buttons_background);
        dragIndicator.setImageResource(com.android.internal.R.drawable.onehand_move_vertically);
        dragIndicator.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(dragIndicator.getMeasuredWidth(),
                dragIndicator.getMeasuredHeight());
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        dragIndicator.setLayoutParams(lp);
        r.addView(dragIndicator);

        dragIndicator.setOnTouchListener(new OnTouchListener() {
            OneHandedMode mDownMode = new OneHandedMode();
            PointF mDownPoint = new PointF();

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch(event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mAnimator.suspendPointerMappingUpgration();
                        mDownMode.set(mAnimator.getOneHandMode());
                        mDownPoint.set(event.getX(), event.getY());
                        dragIndicator.drawableHotspotChanged(event.getX(), event.getY());
                        dragIndicator.setPressed(true);
                        setSlipperyToControlPanel(false);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        int yDelta = (int)((event.getY() - mDownPoint.y + 0.5f) * mDownMode.getScale());
                        OneHandedMode newMode = new OneHandedMode(mDownMode);

                        if (mDownMode.hasGravity(Gravity.BOTTOM)) {
                            yDelta = -yDelta;
                        }

                        newMode.yAdj += yDelta;
                        newMode.yAdj = getAdjustedYAdj(newMode.getScale(), newMode.yAdj);
                        OneHandedSettings.saveYAdj(mContext, newMode.yAdj);
                        mAnimator.setOneHandedMode(newMode, true);
                        dragIndicator.drawableHotspotChanged(event.getX(), event.getY());
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mAnimator.unsuspendPointerMappingUpgration();
                        dragIndicator.setPressed(false);
                        mOperationMonitor.pushMove();
                        setSlipperyToControlPanel(true);
                        break;
                }
                return true;
            }
        });

        // Creating zoom button
        final ImageView zoom = new ImageView(mContext);
        zoom.setBackgroundResource(com.android.internal.R.drawable.onehand_buttons_background);
        zoom.setImageResource(com.android.internal.R.drawable.onehand_screen_resize_left);
        zoom.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        lp = new RelativeLayout.LayoutParams(zoom.getMeasuredWidth(), zoom.getMeasuredHeight());
        lp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        zoom.setLayoutParams(lp);
        r.addView(zoom);

        zoom.setOnTouchListener(new OnTouchListener() {
            OneHandedMode mDownMode = new OneHandedMode();
            PointF mDownPoint = new PointF();

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch(event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDownMode.set(mAnimator.getOneHandMode());
                        mDownPoint.set(event.getX(), event.getY());

                        mAnimator.suspendPointerMappingUpgration();
                        zoom.setPressed(true);
                        setSlipperyToControlPanel(false);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float xDelta =  (int)((event.getX() - mDownPoint.x + 0.5f) * mDownMode.getScale());
                        float yDelta =  (int)((event.getY() - mDownPoint.y + 0.5f) * mDownMode.getScale());

                        if (mDownMode.hasGravity(Gravity.RIGHT)) {
                            xDelta = -xDelta;
                        }

                        if (mDownMode.hasGravity(Gravity.BOTTOM)) {
                            yDelta = -yDelta;
                        }

                        // Use y as the control
                        float beforeHeight = mDefaultDisplayInfo.logicalHeight * mDownMode.getScale();
                        float afterHeight = beforeHeight + yDelta;
                        float newScale = afterHeight / mDefaultDisplayInfo.logicalHeight;

                        OneHandedMode newMode = new OneHandedMode(mDownMode);
                        newMode.setScale(getAdjustedScale(newScale, newMode.yAdj));
                        OneHandedSettings.saveScale(mContext, newMode.getScale());
                        mAnimator.setOneHandedMode(newMode, true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mAnimator.unsuspendPointerMappingUpgration();
                        zoom.setPressed(false);
                        mOperationMonitor.pushResize();
                        setSlipperyToControlPanel(true);
                        break;
                }
                return true;
            }
        });

        // Creating the move indicator
        final ImageView moveIndicator = new ImageView(mContext);
        moveIndicator.setBackgroundResource(com.android.internal.R.drawable.onehand_buttons_background);
        moveIndicator.setImageResource(com.android.internal.R.drawable.onehand_move_horizontally_left);
        moveIndicator.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        lp = new RelativeLayout.LayoutParams(moveIndicator.getMeasuredWidth(), moveIndicator.getMeasuredHeight());
        lp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        // lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        lp.addRule(RelativeLayout.CENTER_VERTICAL);
        moveIndicator.setLayoutParams(lp);
        r.addView(moveIndicator);

        moveIndicator.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                OneHandedMode curMode = mAnimator.getOneHandMode();

                if (curMode.isOffMode())
                    return;

                if (curMode.hasGravity(Gravity.RIGHT)) {
                    curMode.setGravity(Gravity.LEFT | Gravity.BOTTOM);
                } else {
                    curMode.setGravity(Gravity.RIGHT | Gravity.BOTTOM);
                }

                OneHandedSettings.saveGravity(mContext, curMode.getGravity());

                if (curMode.hasGravity(Gravity.RIGHT)) {
                    mOperationMonitor.pushSwipeRight();
                } else {
                    mOperationMonitor.pushSwipeLeft();
                }

                mAnimator.setOneHandedMode(curMode, false);
            }
        });

        mControlPanelLength = Math.max(zoom.getMeasuredHeight(), moveIndicator.getMeasuredHeight());

        mDragIndicator = dragIndicator;
        mMoveIndicator = moveIndicator;
        mZoom = zoom;
        mControlPanelRoot = root;
    }

    private WindowManager.LayoutParams createLpForControlPanel() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.width = mDefaultDisplayInfo.logicalWidth + mControlPanelLength * 2;
        lp.height = mDefaultDisplayInfo.logicalHeight + mControlPanelLength * 2;
        lp.gravity = Gravity.LEFT | Gravity.TOP;
        lp.type = WindowManager.LayoutParams.TYPE_ONEHAND_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_SLIPPERY;
        lp.setTitle(CONTROL_PANEL_WINDOW_NAME);
        lp.format = PixelFormat.TRANSLUCENT;
        lp.x = -mControlPanelLength;
        lp.y = -mControlPanelLength;

        return lp;
    }

    private void setSlipperyToControlPanel(boolean slippery) {
        if (mControlPanelRoot == null || mControlPanelRoot.getLayoutParams() == null) {
            return;
        }

        WindowManager.LayoutParams lp =
                (WindowManager.LayoutParams)mControlPanelRoot.getLayoutParams();

        boolean changed = false;
        if (slippery && (lp.flags & FLAG_SLIPPERY) == 0) {
            lp.flags |= FLAG_SLIPPERY;
            changed = true;
        } else if (!slippery && (lp.flags & FLAG_SLIPPERY) != 0) {
            lp.flags &= ~FLAG_SLIPPERY;
            changed = true;
        }
        if (changed) {
            WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(mControlPanelRoot, lp);
        }
    }

    private RectF getProtectZone(View view) {
        OneHandedMode curMode = mAnimator.getOneHandMode();

        RectF protectZone = new RectF(0, 0, view.getWidth(), view.getHeight());
        protectZone.inset(mControlPanelLength, mControlPanelLength);

        float padding = mProtectZonePadding / curMode.getScale();
        protectZone.inset(-padding, -padding);

        return protectZone;
    }

    private float computeAlphaFromTransformation(Transformation oneHandedTrans) {
        float alpha = 1;

        if (oneHandedTrans != null && oneHandedTrans.getMatrix() != null) {
            int[] targetTranslate = new int[2];
            float[] targetScale = new float[1];

            mAnimator.getTransformationArgsForMode(mAnimator.getOneHandMode()
                    , targetTranslate, targetScale
                    , mDefaultDisplayInfo.logicalWidth, mDefaultDisplayInfo.logicalHeight);

            float[] matValues = mTmpMatrixValues;
            oneHandedTrans.getMatrix().getValues(matValues);
            float currentScale = (matValues[Matrix.MSCALE_X] + matValues[Matrix.MSCALE_Y]) / 2;

            if (targetScale[0] == 1) {
                alpha = (1 - currentScale) / (1 - mLastTargetScale);
            } else {
                mLastTargetScale = targetScale[0];
                alpha = (1 - currentScale) / (1 - mLastTargetScale);
            }

            if (alpha > 1)
                alpha = 1;
       }

       return alpha;
    }
    Transformation getTransformationForControlPanel(Transformation oneHandedTrans) {
        if (oneHandedTrans == null || !mInstalled)
            return null;

        float alpha = computeAlphaFromTransformation(oneHandedTrans);
        mTmpControlPanelTrans.set(oneHandedTrans);
        mTmpControlPanelTrans.setAlpha(alpha);

        return mTmpControlPanelTrans;
    }

    void notifyOutsideScreenTouch(int x, int y) {
        mH.removeMessages(MyHandler.MSG_OUTSIDE_SCREEN_TOUCH);
        Message msg = mH.obtainMessage(MyHandler.MSG_OUTSIDE_SCREEN_TOUCH
                , x, y);
        mH.sendMessage(msg);
    }

    void notifyModeChanged(OneHandedMode curMode, OneHandedMode lastMode) {
        Message msg = mH.obtainMessage(MyHandler.MSG_MODE_CHANGED, curMode);
        mH.sendMessage(msg);
    }

    void install() {
        mH.removeMessages(MyHandler.MSG_INSTALL);
        mH.removeMessages(MyHandler.MSG_REMOVE);
        mH.sendEmptyMessage(MyHandler.MSG_INSTALL);
    }

    void remove() {
        mH.removeMessages(MyHandler.MSG_INSTALL);
        mH.removeMessages(MyHandler.MSG_REMOVE);
        mH.sendEmptyMessage(MyHandler.MSG_REMOVE);
    }

    private void recreatePanels() {
        mH.removeMessages(MyHandler.MSG_RECREATE_PANELS);
        mH.sendEmptyMessage(MyHandler.MSG_RECREATE_PANELS);
    }

    void dump(PrintWriter pw, String[] args) {
        pw.println("Control Panel Status:");
        pw.print("  Bar Height="); pw.println(mControlPanelLength);
        pw.print("  Bar transformation="); pw.println(mTmpControlPanelTrans);
        pw.print("  mLastTargetScale="); pw.println(mLastTargetScale);
    }
}
