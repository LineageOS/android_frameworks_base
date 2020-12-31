/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.tv.ITvRemoteProvider;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;

/**
 * Maintains a connection to a tv remote provider service.
 */
final class TvRemoteProviderProxy implements ServiceConnection {
    private static final String TAG = "TvRemoteProviderProxy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.VERBOSE);


    // This should match TvRemoteProvider.ACTION_TV_REMOTE_PROVIDER
    protected static final String SERVICE_INTERFACE =
            "com.android.media.tv.remoteprovider.TvRemoteProvider";
    private final Context mContext;
    private final Object mLock;
    private final ComponentName mComponentName;
    private final int mUserId;
    private final int mUid;

    // State changes happen only in the main thread, hence no lock is needed
    private boolean mRunning;
    private boolean mBound;
    private boolean mConnected;

    TvRemoteProviderProxy(Context context, Object lock,
                          ComponentName componentName, int userId, int uid) {
        mContext = context;
        mLock = lock;
        mComponentName = componentName;
        mUserId = userId;
        mUid = uid;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "Proxy");
        pw.println(prefix + "  mUserId=" + mUserId);
        pw.println(prefix + "  mRunning=" + mRunning);
        pw.println(prefix + "  mBound=" + mBound);
        pw.println(prefix + "  mConnected=" + mConnected);
    }

    public boolean hasComponentName(String packageName, String className) {
        return mComponentName.getPackageName().equals(packageName)
                && mComponentName.getClassName().equals(className);
    }

    public void start() {
        if (!mRunning) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Starting");
            }

            mRunning = true;
            bind();
        }
    }

    public void stop() {
        if (mRunning) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Stopping");
            }

            mRunning = false;
            unbind();
        }
    }

    public void rebindIfDisconnected() {
        if (mRunning && !mConnected) {
            unbind();
            bind();
        }
    }

    private void bind() {
        if (!mBound) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Binding");
            }

            Intent service = new Intent(SERVICE_INTERFACE);
            service.setComponent(mComponentName);
            try {
                mBound = mContext.bindServiceAsUser(service, this,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        new UserHandle(mUserId));
                if (DEBUG && !mBound) {
                    Slog.d(TAG, this + ": Bind failed");
                }
            } catch (SecurityException ex) {
                if (DEBUG) {
                    Slog.d(TAG, this + ": Bind failed", ex);
                }
            }
        }
    }

    private void unbind() {
        if (mBound) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Unbinding");
            }

            mBound = false;
            mContext.unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (DEBUG) {
            Slog.d(TAG, this + ": onServiceConnected()");
        }

        mConnected = true;

        final ITvRemoteProvider provider = ITvRemoteProvider.Stub.asInterface(service);
        if (provider == null) {
            Slog.e(TAG, this + ": Invalid binder");
            return;
        }

        try {
            provider.setRemoteServiceInputSink(new TvRemoteServiceInput(mLock, provider));
        } catch (RemoteException e) {
            Slog.e(TAG, this + ": Failed remote call to setRemoteServiceInputSink");
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mConnected = false;

<<<<<<< HEAD   (009dd8 Automatic translation import)
        if (DEBUG) {
            Slog.d(TAG, this + ": onServiceDisconnected()");
=======

    private void onConnectionReady(Connection connection) {
        synchronized (mLock) {
            if (DEBUG) Slog.d(TAG, "onConnectionReady");
            if (mActiveConnection == connection) {
                if (DEBUG) Slog.d(TAG, "mConnectionReady = true");
                mConnectionReady = true;
            }
        }
    }

    private void onConnectionDied(Connection connection) {
        if (mActiveConnection == connection) {
            if (DEBUG) Slog.d(TAG, this + ": Service connection died");
            disconnect();
        }
    }

    private void disconnect() {
        synchronized (mLock) {
            if (mActiveConnection != null) {
                mConnectionReady = false;
                mActiveConnection.dispose();
                mActiveConnection = null;
            }
        }
    }

    // Provider helpers
    public void inputBridgeConnected(IBinder token) {
        synchronized (mLock) {
            if (DEBUG) Slog.d(TAG, this + ": inputBridgeConnected token: " + token);
            if (mConnectionReady) {
                mActiveConnection.onInputBridgeConnected(token);
            }
        }
    }

    public interface ProviderMethods {
        // InputBridge
        void openInputBridge(TvRemoteProviderProxy provider, IBinder token, String name,
                             int width, int height, int maxPointers);

        void openInputBridge(TvRemoteProviderProxy provider, IBinder token, String name,
                             int width, int height, int maxPointers, int axisMin, int axisMax,
                             int fuzz, int flat);

        void closeInputBridge(TvRemoteProviderProxy provider, IBinder token);

        void clearInputBridge(TvRemoteProviderProxy provider, IBinder token);

        void sendTimeStamp(TvRemoteProviderProxy provider, IBinder token, long timestamp);

        void sendKeyDown(TvRemoteProviderProxy provider, IBinder token, int keyCode);

        void sendKeyUp(TvRemoteProviderProxy provider, IBinder token, int keyCode);

        void sendPointerDown(TvRemoteProviderProxy provider, IBinder token, int pointerId, int x,
                             int y);

        void sendPointerUp(TvRemoteProviderProxy provider, IBinder token, int pointerId);

        void sendPointerSync(TvRemoteProviderProxy provider, IBinder token);

        void sendMouseBtnLeft(TvRemoteProviderProxy provider, IBinder token, boolean down);

        void sendMouseBtnRight(TvRemoteProviderProxy provider, IBinder token, boolean down);

        void sendMouseMove(TvRemoteProviderProxy provider, IBinder token, int x, int y);

        void sendMouseWheel(TvRemoteProviderProxy provider, IBinder token, int x, int y);

        void sendAbsEvent(TvRemoteProviderProxy provider, IBinder token, int x, int y, int axis);
    }

    private final class Connection implements IBinder.DeathRecipient {
        private final ITvRemoteProvider mTvRemoteProvider;
        private final RemoteServiceInputProvider mServiceInputProvider;

        public Connection(ITvRemoteProvider provider) {
            mTvRemoteProvider = provider;
            mServiceInputProvider = new RemoteServiceInputProvider(this);
        }

        public boolean register() {
            if (DEBUG) Slog.d(TAG, "Connection::register()");
            try {
                mTvRemoteProvider.asBinder().linkToDeath(this, 0);
                mTvRemoteProvider.setRemoteServiceInputSink(mServiceInputProvider);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onConnectionReady(Connection.this);
                    }
                });
                return true;
            } catch (RemoteException ex) {
                binderDied();
            }
            return false;
        }

        public void dispose() {
            if (DEBUG) Slog.d(TAG, "Connection::dispose()");
            mTvRemoteProvider.asBinder().unlinkToDeath(this, 0);
            mServiceInputProvider.dispose();
        }


        public void onInputBridgeConnected(IBinder token) {
            if (DEBUG) Slog.d(TAG, this + ": onInputBridgeConnected");
            try {
                mTvRemoteProvider.onInputBridgeConnected(token);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver onInputBridgeConnected. ", ex);
            }
        }

        @Override
        public void binderDied() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    onConnectionDied(Connection.this);
                }
            });
        }

        void openInputBridge(final IBinder token, final String name, final int width,
                             final int height, final int maxPointers) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG) {
                        Slog.d(TAG, this + ": openInputBridge," +
                                " token=" + token + ", name=" + name);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.openInputBridge(TvRemoteProviderProxy.this, token,
                                    name, width, height, maxPointers);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "openInputBridge, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void openInputBridge(final IBinder token, final String name, final int width,
                             final int height, final int maxPointers, final int axisMin,
                             final int axisMax, final int fuzz, final int flat) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG) {
                        Slog.d(TAG, this + ": openInputBridge," +
                                " token=" + token + ", name=" + name);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.openInputBridge(TvRemoteProviderProxy.this, token,
                                    name, width, height, maxPointers, axisMin, axisMax, fuzz, flat);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "openInputBridge, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void closeInputBridge(final IBinder token) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG) {
                        Slog.d(TAG, this + ": closeInputBridge," +
                                " token=" + token);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.closeInputBridge(TvRemoteProviderProxy.this, token);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "closeInputBridge, Invalid connection or incorrect uid: " +
                                        Binder.getCallingUid());
                    }
                }
            }
        }

        void clearInputBridge(final IBinder token) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG) {
                        Slog.d(TAG, this + ": clearInputBridge," +
                                " token=" + token);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.clearInputBridge(TvRemoteProviderProxy.this, token);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "clearInputBridge, Invalid connection or incorrect uid: " +
                                        Binder.getCallingUid());
                    }
                }
            }
        }

        void sendTimestamp(final IBinder token, final long timestamp) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendTimeStamp(TvRemoteProviderProxy.this, token,
                                    timestamp);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendTimeStamp, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendKeyDown(final IBinder token, final int keyCode) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendKeyDown," +
                                " token=" + token + ", keyCode=" + keyCode);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendKeyDown(TvRemoteProviderProxy.this, token,
                                    keyCode);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendKeyDown, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendKeyUp(final IBinder token, final int keyCode) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendKeyUp," +
                                " token=" + token + ", keyCode=" + keyCode);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendKeyUp(TvRemoteProviderProxy.this, token, keyCode);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendKeyUp, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendPointerDown(final IBinder token, final int pointerId, final int x, final int y) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendPointerDown," +
                                " token=" + token + ", pointerId=" + pointerId);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendPointerDown(TvRemoteProviderProxy.this, token,
                                    pointerId, x, y);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendPointerDown, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendPointerUp(final IBinder token, final int pointerId) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendPointerUp," +
                                " token=" + token + ", pointerId=" + pointerId);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendPointerUp(TvRemoteProviderProxy.this, token,
                                    pointerId);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendPointerUp, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendPointerSync(final IBinder token) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendPointerSync," +
                                " token=" + token);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendPointerSync(TvRemoteProviderProxy.this, token);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendPointerSync, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendMouseBtnLeft(final IBinder token, boolean down) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendMouseBtnLeft," +
                                " token=" + token + ", down=" + down);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendMouseBtnLeft(TvRemoteProviderProxy.this, token, down);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendMouseBtnLeft, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendMouseBtnRight(final IBinder token, boolean down) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendMouseBtnRight," +
                                " token=" + token + ", down=" + down);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendMouseBtnRight(TvRemoteProviderProxy.this, token, down);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendMouseBtnRight, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendMouseMove(final IBinder token, int x, int y) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendMouseMove," +
                                " token=" + token + ", x=" + x +
                                ", y=" + y);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendMouseMove(TvRemoteProviderProxy.this, token, x, y);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendMouseBtnLeft, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendMouseWheel(final IBinder token, int x, int y) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendMouseWheel," +
                                " token=" + token + ", x=" + x +
                                ", y=" + y);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendMouseWheel(TvRemoteProviderProxy.this, token, x, y);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendMouseWheel, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendAbsEvent(final IBinder token, int x, int y, int axis) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendAbsEvent," +
                                " token=" + token + ", x=" + x +
                                ", y=" + y + ", axis=" + axis);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendAbsEvent(TvRemoteProviderProxy.this, token, x, y, axis);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendAbsEvent, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }
    }

    /**
     * Receives events from the connected provider.
     * <p>
     * This inner class is static and only retains a weak reference to the connection
     * to prevent the client from being leaked in case the service is holding an
     * active reference to the client's callback.
     * </p>
     */
    private static final class RemoteServiceInputProvider extends ITvRemoteServiceInput.Stub {
        private final WeakReference<Connection> mConnectionRef;

        public RemoteServiceInputProvider(Connection connection) {
            mConnectionRef = new WeakReference<Connection>(connection);
        }

        public void dispose() {
            // Terminate the connection.
            mConnectionRef.clear();
        }

        @Override
        public void openInputBridge(IBinder token, String name, int width,
                                    int height, int maxPointers) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.openInputBridge(token, name, width, height, maxPointers);
            }
        }

        @Override
        public void nvOpenInputBridge(IBinder token, String name, int width,
                                    int height, int maxPointers, int axisMin,
                                    int axisMax, int fuzz, int flat) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.openInputBridge(token, name, width, height, maxPointers, axisMin, axisMax, fuzz, flat);
            }
        }

        @Override
        public void closeInputBridge(IBinder token) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.closeInputBridge(token);
            }
        }

        @Override
        public void clearInputBridge(IBinder token) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.clearInputBridge(token);
            }
        }

        @Override
        public void sendTimestamp(IBinder token, long timestamp) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendTimestamp(token, timestamp);
            }
        }

        @Override
        public void sendKeyDown(IBinder token, int keyCode) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendKeyDown(token, keyCode);
            }
        }

        @Override
        public void sendKeyUp(IBinder token, int keyCode) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendKeyUp(token, keyCode);
            }
        }

        @Override
        public void sendPointerDown(IBinder token, int pointerId, int x, int y)
                throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendPointerDown(token, pointerId, x, y);
            }
        }

        @Override
        public void sendPointerUp(IBinder token, int pointerId) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendPointerUp(token, pointerId);
            }
        }

        @Override
        public void sendPointerSync(IBinder token) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendPointerSync(token);
            }
>>>>>>> CHANGE (3f6e50 Add support for Nvidia tvremote interface)
        }

        @Override
        public void sendMouseBtnLeft(IBinder token, boolean down) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendMouseBtnLeft(token, down);
            }
        }

        @Override
        public void sendMouseBtnRight(IBinder token, boolean down) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendMouseBtnRight(token, down);
            }
        }

        @Override
        public void sendMouseMove(IBinder token, int x, int y) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendMouseMove(token, x, y);
            }
        }

        @Override
        public void sendMouseWheel(IBinder token, int x, int y) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendMouseWheel(token, x, y);
            }
        }

        @Override
        public void sendAbsEvent(IBinder token, int x, int y, int axis) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendAbsEvent(token, x, y, axis);
            }
        }
    }
}
