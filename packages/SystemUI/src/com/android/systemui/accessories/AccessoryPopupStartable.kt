/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.accessories

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BluetoothController
import java.text.NumberFormat
import java.util.concurrent.Executor
import javax.inject.Inject
import lineageos.accessories.AccessoriesInterface
import lineageos.accessories.AccessoryInfo
import lineageos.accessories.AccessoryStatus
import lineageos.accessories.AccessoryType
import lineageos.accessories.BatteryInfo
import lineageos.accessories.IAccessoriesCallback
import lineageos.accessories.IAccessory
import lineageos.accessories.IAccessoryCallback
import lineageos.accessories.IAccessoryPairingCallback
import lineageos.accessories.PairingStatus
import lineageos.app.LineageContextConstants

/**
 * Shows a popup when a Lineage accessory gets connected, starts charging or runs low, and lets the
 * user act on its pairing.
 */
@SysUISingleton
class AccessoryPopupStartable
@Inject
constructor(
    @Application private val context: Context,
    @Background private val bgExecutor: Executor,
    @Main private val mainExecutor: Executor,
    private val popup: AccessoryPopup,
    private val activityStarter: ActivityStarter,
    private val bluetoothController: BluetoothController,
) : CoreStartable {
    // Only accessed on the background executor
    private val trackers = mutableMapOf<IBinder, Tracker>()

    private val accessoriesCallback =
        object : IAccessoriesCallback.Stub() {
            override fun onAccessoryAdded(accessory: IAccessory) {
                bgExecutor.execute { add(accessory) }
            }

            override fun onAccessoryRemoved(accessory: IAccessory) {
                bgExecutor.execute { remove(accessory) }
            }
        }

    override fun start() {
        if (
            !context.packageManager.hasSystemFeature(LineageContextConstants.Features.ACCESSORIES)
        ) {
            return
        }
        bgExecutor.execute {
            AccessoriesInterface.getInstance(context).registerCallback(accessoriesCallback)
        }
    }

    private fun add(accessory: IAccessory) {
        val binder = accessory.asBinder()
        if (binder in trackers) {
            return
        }

        // Seed the current state, so that the initial callbacks don't look like changes
        val tracker =
            try {
                Tracker(accessory, State(accessory.accessoryInfo, accessory.accessoryStatus))
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to get the accessory state", e)
                return
            }
        trackers[binder] = tracker

        try {
            accessory.registerCallback(tracker)
            accessory.registerPairingCallback(tracker.pairingCallback)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to register the accessory callbacks", e)
            trackers.remove(binder)
        }
    }

    private fun remove(accessory: IAccessory) {
        val tracker = trackers.remove(accessory.asBinder()) ?: return
        try {
            accessory.unregisterCallback(tracker)
            accessory.unregisterPairingCallback(tracker.pairingCallback)
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to unregister the accessory callbacks", e)
        }
        val id = tracker.state.info.id
        mainExecutor.execute { popup.dismiss(id) }
    }

    private fun onStateChanged(tracker: Tracker, old: State, new: State) {
        val id = new.info.id
        val content = createContent(tracker, new)
        when {
            !new.isConnected || (old.isCharging && !new.isCharging) ->
                mainExecutor.execute { popup.dismiss(id) }
            (!old.isConnected && new.isConnected) ||
                (!old.isCharging && new.isCharging) ||
                old.pairingStatus != new.pairingStatus ||
                isLowBatteryReached(old, new) -> mainExecutor.execute { popup.show(id, content) }
            else -> mainExecutor.execute { popup.update(id, content) }
        }
    }

    private fun isLowBatteryReached(old: State, new: State): Boolean {
        val oldLevel = old.level ?: return false
        return oldLevel > LOW_BATTERY_LEVEL && new.isBatteryLow
    }

    private fun createContent(tracker: Tracker, state: State): AccessoryPopupContent {
        val icon =
            if (state.info.type == AccessoryType.KEYBOARD) R.drawable.ic_keyboard
            else R.drawable.ic_accessory_stylus
        val title = state.info.displayName

        fun pairingContent(subtitle: Int, isError: Boolean, onClick: (() -> Unit)?) =
            AccessoryPopupContent(
                icon = icon,
                title = title,
                subtitle = context.getString(subtitle),
                levelText = null,
                isCharging = false,
                isError = isError,
                durationMs = if (onClick != null) ACTION_DURATION_MS else PAIRING_DURATION_MS,
                onClick = onClick,
            )

        when (state.pairingStatus) {
            PairingStatus.PAIRING ->
                return pairingContent(R.string.accessory_popup_connecting, false, null)
            PairingStatus.FAILED ->
                return pairingContent(
                    R.string.accessory_popup_pairing_failed,
                    true,
                    tracker.retryPairing,
                )
            PairingStatus.BLUETOOTH_DISABLED ->
                return pairingContent(
                    R.string.accessory_popup_bluetooth_disabled,
                    false,
                    enableBluetooth,
                )
        }

        val level = state.level
        val subtitle =
            when {
                state.isCharging && level == FULL_LEVEL -> R.string.accessory_popup_fully_charged
                state.isCharging -> R.string.accessory_popup_charging
                state.isBatteryLow -> R.string.accessory_popup_battery_low
                else -> R.string.accessory_popup_connected
            }
        return AccessoryPopupContent(
            icon = icon,
            title = title,
            subtitle = context.getString(subtitle),
            levelText =
                level?.let { NumberFormat.getPercentInstance().format(it / FULL_LEVEL.toDouble()) },
            isCharging = state.isCharging,
            isError = state.isBatteryLow,
            durationMs = if (state.isBatteryLow) LOW_BATTERY_DURATION_MS else DURATION_MS,
        )
    }

    // Pairing a new input device must not be possible from the lockscreen
    private fun runUnlocked(action: () -> Unit) {
        activityStarter.executeRunnableDismissingKeyguard(
            { action() },
            /* cancelAction= */ null,
            /* dismissShade= */ false,
            /* afterKeyguardGone= */ true,
            /* deferred= */ false,
        )
    }

    private val enableBluetooth: () -> Unit = {
        runUnlocked { bluetoothController.setBluetoothEnabled(true) }
    }

    private data class State(
        val info: AccessoryInfo,
        val status: Byte,
        val battery: BatteryInfo? = null,
        val pairingStatus: Byte = PairingStatus.IDLE,
    ) {
        val isConnected = status != AccessoryStatus.DISCONNECTED
        val isCharging = info.isCharging
        val level = battery?.levelPercentage?.takeIf { it in 0..FULL_LEVEL }
        val isBatteryLow = !isCharging && level != null && level <= LOW_BATTERY_LEVEL
    }

    private inner class Tracker(private val accessory: IAccessory, initialState: State) :
        IAccessoryCallback.Stub() {
        // Only accessed on the background executor
        var state = initialState
            private set

        val pairingCallback =
            object : IAccessoryPairingCallback.Stub() {
                override fun onPairingStatusUpdated(status: Byte) {
                    bgExecutor.execute { update(state.copy(pairingStatus = status)) }
                }
            }

        val retryPairing: () -> Unit = {
            runUnlocked {
                bgExecutor.execute {
                    try {
                        accessory.retryPairing()
                    } catch (e: RemoteException) {
                        Log.e(TAG, "Failed to retry pairing", e)
                    }
                }
            }
        }

        override fun onAccessoryInfoUpdated(accessoryInfo: AccessoryInfo?) {
            accessoryInfo ?: return
            bgExecutor.execute { update(state.copy(info = accessoryInfo)) }
        }

        override fun onAccessoryStatusUpdated(status: Byte) {
            bgExecutor.execute { update(state.copy(status = status)) }
        }

        override fun onBatteryInfoUpdated(batteryInfo: BatteryInfo?) {
            bgExecutor.execute { update(state.copy(battery = batteryInfo)) }
        }

        private fun update(newState: State) {
            if (this !in trackers.values) {
                return
            }
            val oldState = state
            state = newState
            onStateChanged(this, oldState, newState)
        }
    }

    companion object {
        private const val TAG = "AccessoryPopupStartable"

        private const val FULL_LEVEL = 100

        // Matches the USI stylus low battery threshold
        private const val LOW_BATTERY_LEVEL = 16

        private const val DURATION_MS = 2500L
        private const val LOW_BATTERY_DURATION_MS = 4000L
        private const val PAIRING_DURATION_MS = 30_000L
        private const val ACTION_DURATION_MS = 8000L
    }
}
