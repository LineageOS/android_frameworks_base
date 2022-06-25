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

package com.android.systemui.screenshot;

import com.android.systemui.dagger.SysUISingleton;

import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

/**
 * LongScreenshotData holds on to 1 LongScreenshot reference and 1 TransitionDestination
 * reference, to facilitate indirect in-process passing.
 */
@SysUISingleton
public class LongScreenshotData {
    private final AtomicReference<ScrollCaptureController.LongScreenshot> mLongScreenshot;
    private final AtomicReference<ScreenshotController.TransitionDestination>
            mTransitionDestinationCallback;
    private String mForegroundAppName;
    private boolean mNeedsMagnification;

    @Inject
    public LongScreenshotData() {
        mLongScreenshot = new AtomicReference<>();
        mTransitionDestinationCallback = new AtomicReference<>();
    }

    /**
     * Set the holder's stored LongScreenshot.
     */
    public void setLongScreenshot(ScrollCaptureController.LongScreenshot longScreenshot) {
        mLongScreenshot.set(longScreenshot);
    }

    /**
     * @return true if the holder has a non-null LongScreenshot.
     */
    public boolean hasLongScreenshot() {
        return mLongScreenshot.get() != null;
    }

    /**
     * Return the current stored LongScreenshot, clear the holder's storage.
     */
    public ScrollCaptureController.LongScreenshot takeLongScreenshot() {
        return mLongScreenshot.getAndSet(null);
    }

    /**
     * Set the holder's TransitionDestination callback.
     */
    public void setTransitionDestinationCallback(
            ScreenshotController.TransitionDestination destination) {
        mTransitionDestinationCallback.set(destination);
    }

    /**
     * Return the current TransitionDestination callback and clear.
     */
    public ScreenshotController.TransitionDestination takeTransitionDestinationCallback() {
        return mTransitionDestinationCallback.getAndSet(null);
    }

    /**
     * Set the holder's foreground app name.
     */
    public void setForegroundAppName(String foregroundAppName) {
        mForegroundAppName = foregroundAppName;
    }

    /**
     * Return the current foreground app name.
     */
    public String getForegroundAppName() {
        return mForegroundAppName;
    }

    public void setNeedsMagnification(boolean status) {
        mNeedsMagnification = status;
    }

    public boolean getNeedsMagnification() {
        return mNeedsMagnification;
    }
}
