/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_PRESENTATION;
import static android.view.Display.FLAG_PRIVATE;
import static android.view.Display.FLAG_TRUSTED;
import static android.view.Display.TYPE_EXTERNAL;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_WAKE;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.window.flags.Flags.FLAG_ENABLE_PRESENTATION_STOPS_TOP_TASK_BUGFIX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.eq;

import android.annotation.NonNull;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowRelayoutResult;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 * atest WmTests:PresentationControllerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class PresentationControllerTests extends WindowTestsBase {

    TestTransitionPlayer mPlayer;

    @Before
    public void setUp() {
        mPlayer = registerTestTransitionPlayer();
    }

    @EnableFlags(FLAG_ENABLE_PRESENTATION_STOPS_TOP_TASK_BUGFIX)
    @Test
    public void testPresentationShowAndHide() {
        final DisplayContent dc = createPresentationDisplay();
        final ActivityRecord activity = createActivityRecord(createTask(dc));
        assertTrue(activity.isVisible());
        assertTrue(activity.getTask().isFocusableAndVisible());
        // Finish WAKE transition (adding task to empty display wakes it up)
        mPlayer.flush();

        // Create a host task on the default display and ensure it has the global focus so it can
        // start a presentation on the presentation display.
        final int hostTaskUid = Process.myUid();
        final ActivityRecord hostActivity = createActivityRecord(mDisplayContent);
        hostActivity.getTask().effectiveUid = hostTaskUid;
        assertEquals(hostActivity.getTask(), mWm.mRoot.getTopDisplayFocusedLeafTask());

        // Add a presentation window, which requests the activity to stop.
        final WindowState window = addPresentationWindow(hostTaskUid, dc.mDisplayId);
        assertFalse(activity.isVisibleRequested());
        assertTrue(activity.isVisible());
        final Transition addTransition = window.mTransitionController.getCollectingTransition();
        assertEquals(TRANSIT_OPEN, addTransition.mType);
        assertTrue(addTransition.isInTransition(window));
        assertTrue(addTransition.isInTransition(activity));

        // Completing the transition makes the activity invisible.
        completeTransition(addTransition, /*abortSync=*/ true);
        assertFalse(activity.isVisible());
        assertFalse(activity.getTask().isFocusableAndVisible());

        // Remove a Presentation window, which requests the activity to be resumed back.
        window.removeIfPossible();
        final Transition removeTransition = window.mTransitionController.getCollectingTransition();
        assertEquals(TRANSIT_CLOSE, removeTransition.mType);
        assertTrue(removeTransition.isInTransition(window));
        assertTrue(removeTransition.isInTransition(activity));
        assertTrue(activity.isVisibleRequested());
        assertFalse(activity.isVisible());

        // Completing the transition makes the activity visible.
        completeTransition(removeTransition, /*abortSync=*/ false);
        assertTrue(activity.isVisible());
    }

    @Test
    public void testPresentationCannotCoverFocusedHostTask() {
        forceAllowPresentationsOnDefaultDisplay();

        int uid = Process.myUid();
        final DisplayContent presentationDisplay = createPresentationDisplay();
        final Task task = createTask(presentationDisplay);
        task.effectiveUid = uid;
        final ActivityRecord activity = createActivityRecord(task);
        assertTrue(activity.isVisible());

        // Adding a presentation window over its host task must fail.
        assertAddPresentationWindowFails(uid, presentationDisplay.mDisplayId);

        // Adding a presentation window on the other display must succeed.
        final WindowState window = addPresentationWindow(uid, DEFAULT_DISPLAY);
        final Transition addTransition = window.mTransitionController.getCollectingTransition();
        completeTransition(addTransition, /*abortSync=*/ true);
        assertTrue(window.isVisible());

        // Moving the host task to the presenting display will remove the presentation.
        task.reparent(mDefaultDisplay.getDefaultTaskDisplayArea(), true);
        waitHandlerIdle(window.mWmService.mAtmService.mH);
        final Transition removeTransition = window.mTransitionController.getCollectingTransition();
        assertEquals(TRANSIT_CLOSE, removeTransition.mType);
        completeTransition(removeTransition, /*abortSync=*/ false);
        assertFalse(window.isVisible());
    }

    @Test
    public void testPresentationCannotCoverUnfocusedHostTask() {
        int uid = Process.myUid();
        final DisplayContent presentationDisplay = createPresentationDisplay();
        final Task hostTask = createTask(presentationDisplay);
        hostTask.effectiveUid = uid;
        final ActivityRecord hostActivity = createActivityRecord(hostTask);
        assertTrue(hostActivity.isVisible());

        // Adding a presentation window over its host task must fail.
        assertAddPresentationWindowFails(uid, presentationDisplay.mDisplayId);

        // Create another task, which makes the host task unfocused.
        final Task taskFromAnotherApp = createTask(presentationDisplay.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        taskFromAnotherApp.effectiveUid = uid + 1;
        final ActivityRecord activityFromAnotherApp = createActivityRecord(taskFromAnotherApp);
        assertTrue(hostActivity.isVisible());
        assertTrue(activityFromAnotherApp.isVisible());
        assertFalse(hostActivity.isFocusedActivityOnDisplay());
        assertTrue(activityFromAnotherApp.isFocusedActivityOnDisplay());

        // Adding a presentation window over its host task must fail even if the host task is not
        // focused.
        assertAddPresentationWindowFails(uid, presentationDisplay.mDisplayId);
    }

    @Test
    public void testPresentationCannotLaunchWithUnfocusedHostTaskOnDifferentDisplay() {
        // This test verifies that a presentation cannot be launched on a task-hosting display
        // when its host task is visible but not focused on a different display.
        final int uid = Process.myUid();

        // Create a host task on the default display.
        final Task hostTask = createTask(mDefaultDisplay);
        hostTask.effectiveUid = uid;
        final ActivityRecord hostActivity = createActivityRecord(hostTask);
        assertTrue(hostActivity.isVisible());

        // Create another task on the default display to make the host task unfocused.
        final Task taskFromAnotherApp = createTask(mDefaultDisplay.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        taskFromAnotherApp.effectiveUid = uid + 1;
        final ActivityRecord activityFromAnotherApp = createActivityRecord(taskFromAnotherApp);
        assertTrue(hostActivity.isVisible());
        assertTrue(activityFromAnotherApp.isVisible());
        assertFalse(hostActivity.isFocusedActivityOnDisplay());
        assertTrue(activityFromAnotherApp.isFocusedActivityOnDisplay());

        // Create a separate display for the presentation (can host tasks by default).
        final DisplayContent presentationDisplay = createPresentationDisplay();
        assertTrue(presentationDisplay.getDisplay().canHostTasks());

        // Adding a presentation window on the other display must fail because the host task
        // is not focused.
        assertAddPresentationWindowFails(uid, presentationDisplay.getDisplayId());
    }

    @Test
    public void testPresentationCanLaunchWithUnfocusedHostTaskOnDifferentDisplayNotHostingTasks() {
        // This test verifies that a presentation can be launched on a display that cannot host
        // tasks, even when its host task is visible but not focused on a different display.
        final int uid = Process.myUid();

        // Create a host task on the default display.
        final Task hostTask = createTask(mDefaultDisplay);
        hostTask.effectiveUid = uid;
        final ActivityRecord hostActivity = createActivityRecord(hostTask);
        assertTrue(hostActivity.isVisible());

        // Create another task on the default display to make the host task unfocused.
        final Task taskFromAnotherApp = createTask(mDefaultDisplay.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        taskFromAnotherApp.effectiveUid = uid + 1;
        final ActivityRecord activityFromAnotherApp = createActivityRecord(taskFromAnotherApp);
        assertTrue(hostActivity.isVisible());
        assertTrue(activityFromAnotherApp.isVisible());
        assertFalse(hostActivity.isFocusedActivityOnDisplay());
        assertTrue(activityFromAnotherApp.isFocusedActivityOnDisplay());

        // Create a separate display for the presentation.
        final DisplayContent presentationDisplay = createPresentationDisplay();
        doReturn(false).when(presentationDisplay.mDisplay).canHostTasks();
        assertFalse(presentationDisplay.getDisplay().canHostTasks());

        // Adding a presentation window on the other display must succeed because the display
        // cannot host tasks.
        final WindowState window = addPresentationWindow(uid, presentationDisplay.getDisplayId());
        final Transition addTransition = window.mTransitionController.getCollectingTransition();
        completeTransition(addTransition, /*abortSync=*/ true);
        assertTrue(window.isVisible());
    }

    @Test
    public void testPresentationCannotLaunchOnAllDisplays() {
        forceAllowPresentationsOnDefaultDisplay();

        final int uid = Process.myUid();
        final DisplayContent presentationDisplay = createPresentationDisplay();
        final Task task = createTask(presentationDisplay);
        task.effectiveUid = uid;
        final ActivityRecord activity = createActivityRecord(task);
        assertTrue(activity.isVisible());

        // Add a presentation window on the default display.
        final WindowState window = addPresentationWindow(uid, DEFAULT_DISPLAY);
        final Transition addTransition = window.mTransitionController.getCollectingTransition();
        completeTransition(addTransition, /*abortSync=*/ true);
        assertTrue(window.isVisible());

        // Adding another presentation window over the task even if it's a different UID because
        // it would end up showing presentations on all displays.
        assertAddPresentationWindowFails(uid + 1, presentationDisplay.mDisplayId);
    }

    @Test
    public void testInvisiblePresentationIsNotAllowed() {
        final int uid = Process.myUid();
        final Task task = createTask(mDefaultDisplay);
        task.effectiveUid = uid;
        final ActivityRecord activity = createActivityRecord(task);
        assertTrue(activity.isVisible());

        // Add a presentation window on a presentation display.
        final DisplayContent presentationDisplay = createPresentationDisplay();
        final WindowState window = addPresentationWindow(uid, presentationDisplay.getDisplayId());
        window.setViewVisibility(View.VISIBLE);
        final Transition addTransition = window.mTransitionController.getCollectingTransition();
        completeTransition(addTransition, /*abortSync=*/ true);
        assertTrue(window.isVisible());

        // Making the presentation view invisible automatically removes it.
        window.setViewVisibility(View.GONE);
        assertTrue(window.isVisible());
        waitHandlerIdle(window.mWmService.mAtmService.mH);
        final Transition removeTransition = window.mTransitionController.getCollectingTransition();
        assertEquals(TRANSIT_CLOSE, removeTransition.mType);
        completeTransition(removeTransition, /*abortSync=*/ false);
        assertFalse(window.isVisible());
    }

    @Test
    public void testInvisiblePrivatePresentationIsAllowed() {
        final int uid = Process.myUid();
        final Task task = createTask(mDefaultDisplay);
        task.effectiveUid = uid;
        final ActivityRecord activity = createActivityRecord(task);
        assertTrue(activity.isVisible());

        // Add a private presentation window on a private presentation display.
        final DisplayContent privateDisplay = createPrivatePresentationDisplay();
        final WindowState window = addPrivatePresentationWindow(uid,
                privateDisplay.getDisplayId());
        window.setViewVisibility(View.VISIBLE);
        final Transition addTransition = window.mTransitionController.getCollectingTransition();
        completeTransition(addTransition, /*abortSync=*/ true);
        assertTrue(window.isVisible());

        // Making the private presentation view invisible is allowed, thus no close transition runs.
        window.setViewVisibility(View.GONE);
        assertTrue(window.isVisible());
        waitHandlerIdle(window.mWmService.mAtmService.mH);
        assertNull(window.mTransitionController.getCollectingTransition());
        assertTrue(window.isVisible());
    }

    @Test
    public void testPresentationCannotLaunchOnInternalDisplayWithoutHostHavingGlobalFocus() {
        forceAllowPresentationsOnDefaultDisplay();

        final int uid = Process.myUid();
        // Adding a presentation window on an internal display requires a host task
        // with global focus on another display.
        assertAddPresentationWindowFails(uid, DEFAULT_DISPLAY);

        final DisplayContent presentationDisplay = createPresentationDisplay();
        final Task taskWiSameUid = createTask(presentationDisplay);
        taskWiSameUid.effectiveUid = uid;
        final ActivityRecord activity = createActivityRecord(taskWiSameUid);
        assertTrue(activity.isVisible());
        final Task taskWithDifferentUid = createTask(presentationDisplay);
        taskWithDifferentUid.effectiveUid = uid + 1;
        createActivityRecord(taskWithDifferentUid);
        assertEquals(taskWithDifferentUid, presentationDisplay.getFocusedRootTask());

        // The task with the same UID is covered by another task with a different UID, so this must
        // also fail.
        assertAddPresentationWindowFails(uid, DEFAULT_DISPLAY);

        // Moving the task with the same UID to front and giving it global focus allows a
        // presentation to show on the default display.
        taskWiSameUid.moveToFront("test");
        final WindowState window = addPresentationWindow(uid, DEFAULT_DISPLAY);
        final Transition addTransition = window.mTransitionController.getCollectingTransition();
        completeTransition(addTransition, /*abortSync=*/ true);
        assertTrue(window.isVisible());
    }

    @Test
    public void testReparentingActivityToSameDisplayClosesPresentation() {
        final int uid = Process.myUid();
        final Task task = createTask(mDefaultDisplay);
        task.effectiveUid = uid;
        final ActivityRecord activity = createActivityRecord(task);
        assertTrue(activity.isVisible());

        // Add a presentation window on a presentation display.
        final DisplayContent presentationDisplay = createPresentationDisplay();
        final WindowState window = addPresentationWindow(uid, presentationDisplay.getDisplayId());
        final Transition addTransition = window.mTransitionController.getCollectingTransition();
        completeTransition(addTransition, /*abortSync=*/ true);
        assertTrue(window.isVisible());

        // Reparenting the host task below the presentation must close the presentation.
        task.reparent(presentationDisplay.getDefaultTaskDisplayArea(), true);
        waitHandlerIdle(window.mWmService.mAtmService.mH);
        final Transition removeTransition = window.mTransitionController.getCollectingTransition();
        // It's a WAKE transition instead of CLOSE because
        assertEquals(TRANSIT_WAKE, removeTransition.mType);
        completeTransition(removeTransition, /*abortSync=*/ false);
        assertFalse(window.isVisible());
    }

    @Test
    public void testPrivatePresentationDoesNotHaveHostTask() {
        final int uid = Process.myUid();
        final Task task = createTask(mDefaultDisplay);
        task.effectiveUid = uid;
        final ActivityRecord activity = createActivityRecord(task);
        assertTrue(activity.isVisible());

        // Add a presentation window on a presentation display.
        final DisplayContent presentationDisplay = createPrivatePresentationDisplay();
        final WindowState window = addPrivatePresentationWindow(uid,
                presentationDisplay.getDisplayId());
        final Transition addTransition = window.mTransitionController.getCollectingTransition();
        completeTransition(addTransition, /*abortSync=*/ true);
        assertTrue(window.isVisible());

        // Reparenting the host task below the presentation must NOT close the presentation
        // because the presentation doesn't have a host task in per-display-focus systems.
        task.reparent(presentationDisplay.getDefaultTaskDisplayArea(), true);
        waitHandlerIdle(window.mWmService.mAtmService.mH);
        final Transition removeTransition = window.mTransitionController.getCollectingTransition();
        assertEquals(TRANSIT_WAKE, removeTransition.mType);
        completeTransition(removeTransition, /*abortSync=*/ false);
        assertTrue(window.isVisible());
    }

    @Test
    public void testPrivatePresentationCanCoverHostTask() {
        // This test verifies that a private presentation can be shown on the same display as its
        // host task, because host task policies do not apply to private displays.
        final int uid = Process.myUid();
        final DisplayContent privateDisplay = createPrivatePresentationDisplay();
        final Task task = createTask(privateDisplay);
        task.effectiveUid = uid;
        final ActivityRecord activity = createActivityRecord(task);
        assertTrue(activity.isVisible());
        // Finish WAKE transition (adding task to empty display wakes it up)
        mPlayer.flush();

        // Adding a private presentation window over its host task must succeed because host task
        // policies are ignored for private displays.
        final WindowState window = addPrivatePresentationWindow(uid, privateDisplay.getDisplayId());
        final Transition addTransition = window.mTransitionController.getCollectingTransition();
        completeTransition(addTransition, /*abortSync=*/ true);
        assertTrue(window.isVisible());
    }

    @Test
    public void testNewPresentationCannotShowOnPresentingDesk() {
        forceAllowPresentationsOnDefaultDisplay();

        int uid = Process.myUid();
        final DisplayContent presentationDisplay = createPresentationDisplay();

        // Emulate the multi desk environment, where a desk task is created and have apps as child
        // tasks.
        final Task rootDeskTask = createTask(presentationDisplay);
        rootDeskTask.effectiveUid = uid + 1;
        final Task leafTask = createTaskInRootTask(rootDeskTask, UserHandle.getUserId(uid));
        leafTask.effectiveUid = uid;
        final ActivityRecord activity = createActivityRecord(leafTask);
        assertTrue(activity.isVisible());

        // Adding a presentation window on the other display must succeed.
        final WindowState window = addPresentationWindow(uid, DEFAULT_DISPLAY);
        final Transition addTransition = window.mTransitionController.getCollectingTransition();
        completeTransition(addTransition, /*abortSync=*/ true);
        assertTrue(window.isVisible());

        // Adding another presentation window over the presentation must fail.
        assertAddPresentationWindowFails(uid, DEFAULT_DISPLAY);
    }

    @Test
    public void testDisallowPresentationOnNonPresentationDisplay() {
        assumeTrue(!mDefaultDisplay.mDisplay.isPublicPresentation());

        final DisplayContent dc = createPresentationDisplay();
        final ActivityRecord activity = createActivityRecord(createTask(dc));
        assertTrue(activity.isVisible());

        assertAddPresentationWindowFails(100000, mDefaultDisplay.getDisplayId());
    }

    private WindowState addPresentationWindow(int uid, int displayId) {
        return addPresentationWindowInner(uid, displayId, false /* isPrivatePresentation */);
    }

    private WindowState addPrivatePresentationWindow(int uid, int displayId) {
        return addPresentationWindowInner(uid, displayId, true /* isPrivatePresentation */);
    }

    private WindowState addPresentationWindowInner(int uid, int displayId,
            boolean isPrivatePresentation) {
        final Session session = createTestSession(mAtm, 1234 /* pid */, uid);
        final int userId = UserHandle.getUserId(uid);
        doReturn(true).when(mWm.mUmInternal).isUserVisible(eq(userId), eq(displayId));
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                isPrivatePresentation ? TYPE_PRIVATE_PRESENTATION : TYPE_PRESENTATION);
        final IWindow clientWindow = new TestIWindow();
        final int res = mWm.addWindow(session, clientWindow, params, View.VISIBLE, displayId,
                userId, WindowInsets.Type.defaultVisible(), null, new WindowRelayoutResult());
        assertTrue(res >= WindowManagerGlobal.ADD_OKAY);
        final WindowState window = mWm.windowForClient(session, clientWindow);
        window.mHasSurface = true;
        return window;
    }

    private void assertAddPresentationWindowFails(int uid, int displayId) {
        final Session session = createTestSession(mAtm, 1234 /* pid */, uid);
        final IWindow clientWindow = new TestIWindow();
        final int res = addPresentationWindowInner(uid, displayId, session, clientWindow);
        assertEquals(WindowManagerGlobal.ADD_INVALID_DISPLAY, res);
    }

    private int addPresentationWindowInner(int uid, int displayId, Session session,
            IWindow clientWindow) {
        final int userId = UserHandle.getUserId(uid);
        doReturn(true).when(mWm.mUmInternal).isUserVisible(eq(userId), eq(displayId));
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_PRESENTATION);
        return mWm.addWindow(session, clientWindow, params, View.VISIBLE, displayId, userId,
                WindowInsets.Type.defaultVisible(), null, new WindowRelayoutResult());
    }

    private DisplayContent createPresentationDisplay() {
        return createPresentationDisplayInner(false /* isPrivateDisplay */);
    }

    private DisplayContent createPrivatePresentationDisplay() {
        return createPresentationDisplayInner(true /* isPrivateDisplay */);
    }

    private DisplayContent createPresentationDisplayInner(boolean isPrivateDisplay) {
        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.flags = FLAG_PRESENTATION | FLAG_TRUSTED;
        displayInfo.type = TYPE_EXTERNAL;
        if (isPrivateDisplay) {
            displayInfo.flags |= FLAG_PRIVATE;
            displayInfo.ownerUid = Process.myUid();
        }
        displayInfo.displayId = DEFAULT_DISPLAY + 1;
        final DisplayContent dc = createNewDisplay(displayInfo);
        final int displayId = dc.getDisplayId();
        doReturn(dc).when(mWm.mRoot).getDisplayContentOrCreate(displayId);
        return dc;
    }

    static ActivityRecord createActivityRecord(Task task) {
        final ActivityRecord activity = createActivityRecord(task.getDisplayContent(), task);
        // PresentationController finds a host task based on the top resumed activity, so make sure
        // to set activity to be resumed in the parent task.
        task.setResumedActivity(activity, "createActivityRecord");
        return activity;
    }

    private void completeTransition(@NonNull Transition transition, boolean abortSync) {
        final ActionChain chain = ActionChain.testFinish(transition);
        if (abortSync) {
            // Forcefully finishing the active sync for testing purpose.
            mWm.mSyncEngine.abort(transition.getSyncId());
        } else {
            transition.onTransactionReady(transition.getSyncId(), mTransaction);
        }
        transition.finishTransition(chain);
    }

    private void forceAllowPresentationsOnDefaultDisplay() {
        spyOn(mDefaultDisplay.getDisplay());
        when(mDefaultDisplay.getDisplay().isPublicPresentation()).thenReturn(true);
    }
}
