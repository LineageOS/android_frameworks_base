/*
 * Copyright 2015-2016 Preetam J. D'Souza
 * Copyright 2016 The Maru OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.mperspective;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.mperspective.IPerspectiveService;
import android.mperspective.IPerspectiveServiceCallback;
import android.mperspective.Perspective;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.R;
import com.android.server.FgThread;
import com.android.server.SystemService;




/**
 * PerspectiveService is the central entity in perspective management.
 *
 * Instead of the standard industry practice of strong-coupling a specific
 * interface to hardware, multiple perspectives can be managed (sometimes in parallel)
 * to better make use of context.
 *
 * Currently, Maru defines a mobile and desktop perspective that can be
 * run in parallel depending on the computing context of the user.
 *
 * Concurrency is handled by locking global state. All changes to state and
 * subsequent side effects (e.g. dispatching lifecycle events to listeners or updating UI)
 * are run under a single locked transaction.
 *
 * Note that the actual perspective lifecycle actions are carried out via
 * JNI calls to a native perspectived daemon.
 */
public class PerspectiveService extends IPerspectiveService.Stub {

    private static final String TAG = "PerspectiveService";

    public static class Lifecycle extends SystemService {
        private PerspectiveService mPerspectiveService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mPerspectiveService = new PerspectiveService(getContext());
            publishBinderService(Context.PERSPECTIVE_SERVICE, mPerspectiveService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mPerspectiveService.systemReady();
            }
        }
    }

    private Context mContext;
    private DisplayManager mDisplayManager;
    private NotificationManager mNotificationManager;

    // wrapper to sp<IPerspectiveService>
    private long mNativeClient;

    // global state lock
    private final Object mLock = new Object();

    private final SparseArray<CallbackWrapper> mCallbacks;

    private int mDesktopState;

    private boolean mHDMIAutoStart = true;
    private final MDisplayListener mDisplayListener;

    private final int mDesktopNotificationId = R.string.desktop_notification_msg;
    private boolean mShowingDesktopNotification = false;

    private final PerspectiveHandler mHandler;
    private static final int MSG_START_DESKTOP = 0;
    private static final int MSG_STOP_DESKTOP = 1;
    private static final int MSG_UPDATE_DESKTOP_STATE = 2;

    public PerspectiveService(Context context) {
        mContext = context;
        mDisplayListener = new MDisplayListener();
        mCallbacks = new SparseArray<CallbackWrapper>();
        mHandler = new PerspectiveHandler(FgThread.get().getLooper());
    }

    private void systemReady() {
        mDisplayManager = (DisplayManager) mContext
                .getSystemService(Context.DISPLAY_SERVICE);
        mDisplayManager.registerDisplayListener(mDisplayListener, FgThread.getHandler());

        mNotificationManager =(NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        mNativeClient = nativeCreateClient();
    }

    private void scheduleStartDesktopPerspective() {
        mHandler.sendEmptyMessage(MSG_START_DESKTOP);
    }

    private void scheduleStopDesktopPerspective() {
        mHandler.sendEmptyMessage(MSG_STOP_DESKTOP);
    }

    private void scheduleUpdateDesktopState() {
        mHandler.sendEmptyMessage(MSG_UPDATE_DESKTOP_STATE);
    }

    private void updateDesktopStateLocked(int state) {
        Log.d(TAG, "mDesktopState: " + Perspective.stateToString(mDesktopState)
                + " -> " + Perspective.stateToString(state));
        if (mDesktopState != state) {
            mDesktopState = state;
            updateDesktopNotificationLocked();
            dispatchEventLocked(state);
        }
    }

    private void updateDesktopState() {
        synchronized (mLock) {
            boolean isRunning = nativeIsRunning(mNativeClient);
            updateDesktopStateLocked(isRunning ? Perspective.STATE_RUNNING
                    : Perspective.STATE_STOPPED);
        }
    }

    private boolean isDesktopRunningInternal() {
        synchronized (mLock) {
            boolean isRunning = nativeIsRunning(mNativeClient);
            int state = isRunning ? Perspective.STATE_RUNNING
                    : Perspective.STATE_STOPPED;
            if (mDesktopState != state) {
                /*
                 * Woops, there is a discrepancy: the desktop changed state
                 * under our feet for some reason. The good thing is that this
                 * should be a rare case.
                 *
                 * This could be fixed by polling nativeIsRunning but that seems
                 * wasteful so we'll just wait for a client to force us to check.
                 *
                 * Schedule an update of our state on the system thread that will
                 * sync up our state (provided there aren't any other pending lifecycle
                 * tasks scheduled before us that sync us up before it even runs).
                 */
                Log.w(TAG, "Yikes, desktop unexpectedly changed state to "
                        + Perspective.stateToString(state)
                        + " (expected " + Perspective.stateToString(mDesktopState) + ")");
                scheduleUpdateDesktopState();
            }
            return isRunning;
        }
    }

    private void registerCallbackInternal(final IPerspectiveServiceCallback callback,
                                          final int pid) {
        synchronized (mLock) {
            if (mCallbacks.get(pid) != null) {
                Log.w(TAG, "pid " + pid + " already has a registered callback, will be updated");
            }

            CallbackWrapper callbackWrapper = new CallbackWrapper(callback, pid);
            try {
                IBinder binder = callback.asBinder();
                binder.linkToDeath(callbackWrapper, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to register callback for pid " + pid, e);
                return;
            }
            mCallbacks.put(pid, new CallbackWrapper(callback, pid));
        }
    }

    private void dispatchEventLocked(int event) {
        for (int i = 0; i < mCallbacks.size(); ++i) {
            mCallbacks.valueAt(i).dispatchEvent(event);
        }
    }

    private void onCallbackDied(CallbackWrapper callbackWrapper) {
        synchronized (mLock) {
            mCallbacks.remove(callbackWrapper.mPid);
        }
    }

    @Override // Binder call
    public void startDesktopPerspective() {
        scheduleStartDesktopPerspective();
    }

    @Override // Binder call
    public void stopDesktopPerspective() {
        scheduleStopDesktopPerspective();
    }

    @Override // Binder call
    public boolean isDesktopRunning() {
        return isDesktopRunningInternal();
    }

    @Override // Binder call
    public void registerCallback(IPerspectiveServiceCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }

        registerCallbackInternal(callback, Binder.getCallingPid());
    }

    private void updateDesktopNotificationLocked() {
        if (mDesktopState == Perspective.STATE_STOPPED) {
            if (mShowingDesktopNotification) {
                mNotificationManager.cancel(mDesktopNotificationId);
                mShowingDesktopNotification = false;
            }
        } else {
            int title = R.string.desktop_notification_title_running;
            if (mDesktopState == Perspective.STATE_STARTING) {
                title = R.string.desktop_notification_title_starting;
            } else if (mDesktopState == Perspective.STATE_STOPPING) {
                title = R.string.desktop_notification_title_stopping;
            }

            final int msg = R.string.desktop_notification_msg;
            final int color = R.color.system_notification_accent_color;
            final int icon = R.drawable.ic_mdesktop;

            final Intent intent = Intent.makeRestartActivityTask(
                    new ComponentName("com.android.settings",
                            "com.android.settings.Settings$MDesktopSettingsActivity"));
            final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(mContext, 0,
                    intent, 0, null, UserHandle.CURRENT);

            final Notification notification = new Notification.Builder(mContext)
                    .setContentTitle(mContext.getText(title))
                    .setContentText(mContext.getText(msg))
                    .setSmallIcon(icon)
                    .setContentIntent(pendingIntent)
                    .setShowWhen(false)
                    .setOngoing(true)
                    .setColor(mContext.getResources().getColor(color))
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build();

            mNotificationManager.notifyAsUser(null, mDesktopNotificationId, notification,
                    UserHandle.ALL);
            mShowingDesktopNotification = true;
        }
    }

    /**
     * Convenience wrapper for managing client callback death.
     */
    private final class CallbackWrapper implements DeathRecipient {
        private final IPerspectiveServiceCallback mCallback;
        private final int mPid;

        public CallbackWrapper(final IPerspectiveServiceCallback callback, final int pid) {
            mCallback = callback;
            mPid = pid;
        }

        @Override
        public void binderDied() {
            onCallbackDied(this);
        }

        public void dispatchEvent(int event) {
            try {
                mCallback.onPerspectiveEvent(event);
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to dispatch event to pid " + mPid, e);
                binderDied();
            }
        }
    }

    /**
     * Defer most work here for a few reasons:
     *  (1) Avoid blocking Binder clients for expensive tasks, particularly the UI thread
     *  (2) Avoid permission issues running stuff under Binder client context
     */
    private class PerspectiveHandler extends Handler 
    {

        public PerspectiveHandler(Looper looper) 
        {
            super(looper);
        }
 
        private void startDesktopPerspectiveInternal() 
        {

            synchronized (mLock) {
                if (mDesktopState == Perspective.STATE_STOPPED) {
                    updateDesktopStateLocked(Perspective.STATE_STARTING);

                    boolean res = nativeStart(mNativeClient);
                    if (res) {
                        updateDesktopStateLocked(Perspective.STATE_RUNNING);
                    } else {
                        // unlikely
                        Log.e(TAG, "nativeStart failed");
                        updateDesktopStateLocked(Perspective.STATE_STOPPED);
                    }
                }
            }
        }

        private void stopDesktopPerspectiveInternal() {
            synchronized (mLock) {
                if (mDesktopState == Perspective.STATE_RUNNING) {
                    updateDesktopStateLocked(Perspective.STATE_STOPPING);

                    boolean res = nativeStop(mNativeClient);
                    if (res) {
                        updateDesktopStateLocked(Perspective.STATE_STOPPED);
                    } else {
                        // unlikely
                        Log.e(TAG, "nativeStop failed");
                        updateDesktopStateLocked(Perspective.STATE_RUNNING);
                    }
                }
            }
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_DESKTOP:
                    startDesktopPerspectiveInternal();
                    break;
                case MSG_STOP_DESKTOP:
                    stopDesktopPerspectiveInternal();
                    break;
                case MSG_UPDATE_DESKTOP_STATE:
                    updateDesktopState();
                    break;
            }
        }
    }

    private class MDisplayListener implements DisplayManager.DisplayListener {
        // track the hdmi display id to check if it has been removed later
        private int mHdmiDisplayId = -1;

        @Override
        public void onDisplayAdded(int displayId) 
        {

            Display display = mDisplayManager.getDisplay(displayId);
            
            final boolean hdmiDisplayAdded = display.getType() == Display.TYPE_HDMI;

            if (hdmiDisplayAdded) 
            {
                if (mHdmiDisplayId == -1) 
                {
                    mHdmiDisplayId = displayId;
                    if (mHDMIAutoStart) 
                    {
                        Log.i(TAG, "HDMI display added, scheduling desktop start...");
                        scheduleStartDesktopPerspective();
                    }
                }
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (displayId == mHdmiDisplayId) {
                if (mHdmiDisplayId != -1) {
                    mHdmiDisplayId = -1;
                }
            }
        }

        @Override
        public void onDisplayChanged(int displayId) { /* no-op */ }
    }

    private static native long nativeCreateClient();
    private static native boolean nativeStart(long ptr);
    private static native boolean nativeStop(long ptr);
    private static native boolean nativeIsRunning(long ptr);

}
