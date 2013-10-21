/*
 * Copyright (C) 2014 The CyanogenMod Project
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

package com.android.systemui.statusbar.policy;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A controller to manage changes to superuser-related states and update the views accordingly.
 */
public class SuControllerImpl implements SuController, AppOpsManager.OnOpActiveChangedListener {
    private static final String TAG = "SuControllerImpl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int[] SU_OPS = new int[] { AppOpsManager.OP_SU };

    private ArrayList<Callback> mCallbacks = new ArrayList<Callback>();

    private AppOpsManager mAppOpsManager;
    private Set<String> mActivePackages = new HashSet<>();
    private Handler mHandler = new Handler();

    public SuControllerImpl(Context context) {
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
    }

    @Override
    public void addCallback(Callback callback) {
        synchronized (mCallbacks) {
            if (mCallbacks.isEmpty()) {
                mAppOpsManager.startWatchingActive(SU_OPS, this);

                synchronized (mActivePackages) {
                    mActivePackages.clear();
                    initActivePackagesLocked();
                }
            }
            mCallbacks.add(callback);
            callback.onSuSessionsChanged(mActivePackages.size());
        }
    }

    @Override
    public void removeCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
            if (mCallbacks.isEmpty()) {
                mAppOpsManager.stopWatchingActive(this);
            }
        }
    }

    @Override
    public int getSessionCount() {
        synchronized (mActivePackages) {
            return mActivePackages.size();
        }
    }

    private void initActivePackagesLocked() {

        List<AppOpsManager.PackageOps> packages = mAppOpsManager.getPackagesForOps(SU_OPS);
        if (packages != null) {
            for (AppOpsManager.PackageOps ops : packages) {
                if (mAppOpsManager.isOperationActive(AppOpsManager.OP_SU,
                        ops.getUid(), ops.getPackageName())) {
                    mActivePackages.add(ops.getPackageName());
                }
            }
        }
    }

    private void fireCallbacks(int sessionCount) {
        synchronized (mCallbacks) {
            for (Callback callback : mCallbacks) {
                callback.onSuSessionsChanged(sessionCount);
            }
        }
    }

    @Override
    public void onOpActiveChanged(int op, int uid, String packageName, boolean active) {
        if (DEBUG) Log.d(TAG, "SU active changed for " + packageName + " to " + active);
        int oldCount, newCount;
        synchronized (mActivePackages) {
            oldCount = mActivePackages.size();
            if (active) {
                mActivePackages.add(packageName);
            } else {
                mActivePackages.remove(packageName);
            }
            newCount = mActivePackages.size();
        }
        if (oldCount != newCount) {
            mHandler.post(() -> fireCallbacks(newCount));
        }
    }
}
