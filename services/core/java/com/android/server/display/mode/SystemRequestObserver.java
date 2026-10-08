/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.mode;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * SystemRequestObserver responsible for handling system requests to filter allowable display
 * modes
 */
class SystemRequestObserver {
    private static final String TAG = "SystemRequestObserver";

    private final VotesStorage mVotesStorage;

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            // noop, binderDied(@NonNull IBinder who) is overridden
        }
        @Override
        public void binderDied(@NonNull IBinder who) {
            Slog.d(TAG, "binder died: " + who);
            removeSystemRequestedVotes(who);
            who.unlinkToDeath(mDeathRecipient, 0);
        }
    };

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<IBinder, SparseArray<List<Integer>>> mDisplaysRestrictions = new HashMap<>();
    @GuardedBy("mLock")
    private final Map<IBinder, SparseArray<Float>> mDisplaysMaxRefreshRates = new HashMap<>();

    SystemRequestObserver(VotesStorage storage) {
        mVotesStorage = storage;
    }

    void requestDisplayModes(IBinder token, int displayId, @Nullable int[] modeIds) {
        if (modeIds == null) {
            removeSystemRequestedVote(token, displayId);
        } else {
            addSystemRequestedVote(token, displayId, modeIds);
        }
    }

    void requestMaxRefreshRate(IBinder token, int displayId, float maxRefreshRate) {
        if (maxRefreshRate == 0) {
            removeMaxRefreshRateVote(token, displayId);
        } else {
            addMaxRefreshRateVote(token, displayId, maxRefreshRate);
        }
    }

    private void addSystemRequestedVote(IBinder token, int displayId, @NonNull int[] modeIds) {
        try {
            boolean needLinkToDeath = false;
            List<Integer> modeIdsList = new ArrayList<>();
            for (int mode: modeIds) {
                modeIdsList.add(mode);
            }
            synchronized (mLock) {
                needLinkToDeath = !isTokenTrackedLocked(token);
                SparseArray<List<Integer>> modesByDisplay = mDisplaysRestrictions.get(token);
                if (modesByDisplay == null) {
                    modesByDisplay = new SparseArray<>();
                    mDisplaysRestrictions.put(token, modesByDisplay);
                }

                modesByDisplay.put(displayId, modeIdsList);
                updateStorageLocked(displayId);
            }
            if (needLinkToDeath) {
                Slog.d(TAG, "binder linking to death: " + token);
                token.linkToDeath(mDeathRecipient, 0);
            }
        } catch (RemoteException re) {
            Slog.d(TAG, "linking to death failed: " + token, re);
            removeSystemRequestedVotes(token);
        }
    }

    private void removeSystemRequestedVote(IBinder token, int displayId) {
        boolean needToUnlink = false;
        synchronized (mLock) {
            SparseArray<List<Integer>> modesByDisplay = mDisplaysRestrictions.get(token);
            if (modesByDisplay != null && modesByDisplay.size() > 0) {
                modesByDisplay.remove(displayId);
                needToUnlink = !isTokenTrackedLocked(token);
                updateStorageLocked(displayId);
            }
        }
        if (needToUnlink) {
            try {
                Slog.d(TAG, "binder unlinking to death: " + token);
                token.unlinkToDeath(mDeathRecipient, 0);
            } catch (NoSuchElementException e) {
                Slog.d(TAG, "unlinking to death failed: " + token, e);
            }
        }
    }

    private void addMaxRefreshRateVote(IBinder token, int displayId, float maxRefreshRate) {
        try {
            boolean needLinkToDeath = false;
            synchronized (mLock) {
                needLinkToDeath = !isTokenTrackedLocked(token);
                SparseArray<Float> ratesByDisplay = mDisplaysMaxRefreshRates.get(token);
                if (ratesByDisplay == null) {
                    ratesByDisplay = new SparseArray<>();
                    mDisplaysMaxRefreshRates.put(token, ratesByDisplay);
                }

                ratesByDisplay.put(displayId, maxRefreshRate);
                updateStorageLocked(displayId);
            }
            if (needLinkToDeath) {
                Slog.d(TAG, "binder linking to death: " + token);
                token.linkToDeath(mDeathRecipient, 0);
            }
        } catch (RemoteException re) {
            Slog.d(TAG, "linking to death failed: " + token, re);
            removeSystemRequestedVotes(token);
        }
    }

    private void removeMaxRefreshRateVote(IBinder token, int displayId) {
        boolean needToUnlink = false;
        synchronized (mLock) {
            SparseArray<Float> ratesByDisplay = mDisplaysMaxRefreshRates.get(token);
            if (ratesByDisplay != null && ratesByDisplay.size() > 0) {
                ratesByDisplay.remove(displayId);
                needToUnlink = !isTokenTrackedLocked(token);
                updateStorageLocked(displayId);
            }
        }
        if (needToUnlink) {
            try {
                Slog.d(TAG, "binder unlinking to death: " + token);
                token.unlinkToDeath(mDeathRecipient, 0);
            } catch (NoSuchElementException e) {
                Slog.d(TAG, "unlinking to death failed: " + token, e);
            }
        }
    }

    private void removeSystemRequestedVotes(IBinder token) {
        synchronized (mLock) {
            SparseArray<List<Integer>> removed = mDisplaysRestrictions.remove(token);
            if (removed != null) {
                for (int i = 0; i < removed.size(); i++) {
                    updateStorageLocked(removed.keyAt(i));
                }
            }
            SparseArray<Float> removedRates = mDisplaysMaxRefreshRates.remove(token);
            if (removedRates != null) {
                for (int i = 0; i < removedRates.size(); i++) {
                    updateStorageLocked(removedRates.keyAt(i));
                }
            }
        }
    }

    @GuardedBy("mLock")
    private boolean isTokenTrackedLocked(IBinder token) {
        SparseArray<List<Integer>> modesByDisplay = mDisplaysRestrictions.get(token);
        SparseArray<Float> ratesByDisplay = mDisplaysMaxRefreshRates.get(token);
        return (modesByDisplay != null && modesByDisplay.size() > 0)
                || (ratesByDisplay != null && ratesByDisplay.size() > 0);
    }

    @GuardedBy("mLock")
    private void updateStorageLocked(int displayId) {
        List<Integer> modeIds = new ArrayList<>();
        boolean[] modesFound = new boolean[1];

        mDisplaysRestrictions.forEach((key, value) -> {
            List<Integer> modesForDisplay = value.get(displayId);
            if (modesForDisplay != null) {
                if (!modesFound[0]) {
                    modeIds.addAll(modesForDisplay);
                    modesFound[0] = true;
                } else {
                    modeIds.retainAll(modesForDisplay);
                }
            }
        });

        float maxRefreshRate = Float.POSITIVE_INFINITY;
        for (SparseArray<Float> ratesByDisplay : mDisplaysMaxRefreshRates.values()) {
            maxRefreshRate = Math.min(maxRefreshRate,
                    ratesByDisplay.get(displayId, Float.POSITIVE_INFINITY));
        }

        List<Vote> votes = new ArrayList<>();
        if (modesFound[0]) {
            votes.add(Vote.forSupportedModes(modeIds));
        }
        if (maxRefreshRate != Float.POSITIVE_INFINITY) {
            votes.add(Vote.forPhysicalRefreshRates(0f, maxRefreshRate));
            votes.add(Vote.forRenderFrameRates(0f, maxRefreshRate));
        }
        mVotesStorage.updateVote(displayId, Vote.PRIORITY_SYSTEM_REQUESTED_MODES,
                Vote.forVotes(votes));
    }
}
