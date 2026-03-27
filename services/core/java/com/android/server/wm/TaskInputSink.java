/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.os.InputConfig;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;

/**
 * Creates a InputWindowHandle that catches all touches that would otherwise pass through a
 * Task.
 */
class TaskInputSink {

    private final Task mTask;
    private final String mName;

    private InputWindowHandleWrapper mInputWindowHandleWrapper;
    private SurfaceControl mSurfaceControl;

    TaskInputSink(Task task) {
        mTask = task;
        mName = Integer.toHexString(System.identityHashCode(this)) + " TaskInputSink "
                + mTask.mTaskId;
    }

    void applyChangesToSurfaceIfChanged(SurfaceControl.Transaction transaction) {
        InputWindowHandleWrapper inputWindowHandleWrapper = getInputWindowHandleWrapper();
        if (mSurfaceControl == null) {
            mSurfaceControl = createSurface(transaction);
        }
        if (inputWindowHandleWrapper.isChanged()) {
            inputWindowHandleWrapper.applyChangesToSurface(transaction, mSurfaceControl);
        }
    }

    private SurfaceControl createSurface(SurfaceControl.Transaction t) {
        SurfaceControl surfaceControl = mTask.makeChildSurface(null)
                .setName(mName)
                .setHidden(false)
                .setCallsite("TaskInputSink.createSurface")
                .build();
        // Put layer below all siblings (and the parent surface too)
        t.setLayer(surfaceControl, Integer.MIN_VALUE);
        return surfaceControl;
    }

    private InputWindowHandleWrapper getInputWindowHandleWrapper() {
        if (mInputWindowHandleWrapper == null) {
            mInputWindowHandleWrapper = new InputWindowHandleWrapper(createInputWindowHandle());
        }
        if (mTask.inTransition() || !mTask.isLeafTask()) {
            // Set to non-touchable, so the touch events can pass through.
            mInputWindowHandleWrapper.setInputConfigMasked(
                    InputConfig.NOT_TOUCHABLE, InputConfig.NOT_TOUCHABLE);
        } else {
            // Set to touchable, so it can block by intercepting the touch events.
            mInputWindowHandleWrapper.setInputConfigMasked(
                    InputConfig.DEFAULT, InputConfig.NOT_TOUCHABLE);
        }
        mInputWindowHandleWrapper.setDisplayId(mTask.getDisplayId());
        return mInputWindowHandleWrapper;
    }

    private InputWindowHandle createInputWindowHandle() {
        InputWindowHandle inputWindowHandle = new InputWindowHandle(null,
                mTask.getDisplayId());
        inputWindowHandle.replaceTouchableRegionWithCrop = true;
        inputWindowHandle.name = mName;
        inputWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
        inputWindowHandle.ownerPid = WindowManagerService.MY_PID;
        inputWindowHandle.ownerUid = WindowManagerService.MY_UID;
        inputWindowHandle.inputConfig = InputConfig.NOT_FOCUSABLE | InputConfig.NO_INPUT_CHANNEL;
        return inputWindowHandle;
    }

    void releaseSurfaceControl() {
        if (mSurfaceControl != null) {
            mSurfaceControl.release();
            mSurfaceControl = null;
        }
    }

}
