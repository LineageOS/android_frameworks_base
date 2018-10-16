package com.android.systemui.onehand;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.WindowManagerGlobal;

import com.android.systemui.R;

public class SlideTouchEvent {
    private static final String TAG = "SlideTouchEvent";

    private static final String EXTRA_ALIGNMENT_STATE = "alignment_state";
    private static final int EXTRA_ALIGNMENT_STATE_UNALIGNED = -1;
    private static final int EXTRA_ALIGNMENT_STATE_LEFT = 0;
    private static final int EXTRA_ALIGNMENT_STATE_RIGHT = 1;

    private static final String ACTION_ONEHAND_TRIGGER_EVENT =
            "com.android.server.wm.onehand.intent.action.ONEHAND_TRIGGER_EVENT";

    /**
     * The units you would like the velocity in. A value of 1 provides pixels per millisecond, 1000 provides pixels per second, etc.
     */
    private static final int UNITS = 1000;

    public static final float SCALE = (float) 3 / 4;

    private float[] mDownPoint = new float[2];
    private float mTriggerSingleHandMode;
    private float mVerticalProhibit;

    private int mMinimumFlingVelocity;
    private int mMaximumFlingVelocity;

    private VelocityTracker mVelocityTracker;
    private Context mContext;
    private IWindowManager wm;

    private boolean mFlag = false;

    public SlideTouchEvent(Context context) {
        mContext = context;
        init();
    }

    private void init() {
        if (null == mContext) {
            Log.e(TAG, "SlideTouchEvent init return...");
            return;
        }

        wm = WindowManagerGlobal.getWindowManagerService();

        final ViewConfiguration configuration = ViewConfiguration.get(mContext);
        mMinimumFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumFlingVelocity = configuration.getScaledMaximumFlingVelocity();

        mTriggerSingleHandMode = mContext.getResources().getDimension(R.dimen.navbar_single_hand_mode_horizontal_threshhold);
        mVerticalProhibit = mContext.getResources().getDimension(R.dimen.navbar_single_hand_mode_vertical_threshhold);
    }

    public void handleTouchEvent(MotionEvent event) {
        if (event == null) {
            return;
        }

        try {
            if (!wm.isOneHandedModeAvailable()) {
                return;
            }
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }

        mVelocityTracker.addMovement(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mFlag = true;
                mDownPoint[0] = event.getX();
                mDownPoint[1] = event.getY();
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getActionIndex() == 0) {
                    mFlag = false;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (!mFlag) {
                    break;
                }
                mFlag = false;
                final VelocityTracker velocityTracker = mVelocityTracker;
                velocityTracker.computeCurrentVelocity(UNITS, mMaximumFlingVelocity);

                final int pointerId = event.getPointerId(0);
                final float velocityX = velocityTracker.getXVelocity(pointerId);

                Log.i(TAG, "vel=" + Math.abs(velocityX) + ", MinimumFlingVelocity=" + mMinimumFlingVelocity);
                if (Math.abs(velocityX) > mMinimumFlingVelocity) {
                    final int historySize = event.getHistorySize();

                    for (int i = 0; i < historySize + 1; i++) {
                        float x = i < historySize ? event.getHistoricalX(i) : event.getX();
                        float y = i < historySize ? event.getHistoricalY(i) : event.getY();
                        float distanceX = mDownPoint[0] - x;
                        float distanceY = mDownPoint[1] - y;
                        if (Math.abs(distanceY) > Math.abs(distanceX) || Math.abs(distanceY) > mVerticalProhibit) {
                            Log.i(TAG, "Sliding distanceY > distancex, " + distanceY + ", " + distanceX);
                            return;
                        }
                        if (Math.abs(distanceX) > mTriggerSingleHandMode) {
                            if (Configuration.ORIENTATION_PORTRAIT == mContext.getResources().getConfiguration().orientation) {
                                toggleOneHandMode(distanceX);
                            }
                        } else {
                            Log.i(TAG, "Sliding distance is too short, can not trigger the one hand mode");
                        }
                    }
                }
                break;
            default:
                break;
        }
    }

    private void toggleOneHandMode(float distanceX) {
        if (distanceX > 0) {
            sendBroadcast(EXTRA_ALIGNMENT_STATE_LEFT);
        }

        if (distanceX < 0) {
            sendBroadcast(EXTRA_ALIGNMENT_STATE_RIGHT);
        }
    }

    private void sendBroadcast(int state) {
        Intent intent = new Intent();
        intent.setAction(ACTION_ONEHAND_TRIGGER_EVENT);
        intent.putExtra(EXTRA_ALIGNMENT_STATE, state);
        mContext.sendBroadcast(intent);
    }
}
