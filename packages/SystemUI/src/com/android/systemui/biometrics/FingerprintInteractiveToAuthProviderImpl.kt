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

package com.android.systemui.biometrics

import android.content.Context
import android.database.ContentObserver
import android.hardware.biometrics.common.AuthenticateReason
import android.provider.Settings
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn

class FingerprintInteractiveToAuthProviderImpl
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val context: Context,
    private val secureSettings: SecureSettings,
    selectedUserInteractor: SelectedUserInteractor,
) : FingerprintInteractiveToAuthProvider {

    override fun getVendorExtension(userId: Int): AuthenticateReason.Vendor? = null

    private val defaultValue =
        if (context
            .getResources()
            .getBoolean(org.lineageos.platform.internal.R.bool.config_fingerprintWakeAndUnlock))
            1
        else 0

    override val enabledForCurrentUser =
        selectedUserInteractor.selectedUser
            .flatMapLatest { currentUserId ->
                val getCurrentSettingValue = { isEnabled(currentUserId) }
                conflatedCallbackFlow {
                    val callback =
                        object : ContentObserver(null) {
                            override fun onChange(selfChange: Boolean) {
                                trySend(getCurrentSettingValue())
                            }
                        }
                    secureSettings.registerContentObserver(
                        Settings.Secure.SFPS_PERFORMANT_AUTH_ENABLED, true, callback)
                    trySend(getCurrentSettingValue())
                    awaitClose { secureSettings.unregisterContentObserver(callback) }
                }
            }
            .flowOn(backgroundDispatcher)

    private fun isEnabled(userId: Int): Boolean {
        var value =
            Settings.Secure.getIntForUser(
                context.contentResolver,
                Settings.Secure.SFPS_PERFORMANT_AUTH_ENABLED,
                -1,
                userId,
            )
        if (value == -1) {
            value = defaultValue
            Settings.Secure.putIntForUser(
                context.contentResolver,
                Settings.Secure.SFPS_PERFORMANT_AUTH_ENABLED,
                value,
                userId,
            )
        }
        return value == 0
    }
}
