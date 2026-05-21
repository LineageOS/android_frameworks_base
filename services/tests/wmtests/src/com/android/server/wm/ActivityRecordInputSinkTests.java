/*
 * Copyright 2023 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.UiAutomation;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.window.TaskFragmentCreationParams;
import android.window.TaskFragmentOrganizer;
import android.window.WindowContainerTransaction;
import android.window.WindowInfosListenerForTest;
import android.window.WindowInfosListenerForTest.DisplayInfo;
import android.window.WindowInfosListenerForTest.WindowInfo;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.window.flags.Flags;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Internal variant of {@link android.server.wm.window.ActivityRecordInputSinkTests}.
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class ActivityRecordInputSinkTests {
    private static final String OVERLAY_APP_PKG = "com.android.server.wm.overlay_app";
    private static final String OVERLAY_ACTIVITY = OVERLAY_APP_PKG + "/.OverlayApp";
    private static final String KEY_DISABLE_INPUT_SINK = "disableInputSink";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Rule
    public final ActivityScenarioRule<TestActivity> mActivityRule =
            new ActivityScenarioRule<>(TestActivity.class);

    private UiAutomation mUiAutomation;

    @Before
    public void setUp() {
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    @After
    public void tearDown() {
        ActivityManager am =
                InstrumentationRegistry.getInstrumentation().getContext().getSystemService(
                        ActivityManager.class);
        mUiAutomation.adoptShellPermissionIdentity();
        try {
            am.forceStopPackage(OVERLAY_APP_PKG);
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    @Test
    public void testSimpleButtonPress() {
        injectTapOnButton();

        mActivityRule.getScenario().onActivity(a -> {
            assertEquals(1, a.mNumClicked);
        });
    }

    @Test
    public void testSimpleButtonPress_withOverlay() throws InterruptedException {
        startOverlayApp(false);
        waitForOverlayApp();

        injectTapOnButton();

        mActivityRule.getScenario().onActivity(a -> {
            assertEquals(0, a.mNumClicked);
        });
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ALLOW_DISABLE_ACTIVITY_RECORD_INPUT_SINK)
    @FlakyTest(bugId = 419786630)
    public void testSimpleButtonPress_withOverlayDisableInputSink() throws InterruptedException {
        startOverlayApp(true);
        waitForOverlayApp();

        injectTapOnButton();

        mActivityRule.getScenario().onActivity(a -> {
            assertEquals(1, a.mNumClicked);
        });
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ALLOW_DISABLE_ACTIVITY_RECORD_INPUT_SINK)
    public void testSimpleButtonPress_withOverlayDisableInputSink_flagDisabled()
            throws InterruptedException {
        startOverlayApp(true);
        waitForOverlayApp();

        injectTapOnButton();

        mActivityRule.getScenario().onActivity(a -> {
            assertEquals(0, a.mNumClicked);
        });
    }

    @Test
    @EnableCompatChanges(
            {ActivityRecordInputSink.ENABLE_OVERLAY_TOUCH_PASS_THROUGH_OPT_IN_ENFORCEMENT})
    public void testTouchPassthrough_TaskFragmentSameUid() throws InterruptedException {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getContext(),
                TestActivity.class);

        // Same UID: No InputSink created, touch should pass through (1 click)
        runTaskFragmentTouchTest(intent,  /* waitForSink= */ false, /* expectedClicks= */ 1);
    }

    @Test
    @EnableCompatChanges(
            {ActivityRecordInputSink.ENABLE_OVERLAY_TOUCH_PASS_THROUGH_OPT_IN_ENFORCEMENT})
    public void testTouchPassthrough_TaskFragmentDifferentUid_enableCompatChanges()
            throws InterruptedException {
        Intent intent = new Intent();
        intent.setComponent(ComponentName.unflattenFromString(OVERLAY_ACTIVITY));

        // Different UID: InputSink created, touch should be blocked (0 clicks)
        runTaskFragmentTouchTest(intent, /* waitForSink= */ true , /* expectedClicks= */ 0);
    }

    @Test
    @DisableCompatChanges(
            {ActivityRecordInputSink.ENABLE_OVERLAY_TOUCH_PASS_THROUGH_OPT_IN_ENFORCEMENT})
    public void testTouchPassthrough_TaskFragmentDifferentUid_disableCompatChanges()
            throws InterruptedException {
        Intent intent = new Intent();
        intent.setComponent(ComponentName.unflattenFromString(OVERLAY_ACTIVITY));

        // Different UID, but compat change disabled: No InputSink created, touch should pass
        // through (1 click)
        runTaskFragmentTouchTest(intent, /* waitForSink= */ false , /* expectedClicks= */ 1);
    }

    private void runTaskFragmentTouchTest(Intent intent, boolean waitForSink, int expectedClicks)
            throws InterruptedException {
        TaskFragmentOrganizer organizer = new TaskFragmentOrganizer(Runnable::run);
        mUiAutomation.adoptShellPermissionIdentity();

        try {
            organizer.registerOrganizer();

            mActivityRule.getScenario().onActivity(activity -> {
                IBinder fragmentToken = new Binder();
                WindowContainerTransaction wct = new WindowContainerTransaction();

                Rect buttonBounds = new Rect();
                activity.mButton.getBoundsOnScreen(buttonBounds);

                // Create a TaskFragment that covers the left half of the button area
                wct.createTaskFragment(new TaskFragmentCreationParams.Builder(
                        organizer.getOrganizerToken(),
                        fragmentToken,
                        activity.getActivityToken())
                        .setInitialRelativeBounds(new Rect(0, 0,
                                buttonBounds.centerX(),
                                buttonBounds.bottom))
                        .build());

                // Start the provided activity into the fragment
                wct.startActivityInTaskFragment(fragmentToken, activity.getActivityToken(),
                        intent, /* activityOptions= */ null);

                organizer.applyTransaction(
                        wct, TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_OPEN, false);
            });

            if (waitForSink) {
                // Wait for the ActivityRecordInputSink to be created (Different UID case)
                waitForOverlayApp();
            } else {
                // Just wait for the hierarchy to settle (Same UID case)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }

            // Tap on the area that is not covered by the TaskFragment
            injectTapOnButtonAtPosition(0.75f, 0.5f);

            mActivityRule.getScenario().onActivity(a -> {
                assertEquals("Click count mismatch for UID test", expectedClicks, a.mNumClicked);
            });

        } finally {
            organizer.unregisterOrganizer();
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    private void startOverlayApp(boolean disableInputSink) {
        String launchCommand = "am start -n " + OVERLAY_ACTIVITY;
        if (disableInputSink) {
            launchCommand += " --ez " + KEY_DISABLE_INPUT_SINK + " true";
        }

        mUiAutomation.adoptShellPermissionIdentity();
        try {
            mUiAutomation.executeShellCommand(launchCommand);
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    private void waitForOverlayApp() throws InterruptedException {
        final var listenerHost = new WindowInfosListenerForTest();
        final var latch = new CountDownLatch(1);
        final BiConsumer<List<WindowInfo>, List<DisplayInfo>> listener =
            (windowInfos, displayInfos) -> {
                final boolean inputSinkReady = windowInfos.stream().anyMatch(
                    info -> info.isVisible
                        && info.name.contains("ActivityRecordInputSink " + OVERLAY_ACTIVITY));
                if (inputSinkReady) {
                    latch.countDown();
                }
            };

        listenerHost.addWindowInfosListener(listener);
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } finally {
            listenerHost.removeWindowInfosListener(listener);
        }
    }

    private void injectTapOnButton() {
        injectTapOnButtonAtPosition(0.5f, 0.5f);
    }

    /**
     * Injects a tap event on the button at a specific relative position.
     *
     * The tap coordinates are calculated as a fraction of the button's dimensions
     * relative to its top-left corner. For example:
     * - (0.0f, 0.0f) taps the top-left corner.
     * - (0.5f, 0.5f) taps the exact center.
     * - (1.0f, 1.0f) taps the bottom-right corner.
     *
     * @param xOffsetRatio The fractional X coordinate along the button's width
     *                     (0.0 is the left edge, 1.0 is the right edge).
     * @param yOffsetRatio The fractional Y coordinate along the button's height
     *                     (0.0 is the top edge, 1.0 is the bottom edge).
     */
    private void injectTapOnButtonAtPosition(float xOffsetRatio, float yOffsetRatio) {
        Rect buttonBounds = new Rect();
        mActivityRule.getScenario().onActivity(a -> {
            a.mButton.getBoundsOnScreen(buttonBounds);
        });

        final int x = (int) (buttonBounds.left + buttonBounds.width() * xOffsetRatio);
        final int y = (int) (buttonBounds.top + buttonBounds.height() * yOffsetRatio);

        MotionEvent down = MotionEvent.obtain(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, 0);
        mUiAutomation.injectInputEvent(down, true);

        SystemClock.sleep(10);

        MotionEvent up = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, x, y, 0);
        mUiAutomation.injectInputEvent(up, true);

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    public static class TestActivity extends Activity {
        int mNumClicked = 0;
        Button mButton;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mButton = new Button(this);
            mButton.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(mButton);
            mButton.setOnClickListener(v -> mNumClicked++);
        }
    }
}
