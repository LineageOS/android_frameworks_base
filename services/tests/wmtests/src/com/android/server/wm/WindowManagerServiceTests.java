/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.Manifest.permission.MANAGE_DISPLAYS;
import static android.Manifest.permission.STATUS_BAR_SERVICE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.permission.flags.Flags.FLAG_SENSITIVE_CONTENT_IMPROVEMENTS;
import static android.permission.flags.Flags.FLAG_SENSITIVE_CONTENT_RECENTS_SCREENSHOT_BUGFIX;
import static android.permission.flags.Flags.FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_OWN_FOCUS;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_DISPLAY_TOPOLOGY_AWARE;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_SENSITIVE_FOR_PRIVACY;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_SPY;
import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.flags.Flags.FLAG_SENSITIVE_CONTENT_APP_PROTECTION;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_SOLID_COLOR;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.IScreenCaptureObserver;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.InputConfig;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.MergedConfiguration;
import android.view.ContentRecordingSession;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowInputChannelParams;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerGlobal;
import android.view.WindowRelayoutResult;
import android.window.ActivityWindowInfo;
import android.window.ClientWindowFrames;
import android.window.ConfigurationChangeSetting;
import android.window.IDisplayEngagementModeCallback;
import android.window.InputTransferToken;
import android.window.ScreenCaptureInternal;
import android.window.WindowContainerToken;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;
import com.android.internal.os.ApplicationSharedMemory;
import com.android.internal.os.IResultReceiver;
import com.android.server.LocalServices;
import com.android.server.StorageManagerInternal;
import com.android.server.wm.SensitiveContentPackages.PackageInfo;
import com.android.server.wm.WindowManagerService.WindowContainerInfo;
import com.android.window.flags.Flags;

import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoSession;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Build/Install/Run:
 * atest WmTests:WindowManagerServiceTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowManagerServiceTests extends WindowTestsBase {

    private final IApplicationThread mAppThread = ActivityThread.currentActivityThread()
            .getApplicationThread();

    private ApplicationSharedMemory mSavedSharedMemory;

    @Override
    protected void onBeforeSystemServicesCreated() {
        mSavedSharedMemory = ApplicationSharedMemory.sInstance;
        ApplicationSharedMemory.sInstance = ApplicationSharedMemory.create();
    }

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            ADD_TRUSTED_DISPLAY, MANAGE_DISPLAYS, STATUS_BAR_SERVICE);

    @Rule
    public Expect mExpect = Expect.create();

    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Before
    public void setUp() {
        Settings.System.clearProviderForTest();
    }

    @After
    public void tearDown() {
        if (ApplicationSharedMemory.sInstance != null) {
            ApplicationSharedMemory.sInstance.close();
        }
        ApplicationSharedMemory.sInstance = mSavedSharedMemory;
        mWm.mSensitiveContentPackages.clearBlockedApps();
        Settings.System.clearProviderForTest();
    }

    @Test
    public void testIsRequestedOrientationMapped() {
        mWm.setOrientationRequestPolicy(/* isIgnoreOrientationRequestDisabled*/ true,
                /* fromOrientations */ new int[]{1}, /* toOrientations */ new int[]{2});
        assertThat(mWm.mapOrientationRequest(1)).isEqualTo(2);
        assertThat(mWm.mapOrientationRequest(3)).isEqualTo(3);

        // Mapping disabled
        mWm.setOrientationRequestPolicy(/* isIgnoreOrientationRequestDisabled*/ false,
                /* fromOrientations */ null, /* toOrientations */ null);
        assertThat(mWm.mapOrientationRequest(1)).isEqualTo(1);
        assertThat(mWm.mapOrientationRequest(3)).isEqualTo(3);
    }

    @Test
    public void testEnableDisableAnimationsForDisplay() {
        // Set non-zero default animation scales for window and transition animations.
        float defaultScale = 5f;
        mWm.setAnimationScale(WindowManagerService.WINDOW_ANIMATION_SCALE, defaultScale);
        mWm.setAnimationScale(WindowManagerService.TRANSITION_ANIMATION_SCALE, defaultScale);
        DisplayContent newDisplay = createNewDisplay();
        assertEquals(defaultScale, newDisplay.getWindowAnimationScaleLocked(), 0.1f /* delta */);
        assertEquals(defaultScale, newDisplay.getTransitionAnimationScaleLocked(),
                0.1f /* delta */);

        // Disable animations for the new display.
        mWm.setAnimationsDisabledForDisplay(newDisplay.mDisplayId, true /* disabled */);
        assertEquals(0, newDisplay.getWindowAnimationScaleLocked(), 0.1f /* delta */);
        assertEquals(0, newDisplay.getTransitionAnimationScaleLocked(), 0.1f /* delta */);
        assertEquals(defaultScale, mDisplayContent.getTransitionAnimationScaleLocked(),
                0.1f /* delta */);
        assertEquals(defaultScale, mDisplayContent.getTransitionAnimationScaleLocked(),
                0.1f /* delta */);

        // Re-enable animations for the new display.
        mWm.setAnimationsDisabledForDisplay(newDisplay.mDisplayId, false /* disabled */);
        assertEquals(defaultScale, newDisplay.getWindowAnimationScaleLocked(), 0.1f /* delta */);
        assertEquals(defaultScale, newDisplay.getTransitionAnimationScaleLocked(),
                0.1f /* delta */);
    }

    @Test
    public void testAddWindowToken() {
        IBinder token = mock(IBinder.class);
        mWm.addWindowToken(token, TYPE_TOAST, mDisplayContent.getDisplayId(), null /* options */);

        WindowToken windowToken = mWm.mRoot.getWindowToken(token);
        assertFalse(windowToken.mRoundedCornerOverlay);
        assertFalse(windowToken.isFromClient());
    }

    @Test
    public void testTaskFocusChange_rootTaskNotHomeType_focusChanges() throws RemoteException {
        DisplayContent display = createNewDisplay();
        // Current focused window
        Task focusedRootTask = createTask(
                display, WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        Task focusedTask = createTaskInRootTask(focusedRootTask, 0 /* userId */);
        WindowState focusedWindow = createAppWindow(focusedTask, TYPE_APPLICATION, "App Window");
        mDisplayContent.mCurrentFocus = focusedWindow;
        // Tapped task
        Task tappedRootTask = createTask(
                display, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        Task tappedTask = createTaskInRootTask(tappedRootTask, 0 /* userId */);
        spyOn(mWm.mAtmService);

        mWm.handleTaskFocusChange(tappedTask, null /* window */);

        verify(mWm.mAtmService).setFocusedTask(tappedTask.mTaskId, null);
    }

    @Test
    public void testTaskFocusChange_rootTaskHomeTypeWithSameTaskDisplayArea_focusDoesNotChange()
            throws RemoteException {
        DisplayContent display = createNewDisplay();
        // Current focused window
        Task focusedRootTask = createTask(
                display, WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        Task focusedTask = createTaskInRootTask(focusedRootTask, 0 /* userId */);
        WindowState focusedWindow = createAppWindow(focusedTask, TYPE_APPLICATION, "App Window");
        mDisplayContent.mCurrentFocus = focusedWindow;
        // Tapped home task
        Task tappedRootTask = createTask(
                display, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        Task tappedTask = createTaskInRootTask(tappedRootTask, 0 /* userId */);
        spyOn(mWm.mAtmService);

        mWm.handleTaskFocusChange(tappedTask, null /* window */);

        verify(mWm.mAtmService, never()).setFocusedTask(tappedTask.mTaskId, null);
    }

    @Test
    public void testTaskFocusChange_rootTaskHomeTypeWithDifferentTaskDisplayArea_focusChanges()
            throws RemoteException {
        final DisplayContent display = createNewDisplay();
        final TaskDisplayArea secondTda = createTaskDisplayArea(
                display, mWm, "Tapped TDA", FEATURE_VENDOR_FIRST);
        // Current focused window
        Task focusedRootTask = createTask(
                display, WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        Task focusedTask = createTaskInRootTask(focusedRootTask, 0 /* userId */);
        WindowState focusedWindow = createAppWindow(focusedTask, TYPE_APPLICATION, "App Window");
        mDisplayContent.mCurrentFocus = focusedWindow;
        // Tapped home task on another task display area
        Task tappedRootTask = createTask(secondTda, WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD);
        Task tappedTask = createTaskInRootTask(tappedRootTask, 0 /* userId */);
        spyOn(mWm.mAtmService);

        mWm.handleTaskFocusChange(tappedTask, null /* window */);

        verify(mWm.mAtmService).setFocusedTask(tappedTask.mTaskId, null);
    }

    @Test
    public void testTaskFocusChange_rootTaskHomeTypeWithNonActivityFocusOnSameDA_focusNotChange() {
        final DisplayContent display = createNewDisplay();

        // Current focused window
        final WindowState focusedWindow = newWindowBuilder("shade", TYPE_NOTIFICATION_SHADE)
                .setDisplay(display)
                .build();
        spyOn(mWm);
        doReturn(focusedWindow).when(mWm).getFocusedWindowLocked();

        // Tapped home task
        final Task tappedRootTask = createTask(
                display, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        final Task tappedTask = createTaskInRootTask(tappedRootTask, 0 /* userId */);
        spyOn(mWm.mAtmService);

        mWm.handleTaskFocusChange(tappedTask, null /* window */);

        verify(mWm.mAtmService, never()).setFocusedTask(tappedTask.mTaskId, null);
    }

    @Test
    public void testTaskFocusChange_rootTaskHomeTypeWithNonActivityFocusOnDiffDA_focusChange() {
        final DisplayContent display1 = createNewDisplay();
        final DisplayContent display2 = createNewDisplay();

        // Current focused window
        final WindowState focusedWindow = newWindowBuilder("shade", TYPE_NOTIFICATION_SHADE)
                .setDisplay(display1)
                .build();
        spyOn(mWm);
        doReturn(focusedWindow).when(mWm).getFocusedWindowLocked();

        // Tapped home task of different display
        final Task tappedRootTask = createTask(
                display2, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        final Task tappedTask = createTaskInRootTask(tappedRootTask, 0 /* userId */);
        spyOn(mWm.mAtmService);

        mWm.handleTaskFocusChange(tappedTask, null /* window */);

        verify(mWm.mAtmService).setFocusedTask(tappedTask.mTaskId, null);
    }

    @Test
    public void testTrackOverlayWindow() {
        final WindowProcessController wpc = mSystemServicesTestRule.addProcess(
                "pkgName", "processName", 1000 /* pid */, Process.SYSTEM_UID);
        final Session session = createTestSession(mAtm, wpc.getPid(), wpc.mUid);
        spyOn(session);
        assertTrue(session.mCanAddInternalSystemWindow);
        final WindowState window = newWindowBuilder("win", LayoutParams.TYPE_PHONE).build();
        session.onWindowSurfaceVisibilityChanged(window, true /* visible */);
        verify(session).setHasOverlayUi(true);
        session.onWindowSurfaceVisibilityChanged(window, false /* visible */);
        verify(session).setHasOverlayUi(false);
    }

    @Test
    public void testRelayoutExitingWindow() {
        final WindowState win = newWindowBuilder("appWin", TYPE_BASE_APPLICATION).build();
        win.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
        win.mWinAnimator.mSurfaceControl = mock(SurfaceControl.class);
        spyOn(win);
        doReturn(true).when(win).isSelfAnimating(anyInt(), anyInt());
        win.mViewVisibility = View.VISIBLE;
        win.mHasSurface = true;
        win.mActivityRecord.mAppStopped = true;
        mWm.mWindowMap.put(win.mClient.asBinder(), win);
        spyOn(mWm.mWindowPlacerLocked);
        // Skip unnecessary operations of relayout.
        doNothing().when(mWm.mWindowPlacerLocked).performSurfacePlacement(anyBoolean());
        final int w = 100;
        final int h = 200;
        final ClientWindowFrames outFrames = new ClientWindowFrames();
        final MergedConfiguration outConfig = new MergedConfiguration();
        final SurfaceControl outSurfaceControl = new SurfaceControl();
        final InsetsState outInsetsState = new InsetsState();
        final InsetsSourceControl.Array outControls = new InsetsSourceControl.Array();
        final WindowRelayoutResult outRelayoutResult = new WindowRelayoutResult(outFrames,
                outConfig, outInsetsState, outControls);
        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.GONE, 0, 0, 0,
                outRelayoutResult, outSurfaceControl);
        // The window is animating, so its destruction is deferred.
        assertTrue(win.mAnimatingExit);
        assertFalse(win.mDestroying);

        doReturn(false).when(win).isSelfAnimating(anyInt(), anyInt());
        win.mAnimatingExit = false;
        win.mViewVisibility = View.VISIBLE;
        win.mActivityRecord.setVisibleRequested(false);
        win.mActivityRecord.setVisible(false);
        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.GONE, 0, 0, 0,
                outRelayoutResult, outSurfaceControl);
        // Because the window is already invisible, it doesn't need to apply exiting animation
        // and WMS#tryStartExitingAnimation() will destroy the surface directly.
        assertFalse(win.mAnimatingExit);
        assertFalse(win.mHasSurface);
        assertNull(win.mWinAnimator.mSurfaceControl);

        // If the previous relayout-to-invisible comes after the next visible request, it doesn't
        // need to destroy the surface.
        if (com.android.window.flags.Flags.avoidIntermediateDestroyingState()) {
            win.mActivityRecord.mAppStopped = false;
            win.mViewVisibility = View.VISIBLE;
            win.mHasSurface = true;
            win.mWinAnimator.mSurfaceControl = mock(SurfaceControl.class);
            requestTransition(win.mActivityRecord, WindowManager.TRANSIT_OPEN);
            win.mActivityRecord.setVisibility(true);
            mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.GONE, 0, 0, 0,
                    outRelayoutResult, outSurfaceControl);
            assertFalse(win.mDestroying);
            assertTrue(win.mHasSurface);
        }

        // Invisible requested activity should not get the last config even if its view is visible.
        win.mActivityRecord.setVisibleRequested(false);
        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.VISIBLE, 0, 0, 0,
                outRelayoutResult, outSurfaceControl);
        assertEquals(0, outConfig.getMergedConfiguration().densityDpi);
        // Non activity window can still get the last config.
        win.mActivityRecord = null;
        win.fillClientWindowFramesAndConfiguration(outFrames, outConfig,
                null /* outActivityWindowInfo*/, false /* useLatestConfig */,
                true /* relayoutVisible */);
        assertEquals(win.getConfiguration().densityDpi,
                outConfig.getMergedConfiguration().densityDpi);
    }

    @Test
    public void testRelayout_firstLayout_dwpcHelperCalledWithCorrectFlags() {
        // When doing the first layout, the initial flags should be reported as changed to
        // keepActivityOnWindowFlagsChanged.
        testRelayoutFlagChanges(
                /*firstRelayout=*/ true,
                /*startFlags=*/ FLAG_SECURE,
                /*startPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*newFlags=*/ FLAG_SECURE,
                /*newPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*expectedChangedFlags=*/ FLAG_SECURE,
                /*expectedChangedPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*expectedFlagsValue=*/ FLAG_SECURE,
                /*expectedPrivateFlagsValue=*/ PRIVATE_FLAG_TRUSTED_OVERLAY);
    }

    @Test
    public void testRelayout_secondLayoutFlagAdded_dwpcHelperCalledWithCorrectFlags() {
        testRelayoutFlagChanges(
                /*firstRelayout=*/ false,
                /*startFlags=*/ 0,
                /*startPrivateFlags=*/ 0,
                /*newFlags=*/ FLAG_SECURE,
                /*newPrivateFlags=*/ 0,
                /*expectedChangedFlags=*/ FLAG_SECURE,
                /*expectedChangedPrivateFlags=*/ 0,
                /*expectedFlagsValue=*/ FLAG_SECURE,
                /*expectedPrivateFlagsValue=*/ 0);
    }

    @Test
    public void testRelayout_secondLayoutMultipleFlagsAddOne_dwpcHelperCalledWithCorrectFlags() {
        testRelayoutFlagChanges(
                /*firstRelayout=*/ false,
                /*startFlags=*/ FLAG_NOT_FOCUSABLE,
                /*startPrivateFlags=*/ 0,
                /*newFlags=*/ FLAG_SECURE | FLAG_NOT_FOCUSABLE,
                /*newPrivateFlags=*/ 0,
                /*expectedChangedFlags=*/ FLAG_SECURE,
                /*expectedChangedPrivateFlags=*/ 0,
                /*expectedFlagsValue=*/ FLAG_SECURE | FLAG_NOT_FOCUSABLE,
                /*expectedPrivateFlagsValue=*/ 0);
    }

    @Test
    public void testRelayout_secondLayoutPrivateFlagAdded_dwpcHelperCalledWithCorrectFlags() {
        testRelayoutFlagChanges(
                /*firstRelayout=*/ false,
                /*startFlags=*/ 0,
                /*startPrivateFlags=*/ 0,
                /*newFlags=*/ 0,
                /*newPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*expectedChangedFlags=*/ 0,
                /*expectedChangedPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*expectedFlagsValue=*/ 0,
                /*expectedPrivateFlagsValue=*/ PRIVATE_FLAG_TRUSTED_OVERLAY);
    }

    @Test
    public void testRelayout_secondLayoutFlagsRemoved_dwpcHelperCalledWithCorrectFlags() {
        testRelayoutFlagChanges(
                /*firstRelayout=*/ false,
                /*startFlags=*/ FLAG_SECURE,
                /*startPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*newFlags=*/ 0,
                /*newPrivateFlags=*/ 0,
                /*expectedChangedFlags=*/ FLAG_SECURE,
                /*expectedChangedPrivateFlags=*/ PRIVATE_FLAG_TRUSTED_OVERLAY,
                /*expectedFlagsValue=*/ 0,
                /*expectedPrivateFlagsValue=*/ 0);
    }

    @Test
    public void testRelayout_addTrustedOverlay_permissionDenied() {
        testRelayoutFlagChanges(
                false, /* firstRelayout */
                0, /* startFlags */
                0, /* startPrivateFlags */
                0, /* newFlags */
                PRIVATE_FLAG_TRUSTED_OVERLAY, /* newPrivateFlags */
                0, /* expectedChangedFlags */
                0, /* expectedChangedPrivateFlags */
                0, /* expectedFlagsValue */
                0, /* expectedPrivateFlagsValue */
                false, /* internalSystemWindowGranted */
                true /* manageActivityTasksGranted */);
    }

    @Test
    public void testRelayout_addTrustedOverlay_permissionGranted() {
        testRelayoutFlagChanges(
                false, /* firstRelayout */
                0, /* startFlags */
                0, /* startPrivateFlags */
                0, /* newFlags */
                PRIVATE_FLAG_TRUSTED_OVERLAY, /* newPrivateFlags */
                0, /* expectedChangedFlags */
                PRIVATE_FLAG_TRUSTED_OVERLAY, /* expectedChangedPrivateFlags */
                0, /* expectedFlagsValue */
                PRIVATE_FLAG_TRUSTED_OVERLAY /* expectedPrivateFlagsValue */,
                true, /* internalSystemWindowGranted */
                true /* manageActivityTasksGranted */);
    }

    @Test
    public void testRelayout_addRoundedCornersOverlay_permissionDenied() {
        testRelayoutFlagChanges(
                false, /* firstRelayout */
                0, /* startFlags */
                0, /* startPrivateFlags */
                0, /* newFlags */
                PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY, /* newPrivateFlags */
                0, /* expectedChangedFlags */
                0, /* expectedChangedPrivateFlags */
                0, /* expectedFlagsValue */
                0, /* expectedPrivateFlagsValue */
                false, /* internalSystemWindowGranted */
                true /* manageActivityTasksGranted */);
    }

    @Test
    public void testRelayout_addRoundedCornersOverlay_permissionGranted() {
        testRelayoutFlagChanges(
                false, /* firstRelayout */
                0, /* startFlags */
                0, /* startPrivateFlags */
                0, /* newFlags */
                PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY, /* newPrivateFlags */
                0, /* expectedChangedFlags */
                PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY, /* expectedChangedPrivateFlags */
                0, /* expectedFlagsValue */
                PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY /* expectedPrivateFlagsValue */,
                true, /* internalSystemWindowGranted */
                true /* manageActivityTasksGranted */);
    }

    @Test
    public void testRelayout_addInterceptGlobalDragAndDrop_permissionDenied() {
        testRelayoutFlagChanges(
                false, /* firstRelayout */
                0, /* startFlags */
                0, /* startPrivateFlags */
                0, /* newFlags */
                PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP, /* newPrivateFlags */
                0, /* expectedChangedFlags */
                0, /* expectedChangedPrivateFlags */
                0, /* expectedFlagsValue */
                0, /* expectedPrivateFlagsValue */
                true, /* internalSystemWindowGranted */
                false /* manageActivityTasksGranted */);
    }

    @Test
    public void testRelayout_addInterceptGlobalDragAndDrop_permissionGranted() {
        testRelayoutFlagChanges(
                false, /* firstRelayout */
                0, /* startFlags */
                0, /* startPrivateFlags */
                0, /* newFlags */
                PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP, /* newPrivateFlags */
                0, /* expectedChangedFlags */
                PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP, /* expectedChangedPrivateFlags */
                0, /* expectedFlagsValue */
                PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP /* expectedPrivateFlagsValue */,
                true, /* internalSystemWindowGranted */
                true /* manageActivityTasksGranted */);
    }


    private void testRelayoutFlagChanges(boolean firstRelayout, int startFlags,
            int startPrivateFlags, int newFlags, int newPrivateFlags, int expectedChangedFlags,
            int expectedChangedPrivateFlags, int expectedFlagsValue,
            int expectedPrivateFlagsValue) {
            testRelayoutFlagChanges(firstRelayout, startFlags, startPrivateFlags, newFlags,
                    newPrivateFlags, expectedChangedFlags, expectedChangedPrivateFlags,
                    expectedFlagsValue, expectedPrivateFlagsValue,
                    true /* internalSystemWindowGranted */,
                    true /* manageActivityTasksGranted */);
    }

    // Helper method to test relayout of a window, either for the initial layout, or a subsequent
    // one, and makes sure that the flags and private flags changes and final values are properly
    // reported to mDwpcHelper.keepActivityOnWindowFlagsChanged.
    private void testRelayoutFlagChanges(boolean firstRelayout, int startFlags,
            int startPrivateFlags, int newFlags, int newPrivateFlags, int expectedChangedFlags,
            int expectedChangedPrivateFlags, int expectedFlagsValue,
            int expectedPrivateFlagsValue, boolean internalSystemWindowGranted,
            boolean manageActivityTasksGranted) {
        final WindowState win = newWindowBuilder("appWin", TYPE_BASE_APPLICATION).build();
        win.mRelayoutCalled = !firstRelayout;
        mWm.mWindowMap.put(win.mClient.asBinder(), win);
        spyOn(mDisplayContent.mDwpcHelper);
        when(mDisplayContent.mDwpcHelper.hasController()).thenReturn(true);

        doReturn(internalSystemWindowGranted ? PackageManager.PERMISSION_GRANTED
                : PackageManager.PERMISSION_DENIED).when(mWm.mContext).checkPermission(
                eq(android.Manifest.permission.INTERNAL_SYSTEM_WINDOW), anyInt(), anyInt());
        doReturn(manageActivityTasksGranted ? PackageManager.PERMISSION_GRANTED
                : PackageManager.PERMISSION_DENIED).when(mWm.mContext).checkPermission(
                eq(android.Manifest.permission.MANAGE_ACTIVITY_TASKS), anyInt(), anyInt());

        win.mAttrs.flags = startFlags;
        win.mAttrs.privateFlags = startPrivateFlags;

        LayoutParams newParams = new LayoutParams();
        newParams.copyFrom(win.mAttrs);
        newParams.flags = newFlags;
        newParams.privateFlags = newPrivateFlags;

        int seq = 1;
        if (!firstRelayout) {
            win.mRelayoutSeq = 1;
            seq = 2;
        }
        mWm.relayoutWindow(win.mSession, win.mClient, newParams, 100, 200, View.VISIBLE, 0, seq,
                0, new WindowRelayoutResult(), new SurfaceControl());

        ArgumentCaptor<Integer> changedFlags = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> changedPrivateFlags = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> flagsValue = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> privateFlagsValue = ArgumentCaptor.forClass(Integer.class);

        if (!firstRelayout && expectedChangedFlags == 0 && expectedChangedPrivateFlags == 0) {
            verify(mDisplayContent.mDwpcHelper, never()).keepActivityOnWindowFlagsChanged(
                    any(ActivityInfo.class), anyInt(), anyInt(), anyInt(), anyInt());
            return;
        }

        verify(mDisplayContent.mDwpcHelper).keepActivityOnWindowFlagsChanged(
                any(ActivityInfo.class), changedFlags.capture(), changedPrivateFlags.capture(),
                flagsValue.capture(), privateFlagsValue.capture());

        assertThat(changedFlags.getValue()).isEqualTo(expectedChangedFlags);
        assertThat(changedPrivateFlags.getValue()).isEqualTo(expectedChangedPrivateFlags);
        assertThat(flagsValue.getValue()).isEqualTo(expectedFlagsValue);
        assertThat(privateFlagsValue.getValue()).isEqualTo(expectedPrivateFlagsValue);
    }

    @Test
    public void testMoveWindowTokenToDisplay_NullToken_DoNothing() {
        mWm.moveWindowTokenToDisplay(null, mDisplayContent.getDisplayId());

        verify(mDisplayContent, never()).reParentWindowToken(any());
    }

    @Test
    public void testMoveWindowTokenToDisplay_SameDisplay_DoNothing() {
        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD_DIALOG,
                mDisplayContent);

        mWm.moveWindowTokenToDisplay(windowToken.token, mDisplayContent.getDisplayId());

        verify(mDisplayContent, never()).reParentWindowToken(any());
    }

    @Test
    public void testMoveWindowTokenToDisplay_DifferentDisplay_DoMoveDisplay() {
        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD_DIALOG,
                mDisplayContent);

        mWm.moveWindowTokenToDisplay(windowToken.token, DEFAULT_DISPLAY);

        assertThat(windowToken.getDisplayContent()).isEqualTo(mDefaultDisplay);
    }

    @Test
    public void testAttachWindowContextToWindowToken_InvalidToken_EarlyReturn() {
        spyOn(mWm.mWindowContextListenerController);

        mWm.attachWindowContextToWindowToken(mAppThread, new Binder(), new Binder());

        verify(mWm.mWindowContextListenerController, never()).getWindowType(any());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachWindowContextToWindowToken_InvalidWindowType_ThrowException() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        doReturn(INVALID_WINDOW_TYPE).when(mWm.mWindowContextListenerController)
                .getWindowType(any());

        mWm.attachWindowContextToWindowToken(mAppThread, new Binder(), windowToken.token);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAttachWindowContextToWindowToken_DifferentWindowType_ThrowException() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        doReturn(TYPE_APPLICATION).when(mWm.mWindowContextListenerController)
                .getWindowType(any());

        mWm.attachWindowContextToWindowToken(mAppThread, new Binder(), windowToken.token);
    }

    @Test
    public void testAttachWindowContextToWindowToken_CallerNotValid_EarlyReturn() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        doReturn(TYPE_INPUT_METHOD).when(mWm.mWindowContextListenerController)
                .getWindowType(any());
        doReturn(false).when(mWm.mWindowContextListenerController)
                .assertCallerCanModifyListener(any(), anyBoolean(), anyInt());

        mWm.attachWindowContextToWindowToken(mAppThread, new Binder(), windowToken.token);

        verify(mWm.mWindowContextListenerController, never()).registerWindowContainerListener(
                any(), any(), any(), anyInt(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    public void testAttachWindowContextToWindowToken_CallerValid_DoRegister() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowToken windowToken = createTestWindowToken(TYPE_INPUT_METHOD, mDefaultDisplay);
        doReturn(TYPE_INPUT_METHOD).when(mWm.mWindowContextListenerController)
                .getWindowType(any());
        doReturn(true).when(mWm.mWindowContextListenerController)
                .assertCallerCanModifyListener(any(), anyBoolean(), anyInt());

        final IBinder clientToken = new Binder();
        mWm.attachWindowContextToWindowToken(mAppThread, clientToken, windowToken.token);
        final WindowProcessController wpc = mAtm.getProcessController(mAppThread);
        verify(mWm.mWindowContextListenerController).registerWindowContainerListener(wpc,
                clientToken, windowToken, TYPE_INPUT_METHOD,
                true /* callerCanManageAppTokens */, windowToken.mOptions,
                false /* shouldDispatchConfigWhenRegistering */);
    }

    @Test
    public void testScreenshotOverlayIsBehindAccessibilityOverlay_AddedWhenPlayingTransition() {
        TestTransitionPlayer player = registerTestTransitionPlayer();

        final Session session = createTestSession(mAtm, 1234 /* pid */, Process.SYSTEM_UID);

        final Binder accessibilityToken = new Binder();
        mWm.addWindowToken(accessibilityToken, TYPE_ACCESSIBILITY_OVERLAY, DEFAULT_DISPLAY, null);

        final WindowManager.LayoutParams accessibilityParams =
                new WindowManager.LayoutParams(TYPE_ACCESSIBILITY_OVERLAY);
        accessibilityParams.token = accessibilityToken;
        final IWindow accessibilityWindow = new TestIWindow();
        mWm.addWindow(session, accessibilityWindow, accessibilityParams, View.VISIBLE,
                DEFAULT_DISPLAY, UserHandle.USER_SYSTEM, WindowInsets.Type.defaultVisible(), null,
                new WindowRelayoutResult());

        // Generate an activity launch transition
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setCreateTask(true).setVisible(false).build();
        requestTransition(activity, WindowManager.TRANSIT_OPEN);
        mWm.mRoot.resumeFocusedTasksTopActivities();
        final Transition transition = activity.mTransitionController.getCollectingTransition();
        assertNotNull(transition);
        mWm.mAnimator.ready();
        activity.mTransitionController.requestStartTransition(
                transition, activity.getTask(), null, null);
        assertEquals(transition, player.mLastTransit);
        player.startTransition();

        final WindowManager.LayoutParams screenshotParams =
                new WindowManager.LayoutParams(TYPE_SCREENSHOT);
        final IWindow screenshotWindow = new TestIWindow();
        mWm.addWindow(session, screenshotWindow, screenshotParams, View.VISIBLE, DEFAULT_DISPLAY,
                UserHandle.USER_SYSTEM, WindowInsets.Type.defaultVisible(), null,
                new WindowRelayoutResult());

        final WindowState screenshotWindowState = mWm.windowForClient(session, screenshotWindow);
        assertTrue(player.mLastTransit.isInTransition(screenshotWindowState));

        final ActionChain chain = ActionChain.testFinish(player.mLastTransit);
        player.mLastTransit.onTransactionReady(player.mLastTransit.getSyncId(), mTransaction);
        player.mLastTransit.finishTransition(chain);

        final ArgumentCaptor<Integer> screenshotLayerCaptor =
                ArgumentCaptor.forClass(Integer.class);
        verify(mTransaction, atLeastOnce()).setLayer(
                eq(screenshotWindowState.getParentSurfaceControl()),
                screenshotLayerCaptor.capture());

        final ArgumentCaptor<Integer> accessibilityLayerCaptor =
                ArgumentCaptor.forClass(Integer.class);
        final WindowState accessibilityWindowState =
                mWm.windowForClient(session, accessibilityWindow);
        verify(mTransaction, atLeastOnce()).setLayer(
                eq(accessibilityWindowState.getParentSurfaceControl()),
                accessibilityLayerCaptor.capture());

        assertTrue(screenshotLayerCaptor.getValue() < accessibilityLayerCaptor.getValue());
    }

    @Test
    public void testAddWindowWithSubWindowTypeByWindowContext() {
        spyOn(mWm.mWindowContextListenerController);

        final WindowState parentWin = newWindowBuilder("ime", TYPE_INPUT_METHOD).build();
        final IBinder parentToken = parentWin.mToken.token;
        parentWin.mAttrs.token = parentToken;
        mWm.mWindowMap.put(parentToken, parentWin);
        final Session session = parentWin.mSession;
        session.onWindowAdded(parentWin);
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                TYPE_APPLICATION_ATTACHED_DIALOG);
        params.token = parentToken;
        params.setTitle("attached-dialog");
        final IBinder windowContextToken = new Binder();
        params.setWindowContextToken(windowContextToken);
        doReturn(true).when(mWm.mWindowContextListenerController)
                .hasListener(eq(windowContextToken));
        doReturn(TYPE_INPUT_METHOD).when(mWm.mWindowContextListenerController)
                .getWindowType(eq(windowContextToken));
        // Clean up with the allow_current_user_access_unassigned_displays_in_mumd flag.
        if (Flags.currentUserAccessUnassignedDisplays()) {
            doReturn(UserHandle.getUserId(session.mUid))
                .when(mWm.mUmInternal).getUserAssignedToDisplay(anyInt());
        } else {
            doReturn(true).when(mWm.mUmInternal).isUserVisible(anyInt(), anyInt());
        }

        mWm.addWindow(session, new TestIWindow(), params, View.VISIBLE, DEFAULT_DISPLAY,
                UserHandle.USER_SYSTEM, WindowInsets.Type.defaultVisible(), null,
                new WindowRelayoutResult());

        verify(mWm.mWindowContextListenerController, never()).registerWindowContainerListener(any(),
                any(), any(), anyInt(), anyBoolean(), any(), anyBoolean());

        // Even if the given display id is INVALID_DISPLAY, the specified params.token should be
        // able to map the corresponding display.
        final int result = mWm.addWindow(
                session, new TestIWindow(), params, View.VISIBLE, INVALID_DISPLAY,
                UserHandle.USER_SYSTEM, WindowInsets.Type.defaultVisible(), null,
                new WindowRelayoutResult());
        assertThat(result).isAtLeast(WindowManagerGlobal.ADD_OKAY);

        assertTrue(parentWin.hasChild());
        assertTrue(parentWin.isAttached());
        session.binderDied();
        assertFalse(parentWin.hasChild());
        assertFalse(parentWin.isAttached());
    }

    @Test
    @EnableFlags(android.view.inputmethod.Flags.FLAG_WARM_WORK_PROFILE_IME)
    public void testAddWindow_staleImeWindow_ignored() {
        // Setup the active IME token on the display
        final WindowToken activeImeToken = createImeWindowToken(mDisplayContent);
        mDisplayContent.getImeContainer().setImeWindowToken(activeImeToken.asImeToken());

        // Create a stale IME window token
        final WindowToken staleImeToken = createImeWindowToken(mDisplayContent);

        // Add an IME window using the stale token
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(TYPE_INPUT_METHOD);
        params.token = staleImeToken.token;
        final IWindow staleImeWindow = new TestIWindow();
        final Session session = createTestSession(mAtm, 1234 /* pid */, Process.SYSTEM_UID);

        final int result = mWm.addWindow(session, staleImeWindow, params, View.VISIBLE,
                DEFAULT_DISPLAY, UserHandle.USER_SYSTEM, WindowInsets.Type.defaultVisible(), null,
                new WindowRelayoutResult());
        assertThat(result).isAtLeast(WindowManagerGlobal.ADD_OKAY);

        final WindowState win = mWm.windowForClient(session, staleImeWindow);
        // The display's IME window should not be set to the stale window
        assertThat(mDisplayContent.getImeWindow()).isNotEqualTo(win);
    }

    @Test
    @EnableFlags(android.view.inputmethod.Flags.FLAG_WARM_WORK_PROFILE_IME)
    public void testAddWindow_validImeWindow_added() {
        // Setup the active IME token on the display
        final WindowToken activeImeToken = createImeWindowToken(mDisplayContent);
        mDisplayContent.getImeContainer().setImeWindowToken(activeImeToken.asImeToken());

        // Add an IME window using the active token
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(TYPE_INPUT_METHOD);
        params.token = activeImeToken.token;
        final IWindow activeImeWindow = new TestIWindow();
        final Session session = createTestSession(mAtm, 1234 /* pid */, Process.SYSTEM_UID);

        final int result = mWm.addWindow(session, activeImeWindow, params, View.VISIBLE,
                DEFAULT_DISPLAY, UserHandle.USER_SYSTEM, WindowInsets.Type.defaultVisible(), null,
                new WindowRelayoutResult());
        assertThat(result).isAtLeast(WindowManagerGlobal.ADD_OKAY);

        final WindowState win = mWm.windowForClient(session, activeImeWindow);
        // The display's IME window should be set to the valid window
        assertThat(mDisplayContent.getImeWindow()).isEqualTo(win);
    }

    @Test
    public void testIsInTouchMode_returnsDefaultInTouchModeForinexistingDisplay() {
        assertThat(mWm.isInTouchMode(INVALID_DISPLAY)).isEqualTo(
                mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_defaultInTouchMode));
    }

    @Test
    public void testSetInTouchMode_instrumentedProcessGetPermissionToSwitchTouchMode() {
        // Enable global touch mode
        mWm.mPerDisplayFocusEnabled = true;

        // Get current touch mode state and setup WMS to run setInTouchMode
        boolean currentTouchMode = mWm.isInTouchMode(DEFAULT_DISPLAY);
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(true);

        mWm.setInTouchMode(!currentTouchMode, DEFAULT_DISPLAY);

        verify(mWm.mInputManager).setInTouchMode(
                !currentTouchMode, callingPid, callingUid, /* hasPermission= */ true,
                DEFAULT_DISPLAY);
    }

    @Test
    public void testSetInTouchMode_nonInstrumentedProcessDontGetPermissionToSwitchTouchMode() {
        // Enable global touch mode
        mWm.mPerDisplayFocusEnabled = true;

        // Get current touch mode state and setup WMS to run setInTouchMode
        boolean currentTouchMode = mWm.isInTouchMode(DEFAULT_DISPLAY);
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(false);

        mWm.setInTouchMode(!currentTouchMode, DEFAULT_DISPLAY);

        verify(mWm.mInputManager).setInTouchMode(
                !currentTouchMode, callingPid, callingUid, /* hasPermission= */ false,
                DEFAULT_DISPLAY);
    }

    @Test
    public void testSetInTouchMode_multiDisplay_globalTouchModeUpdate() {
        // Disable global touch mode
        mWm.mPerDisplayFocusEnabled = false;

        // Create one extra display
        final DisplayContent display = createMockSimulatedDisplay();
        display.getDisplayInfo().flags &= ~FLAG_OWN_FOCUS;
        final DisplayContent displayOwnTouchMode = createMockSimulatedDisplay();
        displayOwnTouchMode.getDisplayInfo().flags |= FLAG_OWN_FOCUS;
        final int numberOfDisplays = mWm.mRoot.mChildren.size();
        assertThat(numberOfDisplays).isAtLeast(3);
        final int numberOfGlobalTouchModeDisplays = (int) mWm.mRoot.mChildren.stream()
                .filter(d -> (d.getDisplayInfo().flags & FLAG_OWN_FOCUS) == 0)
                .count();
        assertThat(numberOfGlobalTouchModeDisplays).isAtLeast(2);

        // Get current touch mode state and setup WMS to run setInTouchMode
        boolean currentTouchMode = mWm.isInTouchMode(DEFAULT_DISPLAY);
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(true);

        mWm.setInTouchMode(!currentTouchMode, DEFAULT_DISPLAY);

        verify(mWm.mInputManager, times(numberOfGlobalTouchModeDisplays)).setInTouchMode(
                eq(!currentTouchMode), eq(callingPid), eq(callingUid),
                /* hasPermission= */ eq(true), /* displayId= */ anyInt());
    }

    @Test
    public void testSetInTouchMode_multiDisplay_singleDisplayTouchModeUpdate() {
        // Enable global touch mode
        mWm.mPerDisplayFocusEnabled = true;

        // Create one extra display
        final DisplayContent virtualDisplay = createMockSimulatedDisplay();
        virtualDisplay.getDisplayInfo().flags &= ~FLAG_OWN_FOCUS;
        final int numberOfDisplays = mWm.mRoot.mChildren.size();
        assertThat(numberOfDisplays).isAtLeast(2);

        // Get current touch mode state and setup WMS to run setInTouchMode
        boolean currentTouchMode = mWm.isInTouchMode(DEFAULT_DISPLAY);
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(true);

        mWm.setInTouchMode(!currentTouchMode, virtualDisplay.mDisplayId);

        // Ensure that new display touch mode state has changed.
        verify(mWm.mInputManager).setInTouchMode(
                !currentTouchMode, callingPid, callingUid, /* hasPermission= */ true,
                virtualDisplay.mDisplayId);

        // Disable global touch mode and make the virtual display own focus.
        mWm.mPerDisplayFocusEnabled = false;
        virtualDisplay.getDisplayInfo().flags |= FLAG_OWN_FOCUS;
        clearInvocations(mWm.mInputManager);
        mWm.setInTouchMode(!currentTouchMode, virtualDisplay.mDisplayId);

        // Ensure that new display touch mode state has changed.
        verify(mWm.mInputManager).setInTouchMode(
                !currentTouchMode, callingPid, callingUid, /* hasPermission= */ true,
                virtualDisplay.mDisplayId);
    }

    @Test
    public void testSetInTouchModeOnAllDisplays() {
        // Create a couple of extra displays.
        // setInTouchModeOnAllDisplays should ignore the ownFocus setting.
        final DisplayContent display = createMockSimulatedDisplay();
        display.getDisplayInfo().flags &= ~FLAG_OWN_FOCUS;
        final DisplayContent displayOwnTouchMode = createMockSimulatedDisplay();
        displayOwnTouchMode.getDisplayInfo().flags |= FLAG_OWN_FOCUS;

        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        doReturn(true).when(mWm.mInputManager).setInTouchMode(anyBoolean(), anyInt(),
                anyInt(), anyBoolean(), anyInt());
        doReturn(false).when(mWm).checkCallingPermission(anyString(), anyString(), anyBoolean());
        when(mWm.mAtmService.instrumentationSourceHasPermission(callingPid,
                android.Manifest.permission.MODIFY_TOUCH_MODE_STATE)).thenReturn(true);

        final Runnable verification = () -> {
            for (boolean inTouchMode : new boolean[] { true, false }) {
                mWm.setInTouchModeOnAllDisplays(inTouchMode);
                for (int i = 0; i < mRootWindowContainer.getChildCount(); ++i) {
                    final DisplayContent dc = mRootWindowContainer.getChildAt(i);
                    // All displays that are not already in the desired touch mode are requested to
                    // change their touch mode.
                    if (dc.isInTouchMode() != inTouchMode) {
                        verify(mWm.mInputManager, description("perDisplayFocusEnabled="
                                + mWm.mPerDisplayFocusEnabled)).setInTouchMode(true,
                                callingPid, callingUid, /* hasPermission= */ true, dc.mDisplayId);
                    }
                }
            }
        };

        mWm.mPerDisplayFocusEnabled = false;
        verification.run();

        clearInvocations(mWm.mInputManager);
        mWm.mPerDisplayFocusEnabled = true;
        verification.run();
    }

    @Test
    public void testGetTaskWindowContainerTokenForRecordingSession_invalidCookie() {
        Binder cookie = new Binder("test cookie");
        WindowContainerInfo wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(cookie));
        assertThat(wci).isNull();

        final ActivityRecord testActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .build();

        wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(cookie));
        assertThat(wci).isNull();
    }

    @Test
    public void testGetTaskWindowContainerTokenForRecordingSession_validCookie() {
        final Binder cookie = new Binder("ginger cookie");
        final WindowContainerToken launchRootTask = mock(WindowContainerToken.class);
        final int uid = 123;
        setupActivityWithLaunchCookie(cookie, launchRootTask, uid);

        WindowContainerInfo wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(cookie));
        mExpect.that(wci.getToken()).isEqualTo(launchRootTask);
        mExpect.that(wci.getUid()).isEqualTo(uid);
    }

    @Test
    public void testGetTaskWindowContainerTokenForRecordingSession_validTaskId() {
        final WindowContainerToken launchRootTask = mock(WindowContainerToken.class);
        final WindowContainer.RemoteToken remoteToken = mock(WindowContainer.RemoteToken.class);
        when(remoteToken.toWindowContainerToken()).thenReturn(launchRootTask);

        final int uid = 123;
        final ActivityRecord testActivity =
                new ActivityBuilder(mAtm).setCreateTask(true).setUid(uid).build();
        testActivity.mLaunchCookie = null;
        testActivity.getTask().mRemoteToken = remoteToken;

        WindowContainerInfo wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(
                        new Binder("cookie"), testActivity.getTask().mTaskId));
        mExpect.that(wci.getToken()).isEqualTo(launchRootTask);
        mExpect.that(wci.getUid()).isEqualTo(uid);
    }

    @Test
    public void testGetTaskWindowContainerTokenForRecordingSession_multipleCookies() {
        final Binder cookie1 = new Binder("ginger cookie");
        final WindowContainerToken launchRootTask1 = mock(WindowContainerToken.class);
        final int uid1 = 123;
        setupActivityWithLaunchCookie(cookie1, launchRootTask1, uid1);

        setupActivityWithLaunchCookie(new Binder("choc chip cookie"),
                mock(WindowContainerToken.class), /* uid= */ 456);

        setupActivityWithLaunchCookie(new Binder("peanut butter cookie"),
                mock(WindowContainerToken.class), /* uid= */ 789);

        WindowContainerInfo wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(cookie1));
        mExpect.that(wci.getToken()).isEqualTo(launchRootTask1);
        mExpect.that(wci.getUid()).isEqualTo(uid1);
    }

    @Test
    public void testGetTaskWindowContainerTokenForRecordingSession_multipleCookies_noneValid() {
        setupActivityWithLaunchCookie(new Binder("ginger cookie"),
                mock(WindowContainerToken.class), /* uid= */ 123);

        setupActivityWithLaunchCookie(new Binder("choc chip cookie"),
                mock(WindowContainerToken.class), /* uid= */ 456);

        setupActivityWithLaunchCookie(new Binder("peanut butter cookie"),
                mock(WindowContainerToken.class), /* uid= */ 789);

        WindowContainerInfo wci = mWm.getTaskWindowContainerInfoForRecordingSession(
                ContentRecordingSession.createTaskSession(new Binder("some other cookie")));
        assertThat(wci).isNull();
    }

    @Test
    public void setContentRecordingSession_sessionNull_returnsTrue() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);

        boolean result = wmInternal.setContentRecordingSession(/* incomingSession= */ null);

        assertThat(result).isTrue();
    }

    @Test
    public void setContentRecordingSession_sessionContentDisplay_returnsTrue() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(
                DEFAULT_DISPLAY);

        boolean result = wmInternal.setContentRecordingSession(session);

        assertThat(result).isTrue();
    }

    @Test
    public void setContentRecordingSession_sessionContentTask_noMatchingTask_returnsFalse() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        IBinder launchCookie = new Binder();
        ContentRecordingSession session = ContentRecordingSession.createTaskSession(launchCookie);

        boolean result = wmInternal.setContentRecordingSession(session);

        assertThat(result).isFalse();
    }

    @Test
    public void setContentRecordingSession_sessionContentTask_matchingTask_returnsTrue() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        ActivityRecord activityRecord = createActivityRecord(createTask(mDefaultDisplay));
        activityRecord.mLaunchCookie = new Binder();
        ContentRecordingSession session = ContentRecordingSession.createTaskSession(
                activityRecord.mLaunchCookie);

        boolean result = wmInternal.setContentRecordingSession(session);

        assertThat(result).isTrue();
    }

    @Test
    public void setContentRecordingSession_matchingTask_mutatesSessionWithWindowContainerInfo() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        Task task = createTask(mDefaultDisplay);
        ActivityRecord activityRecord = createActivityRecord(task);
        activityRecord.mLaunchCookie = new Binder();
        ContentRecordingSession session =
                ContentRecordingSession.createTaskSession(activityRecord.mLaunchCookie);

        wmInternal.setContentRecordingSession(session);

        mExpect.that(session.getTokenToRecord())
                .isEqualTo(task.mRemoteToken.toWindowContainerToken().asBinder());
        mExpect.that(session.getTargetUid()).isEqualTo(task.effectiveUid);
    }

    @Test
    public void notifyScreenshotListeners_detectionDisabledSuppressesCallbacksButKeepsObserver() {
        final String setting = Settings.Global.DISABLE_SCREEN_CAPTURE_DETECTION;
        final int previousValue = Settings.Global.getInt(mContext.getContentResolver(), setting, 0);
        final int[] notificationCount = {0};
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final IScreenCaptureObserver observer = new IScreenCaptureObserver.Stub() {
            @Override
            public void onScreenCaptured() {
                notificationCount[0]++;
            }
        };
        activity.registerCaptureObserver(observer);

        try {
            Settings.Global.putInt(mContext.getContentResolver(), setting, 1);
            assertThat(mWm.notifyScreenshotListeners(DEFAULT_DISPLAY)).isEmpty();
            assertThat(notificationCount[0]).isEqualTo(0);
            assertThat(activity.isRegisteredForScreenCaptureCallback()).isTrue();

            Settings.Global.putInt(mContext.getContentResolver(), setting, 0);
            assertThat(mWm.notifyScreenshotListeners(DEFAULT_DISPLAY))
                    .contains(activity.mActivityComponent);
            assertThat(notificationCount[0]).isEqualTo(1);
        } finally {
            Settings.Global.putInt(mContext.getContentResolver(), setting, previousValue);
            activity.unregisterCaptureObserver(observer);
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION)
    public void shouldBlockScreenCaptureForNotificationApps() {
        String testPackage = "test";
        int ownerId1 = 20;
        int ownerId2 = 21;
        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        verify(mWm).refreshScreenCaptureDisabled();

        // window client token parameter is ignored for this feature.
        assertTrue(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId1, new Binder()));
        assertFalse(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId2, new Binder()));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_SENSITIVE_CONTENT_APP_PROTECTION)
    public void shouldBlockScreenCaptureForSensitiveContentOnScreenApps() {
        String testPackage = "test";
        int ownerId1 = 20;
        final IBinder windowClientToken = new Binder("window client token");
        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1, windowClientToken);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        verify(mWm).refreshScreenCaptureDisabled();

        assertTrue(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId1, windowClientToken));
        assertFalse(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId1, new Binder()));
    }

    @Test
    @RequiresFlagsEnabled(
            {FLAG_SENSITIVE_CONTENT_APP_PROTECTION, FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION})
    public void shouldBlockScreenCaptureForApps() {
        String testPackage = "test";
        int ownerId1 = 20;
        int ownerId2 = 21;
        final IBinder windowClientToken = new Binder("window client token");
        PackageInfo blockedPackage1 = new PackageInfo(testPackage, ownerId1);
        PackageInfo blockedPackage2 = new PackageInfo(testPackage, ownerId1, windowClientToken);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage1);
        blockedPackages.add(blockedPackage2);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        verify(mWm).refreshScreenCaptureDisabled();

        assertTrue(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId1, windowClientToken));
        assertTrue(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId1, new Binder()));
        assertFalse(mWm.mSensitiveContentPackages
                .shouldBlockScreenCaptureForApp(testPackage, ownerId2, new Binder()));
    }

    @Test
    public void addBlockScreenCaptureForApps_duplicate_verifyNoRefresh() {
        String testPackage = "test";
        int ownerId1 = 20;
        int ownerId2 = 21;
        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);

        verify(mWm, times(1)).refreshScreenCaptureDisabled();
    }

    @Test
    public void addBlockScreenCaptureForApps_notDuplicate_verifyRefresh() {
        String testPackage = "test";
        int ownerId1 = 20;
        int ownerId2 = 21;
        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        PackageInfo blockedPackage2 = new PackageInfo(testPackage, ownerId2);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);
        ArraySet<PackageInfo> blockedPackages2 = new ArraySet();
        blockedPackages2.add(blockedPackage);
        blockedPackages2.add(blockedPackage2);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages2);

        verify(mWm, times(2)).refreshScreenCaptureDisabled();
    }

    @Test
    @RequiresFlagsEnabled(
            {FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION, FLAG_SENSITIVE_CONTENT_IMPROVEMENTS,
                    FLAG_SENSITIVE_CONTENT_RECENTS_SCREENSHOT_BUGFIX})
    public void addBlockScreenCaptureForApps_appNotInForeground_invalidateSnapshot() {
        spyOn(mWm.mTaskSnapshotController);

        // createAppWindow uses package name of "test" and uid of "0"
        String testPackage = "test";
        int ownerId1 = 0;

        final Task task = createTask(mDisplayContent);
        final WindowState win = createAppWindow(task, ACTIVITY_TYPE_STANDARD, "appWindow");
        mWm.mWindowMap.put(win.mClient.asBinder(), win);
        final ActivityRecord activity = win.mActivityRecord;
        activity.setVisibleRequested(false);
        activity.setVisible(false);
        win.setHasSurface(false);

        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);

        verify(mWm.mTaskSnapshotController).removeAndDeleteSnapshot(anyInt(), eq(ownerId1));
    }

    @Test
    public void clearBlockedApps_clearsCache() {
        String testPackage = "test";
        int ownerId1 = 20;
        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);

        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.addBlockScreenCaptureForApps(blockedPackages);
        wmInternal.clearBlockedApps();
        verify(mWm, times(2)).refreshScreenCaptureDisabled();
    }

    @Test
    public void clearBlockedApps_alreadyEmpty_verifyNoRefresh() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        wmInternal.clearBlockedApps();

        verify(mWm, never()).refreshScreenCaptureDisabled();
    }

    @Test
    public void backupDisplayWindowSettings_fileNotFound_returnsNull()
            throws FileNotFoundException {
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(DisplayWindowSettingsProvider.class)
                .initMocks(this)
                .startMocking();
        try {
            final int userId = UserHandle.USER_SYSTEM;
            AtomicFile atomicFile = mock(AtomicFile.class);

            doReturn(atomicFile).when(() ->
                    DisplayWindowSettingsProvider.getOverrideSettingsFileForUser(userId));
            when(atomicFile.openRead()).thenThrow(new FileNotFoundException());
            WindowManagerInternal wmInternal =
                    LocalServices.getService(WindowManagerInternal.class);
            byte[] payload = wmInternal.backupDisplayWindowSettings(userId);

            assertThat(payload).isNull();
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void backupDisplayWindowSettings_validFile_returnsPayload()
            throws Exception {
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(DisplayWindowSettingsXmlHelper.class)
                .mockStatic(DisplayWindowSettingsProvider.class)
                .initMocks(this)
                .startMocking();
        try {
            final int userId = UserHandle.USER_SYSTEM;
            final byte[] payload = "test_payload".getBytes(StandardCharsets.UTF_8);
            File tempFile = mTemporaryFolder.newFile();
            Files.writeString(tempFile.toPath(), "file_content");
            AtomicFile atomicFile = new AtomicFile(tempFile);

            doReturn(atomicFile).when(() ->
                    DisplayWindowSettingsProvider.getOverrideSettingsFileForUser(userId));
            doReturn(payload).when(() ->
                    DisplayWindowSettingsXmlHelper.readAndFilterSettings(any(InputStream.class)));
            WindowManagerInternal wmInternal =
                    LocalServices.getService(WindowManagerInternal.class);
            byte[] backupPayload = wmInternal.backupDisplayWindowSettings(userId);

            assertThat(backupPayload).isEqualTo(payload);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void restoreDisplayWindowSettings_fileNotFound_doesNotRestore()
            throws Exception {
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(DisplayWindowSettingsProvider.class)
                .initMocks(this)
                .startMocking();
        try {
            final int userId = UserHandle.USER_SYSTEM;
            final byte[] payload = "test_payload".getBytes(StandardCharsets.UTF_8);
            File settingsFile = new File(mTemporaryFolder.getRoot(), "display_settings.xml");
            AtomicFile atomicFile = new AtomicFile(settingsFile);

            doReturn(atomicFile).when(
                    () -> DisplayWindowSettingsProvider.getOverrideSettingsFileForUser(userId));
            spyOn(mWm.mDisplayWindowSettingsProvider);
            WindowManagerInternal wmInternal =
                    LocalServices.getService(WindowManagerInternal.class);
            wmInternal.restoreDisplayWindowSettings(userId, payload);

            byte[] writtenContent = Files.readAllBytes(settingsFile.toPath());
            assertThat(writtenContent).isEqualTo(payload);
            verify(mWm.mDisplayWindowSettingsProvider).setOverrideSettingsForUser(userId);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void restoreDisplayWindowSettings_writesPayloadAndReloadsSettings()
            throws Exception {
        MockitoSession mockitoSession = mockitoSession()
                .mockStatic(DisplayWindowSettingsProvider.class)
                .initMocks(this)
                .startMocking();
        try {
            final int userId = UserHandle.USER_SYSTEM;
            final byte[] payload = "test_payload".getBytes(StandardCharsets.UTF_8);
            File settingsFile = new File(mTemporaryFolder.getRoot(), "display_settings.xml");
            AtomicFile atomicFile = new AtomicFile(settingsFile);

            doReturn(atomicFile).when(
                    () -> DisplayWindowSettingsProvider.getOverrideSettingsFileForUser(userId));
            spyOn(mWm.mDisplayWindowSettingsProvider);
            WindowManagerInternal wmInternal =
                    LocalServices.getService(WindowManagerInternal.class);
            wmInternal.restoreDisplayWindowSettings(userId, payload);

            byte[] writtenContent = Files.readAllBytes(settingsFile.toPath());
            assertThat(writtenContent).isEqualTo(payload);
            verify(mWm.mDisplayWindowSettingsProvider).setOverrideSettingsForUser(userId);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void testisLetterboxBackgroundMultiColored() {
        assertThat(setupLetterboxConfigurationWithBackgroundType(
                LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING)).isTrue();
        assertThat(setupLetterboxConfigurationWithBackgroundType(
                LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND)).isTrue();
        assertThat(setupLetterboxConfigurationWithBackgroundType(
                LETTERBOX_BACKGROUND_WALLPAPER)).isTrue();
        assertThat(setupLetterboxConfigurationWithBackgroundType(
                LETTERBOX_BACKGROUND_SOLID_COLOR)).isFalse();
    }

    @Test
    public void testCaptureDisplay() {
        Rect displayBounds = new Rect(0, 0, 100, 200);
        spyOn(mDisplayContent);
        when(mDisplayContent.getBounds()).thenReturn(displayBounds);

        // Null captureArgs
        ScreenCaptureInternal.LayerCaptureArgs resultingArgs =
                mWm.getCaptureArgs(DEFAULT_DISPLAY, null /* captureArgs */);
        assertEquals(displayBounds, resultingArgs.mSourceCrop);

        // Non null captureArgs, didn't set rect
        ScreenCaptureInternal.CaptureArgs captureArgs =
                new ScreenCaptureInternal.CaptureArgs.Builder<>().build();
        resultingArgs = mWm.getCaptureArgs(DEFAULT_DISPLAY, captureArgs);
        assertEquals(displayBounds, resultingArgs.mSourceCrop);

        // Non null captureArgs, invalid rect
        captureArgs =
                new ScreenCaptureInternal.CaptureArgs.Builder<>()
                        .setSourceCrop(new Rect(0, 0, -1, -1))
                        .build();
        resultingArgs = mWm.getCaptureArgs(DEFAULT_DISPLAY, captureArgs);
        assertEquals(displayBounds, resultingArgs.mSourceCrop);

        // Non null captureArgs, null rect
        captureArgs = new ScreenCaptureInternal.CaptureArgs.Builder<>().setSourceCrop(null).build();
        resultingArgs = mWm.getCaptureArgs(DEFAULT_DISPLAY, captureArgs);
        assertEquals(displayBounds, resultingArgs.mSourceCrop);

        // Non null captureArgs, valid rect
        Rect validRect = new Rect(0, 0, 10, 50);
        captureArgs =
                new ScreenCaptureInternal.CaptureArgs.Builder<>().setSourceCrop(validRect).build();
        resultingArgs = mWm.getCaptureArgs(DEFAULT_DISPLAY, captureArgs);
        assertEquals(validRect, resultingArgs.mSourceCrop);
    }

    @Test
    public void testGrantInputChannel_sanitizeSpyWindowForApplications() {
        final Session session = mock(Session.class);
        final int callingUid = Process.FIRST_APPLICATION_UID;
        final int callingPid = 1234;
        final SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final IBinder window = new Binder();
        final InputTransferToken inputTransferToken = mock(InputTransferToken.class);

        final WindowInputChannelParams invalidParams = new WindowInputChannelParams();
        invalidParams.displayId = DEFAULT_DISPLAY;
        invalidParams.clientToken = window;
        invalidParams.inputTransferToken = inputTransferToken;
        invalidParams.surface = surfaceControl;
        invalidParams.type = TYPE_APPLICATION;
        invalidParams.flags = FLAG_NOT_FOCUSABLE;
        invalidParams.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY;
        invalidParams.inputFeatures = INPUT_FEATURE_SPY;
        invalidParams.inputHandleName = "TestInputChannel";
        assertThrows(SecurityException.class, () ->
                mWm.grantInputChannel(session, callingUid, callingPid, invalidParams));
    }

    @Test
    public void testGrantInputChannel_allowSpyWindowForInputMonitorPermission() {
        final Session session = mock(Session.class);
        final int callingUid = Process.SYSTEM_UID;
        final int callingPid = 1234;
        final SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final IBinder window = new Binder();
        final InputTransferToken inputTransferToken = mock(InputTransferToken.class);

        final WindowInputChannelParams params = new WindowInputChannelParams();
        params.displayId = DEFAULT_DISPLAY;
        params.clientToken = window;
        params.inputTransferToken = inputTransferToken;
        params.surface = surfaceControl;
        params.type = TYPE_APPLICATION;
        params.flags = FLAG_NOT_FOCUSABLE;
        params.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY;
        params.inputFeatures = INPUT_FEATURE_SPY;
        params.inputHandleName = "TestInputChannel";
        mWm.grantInputChannel(session, callingUid, callingPid, params);

        verify(mTransaction).setInputWindowInfo(
                eq(surfaceControl),
                argThat(h -> (h.inputConfig & InputConfig.SPY) == InputConfig.SPY));
    }

    @Test
    public void testGrantInputChannel_sanitizeDisplayTopologyAwareForManageDisplaysPermission() {
        final Session session = mock(Session.class);
        final int callingUid = Process.FIRST_APPLICATION_UID;
        final int callingPid = 1234;
        final SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final IBinder window = new Binder();
        final InputTransferToken inputTransferToken = mock(InputTransferToken.class);

        final WindowInputChannelParams invalidParams = new WindowInputChannelParams();
        invalidParams.displayId = DEFAULT_DISPLAY;
        invalidParams.clientToken = window;
        invalidParams.inputTransferToken = inputTransferToken;
        invalidParams.surface = surfaceControl;
        invalidParams.type = TYPE_APPLICATION;
        invalidParams.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY;
        invalidParams.inputFeatures = INPUT_FEATURE_DISPLAY_TOPOLOGY_AWARE;
        invalidParams.inputHandleName = "TestInputChannel";
        assertThrows(SecurityException.class, () ->
                mWm.grantInputChannel(session, callingUid, callingPid, invalidParams));
    }

    @Test
    public void testUpdateInputChannel_sanitizeSpyWindowForApplications() {
        final Session session = mock(Session.class);
        final int callingUid = Process.FIRST_APPLICATION_UID;
        final int callingPid = 1234;
        final SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final IBinder window = new Binder();
        final InputTransferToken inputTransferToken = mock(InputTransferToken.class);

        final WindowInputChannelParams params = new WindowInputChannelParams();
        params.displayId = DEFAULT_DISPLAY;
        params.clientToken = window;
        params.inputTransferToken = inputTransferToken;
        params.surface = surfaceControl;
        params.type = TYPE_APPLICATION;
        params.flags = FLAG_NOT_FOCUSABLE;
        params.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY;
        params.inputHandleName = "TestInputChannel";
        final InputChannel inputChannel = mWm.grantInputChannel(session, callingUid, callingPid,
                params);
        verify(mTransaction).setInputWindowInfo(
                eq(surfaceControl),
                argThat(h -> (h.inputConfig & InputConfig.SPY) == 0));

        final WindowInputChannelParams invalidParams = new WindowInputChannelParams();
        invalidParams.displayId = DEFAULT_DISPLAY;
        invalidParams.channelToken = inputChannel.getToken();
        invalidParams.surface = surfaceControl;
        invalidParams.flags = FLAG_NOT_FOCUSABLE;
        invalidParams.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY;
        invalidParams.inputFeatures = INPUT_FEATURE_SPY;
        assertThrows(SecurityException.class, () -> mWm.updateInputChannel(invalidParams));
    }

    @Test
    public void testUpdateInputChannel_allowSpyWindowForInputMonitorPermission() {
        final Session session = mock(Session.class);
        final int callingUid = Process.SYSTEM_UID;
        final int callingPid = 1234;
        final SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final IBinder window = new Binder();
        final InputTransferToken inputTransferToken = mock(InputTransferToken.class);

        final WindowInputChannelParams params = new WindowInputChannelParams();
        params.displayId = DEFAULT_DISPLAY;
        params.clientToken = window;
        params.inputTransferToken = inputTransferToken;
        params.surface = surfaceControl;
        params.type = TYPE_APPLICATION;
        params.flags = FLAG_NOT_FOCUSABLE;
        params.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY;
        params.inputHandleName = "TestInputChannel";
        final InputChannel inputChannel = mWm.grantInputChannel(session, callingUid, callingPid,
                params);
        verify(mTransaction).setInputWindowInfo(
                eq(surfaceControl),
                argThat(h -> (h.inputConfig & InputConfig.SPY) == 0));

        final WindowInputChannelParams updateParams = new WindowInputChannelParams();
        updateParams.displayId = DEFAULT_DISPLAY;
        updateParams.channelToken = inputChannel.getToken();
        updateParams.surface = surfaceControl;
        updateParams.flags = FLAG_NOT_FOCUSABLE;
        updateParams.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY;
        updateParams.inputFeatures = INPUT_FEATURE_SPY;
        mWm.updateInputChannel(updateParams);
        verify(mTransaction).setInputWindowInfo(
                eq(surfaceControl),
                argThat(h -> (h.inputConfig & InputConfig.SPY) == InputConfig.SPY));
    }

    @Test
    public void testUpdateInputChannel_sanitizeInputFeatureSensitive_forUntrustedWindows() {
        final Session session = mock(Session.class);
        final int callingUid = Process.FIRST_APPLICATION_UID;
        final int callingPid = 1234;
        final SurfaceControl surfaceControl = mock(SurfaceControl.class);
        final IBinder window = new Binder();
        final InputTransferToken inputTransferToken = mock(InputTransferToken.class);

        final WindowInputChannelParams invalidParams = new WindowInputChannelParams();
        invalidParams.displayId = DEFAULT_DISPLAY;
        invalidParams.clientToken = window;
        invalidParams.inputTransferToken = inputTransferToken;
        invalidParams.surface = surfaceControl;
        invalidParams.type = TYPE_APPLICATION;
        invalidParams.flags = FLAG_NOT_FOCUSABLE;
        invalidParams.inputFeatures = INPUT_FEATURE_SENSITIVE_FOR_PRIVACY;
        invalidParams.inputHandleName = "TestInputChannel";
        assertThrows(SecurityException.class, () -> mWm.grantInputChannel(session, callingUid,
                callingPid, invalidParams));

        final WindowInputChannelParams params = new WindowInputChannelParams();
        params.displayId = DEFAULT_DISPLAY;
        params.clientToken = window;
        params.inputTransferToken = inputTransferToken;
        params.surface = surfaceControl;
        params.type = TYPE_APPLICATION;
        params.flags = FLAG_NOT_FOCUSABLE;
        params.inputHandleName = "TestInputChannel";
        final InputChannel inputChannel = mWm.grantInputChannel(session, callingUid, callingPid,
                params);

        final WindowInputChannelParams updateParams = new WindowInputChannelParams();
        updateParams.displayId = DEFAULT_DISPLAY;
        updateParams.channelToken = inputChannel.getToken();
        updateParams.surface = surfaceControl;
        updateParams.flags = FLAG_NOT_FOCUSABLE;
        updateParams.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY;
        updateParams.inputFeatures = INPUT_FEATURE_SENSITIVE_FOR_PRIVACY;
        mWm.updateInputChannel(updateParams);
        verify(mTransaction).setInputWindowInfo(
                eq(surfaceControl),
                argThat(h -> (h.inputConfig & InputConfig.SENSITIVE_FOR_PRIVACY) != 0));
    }

    @Test
    public void testRequestKeyboardShortcuts_noWindow() {
        doNothing().when(mWm.mContext).enforceCallingOrSelfPermission(anyString(), anyString());
        doReturn(null).when(mWm).getFocusedWindowLocked();
        doReturn(null).when(mWm.mRoot).getCurrentImeWindow();

        TestResultReceiver receiver = new TestResultReceiver();
        mWm.requestAppKeyboardShortcuts(receiver, 0);
        assertNotNull(receiver.resultData);
        assertTrue(receiver.resultData.isEmpty());

        receiver = new TestResultReceiver();
        mWm.requestImeKeyboardShortcuts(receiver, 0);
        assertNotNull(receiver.resultData);
        assertTrue(receiver.resultData.isEmpty());
    }

    @Test
    public void testRequestKeyboardShortcuts() throws RemoteException {
        final IWindow window = mock(IWindow.class);
        final IBinder binder = mock(IBinder.class);
        doReturn(binder).when(window).asBinder();
        final WindowState windowState = newWindowBuilder("appWin",
                TYPE_BASE_APPLICATION).setDisplay(mDisplayContent).setClientWindow(window).build();
        doNothing().when(mWm.mContext).enforceCallingOrSelfPermission(anyString(), anyString());
        doReturn(windowState).when(mWm).getFocusedWindowLocked();
        doReturn(windowState).when(mWm.mRoot).getCurrentImeWindow();

        TestResultReceiver receiver = new TestResultReceiver();
        mWm.requestAppKeyboardShortcuts(receiver, 0);
        mWm.requestImeKeyboardShortcuts(receiver, 0);
        verify(window, times(2)).requestAppKeyboardShortcuts(receiver, 0);
    }

    @Test
    public void testInputDeviceNotifyConfigurationChanged() {
        spyOn(mDisplayContent);
        doReturn(false).when(mDisplayContent).sendNewConfiguration();
        final InputDevice deviceA = mock(InputDevice.class);
        final InputDevice deviceB = mock(InputDevice.class);
        doReturn("deviceA").when(deviceA).getDescriptor();
        doReturn("deviceB").when(deviceB).getDescriptor();
        final InputDevice[] devices1 = { deviceA };
        final InputDevice[] devices2 = { deviceB, deviceA };
        final Runnable verifySendNewConfiguration = () -> {
            clearInvocations(mDisplayContent);
            mWm.mInputManagerCallback.notifyConfigurationChanged();
            verify(mDisplayContent).sendNewConfiguration();
        };
        doReturn(devices1).when(mWm.mInputManager).getInputDevices();
        verifySendNewConfiguration.run();

        doReturn(devices2).when(mWm.mInputManager).getInputDevices();
        verifySendNewConfiguration.run();

        doReturn(true).when(deviceB).isEnabled();
        verifySendNewConfiguration.run();

        doReturn(true).when(deviceA).isExternal();
        verifySendNewConfiguration.run();

        doReturn(1).when(deviceA).getSources();
        verifySendNewConfiguration.run();

        doReturn(1).when(deviceA).getAssociatedDisplayId();
        verifySendNewConfiguration.run();

        doReturn(1).when(deviceA).getKeyboardType();
        verifySendNewConfiguration.run();

        clearInvocations(mDisplayContent);
        mWm.mInputManagerCallback.notifyConfigurationChanged();
        verify(mDisplayContent, never()).sendNewConfiguration();
    }

    @Test
    public void testReportSystemGestureExclusionChanged_invalidWindow() {
        final Session session = mock(Session.class);
        final IWindow window = mock(IWindow.class);
        final IBinder binder = mock(IBinder.class);
        doReturn(binder).when(window).asBinder();

        // No exception even if the window doesn't exist
        mWm.reportSystemGestureExclusionChanged(session, window, new ArrayList<>());
    }

    @Test
    public void testReportKeepClearAreasChanged_invalidWindow() {
        final Session session = mock(Session.class);
        final IWindow window = mock(IWindow.class);
        final IBinder binder = mock(IBinder.class);
        doReturn(binder).when(window).asBinder();

        // No exception even if the window doesn't exist
        mWm.reportKeepClearAreasChanged(session, window, new ArrayList<>(), new ArrayList<>());
    }

    @Test
    public void testRelayout_appWindowSendActivityWindowInfo() {
        // Skip unnecessary operations of relayout.
        spyOn(mWm.mWindowPlacerLocked);
        doNothing().when(mWm.mWindowPlacerLocked).performSurfacePlacement(anyBoolean());

        final Task task = createTask(mDisplayContent);
        final WindowState win = createAppWindow(task, ACTIVITY_TYPE_STANDARD, "appWindow");
        mWm.mWindowMap.put(win.mClient.asBinder(), win);

        final int w = 100;
        final int h = 200;
        final ClientWindowFrames outFrames = new ClientWindowFrames();
        final MergedConfiguration outConfig = new MergedConfiguration();
        final SurfaceControl outSurfaceControl = new SurfaceControl();
        final InsetsState outInsetsState = new InsetsState();
        final InsetsSourceControl.Array outControls = new InsetsSourceControl.Array();
        final WindowRelayoutResult outRelayoutResult = new WindowRelayoutResult(outFrames,
                outConfig, outInsetsState, outControls);

        final ActivityRecord activity = win.mActivityRecord;
        final ActivityWindowInfo expectedInfo = new ActivityWindowInfo();
        expectedInfo.set(true, new Rect(0, 0, 1000, 2000), new Rect(0, 0, 500, 2000));
        doReturn(expectedInfo).when(activity).getActivityWindowInfo();
        activity.setVisibleRequested(false);
        activity.setVisible(false);

        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.VISIBLE, 0, 0, 0,
                outRelayoutResult, outSurfaceControl);

        // No latest reported value, so return empty when activity is invisible
        final ActivityWindowInfo activityWindowInfo = outRelayoutResult.activityWindowInfo;
        assertEquals(new ActivityWindowInfo(), activityWindowInfo);

        activity.setVisibleRequested(true);
        activity.setVisible(true);

        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.VISIBLE, 0, 0, 0,
                outRelayoutResult, outSurfaceControl);

        // Report the latest when activity is visible.
        final ActivityWindowInfo activityWindowInfo2 = outRelayoutResult.activityWindowInfo;
        assertEquals(expectedInfo, activityWindowInfo2);

        expectedInfo.set(false, new Rect(0, 0, 1000, 2000), new Rect(0, 0, 1000, 2000));
        activity.setVisibleRequested(false);
        activity.setVisible(false);

        mWm.relayoutWindow(win.mSession, win.mClient, win.mAttrs, w, h, View.VISIBLE, 0, 0, 0,
                outRelayoutResult, outSurfaceControl);

        // Report the last reported value when activity is invisible.
        final ActivityWindowInfo activityWindowInfo3 = outRelayoutResult.activityWindowInfo;
        assertEquals(activityWindowInfo2, activityWindowInfo3);
    }

    @Test
    public void testAddOverlayWindowToUnassignedDisplay_notAllowed_ForVisibleBackgroundUsers() {
        doReturn(true).when(() -> UserManager.isVisibleBackgroundUsersEnabled());
        int uid = 100000; // uid for non-system user
        Session session = createTestSession(mAtm, 1234 /* pid */, uid);
        DisplayContent dc = createNewDisplay();
        int displayId = dc.getDisplayId();
        int userId = UserHandle.getUserId(uid);
        // Clean up with the allow_current_user_access_unassigned_displays_in_mumd flag.
        if (Flags.currentUserAccessUnassignedDisplays()) {
            doReturn(UserHandle.USER_SYSTEM).when(mWm.mUmInternal)
                .getUserAssignedToDisplay(displayId);
        } else {
            doReturn(false).when(mWm.mUmInternal).isUserVisible(eq(userId), eq(displayId));
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                LayoutParams.TYPE_APPLICATION_OVERLAY);

        int result = mWm.addWindow(session, new TestIWindow(), params, View.VISIBLE, displayId,
                userId, WindowInsets.Type.defaultVisible(), null, new WindowRelayoutResult());

        assertThat(result).isEqualTo(WindowManagerGlobal.ADD_INVALID_DISPLAY);
    }

    /** Mocks some deps to associate a display content to a specific display id. */
    private void setupReparentWindowContextToDisplayAreaTest(WindowToken windowToken,
            DisplayContent dc, int displayId) {
        spyOn(mWm.mWindowContextListenerController);
        doReturn(dc).when(mWm.mRoot).getDisplayContentOrCreate(displayId);
        doReturn(true).when(mWm.mWindowContextListenerController).assertCallerCanReparentListener(
                any(), anyBoolean(), anyInt(), eq(displayId));
        doReturn(windowToken).when(mWm.mWindowContextListenerController).getContainer(
                eq(windowToken.token));
    }

    @Test
    public void testUpdateOverlayWindows_singleWindowRequestsHiding_doNotHideOverlayWithSameUid() {
        WindowState overlayWindow = newWindowBuilder("overlay_window",
                TYPE_APPLICATION_OVERLAY).build();
        WindowState appWindow = newWindowBuilder("app_window", TYPE_APPLICATION).build();
        makeWindowVisible(appWindow, overlayWindow);

        int uid = 100000;
        spyOn(appWindow);
        spyOn(overlayWindow);
        doReturn(true).when(appWindow).hideNonSystemOverlayWindowsWhenVisible();
        doReturn(uid).when(appWindow).getOwningUid();
        doReturn(uid).when(overlayWindow).getOwningUid();

        mWm.updateNonSystemOverlayWindowsVisibilityIfNeeded(appWindow, true);

        verify(overlayWindow).setForceHideNonSystemOverlayWindowIfNeeded(false);
    }

    @Test
    public void testUpdateOverlayWindows_multipleWindowsRequestHiding_hideOverlaysWithAnyUids() {
        WindowState overlayWindow = newWindowBuilder("overlay_window",
                TYPE_APPLICATION_OVERLAY).build();
        setFieldValue(overlayWindow.mSession, "mCanAddInternalSystemWindow", false);
        WindowState appWindow1 = newWindowBuilder("app_window_1", TYPE_APPLICATION).build();
        WindowState appWindow2 = newWindowBuilder("app_window_2", TYPE_APPLICATION).build();
        makeWindowVisible(appWindow1, appWindow2, overlayWindow);

        int uid1 = 100000;
        int uid2 = 100001;
        spyOn(appWindow1);
        spyOn(appWindow2);
        spyOn(overlayWindow);
        doReturn(true).when(appWindow1).hideNonSystemOverlayWindowsWhenVisible();
        doReturn(true).when(appWindow2).hideNonSystemOverlayWindowsWhenVisible();
        doReturn(uid1).when(appWindow1).getOwningUid();
        doReturn(uid1).when(overlayWindow).getOwningUid();
        doReturn(uid2).when(appWindow2).getOwningUid();

        mWm.updateNonSystemOverlayWindowsVisibilityIfNeeded(appWindow1, true);
        mWm.updateNonSystemOverlayWindowsVisibilityIfNeeded(appWindow2, true);

        verify(overlayWindow).setForceHideNonSystemOverlayWindowIfNeeded(true);
        assertTrue(overlayWindow.isForceHiddenNonSystemOverlayWindow());
        assertNotNull(overlayWindow.getAnimation());
        assertEquals(mContext.getResources().getInteger(
                com.android.internal.R.integer.config_shortAnimTime),
                overlayWindow.getAnimation().getDurationHint());
    }

    @Test
    public void testUpdateOverlayWindows_multipleWindowsFromSameUid_idempotent() {
        // Deny INTERNAL_SYSTEM_WINDOW permission for WindowSession so that the saw isn't allowed to
        // show despite hideNonSystemOverlayWindows.
        doReturn(PackageManager.PERMISSION_DENIED).when(mWm.mContext).checkPermission(
                eq(android.Manifest.permission.INTERNAL_SYSTEM_WINDOW), anyInt(), anyInt());

        WindowState saw =
                newWindowBuilder("saw", TYPE_APPLICATION_OVERLAY).setOwnerId(10123).build();
        saw.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
        saw.mWinAnimator.mSurfaceControl = mock(SurfaceControl.class);
        assertThat(saw.mSession.mCanAddInternalSystemWindow).isFalse();

        WindowState app1 = newWindowBuilder("app1", TYPE_APPLICATION).setOwnerId(10456).build();
        spyOn(app1);
        doReturn(true).when(app1).hideNonSystemOverlayWindowsWhenVisible();

        WindowState app2 = newWindowBuilder("app2", TYPE_APPLICATION).setOwnerId(10456).build();
        spyOn(app2);
        doReturn(true).when(app2).hideNonSystemOverlayWindowsWhenVisible();

        makeWindowVisible(saw, app1, app2);
        spyOn(saw.mWinAnimator);
        // Disable animation so visibility policy flag can be set immediately to verify.
        doReturn(false).when(saw.mWinAnimator).applyAnimationLocked(
                anyInt(), anyBoolean());
        assertThat(saw.isVisibleByPolicy()).isTrue();

        // Two hideNonSystemOverlayWindows windows: SAW is hidden.
        mWm.updateNonSystemOverlayWindowsVisibilityIfNeeded(app1, true);
        mWm.updateNonSystemOverlayWindowsVisibilityIfNeeded(app2, true);
        assertThat(saw.isVisibleByPolicy()).isFalse();

        // Marking the same window hidden twice: SAW is still hidden.
        mWm.updateNonSystemOverlayWindowsVisibilityIfNeeded(app1, false);
        mWm.updateNonSystemOverlayWindowsVisibilityIfNeeded(app1, false);
        assertThat(saw.isVisibleByPolicy()).isFalse();

        // Marking the remaining window hidden: SAW can be shown again.
        mWm.updateNonSystemOverlayWindowsVisibilityIfNeeded(app2, false);
        assertThat(saw.isVisibleByPolicy()).isTrue();
    }

    @Test
    public void reparentWindowContextToDisplayArea_newDisplay_reparented() {
        final WindowToken windowToken = createTestClientWindowToken(TYPE_NOTIFICATION_SHADE,
                mDisplayContent);
        final int newDisplayId = 1;
        final DisplayContent dc = createNewDisplay();
        setupReparentWindowContextToDisplayAreaTest(windowToken, dc, newDisplayId);

        assertThat(windowToken.getDisplayContent()).isEqualTo(mDisplayContent);

        assertThat(mWm.reparentWindowContextToDisplayArea(mAppThread, windowToken.token,
                newDisplayId)).isTrue();

        assertThat(windowToken.getDisplayContent()).isNotEqualTo(mDisplayContent);
    }

    @Test
    public void reparentWindowContext_afterReparent_DCNeedsLayout() {
        final WindowToken windowToken = createTestClientWindowToken(TYPE_NOTIFICATION_SHADE,
                mDisplayContent);
        final int newDisplayId = 1;
        final DisplayContent dc = createNewDisplay();
        setupReparentWindowContextToDisplayAreaTest(windowToken, dc, newDisplayId);

        assertThat(mWm.reparentWindowContextToDisplayArea(mAppThread, windowToken.token,
                newDisplayId)).isTrue();

        assertThat(dc.isLayoutNeeded()).isTrue();
    }

    @Test
    public void reparentWindowContext_afterReparent_traversalScheduled() {
        final WindowToken windowToken = createTestClientWindowToken(TYPE_NOTIFICATION_SHADE,
                mDisplayContent);
        final int newDisplayId = 1;
        final DisplayContent dc = createNewDisplay();
        setupReparentWindowContextToDisplayAreaTest(windowToken, dc, newDisplayId);
        spyOn(mWm.mWindowPlacerLocked);
        reset(mWm.mWindowPlacerLocked);

        verify(mWm.mWindowPlacerLocked, never()).requestTraversal();

        assertThat(mWm.reparentWindowContextToDisplayArea(mAppThread, windowToken.token,
                newDisplayId)).isTrue();

        // Reparenting the WindowToken also requests a traversal.
        verify(mWm.mWindowPlacerLocked, times(2)).requestTraversal();
    }

    @Test
    public void createImplFromParcel_invalidSettingType_throwsException() {
        final Parcelable.Creator<ConfigurationChangeSetting> creator =
                new ConfigurationChangeSetting.CreatorImpl(true /* isSystem */);
        final Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(ConfigurationChangeSetting.SETTING_TYPE_UNKNOWN);
            parcel.setDataPosition(0);

            assertThrows(IllegalArgumentException.class, () -> {
                creator.createFromParcel(parcel);
            });
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void setConfigurationChangeSettingsForUser_createsFromParcel_callsSettingImpl()
            throws Settings.SettingNotFoundException {
        final int currentUserId = ActivityManager.getCurrentUser();
        final int forcedDensity = 400;
        final float forcedFontScaleFactor = 1.15f;
        final Parcelable.Creator<ConfigurationChangeSetting> creator =
                new ConfigurationChangeSetting.CreatorImpl(true /* isSystem */);
        final List<ConfigurationChangeSetting> settings = Stream.of(
                // Display Size
                new ConfigurationChangeSetting.DensitySetting(DEFAULT_DISPLAY, forcedDensity),
                // Font Size
                new ConfigurationChangeSetting.FontScaleSetting(forcedFontScaleFactor)
        ).map(setting -> simulateIpcTransfer(setting, creator)).toList();

        mWm.setConfigurationChangeSettingsForUser(settings, UserHandle.USER_CURRENT);

        verify(mDisplayContent).setForcedDensity(forcedDensity, currentUserId);
        assertEquals(forcedFontScaleFactor, Settings.System.getFloat(
                mContext.getContentResolver(), Settings.System.FONT_SCALE), 0.1f /* delta */);
        verify(mAtm).updateFontScaleIfNeeded(currentUserId);
    }

    @Test
    public void setForcedDisplayDensityRatio_forExternalDisplay_setsRatio() {
        final DisplayInfo displayInfo = new DisplayInfo(mDisplayInfo);
        displayInfo.displayId = DEFAULT_DISPLAY + 1;
        displayInfo.type = Display.TYPE_EXTERNAL;
        displayInfo.logicalDensityDpi = 100;
        mDisplayContent = createNewDisplay(displayInfo);
        final int currentUserId = ActivityManager.getCurrentUser();
        final float forcedDensityRatio = 2f;

        mWm.setForcedDisplayDensityRatio(displayInfo.displayId, forcedDensityRatio,
                currentUserId);

        verify(mDisplayContent).setForcedDensityRatio(forcedDensityRatio,
                currentUserId);
    }

    @Test
    public void setForcedDisplayDensityRatio_forInternalDisplay_setsRatio() {
        final DisplayInfo displayInfo = new DisplayInfo(mDisplayInfo);
        displayInfo.displayId = DEFAULT_DISPLAY + 1;
        displayInfo.type = Display.TYPE_INTERNAL;
        mDisplayContent = createNewDisplay(displayInfo);
        final int currentUserId = ActivityManager.getCurrentUser();
        final float forcedDensityRatio = 2f;

        mWm.setForcedDisplayDensityRatio(displayInfo.displayId, forcedDensityRatio,
                currentUserId);

        verify(mDisplayContent).setForcedDensityRatio(forcedDensityRatio,
                currentUserId);
    }


    @Test
    public void setForcedDisplayDensity_forExternalDisplay_resetsRatio() {
        final DisplayInfo displayInfo = new DisplayInfo(mDisplayInfo);
        displayInfo.displayId = DEFAULT_DISPLAY + 1;
        displayInfo.type = Display.TYPE_EXTERNAL;
        displayInfo.logicalDensityDpi = 100;
        mDisplayContent = createNewDisplay(displayInfo);
        final int currentUserId = ActivityManager.getCurrentUser();
        final float forcedDensityRatio = 2f;

        mWm.setForcedDisplayDensityRatio(displayInfo.displayId, forcedDensityRatio,
                currentUserId);
        mWm.setForcedDisplayDensityForUser(displayInfo.displayId, 200,
                currentUserId);

        verify(mDisplayContent).clearForcedDensityRatio();
    }

    @Test
    public void clearForcedDisplayDensityRatio_clearsRatioAndDensity() {
        final DisplayInfo displayInfo = new DisplayInfo(mDisplayInfo);
        displayInfo.displayId = DEFAULT_DISPLAY + 1;
        displayInfo.type = Display.TYPE_INTERNAL;
        mDisplayContent = createNewDisplay(displayInfo);
        final int currentUserId = ActivityManager.getCurrentUser();

        mWm.clearForcedDisplayDensityForUser(displayInfo.displayId, currentUserId);

        verify(mDisplayContent).setForcedDensityRatio(0.0f,
                currentUserId);

        assertEquals(mDisplayContent.mBaseDisplayDensity,
                mDisplayContent.getInitialDisplayDensity());
        assertEquals(mDisplayContent.mForcedDisplayDensityRatio, 0.0f, 0.001);
    }

    @Test
    @DisableFlags(Flags.FLAG_DEVICE_ENGAGEMENT_MODE)
    public void testSetDisplayEngagementMode_flagDisabled() {
        spyOn(mDefaultDisplay);

        final int testMode = WindowManager.ENGAGEMENT_MODE_FLAG_VISUALS_ON;
        mWm.setDisplayEngagementMode(mDefaultDisplay.getDisplayId(), testMode);

        waitUntilHandlersIdle();
        verify(mDefaultDisplay, never()).setEngagementMode(anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_DEVICE_ENGAGEMENT_MODE)
    public void testSetDisplayEngagementMode_success() {
        spyOn(mDefaultDisplay);

        final int testMode = WindowManager.ENGAGEMENT_MODE_FLAG_VISUALS_ON;
        mWm.setDisplayEngagementMode(mDefaultDisplay.getDisplayId(), testMode);
        when(mDefaultDisplay.getEngagementMode()).thenReturn(testMode);

        waitUntilHandlersIdle();
        verify(mDefaultDisplay).setEngagementMode(testMode);
        final int result = mWm.getDisplayEngagementMode(mDefaultDisplay.getDisplayId());
        assertEquals(testMode, result);
    }

    @Test
    @EnableFlags(Flags.FLAG_DEVICE_ENGAGEMENT_MODE)
    public void testSetDisplayEngagementMode_dispatchesCallbackWhenModeChanges() {
        spyOn(mWm);

        final int initialMode = mWm.getDisplayEngagementMode(mDefaultDisplay.getDisplayId());
        final int newMode = WindowManager.ENGAGEMENT_MODE_FLAG_AUDIO_ON;

        assertThat(initialMode).isNotEqualTo(newMode);
        mWm.setDisplayEngagementMode(mDefaultDisplay.getDisplayId(), newMode);

        waitUntilHandlersIdle();
        verify(mWm, times(1)).dispatchDisplayEngagementModeChanged(
                mDefaultDisplay.getDisplayId(), newMode);
    }

    @Test
    @EnableFlags(Flags.FLAG_DEVICE_ENGAGEMENT_MODE)
    public void testSetDisplayEngagementMode_invalidDisplay() {
        spyOn(mDefaultDisplay);

        final int testMode = WindowManager.ENGAGEMENT_MODE_FLAG_VISUALS_ON;
        mWm.setDisplayEngagementMode(Display.INVALID_DISPLAY, testMode);

        waitUntilHandlersIdle();
        verify(mDefaultDisplay, never()).setEngagementMode(testMode);
    }

    @Test
    @EnableFlags(Flags.FLAG_DEVICE_ENGAGEMENT_MODE)
    public void testDisplayEngagementModeCallback() throws RemoteException {
        spyOn(mDefaultDisplay);

        final int testMode = WindowManager.ENGAGEMENT_MODE_FLAG_VISUALS_ON;
        IDisplayEngagementModeCallback callback = mock(IDisplayEngagementModeCallback.class);
        when(callback.asBinder()).thenReturn(new Binder());
        mWm.registerDisplayEngagementModeCallback(callback);

        waitUntilHandlersIdle();
        // The register callback should trigger a callback with the initial mode.
        verify(callback).onEngagementModeChanged(mDefaultDisplay.getDisplayId(),
                DisplayContent.DEFAULT_ENGAGEMENT_MODE);

        mWm.setDisplayEngagementMode(mDefaultDisplay.getDisplayId(), testMode);

        waitUntilHandlersIdle();
        // Callback should be triggered with the new mode.
        verify(callback).onEngagementModeChanged(mDefaultDisplay.getDisplayId(), testMode);

        mWm.unregisterDisplayEngagementModeCallback(callback);
        final int newMode = WindowManager.ENGAGEMENT_MODE_FLAG_AUDIO_ON;
        mWm.setDisplayEngagementMode(mDefaultDisplay.getDisplayId(), newMode);

        waitUntilHandlersIdle();
        verify(callback, never()).onEngagementModeChanged(mDefaultDisplay.getDisplayId(), newMode);
    }

    @Test
    @EnableFlags(Flags.FLAG_DEVICE_ENGAGEMENT_MODE)
    public void testGetDisplayEngagementMode_invalidDisplay_returnsDefault() {
        final int result = mWm.getDisplayEngagementMode(Display.INVALID_DISPLAY);
        assertEquals(DisplayContent.DEFAULT_ENGAGEMENT_MODE, result);
    }

    @Test
    @EnableFlags(Flags.FLAG_DEVICE_ENGAGEMENT_MODE)
    public void testSetDisplayEngagementMode_multipleDisplays() {
        final DisplayContent dc = createNewDisplay();
        spyOn(mDefaultDisplay);
        spyOn(dc);

        final int defaultDisplayMode = WindowManager.ENGAGEMENT_MODE_FLAG_VISUALS_ON;
        mWm.setDisplayEngagementMode(mDefaultDisplay.getDisplayId(), defaultDisplayMode);
        when(mDefaultDisplay.getEngagementMode()).thenReturn(defaultDisplayMode);

        final int secondaryDisplayMode = WindowManager.ENGAGEMENT_MODE_FLAG_AUDIO_ON;
        mWm.setDisplayEngagementMode(dc.getDisplayId(), secondaryDisplayMode);
        when(dc.getEngagementMode()).thenReturn(secondaryDisplayMode);

        waitUntilHandlersIdle();
        verify(mDefaultDisplay).setEngagementMode(defaultDisplayMode);
        verify(dc).setEngagementMode(secondaryDisplayMode);

        assertEquals(defaultDisplayMode,
                mWm.getDisplayEngagementMode(mDefaultDisplay.getDisplayId()));
        assertEquals(secondaryDisplayMode, mWm.getDisplayEngagementMode(dc.getDisplayId()));
    }

    @EnableFlags(com.android.window.flags.Flags.FLAG_ENGAGEMENT_CONTROL_API)
    @Test
    public void testEngagementControlRequestConsumer() throws RemoteException {
        final WindowState win = newWindowBuilder("appWin", TYPE_BASE_APPLICATION)
                .setOwnerId(Binder.getCallingUid())
                .build();
        final IBinder windowToken = win.mClient.asBinder();
        mWm.mWindowMap.put(windowToken, win);

        final int displayId = DEFAULT_DISPLAY;
        final int flags = 1;
        final int taskId = win.getTask().mTaskId;

        android.window.IEngagementControlRequestConsumer consumer =
                mock(android.window.IEngagementControlRequestConsumer.Stub.class);
        when(consumer.asBinder()).thenReturn((IBinder) consumer);

        mWm.registerEngagementControlRequestConsumer(consumer);
        mWm.requestEngagementControlState(windowToken, flags);

        verify(consumer).onEngagementControlRequest(displayId, taskId, flags);

        mWm.unregisterEngagementControlRequestConsumer(consumer);
        mWm.requestEngagementControlState(windowToken, 2);

        verify(consumer, never()).onEngagementControlRequest(displayId, taskId, 2);
    }

    @Test
    @DisableFlags({android.security.Flags.FLAG_APP_LOCK_APIS,
            android.security.Flags.FLAG_APP_LOCK_CORE})
    public void testConstructor_appLockFlagsAreOff_appLockControllerIsNull() {
        assertNull(mWm.mAppLockController);
    }

    @Test
    @EnableFlags({android.security.Flags.FLAG_APP_LOCK_APIS,
            android.security.Flags.FLAG_APP_LOCK_CORE})
    public void testConstructor_appLockControllerIsNotNull() {
        assertNotNull(mWm.mAppLockController);
    }

    @Test
    @EnableFlags({android.security.Flags.FLAG_APP_LOCK_APIS,
            android.security.Flags.FLAG_APP_LOCK_CORE})
    public void testSystemReady_callsAppLockControllerSystemReady() {
        final AppLockController appLockController = mWm.mAppLockController;
        spyOn(appLockController);
        doNothing().when(appLockController).systemReady();

        // Mock other methods in systemReady().
        spyOn(mWm.mAnimatorScale);
        doNothing().when(mWm.mAnimatorScale).onSystemReady();
        spyOn(mWm.mPolicy);
        doNothing().when(mWm.mPolicy).systemReady();
        spyOn(mWm.mRoot);
        doNothing().when(mWm.mRoot).forAllDisplayPolicies(DisplayPolicy::systemReady);
        spyOn(mWm.mSnapshotController);
        doNothing().when(mWm.mSnapshotController).systemReady();

        mWm.systemReady();

        verify(appLockController).systemReady();
    }

    @Test
    @EnableFlags({android.security.Flags.FLAG_APP_LOCK_APIS,
            android.security.Flags.FLAG_APP_LOCK_CORE})
    public void testIsPackageLockedByAppLock() {
        final AppLockController appLockController = mWm.mAppLockController;
        spyOn(appLockController);
        doReturn(true).when(appLockController).isPackageLockedByAppLockLocked(TEST_PACKAGE_1,
                TEST_USER_ID_1);
        doReturn(true).when(appLockController).isPackageLockedByAppLockLocked(TEST_PACKAGE_2,
                TEST_USER_ID_2);
        doReturn(false).when(appLockController).isPackageLockedByAppLockLocked(TEST_PACKAGE_3,
                TEST_USER_ID_1);

        assertThat(mWm.isPackageLockedByAppLockLocked(TEST_PACKAGE_1, TEST_USER_ID_1)).isTrue();
        assertThat(mWm.isPackageLockedByAppLockLocked(TEST_PACKAGE_2, TEST_USER_ID_2)).isTrue();
        assertThat(mWm.isPackageLockedByAppLockLocked(TEST_PACKAGE_3, TEST_USER_ID_1)).isFalse();
    }

    @EnableFlags({android.security.Flags.FLAG_APP_LOCK_APIS,
            android.security.Flags.FLAG_APP_LOCK_CORE})
    @Test
    public void testAddOverlayWindowBySystem_packageIsLockedByAppLock_showOnWindowReturnsTrue() {
        internalTestAddOverlayWindowForAppLock(true /* isPackageLockedByAppLock */,
                true /* isWindowCreatedBySystem */);
    }

    @EnableFlags({android.security.Flags.FLAG_APP_LOCK_APIS,
            android.security.Flags.FLAG_APP_LOCK_CORE})
    @Test
    public void testAddOverlayWindowBySystem_packageIsNotLockedByAppLock_showOnWindowReturnsTrue() {
        internalTestAddOverlayWindowForAppLock(false /* isPackageLockedByAppLock */,
                true /* isWindowCreatedBySystem */);
    }

    @EnableFlags({android.security.Flags.FLAG_APP_LOCK_APIS,
            android.security.Flags.FLAG_APP_LOCK_CORE})
    @Test
    public void testAddOverlayWindowByApp_packageIsLockedByAppLock_showOnWindowReturnsFalse() {
        internalTestAddOverlayWindowForAppLock(true /* isPackageLockedByAppLock */,
                false /* isWindowCreatedBySystem */);
    }

    @EnableFlags({android.security.Flags.FLAG_APP_LOCK_APIS,
            android.security.Flags.FLAG_APP_LOCK_CORE})
    @Test
    public void testAddOverlayWindowByApp_packageIsNotLockedByAppLock_showOnWindowReturnsTrue() {
        internalTestAddOverlayWindowForAppLock(false /* isPackageLockedByAppLock */,
                false /* isWindowCreatedBySystem */);
    }

    private void internalTestAddOverlayWindowForAppLock(boolean isPackageLockedByAppLock,
            boolean isWindowCreatedBySystem) {
        spyOn(mWm.mContext);
        doReturn(isWindowCreatedBySystem ? PackageManager.PERMISSION_GRANTED
                : PackageManager.PERMISSION_DENIED).when(mWm.mContext).checkCallingOrSelfPermission(
                eq(android.Manifest.permission.INTERNAL_SYSTEM_WINDOW));

        final Session session = createTestSession(mAtm, 1234 /* pid */, 10123 /* uid */);
        final IWindow client = new TestIWindow();
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                TYPE_APPLICATION_OVERLAY);
        params.setTitle("overlayWindowLockedByAppLock: " + isPackageLockedByAppLock);
        params.packageName = DEFAULT_COMPONENT_PACKAGE_NAME;
        // Simulate package's locked by App Lock state.
        final AppLockController appLockController = mWm.mAppLockController;
        spyOn(appLockController);
        doReturn(isPackageLockedByAppLock).when(appLockController).isPackageLockedByAppLockLocked(
                DEFAULT_COMPONENT_PACKAGE_NAME, TEST_USER_ID_1);

        final int addWindowRes = mWm.addWindow(session, client, params, View.VISIBLE,
                DEFAULT_DISPLAY, TEST_USER_ID_1, WindowInsets.Type.defaultVisible(),
                null /* outInputChannel */, new WindowRelayoutResult());

        assertThat(addWindowRes).isAtLeast(WindowManagerGlobal.ADD_OKAY);
        final WindowState win = mWm.mWindowMap.get(client.asBinder());
        assertThat(win).isNotNull();
        final boolean hideRes = win.hide(true /* doAnimation */, true /* requestAnim */);
        final boolean showRes = win.show(true /* doAnimation */, true /* requestAnim */);
        if (isWindowCreatedBySystem || !isPackageLockedByAppLock) {
            assertThat(hideRes).isTrue();
            assertThat(showRes).isTrue();
        } else {
            // Hiding and showing the window should not work if the package is locked by App Lock
            // because the window is already hidden and can't be shown.
            assertThat(hideRes).isFalse();
            assertThat(showRes).isFalse();
        }
    }

    @Test
    public void testAddTrustedTaskOverlay_nullOverlay_throwsException() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        final Task task = createTask(mDisplayContent);

        assertThrows(IllegalArgumentException.class,
                () -> wmInternal.addTrustedTaskOverlay(task.mTaskId, null));
    }

    @Test
    public void testAddTrustedTaskOverlay_invalidSurfaceControl_throwsException() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        final Task task = createTask(mDisplayContent);
        SurfaceControlViewHost.SurfacePackage overlay =
                mock(SurfaceControlViewHost.SurfacePackage.class);
        SurfaceControl sc = mock(SurfaceControl.class);
        when(overlay.getSurfaceControl()).thenReturn(sc);
        when(sc.isValid()).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> wmInternal.addTrustedTaskOverlay(task.mTaskId, overlay));
    }

    @Test
    public void testAddTrustedTaskOverlay_invalidTaskId_throwsException() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        SurfaceControlViewHost.SurfacePackage overlay =
                mock(SurfaceControlViewHost.SurfacePackage.class);
        SurfaceControl sc = mock(SurfaceControl.class);
        when(overlay.getSurfaceControl()).thenReturn(sc);
        when(sc.isValid()).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> wmInternal.addTrustedTaskOverlay(9999, overlay));
    }

    @Test
    public void testAddTrustedTaskOverlay_leafTask() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        final Task rootTask = createTask(mDisplayContent);
        final Task childTask = createTaskInRootTask(rootTask, 0);
        spyOn(childTask);

        SurfaceControlViewHost.SurfacePackage overlay =
                mock(SurfaceControlViewHost.SurfacePackage.class);
        SurfaceControl sc = mock(SurfaceControl.class);
        when(overlay.getSurfaceControl()).thenReturn(sc);
        when(sc.isValid()).thenReturn(true);

        wmInternal.addTrustedTaskOverlay(childTask.mTaskId, overlay);

        verify(childTask).addTrustedOverlay(eq(overlay), any());
    }

    @Test
    public void testRemoveTrustedTaskOverlay_nullOverlay_throwsException() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        final Task task = createTask(mDisplayContent);

        assertThrows(IllegalArgumentException.class,
                () -> wmInternal.removeTrustedTaskOverlay(task.mTaskId, null));
    }

    @Test
    public void testRemoveTrustedTaskOverlay_invalidSurfaceControl_throwsException() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        final Task task = createTask(mDisplayContent);
        SurfaceControlViewHost.SurfacePackage overlay =
                mock(SurfaceControlViewHost.SurfacePackage.class);
        SurfaceControl sc = mock(SurfaceControl.class);
        when(overlay.getSurfaceControl()).thenReturn(sc);
        when(sc.isValid()).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> wmInternal.removeTrustedTaskOverlay(task.mTaskId, overlay));
    }

    @Test
    public void testRemoveTrustedTaskOverlay_invalidTaskId_throwsException() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        SurfaceControlViewHost.SurfacePackage overlay =
                mock(SurfaceControlViewHost.SurfacePackage.class);
        SurfaceControl sc = mock(SurfaceControl.class);
        when(overlay.getSurfaceControl()).thenReturn(sc);
        when(sc.isValid()).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> wmInternal.removeTrustedTaskOverlay(9999, overlay));
    }

    @Test
    public void testRemoveTrustedTaskOverlay_leafTask() {
        WindowManagerInternal wmInternal = LocalServices.getService(WindowManagerInternal.class);
        final Task rootTask = createTask(mDisplayContent);
        final Task childTask = createTaskInRootTask(rootTask, 0);
        spyOn(childTask);

        SurfaceControlViewHost.SurfacePackage overlay =
                mock(SurfaceControlViewHost.SurfacePackage.class);
        SurfaceControl sc = mock(SurfaceControl.class);
        when(overlay.getSurfaceControl()).thenReturn(sc);
        when(sc.isValid()).thenReturn(true);

        wmInternal.removeTrustedTaskOverlay(childTask.mTaskId, overlay);

        verify(childTask).removeTrustedOverlay(overlay);
    }

    /**
     * Simulates IPC transfer by writing the setting to a parcel and reading it back.
     *
     * @param setting the setting to transfer.
     * @param creator the creator to use for reconstructing the setting from the parcel.
     * @return a new instance of the setting created from the parcel.
     */
    private static <T extends ConfigurationChangeSetting> T simulateIpcTransfer(
            T setting, Parcelable.Creator<T> creator) {
        final Parcel parcel = Parcel.obtain();
        try {
            setting.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static class TestResultReceiver implements IResultReceiver {
        public android.os.Bundle resultData;
        private final IBinder mBinder = mock(IBinder.class);

        @Override
        public void send(int resultCode, android.os.Bundle resultData) {
            this.resultData = resultData;
        }

        @Override
        public android.os.IBinder asBinder() {
            return mBinder;
        }
    }

    private void setupActivityWithLaunchCookie(
            IBinder launchCookie, WindowContainerToken wct, int uid) {
        final WindowContainer.RemoteToken remoteToken = mock(WindowContainer.RemoteToken.class);
        when(remoteToken.toWindowContainerToken()).thenReturn(wct);
        final ActivityRecord testActivity =
                new ActivityBuilder(mAtm).setCreateTask(true).setUid(uid).build();
        testActivity.mLaunchCookie = launchCookie;
        testActivity.getTask().mRemoteToken = remoteToken;
    }

    @Test
    @EnableFlags(Flags.FLAG_SYNC_BEFORE_ENABLING_SCREEN)
    public void testSyncBeforeEnablingScreen() throws Exception {
        final StorageManagerInternal smInternal = mock(StorageManagerInternal.class);
        LocalServices.addService(StorageManagerInternal.class, smInternal);

        final CountDownLatch syncStartedLatch = new CountDownLatch(1);
        final AtomicReference<Runnable> callbackRef = new AtomicReference<>();

        doAnswer(invocation -> {
            callbackRef.set(invocation.getArgument(0));
            syncStartedLatch.countDown();
            return true;
        }).doReturn(false).when(smInternal)
                .waitForCheckpointReady(any(Runnable.class));

        try {
            mWm.mDisplayEnabled = false;
            mWm.mSystemBooted = true;
            mWm.mShowingBootMessages = true;
            mWm.mForceDisplayEnabled = false;
            mWm.mBootWaitForWindowsStartTime = -1;

            mWm.enableScreenIfNeeded();
            waitHandlerIdle(mWm.mH);

            assertTrue("Sync should have started",
                    syncStartedLatch.await(10, TimeUnit.SECONDS));

            assertEquals("Screen enable should be deferred", -1,
                    mWm.mBootWaitForWindowsStartTime);

            callbackRef.get().run();

            waitHandlerIdle(mWm.mH);

            assertNotEquals("Screen enable should be proceeded", -1,
                    mWm.mBootWaitForWindowsStartTime);
            verify(smInternal, times(2)).waitForCheckpointReady(any(Runnable.class));
        } finally {
            LocalServices.removeServiceForTest(StorageManagerInternal.class);
        }
    }

    private boolean setupLetterboxConfigurationWithBackgroundType(
            @AppCompatConfiguration.LetterboxBackgroundType int letterboxBackgroundType) {
        mWm.mAppCompatConfiguration.setLetterboxBackgroundTypeOverride(letterboxBackgroundType);
        return mWm.isLetterboxBackgroundMultiColored();
    }

    @Test
    public void testAddToastWindow_singleWindowPerToken() {
        final IBinder token = new Binder();
        mWm.addWindowToken(token, TYPE_TOAST, DEFAULT_DISPLAY, null /* options */);

        final int uid1 = 1234;
        final int pid1 = 1234;
        final Session session1 = createTestSession(mAtm, pid1, uid1);
        final WindowManager.LayoutParams params1 = new WindowManager.LayoutParams(TYPE_TOAST);
        params1.token = token;
        params1.packageName = "test1";

        final ApplicationInfo appInfo1 = new ApplicationInfo();
        appInfo1.targetSdkVersion = android.os.Build.VERSION_CODES.O;
        doReturn(appInfo1)
                .when(mWm.mPmInternal)
                .getApplicationInfo(eq("test1"), anyLong(), anyInt(), anyInt());
        doReturn(true).when(mWm.mPmInternal).isSameApp(eq("test1"), eq(uid1), anyInt());

        final IWindow client1 = new TestIWindow();
        final int res1 =
                mWm.addWindow(
                        session1,
                        client1,
                        params1,
                        View.VISIBLE,
                        DEFAULT_DISPLAY,
                        0 /* requestUserId */,
                        WindowInsets.Type.defaultVisible(),
                        null,
                        new WindowRelayoutResult());
        assertThat(res1).isAtLeast(WindowManagerGlobal.ADD_OKAY);

        // Add second window with same token but different UID to bypass the canAddToastWindowForUid
        // check
        final int uid2 = 1235;
        final int pid2 = 1235;
        final Session session2 = createTestSession(mAtm, pid2, uid2);
        final WindowManager.LayoutParams params2 = new WindowManager.LayoutParams(TYPE_TOAST);
        params2.token = token;
        params2.packageName = "test2";

        final ApplicationInfo appInfo2 = new ApplicationInfo();
        appInfo2.targetSdkVersion = android.os.Build.VERSION_CODES.O;
        doReturn(appInfo2)
                .when(mWm.mPmInternal)
                .getApplicationInfo(eq("test2"), anyLong(), anyInt(), anyInt());
        doReturn(true).when(mWm.mPmInternal).isSameApp(eq("test2"), eq(uid2), anyInt());

        final IWindow client2 = new TestIWindow();
        final int res2 =
                mWm.addWindow(
                        session2,
                        client2,
                        params2,
                        View.VISIBLE,
                        DEFAULT_DISPLAY,
                        0 /* requestUserId */,
                        WindowInsets.Type.defaultVisible(),
                        null,
                        new WindowRelayoutResult());
        assertThat(res2).isEqualTo(WindowManagerGlobal.ADD_BAD_APP_TOKEN);
    }
}
