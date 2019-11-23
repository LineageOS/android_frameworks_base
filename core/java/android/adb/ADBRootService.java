/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.adb;

import android.adbroot.IADBRootService;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

/**
 * {@hide}
 */
public class ADBRootService {
    private static final String TAG = "ADBRootService";

    private static final String ADB_ROOT_SERVICE = "adbroot_service";

    private IADBRootService mService;
    private Context mContext;

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            if (mService != null) {
                mService.asBinder().unlinkToDeath(this, 0);
            }
            mService = null;
        }
    };

    /**
     * Creates a new instance.
     */
    public ADBRootService(Context context) {
        mContext = context;
    }

    private synchronized IADBRootService getService()
            throws RemoteException {
        if (mService != null) {
            return mService;
        }

        final IBinder service = ServiceManager.getService(ADB_ROOT_SERVICE);
        if (service != null) {
            service.linkToDeath(mDeathRecipient, 0);
            mService = IADBRootService.Stub.asInterface(service);
            return mService;
        }

        Slog.e(TAG, "Unable to acquire ADBRootService");
        return null;
    }

    /**
     * @hide
     */
    public void setEnabled(boolean enable) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ADBROOT, "adbroot");
        try {
            final IADBRootService svc = getService();
            if (svc != null) {
                svc.setEnabled(enable);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean getEnabled() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ADBROOT, "adbroot");
        try {
            final IADBRootService svc = getService();
            if (svc != null) {
                return svc.getEnabled();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return false;
    }
}
