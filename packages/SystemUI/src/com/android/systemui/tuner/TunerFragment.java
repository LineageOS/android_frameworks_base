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
 * limitations under the License.
 */
package com.android.systemui.tuner;

import android.annotation.SuppressLint;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Build;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.res.R;
import com.android.systemui.shared.plugins.PluginPrefs;
import com.android.tools.r8.keepanno.annotations.KeepTarget;
import com.android.tools.r8.keepanno.annotations.UsesReflection;

public class TunerFragment extends PreferenceFragment {

    private static final String TAG = "TunerFragment";

    private static final String KEY_BATTERY_PCT = "battery_pct";
    private static final String KEY_PLUGINS = "plugins";
    private static final CharSequence KEY_DOZE = "doze";

    public static final String SETTING_SEEN_TUNER_WARNING = "seen_tuner_warning";

    private static final String WARNING_TAG = "tuner_warning";
    private static final String[] DEBUG_ONLY = new String[] {
            "nav_bar",
            "lockscreen",
            "picture_in_picture",
    };

    // We are the only ones who ever call this constructor, so don't worry about the warning
    @SuppressLint("ValidFragment")
    public TunerFragment() {
        super();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    // aapt doesn't generate keep rules for android:fragment references in <Preference> tags, so
    // explicitly declare references per usage in `R.xml.tuner_prefs`. See b/120445169.
    @UsesReflection({
        @KeepTarget(classConstant = LockscreenFragment.class),
        @KeepTarget(classConstant = NavBarTuner.class),
        @KeepTarget(classConstant = PluginFragment.class),
    })
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.tuner_prefs);
        if (!PluginPrefs.hasPlugins(getContext())) {
            getPreferenceScreen().removePreference(findPreference(KEY_PLUGINS));
        }
        if (!alwaysOnAvailable()) {
            getPreferenceScreen().removePreference(findPreference(KEY_DOZE));
        }
        if (!Build.IS_DEBUGGABLE) {
            for (int i = 0; i < DEBUG_ONLY.length; i++) {
                Preference preference = findPreference(DEBUG_ONLY[i]);
                if (preference != null) getPreferenceScreen().removePreference(preference);
            }
        }
    }

    private boolean alwaysOnAvailable() {
        return new AmbientDisplayConfiguration(getContext()).alwaysOnAvailable();
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().setTitle(R.string.system_ui_tuner);

        MetricsLogger.visibility(getContext(), MetricsEvent.TUNER, true);
    }

    @Override
    public void onPause() {
        super.onPause();

        MetricsLogger.visibility(getContext(), MetricsEvent.TUNER, false);
    }
}
