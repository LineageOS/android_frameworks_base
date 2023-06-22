/*
 * Copyright (C) 2023 ArrowOS
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

package com.android.systemui.biometrics;

import android.content.Context;
import android.provider.Settings;

import javax.inject.Inject;

public class FingerprintInteractiveToAuthProviderImpl implements
        FingerprintInteractiveToAuthProvider {

    private final Context mContext;
    private final int mDefaultValue;

    @Inject
    public FingerprintInteractiveToAuthProviderImpl(Context context) {
        mContext = context;
        mDefaultValue = context.getResources().getBoolean(
                org.lineageos.platform.internal.R.bool.config_fingerprintWakeAndUnlock) ? 1 : 0;
    }

    public boolean isEnabled(int userId) {
        int value = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SFPS_PERFORMANT_AUTH_ENABLED, -1, userId);
        if (value == -1) {
            value = mDefaultValue;
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SFPS_PERFORMANT_AUTH_ENABLED, value, userId);
        }
        return value == 0;
    }
}
