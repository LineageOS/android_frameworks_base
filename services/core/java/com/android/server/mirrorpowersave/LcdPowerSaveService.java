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

package com.android.server.mirrorpowersave;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.server.SystemService;
import com.android.server.Watchdog;

import com.android.internal.mirrorpowersave.ILcdPowerSave;
import com.android.internal.mirrorpowersave.LcdPowerSaveInternal;
import com.android.internal.mirrorpowersave.LcdPowerSaveManager;

import java.util.ArrayList;

import libcore.util.Objects;

public final class LcdPowerSaveService extends SystemService
        implements Watchdog.Monitor {
    private static final String TAG = "LcdPowerSaveService";
    private static final boolean DEBUG = false;

    private final Object mLock = new Object();
    private final ArrayList<PowerSaveRequest> mPowerSaveRequests =
            new ArrayList<PowerSaveRequest>();

    private Context mContext;
    private Handler mHandler;
    private LcdPowerSave mLcdPowerSave = null;
    // True if boot completed occurred.
    private boolean mBootCompleted = false;
    private ScreenStatusChangedReceiver mScreenStatusChangedReceiver;

    public LcdPowerSaveService(Context context) {
        super(context);
        mContext = context;
        mHandler = new Handler();
        mPowerSaveRequests.clear();
        mLcdPowerSave = new LcdPowerSave(mContext);
    }

    @Override // Watchdog.Monitor implementation
    public void monitor() {
        // Grab and release lock for watchdog monitor to detect deadlocks.
        synchronized (mLock) {
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.LCD_POWER_SAVE_SERVICE, new BinderService());
        publishLocalService(LcdPowerSaveInternal.class, new LocalService());
        Watchdog.getInstance().addMonitor(this);
        Watchdog.getInstance().addThread(mHandler);
    }

    @Override
    public void onBootPhase(int phase) {
        synchronized (mLock) {
            if (phase == PHASE_BOOT_COMPLETED) {
                mBootCompleted = true;
                if (!mPowerSaveRequests.isEmpty()) {
                    mLcdPowerSave.lcdPowerSaving(true);
                }
            }
        }
    }

    public void systemReady() {
        mLcdPowerSave.systemReady();
        synchronized (mLock) {
            if (mBootCompleted && !mPowerSaveRequests.isEmpty()) {
            	mLcdPowerSave.lcdPowerSaving(true);
            }
        }
    }

    private void acquirePowerSaveInternal(IBinder lock, int flags, String tag,
            String packageName, int uid, int pid) {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "acquirePowerSaveInternal: lock=" + Objects.hashCode(lock)
                        + ", flags=0x" + Integer.toHexString(flags)
                        + ", tag=\"" + tag + "\", " + ", uid=" + uid + ", pid=" + pid);
            }
            PowerSaveRequest request;
            int index = findPowerSaveRequestIndexLocked(lock);
            if (index >= 0) {
                request = mPowerSaveRequests.get(index);
                if (!request.hasSameProperties(flags, tag, uid, pid)) {
                    // Update existing power save request. This shouldn't happen but is harmless.
                    request.updateProperties(flags, tag, packageName, uid, pid);
                }
            } else {
                request = new PowerSaveRequest(lock, flags, tag, packageName, uid, pid);
                try {
                    lock.linkToDeath(request, 0);
                } catch (RemoteException ex) {
                    throw new IllegalArgumentException("Power save request is already dead.");
                }

                if (mPowerSaveRequests.isEmpty()) {
                    // Set Intent.ACTION_SCREEN_ON / Intent.ACTION_SCREEN_OFF receiver
                    IntentFilter filter = new IntentFilter();
                    filter.addAction(Intent.ACTION_SCREEN_ON);
                    filter.addAction(Intent.ACTION_SCREEN_OFF);
                    mScreenStatusChangedReceiver = new ScreenStatusChangedReceiver();
                    mContext.registerReceiver(
                            mScreenStatusChangedReceiver, filter, null, mHandler);
                }

                mPowerSaveRequests.add(request);
            }

            updateLcdPowerSaveConfigLocked();
            if (mBootCompleted) {
                mLcdPowerSave.lcdPowerSaving(true);
            }
        }
    }

    private void releasePowerSaveInternal(IBinder lock) {
        synchronized (mLock) {
            int index = findPowerSaveRequestIndexLocked(lock);
            if (index < 0) {
                if (DEBUG) {
                    Slog.d(TAG, "releasePowerSaveInternal: lock=" + Objects.hashCode(lock));
                }
                return;
            }

            PowerSaveRequest request = mPowerSaveRequests.get(index);
            if (DEBUG) {
                Slog.d(TAG, "releasePowerSaveInternal: lock=" + Objects.hashCode(lock)
                    + " [" + request.mTag + "]");
            }
            mPowerSaveRequests.remove(index);
            request.mLock.unlinkToDeath(request, 0);
            if (mPowerSaveRequests.isEmpty()) {
                if (mScreenStatusChangedReceiver != null) {
                    mContext.unregisterReceiver(mScreenStatusChangedReceiver);
                }
                mLcdPowerSave.lcdPowerSaving(false);
            } else {
                updateLcdPowerSaveConfigLocked();
                mLcdPowerSave.lcdPowerSaving(true);
            }
        }
    }

    private void resumeLcdInternal(IBinder lock) {
        synchronized (mLock) {
            int index = findPowerSaveRequestIndexLocked(lock);
            if (index < 0) {
                if (DEBUG) {
                    Slog.d(TAG, "resumeLcdInternal: lock=" + Objects.hashCode(lock));
                }
                return;
            }
            mLcdPowerSave.lcdOn();
        }
    }

    /**
     * the API must be called after ...
     *   1. request.updateProperties()
     *   2. mPowerSaveRequests.add()
     *   3. mPowerSaveRequests.remove()
     */
    private void updateLcdPowerSaveConfigLocked() {
        int requestedFlags = 0;

        if (mPowerSaveRequests.isEmpty()) {
            // if no request, the default flag value is set.
            // Actually, in the case of the empty, it is no matter
            // whether adding the default value or not.
            // However, for the correct finalization, the default value
            // is set.
            requestedFlags = LcdPowerSaveManager.FLAG_LOCAL_TOUCH_ACTIVATED |
                    LcdPowerSaveManager.FLAG_LCD_ON_BY_USER_ACTIVITY;
        } else {
            for (PowerSaveRequest request : mPowerSaveRequests) {
                requestedFlags |= request.mFlags;
            }
        }
        mLcdPowerSave.configureLocalTouch(
                (requestedFlags & LcdPowerSaveManager.FLAG_LOCAL_TOUCH_ACTIVATED) != 0);

        mLcdPowerSave.configureResumeLcdWithUserActivity(
                (requestedFlags & LcdPowerSaveManager.FLAG_LCD_ON_BY_USER_ACTIVITY) != 0);
    }

    private int findPowerSaveRequestIndexLocked(IBinder lock) {
        final int count = mPowerSaveRequests.size();
        for (int i = 0; i < count; i++) {
            if (mPowerSaveRequests.get(i).mLock == lock) {
                return i;
            }
        }
        return -1;
    }

    private void handlePowerSaveRequestDeath(PowerSaveRequest request) {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "handlePowerSaveRequestDeath: lock="
                        + Objects.hashCode(request.mLock)
                        + " [" + request.mTag + "]");
            }
            int index = mPowerSaveRequests.indexOf(request);
            if (index < 0) {
                return;
            }
            mPowerSaveRequests.remove(index);
            updateLcdPowerSaveConfigLocked();
            if (mPowerSaveRequests.isEmpty()) {
                mLcdPowerSave.lcdPowerSaving(false);
            }
        }
    }

    private final class BinderService extends ILcdPowerSave.Stub {
        @Override // Binder call
        public void acquirePowerSave(IBinder lock, int flags, String tag, String packageName) {
            if (lock == null) {
                throw new IllegalArgumentException("lock must not be null");
            }
            if (packageName == null) {
                throw new IllegalArgumentException("packageName must not be null");
            }
            final int uid = Binder.getCallingUid();
            final int pid = Binder.getCallingPid();
            final long ident = Binder.clearCallingIdentity();
            if (DEBUG) {
                Slog.i(TAG, "acquirePowerSave uid:pid = " + uid + ":" +pid);
            }
            try {
                acquirePowerSaveInternal(lock, flags, tag, packageName, uid, pid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void releasePowerSave(IBinder lock) {
            if (lock == null) {
                throw new IllegalArgumentException("lock must not be null");
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                releasePowerSaveInternal(lock);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void resumeLcd(IBinder lock) {
            if (lock == null) {
                throw new IllegalArgumentException("lock must not be null");
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                resumeLcdInternal(lock);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private final class ScreenStatusChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                if (mPowerSaveRequests.isEmpty()) {
                    return;
                }
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    if (mBootCompleted) {
                        mLcdPowerSave.lcdPowerSaving(true);
                    }
                } else {
                    mLcdPowerSave.lcdPowerSaving(false);
                }
            }
        }
    }

    /**
     * Represents a power save request that has been acquired by an application.
     */
    private final class PowerSaveRequest implements IBinder.DeathRecipient {
        public final IBinder mLock;
        public int mFlags;
        public String mTag;
        public final String mPackageName;
        public final int mOwnerUid;
        public final int mOwnerPid;
        public boolean mNotifiedAcquired;

        public PowerSaveRequest(IBinder lock, int flags, String tag, String packageName,
                int ownerUid, int ownerPid) {
            mLock = lock;
            mFlags = flags;
            mTag = tag;
            mPackageName = packageName;
            mOwnerUid = ownerUid;
            mOwnerPid = ownerPid;
        }

        @Override
        public void binderDied() {
            LcdPowerSaveService.this.handlePowerSaveRequestDeath(this);
        }

        public boolean hasSameProperties(int flags, String tag, int ownerUid, int ownerPid) {
            return mFlags == flags
                    && mTag.equals(tag)
                    && mOwnerUid == ownerUid
                    && mOwnerPid == ownerPid;
        }

        public void updateProperties(int flags, String tag, String packageName,
                int ownerUid, int ownerPid) {
            if (!mPackageName.equals(packageName)) {
                throw new IllegalStateException("Existing power save request package name changed: "
                        + mPackageName + " to " + packageName);
            }
            if (mOwnerUid != ownerUid) {
                throw new IllegalStateException("Existing power save request uid changed: "
                        + mOwnerUid + " to " + ownerUid);
            }
            if (mOwnerPid != ownerPid) {
                throw new IllegalStateException("Existing power save request pid changed: "
                        + mOwnerPid + " to " + ownerPid);
            }
            mFlags = flags;
            mTag = tag;
        }
    }

    private final class LocalService extends LcdPowerSaveInternal {
        @Override
        public void userActivity(long eventTime, int event) {
            mLcdPowerSave.userActivity(eventTime, event);
        }

        @Override
        public boolean interceptPowerKeyBeforeQueueingWhenLcdOff(KeyEvent event, int policyFlags) {
            return mLcdPowerSave.interceptPowerKeyBeforeQueueingWhenLcdOff(event, policyFlags);
        }

        @Override
        public void interceptProximityWhenLcdOn() {
            mLcdPowerSave.interceptProximityWhenLcdOn();
        }
    }
}
