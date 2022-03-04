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

package android.app;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ApplicationErrorReport;
import android.app.ContentProviderHolder;
import android.app.GrantedUriPermission;
import android.app.HandoffActivityData;
import android.app.IApplicationThread;
import android.app.IActivityClientController;
import android.app.IActivityController;
import android.app.IAssistDataReceiver;
import android.app.IInstrumentationWatcher;
import android.app.IProcessObserver;
import android.app.IScreenCaptureObserver;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.ITaskStackListener;
import android.app.IUiAutomationConnection;
import android.app.IUidObserver;
import android.app.IUserSwitchObserver;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.PictureInPictureUiState;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProviderInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
import android.os.StrictMode;
import android.os.WorkSource;
import android.service.voice.IVoiceInteractionSession;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationAdapter;
import android.window.IWindowOrganizerController;
import android.window.ITaskSnapshotManager;
import android.window.BackAnimationAdapter;
import android.window.BackNavigationInfo;
import android.window.SplashScreenView;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.os.IResultReceiver;

import java.util.List;

/**
 * System private API for talking with the activity task manager that handles how activities are
 * managed on screen.
 *
 * @hide
 */
// TODO(b/174040395): Make this interface private to ActivityTaskManager.java and have external
// caller go through that call instead. This would help us better separate and control the API
// surface exposed.
// TODO(b/174041603): Create a builder interface for things like startActivityXXX(...) to reduce
// interface duplication.
interface IActivityTaskManager {
    int startActivity(in IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, in Intent intent, in String resolvedType,
            in IBinder resultTo, in String resultWho, int requestCode,
            int flags, in ProfilerInfo profilerInfo, in Bundle options);
    int startActivities(in IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, in Intent[] intents, in String[] resolvedTypes,
            in IBinder resultTo, in Bundle options, int userId);
    int startActivityAsUser(in IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, in Intent intent, in String resolvedType,
            in IBinder resultTo, in String resultWho, int requestCode, int flags,
            in ProfilerInfo profilerInfo, in Bundle options, int userId);
    boolean startNextMatchingActivity(in IBinder callingActivity,
            in Intent intent, in Bundle options);
    int startActivityIntentSender(in IApplicationThread caller,
            in IIntentSender target, in IBinder whitelistToken, in Intent fillInIntent,
            in String resolvedType, in IBinder resultTo, in String resultWho, int requestCode,
            int flagsMask, int flagsValues, in Bundle options);
    WaitResult startActivityAndWait(in IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, in Intent intent, in String resolvedType,
            in IBinder resultTo, in String resultWho, int requestCode, int flags,
            in ProfilerInfo profilerInfo, in Bundle options, int userId);
    int startVoiceActivity(in String callingPackage, in String callingFeatureId, int callingPid,
            int callingUid, in Intent intent, in String resolvedType,
            in IVoiceInteractionSession session, in IVoiceInteractor interactor, int flags,
            in ProfilerInfo profilerInfo, in Bundle options, int userId);
    String getVoiceInteractorPackageName(in IBinder callingVoiceInteractor);
    int startAssistantActivity(in String callingPackage, in String callingFeatureId, int callingPid,
            int callingUid, in Intent intent, in String resolvedType, in Bundle options, int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_ACTIVITY)")
    int startActivityFromGameSession(IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, int callingPid, int callingUid, in Intent intent,
            int taskId, int userId);
    int startActivityFromRecents(int taskId, in Bundle options);
    int startActivityAsCaller(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int flags, in ProfilerInfo profilerInfo, in Bundle options,
            boolean ignoreTargetSecurity, int userId);
    void preloadRecentsActivity(in Intent intent);

    boolean isActivityStartAllowedOnDisplay(int displayId, in Intent intent, in String resolvedType,
            int userId);

    void unhandledBack();

    /** Returns an interface to control the activity related operations. */
    IActivityClientController getActivityClientController();

    int getFrontActivityScreenCompatMode();
    void setFrontActivityScreenCompatMode(int mode);
    void setFocusedTask(int taskId);
    boolean setTaskIsPerceptible(int taskId, boolean isPerceptible);
    boolean removeTask(int taskId);
    void removeAllVisibleRecentTasks();
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum, boolean filterOnlyVisibleRecents,
            boolean keepIntentExtra, int displayId);
    void moveTaskToFront(in IApplicationThread app, in String callingPackage, int task,
            int flags, in Bundle options);
    ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags,
            int userId);
    boolean isTopActivityImmersive();
    void reportAssistContextExtras(in IBinder assistToken, in Bundle extras,
            in AssistStructure structure, in AssistContent content, in Uri referrer);

    /**
     * @return whether the app could be universal resizeable (assuming it's on a large screen and
     * ignoring possible overrides)
     */
    boolean canBeUniversalResizeable(in ApplicationInfo appInfo);

    void setFocusedRootTask(int taskId);
    ActivityTaskManager.RootTaskInfo getFocusedRootTaskInfo();
    Rect getTaskBounds(int taskId);

    /** Focuses the top task on a display if it isn't already focused. Used for Recents. */
    void focusTopTask(int displayId);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.UPDATE_LOCK_TASK_PACKAGES)")
    void updateLockTaskPackages(int userId, in String[] packages);
    boolean isInLockTaskMode();
    int getLockTaskModeState();
    List<IBinder> getAppTasks(in String callingPackage);
    void startSystemLockTaskMode(int taskId);
    void stopSystemLockTaskMode();
    void rebuildSystemLockTaskPinnedMode();
    void finishVoiceTask(in IVoiceInteractionSession session);
    int addAppTask(in IBinder activityToken, in Intent intent,
            in ActivityManager.TaskDescription description, in Bitmap thumbnail);
    Point getAppTaskThumbnailSize();

    oneway void releaseSomeActivities(in IApplicationThread app);
    Bitmap getTaskDescriptionIcon(in String filename, int userId);
    void registerTaskStackListener(in ITaskStackListener listener);
    void unregisterTaskStackListener(in ITaskStackListener listener);
    void setTaskResizeable(int taskId, int resizeableMode);

    /**
     * Resize the task with given bounds
     *
     * @param taskId The id of the task to set the bounds for.
     * @param bounds The new bounds.
     * @param resizeMode Resize mode defined as {@code ActivityTaskManager#RESIZE_MODE_*} constants.
     */
    void resizeTask(int taskId, in Rect bounds, int resizeMode);
    void moveRootTaskToDisplay(int taskId, int displayId);

    void moveTaskToRootTask(int taskId, int rootTaskId, boolean toTop);

    /**
     * Removes root tasks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeRootTasksInWindowingModes(in int[] windowingModes);
    /** Removes root tasks of the activity types from the system. */
    void removeRootTasksWithActivityTypes(in int[] activityTypes);

    List<ActivityTaskManager.RootTaskInfo> getAllRootTaskInfos();
    ActivityTaskManager.RootTaskInfo getRootTaskInfo(int windowingMode, int activityType);
    List<ActivityTaskManager.RootTaskInfo> getAllRootTaskInfosOnDisplay(int displayId);
    ActivityTaskManager.RootTaskInfo getRootTaskInfoOnDisplay(int windowingMode, int activityType, int displayId);

    /**
     * Informs ActivityTaskManagerService that the keyguard is showing.
     *
     * @param showingKeyguard True if the keyguard is showing, false otherwise.
     * @param showingAod True if AOD is showing, false otherwise.
     */
    void setLockScreenShown(boolean showingKeyguard, boolean showingAod);
    Bundle getAssistContextExtras(int requestType);
    boolean requestAssistContextExtras(int requestType, in IAssistDataReceiver receiver,
            in Bundle receiverExtras, in IBinder activityToken,
            boolean focused, boolean newSessionId);
    boolean requestAutofillData(in IAssistDataReceiver receiver, in Bundle receiverExtras,
            in IBinder activityToken, int flags);
    boolean requestAssistDataForTask(in IAssistDataReceiver receiver, int taskId,
            in String callingPackageName, String callingAttributionTag, boolean fetchStructure);

    /**
     * Notify the system that the keyguard is going away.
     *
     * @param flags See
     *              {@link android.view.WindowManagerPolicyConstants#KEYGUARD_GOING_AWAY_FLAG_TO_SHADE}
     *              etc.
     */
     @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.CONTROL_KEYGUARD)")
    void keyguardGoingAway(int flags);

    void suppressResizeConfigChanges(boolean suppress);

    /** Returns an interface enabling the management of window organizers. */
    IWindowOrganizerController getWindowOrganizerController();

    /** Returns an interface enabling the access of task snapshots. */
    @EnforcePermission(allOf={"MANAGE_ACTIVITY_TASKS", "READ_FRAME_BUFFER"})
    ITaskSnapshotManager getTaskSnapshotManager();

    boolean supportsLocalVoiceInteraction();

    // Requests the "Open in browser" education to be shown
    void requestOpenInBrowserEducation(IBinder appToken);

    // Get device configuration
    ConfigurationInfo getDeviceConfigurationInfo();

    /** Cancels the window transitions for the given task. */
    void cancelTaskWindowTransition(int taskId);

    /**
     * Return the user id of last resumed activity.
     */
    int getLastResumedActivityUserId();

    /**
     * Return the uid of last resumed activity.
     */
    int getLastResumedActivityUid();

    /**
     * Updates global configuration and applies changes to the entire system.
     * @param values Update values for global configuration. If null is passed it will request the
     *               Window Manager to compute new config for the default display.
     * @throws RemoteException
     * @return Returns true if the configuration was updated.
     */
    boolean updateConfiguration(in Configuration values);
    void updateLockTaskFeatures(int userId, int flags);

    /**
     * Registers a remote animation to be run for all activity starts from a certain package during
     * a short predefined amount of time.
     */
    void registerRemoteAnimationForNextActivityStart(in String packageName,
            in RemoteAnimationAdapter adapter, in IBinder launchCookie);

    /**
     * Registers remote animations for a display.
     */
    void registerRemoteAnimationsForDisplay(int displayId, in RemoteAnimationDefinition definition);

    /** @see android.app.ActivityManager#alwaysShowUnsupportedCompileSdkWarning */
    void alwaysShowUnsupportedCompileSdkWarning(in ComponentName activity);

    void setVrThread(int tid);
    void setPersistentVrThread(int tid);
    void stopAppSwitches();
    void resumeAppSwitches();
    void setActivityController(in IActivityController watcher, boolean imAMonkey);
    void setVoiceKeepAwake(in IVoiceInteractionSession session, boolean keepAwake);

    int getPackageScreenCompatMode(in String packageName);
    void setPackageScreenCompatMode(in String packageName, int mode);
    boolean getPackageAskScreenCompat(in String packageName);
    void setPackageAskScreenCompat(in String packageName, boolean ask);

    /**
     * Clears launch params for given packages.
     */
    void clearLaunchParamsForPackages(in List<String> packageNames);

    /**
     * A splash screen view has copied.
     */
    void onSplashScreenViewCopyFinished(int taskId,
            in @nullable SplashScreenView.SplashScreenViewParcelable material);

    /**
     * When the Picture-in-picture state has changed.
     * @param pipState the {@link PictureInPictureUiState} is sent to current pip task if there is
     * any -or- the top most task (state like entering PiP does not require a pinned task).
     * @param displayId the id of the display in {@link PipDisplayLayoutState}.
     */
    void onPictureInPictureUiStateChanged(in PictureInPictureUiState pipState, in int displayId);

    /**
     * Re-attach navbar to the display during a recents transition.
     * TODO(188595497): Remove this once navbar attachment is in shell.
     */
    void detachNavigationBarFromApp(in IBinder transition);

    /**
     * Informs the transition system that the current transition is about to be animated by a
     * remote process rather than the calling process. Core must have already been informed of
     * the delegate process via RemoteTransition or WindowContainerTransaction.setAnimationDelegate.
     * This must be called from a process that is already a remote transition player or delegate.
     * Delegates are cleaned-up automatically at the end of the transition.
     * @param transition is the token representing the transition being animated.
     */
    void setRunningRemoteTransitionDelegate(in IBinder transition);

    /**
     * Simulate inject touch event to trigger potential focus change in the server.
     * Called when navigation bar is about to trigger back event but won't inject back key to input
     * manager.
     * @param displayId Id of the display the user just touched.
     * @return Return true if display order will change.
     */
    boolean simulateTouchDisplay(int displayId);

    /**
     * Prepare the back navigation in the server. This setups the leashed for sysui to animate
     * the back gesture and returns the data needed for the animation.
     * @param navigationObserver a remote callback to nofify shell when the focused window is gone,
                                 or an unexpected transition has happened on the navigation target.
     * @param adaptor a remote animation to be run for the back navigation plays the animation.
     * @return Returns the back navigation info.
     */
    android.window.BackNavigationInfo startBackNavigation(
            in RemoteCallback navigationObserver, in BackAnimationAdapter adaptor);

    /**
     * This setups the leashed for sysui to animate the current back gesture.
     * Only valid after startBackNavigation.
     * @return Returns whether system can prepare back animation.
     */
    boolean startPredictiveBackAnimation();

    /**
     * registers a callback to be invoked when a background activity launch is aborted.
     *
     * @param observer callback to be registered.
     * @return true if the callback was successfully registered, false otherwise.
     * @hide
     */
    boolean registerBackgroundActivityStartCallback(in IBinder binder);

    /**
     * unregisters a callback to be invoked when a background activity launch is aborted.
     *
     * @param observer callback to be registered.
     * @hide
     */
    void unregisterBackgroundActivityStartCallback(in IBinder binder);

    /**
     * registers a callback to be invoked when the screen is captured.
     *
     * @param observer callback to be registered.
     * @param activityToken The token for the activity to set the callback to.
     * @hide
     */
    void registerScreenCaptureObserver(IBinder activityToken, IScreenCaptureObserver observer);

    /**
     * unregisters the screen capture callback which was registered with
     * {@link #registerScreenCaptureObserver(ScreenCaptureObserver)}.
     *
     * @param observer callback to be unregistered.
     * @param activityToken The token for the activity to unset the callback from.
     * @hide
     */
    void unregisterScreenCaptureObserver(IBinder activityToken, IScreenCaptureObserver observer);

    /**
    * Move the task to the top/ bottom of the activity stack of specific display
    *
    * @param taskId Id of the task that has to be moved
    * @param displayId Id of the display to which it has to be moved
    * @param onTop defines the task has to be moved on top or bottom of the stack
    * @hide
    */
    void moveRootTaskToDisplayOnTopOrBottom(int taskId, int displayId, boolean onTop);

    /**
     * Reports HandoffActivityData for a given set of activities back to
     * ActivityTaskManager.
     *
     * @param requestToken A request token used by ActivityTaskManager to
     * identify the request.
     * @param data A list of HandoffActivityData objects containing the data for the requested
     * activities in the same order as the request.
     * @hide
     */
    void reportHandoffActivityData(in IBinder requestToken, in List<HandoffActivityData> data);

    /**
     * Retrieves the destination activity's package name associated
     * with an original launching activity.
     * This is used for Activity Trampolines, where an initial activity redirects to a final
     * destination activity.
     *
     * @param originalPackageName The package name of the first activity in the launch chain.
     * @return The package name of the final destination activity, or the provided
     * {@code originalPackageName} if no redirection is found.
     */
    String getDestinationPackage(in String originalPackageName);
}
