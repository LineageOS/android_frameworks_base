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
 * limitations under the License
 */

package android.telephony.ims.compat.feature;

import android.app.PendingIntent;
import android.os.Message;
import android.os.RemoteException;
import android.os.IBinder;
import android.telephony.ims.compat.feature.ImsFeature;

import android.annotation.UnsupportedAppUsage;
import android.telephony.ims.ImsCallProfile;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsCallSessionListener;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMMTelFeature;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsService;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsUt;
import android.telephony.ims.ImsCallSession;
import android.telephony.ims.compat.stub.ImsCallSessionImplBase;
import android.telephony.ims.stub.ImsEcbmImplBase;
import android.telephony.ims.stub.ImsMultiEndpointImplBase;
import android.telephony.ims.stub.ImsUtImplBase;

/**
 * Base implementation for MMTel.
 * Any class wishing to use MMTelFeature should extend this class and implement all methods that the
 * service supports.
 *
 * @hide
 */

public class MMTelFeature extends ImsFeature {


    private static final int SERVICE_ID = ImsFeature.MMTEL;


    protected final int mSlotId;
    protected IBinder mBinder;

    public MMTelFeature(int slotId, IBinder binder) {
        mSlotId = slotId;
        mBinder = binder;
    }

    // Lock for feature synchronization
    private final Object mLock = new Object();

    private final IImsMMTelFeature mImsMMTelBinder = new IImsMMTelFeature.Stub() {

        @Override
        public int startSession(PendingIntent incomingCallIntent,
                IImsRegistrationListener listener) throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        return getServiceInterface(mBinder).open(mSlotId, ImsFeature.MMTEL, incomingCallIntent,
                listener);
            }
        }

        @Override
        public void endSession(int sessionId) throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        getServiceInterface(mBinder).close(sessionId);
            }
        }

        @Override
        public boolean isConnected(int callSessionType, int callType)
                throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        return getServiceInterface(mBinder).isConnected(SERVICE_ID,  callSessionType, callType);
            }
        }

        @Override
        public boolean isOpened() throws RemoteException {
            synchronized (mLock) {
               checkBinderConnection();
        return getServiceInterface(mBinder).isOpened(SERVICE_ID);
            }
        }

         /**
          * Base implementation, always returns READY for compatibility with old ImsService.
          */

        @Override
        public int getFeatureStatus() throws RemoteException {
            synchronized (mLock) {
                return ImsFeature.STATE_READY;
            }
        }

        @Override
        public void addRegistrationListener(IImsRegistrationListener listener)
                throws RemoteException {
            synchronized (mLock) {
               checkBinderConnection();
               getServiceInterface(mBinder).addRegistrationListener(mSlotId, ImsFeature.MMTEL, listener);
            }
        }

        @Override
        public void removeRegistrationListener(IImsRegistrationListener listener)
                throws RemoteException {
            synchronized (mLock) {
                // Not Implemented in old ImsService. If the registration listener becomes invalid, the
                // ImsService will remove.
            }
        }

        @Override
        public ImsCallProfile createCallProfile(int sessionId, int callSessionType, int callType)
                throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        return getServiceInterface(mBinder).createCallProfile(sessionId, callSessionType, callType);
            }
        }

        @Override
        public IImsCallSession createCallSession(int sessionId, ImsCallProfile profile)
                throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        return getServiceInterface(mBinder).createCallSession(sessionId, profile, null);
            }
        }

        @Override
        public IImsCallSession getPendingCallSession(int sessionId, String callId)
                throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        return getServiceInterface(mBinder).getPendingCallSession(sessionId, callId);
            }
        }

        @Override
        public IImsUt getUtInterface() throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        return getServiceInterface(mBinder).getUtInterface(SERVICE_ID);
            }
        }

        @Override
        public IImsConfig getConfigInterface() throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        return getServiceInterface(mBinder).getConfigInterface(mSlotId);
            }
        }

        @Override
        public void turnOnIms() throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        getServiceInterface(mBinder).turnOnIms(mSlotId);
            }
        }

        @Override
        public void turnOffIms() throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        getServiceInterface(mBinder).turnOffIms(mSlotId);
            }
        }

        @Override
        public IImsEcbm getEcbmInterface() throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        return getServiceInterface(mBinder).getEcbmInterface(SERVICE_ID);
            }
        }

        @Override
        public void setUiTTYMode(int uiTtyMode, Message onComplete) throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        getServiceInterface(mBinder).setUiTTYMode(SERVICE_ID, uiTtyMode, onComplete);
            }
        }

        @Override
        public IImsMultiEndpoint getMultiEndpointInterface() throws RemoteException {
            synchronized (mLock) {
                checkBinderConnection();
        return getServiceInterface(mBinder).getMultiEndpointInterface(SERVICE_ID);
            }
        }
    };

    /**
     * @hide
     */
    @Override
    public final IImsMMTelFeature getBinder() {
        return mImsMMTelBinder;
    }


    /**
     * @return false if the binder connection is no longer alive.
     */
    public boolean isBinderAlive() {
        return mBinder != null && mBinder.isBinderAlive();
    }

    private IImsService getServiceInterface(IBinder b) {
        return IImsService.Stub.asInterface(b);
    }

    protected void checkBinderConnection() throws RemoteException {
        if (!isBinderAlive()) {
            throw new RemoteException("ImsServiceProxy is not available for that feature.");
        }
    }

     
    @Override
    public void onFeatureReady() {

    }

    /**
     * {@inheritDoc}
     */
    public void onFeatureRemoved() {

    }
}
