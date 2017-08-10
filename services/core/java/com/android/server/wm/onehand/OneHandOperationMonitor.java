package com.android.server.wm.onehand;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.KeyguardManager;
import android.content.Context;

import java.util.List;

public class OneHandOperationMonitor {
    private static final String TAG = "OneHandOperationMonitor";

    private static final String ACTION_ENTER = "enter";
    private static final String ACTION_EXIT_OUTSIDE = "exit_outside";
    private static final String ACTION_EXIT_HOME = "exit_home";
    private static final String ACTION_MOVE = "move";
    private static final String ACTION_RESIZE = "resize";
    private static final String ACTION_SWIPE_LEFT = "swipe_left";
    private static final String ACTION_SWIPE_RIGHT = "swipe_right";

    private Context mContext;

    public OneHandOperationMonitor(Context context) {
        mContext = context;
    }

    public void pushEnter() {
        EventLogTags.writeOnehandAction(ACTION_ENTER, getCurrentApp());
    }

    public void pushExitByOutsideScreenTouch() {
        EventLogTags.writeOnehandAction(ACTION_EXIT_OUTSIDE, getCurrentApp());
    }

    public void pushExitByHomeButtonTouch() {
        EventLogTags.writeOnehandAction(ACTION_EXIT_HOME, getCurrentApp());
    }

    public void pushMove() {
        EventLogTags.writeOnehandAction(ACTION_MOVE, "");
    }

    public void pushResize() {
        EventLogTags.writeOnehandAction(ACTION_RESIZE, "");
    }

    public void pushSwipeLeft() {
        EventLogTags.writeOnehandAction(ACTION_SWIPE_LEFT, "");
    }

    public void pushSwipeRight() {
        EventLogTags.writeOnehandAction(ACTION_SWIPE_RIGHT, "");
    }

    private String getCurrentApp() {
        KeyguardManager km =
                (KeyguardManager)mContext.getSystemService(Context.KEYGUARD_SERVICE);
        if (km.inKeyguardRestrictedInputMode()) {
            return "KEYGUARD";
        }

        ActivityManager am =
                (ActivityManager)mContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<RunningTaskInfo> tasks = am.getRunningTasks(1);
        if (tasks.size() != 0) {
            return tasks.get(0).topActivity.getPackageName();
        }

        return "UNKOWN";
    }
}

