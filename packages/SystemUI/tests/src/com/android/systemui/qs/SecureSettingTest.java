/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static org.junit.Assert.assertEquals;

import android.app.ActivityManager;
import android.os.Handler;
import android.provider.Settings.Secure;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.utils.os.FakeHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class SecureSettingTest extends SysuiTestCase {
    private SecureSetting mSetting;

    @Before
    public void setUp() throws Exception {
    }

    @Test
    public void testSecureSettingDefault() {
        Handler handler = new FakeHandler(TestableLooper.get(this).getLooper());
        SecureSetting setting = new SecureSetting(mContext, handler, Secure.DOZE_ENABLED,
                ActivityManager.getCurrentUser(), 1) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                // Stub
            }
        };

        // Check default value before listening
        assertEquals(1, setting.getValue());

        // Start listening
        setting.setListening(true);

        // Check default value if setting is not set
        assertEquals(1, setting.getValue());

        // Check value after setting has been set
        setting.setValue(0);
        assertEquals(0, setting.getValue());
    }
}
