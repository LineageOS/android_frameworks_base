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

package com.android.systemui.biometrics

import android.content.Context
import android.hardware.biometrics.common.AuthenticateReason
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.lineageos.platform.internal.R
import org.mockito.Mockito

class FakeFingerprintInteractiveToAuthProvider : FingerprintInteractiveToAuthProvider {
    override val enabledForCurrentUser: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val mockContext = Mockito.mock(Context::class.java)

    private val defaultValue = if (mockContext.resources.getBoolean(
                    R.bool.config_fingerprintWakeAndUnlock)) {
        1
    } else {
        0
    }

    private val userIdToExtension = mutableMapOf<Int, AuthenticateReason.Vendor>()

    override fun getVendorExtension(userId: Int): AuthenticateReason.Vendor? =
            userIdToExtension[userId]

    override fun isEnabled(userId: Int): Boolean {
        var value = Settings.Secure.getIntForUser(
                mockContext.contentResolver, Settings.Secure.SFPS_PERFORMANT_AUTH_ENABLED,
                -1,
                userId,
        )
        if (value == -1) {
            value = defaultValue
        }
        return value == 0
    }

    fun setVendorExtension(userId: Int, extension: AuthenticateReason.Vendor) {
        userIdToExtension[userId] = extension
    }
}
