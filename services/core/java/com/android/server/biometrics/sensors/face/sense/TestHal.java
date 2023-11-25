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
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.face.V1_0.FaceError;
import android.hardware.biometrics.face.V1_0.OptionalBool;
import android.hardware.biometrics.face.V1_0.OptionalUint64;
import android.hardware.biometrics.face.V1_0.Status;
import android.hardware.face.Face;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.face.FaceUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import vendor.aospa.biometrics.face.ISenseService;
import vendor.aospa.biometrics.face.ISenseServiceReceiver;

public class TestHal extends ISenseService.Stub {
    private static final String TAG = "face.hidl.TestHal";

    @NonNull
    private final Context mContext;
    private final int mSensorId;

    @Nullable
    private ISenseServiceReceiver mCallback;
    private int mUserId;

    TestHal(@NonNull Context context, int sensorId) {
        mContext = context;
        mSensorId = sensorId;
    }

    @Override
    public void setCallback(ISenseServiceReceiver clientCallback) {
        mCallback = clientCallback;
    }

    @Override
    public long generateChallenge(int challengeTimeoutSec) {
        Slog.w(TAG, "generateChallenge");
        return 0L;
    }

    @Override
    public void enroll(byte[] hat, int timeoutSec, int[] disabledFeatures) {
        Slog.w(TAG, "enroll");
    }

    @Override
    public int revokeChallenge() {
        return 0;
    }

    @Override
    public void setFeature(int feature, boolean enabled, byte[] token, int faceId) { }

    @Override
    public boolean getFeature(int feature, int faceId) {
        return false;
    }

    @Override
    public int getFeatureCount() throws RemoteException {
        return 0;
    }

    @Override
    public int getAuthenticatorId() {
        return 0;
    }

    @Override
    public void cancel() throws RemoteException {
        if (mCallback != null) {
            mCallback.onError(0 /* deviceId */, 0 /* vendorCode */);
        }
    }

    @Override
    public int enumerate() throws RemoteException {
        Slog.w(TAG, "enumerate");
        if (mCallback != null) {
            mCallback.onEnumerate(new int[0], 0 /* userId */);
        }
        return 0;
    }

    @Override
    public void remove(int faceId) throws RemoteException {
        Slog.w(TAG, "remove");
        if (mCallback != null) {
            if (faceId == 0) {
                List<Face> faces = FaceUtils.getInstance(mSensorId).getBiometricsForUser(mContext, mUserId);
                if (faces.size() <= 0) {
                    mCallback.onError(6, 0);
                    return;
                }
                int[] faceIds = new int[faces.size()];
                for (int i = 0; i < faces.size(); i++) {
                    Face face = faces.get(i);
                    faceIds[i] = face.getBiometricId();
                }

                mCallback.onRemoved(faceIds, mUserId);
            } else {
                mCallback.onRemoved(new int[]{faceId}, mUserId);
            }
        }
    }

    @Override
    public void authenticate(long operationId) {
        Slog.w(TAG, "authenticate");
    }

    @Override
    public void resetLockout(byte[] hat) {
        Slog.w(TAG, "resetLockout");
    }

}
