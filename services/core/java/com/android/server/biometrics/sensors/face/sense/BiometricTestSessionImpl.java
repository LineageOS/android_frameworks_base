/*
 * Copyright (C) 2020 The Android Open Source Project
 * Copyright (C) 2023 Paranoid Android
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

package com.android.server.biometrics.sensors.face.sense;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.face.Face;
import android.hardware.face.FaceAuthenticationFrame;
import android.hardware.face.FaceEnrollFrame;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricStateCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.face.FaceUtils;

import java.util.List;
import java.util.Random;

/**
 * A test session implementation for {@link SenseProvider}. See
 * {@link android.hardware.biometrics.BiometricTestSession}.
 */
public class BiometricTestSessionImpl extends ITestSession.Stub {

    private static final String TAG = "face/sense/BiometricTestSessionImpl";

    @NonNull private final Context mContext;
    private final int mSensorId;
    @NonNull private final ITestSessionCallback mCallback;
    @NonNull private final Random mRandom;

    @NonNull private final SenseProvider.HalResultController mHalResultController;
    @NonNull private final SenseProvider mSenseProvider;

    /**
     * Internal receiver currently only used for enroll. Results do not need to be forwarded to the
     * test, since enrollment is a platform-only API. The authentication path is tested through
     * the public BiometricPrompt APIs and does not use this receiver.
     */
    private final IFaceServiceReceiver mReceiver = new IFaceServiceReceiver.Stub() {
        @Override
        public void onEnrollResult(Face face, int remaining) {

        }

        @Override
        public void onAcquired(int acquireInfo, int vendorCode) {

        }

        @Override
        public void onAuthenticationSucceeded(Face face, int userId, boolean isStrongBiometric) {

        }

        @Override
        public void onFaceDetected(int sensorId, int userId, boolean isStrongBiometric) {

        }

        @Override
        public void onAuthenticationFailed() {

        }

        @Override
        public void onError(int error, int vendorCode) {

        }

        @Override
        public void onRemoved(Face face, int remaining) {

        }

        @Override
        public void onFeatureSet(boolean success, int feature) {

        }

        @Override
        public void onFeatureGet(boolean success, int[] features, boolean[] featureState) {

        }

        @Override
        public void onChallengeGenerated(int sensorId, int userId, long challenge) {

        }

        @Override
        public void onAuthenticationFrame(FaceAuthenticationFrame frame) {

        }

        @Override
        public void onEnrollmentFrame(FaceEnrollFrame frame) {

        }
    };

    BiometricTestSessionImpl(@NonNull Context context, int sensorId,
            @NonNull ITestSessionCallback callback, @NonNull SenseProvider provider,
            @NonNull SenseProvider.HalResultController halResultController) {
        mContext = context;
        mSensorId = sensorId;
        mCallback = callback;
        mSenseProvider = provider;
        mHalResultController = halResultController;
        mRandom = new Random();
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void setTestHalEnabled(boolean enabled) {
        super.setTestHalEnabled_enforcePermission();

        mSenseProvider.setTestHalEnabled(enabled);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void startEnroll(int userId) {
        super.startEnroll_enforcePermission();

        mSenseProvider.scheduleEnroll(mSensorId, new Binder(), new byte[69], userId, mReceiver,
                mContext.getOpPackageName(), new int[0] /* disabledFeatures */,
                null /* previewSurface */, false /* debugConsent */, null);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void finishEnroll(int userId) {
        super.finishEnroll_enforcePermission();

        mHalResultController.onEnrollResult(1, userId, 0);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void acceptAuthentication(int userId) {
        super.acceptAuthentication_enforcePermission();

        // Fake authentication with any of the existing faces
        List<Face> faces = FaceUtils.getInstance(mSensorId)
                .getBiometricsForUser(mContext, userId);
        if (faces.isEmpty()) {
            Slog.w(TAG, "No faces, returning");
            return;
        }
        final int fid = faces.get(0).getBiometricId();
        byte[] hat = {0};
        mHalResultController.onAuthenticated(0 /* deviceId */, userId, hat);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void rejectAuthentication(int userId) {
        super.rejectAuthentication_enforcePermission();

        mHalResultController.onAuthenticated(0 /* deviceId */, userId, null);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void notifyAcquired(int userId, int acquireInfo) {
        super.notifyAcquired_enforcePermission();

        mHalResultController.onAcquired(userId, 0 /* deviceId */, 0 /* vendorCode */);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void notifyError(int userId, int errorCode) {
        super.notifyError_enforcePermission();

        mHalResultController.onError(0 /* deviceId */, 0 /* vendorCode */);
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.TEST_BIOMETRIC)
    @Override
    public void cleanupInternalState(int userId) {
        super.cleanupInternalState_enforcePermission();

        mSenseProvider.scheduleInternalCleanup(mSensorId, userId, new ClientMonitorCallback() {
            @Override
            public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
                try {
                    mCallback.onCleanupStarted(clientMonitor.getTargetUserId());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }

            @Override
            public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                    boolean success) {
                try {
                    mCallback.onCleanupFinished(clientMonitor.getTargetUserId());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception", e);
                }
            }
        });
    }
}
