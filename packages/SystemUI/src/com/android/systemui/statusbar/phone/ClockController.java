package com.android.systemui.statusbar.phone;

import android.util.Log;
import android.view.View;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.tuner.TunerService;

/**
 * To control your...clock
 */
public class ClockController implements TunerService.Tunable {

    private static final String TAG = "ClockController";

    public static final int CLOCK_POSITION_RIGHT = 0;
    public static final int CLOCK_POSITION_LEFT = 1;

    public static final String CLOCK_POSITION = "lineagesystem:status_bar_clock";

    private Clock mRightClock, mLeftClock, mActiveClock;

    private int mClockPosition = CLOCK_POSITION_RIGHT;
    private boolean mBlackListed = false;

    public ClockController(View statusBar) {
        mRightClock = statusBar.findViewById(R.id.clock);
        mLeftClock = statusBar.findViewById(R.id.clock_left);

        mActiveClock = mRightClock;

        Dependency.get(TunerService.class).addTunable(this,
                StatusBarIconController.ICON_BLACKLIST, CLOCK_POSITION);
    }

    private Clock getClockForCurrentLocation() {
        Clock clockForAlignment;
        switch (mClockPosition) {
            case CLOCK_POSITION_LEFT:
                clockForAlignment = mLeftClock;
                break;
            case CLOCK_POSITION_RIGHT:
            default:
                clockForAlignment = mRightClock;
                break;
        }
        return clockForAlignment;
    }

    private void updateActiveClock() {
        mActiveClock.setVisibility(View.GONE);
        mActiveClock = getClockForCurrentLocation();
        mActiveClock.setVisibility(View.VISIBLE);

        mActiveClock.setClockVisibleByUser(mBlackListed);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        Log.d(TAG, "onTuningChanged key=" + key + " value=" + newValue);

        if (CLOCK_POSITION.equals(key)) {
            mClockPosition = newValue == null ? CLOCK_POSITION_RIGHT : Integer.valueOf(newValue);
        } else {
            mBlackListed = !StatusBarIconController.getIconBlacklist(newValue).contains("clock");
        }
        updateActiveClock();
    }

    public void setVisibility(boolean visible) {
        if (mActiveClock != null) {
            mActiveClock.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
