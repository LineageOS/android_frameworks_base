/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.systemstatusicons.bluetooth.ui.viewmodel

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.bluetooth.domain.interactor.BluetoothConnectionStatusInteractor
import com.android.systemui.statusbar.systemstatusicons.SystemStatusIconsInCompose
import com.android.systemui.statusbar.systemstatusicons.ui.viewmodel.SystemStatusIconViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * View model for the bluetooth connected system status icon. Emits a bluetooth connected icon
 * (with battery level when the connected device reports one) when a device is connected. Null
 * otherwise.
 */
class BluetoothIconViewModel
@AssistedInject
constructor(
    @Assisted context: Context,
    interactor: BluetoothConnectionStatusInteractor,
    private val bluetoothController: BluetoothController,
) : SystemStatusIconViewModel.Default, HydratedActivatable() {
    init {
        SystemStatusIconsInCompose.expectInNewMode()
    }

    override val slotName = context.getString(com.android.internal.R.string.status_bar_bluetooth)

    override val visible: Boolean by
        interactor.isBluetoothConnected.hydratedStateOf(traceName = null, initialValue = false)

    @get:DrawableRes
    private val iconRes: Int by
        callbackFlow {
                val callback =
                    object : BluetoothController.Callback {
                        override fun onBluetoothStateChange(enabled: Boolean) {
                            trySend(bluetoothController.batteryLevel)
                        }

                        override fun onBluetoothDevicesChanged() {
                            trySend(bluetoothController.batteryLevel)
                        }
                    }
                bluetoothController.addCallback(callback)
                trySend(bluetoothController.batteryLevel)
                awaitClose { bluetoothController.removeCallback(callback) }
            }
            .map(::iconForBatteryLevel)
            .distinctUntilChanged()
            .hydratedStateOf(
                traceName = null,
                initialValue = R.drawable.stat_sys_data_bluetooth_connected,
            )

    override val icon: Icon?
        get() =
            if (visible) {
                Icon.Resource(
                    resId = iconRes,
                    contentDescription =
                        ContentDescription.Resource(R.string.accessibility_bluetooth_connected),
                )
            } else {
                null
            }

    @AssistedFactory
    interface Factory {
        fun create(context: Context): BluetoothIconViewModel
    }

    companion object {
        @JvmStatic
        @DrawableRes
        fun iconForBatteryLevel(level: Int): Int =
            when {
                level == 100 -> R.drawable.stat_sys_data_bluetooth_connected_battery_9
                level >= 90 -> R.drawable.stat_sys_data_bluetooth_connected_battery_8
                level >= 80 -> R.drawable.stat_sys_data_bluetooth_connected_battery_7
                level >= 70 -> R.drawable.stat_sys_data_bluetooth_connected_battery_6
                level >= 60 -> R.drawable.stat_sys_data_bluetooth_connected_battery_5
                level >= 50 -> R.drawable.stat_sys_data_bluetooth_connected_battery_4
                level >= 40 -> R.drawable.stat_sys_data_bluetooth_connected_battery_3
                level >= 30 -> R.drawable.stat_sys_data_bluetooth_connected_battery_2
                level >= 20 -> R.drawable.stat_sys_data_bluetooth_connected_battery_1
                level >= 10 -> R.drawable.stat_sys_data_bluetooth_connected_battery_0
                else -> R.drawable.stat_sys_data_bluetooth_connected
            }
    }
}
