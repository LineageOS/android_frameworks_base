/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.Manifest.permission.REPOSITION_SELF_WINDOWS;
import static android.app.TaskInfo.SELF_MOVABLE_ALLOWED;
import static android.app.TaskInfo.SELF_MOVABLE_DEFAULT;
import static android.app.TaskMoveRequestHandler.REMOTE_CALLBACK_BOUNDS_KEY;
import static android.app.TaskMoveRequestHandler.REMOTE_CALLBACK_DISPLAY_ID_KEY;
import static android.app.TaskMoveRequestHandler.REMOTE_CALLBACK_RESULT_KEY;
import static android.app.TaskMoveRequestHandler.RESULT_APPROVED;
import static android.app.TaskMoveRequestHandler.RESULT_FAILED_BAD_BOUNDS;
import static android.app.TaskMoveRequestHandler.RESULT_FAILED_BAD_STATE;
import static android.app.TaskMoveRequestHandler.RESULT_FAILED_IMMOVABLE_TASK;
import static android.app.TaskMoveRequestHandler.RESULT_FAILED_NONEXISTENT_DISPLAY;
import static android.app.TaskMoveRequestHandler.RESULT_FAILED_NO_PERMISSIONS;
import static android.app.TaskMoveRequestHandler.RESULT_FAILED_UNABLE_TO_PLACE_TASK;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.TRANSIT_CHANGE;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ACTIVITY_STARTS;
import static com.android.server.wm.ActivityTaskSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.BackgroundActivityStartController.BalVerdict;
import static com.android.server.wm.RootWindowContainer.MATCH_ATTACHED_TASK_OR_RECENT_TASKS;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.IAppTask;
import android.app.IApplicationThread;
import android.app.TaskMoveRequestHandler;
import android.app.TaskWindowingLayerRequestHandler;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import java.util.Objects;

/**
 * An implementation of IAppTask, that allows an app to manage its own tasks via
 * {@link android.app.ActivityManager.AppTask}.  We keep track of the callingUid to ensure that
 * only the process that calls getAppTasks() can call the AppTask methods.
 */
class AppTaskImpl extends IAppTask.Stub {
    private static final String TAG = "AppTaskImpl";
    private final ActivityTaskManagerService mService;

    private final int mTaskId;
    private final int mCallingUid;

    public AppTaskImpl(ActivityTaskManagerService service, int taskId, int callingUid) {
        mService = service;
        mTaskId = taskId;
        mCallingUid = callingUid;
    }

    private void checkCallerOrSystemOrRoot() {
        if (mCallingUid != Binder.getCallingUid() && Process.SYSTEM_UID != Binder.getCallingUid()
                && Process.ROOT_UID != Binder.getCallingUid()) {
            throw new SecurityException("Caller " + mCallingUid
                    + " does not match caller of getAppTasks(): " + Binder.getCallingUid());
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            throw ActivityTaskManagerService.logAndRethrowRuntimeExceptionOnTransact(TAG, e);
        }
    }

    @Override
    public void finishAndRemoveTask() {
        checkCallerOrSystemOrRoot();

        synchronized (mService.mGlobalLock) {
            int origCallingUid = Binder.getCallingUid();
            int origCallingPid = Binder.getCallingPid();
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                // We remove the task from recents to preserve backwards
                if (!mService.mTaskSupervisor.removeTaskById(mTaskId, false,
                        REMOVE_FROM_RECENTS, "finish-and-remove-task", origCallingUid,
                        origCallingPid)) {
                    throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
                }
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }
    }

    @Override
    public ActivityManager.RecentTaskInfo getTaskInfo() {
        checkCallerOrSystemOrRoot();

        synchronized (mService.mGlobalLock) {
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            try {
                Task task = mService.mRootWindowContainer.anyTaskForId(mTaskId,
                        MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
                if (task == null) {
                    throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
                }
                final boolean getTasksAllowed = mService.isGetTasksAllowed(
                        "getTaskInfo", callingPid, callingUid);
                return mService.getRecentTasks().createRecentTaskInfo(task,
                        false /* stripExtras */, getTasksAllowed);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void moveToFront(IApplicationThread appThread, String callingPackage) {
        checkCallerOrSystemOrRoot();
        // Will bring task to front if it already has a root activity.
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        mService.assertPackageMatchesCallingUid(callingPackage);
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mService.mGlobalLock) {
                WindowProcessController callerApp = null;
                if (appThread != null) {
                    callerApp = mService.getProcessController(appThread);
                }
                final BackgroundActivityStartController balController =
                        mService.mTaskSupervisor.getBackgroundActivityLaunchController();
                BalVerdict balVerdict = balController.checkBackgroundActivityStart(
                        callingUid,
                        callingPid,
                        callingPackage,
                        -1,
                        -1,
                        callerApp,
                        null,
                        false,
                        null,
                        null,
                        null);
                if (balVerdict.blocks() && !mService.isBackgroundActivityStartsEnabled()) {
                    Slog.w(TAG, "moveTaskToFront blocked: : " + balVerdict);
                    return;
                }
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(TAG, "moveTaskToFront allowed: " + balVerdict);
                }
            }
            mService.mTaskSupervisor.startActivityFromRecents(callingPid, callingUid, mTaskId,
                    null /* options */);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * This method uses different Shell Transitions machinery than
     * {@link ActivityTaskManagerService#resizeTask}. We want to give the Shell some freedom to
     * adjust the target bounds as the WM Core may not know about some limitations of how the tasks
     * are presented by the WM Shell.
     */
    @Override
    public void moveTaskTo(int displayId, Rect bounds, IRemoteCallback callback) {
        checkCallerOrSystemOrRoot();
        final int origCallingPid = Binder.getCallingPid();
        final int origCallingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            if (mService.checkPermission(REPOSITION_SELF_WINDOWS, origCallingPid, origCallingUid)
                    != PERMISSION_GRANTED) {
                reportTaskMoveRequestResult(
                        RESULT_FAILED_NO_PERMISSIONS, INVALID_DISPLAY, null /* bounds */, callback);
                return;
            }
            synchronized (mService.mGlobalLock) {
                final Task task = mService.mRootWindowContainer.anyTaskForId(mTaskId);
                if (task == null) {
                    reportTaskMoveRequestResult(
                            RESULT_FAILED_BAD_STATE, INVALID_DISPLAY, null /* bounds */, callback);
                    return;
                }

                if (displayId == INVALID_DISPLAY) {
                    displayId = task.getDisplayId();
                }

                final int result = validateTaskMoveRequest(displayId, bounds, task,
                        origCallingPid, origCallingUid);
                if (result != RESULT_APPROVED) {
                    reportTaskMoveRequestResult(
                            result, INVALID_DISPLAY, null /* bounds */, callback);
                    return;
                }

                final TransitionController controller = mService.getTransitionController();
                final Transition transition = new Transition(
                        TRANSIT_CHANGE, 0, controller, mService.mWindowManager.mSyncEngine);
                transition.setRequestedLocation(displayId, bounds);
                transition.addTransactionPresentedListener(() ->
                        reportTaskMoveRequestResult(
                            result, task.getDisplayId(), task.getBounds(), callback));
                controller.startCollectOrQueue(transition,
                        (deferred) -> {
                            if (deferred) {
                                int lateResult = validateTaskMoveRequest(
                                        transition.getRequestedLocation().getDisplayId(),
                                        transition.getRequestedLocation().getBounds(),
                                        task,
                                        origCallingPid,
                                        origCallingUid);
                                if (lateResult != RESULT_APPROVED) {
                                    reportTaskMoveRequestResult(
                                            lateResult,
                                            INVALID_DISPLAY,
                                            null /* bounds */,
                                            callback);
                                    transition.abort();
                                    return;
                                }
                            }
                            controller.requestStartTransition(transition, task, null, null);
                            transition.setReady(task, true);
                        });
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Reports execution result of a {@link #moveTaskTo} request using the callback provided.
     *
     * @param result The result code.
     * @param displayId The final display ID of the moved task after request execution.
     * @param bounds The final bounds on host display of the moved task after request execution.
     * @param callback The callback to notify about request result.
     */
    private void reportTaskMoveRequestResult(
            int result, int displayId, Rect bounds, IRemoteCallback callback) {
        if (callback == null) {
            return;
        }
        final Bundle res = new Bundle();
        res.putInt(REMOTE_CALLBACK_RESULT_KEY, result);
        if (result == RESULT_APPROVED) {
            res.putInt(REMOTE_CALLBACK_DISPLAY_ID_KEY, displayId);
            res.putParcelable(REMOTE_CALLBACK_BOUNDS_KEY, bounds);
        }

        try {
            callback.sendResult(res);
        } catch (RemoteException e) {
            // Client throwed an exception back to the server, ignoring it.
        }
    }

    @TaskMoveRequestHandler.RequestResult
    private int validateTaskMoveRequest(
            int displayId,
            Rect bounds,
            @NonNull Task task,
            int origCallingPid,
            int origCallingUid) {
        final DisplayContent targetDisplay =
                mService.mRootWindowContainer.getDisplayContent(displayId);
        if (targetDisplay == null) {
            return RESULT_FAILED_NONEXISTENT_DISPLAY;
        }

        if (!mService.mTaskSupervisor.canPlaceEntityOnDisplay(
                displayId, origCallingPid, origCallingUid, task)) {
            return RESULT_FAILED_UNABLE_TO_PLACE_TASK;
        }

        final Rect maximalBounds = targetDisplay.getBounds();
        if (!maximalBounds.contains(bounds)) {
            return RESULT_FAILED_BAD_BOUNDS;
        }

        final float minimalWindowSizeDp = (float) targetDisplay.mMinSizeOfResizeableTaskDp;
        final float targetDisplayDensity = targetDisplay.getDisplayMetrics().density;
        if (bounds.width() < Math.ceil(minimalWindowSizeDp * targetDisplayDensity)
                || bounds.height() < Math.ceil(minimalWindowSizeDp * targetDisplayDensity)) {
            return RESULT_FAILED_BAD_BOUNDS;
        }

        final int taskMovableState = task.getSelfMovable();
        final boolean isTaskMovable = (taskMovableState == SELF_MOVABLE_ALLOWED
                || (taskMovableState == SELF_MOVABLE_DEFAULT
                    && task.getWindowingMode() == WINDOWING_MODE_FREEFORM));
        if (!isTaskMovable) {
            return RESULT_FAILED_IMMOVABLE_TASK;
        }

        if (!mService.getTransitionController().isShellTransitionsEnabled()) {
            return RESULT_FAILED_BAD_STATE;
        }

        return RESULT_APPROVED;
    }

    @Override
    public int startActivity(IBinder whoThread, String callingPackage, String callingFeatureId,
            Intent intent, String resolvedType, Bundle bOptions) {
        checkCallerOrSystemOrRoot();
        mService.assertPackageMatchesCallingUid(callingPackage);
        mService.mAmInternal.addCreatorToken(intent, callingPackage);

        int callingUser = UserHandle.getCallingUserId();
        Task task;
        IApplicationThread appThread;
        synchronized (mService.mGlobalLock) {
            task = mService.mRootWindowContainer.anyTaskForId(mTaskId,
                    MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
            if (task == null) {
                throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
            }
            appThread = IApplicationThread.Stub.asInterface(whoThread);
            if (appThread == null) {
                throw new IllegalArgumentException("Bad app thread " + appThread);
            }
        }
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        return mService.getActivityStartController().obtainStarter(intent, "AppTaskImpl")
                .setCaller(appThread)
                .setCallingPackage(callingPackage)
                .setCallingFeatureId(callingFeatureId)
                .setResolvedType(resolvedType)
                .setActivityOptions(bOptions, callingPid, callingUid)
                .setUserId(callingUser)
                .setInTask(task)
                .execute();
    }

    @Override
    public void setExcludeFromRecents(boolean exclude) {
        checkCallerOrSystemOrRoot();

        synchronized (mService.mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                Task task = mService.mRootWindowContainer.anyTaskForId(mTaskId,
                        MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
                if (task == null) {
                    throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
                }
                Intent intent = task.getBaseIntent();
                if (exclude) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                } else {
                    intent.setFlags(intent.getFlags()
                            & ~Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void requestWindowingLayer(int layer, IRemoteCallback callback) {
        checkCallerOrSystemOrRoot();
        Objects.requireNonNull(callback, "The callback provided is null.");

        final Bundle result = new Bundle();
        // TODO(b/443206707): implement requesting a transition
        // currently, just return failed due to bad state
        result.putInt(TaskWindowingLayerRequestHandler.REMOTE_CALLBACK_RESULT_KEY,
                TaskWindowingLayerRequestHandler.RESULT_FAILED_BAD_STATE);
        try {
            callback.sendResult(result);
        } catch (RemoteException e) {
            // Client thrown an exception back to the server, ignoring it.
        }
    }
}
