/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.app.Activity.RESULT_CANCELED;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.START_ABORTED;
import static android.app.ActivityManager.START_CANCELED;
import static android.app.ActivityManager.START_CANNOT_GUARANTEE_TASK_MOVABILITY;
import static android.app.ActivityManager.START_CLASS_NOT_FOUND;
import static android.app.ActivityManager.START_DELIVERED_TO_TOP;
import static android.app.ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT;
import static android.app.ActivityManager.START_INTENT_NOT_RESOLVED;
import static android.app.ActivityManager.START_NOT_VOICE_COMPATIBLE;
import static android.app.ActivityManager.START_PERMISSION_DENIED;
import static android.app.ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
import static android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static android.content.pm.ActivityInfo.FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.os.Process.SYSTEM_UID;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.display.feature.flags.Flags.FLAG_ENABLE_DISPLAY_CONTENT_MODE_MANAGEMENT;
import static com.android.server.wm.ActivityStarter.canEmbedActivity;
import static com.android.server.wm.TaskFragment.EMBEDDING_ALLOWED;
import static com.android.server.wm.TaskFragment.EMBEDDING_DISALLOWED_MIN_DIMENSION_VIOLATION;
import static com.android.server.wm.TaskFragment.EMBEDDING_DISALLOWED_NEW_TASK;
import static com.android.server.wm.TaskFragment.EMBEDDING_DISALLOWED_UNTRUSTED_HOST;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowTestsBase.ActivityBuilder.DEFAULT_FAKE_UID;
import static com.android.window.flags.Flags.FLAG_TRACK_LAUNCH_ORIGINATOR;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.clearInvocations;

import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.IApplicationThread;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.WindowLayout;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.SigningDetails;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.service.voice.IVoiceInteractionSession;
import android.util.Pair;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import android.view.RemoteAnimationAdapter;
import android.view.WindowManager;
import android.window.TaskFragmentOrganizerToken;

import androidx.test.filters.SmallTest;

import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.server.am.PendingIntentController;
import com.android.server.am.PendingIntentRecord;
import com.android.server.am.psc.ProcessRecordInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.wm.BackgroundActivityStartController.BalVerdict;
import com.android.server.wm.LaunchParamsController.LaunchParams;
import com.android.server.wm.LaunchParamsController.LaunchParamsModifier;
import com.android.server.wm.utils.MockTracker;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for the {@link ActivityStarter} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityStarterTests
 */
@SmallTest
@Presubmit
public final class ActivityStarterTests extends ActivityStarterTestBase {

    private static final int PRECONDITION_NO_CALLER_APP = 1;
    private static final int PRECONDITION_NO_INTENT_COMPONENT = 1 << 1;
    private static final int PRECONDITION_NO_ACTIVITY_INFO = 1 << 2;
    private static final int PRECONDITION_SOURCE_PRESENT = 1 << 3;
    private static final int PRECONDITION_REQUEST_CODE = 1 << 4;
    private static final int PRECONDITION_SOURCE_VOICE_SESSION = 1 << 5;
    private static final int PRECONDITION_NO_VOICE_SESSION_SUPPORT = 1 << 6;
    private static final int PRECONDITION_DIFFERENT_UID = 1 << 7;
    private static final int PRECONDITION_ACTIVITY_SUPPORTS_INTENT_EXCEPTION = 1 << 8;
    private static final int PRECONDITION_CANNOT_START_ANY_ACTIVITY = 1 << 9;
    private static final int FAKE_CALLING_UID = 666;
    private static final int FAKE_REAL_CALLING_UID = 667;
    private static final String FAKE_CALLING_PACKAGE = "com.whatever.dude";
    private static final int UNIMPORTANT_UID = 12345;
    private static final int UNIMPORTANT_UID2 = 12346;
    private static final int CURRENT_IME_UID = 12347;

    @Test
    public void testStartActivityPreconditions() {
        verifyStartActivityPreconditions(PRECONDITION_NO_CALLER_APP, START_PERMISSION_DENIED);
        verifyStartActivityPreconditions(PRECONDITION_NO_INTENT_COMPONENT,
                START_INTENT_NOT_RESOLVED);
        verifyStartActivityPreconditions(PRECONDITION_NO_ACTIVITY_INFO, START_CLASS_NOT_FOUND);
        verifyStartActivityPreconditions(PRECONDITION_SOURCE_PRESENT | PRECONDITION_REQUEST_CODE,
                Intent.FLAG_ACTIVITY_FORWARD_RESULT, START_FORWARD_AND_REQUEST_CONFLICT);
        verifyStartActivityPreconditions(
                PRECONDITION_SOURCE_PRESENT | PRECONDITION_NO_VOICE_SESSION_SUPPORT
                        | PRECONDITION_SOURCE_VOICE_SESSION | PRECONDITION_DIFFERENT_UID,
                START_NOT_VOICE_COMPATIBLE);
        verifyStartActivityPreconditions(
                PRECONDITION_SOURCE_PRESENT | PRECONDITION_NO_VOICE_SESSION_SUPPORT
                        | PRECONDITION_SOURCE_VOICE_SESSION | PRECONDITION_DIFFERENT_UID
                        | PRECONDITION_ACTIVITY_SUPPORTS_INTENT_EXCEPTION,
                START_NOT_VOICE_COMPATIBLE);
        verifyStartActivityPreconditions(PRECONDITION_CANNOT_START_ANY_ACTIVITY, START_ABORTED);
    }

    private static boolean containsConditions(int preconditions, int mask) {
        return (preconditions & mask) == mask;
    }

    private void verifyStartActivityPreconditions(int preconditions, int expectedResult) {
        verifyStartActivityPreconditions(preconditions, 0 /*launchFlags*/, expectedResult);
    }

    private void verifyStartActivityPreconditions(int preconditions, int launchFlags,
            int expectedResult) {
        // We track mocks created here because this is used in a single test
        // (testStartActivityPreconditions) as a specific case, and mocks created inside it won't be
        // used for other cases. To avoid extensive memory usage, we clean up all used mocks after
        // each case. This is necessary because usually we only clean up mocks after a test
        // finishes, but this test creates too many mocks that the intermediate memory usage can be
        // ~0.8 GiB and thus very susceptible to OutOfMemoryException.
        try (MockTracker tracker = new MockTracker()) {
            verifyStartActivityPreconditionsUntracked(preconditions, launchFlags, expectedResult);
        }
    }

    /**
     * Excercises how the {@link ActivityStarter} reacts to various preconditions. The caller
     * provides a bitmask of all the set conditions (such as {@link #PRECONDITION_NO_CALLER_APP})
     * and the launch flags specified in the intent. The method constructs a call to
     * {@link ActivityStarter#execute} based on these preconditions and ensures the result matches
     * the expected. It is important to note that the method also checks side effects of the start,
     * such as ensuring {@link ActivityOptions#abort()} is called in the relevant scenarios.
     *
     * @param preconditions A bitmask representing the preconditions for the launch
     * @param launchFlags The launch flags to be provided by the launch {@link Intent}.
     * @param expectedResult The expected result from the launch.
     */
    private void verifyStartActivityPreconditionsUntracked(int preconditions, int launchFlags,
            int expectedResult) {
        final ActivityTaskManagerService service = mAtm;
        final IPackageManager packageManager = mock(IPackageManager.class);

        final ActivityStarter starter =
                new ActivityStarter(
                        mController,
                        service,
                        service.mTaskSupervisor,
                        mock(ActivityStartInterceptor.class),
                        mock(UserHelper.class));
        prepareStarter(launchFlags);
        final IApplicationThread caller = mock(IApplicationThread.class);
        final WindowProcessListener listener = mock(WindowProcessListener.class);
        final ProcessRecordInternal owner = mock(ProcessRecordInternal.class);

        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = "com.android.test.package";
        final WindowProcessController wpc =
                containsConditions(preconditions, PRECONDITION_NO_CALLER_APP)
                        ? null
                        : new WindowProcessController(service, ai, null, 0, -1, owner, listener);
        doReturn(wpc).when(service).getProcessController(any());

        final Intent intent = new Intent();
        intent.setFlags(launchFlags);

        final ActivityInfo aInfo = containsConditions(preconditions, PRECONDITION_NO_ACTIVITY_INFO)
                ?  null : new ActivityInfo();

        IVoiceInteractionSession voiceSession =
                containsConditions(preconditions, PRECONDITION_SOURCE_VOICE_SESSION)
                        ? mock(IVoiceInteractionSession.class) : null;

        // Create source token
        final ActivityBuilder builder = new ActivityBuilder(service).setTask(
                new TaskBuilder(service.mTaskSupervisor)
                        .setVoiceSession(voiceSession)
                        .setCreateParentTask(true)
                        .build());

        if (aInfo != null) {
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.packageName =
                    ActivityBuilder.getDefaultComponent().getPackageName();
        }

        // Offset uid by one from {@link ActivityInfo} to simulate different uids.
        if (containsConditions(preconditions, PRECONDITION_DIFFERENT_UID)) {
            builder.setUid(aInfo.applicationInfo.uid + 1);
        }

        final ActivityRecord source = builder.build();

        if (!containsConditions(preconditions, PRECONDITION_NO_INTENT_COMPONENT)) {
            intent.setComponent(source.mActivityComponent);
        }

        if (containsConditions(preconditions, PRECONDITION_CANNOT_START_ANY_ACTIVITY)) {
            doReturn(false).when(service.mTaskSupervisor).checkStartAnyActivityPermission(
                    any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any());
        }

        try {
            if (containsConditions(preconditions,
                    PRECONDITION_ACTIVITY_SUPPORTS_INTENT_EXCEPTION)) {
                doAnswer((inv) -> {
                    throw new RemoteException();
                }).when(packageManager).activitySupportsIntentAsUser(
                        eq(source.mActivityComponent), eq(intent), any(), anyInt());
            } else {
                doReturn(!containsConditions(preconditions, PRECONDITION_NO_VOICE_SESSION_SUPPORT))
                        .when(packageManager).activitySupportsIntentAsUser(
                                eq(source.mActivityComponent), eq(intent), any(), anyInt());
            }
        } catch (RemoteException e) {
        }

        final IBinder resultTo = containsConditions(preconditions, PRECONDITION_SOURCE_PRESENT)
                || containsConditions(preconditions, PRECONDITION_SOURCE_VOICE_SESSION)
                ? source.token : null;

        final int requestCode = containsConditions(preconditions, PRECONDITION_REQUEST_CODE)
                ? 1 : 0;

        final int result = starter.setCaller(caller)
                .setIntent(intent)
                .setActivityInfo(aInfo)
                .setResultTo(resultTo)
                .setRequestCode(requestCode)
                .setReason(name.getMethodName())
                .execute();

        // In some cases the expected result internally is different than the published result. We
        // must use ActivityStarter#getExternalResult to translate.
        assertEquals(ActivityStarter.getExternalResult(expectedResult), result);

        // Ensure that {@link ActivityOptions} are aborted with unsuccessful result.
        if (expectedResult != START_SUCCESS) {
            final ActivityStarter optionStarter = new ActivityStarter(mController, mAtm,
                    mAtm.mTaskSupervisor, mock(ActivityStartInterceptor.class),
                    mock(UserHelper.class));
            final ActivityOptions options = spy(ActivityOptions.makeBasic());

            final int optionResult = optionStarter.setCaller(caller)
                    .setIntent(intent)
                    .setActivityInfo(aInfo)
                    .setResultTo(resultTo)
                    .setRequestCode(requestCode)
                    .setReason(name.getMethodName())
                    .setActivityOptions(new SafeActivityOptions(
                            options, Binder.getCallingPid(), Binder.getCallingUid()))
                    .execute();
            verify(options, times(1)).abort();
        }
    }

    /**
     * Ensures that values specified at launch time are passed to {@link LaunchParamsModifier}
     * when we are laying out a new task.
     */
    @Test
    public void testCreateTaskLayout() {
        // modifier for validating passed values.
        final LaunchParamsModifier modifier = mock(LaunchParamsModifier.class);
        mAtm.mTaskSupervisor.getLaunchParamsController().registerModifier(modifier);

        // add custom values to activity info to make unique.
        final ActivityInfo info = new ActivityInfo();
        final Rect launchBounds = new Rect(0, 0, 20, 30);

        final WindowLayout windowLayout =
                createWindowLayoutWithMinSize(1, 2, null, TypedValue.COMPLEX_UNIT_PX);

        info.windowLayout = windowLayout;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = ActivityBuilder.getDefaultComponent().getPackageName();

        // create starter.
        final ActivityStarter optionStarter = prepareStarter(0 /* launchFlags */);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchBounds(launchBounds);

        // run starter.
        optionStarter
                .setActivityInfo(info)
                .setActivityOptions(new SafeActivityOptions(
                        options, Binder.getCallingPid(), Binder.getCallingUid()))
                .execute();

        // verify that values are passed to the modifier. Values are passed thrice -- two for
        // setting initial state, another when task is created.
        verify(modifier, times(3)).onCalculate(any(), eq(windowLayout), any(), any(), eq(options),
                any(), anyInt(), any(), any());
    }

    /**
     * This test ensures that if the intent is being delivered to a split-screen unfocused task
     * while it already on top, reports it as delivering to top.
     */
    @Test
    public void testSplitScreenDeliverToTop() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP,
                false /* mockGetRootTask */);
        final Pair<ActivityRecord, ActivityRecord> activities = createActivitiesInSplit();
        final ActivityRecord splitPrimaryFocusActivity = activities.first;
        final ActivityRecord splitSecondReusableActivity = activities.second;

        // Set focus back to primary.
        splitPrimaryFocusActivity.moveFocusableActivityToTop("testSplitScreenDeliverToTop");

        // Start activity and delivered new intent.
        starter.getIntent().setComponent(splitSecondReusableActivity.mActivityComponent);
        doReturn(splitSecondReusableActivity)
                .when(mRootWindowContainer).findTask(any(), any(), anyBoolean());
        final int result = starter.execute();

        // Ensure result is delivering intent to top.
        assertEquals(START_DELIVERED_TO_TOP, result);
    }

    /**
     * This test ensures that if the intent is being delivered to a split-screen unfocused task
     * reports it is brought to front instead of delivering to top.
     */
    @Test
    public void testSplitScreenTaskToFront() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP, false);
        final Pair<ActivityRecord, ActivityRecord> activities = createActivitiesInSplit();
        final ActivityRecord splitPrimaryFocusActivity = activities.first;
        final ActivityRecord splitSecondReusableActivity = activities.second;
        final ActivityRecord splitSecondTopActivity = new ActivityBuilder(mAtm).setCreateTask(true)
                .setParentTask(splitSecondReusableActivity.getRootTask()).build();
        assertTrue(splitSecondTopActivity.inMultiWindowMode());

        // Let primary stack has focus.
        splitPrimaryFocusActivity.moveFocusableActivityToTop("testSplitScreenTaskToFront");

        // Start activity and delivered new intent.
        starter.getIntent().setComponent(splitSecondReusableActivity.mActivityComponent);
        doReturn(splitSecondReusableActivity)
                .when(mRootWindowContainer).findTask(any(), any(), anyBoolean());
        final int result = starter.execute();

        // Ensure result is moving task to front.
        assertEquals(START_TASK_TO_FRONT, result);
    }

    /** Returns 2 activities. The first is in primary and the second is in secondary. */
    private Pair<ActivityRecord, ActivityRecord> createActivitiesInSplit() {
        final TestSplitOrganizer splitOrg = new TestSplitOrganizer(mAtm);
        // The fullscreen windowing mode activity will be moved to split-secondary by
        // TestSplitOrganizer when a split-primary task appears.
        final ActivityRecord splitPrimaryActivity = new TaskBuilder(mSupervisor)
                .setParentTask(splitOrg.mPrimary)
                .setCreateActivity(true)
                .build()
                .getTopMostActivity();
        final ActivityRecord splitSecondActivity = new TaskBuilder(mSupervisor)
                .setParentTask(splitOrg.mSecondary)
                .setCreateActivity(true)
                .build()
                .getTopMostActivity();

        splitPrimaryActivity.setVisibleRequested(true);
        splitSecondActivity.setVisibleRequested(true);

        assertEquals(splitOrg.mPrimary, splitPrimaryActivity.getRootTask());
        assertEquals(splitOrg.mSecondary, splitSecondActivity.getRootTask());
        return Pair.create(splitPrimaryActivity, splitSecondActivity);
    }

    /**
     * This test ensures that if the intent is being delivered to a desktop mode unfocused task
     * while it is already on top, reports it as delivering to top.
     */
    @Test
    public void testDesktopModeDeliverToTop() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP,
                false /* mockGetRootTask */);
        final List<ActivityRecord> activities = createActivitiesInDesktopMode();

        // Set focus back to the first task.
        activities.get(0).moveFocusableActivityToTop("testDesktopModeDeliverToTop");

        // Start activity and delivered new intent.
        starter.getIntent().setComponent(activities.get(3).mActivityComponent);
        doReturn(activities.get(3)).when(mRootWindowContainer).findTask(any(), any(), anyBoolean());
        final int result = starter.execute();

        // Ensure result is delivering intent to top.
        assertEquals(START_DELIVERED_TO_TOP, result);
    }

    /**
     * This test ensures that if the intent is being delivered to a desktop mode unfocused task
     * reports it is brought to front instead of delivering to top.
     */
    @Test
    public void testDesktopModeTaskToFront() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP, false);
        final List<ActivityRecord> activities = createActivitiesInDesktopMode();
        final ActivityRecord desktopModeFocusActivity = activities.get(0);
        final ActivityRecord desktopModeReusableActivity = activities.get(1);
        final ActivityRecord desktopModeTopActivity = new ActivityBuilder(mAtm).setCreateTask(true)
                .setParentTask(desktopModeReusableActivity.getRootTask()).build();
        assertTrue(desktopModeTopActivity.inMultiWindowMode());

        // Let first stack has focus.
        desktopModeFocusActivity.moveFocusableActivityToTop("testDesktopModeTaskToFront");

        // Start activity and delivered new intent.
        starter.getIntent().setComponent(desktopModeReusableActivity.mActivityComponent);
        doReturn(desktopModeReusableActivity)
                .when(mRootWindowContainer).findTask(any(), any(), anyBoolean());
        final int result = starter.execute();

        // Ensure result is moving task to front.
        assertEquals(START_TASK_TO_FRONT, result);
    }

    /** Returns 4 activities. */
    private List<ActivityRecord> createActivitiesInDesktopMode() {
        final TestDesktopOrganizer desktopOrganizer = new TestDesktopOrganizer(mAtm);
        List<ActivityRecord> activityRecords = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            Rect bounds = new Rect(desktopOrganizer.getDefaultDesktopTaskBounds());
            bounds.offset(20 * i, 20 * i);
            desktopOrganizer.createTask(bounds);
        }

        for (int i = 0; i < 4; i++) {
            activityRecords.add(new TaskBuilder(mSupervisor)
                    .setParentTask(desktopOrganizer.mTasks.get(i))
                    .setCreateActivity(true)
                    .build()
                    .getTopMostActivity());
        }

        for (int i = 0; i < 4; i++) {
            activityRecords.get(i).setVisibleRequested(true);
        }

        for (int i = 0; i < 4; i++) {
            assertEquals(desktopOrganizer.mTasks.get(i), activityRecords.get(i).getRootTask());
        }

        return activityRecords;
    }

    @Test
    public void testMoveVisibleTaskToFront() {
        final ActivityRecord activity = new TaskBuilder(mSupervisor)
                .setCreateActivity(true).build().getTopMostActivity();
        final ActivityRecord translucentActivity = new TaskBuilder(mSupervisor)
                .setCreateActivity(true).build().getTopMostActivity();
        assertTrue(activity.isVisibleRequested());

        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetRootTask */);
        starter.getIntent().setComponent(activity.mActivityComponent);
        final int result = starter.execute();

        assertEquals(START_TASK_TO_FRONT, result);
        assertEquals(1, activity.compareTo(translucentActivity));
    }

    @Test
    public void testReportStartedNoDisplayActivity() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true)
                .setVisible(false).build();
        final Task task = activity.getTask();
        task.inRecents = true;
        final ActivityRecord[] outActivity = new ActivityRecord[1];
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK)
                .setInTask(task)
                .setOutActivity(outActivity);
        starter.mRequest.activityInfo.applicationInfo.packageName = mContext.getPackageName();
        starter.mRequest.activityInfo.theme = android.R.style.Theme_NoDisplay;
        starter.execute();
        final ActivityRecord startedActivity = outActivity[0];

        assertNotNull(startedActivity);
        assertEquals(task, startedActivity.getTask());
        assertNotEquals("Report started no-display activity instead of the existing one",
                activity, startedActivity);
        assertTrue(startedActivity.isNoDisplay());

        startedActivity.setProcess(activity.app);
        startedActivity.setState(ActivityRecord.State.RESUMED, "test");
        startedActivity.makeFinishingLocked();
        // MetricsLogger will redirect to monitor next activity.
        startedActivity.setVisibility(false);

        assertNotNull(mSupervisor.getActivityMetricsLogger().notifyWindowsDrawn(activity));
    }

    /**
     * Tests activity is cleaned up properly in a task mode violation.
     */
    @Test
    public void testTaskModeViolation() {
        final DisplayContent display = mAtm.mRootWindowContainer.getDefaultDisplay();
        display.removeAllTasks();
        assertNoTasks(display);

        final ActivityStarter starter = prepareStarter(0);

        final LockTaskController lockTaskController = mAtm.getLockTaskController();
        doReturn(true).when(lockTaskController).isNewTaskLockTaskModeViolation(any());

        final int result = starter.execute();

        assertEquals(START_RETURN_LOCK_TASK_MODE_VIOLATION, result);
        assertNoTasks(display);
    }

    private void assertNoTasks(DisplayContent display) {
        display.forAllRootTasks(rootTask -> {
            assertFalse(rootTask.hasChild());
        });
    }

    /**
     * This test ensures that activity starts are not being logged when the logging is disabled.
     */
    @Test
    public void testActivityStartsLogging_noLoggingWhenDisabled() {
        doReturn(false).when(mAtm).isActivityStartsLoggingEnabled();

        ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK);
        starter.execute();

        // verify logging wasn't done
        verify(mActivityMetricsLogger, never()).logAbortedBgActivityStart(any(), any(), anyInt(),
                any(), anyInt(), anyBoolean(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    /**
     * This test ensures that activity starts are being logged when the logging is enabled.
     */
    @Test
    public void testActivityStartsLogging_logsWhenEnabled() {
        // note: conveniently this package doesn't have any activity visible
        doReturn(true).when(mAtm).isActivityStartsLoggingEnabled();

        ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK)
                .setCallingUid(FAKE_CALLING_UID)
                .setRealCallingUid(FAKE_REAL_CALLING_UID)
                .setCallingPackage(FAKE_CALLING_PACKAGE)
                .setOriginatingPendingIntent(null);

        starter.execute();

        // verify the above activity start was logged
        verify(mActivityMetricsLogger, times(1)).logAbortedBgActivityStart(any(), any(),
                eq(FAKE_CALLING_UID), eq(FAKE_CALLING_PACKAGE), anyInt(), anyBoolean(),
                eq(FAKE_REAL_CALLING_UID), anyInt(), anyBoolean(), eq(false));
    }

    /**
     * This test ensures that unsupported usecases are aborted when background starts are
     * disallowed.
     */
    @Test
    public void testPinnedSingleInstanceAborted() {
        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        // window visibility
        doReturn(false).when(mAtm).hasActiveVisibleWindow(UNIMPORTANT_UID);
        doReturn(false).when(mAtm).hasActiveVisibleWindow(UNIMPORTANT_UID2);
        // process importance
        mAtm.mActiveUids.onUidActive(UNIMPORTANT_UID, PROCESS_STATE_BOUND_TOP);
        mAtm.mActiveUids.onUidActive(UNIMPORTANT_UID2, PROCESS_STATE_BOUND_TOP);
        // foreground activities
        final IApplicationThread caller = mock(IApplicationThread.class);
        final ProcessRecordInternal owner = mock(ProcessRecordInternal.class);
        final WindowProcessListener listener = mock(WindowProcessListener.class);
        final ApplicationInfo ai = new ApplicationInfo();
        ai.uid = UNIMPORTANT_UID;
        ai.packageName = "com.android.test.package";
        final WindowProcessController callerApp = spy(new WindowProcessController(
                mAtm, ai, null, UNIMPORTANT_UID, -1, owner, listener));
        doReturn(false).when(callerApp).hasForegroundActivities();
        doReturn(callerApp).when(mAtm).getProcessController(caller);
        // caller is recents
        RecentTasks recentTasks = mock(RecentTasks.class);
        mAtm.mTaskSupervisor.setRecentTasks(recentTasks);
        doReturn(false).when(recentTasks).isCallerRecents(UNIMPORTANT_UID);
        // caller is temp allowed
        // caller is instrumenting with background activity starts privileges
        callerApp.setInstrumenting(false, -1, false);
        // callingUid is the device owner
        doReturn(false).when(mAtm).isDeviceOwner(UNIMPORTANT_UID);
        // callingUid is the affiliated profile owner
        doReturn(false).when(mAtm).isAffiliatedProfileOwner(UNIMPORTANT_UID);

        // caller has OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION appop
        doReturn(AppOpsManager.MODE_DEFAULT).when(mAppOpsManager).checkOpNoThrow(
                eq(AppOpsManager.OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION),
                anyInt(), any());

        final ActivityRecord baseActivity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        baseActivity.getRootTask().setWindowingMode(WINDOWING_MODE_PINNED);
        doReturn(baseActivity).when(mRootWindowContainer).findTask(any(), any(), anyBoolean());

        ActivityOptions rawOptions = ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED);
        final ActivityOptions options = spy(rawOptions);
        ActivityRecord[] outActivity = new ActivityRecord[1];
        ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_NEW_TASK, true, LAUNCH_SINGLE_INSTANCE)
                .setCallingPackage("com.whatever.dude")
                .setCaller(caller)
                .setCallingUid(UNIMPORTANT_UID)
                .setRealCallingUid(UNIMPORTANT_UID2)
                .setActivityOptions(new SafeActivityOptions(
                        options, Binder.getCallingPid(), Binder.getCallingUid()))
                .setOutActivity(outActivity);

        final int result = starter.execute();
        assertEquals(ActivityStarter.getExternalResult(START_ABORTED), result);
        verify(options, times(1)).abort();

        final ActivityRecord startedActivity = outActivity[0];
        if (startedActivity != null && startedActivity.getTask() != null) {
            // Remove the activity so it doesn't interfere with with subsequent activity launch
            // tests from this method.
            startedActivity.getTask().removeChild(startedActivity);
        }
    }

    /**
     * This test ensures that {@link ActivityStarter#setTargetRootTaskIfNeeded} will task the
     * adjacent task of indicated launch target into account. So the existing task will be launched
     * into closer target.
     */
    @Test
    public void testAdjustLaunchTargetWithAdjacentTask() {
        // Create adjacent tasks and put one activity under it
        final Task parent = new TaskBuilder(mSupervisor).build();
        final Task adjacentParent = new TaskBuilder(mSupervisor).build();
        parent.setAdjacentTaskFragments(new TaskFragment.AdjacentSet(parent, adjacentParent));
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setParentTask(parent)
                .setCreateTask(true).build();

        // Launch the activity to its adjacent parent
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchRootTask(adjacentParent.mRemoteToken.toWindowContainerToken());
        final Intent intent = activity.intent;
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK);
        prepareStarter(intent.getFlags(), false /* mockGetRootTask */)
                .setIntent(intent)
                .setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .execute();

        // Verify the activity will be launched into the original parent
        assertTrue(activity.isDescendantOf(parent));
    }

    /**
     * This test ensures that {@link ActivityStarter#setTargetRootTaskIfNeeded} will
     * move the existing task to front if the current focused root task doesn't have running task.
     */
    @Test
    public void testBringTaskToFrontWhenFocusedTaskIsFinishing() {
        // Put 2 tasks in the same root task (simulate the behavior of home root task).
        final Task rootTask = new TaskBuilder(mSupervisor).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setParentTask(rootTask)
                .setCreateTask(true).build();
        new ActivityBuilder(mAtm)
                .setParentTask(activity.getRootTask())
                .setCreateTask(true).build();

        // Create a top finishing activity.
        final ActivityRecord finishingTopActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true).build();
        finishingTopActivity.getRootTask().moveToFront("finishingTopActivity");

        assertEquals(finishingTopActivity, mRootWindowContainer.topRunningActivity());
        finishingTopActivity.finishing = true;

        // Launch the bottom task of the target root task.
        prepareStarter(FLAG_ACTIVITY_NEW_TASK, false /* mockGetRootTask */)
                .setIntent(activity.intent.addFlags(
                        FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                .execute();
        verify(activity.getRootTask()).startActivityLocked(any(), any(), anyBoolean(),
                eq(true) /* isTaskSwitch */, any(), any());
        // The hierarchies of the activity should move to front.
        assertEquals(activity.getTask(), mRootWindowContainer.topRunningActivity().getTask());
    }

    /**
     * This test ensures that when starting an existing single task activity on secondary display
     * which is not the top focused display, it should deliver new intent to the activity and not
     * create a new stack.
     */
    @Test
    public void testDeliverIntentToTopActivityOfNonTopDisplay() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetRootTask */);

        // Create a secondary display at bottom.
        final TestDisplayContent secondaryDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setPosition(POSITION_BOTTOM).build();
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        final Task stack = secondaryTaskContainer.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Create an activity record on the top of secondary display.
        final ActivityRecord topActivityOnSecondaryDisplay = createSingleTaskActivityOn(stack);

        // Put an activity on default display as the top focused activity.
        new ActivityBuilder(mAtm).setCreateTask(true).build();

        // Start activity with the same intent as {@code topActivityOnSecondaryDisplay}
        // on secondary display.
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(secondaryDisplay.mDisplayId);
        final int result = starter
                .setIntent(topActivityOnSecondaryDisplay.intent)
                .setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .execute();

        // Ensure result is delivering intent to top.
        assertEquals(START_DELIVERED_TO_TOP, result);

        // Ensure secondary display only creates one stack.
        verify(secondaryTaskContainer, times(1)).createRootTask(anyInt(), anyInt(), anyBoolean());
    }

    /**
     * This test ensures that when starting an existing non-top single task activity on secondary
     * display which is the top focused display, it should bring the task to front without creating
     * unused stack.
     */
    @Test
    public void testBringTaskToFrontOnSecondaryDisplay() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetRootTask */);

        // Create a secondary display with an activity.
        final TestDisplayContent secondaryDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500).build();
        mRootWindowContainer.positionChildAt(POSITION_TOP, secondaryDisplay,
                false /* includingParents */);
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        final ActivityRecord singleTaskActivity = createSingleTaskActivityOn(
                secondaryTaskContainer.createRootTask(WINDOWING_MODE_FULLSCREEN,
                        ACTIVITY_TYPE_STANDARD, false /* onTop */));
        // Activity should start invisible since we are bringing it to front.
        singleTaskActivity.setVisible(false);
        singleTaskActivity.setVisibleRequested(false);

        // Create another activity on top of the secondary display.
        final Task topStack = secondaryTaskContainer.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final Task topTask = new TaskBuilder(mSupervisor).setParentTask(topStack).build();
        new ActivityBuilder(mAtm).setTask(topTask).build();

        // Start activity with the same intent as {@code singleTaskActivity} on secondary display.
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(secondaryDisplay.mDisplayId);
        final int result = starter
                .setIntent(singleTaskActivity.intent)
                .setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .execute();

        // Ensure result is moving existing task to front.
        assertEquals(START_TASK_TO_FRONT, result);

        // Ensure secondary display only creates two stacks.
        verify(secondaryTaskContainer, times(2)).createRootTask(anyInt(), anyInt(), anyBoolean());
        // The metrics logger should receive the same result and non-null options.
        verify(mActivityMetricsLogger).notifyActivityLaunched(any() /* launchingState */,
                eq(result), eq(false) /* newActivityCreated */, eq(singleTaskActivity),
                notNull() /* options */);
    }


    /**
     * This test ensures that activity launch on a secondary display is allowed if the activity did
     * not opt out from showing on remote devices.
     */
    @Test
    public void testStartActivityOnVirtualDisplay() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetRootTask */);
        starter.mRequest.activityInfo.flags |= ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;

        // Create a virtual display at bottom.
        final TestDisplayContent secondaryDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setType(Display.TYPE_VIRTUAL)
                        .setPosition(POSITION_BOTTOM).build();
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        final Task stack = secondaryTaskContainer.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Create an activity record on the top of secondary display.
        final ActivityRecord topActivityOnSecondaryDisplay = createSingleTaskActivityOn(stack);

        // Put an activity on default display as the top focused activity.
        new ActivityBuilder(mAtm).setCreateTask(true).build();

        // Start activity with the same intent as {@code topActivityOnSecondaryDisplay}
        // on secondary display.
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(secondaryDisplay.mDisplayId);
        final int result = starter
                .setIntent(topActivityOnSecondaryDisplay.intent)
                .setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .execute();

        // Ensure result is delivering intent to top.
        assertEquals(START_DELIVERED_TO_TOP, result);

        // Ensure secondary display only creates one stack.
        verify(secondaryTaskContainer, times(1)).createRootTask(anyInt(), anyInt(), anyBoolean());
    }

    /**
     * This test ensures that activity launch on an insecure secondary display is disallowed if the
     * activity opted out from showing on remote devices.
     */
    @Test
    public void testStartOptedOutActivityOnInsecureVirtualDisplay() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetRootTask */);
        starter.mRequest.activityInfo.flags &= ~ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;

        // Create a virtual display at bottom.
        final TestDisplayContent secondaryDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setType(Display.TYPE_VIRTUAL)
                        .removeFlags(Display.FLAG_SECURE)
                        .setPosition(POSITION_BOTTOM).build();
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        final Task stack = secondaryTaskContainer.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Create an activity record on the top of secondary display.
        final ActivityRecord topActivityOnSecondaryDisplay = createSingleTaskActivityOn(stack);

        // Put an activity on default display as the top focused activity.
        new ActivityBuilder(mAtm).setCreateTask(true).build();

        // Start activity with the same intent as {@code topActivityOnSecondaryDisplay}
        // on secondary display.
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(secondaryDisplay.mDisplayId);
        final int result = starter
                .setIntent(topActivityOnSecondaryDisplay.intent)
                .setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .execute();

        // Ensure result is canceled.
        assertEquals(START_CANCELED, result);

        // Ensure secondary display only creates one stack.
        verify(secondaryTaskContainer, times(1)).createRootTask(anyInt(), anyInt(), anyBoolean());
    }

    /**
     * This test ensures that activity launch on a secure secondary display is allowed event if the
     * activity opted out from showing on remote devices.
     */
    @Test
    public void testStartOptedOutActivityOnSecureVirtualDisplay() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetRootTask */);
        starter.mRequest.activityInfo.flags &= ~ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;

        // Create a virtual display at bottom.
        final TestDisplayContent secondaryDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setType(Display.TYPE_VIRTUAL)
                        .addFlags(Display.FLAG_SECURE)
                        .setPosition(POSITION_BOTTOM).build();
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        final Task stack = secondaryTaskContainer.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        // Create an activity record on the top of secondary display.
        final ActivityRecord topActivityOnSecondaryDisplay = createSingleTaskActivityOn(stack);

        // Put an activity on default display as the top focused activity.
        new ActivityBuilder(mAtm).setCreateTask(true).build();

        // Start activity with the same intent as {@code topActivityOnSecondaryDisplay}
        // on secondary display.
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(secondaryDisplay.mDisplayId);
        final int result = starter
                .setIntent(topActivityOnSecondaryDisplay.intent)
                .setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .execute();

        // Ensure result is delivering intent to top.
        assertEquals(START_DELIVERED_TO_TOP, result);

        // Ensure secondary display only creates one stack.
        verify(secondaryTaskContainer, times(1)).createRootTask(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    public void testLaunchTopFocusedActivityToDifferentRootTask() {
        // Launch an activity to be the top focused one.
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task initialTask = activity.getTask();
        mAtm.setFocusedTask(initialTask.mTaskId);

        // Create a new RootTask to be the target
        final Task targetRootTask = new TaskBuilder(mSupervisor)
                .setWindowingMode(WINDOWING_MODE_MULTI_WINDOW)
                .setActivityType(ACTIVITY_TYPE_STANDARD)
                .setCreatedByOrganizer(true)
                .setOnTop(false)
                .build();

        // Prepare to launch the SAME activity intent, but targeting the new root task
        final Intent intent = new Intent(activity.intent);
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchRootTask(targetRootTask.mRemoteToken.toWindowContainerToken());

        // Execute the start
        prepareStarter(intent.getFlags(), false /* mockGetRootTask */)
                .setIntent(intent)
                .setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .execute();

        // The activity's task should now be reparented under the targetRootTask
        assertEquals(targetRootTask, initialTask.getParent());
        assertEquals(activity, initialTask.getTopNonFinishingActivity());
    }

    /**
     * This test ensures that activity launch on a secondary display that cannot host tasks is
     * disallowed, and a SecurityException should be thrown.
     */
    @Test
    @RequiresFlagsEnabled(FLAG_ENABLE_DISPLAY_CONTENT_MODE_MANAGEMENT)
    public void testStartActivityOnDisplayCannotHostTasks() {
        final ActivityStarter starter = prepareStarter(0);

        // Create a display that cannot host tasks.
        final TestDisplayContent secondaryDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setCanHostTasks(false).build();

        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();

        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(secondaryDisplay.mDisplayId);

        assertThrows(SecurityException.class,
                () -> starter
                        .setIntent(activity.intent)
                        .setActivityOptions(options.toBundle(), Binder.getCallingPid(),
                                Binder.getCallingUid())
                        .execute());
    }

    @Test
    public void testWasVisibleInRestartAttempt() {
        final ActivityStarter starter = prepareStarter(
                FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_SINGLE_TOP, false);
        final ActivityRecord reusableActivity =
                new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord topActivity =
                new ActivityBuilder(mAtm).setCreateTask(true).build();

        // Make sure topActivity is on top
        topActivity.getRootTask().moveToFront("testWasVisibleInRestartAttempt");
        reusableActivity.setVisible(false);

        final TaskChangeNotificationController taskChangeNotifier =
                mAtm.getTaskChangeNotificationController();
        spyOn(taskChangeNotifier);

        Task task = topActivity.getTask();
        starter.postStartActivityProcessing(
                task.getTopNonFinishingActivity(), START_DELIVERED_TO_TOP, task.getRootTask());

        verify(taskChangeNotifier).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(taskChangeNotifier).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), eq(true));

        Task task2 = reusableActivity.getTask();
        starter.postStartActivityProcessing(
                task2.getTopNonFinishingActivity(), START_TASK_TO_FRONT, task.getRootTask());
        verify(taskChangeNotifier, times(2)).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(taskChangeNotifier).notifyActivityRestartAttempt(
                any(), anyBoolean(), anyBoolean(), eq(false));
    }

    private ActivityRecord createSingleTaskActivityOn(Task task) {
        final ComponentName componentName = ComponentName.createRelative(
                DEFAULT_COMPONENT_PACKAGE_NAME,
                DEFAULT_COMPONENT_PACKAGE_NAME + ".SingleTaskActivity");
        return new ActivityBuilder(mAtm)
                .setComponent(componentName)
                .setLaunchMode(LAUNCH_SINGLE_TASK)
                .setTask(task)
                .build();
    }

    /**
     * This test ensures that a reused top activity in the top focused stack is able to be
     * reparented to another display.
     */
    @Test
    public void testReparentTopFocusedActivityToSecondaryDisplay() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK,
                false /* mockGetRootTask */);

        // Create a secondary display at bottom.
        final TestDisplayContent secondaryDisplay = addNewDisplayContentAt(POSITION_BOTTOM);
        final TaskDisplayArea secondaryTaskContainer = secondaryDisplay.getDefaultTaskDisplayArea();
        secondaryTaskContainer.createRootTask(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD,
                true /* onTop */);

        // Put an activity on default display as the top focused activity.
        final ActivityRecord topActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setLaunchMode(LAUNCH_SINGLE_TASK)
                .build();

        // Start activity with the same intent as {@code topActivity} on secondary display.
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(secondaryDisplay.mDisplayId);
        starter
                .setIntent(topActivity.intent)
                .setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .execute();

        // Ensure the activity is moved to secondary display.
        assertEquals(secondaryDisplay, topActivity.mDisplayContent);
    }

    /**
     * This test ensures that starting an activity with the freeze-task-list activity option will
     * actually freeze the task list
     */
    @Test
    public void testFreezeTaskListActivityOption() {
        RecentTasks recentTasks = mock(RecentTasks.class);
        mAtm.mTaskSupervisor.setRecentTasks(recentTasks);
        doReturn(true).when(recentTasks).isCallerRecents(anyInt());

        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setFreezeRecentTasksReordering();

        starter
                .setActivityOptions(new SafeActivityOptions(options,
                        Binder.getCallingPid(), Binder.getCallingUid()))
                .execute();

        verify(recentTasks, times(1)).setFreezeTaskListReordering();
        verify(recentTasks, times(0)).resetFreezeTaskListReorderingOnTimeout();
    }

    /**
     * This test ensures that if we froze the task list as a part of starting an activity that fails
     * to start, that we also reset the task list.
     */
    @Test
    public void testFreezeTaskListActivityOptionFailedStart_expectResetFreezeTaskList() {
        RecentTasks recentTasks = mock(RecentTasks.class);
        mAtm.mTaskSupervisor.setRecentTasks(recentTasks);
        doReturn(true).when(recentTasks).isCallerRecents(anyInt());

        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setFreezeRecentTasksReordering();

        starter
                .setActivityOptions(new SafeActivityOptions(options,
                        Binder.getCallingPid(), Binder.getCallingUid()))
                .execute();

        // Simulate a failed start
        starter.postStartActivityProcessing(null, START_CANCELED, null);

        verify(recentTasks, times(1)).setFreezeTaskListReordering();
        verify(recentTasks, times(1)).resetFreezeTaskListReorderingOnTimeout();
    }

    @Test
    public void testMovableTaskRequired_abortsIfTaskNotAllowedToMove() {
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic().setMovableTaskRequired(true);

        final TaskDisplayArea tda = mRootWindowContainer.getDefaultTaskDisplayArea();
        spyOn(tda);
        doReturn(false).when(tda).getIsTaskMoveAllowed();

        final int result = starter
                .setActivityOptions(
                        options.toBundle(),
                        Binder.getCallingPid(),
                        Binder.getCallingUid())
                .execute();

        assertEquals(START_CANNOT_GUARANTEE_TASK_MOVABILITY, result);
    }

    @Test
    public void testMovableTaskRequired_succeedsIfTaskAllowedToMove() {
        // Registering no-op modifier so that the test actually tests the ActivityStarter's
        // behavior, not LPMs'.
        final LaunchParamsModifier modifier = mock(LaunchParamsModifier.class);
        doReturn(LaunchParamsModifier.RESULT_DONE)
                .when(modifier)
                .onCalculate(any(), any(), any(), any(), any(), any(), anyInt(), any(),
                    any(LaunchParams.class));
        mAtm.mTaskSupervisor.getLaunchParamsController().registerModifier(modifier);

        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic().setMovableTaskRequired(true);

        final TaskDisplayArea tda = mRootWindowContainer.getDefaultTaskDisplayArea();
        spyOn(tda);
        doReturn(true).when(tda).getIsTaskMoveAllowed();

        final int result = starter
                .setActivityOptions(
                        options.toBundle(),
                        Binder.getCallingPid(),
                        Binder.getCallingUid())
                .execute();

        assertEquals(START_SUCCESS, result);
    }

    @Test
    public void testMovableTaskRequired_prioritizesLpmOverrideToFalse() {
        final LaunchParamsModifier modifier = mock(LaunchParamsModifier.class);
        mAtm.mTaskSupervisor.getLaunchParamsController().registerModifier(modifier);
        doAnswer(invocation -> {
            final LaunchParams out = invocation.getArgument(8);
            out.mIsTaskMoveDisallowed = true;
            return LaunchParamsModifier.RESULT_DONE;
        }).when(modifier).onCalculate(any(), any(), any(), any(), any(), any(), anyInt(), any(),
                any(LaunchParams.class));

        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = ActivityOptions.makeBasic().setMovableTaskRequired(true);

        final TaskDisplayArea tda = mRootWindowContainer.getDefaultTaskDisplayArea();
        spyOn(tda);
        doReturn(true).when(tda).getIsTaskMoveAllowed();

        final int result = starter
                .setActivityOptions(
                        options.toBundle(),
                        Binder.getCallingPid(),
                        Binder.getCallingUid())
                .execute();

        assertEquals(START_CANNOT_GUARANTEE_TASK_MOVABILITY, result);
    }

    @Test
    public void testMovableTaskRequired_abortsIfRecyclingTask() {
        final ActivityStarter starter = prepareStarter(0);
        final ActivityOptions options = ActivityOptions.makeBasic().setMovableTaskRequired(true);

        final ActivityRecord existing = new ActivityBuilder(mAtm).setCreateTask(true).build();

        final int result = starter
                .setIntent(existing.intent)
                .setActivityOptions(
                        options.toBundle(),
                        Binder.getCallingPid(),
                        Binder.getCallingUid())
                .execute();

        assertEquals(START_CANNOT_GUARANTEE_TASK_MOVABILITY, result);
    }


    @Test
    public void testNoActivityInfo() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        spyOn(starter.mRequest);

        final Intent intent = new Intent();
        intent.setComponent(ActivityBuilder.getDefaultComponent());
        starter.setActivityInfo(null).execute();
        verify(starter.mRequest).resolveActivity(any());

        // Also verifies the value of Request#componentSpecified should be true even the
        // ActivityStarter#setComponentSpecified is not explicitly set.
        assertTrue(starter.mRequest.componentSpecified);
    }

    @Test
    public void testResolveEphemeralInstaller() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final Intent intent = new Intent();
        intent.setComponent(ActivityBuilder.getDefaultComponent());

        doReturn(true).when(mMockPackageManager).isInstantAppInstallerComponent(any());
        starter.setIntent(intent).mRequest.resolveActivity(mAtm.mTaskSupervisor);

        // Make sure the client intent won't be modified.
        assertThat(intent.getComponent()).isNotNull();
        assertThat(starter.getIntent().getComponent()).isNull();
    }

    @Test
    public void testNotAllowIntentWithFd() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final Intent intent = spy(new Intent());
        intent.setComponent(ActivityBuilder.getDefaultComponent());
        doReturn(true).when(intent).hasFileDescriptors();

        boolean exceptionCaught = false;
        try {
            starter.setIntent(intent).execute();
        } catch (IllegalArgumentException ex) {
            exceptionCaught = true;
        }
        assertThat(exceptionCaught).isTrue();
    }

    @Test
    public void testRecycleTaskFromAnotherUser() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        starter.mStartActivity = new ActivityBuilder(mAtm).build();
        final Task task = new TaskBuilder(mAtm.mTaskSupervisor)
                .setParentTask(createTask(mDisplayContent, WINDOWING_MODE_FULLSCREEN,
                        ACTIVITY_TYPE_STANDARD))
                .setUserId(10)
                .build();

        final int result = starter.recycleTask(task, null, null, null);
        assertThat(result == START_SUCCESS).isTrue();
        assertThat(starter.mAddingToTask).isTrue();
    }

    @Test
    public void testRecycleTaskWhenBalBlocks() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        starter.mStartActivity = new ActivityBuilder(mAtm).build();
        starter.mBalVerdict = BalVerdict.BLOCK;
        final Task task = new TaskBuilder(mAtm.mTaskSupervisor)
                .setParentTask(createTask(mDisplayContent, WINDOWING_MODE_FULLSCREEN,
                        ACTIVITY_TYPE_STANDARD))
                .setCreateActivity(true)
                .build();
        final Task rootTask = task.getRootTask();
        final ActivityRecord topActivity = task.getTopMostActivity();

        spyOn(starter);
        final Task launchRootTask = new TaskBuilder(mAtm.mTaskSupervisor).build();
        doReturn(launchRootTask).when(starter).getOrCreateRootTask(eq(starter.mStartActivity),
                anyInt(), eq(task), any());

        starter.recycleTask(task, topActivity, task, null);
        assertThat(rootTask == task.getRootTask()).isTrue();
    }

    @Test
    public void testRecycleTaskWakeUpWhenDreaming() {
        doNothing().when(mWm.mAtmService.mTaskSupervisor).wakeUp(anyInt(), anyString());
        doReturn(true).when(mWm.mAtmService).isDreaming();
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final ActivityRecord target = new ActivityBuilder(mAtm).setCreateTask(true).build();
        starter.mStartActivity = target;
        target.setVisibleRequested(false);
        target.setTurnScreenOn(true);
        // Assume the flag was consumed by relayout.
        target.setCurrentLaunchCanTurnScreenOn(false);
        startActivityInner(starter, target, null /* source */, null /* options */,
                null /* inTask */, null /* inTaskFragment */);
        // The flag should be set again when resuming (from recycleTask) the target as top.
        assertTrue(target.currentLaunchCanTurnScreenOn());
        // In real case, dream activity has a higher priority (TaskDisplayArea#getPriority) that
        // will be put at a higher z-order. So it relies on wakeUp() to be dismissed.
        verify(mWm.mAtmService.mTaskSupervisor).wakeUp(eq(target.getDisplayId()), anyString());
    }

    @Test
    public void testTargetTaskInSplitScreen() {
        final ActivityStarter starter =
                prepareStarter(FLAG_ACTIVITY_LAUNCH_ADJACENT, false /* mockGetRootTask */);
        final ActivityRecord top = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityOptions options = ActivityOptions.makeBasic();
        final ActivityRecord[] outActivity = new ActivityRecord[1];

        // Activity must not land on split-screen task if currently not in split-screen mode.
        starter.setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .setOutActivity(outActivity).execute();
        assertThat(outActivity[0].inMultiWindowMode()).isFalse();

        // Move activity to split-screen-primary task and make sure it has the focus.
        TestSplitOrganizer splitOrg = new TestSplitOrganizer(mAtm, top.getDisplayContent());
        top.getRootTask().reparent(splitOrg.mPrimary, POSITION_BOTTOM);
        top.getRootTask().moveToFront("testTargetTaskInSplitScreen");

        // Activity must land on split-screen-secondary when launch adjacent.
        startActivityInner(starter, outActivity[0], top, options, null /* inTask */,
                null /* taskFragment*/);
        assertThat(outActivity[0].inMultiWindowMode()).isTrue();
    }

    @Test
    public void testLaunchAdjacentDisabled() {
        final ActivityStarter starter =
                prepareStarter(FLAG_ACTIVITY_LAUNCH_ADJACENT, false /* mockGetRootTask */);
        final ActivityRecord top = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityOptions options = ActivityOptions.makeBasic();
        final ActivityRecord[] outActivity = new ActivityRecord[1];

        // Activity must not land on split-screen task if currently not in split-screen mode.
        starter.setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .setOutActivity(outActivity).execute();
        assertThat(outActivity[0].inMultiWindowMode()).isFalse();

        // Move activity to split-screen-primary task and make sure it has the focus.
        TestSplitOrganizer splitOrg = new TestSplitOrganizer(mAtm, top.getDisplayContent());
        top.getRootTask().reparent(splitOrg.mPrimary, POSITION_BOTTOM);
        top.getRootTask().moveToFront("testLaunchAdjacentDisabled");
        top.getRootTask().setLaunchAdjacentDisabled(true);

        // Ensure activity does not launch into split-screen-secondary when launch adjacent is
        // disabled
        startActivityInner(starter, outActivity[0], top, options, null /* inTask */,
                null /* taskFragment*/);
        assertThat(outActivity[0].isDescendantOf(splitOrg.mSecondary)).isFalse();
    }

    @Test
    public void testTransientLaunchWithKeyguard() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        final ActivityRecord target = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord top = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final KeyguardController keyguard = mSupervisor.getKeyguardController();
        doReturn(true).when(keyguard).isKeyguardLocked(anyInt());
        doReturn(true).when(keyguard).isKeyguardOccluded(anyInt());
        registerTestTransitionPlayer();
        starter.setActivityOptions(ActivityOptions.makeBasic().setTransientLaunch().toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .setIntent(target.intent)
                .execute();
        final TransitionController controller = mRootWindowContainer.mTransitionController;
        final Transition transition = controller.getCollectingTransition();
        final Transition.ChangeInfo targetChangeInfo = transition.mChanges.get(target);

        assertThat(targetChangeInfo).isNotNull();
        assertThat(targetChangeInfo.hasChanged()).isTrue();
        assertThat(controller.isCollecting(top.getTask())).isTrue();
        assertThat(transition.isTransientLaunch(target)).isTrue();
        assertThat(transition.isInTransientHide(top.getTask())).isTrue();
    }

    @Test
    public void testTransitionSetReady() {
        final Task task = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord[] overlayActivity = new ActivityRecord[1];
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setTaskOverlay(true /* taskOverlay */, true /* canResume */);
        options.setLaunchTaskId(task.mTaskId);
        requestTransition(mDisplayContent, WindowManager.TRANSIT_OPEN);
        final Transition transition =
                mRootWindowContainer.mTransitionController.getCollectingTransition();
        spyOn(transition);
        final ActivityStarter starter = prepareStarter(0 /* flags */, false /* mockGetRootTask */);
        starter.setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .setInTask(task)
                .setOutActivity(overlayActivity)
                .execute();

        assertTrue(overlayActivity[0].isAlwaysOnTop());
        // Expect setReady(false) because it is usually the case: prev paused -> resume next.
        verify(transition).setReady(eq(overlayActivity[0]), eq(false));

        clearInvocations(transition);
        final ActivityStarter starter2 = prepareStarter(0 /* flags */, false /* mockGetRootTask */);
        spyOn(starter2);
        doReturn(START_SUCCESS).when(starter2).isAllowedToStart(any(), anyBoolean(), any());
        starter2.getIntent().setComponent(
                ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME, "RegularActivity"));
        starter2.setInTask(task).execute();

        assertEquals(3, overlayActivity[0].getParent().getChildCount());
        assertEquals(overlayActivity[0], overlayActivity[0].getParent().getChildAt(2));
        // The started activity is not the top child, so setReady(false) should NOT be called.
        verify(transition, never()).setReady(any(), eq(false));
    }

    /**
     * Tests ATMS#startActivityWithScreenshot should collect display content for creating snapshot.
     */
    @Test
    public void testActivityStartWithScreenshot() {
        final ActivityStarter starter = prepareStarter(0 /* flags */);
        starter.setFreezeScreen(true);

        registerTestTransitionPlayer();

        final Intent intent = new Intent();
        intent.setComponent(ActivityBuilder.getDefaultComponent());
        starter.setIntent(intent).execute();

        final TransitionController controller = mRootWindowContainer.mTransitionController;
        final Transition transition = controller.getCollectingTransition();
        final Transition.ChangeInfo targetChangeInfo = transition.mChanges.get(mDisplayContent);
        assertThat(targetChangeInfo).isNotNull();
    }

    @Test
    public void testActivityStart_expectAddedToRecentTask() {
        RecentTasks recentTasks = mock(RecentTasks.class);
        mAtm.mTaskSupervisor.setRecentTasks(recentTasks);
        doReturn(true).when(recentTasks).isCallerRecents(anyInt());

        final ActivityStarter starter = prepareStarter(0 /* flags */);

        starter.execute();

        verify(recentTasks, times(1)).add(any());
    }

    @Test
    public void testStartActivityInner_inTaskFragment_failsByDefault() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final TaskFragment taskFragment = new TaskFragment(mAtm, sourceRecord.token,
                true /* createdByOrganizer */);
        sourceRecord.getTask().addChild(taskFragment, POSITION_TOP);

        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, taskFragment);

        assertFalse(taskFragment.hasChild());
        assertNotNull("Target record must be started on Task.", targetRecord.getParent().asTask());
    }

    @Test
    public void testStartActivityInner_inTaskFragment_allowedForSystemUid() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final TaskFragment taskFragment = new TaskFragment(mAtm, sourceRecord.token,
                true /* createdByOrganizer */);
        sourceRecord.getTask().addChild(taskFragment, POSITION_TOP);

        taskFragment.setTaskFragmentOrganizer(mock(TaskFragmentOrganizerToken.class), SYSTEM_UID,
                "system_uid");

        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, taskFragment);

        assertTrue(taskFragment.hasChild());
    }

    @Test
    public void testStartActivityInner_inTaskFragment_allowedForSameUid() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final TaskFragment taskFragment = new TaskFragment(mAtm, sourceRecord.token,
                true /* createdByOrganizer */);
        sourceRecord.getTask().addChild(taskFragment, POSITION_TOP);

        taskFragment.setTaskFragmentOrganizer(mock(TaskFragmentOrganizerToken.class),
                targetRecord.getUid(), "test_process_name");

        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, taskFragment);

        assertTrue(taskFragment.hasChild());
    }

    @Test
    public void testStartActivityInner_inTaskFragment_allowedTrustedCertUid() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final TaskFragment taskFragment = new TaskFragment(mAtm, sourceRecord.token,
                true /* createdByOrganizer */);
        sourceRecord.getTask().addChild(taskFragment, POSITION_TOP);

        taskFragment.setTaskFragmentOrganizer(mock(TaskFragmentOrganizerToken.class),
                12345, "test_process_name");
        AndroidPackage androidPackage = mock(AndroidPackage.class);
        doReturn(androidPackage).when(mMockPackageManager).getPackage(eq(12345));

        Set<String> certs = new HashSet(Arrays.asList("test_cert1", "test_cert1"));
        targetRecord.info.setKnownActivityEmbeddingCerts(certs);
        SigningDetails signingDetails = mock(SigningDetails.class);
        doReturn(true).when(signingDetails).hasAncestorOrSelfWithDigest(any());
        doReturn(signingDetails).when(androidPackage).getSigningDetails();

        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, taskFragment);

        assertTrue(taskFragment.hasChild());
    }

    @Test
    public void testStartActivityInner_inTaskFragment_allowedForUntrustedEmbedding() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final TaskFragment taskFragment = new TaskFragment(mAtm, sourceRecord.token,
                true /* createdByOrganizer */);
        sourceRecord.getTask().addChild(taskFragment, POSITION_TOP);

        targetRecord.info.flags |= ActivityInfo.FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING;

        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, taskFragment);

        assertTrue(taskFragment.hasChild());
    }

    @Test
    public void testStartActivityInner_inTask() {
        final ActivityStarter starter = prepareStarter(0, false);
        // Simulate an app uses AppTask to create a non-attached task, and then it requests to
        // start activity in the task.
        final Task inTask = new TaskBuilder(mSupervisor).setTaskDisplayArea(null).setTaskId(123)
                .build();
        inTask.inRecents = true;
        assertFalse(inTask.isAttached());
        final ActivityRecord target = new ActivityBuilder(mAtm).build();
        startActivityInner(starter, target, null /* source */, null /* options */, inTask,
                null /* inTaskFragment */);

        assertTrue(inTask.isAttached());
        assertEquals(inTask, target.getTask());
    }

    @Test
    public void testLaunchCookie_newAndExistingTask() {
        final ActivityStarter starter = prepareStarter(0, false);

        // Put an activity on default display as the top focused activity.
        ActivityRecord r = new ActivityBuilder(mAtm).setCreateTask(true).build();

        // Start an activity with a launch cookie
        final Binder cookie = new Binder();
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchCookie(cookie);
        final Intent intent = new Intent();
        intent.setComponent(ActivityBuilder.getDefaultComponent());
        starter.setIntent(intent)
                .setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .execute();

        // Verify the cookie is set
        assertTrue(mRootWindowContainer.topRunningActivity().mLaunchCookie == cookie);

        // Relaunch the activity to bring the task forward
        final Binder newCookie = new Binder();
        final ActivityOptions newOptions = ActivityOptions.makeBasic();
        newOptions.setLaunchCookie(newCookie);
        starter.setIntent(intent)
                .setActivityOptions(newOptions.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .execute();

        // Verify the cookie is updated
        assertTrue(mRootWindowContainer.topRunningActivity().mLaunchCookie == newCookie);
    }

    @Test
    public void testRemoteAnimation_appliesToExistingTask() {
        final ActivityStarter starter = prepareStarter(0, false);

        // Put an activity on default display as the top focused activity.
        ActivityRecord r = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Intent intent = new Intent();
        intent.setComponent(ActivityBuilder.getDefaultComponent());
        starter.setIntent(intent).execute();

        assertNull(mRootWindowContainer.topRunningActivity().mPendingRemoteAnimation);

        // Relaunch the activity with remote animation indicated in options.
        final RemoteAnimationAdapter adaptor = mock(RemoteAnimationAdapter.class);
        final ActivityOptions options = ActivityOptions.makeRemoteAnimation(adaptor);
        starter.setIntent(intent)
                .setActivityOptions(options.toBundle(),
                        Binder.getCallingPid(), Binder.getCallingUid())
                .execute();

        // Verify the remote animation is updated.
        assertEquals(adaptor, mRootWindowContainer.topRunningActivity().mPendingRemoteAnimation);
    }

    @Test
    @RequiresFlagsDisabled(com.android.wm.shell.Flags.FLAG_ENABLE_PIP2)
    public void testStartLaunchIntoPipActivity_setLaunchIntoPipHostActivity() {
        final ActivityStarter starter = prepareStarter(0, false);

        // Create an activity from ActivityOptions#makeLaunchIntoPip
        final PictureInPictureParams params = new PictureInPictureParams.Builder()
                .build();
        final ActivityOptions opts = ActivityOptions.makeLaunchIntoPip(params);
        ActivityRecord targetRecord = new ActivityBuilder(mAtm)
                .setActivityOptions(opts)
                .build();

        // Start the target launch-into-pip activity from a source
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setCreateTask(true).build();
        startActivityInner(starter, targetRecord, sourceRecord, opts,
                null /* inTask */, null /* inTaskFragment */);

        // Verify the ActivityRecord#getLaunchIntoPipHostActivity points to sourceRecord.
        assertThat(targetRecord.getLaunchIntoPipHostActivity()).isNotNull();
        assertEquals(targetRecord.getLaunchIntoPipHostActivity(), sourceRecord);
    }

    @Test
    public void testResultCanceledWhenNotAllowedStartingActivity() {
        final Task task = new TaskBuilder(mSupervisor).build();
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        final ActivityRecord sourceRecord = new ActivityBuilder(mAtm).setTask(task).build();
        targetRecord.resultTo = sourceRecord;

        // Abort the activity start and ensure the sourceRecord gets the result (RESULT_CANCELED).
        spyOn(starter);
        doReturn(START_ABORTED).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        startActivityInner(starter, targetRecord, sourceRecord, null /* options */,
                null /* inTask */, null /* inTaskFragment */);
        verify(sourceRecord).sendResult(anyInt(), any(), anyInt(), eq(RESULT_CANCELED), any(),
                any(), any());
    }

    @Test
    public void testCanEmbedActivity() {
        final Size minDimensions = new Size(1000, 1000);
        final WindowLayout windowLayout = createWindowLayoutWithMinSize(minDimensions.getWidth(),
                minDimensions.getHeight(), mContext.getResources().getDisplayMetrics(),
                TypedValue.COMPLEX_UNIT_PX);
        final ActivityRecord starting = new ActivityBuilder(mAtm)
                .setUid(UNIMPORTANT_UID)
                .setWindowLayout(windowLayout)
                .build();

        // Task fragment hasn't attached to a task yet. Start activity to a new task.
        TaskFragment taskFragment = new TaskFragmentBuilder(mAtm).build();
        final Task task = new TaskBuilder(mSupervisor).build();

        assertEquals(EMBEDDING_DISALLOWED_NEW_TASK,
                canEmbedActivity(taskFragment, starting, task));

        // Starting activity is going to be started on a task different from task fragment's parent
        // task. Start activity to a new task.
        task.addChild(taskFragment, POSITION_TOP);
        final Task newTask = new TaskBuilder(mSupervisor).build();

        assertEquals(EMBEDDING_DISALLOWED_NEW_TASK,
                canEmbedActivity(taskFragment, starting, newTask));

        // Make task fragment bounds exceed task bounds.
        final Rect taskBounds = task.getBounds();
        taskFragment.setBounds(taskBounds.left, taskBounds.top, taskBounds.right + 1,
                taskBounds.bottom + 1);

        assertEquals(EMBEDDING_DISALLOWED_UNTRUSTED_HOST,
                canEmbedActivity(taskFragment, starting, task));

        taskFragment.setBounds(taskBounds);
        starting.info.flags |= FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING;

        assertEquals(EMBEDDING_ALLOWED, canEmbedActivity(taskFragment, starting, task));

        starting.info.flags &= ~FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING;
        // Set task fragment's uid as the same as starting activity's uid.
        taskFragment.setTaskFragmentOrganizer(mock(TaskFragmentOrganizerToken.class),
                UNIMPORTANT_UID, "test");

        assertEquals(EMBEDDING_ALLOWED, canEmbedActivity(taskFragment, starting, task));

        // Make task fragment bounds smaller than starting activity's minimum dimensions
        taskFragment.setBounds(0, 0, minDimensions.getWidth() - 1, minDimensions.getHeight() - 1);

        assertEquals(EMBEDDING_DISALLOWED_MIN_DIMENSION_VIOLATION,
                canEmbedActivity(taskFragment, starting, task));
    }

    @Test
    public void testRecordActivityMovementBeforeDeliverToTop() {
        // Mock recents as task is only marked to be in recents
        mAtm.mTaskSupervisor.setRecentTasks(mock(RecentTasks.class));

        final Task task = new TaskBuilder(mAtm.mTaskSupervisor).build();
        final ActivityRecord activityBot = new ActivityBuilder(mAtm).setTask(task).build();
        final ActivityRecord activityTop = new ActivityBuilder(mAtm).setTask(task).build();

        activityBot.setVisible(false);
        activityBot.setVisibleRequested(false);

        assertTrue(activityTop.isVisible());
        assertTrue(activityTop.isVisibleRequested());

        final ActivityRecord[] outActivity = new ActivityRecord[1];
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_REORDER_TO_FRONT
                        | FLAG_ACTIVITY_NEW_TASK, false /* mockGetRootTask */);
        starter.mStartActivity = activityBot;
        task.inRecents = true;
        starter.setInTask(task).setOutActivity(outActivity);
        starter.getIntent().setComponent(activityBot.mActivityComponent);
        final int result = starter.execute();

        assertEquals(START_DELIVERED_TO_TOP, result);
        assertNotNull(starter.mMovedToTopActivity);
        assertEquals(activityBot, outActivity[0]);

        final ActivityStarter starter2 = prepareStarter(FLAG_ACTIVITY_REORDER_TO_FRONT
                        | FLAG_ACTIVITY_NEW_TASK, false /* mockGetRootTask */);
        starter2.setInTask(task);
        starter2.getIntent().setComponent(activityBot.mActivityComponent);
        final int result2 = starter2.execute();

        assertEquals(START_DELIVERED_TO_TOP, result2);
        assertNull(starter2.mMovedToTopActivity);
    }

    /**
     * Tests a task with specific display category exist in system and then launching another
     * activity with the same affinity but without define the display category. Make sure the
     * lunching activity is placed on the different task.
     */
    @Test
    public void testLaunchActivityWithoutDisplayCategory() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.taskAffinity = ActivityRecord.computeTaskAffinity("test", DEFAULT_FAKE_UID);
        info.requiredDisplayCategory = "automotive";
        final Task task = new TaskBuilder(mSupervisor).setCreateActivity(true).setActivityInfo(info)
                .build();

        final ActivityRecord target = new ActivityBuilder(mAtm).setAffinity(info.taskAffinity)
                .build();
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK, false);
        spyOn(starter);
        doReturn(START_SUCCESS).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        startActivityInner(starter, target, null /* source */, null /* options */,
                null /* inTask */, null /* inTaskFragment */);

        assertNotEquals(task, target.getTask());
    }

    /**
     * Tests a task with a specific display category exist in the system and then launches another
     * activity with the different display category. Make sure the launching activity is not placed
     * on the sourceRecord's task.
     */
    @Test
    public void testLaunchActivityWithDifferentDisplayCategory() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.taskAffinity = ActivityRecord.computeTaskAffinity("test", DEFAULT_FAKE_UID);
        info.requiredDisplayCategory = "automotive";
        final Task task = new TaskBuilder(mSupervisor).setCreateActivity(true).setActivityInfo(info)
                .build();

        final ActivityRecord target = new ActivityBuilder(mAtm).setRequiredDisplayCategory("auto")
                .setAffinity(info.taskAffinity).build();
        final ActivityStarter starter = prepareStarter(0, false);
        spyOn(starter);
        doReturn(START_SUCCESS).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        startActivityInner(starter, target,  task.getBottomMostActivity(), null /* options */,
                null /* inTask */, null /* inTaskFragment */);

        assertNotEquals(task, target.getTask());
    }

    /**
     * Tests a task with specific display category exist in system and launching another
     * activity without display category from that task. Make sure the launching activity
     * is placed on the different task since the source has display category.
     */
    @Test
    public void testLaunchActivityFromSourceWithDisplayCategory() {
        final String category = "automotive";
        final String affinity = ActivityRecord.computeTaskAffinity("test", DEFAULT_FAKE_UID);

        // Create source activity with display category
        final ActivityRecord sourceActivity = new ActivityBuilder(mAtm)
                .setRequiredDisplayCategory(category)
                .setAffinity(affinity)
                .setCreateTask(true)
                .build();
        final Task task = sourceActivity.getTask();

        // Create target activity without display category
        final ActivityRecord target = new ActivityBuilder(mAtm)
                .setAffinity(affinity)
                .build();

        final ActivityStarter starter = prepareStarter(0, false);
        spyOn(starter);
        doReturn(START_SUCCESS).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        startActivityInner(starter, target, sourceActivity, null /* options */,
                null /* inTask */, null /* inTaskFragment */);

        assertNotEquals(task, target.getTask());
    }

    /**
     * Tests a task with specific display category exist in system and then launching another
     * activity with the same display category. Make sure the launching activity is placed on the
     * same task.
     */
    @Test
    public void testLaunchActivityWithSameDisplayCategory() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.taskAffinity = ActivityRecord.computeTaskAffinity("test", DEFAULT_FAKE_UID);
        info.requiredDisplayCategory = "automotive";
        final Task task = new TaskBuilder(mSupervisor).setCreateActivity(true).setActivityInfo(info)
                .build();

        final ActivityRecord target = new ActivityBuilder(mAtm)
                .setRequiredDisplayCategory(info.requiredDisplayCategory)
                .setAffinity(info.taskAffinity).build();

        final ActivityStarter starter = prepareStarter(0, false);
        spyOn(starter);
        doReturn(START_SUCCESS).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        startActivityInner(starter, target,  task.getBottomMostActivity(), null /* options */,
                null /* inTask */, null /* inTaskFragment */);

        assertEquals(task, target.getTask());
    }

    /**
     * Tests a task with specific display category exist in system and launching activity into the
     * specific task within inTask attribute. Make sure the activity is not placed on the task since
     * the display category is different.
     */
    @Test
    public void testLaunchActivityInTaskWithDisplayCategory() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.requiredDisplayCategory = "automotive";
        final Task inTask = new TaskBuilder(mSupervisor).setActivityInfo(info).build();
        inTask.inRecents = true;

        final ActivityStarter starter = prepareStarter(0, false);
        spyOn(starter);
        doReturn(START_SUCCESS).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        final ActivityRecord target = new ActivityBuilder(mAtm).build();
        startActivityInner(starter, target, null /* source */, null /* options */, inTask,
                null /* inTaskFragment */);

        assertNotEquals(inTask, target.getTask());
    }

    /**
     * Tests a task without a specific display category exist in the system and launches activity
     * with display category into the task within the inTask attribute. Make sure the activity is
     * not placed on the task since the display category is different.
     */
    @Test
    public void testLaunchDisplayCategoryActivityInTask() {
        final Task inTask = new TaskBuilder(mSupervisor).build();
        inTask.inRecents = true;

        final ActivityStarter starter = prepareStarter(0, false);
        spyOn(starter);
        doReturn(START_SUCCESS).when(starter).isAllowedToStart(any(), anyBoolean(), any());
        final ActivityRecord target = new ActivityBuilder(mAtm).setRequiredDisplayCategory("auto")
                .build();
        startActivityInner(starter, target, null /* source */, null /* options */, inTask,
                null /* inTaskFragment */);

        assertNotEquals(inTask, target.getTask());
    }

    @Test
    public void testLaunchActivitySourceRemain() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord baseActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .build();
        baseActivity.mLaunchSourceType = ActivityRecord.LAUNCH_SOURCE_TYPE_HOME;
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm).build();
        targetRecord.mLaunchSourceType = ActivityRecord.LAUNCH_SOURCE_TYPE_APPLICATION;
        starter.mLastStartActivityRecord = targetRecord;
        startActivityInner(starter, targetRecord, baseActivity, null /* options */,
                null /* inTask */, null /* inTaskFragment */);
        assertEquals(baseActivity.getTask(), targetRecord.getTask());
        assertEquals(ActivityRecord.LAUNCH_SOURCE_TYPE_HOME, baseActivity.mLaunchSourceType);
    }

    @Test
    public void launchActivity_reusesBubbledTask() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord bubbledActivity = createBubbledActivity();

        // create the target activity to be launched with the same component as the bubbled activity
        final ActivityRecord targetRecord = new ActivityBuilder(mAtm)
                .setLaunchMode(LAUNCH_SINGLE_TASK)
                .setComponent(ActivityBuilder.getDefaultComponent()).build();
        starter.getIntent().setComponent(bubbledActivity.mActivityComponent);
        startActivityInner(starter, targetRecord, bubbledActivity, null /* options */,
                null /* inTask */, null /* inTaskFragment */);

        assertEquals(bubbledActivity.getTask(), targetRecord.getTask());
    }

    @Test
    public void launchActivity_nullSourceRecord_doesNotReuseBubbledTask() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord bubbledActivity = createBubbledActivity();

        // create the target activity to be launched
        final ActivityRecord targetRecord =
                new ActivityBuilder(mAtm)
                        .setLaunchMode(LAUNCH_SINGLE_TASK)
                        .setComponent(ActivityBuilder.getDefaultComponent()).build();
        starter.getIntent().setComponent(bubbledActivity.mActivityComponent);

        // pass null as the source record
        startActivityInner(starter, targetRecord, null, null /* options */,
                null /* inTask */, null /* inTaskFragment */);

        assertNotEquals(bubbledActivity.getTask(), targetRecord.getTask());
    }

    @Test
    public void launchActivity_nonBubbledSourceRecord_doesNotReuseBubbledTask() {
        final ActivityStarter starter = prepareStarter(0, false);
        final ActivityRecord bubbledActivity = createBubbledActivity();

        // create a non bubbled activity
        final ActivityRecord nonBubbleSourceRecord =
                new ActivityBuilder(mAtm).setCreateTask(true)
                        .setLaunchMode(LAUNCH_SINGLE_TASK)
                        .setComponent(ActivityBuilder.getDefaultComponent())
                        .build();

        // create the target activity to be launched
        final ActivityRecord targetRecord =
                new ActivityBuilder(mAtm)
                        .setLaunchMode(LAUNCH_SINGLE_TASK)
                        .setComponent(ActivityBuilder.getDefaultComponent()).build();
        starter.getIntent().setComponent(bubbledActivity.mActivityComponent);

        // use the non bubbled activity as the source
        startActivityInner(starter, targetRecord, nonBubbleSourceRecord, null /* options */,
                null /* inTask */, null /* inTaskFragment*/);

        assertNotEquals(bubbledActivity.getTask(), targetRecord.getTask());
    }

    @Test
    @EnableFlags(FLAG_TRACK_LAUNCH_ORIGINATOR)
    public void launchActivity_homeCallingUid_setsOriginalCallerUid() {
        final int homeUid = 51121;
        final Task homeTask = mRootWindowContainer.getDefaultTaskDisplayArea().getRootHomeTask();
        final ActivityRecord homeActivity = new ActivityBuilder(mAtm).setTask(homeTask).build();
        mAtm.mHomeProcess = mSystemServicesTestRule.addProcess(homeActivity.packageName,
                homeActivity.processName, 114514 /* pid */, homeUid);
        final ActivityStarter starter = prepareStarter(FLAG_ACTIVITY_NEW_TASK)
                .setRealCallingUid(homeUid);

        // Launch from home.
        starter.execute();

        assertEquals(homeUid, starter.mRequest.mOriginalCallerUid);
    }

    /**
     * This test simulates the following scenario:
     * 1. Privileged app (P) starts malicious app's activity (M1).
     * 2. M1 starts M2 (also in malicious app) using startNextMatchingActivity().
     *     This causes M2's launchedFromPackage to be P.
     * 3. M2 starts an activity in P (P2) using startActivity() with
     *     FLAG_ACTIVITY_FORWARD_RESULT.
     * The test verifies that P2's launchedFromPackage is M, not P.
     * See b/457742426 for details.
     */
    @Test
    public void testLaunchedFromPackage_nextMatchingActivity_forwardResult() {
        final String privilegedPackage = "com.test.privileged";
        final int privilegedUid = 10001;
        final String maliciousPackage = "com.test.malicious";
        final int maliciousUid = 10002;

        // Setup P1 activity
        final ActivityRecord p1 = new ActivityBuilder(mAtm)
                .setComponent(new ComponentName(privilegedPackage, "P1Activity"))
                .setUid(privilegedUid)
                .setCreateTask(true)
                .build();

        // Setup M1 activity, launched by P1
        final ActivityRecord m1 = new ActivityBuilder(mAtm)
                .setComponent(new ComponentName(maliciousPackage, "M1Activity"))
                .setUid(maliciousUid)
                .setCreateTask(true)
                .setLaunchedFromPackage(privilegedPackage)
                .setLaunchedFromUid(privilegedUid)
                .build();
        m1.resultTo = p1;

        // Setup M2 activity, as if launched from M1 via startNextMatchingActivity()
        final ActivityRecord m2 = new ActivityBuilder(mAtm)
                .setComponent(new ComponentName(maliciousPackage, "M2Activity"))
                .setUid(maliciousUid)
                .setCreateTask(true)
                .setLaunchedFromPackage(privilegedPackage) // Spoofed package name
                .setLaunchedFromUid(maliciousUid)
                .build();
        m2.resultTo = p1; // result is forwarded

        // M2 starts P2
        final ActivityStarter starter = prepareStarter(0);
        doReturn(privilegedUid).when(mMockPackageManager).getPackageUid(
                eq(privilegedPackage), anyLong(), anyInt());
        doReturn(maliciousUid).when(mMockPackageManager).getPackageUid(
                eq(maliciousPackage), anyLong(), anyInt());
        doReturn(false).when(mMockPackageManager).isSameApp(eq(privilegedPackage),
                eq(maliciousUid), anyInt());
        starter.setCallingPackage(maliciousPackage);
        starter.setCallingUid(maliciousUid);

        final Intent p2Intent = new Intent();
        p2Intent.setComponent(new ComponentName(privilegedPackage, "P2Activity"));
        p2Intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);

        final ActivityInfo p2ActivityInfo = new ActivityInfo();
        p2ActivityInfo.applicationInfo = new ApplicationInfo();
        p2ActivityInfo.applicationInfo.packageName = privilegedPackage;
        p2ActivityInfo.applicationInfo.uid = privilegedUid;
        p2ActivityInfo.name = "P2Activity";

        final ActivityRecord[] outActivity = new ActivityRecord[1];

        // The request simulates M2 starting P2
        starter.setIntent(p2Intent)
                .setActivityInfo(p2ActivityInfo)
                .setResultTo(m2.token) // sourceRecord is m2
                .setRequestCode(-1) // for startActivity()
                .setOutActivity(outActivity)
                .execute();

        final ActivityRecord p2 = outActivity[0];

        assertNotNull(p2);
        assertEquals("launchedFromPackage should be the immediate caller",
                maliciousPackage, p2.launchedFromPackage);
        assertEquals("launchedFromUid should be the immediate caller",
                maliciousUid, p2.launchedFromUid);
    }

    /**
     * Verifies that when an activity launch from a package with {@code cantSaveState="true"}
     * conflicts with an existing heavy-weight process, {@link ActivityStarter} preserves the
     * original app calling UID and PID in {@link ActivityStarter.Request} when rerouting the
     * intent to {@link HeavyWeightSwitcherActivity}. Also verifies that {@code
     * ignoreTargetSecurity} is set to permit starting the non-exported system activity while
     * preventing untrusted background apps from bypassing BAL restrictions.
     */
    @Test
    public void testHeavyWeightSwitcher_preservesCallingUid_setsFlag() {
        mAtm.mHasHeavyWeightFeature = true;
        final int heavyUid = 10001;
        final int attackerUid = 10002;
        final int attackerPid = 12346;
        final String heavyPackage = "com.test.heavy";
        final String attackerPackage = "com.test.attacker";

        // Create an active heavy-weight process.
        final WindowProcessController heavyWpc = mSystemServicesTestRule.addProcess(heavyPackage,
                heavyPackage, 12345 /* pid */, heavyUid);
        mAtm.mHeavyWeightProcess = heavyWpc;

        // Mock PendingIntentController to prevent NPE when wrapping target in IntentSender.
        mAtm.mPendingIntentController = mock(PendingIntentController.class);
        doReturn(mock(PendingIntentRecord.class)).when(mAtm.mPendingIntentController)
                .getIntentSender(anyInt(), any(), any(), anyInt(), anyInt(), any(), any(),
                        anyInt(), any(), any(), anyInt(), any());

        // Configure starter for an activity in a different package with cantSaveState.
        final ActivityStarter starter = prepareStarter(0 /* launchFlags */);
        starter.setCallingUid(attackerUid);
        starter.setCallingPid(attackerPid);
        starter.setCallingPackage(attackerPackage);
        starter.setRealCallingUid(attackerUid);
        starter.setRealCallingPid(attackerPid);

        final ActivityInfo info = starter.mRequest.activityInfo;
        info.packageName = attackerPackage;
        info.processName = attackerPackage;
        info.applicationInfo.packageName = attackerPackage;
        info.applicationInfo.uid = attackerUid;
        info.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE;
        info.name = "com.test.attacker.TargetActivity";

        // Mock resolution and ActivityManagerInternal so HeavyWeightSwitcherActivity resolves.
        final ResolveInfo switcherResolveInfo = new ResolveInfo();
        final ActivityInfo switcherActivityInfo = new ActivityInfo();
        switcherActivityInfo.name = HeavyWeightSwitcherActivity.class.getName();
        switcherActivityInfo.packageName = "android";
        switcherActivityInfo.applicationInfo = new ApplicationInfo();
        switcherActivityInfo.applicationInfo.packageName = "android";
        switcherActivityInfo.applicationInfo.uid = SYSTEM_UID;
        switcherResolveInfo.activityInfo = switcherActivityInfo;
        doReturn(switcherResolveInfo).when(mSupervisor).resolveIntent(any(), any(), anyInt(),
                anyInt(), anyInt(), anyInt());
        mAtm.mAmInternal = mock(ActivityManagerInternal.class);
        doAnswer(invocation -> invocation.getArgument(0))
                .when(mAtm.mAmInternal).getActivityInfoForUser(any(), anyInt());

        final int result = starter.execute();

        assertEquals(START_SUCCESS, result);
        assertTrue(starter.mRequest.ignoreTargetSecurity);
        assertEquals(attackerUid, starter.mRequest.callingUid);
        assertEquals(attackerPid, starter.mRequest.callingPid);
        assertEquals(HeavyWeightSwitcherActivity.class.getName(),
                starter.mRequest.intent.getComponent().getClassName());
    }

    /**
     * Verifies that when {@link ActivityStarter.Request#callingUid} is preserved for a
     * heavy-weight switcher launch, if BAL evaluation blocks the background caller,
     * {@link ActivityStarter} aborts the launch instead of allowing the non-exported activity
     * to open.
     */
    @Test
    public void testHeavyWeightSwitcher_blockedByBal_whenCallerInBackground() {
        mAtm.mHasHeavyWeightFeature = true;
        final int heavyUid = 10001;
        final int attackerUid = 10002;
        final int attackerPid = 12346;
        final String heavyPackage = "com.test.heavy";
        final String attackerPackage = "com.test.attacker";

        // Create an active heavy-weight process.
        final WindowProcessController heavyWpc = mSystemServicesTestRule.addProcess(heavyPackage,
                heavyPackage, 12345 /* pid */, heavyUid);
        mAtm.mHeavyWeightProcess = heavyWpc;

        // Mock PendingIntentController to prevent NPE when wrapping target in IntentSender.
        mAtm.mPendingIntentController = mock(PendingIntentController.class);
        doReturn(mock(PendingIntentRecord.class)).when(mAtm.mPendingIntentController)
                .getIntentSender(anyInt(), any(), any(), anyInt(), anyInt(), any(), any(),
                        anyInt(), any(), any(), anyInt(), any());

        doReturn(false).when(mAtm).isBackgroundActivityStartsEnabled();
        final BackgroundActivityStartController balController =
                mock(BackgroundActivityStartController.class);
        doReturn(balController).when(mSupervisor).getBackgroundActivityLaunchController();
        doReturn(BalVerdict.BLOCK).when(balController).checkBackgroundActivityStart(
                anyInt(), anyInt(), any(), anyInt(), anyInt(), any(), any(), anyBoolean(),
                any(), any(), any());

        // Configure starter for an activity in a different package with cantSaveState.
        final ActivityStarter starter = prepareStarter(0 /* launchFlags */);
        starter.setCallingUid(attackerUid);
        starter.setCallingPid(attackerPid);
        starter.setCallingPackage(attackerPackage);
        starter.setRealCallingUid(attackerUid);
        starter.setRealCallingPid(attackerPid);

        final ActivityInfo info = starter.mRequest.activityInfo;
        info.packageName = attackerPackage;
        info.processName = attackerPackage;
        info.applicationInfo.packageName = attackerPackage;
        info.applicationInfo.uid = attackerUid;
        info.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE;
        info.name = "com.test.attacker.TargetActivity";

        // Mock resolution and ActivityManagerInternal so HeavyWeightSwitcherActivity resolves.
        final ResolveInfo switcherResolveInfo = new ResolveInfo();
        final ActivityInfo switcherActivityInfo = new ActivityInfo();
        switcherActivityInfo.name = HeavyWeightSwitcherActivity.class.getName();
        switcherActivityInfo.packageName = "android";
        switcherActivityInfo.applicationInfo = new ApplicationInfo();
        switcherActivityInfo.applicationInfo.packageName = "android";
        switcherActivityInfo.applicationInfo.uid = SYSTEM_UID;
        switcherResolveInfo.activityInfo = switcherActivityInfo;
        doReturn(switcherResolveInfo).when(mSupervisor).resolveIntent(any(), any(), anyInt(),
                anyInt(), anyInt(), anyInt());
        mAtm.mAmInternal = mock(ActivityManagerInternal.class);
        doAnswer(invocation -> invocation.getArgument(0))
                .when(mAtm.mAmInternal).getActivityInfoForUser(any(), anyInt());

        final int result = starter.execute();

        assertEquals(START_ABORTED, result);
    }

    private ActivityRecord createBubbledActivity() {
        final ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setTaskAlwaysOnTop(true);
        opts.setLaunchedFromBubble(true);
        opts.setLaunchBounds(new Rect(10, 10, 100, 100));
        return new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setComponent(ActivityBuilder.getDefaultComponent())
                .setActivityOptions(opts)
                .build();
    }

    private static void startActivityInner(ActivityStarter starter, ActivityRecord target,
            ActivityRecord source, ActivityOptions options, Task inTask,
            TaskFragment inTaskFragment) {
        starter.startActivityInner(target, source, null /* voiceSession */,
                null /* voiceInteractor */, 0 /* startFlags */,
                options, inTask, inTaskFragment,
                BalVerdict.ALLOW_PRIVILEGED,
                null /* intentGrants */, -1 /* realCallingUid */);
    }
}
