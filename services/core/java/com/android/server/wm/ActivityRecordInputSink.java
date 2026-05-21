/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import android.app.ActivityOptions;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.os.Build;
import android.os.InputConfig;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.window.flags.Flags;

/**
 * Creates a InputWindowHandle that catches all touches that would otherwise pass through an
 * Activity.
 */
class ActivityRecordInputSink {

    /**
     * Feature flag for making Activities consume all touches within their task bounds.
     */
    @ChangeId
    static final long ENABLE_TOUCH_OPAQUE_ACTIVITIES = 194480991L;

    /**
     * If the app's target SDK is 36+, pass-through touches from a cross-uid overlaying activity is
     * blocked by default. The activity may opt in to receive pass-through touches using
     * {@link ActivityOptions#setAllowPassThroughOnTouchOutside}, which allows the to-be-launched
     * cross-uid overlaying activity and other activities in that app to pass through touches. The
     * activity needs to ensure that it trusts the overlaying app and its content is not vulnerable
     * to UI redressing attacks.
     *
     * @see ActivityOptions#setAllowPassThroughOnTouchOutside
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.BAKLAVA)
    static final long ENABLE_OVERLAY_TOUCH_PASS_THROUGH_OPT_IN_ENFORCEMENT = 358129114L;

    private final ActivityRecord mActivityRecord;
    private final boolean mIsCompatEnabled;
    private final String mName;

    private InputWindowHandleWrapper mInputWindowHandleWrapper;
    private SurfaceControl mSurfaceControl;
    private boolean mBlockTouchesFromTop;

    ActivityRecordInputSink(ActivityRecord activityRecord, ActivityRecord sourceRecord,
            boolean appOptInTouchPassThrough) {
        mActivityRecord = activityRecord;
        mIsCompatEnabled = CompatChanges.isChangeEnabled(ENABLE_TOUCH_OPAQUE_ACTIVITIES,
                mActivityRecord.getUid());
        mName = Integer.toHexString(System.identityHashCode(this)) + " ActivityRecordInputSink "
                + mActivityRecord.mActivityComponent.flattenToShortString();

        if (sourceRecord == null) {
            return;
        }
        // If the source activity has target sdk 36+, it is required to opt in to receive
        // pass-through touches from the overlaying activity.
        final boolean isTouchPassThroughOptInEnforced = CompatChanges.isChangeEnabled(
                ENABLE_OVERLAY_TOUCH_PASS_THROUGH_OPT_IN_ENFORCEMENT,
                sourceRecord.getUid());
        if (!Flags.touchPassThroughOptIn() || !isTouchPassThroughOptInEnforced
                || appOptInTouchPassThrough) {
            sourceRecord.mAllowedTouchUid = mActivityRecord.getUid();
        }
    }

    public void applyChangesToSurfaceIfChanged(SurfaceControl.Transaction transaction) {
        final boolean blockTouchesFromTop = isBlockingTouchesFromTopNeeded();
        final InputWindowHandleWrapper inputWindowHandleWrapper =
                getInputWindowHandleWrapper(blockTouchesFromTop);
        if (mSurfaceControl == null) {
            mSurfaceControl = createSurface();
            updateInputSinkZOrder(transaction, blockTouchesFromTop);
        } else if (mBlockTouchesFromTop != blockTouchesFromTop) {
            updateInputSinkZOrder(transaction, blockTouchesFromTop);
        }
        if (inputWindowHandleWrapper.isChanged()) {
            inputWindowHandleWrapper.applyChangesToSurface(transaction, mSurfaceControl);
        }
    }

    private SurfaceControl createSurface() {
        SurfaceControl surfaceControl = mActivityRecord.makeChildSurface(null)
                .setName(mName)
                .setHidden(false)
                .setCallsite("ActivityRecordInputSink.createSurface")
                .build();
        return surfaceControl;
    }

    private void updateInputSinkZOrder(
            SurfaceControl.Transaction t, boolean blockTouchesFromTop) {
        // Put the layer above all siblings if the touches need to be blocked from top, otherwise,
        // put layer below all siblings (and the parent surface too)
        t.setLayer(mSurfaceControl, blockTouchesFromTop ? Integer.MAX_VALUE : Integer.MIN_VALUE);
        mBlockTouchesFromTop = blockTouchesFromTop;
    }

    private InputWindowHandleWrapper getInputWindowHandleWrapper(
            boolean blockTouchesFromTop) {
        if (mInputWindowHandleWrapper == null) {
            mInputWindowHandleWrapper = new InputWindowHandleWrapper(createInputWindowHandle());
        }
        mInputWindowHandleWrapper.setDisplayId(mActivityRecord.getDisplayId());

        if (blockTouchesFromTop) {
            // Early return if the caller explicitly specifies that an input sink is required on top
            // of the Activity to intercept the touch events.
            mInputWindowHandleWrapper.setInputConfigMasked(
                    InputConfig.DEFAULT, InputConfig.NOT_TOUCHABLE);
            return mInputWindowHandleWrapper;
        }

        // Don't block touches from passing through to an activity below us in the same task, if
        // that activity is either from the same uid or if that activity has launched an activity
        // in our uid.
        final ActivityRecord activityBelowInTask = mActivityRecord.getTask() != null
                ? mActivityRecord.getTask().getActivityBelow(mActivityRecord) : null;
        final boolean allowPassthrough = activityBelowInTask != null && (
                activityBelowInTask.mAllowedTouchUid == mActivityRecord.getUid()
                        || activityBelowInTask.isUid(mActivityRecord.getUid()));
        if (allowPassthrough || !mIsCompatEnabled || mActivityRecord.inTransition()
                || !mActivityRecord.mActivityRecordInputSinkEnabled) {
            // Set to non-touchable, so the touch events can pass through.
            mInputWindowHandleWrapper.setInputConfigMasked(InputConfig.NOT_TOUCHABLE,
                    InputConfig.NOT_TOUCHABLE);
        } else {
            // Set to touchable, so it can block by intercepting the touch events.
            mInputWindowHandleWrapper.setInputConfigMasked(
                    InputConfig.DEFAULT, InputConfig.NOT_TOUCHABLE);
        }
        return mInputWindowHandleWrapper;
    }

    private InputWindowHandle createInputWindowHandle() {
        InputWindowHandle inputWindowHandle = new InputWindowHandle(null,
                mActivityRecord.getDisplayId());
        inputWindowHandle.replaceTouchableRegionWithCrop = true;
        inputWindowHandle.name = mName;
        inputWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
        inputWindowHandle.ownerPid = WindowManagerService.MY_PID;
        inputWindowHandle.ownerUid = WindowManagerService.MY_UID;
        inputWindowHandle.inputConfig = InputConfig.NOT_FOCUSABLE | InputConfig.NO_INPUT_CHANNEL;
        return inputWindowHandle;
    }

    /**
     * A non-embedded Activity below the TaskFragments could be exposed to user touches since
     * (1) the upper TaskFragments do not fill the entire Task, and (2) the bounds of the Activity
     * within the TaskFragment and the Activity below in the hierarchy can be different. Therefore,
     * we move the input sink above for the top most Activity that is covered partially to block the
     * touches. This could help avoid tapjacking happening within a Task.
     *
     * @return true if the ActivityRecord is visible to users, and it is below a TaskFragment with
     * a different Uid.
     */
    private boolean isBlockingTouchesFromTopNeeded() {
        if (!mActivityRecord.isVisibleRequested()
                // Based on the trust model for embedded Activities, return early if this Activity
                // is embedded.
                || mActivityRecord.isEmbedded()
                || mActivityRecord.inTransition()) {
            return false;
        }

        final Task task = mActivityRecord.getTask();
        if (task == null) {
            return false;
        }
        final int indexOfActivityRecord = task.mChildren.indexOf(mActivityRecord);
        if (indexOfActivityRecord < 0) {
            return false;
        }

        // Traversing from bottom to top
        for (int i = indexOfActivityRecord + 1; i < task.getChildCount(); i++) {
            WindowContainer child = task.getChildAt(i);
            if (!child.isVisibleRequested()) {
                // If the child is not visible, it doesn't need to be considered for blocking
                // touches from the top.
                continue;
            }

            final TaskFragment tf = child.asTaskFragment();
            if (tf != null) {
                if (!mActivityRecord.isUid(tf.mTaskFragmentOrganizerUid)
                        && tf.mTaskFragmentOrganizerUid != mActivityRecord.mAllowedTouchUid) {
                    return true;
                }
            } else {
                // We rely on the input sink of the visible Activity above to take care of the input
                // handling. Therefore, returning false once we meet a child of a visible Activity.
                return false;
            }
        }
        return false;
    }

    void releaseSurfaceControl() {
        if (mSurfaceControl != null) {
            mSurfaceControl.release();
            mSurfaceControl = null;
        }
    }

}
