/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.tuner;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.systemui.DemoMode;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.leak.LeakDetector;

import lineageos.providers.LineageSettings;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;


public class TunerServiceImpl extends TunerService {

    private static final String TUNER_VERSION = "sysui_tuner_version";

    private static final int CURRENT_TUNER_VERSION = 1;

    private final Observer mObserver = new Observer();
    // Map of Uris we listen on to their settings keys.
    private final ArrayMap<Uri, String> mListeningUris = new ArrayMap<>();
    // Map of settings keys to the listener.
    private final HashMap<String, Set<Tunable>> mTunableLookup = new HashMap<>();
    // Set of all tunables, used for leak detection.
    private final HashSet<Tunable> mTunables = LeakDetector.ENABLED ? new HashSet<>() : null;
    private final Context mContext;

    private ContentResolver mContentResolver;
    private int mCurrentUser;
    private CurrentUserTracker mUserTracker;

    public TunerServiceImpl(Context context) {
        mContext = context;
        mContentResolver = mContext.getContentResolver();

        for (UserInfo user : UserManager.get(mContext).getUsers()) {
            mCurrentUser = user.getUserHandle().getIdentifier();
            if (getValue(TUNER_VERSION, 0) != CURRENT_TUNER_VERSION) {
                upgradeTuner(getValue(TUNER_VERSION, 0), CURRENT_TUNER_VERSION);
            }
        }

        mCurrentUser = ActivityManager.getCurrentUser();
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mCurrentUser = newUserId;
                reloadAll();
                reregisterAll();
            }
        };
        mUserTracker.startTracking();
    }

    @Override
    public void destroy() {
        mUserTracker.stopTracking();
    }

    private void upgradeTuner(int oldVersion, int newVersion) {
        if (oldVersion < 1) {
            String blacklistStr = getValue(StatusBarIconController.ICON_BLACKLIST);
            if (blacklistStr != null) {
                ArraySet<String> iconBlacklist =
                        StatusBarIconController.getIconBlacklist(blacklistStr);

                iconBlacklist.add("rotate");
                iconBlacklist.add("headset");

                Settings.Secure.putStringForUser(mContentResolver,
                        StatusBarIconController.ICON_BLACKLIST,
                        TextUtils.join(",", iconBlacklist), mCurrentUser);
            }
        }
        setValue(TUNER_VERSION, newVersion);
    }

    private boolean isLineageSystem(String key) {
        return key.startsWith("lineagesystem:");
    }

    private boolean isLineageSecure(String key) {
        return key.startsWith("lineagesecure:");
    }

    private boolean isSystem(String key) {
        return key.startsWith("system:");
    }

    private String chomp(String key) {
        return key.replaceFirst("^(lineagesecure|lineagesystem|system):", "");
    }

    @Override
    public String getValue(String setting) {
        if (isLineageSecure(setting)) {
            return LineageSettings.Secure.getStringForUser(
                    mContentResolver, chomp(setting), mCurrentUser);
        } else if (isLineageSystem(setting)) {
            return LineageSettings.System.getStringForUser(
                    mContentResolver, chomp(setting), mCurrentUser);
        } else if (isSystem(setting)) {
            return Settings.System.getStringForUser(
                    mContentResolver, chomp(setting), mCurrentUser);
        } else {
            return Settings.Secure.getStringForUser(mContentResolver, setting, mCurrentUser);
        }
    }

    @Override
    public void setValue(String setting, String value) {
        if (isLineageSecure(setting)) {
            LineageSettings.Secure.putStringForUser(
                    mContentResolver, chomp(setting), value, mCurrentUser);
        } else if (isLineageSystem(setting)) {
            LineageSettings.System.putStringForUser(
                    mContentResolver, chomp(setting), value, mCurrentUser);
        } else if (isSystem(setting)) {
            Settings.System.putStringForUser(
                    mContentResolver, chomp(setting), value, mCurrentUser);
        } else {
            Settings.Secure.putStringForUser(mContentResolver, setting, value, mCurrentUser);
        }
    }

    @Override
    public int getValue(String setting, int def) {
        if (isLineageSecure(setting)) {
            return LineageSettings.Secure.getIntForUser(
                    mContentResolver, chomp(setting), def, mCurrentUser);
        } else if (isLineageSystem(setting)) {
            return LineageSettings.System.getIntForUser(
                    mContentResolver, chomp(setting), def, mCurrentUser);
        } else if (isSystem(setting)) {
            return Settings.System.getIntForUser(
                    mContentResolver, chomp(setting), def, mCurrentUser);
        } else {
            return Settings.Secure.getIntForUser(mContentResolver, setting, def, mCurrentUser);
        }
    }

    @Override
    public String getValue(String setting, String def) {
        String ret;
        if (isLineageSecure(setting)) {
            ret = LineageSettings.Secure.getStringForUser(
                    mContentResolver, chomp(setting), mCurrentUser);
        } else if (isLineageSystem(setting)) {
            ret = LineageSettings.System.getStringForUser(
                    mContentResolver, chomp(setting), mCurrentUser);
        } else if (isSystem(setting)) {
            ret = Settings.System.getStringForUser(
                    mContentResolver, chomp(setting), mCurrentUser);
        } else {
            ret = Secure.getStringForUser(mContentResolver, setting, mCurrentUser);
        }
        if (ret == null) return def;
        return ret;
    }

    @Override
    public void setValue(String setting, int value) {
        if (isLineageSecure(setting)) {
            LineageSettings.Secure.putIntForUser(
                    mContentResolver, chomp(setting), value, mCurrentUser);
        } else if (isLineageSystem(setting)) {
            LineageSettings.System.putIntForUser(
                    mContentResolver, chomp(setting), value, mCurrentUser);
        } else if (isSystem(setting)) {
            Settings.System.putIntForUser(mContentResolver, chomp(setting), value, mCurrentUser);
        } else {
            Settings.Secure.putIntForUser(mContentResolver, setting, value, mCurrentUser);
        }
    }

    @Override
    public void addTunable(Tunable tunable, String... keys) {
        for (String key : keys) {
            addTunable(tunable, key);
        }
    }

    private void addTunable(Tunable tunable, String key) {
        if (!mTunableLookup.containsKey(key)) {
            mTunableLookup.put(key, new ArraySet<Tunable>());
        }
        mTunableLookup.get(key).add(tunable);
        if (LeakDetector.ENABLED) {
            mTunables.add(tunable);
            Dependency.get(LeakDetector.class).trackCollection(mTunables, "TunerService.mTunables");
        }
        final Uri uri;
        if (isLineageSecure(key)) {
            uri = LineageSettings.Secure.getUriFor(chomp(key));
        } else if (isLineageSystem(key)) {
            uri = LineageSettings.System.getUriFor(chomp(key));
        } else if (isSystem(key)) {
            uri = Settings.System.getUriFor(chomp(key));
        } else {
            uri = Settings.Secure.getUriFor(key);
        }
        if (!mListeningUris.containsKey(uri)) {
            mListeningUris.put(uri, key);
            mContentResolver.registerContentObserver(uri, false, mObserver, mCurrentUser);
        }
        // Send the first state.
        String value = getValue(key);
        tunable.onTuningChanged(key, value);
    }

    @Override
    public void removeTunable(Tunable tunable) {
        for (Set<Tunable> list : mTunableLookup.values()) {
            list.remove(tunable);
        }
        if (LeakDetector.ENABLED) {
            mTunables.remove(tunable);
        }
    }

    protected void reregisterAll() {
        if (mListeningUris.size() == 0) {
            return;
        }
        mContentResolver.unregisterContentObserver(mObserver);
        for (Uri uri : mListeningUris.keySet()) {
            mContentResolver.registerContentObserver(uri, false, mObserver, mCurrentUser);
        }
    }

    private void reloadSetting(Uri uri) {
        String key = mListeningUris.get(uri);
        Set<Tunable> tunables = mTunableLookup.get(key);
        if (tunables == null) {
            return;
        }
        String value = getValue(key);
        for (Tunable tunable : tunables) {
            tunable.onTuningChanged(key, value);
        }
    }

    private void reloadAll() {
        for (String key : mTunableLookup.keySet()) {
            String value = getValue(key);
            for (Tunable tunable : mTunableLookup.get(key)) {
                tunable.onTuningChanged(key, value);
            }
        }
    }

    @Override
    public void clearAll() {
        // A couple special cases.
        Settings.Global.putString(mContentResolver, DemoMode.DEMO_MODE_ALLOWED, null);
        Intent intent = new Intent(DemoMode.ACTION_DEMO);
        intent.putExtra(DemoMode.EXTRA_COMMAND, DemoMode.COMMAND_EXIT);
        mContext.sendBroadcast(intent);

        for (String key : mTunableLookup.keySet()) {
            setValue(key, null);
        }
    }

    private class Observer extends ContentObserver {
        public Observer() {
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (userId == ActivityManager.getCurrentUser()) {
                reloadSetting(uri);
            }
        }
    }
}
