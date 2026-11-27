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
import com.android.systemui.res.R
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
import lineageos.app.LineageContextConstants

/** Shows a popup when a Lineage accessory gets connected, starts charging or runs low. */
@SysUISingleton
class AccessoryPopupStartable
@Inject
constructor(
    @Application private val context: Context,
    @Background private val bgExecutor: Executor,
    @Main private val mainExecutor: Executor,
    private val popup: AccessoryPopup,
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
                Tracker(State(accessory.accessoryInfo, accessory.accessoryStatus, null))
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to get the accessory state", e)
                return
            }
        trackers[binder] = tracker

        try {
            accessory.registerCallback(tracker)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to register the accessory callback", e)
            trackers.remove(binder)
        }
    }

    private fun remove(accessory: IAccessory) {
        val tracker = trackers.remove(accessory.asBinder()) ?: return
        try {
            accessory.unregisterCallback(tracker)
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to unregister the accessory callback", e)
        }
        val id = tracker.state.info.id
        mainExecutor.execute { popup.dismiss(id) }
    }

    private fun onStateChanged(old: State, new: State) {
        val id = new.info.id
        val content = createContent(new)
        when {
            !new.isConnected || (old.isCharging && !new.isCharging) ->
                mainExecutor.execute { popup.dismiss(id) }
            (!old.isConnected && new.isConnected) ||
                (!old.isCharging && new.isCharging) ||
                isLowBatteryReached(old, new) -> mainExecutor.execute { popup.show(id, content) }
            else -> mainExecutor.execute { popup.update(id, content) }
        }
    }

    private fun isLowBatteryReached(old: State, new: State): Boolean {
        val oldLevel = old.level ?: return false
        return oldLevel > LOW_BATTERY_LEVEL && new.isBatteryLow
    }

    private fun createContent(state: State): AccessoryPopupContent {
        val level = state.level
        val subtitle =
            when {
                state.isCharging && level == FULL_LEVEL -> R.string.accessory_popup_fully_charged
                state.isCharging -> R.string.accessory_popup_charging
                state.isBatteryLow -> R.string.accessory_popup_battery_low
                else -> R.string.accessory_popup_connected
            }
        return AccessoryPopupContent(
            icon =
                if (state.info.type == AccessoryType.KEYBOARD) R.drawable.ic_keyboard
                else R.drawable.ic_accessory_stylus,
            title = state.info.displayName,
            subtitle = context.getString(subtitle),
            levelText =
                level?.let { NumberFormat.getPercentInstance().format(it / FULL_LEVEL.toDouble()) },
            isCharging = state.isCharging,
            isError = state.isBatteryLow,
            durationMs = if (state.isBatteryLow) LOW_BATTERY_DURATION_MS else DURATION_MS,
        )
    }

    private data class State(
        val info: AccessoryInfo,
        val status: Byte,
        val battery: BatteryInfo?,
    ) {
        val isConnected = status != AccessoryStatus.DISCONNECTED
        val isCharging = info.isCharging
        val level = battery?.levelPercentage?.takeIf { it in 0..FULL_LEVEL }
        val isBatteryLow = !isCharging && level != null && level <= LOW_BATTERY_LEVEL
    }

    private inner class Tracker(initialState: State) : IAccessoryCallback.Stub() {
        // Only accessed on the background executor
        var state = initialState
            private set

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
            onStateChanged(oldState, newState)
        }
    }

    companion object {
        private const val TAG = "AccessoryPopupStartable"

        private const val FULL_LEVEL = 100

        // Matches the USI stylus low battery threshold
        private const val LOW_BATTERY_LEVEL = 16

        private const val DURATION_MS = 2500L
        private const val LOW_BATTERY_DURATION_MS = 4000L
    }
}
