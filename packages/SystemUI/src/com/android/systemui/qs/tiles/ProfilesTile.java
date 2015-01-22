/*
 * Copyright (C) 2015 The CyanogenMod Project
 *               2017-2018 The LineageOS Project
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

package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import lineageos.app.Profile;
import lineageos.app.ProfileManager;
import lineageos.providers.LineageSettings;

import org.lineageos.internal.logging.LineageMetricsLogger;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

public class ProfilesTile extends QSTileImpl<State> {

    private static final Intent PROFILES_SETTINGS =
            new Intent("org.lineageos.lineageparts.PROFILES_SETTINGS");

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_profiles);

    private boolean mListening;

    private final ActivityStarter mActivityStarter;
    private final DialogLaunchAnimator mDialogLaunchAnimator;
    private final KeyguardDismissUtil mKeyguardDismissUtil;
    private final KeyguardStateController mKeyguardStateController;
    private final ProfileManager mProfileManager;
    private final ProfileAdapter mAdapter;
    private final ProfilesObserver mObserver;
    private final Callback mCallback = new Callback();

    @Inject
    public ProfilesTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            DialogLaunchAnimator dialogLaunchAnimator,
            KeyguardDismissUtil keyguardDismissUtil,
            KeyguardStateController keyguardStateController
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mActivityStarter = activityStarter;
        mDialogLaunchAnimator = dialogLaunchAnimator;
        mKeyguardDismissUtil = keyguardDismissUtil;
        mKeyguardStateController = keyguardStateController;
        mProfileManager = ProfileManager.getInstance(mContext);
        mAdapter = new ProfileAdapter();
        mObserver = new ProfilesObserver(mHandler);
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_profiles_label);
    }

    @Override
    public Intent getLongClickIntent() {
        return PROFILES_SETTINGS;
    }

    @Override
    protected void handleClick(@Nullable View view) {
        mUiHandler.post(() -> mKeyguardDismissUtil.executeWhenUnlocked(() -> {
            ProfilesDialog dialog = new ProfilesDialog(mContext);
            if (view != null && !mKeyguardStateController.isShowing()) {
                mDialogLaunchAnimator.showFromView(dialog, view);
            } else {
                dialog.show();
            }
            return false;
        }, false /* requiresShadeOpen */, true /* afterKeyguardDone */));
    }

    @Override
    protected void handleLongClick(@Nullable View view) {
        mActivityStarter.postStartActivityDismissingKeyguard(PROFILES_SETTINGS, 0);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.icon = mIcon;
        state.label = mContext.getString(R.string.quick_settings_profiles_label);
        if (profilesEnabled()) {
            state.secondaryLabel = mProfileManager.getActiveProfile().getName();
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_profiles, state.label);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.secondaryLabel = null;
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_profiles_off);
            state.state = Tile.STATE_INACTIVE;
        }
        state.dualTarget = true;
    }

    private void setProfilesEnabled(Boolean enabled) {
        LineageSettings.System.putInt(mContext.getContentResolver(),
                LineageSettings.System.SYSTEM_PROFILES_ENABLED, enabled ? 1 : 0);
    }

    private boolean profilesEnabled() {
        return LineageSettings.System.getInt(mContext.getContentResolver(),
                LineageSettings.System.SYSTEM_PROFILES_ENABLED, 1) == 1;
    }

    @Override
    public int getMetricsCategory() {
        return LineageMetricsLogger.TILE_PROFILES;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mObserver.startObserving();
            final IntentFilter filter = new IntentFilter();
            filter.addAction(ProfileManager.INTENT_ACTION_PROFILE_SELECTED);
            filter.addAction(ProfileManager.INTENT_ACTION_PROFILE_UPDATED);
            mContext.registerReceiver(mReceiver, filter);
            mKeyguardStateController.addCallback(mCallback);
            refreshState();
        } else {
            mObserver.endObserving();
            mContext.unregisterReceiver(mReceiver);
            mKeyguardStateController.removeCallback(mCallback);
        }
    }

    private final class Callback implements KeyguardStateController.Callback {
        @Override
        public void onKeyguardShowingChanged() {
            refreshState();
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ProfileManager.INTENT_ACTION_PROFILE_SELECTED.equals(intent.getAction())
                    || ProfileManager.INTENT_ACTION_PROFILE_UPDATED.equals(intent.getAction())) {
                refreshState();
            }
        }
    };

    public class ProfileAdapter extends RecyclerView.Adapter<ProfileViewHolder> {
        private List<Profile> mProfilesList;

        ProfileAdapter() {
            super();
            reload();
        }

        @NonNull
        @Override
        public ProfileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View holderView = LayoutInflater.from(mContext).inflate(
                    android.R.layout.simple_list_item_single_choice, parent, false);
            return new ProfileViewHolder(holderView);
        }

        @Override
        public void onBindViewHolder(@NonNull ProfileViewHolder holder, int position) {
            holder.onBind(mProfilesList.get(position));
        }

        @Override
        public int getItemCount() {
            return mProfilesList.size();
        }

        public void reload() {
            mProfilesList = Arrays.asList(mProfileManager.getProfiles());
            notifyDataSetChanged();
        }
    }

    private class ProfileViewHolder extends RecyclerView.ViewHolder {
        private CheckedTextView mCheckedTextView;

        public ProfileViewHolder(@NonNull View itemView) {
            super(itemView);
            mCheckedTextView = itemView.findViewById(android.R.id.text1);
        }

        void onBind(@NonNull Profile profile) {
            mCheckedTextView.setText(profile.getName());
            mCheckedTextView.setEnabled(profilesEnabled());
            mCheckedTextView.setChecked(
                    profile.getUuid().equals(mProfileManager.getActiveProfile().getUuid()));
            mCheckedTextView.setOnClickListener(v -> {
                mProfileManager.setActiveProfile(profile.getUuid());
                mAdapter.notifyDataSetChanged();
            });
        }
    }

    private class ProfilesDialog extends SystemUIDialog {
        public ProfilesDialog(Context context) {
            super(context);
            mAdapter.reload();
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            Window window = getWindow();

            window.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS);

            window.setGravity(Gravity.CENTER);
            setTitle(R.string.screenrecord_name);

            setContentView(R.layout.profiles_dialog);

            Switch profilesToggle = findViewById(R.id.toggle);
            profilesToggle.setChecked(profilesEnabled());
            profilesToggle.setOnCheckedChangeListener(
                    (buttonView, isChecked) -> setProfilesEnabled(isChecked));

            RecyclerView recyclerView = findViewById(R.id.list_layout);
            recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
            recyclerView.setAdapter(mAdapter);

            Button doneButton = findViewById(R.id.done_button);
            doneButton.setOnClickListener(v -> dismiss());
        }
    }

    private class ProfilesObserver extends ContentObserver {
        public ProfilesObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
            mUiHandler.post(mAdapter::notifyDataSetChanged);
        }

        public void startObserving() {
            mContext.getContentResolver().registerContentObserver(
                    LineageSettings.System.getUriFor(
                            LineageSettings.System.SYSTEM_PROFILES_ENABLED), false, this);
        }

        public void endObserving() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
    }
}
