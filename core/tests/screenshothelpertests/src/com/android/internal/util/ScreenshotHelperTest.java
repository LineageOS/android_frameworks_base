/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.util;

import static android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN;
import static android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE;
import static android.view.WindowManager.TAKE_SCREENSHOT_SELECTED_REGION;

import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class ScreenshotHelperTest {
    private Context mContext;
    private ScreenshotHelper mScreenshotHelper;
    private Handler mHandler;


    @Before
    public void setUp() {
        // `ScreenshotHelper.notifyScreenshotError()` calls `Context.sendBroadcastAsUser()` and
        // `Context.bindServiceAsUser`.
        //
        // This raises a `SecurityException` if the device is locked. Calling either `Context`
        // method results in a broadcast of `android.intent.action. USER_PRESENT`. Only the system
        // process is allowed to broadcast that `Intent`.
        Resources res = mock(Resources.class);
        mContext = Mockito.spy(Context.class);
        doNothing().when(mContext).sendBroadcastAsUser(any(), any());
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        doReturn(res).when(mContext).getResources();
        doReturn("com.android.systemui/.Service").when(res).getString(
                eq(com.android.internal.R.string.config_screenshotServiceComponent));
        doReturn("com.android.systemui/.ErrorReceiver").when(res).getString(
                eq(com.android.internal.R.string.config_screenshotErrorReceiverComponent));

        mHandler = new Handler(Looper.getMainLooper());
        mScreenshotHelper = new ScreenshotHelper(mContext);
    }

    @Test
    public void testFullscreenScreenshot() {
        mScreenshotHelper.takeScreenshot(
                WindowManager.ScreenshotSource.SCREENSHOT_OTHER, mHandler, null);
    }

    @Test
    public void testFullscreenScreenshotRequest() {
        ScreenshotRequest request = new ScreenshotRequest.Builder(
                TAKE_SCREENSHOT_FULLSCREEN, WindowManager.ScreenshotSource.SCREENSHOT_OTHER)
                .build();
        mScreenshotHelper.takeScreenshot(request, mHandler, null);
    }

    @Test
    public void testSelectedRegionScreenshot() {
        mScreenshotHelper.takeScreenshot(TAKE_SCREENSHOT_SELECTED_REGION,
                WindowManager.ScreenshotSource.SCREENSHOT_OTHER, mHandler, null);
    }

    @Test
    public void testProvidedImageScreenshot() {
        HardwareBuffer buffer = HardwareBuffer.create(
                10, 10, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
        ScreenshotRequest request = new ScreenshotRequest.Builder(
                TAKE_SCREENSHOT_PROVIDED_IMAGE, WindowManager.ScreenshotSource.SCREENSHOT_OTHER)
                .setTopComponent(new ComponentName("", ""))
                .setTaskId(1)
                .setUserId(1)
                .setBitmap(bitmap)
                .setBoundsOnScreen(new Rect())
                .setInsets(Insets.NONE)
                .build();
        mScreenshotHelper.takeScreenshot(request, mHandler, null);
    }

    @Test
    public void testScreenshotTimesOut() {
        long timeoutMs = 10;
        ScreenshotRequest request = new ScreenshotRequest.Builder(
                TAKE_SCREENSHOT_FULLSCREEN, WindowManager.ScreenshotSource.SCREENSHOT_OTHER)
                .build();

        CountDownLatch lock = new CountDownLatch(1);
        mScreenshotHelper.takeScreenshotInternal(request, mHandler,
                uri -> {
                    assertNull(uri);
                    lock.countDown();
                }, timeoutMs);

        try {
            // Add tolerance for delay to prevent flakes.
            long awaitDurationMs = timeoutMs + 100;
            if (!lock.await(awaitDurationMs, TimeUnit.MILLISECONDS)) {
                fail("lock never freed");
            }
        } catch (InterruptedException e) {
            fail("lock interrupted");
        }
    }
}
