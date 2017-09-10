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

package android.mperspective;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

/**
 * System private Java interface to PerspectiveService.
 *
 * @hide
 */
public final class PerspectiveManager {
    private static final String TAG = "PerspectiveManager";

    private final IPerspectiveService mService;
    private IPerspectiveServiceCallback mCallback;
    private PerspectiveListenerHandler mListener;

    public PerspectiveManager(final IPerspectiveService service) {
        mService = service;
    }

    public void startDesktopPerspective() {
        try {
            mService.startDesktopPerspective();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException starting desktop perspective", e);
        }
    }

    public void stopDesktopPerspective() {
        try {
            mService.stopDesktopPerspective();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException stopping desktop perspective", e);
        }
    }

    public boolean isDesktopRunning() {
        try {
            return mService.isDesktopRunning();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException checking desktop perspective state", e);
        }
        return false;
    }

    public void registerPerspectiveListener(final PerspectiveListener listener,
                                            final Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }

        mListener = new PerspectiveListenerHandler(listener, handler);
        registerCallbackIfNeeded();
    }

    public void unregisterPerspectiveListener() {
        mListener = null;
        // TODO: unregister from service?
    }

    private void registerCallbackIfNeeded() {
        if (mCallback == null) {
            mCallback = new PerspectiveServiceCallback();
            try {
                mService.registerCallback(mCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException registering perspective callback", e);
                mCallback = null;
            }
        }
    }

    private final class PerspectiveServiceCallback extends IPerspectiveServiceCallback.Stub {
        @Override
        public void onPerspectiveEvent(int event) {
            if (mListener != null) {
                mListener.sendPerspectiveEvent(event);
            }
        }
    }

    /**
     * This class ensures that events are delivered to a listener on
     * its original registering thread or specified alternate Handler.
     */
    private static final class PerspectiveListenerHandler extends Handler {
        private PerspectiveListener mListener;

        public PerspectiveListenerHandler(final PerspectiveListener listener,
                                          final Handler handler) {
            super(handler != null ? handler.getLooper() : Looper.myLooper());
            mListener = listener;
        }

        public void sendPerspectiveEvent(int event) {
            sendEmptyMessage(event);
        }

        @Override
        public void handleMessage(Message msg) {
            mListener.onPerspectiveStateChanged(msg.what);
        }
    }

    /**
     * Listen for perspective lifecycle events.
     */
    public interface PerspectiveListener {

        /**
         *
         * @param state One of {@link Perspective#STATE_STARTING},
         *              {@link Perspective#STATE_RUNNING},
         *              {@link Perspective#STATE_STOPPING},
         *              {@link Perspective#STATE_STOPPED},
         */
        void onPerspectiveStateChanged(int state);
    }
}
