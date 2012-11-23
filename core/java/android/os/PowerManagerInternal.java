/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.PowerManager.GoToSleepReason;
import android.os.PowerManager.UserActivityEvent;
import android.os.PowerManager.UserActivityFlag;
import android.os.PowerManager.WakeReason;
import android.util.IntArray;
import android.view.Display;
import android.view.KeyEvent;

import java.util.function.Consumer;

/**
 * Power manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class PowerManagerInternal {
    /**
     * Wakefulness: The device is asleep.  It can only be awoken by a call to wakeUp().
     * The screen should be off or in the process of being turned off by the display controller.
     * The device typically passes through the dozing state first.
     */
    public static final int WAKEFULNESS_ASLEEP = 0;

    /**
     * Wakefulness: The device is fully awake.  It can be put to sleep by a call to goToSleep().
     * When the user activity timeout expires, the device may start dreaming or go to sleep.
     */
    public static final int WAKEFULNESS_AWAKE = 1;

    /**
     * Wakefulness: The device is dreaming.  It can be awoken by a call to wakeUp(),
     * which ends the dream.  The device goes to sleep when goToSleep() is called, when
     * the dream ends or when unplugged.
     * User activity may brighten the screen but does not end the dream.
     */
    public static final int WAKEFULNESS_DREAMING = 2;

    /**
     * Wakefulness: The device is dozing.  It is almost asleep but is allowing a special
     * low-power "doze" dream to run which keeps the display on but lets the application
     * processor be suspended.  It can be awoken by a call to wakeUp() which ends the dream.
     * The device fully goes to sleep if the dream cannot be started or ends on its own.
     */
    public static final int WAKEFULNESS_DOZING = 3;

    public static String wakefulnessToString(int wakefulness) {
        switch (wakefulness) {
            case WAKEFULNESS_ASLEEP:
                return "Asleep";
            case WAKEFULNESS_AWAKE:
                return "Awake";
            case WAKEFULNESS_DREAMING:
                return "Dreaming";
            case WAKEFULNESS_DOZING:
                return "Dozing";
            default:
                return Integer.toString(wakefulness);
        }
    }

    /**
     * Converts platform constants to proto enums.
     */
    public static int wakefulnessToProtoEnum(int wakefulness) {
        switch (wakefulness) {
            case WAKEFULNESS_ASLEEP:
                return PowerManagerInternalProto.WAKEFULNESS_ASLEEP;
            case WAKEFULNESS_AWAKE:
                return PowerManagerInternalProto.WAKEFULNESS_AWAKE;
            case WAKEFULNESS_DREAMING:
                return PowerManagerInternalProto.WAKEFULNESS_DREAMING;
            case WAKEFULNESS_DOZING:
                return PowerManagerInternalProto.WAKEFULNESS_DOZING;
            default:
                return wakefulness;
        }
    }

    /**
     * Converts wakelock flags into strings.
     * @param flags wakelock flags to convert to string
     * @return Readable string of wakelock value.
     */
    @SuppressWarnings("deprecation")
    public static String getLockLevelString(int flags) {
        return switch (flags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
            case PowerManager.FULL_WAKE_LOCK -> "FULL_WAKE_LOCK";
            case PowerManager.SCREEN_BRIGHT_WAKE_LOCK -> "SCREEN_BRIGHT_WAKE_LOCK";
            case PowerManager.SCREEN_DIM_WAKE_LOCK -> "SCREEN_DIM_WAKE_LOCK";
            case PowerManager.PARTIAL_WAKE_LOCK -> "PARTIAL_WAKE_LOCK";
            case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK -> "PROXIMITY_SCREEN_OFF_WAKE_LOCK";
            case PowerManager.DOZE_WAKE_LOCK -> "DOZE_WAKE_LOCK";
            case PowerManager.DRAW_WAKE_LOCK -> "DRAW_WAKE_LOCK";
            case PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK ->
                    "SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK";
            case PowerManager.PARTIAL_SLEEP_WAKE_LOCK -> "PARTIAL_SLEEP_WAKE_LOCK";
            default -> "???";
        };
    }

    /**
     * Returns true if the wakefulness state represents an interactive state
     * as defined by {@link android.os.PowerManager#isInteractive}.
     */
    public static boolean isInteractive(int wakefulness) {
        return wakefulness == WAKEFULNESS_AWAKE || wakefulness == WAKEFULNESS_DREAMING;
    }

    /**
     * Used by the window manager to override the button brightness based on the
     * current foreground activity.
     *
     * This method must only be called by the window manager.
     *
     * @param brightness The overridden brightness, or Float.NaN to disable the override.
     */
    public abstract void setButtonBrightnessOverrideFromWindowManager(float brightness);

    /**
     * Used by the window manager to override the user activity timeout based on the
     * current foreground activity.  It can only be used to make the timeout shorter
     * than usual, not longer.
     *
     * This method must only be called by the window manager.
     *
     * @param timeoutMillis The overridden timeout, or -1 to disable the override.
     */
    public abstract void setUserActivityTimeoutOverrideFromWindowManager(long timeoutMillis);

    /**
     * Used by the window manager to tell the power manager that the user is no longer actively
     * using the device.
     */
    public abstract void setUserInactiveOverrideFromWindowManager();

    /**
     * Used by device administration to set the maximum screen off timeout.
     *
     * This method must only be called by the device administration policy manager.
     */
    public abstract void setMaximumScreenOffTimeoutFromDeviceAdmin(int userId, long timeMs);

    /**
     * Used by the dream manager to override certain properties while dozing.
     *
     * @param screenState The overridden screen state, or {@link Display#STATE_UNKNOWN}
     * to disable the override.
     * @param reason The reason for overriding the screen state.
     * @param screenBrightness The overridden screen brightness between
     * {@link PowerManager#BRIGHTNESS_MIN} and {@link PowerManager#BRIGHTNESS_MAX}, or
     * {@link PowerManager#BRIGHTNESS_INVALID_FLOAT} to disable the override.
     * @param useNormalBrightnessForDoze Whether use normal brightness while device is dozing.
     */
    public abstract void setDozeOverrideFromDreamManager(
            int screenState, @Display.StateReason int reason, float screenBrightness,
            boolean useNormalBrightnessForDoze);

    /**
     * Used by sidekick manager to tell the power manager if it shouldn't change the display state
     * when a draw wake lock is acquired. Some processes may grab such a wake lock to do some work
     * in a powered-up state, but we shouldn't give up sidekick control over the display until this
     * override is lifted.
     */
    public abstract void setDrawWakeLockOverrideFromSidekick(boolean keepState);

    public abstract PowerSaveState getLowPowerState(int serviceType);

    public abstract void registerLowPowerModeObserver(LowPowerModeListener listener);

    /**
     * Same as {@link #registerLowPowerModeObserver} but can take a lambda.
     */
    public void registerLowPowerModeObserver(int serviceType, Consumer<PowerSaveState> listener) {
        registerLowPowerModeObserver(new LowPowerModeListener() {
            @Override
            public int getServiceType() {
                return serviceType;
            }

            @Override
            public void onLowPowerModeChanged(PowerSaveState state) {
                listener.accept(state);
            }
        });
    }

    public interface LowPowerModeListener {
        int getServiceType();
        void onLowPowerModeChanged(PowerSaveState state);
    }

    /** Interface for clients to receive callbacks related to user activity. */
    public interface UserActivityListener {
        /**
         * Called when a user activity happens.
         *
         * @param when The time of the user activity, in the {@link SystemClock#uptimeMillis()}.
         * @param event The type of the user activity.
         * @param flags The flags associated with the user activity.
         */
        void onUserActivity(long when, @UserActivityEvent int event, @UserActivityFlag int flags);
    }

    /** A delegate for handling wake up tasks. */
    public interface WakeUpDelegate {
        /**
         * Invokes the wake up logic of the delegate.
         *
         * @param eventTime The time when the request to wake up was issued, in the
         *      {@link SystemClock#uptimeMillis()} time base..
         * @param reason The reason for the wake up.
         * @param details A free form string to explain the specific details behind the wake up for
         *      debugging purposes.
         * @param uid The UID that triggered this wake up.
         * @return {@code true} if the delegate successfully handles the wake up, {@code false}
         *      otherwise.
         */
        boolean wakeUp(long eventTime, @WakeReason int reason, String details, int uid);

        /**
         * Invokes the sleep logic of the delegate.
         *
         * @param eventTime The time when the request to sleep request was issued, in the
         *      {@link SystemClock#uptimeMillis()} time base.
         * @param uid The UID that triggered this sleep request.
         * @return {@code true} if the delegate successfully handles the sleep, {@code false}
         *      otherwise.
         */
        boolean sleep(long eventTime, @GoToSleepReason int reason, int uid);
    }

    /**
     * Sets a wake up delegate that can handle wake up events. Set to {@code null} to remove any
     * previously set delegate.
     */
    public abstract void setWakeUpDelegate(@Nullable WakeUpDelegate delegate);

    /**
     * Registers a listener to be notified about user activity events.
     *
     * @param listener the {@link UserActivityListener} to register.
     */
    public abstract void registerUserActivityListener(UserActivityListener listener);

    /**
     * Unregisters a listener that was registered via {@link #registerUserActivityListener}.
     *
     * @param listener the {@link UserActivityListener} to unregister.
     */
    public abstract void unregisterUserActivityListener(UserActivityListener listener);

    public abstract boolean setDeviceIdleMode(boolean enabled);

    public abstract boolean setLightDeviceIdleMode(boolean enabled);

    public abstract void setDeviceIdleWhitelist(int[] appids);

    public abstract void setDeviceIdleTempWhitelist(int[] appids);

    /**
     * Updates the Low Power Standby allowlist.
     *
     * @param uids UIDs that are exempt from Low Power Standby restrictions
     */
    public abstract void setLowPowerStandbyAllowlist(int[] uids);

    /**
     * Used by LowPowerStandbyController to notify the power manager that Low Power Standby's
     * active state has changed.
     *
     * @param active {@code true} to activate Low Power Standby, {@code false} to turn it off.
     */
    public abstract void setLowPowerStandbyActive(boolean active);

    public abstract void startUidChanges();

    public abstract void finishUidChanges();

    public abstract void updateUidProcState(int uid, int procState);

    public abstract void uidGone(int uid);

    public abstract void uidActive(int uid);

    public abstract void uidIdle(int uid);

    /**
     * Checks if the wakefulness of the supplied group is interactive.
     */
    public abstract boolean isGroupInteractive(int groupId);

    /** Returns if any of the default adjacent group is interactive. */
    public abstract boolean isAnyDefaultAdjacentGroupInteractive();

    /** Returns if the supplied group is adjacent to the default group. */
    public abstract boolean isDefaultGroupAdjacent(int groupId);

    /**
     * Used to notify the power manager that wakelocks should be enabled / disabled.
     *
     * @param force {@code true} to activate force disable wakelocks, {@code false} to turn it off.
     */
    public abstract void setForceDisableWakelocks(boolean force);

    /**
     * Used to notify the power manager that wakelocks should be enabled / disabled.
     *
     * @param force {@code true} to activate force disable wakelocks, {@code false} to turn it off.
     * @param displayIds wakelocks corresponding to the power groups of each of these display ids
     *                   will be acted upon.
     */
    public abstract void setForceDisableWakelocksByDisplay(boolean force, IntArray displayIds);

    /**
     * Used to notify the power manager that wakelocks should be enabled / disabled.
     *
     * @param force {@code true} to activate force disable wakelocks, {@code false} to turn it off.
     * @param groupIds wakelocks corresponding to these power groups will be acted upon.
     */
    public abstract void setForceDisableWakelocksByPowerGroup(boolean force, IntArray groupIds);

    /**
     * Used to put certain power groups specifically to sleep.
     *
     * @param groupIds power groups that should be put to sleep
     * @param eventTime when the request was issued
     * @param reason reason for going to sleep - any of
     * {@link android.os.PowerManager.GoToSleepReason}
     * @param flags PowerManager sleep flags
     */
    public abstract void goToSleepPerGroup(IntArray groupIds, long eventTime, int reason,
            int flags);

    /**
     * Used to wake up certain power groups.
     *
     * @param groupIds Power groups that should be woken up
     * @param eventTime When the request was issued
     * @param reason Reason for waking - any of {@link android.os.PowerManager.WakeReason}
     * @param details Details about the event.
     * @param opPackageName The Package name used for AppOps.
     * @param uid The uid used for AppOps.
     */
    public abstract void wakeupPerGroup(IntArray groupIds, long eventTime, int reason,
            String details, String opPackageName, int uid);

    /**
     * Boost: It is sent when user interacting with the device, for example,
     * touchscreen events are incoming.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Boost.aidl
     */
    public static final int BOOST_INTERACTION = 0;

    /**
     * Boost: It indicates that the framework is likely to provide a new display
     * frame soon. This implies that the device should ensure that the display
     * processing path is powered up and ready to receive that update.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Boost.aidl
     */
    public static final int BOOST_DISPLAY_UPDATE_IMMINENT = 1;

    /**
     * SetPowerBoost() indicates the device may need to boost some resources, as
     * the load is likely to increase before the kernel governors can react.
     * Depending on the boost, it may be appropriate to raise the frequencies of
     * CPU, GPU, memory subsystem, or stop CPU from going into deep sleep state.
     *
     * @param boost Boost which is to be set with a timeout.
     * @param durationMs The expected duration of the user's interaction, if
     *        known, or 0 if the expected duration is unknown.
     *        a negative value indicates canceling previous boost.
     *        A given platform can choose to boost some time based on durationMs,
     *        and may also pick an appropriate timeout for 0 case.
     */
    public abstract void setPowerBoost(int boost, int durationMs);

    /**
     * Mode: It indicates that the device is to allow wake up when the screen
     * is tapped twice.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_DOUBLE_TAP_TO_WAKE = 0;

    /**
     * Mode: It indicates Low power mode is activated or not. Low power mode
     * is intended to save battery at the cost of performance.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_LOW_POWER = 1;

    /**
     * Mode: It indicates Sustained Performance mode is activated or not.
     * Sustained performance mode is intended to provide a consistent level of
     * performance for a prolonged amount of time.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_SUSTAINED_PERFORMANCE = 2;

    /**
     * Mode: It sets the device to a fixed performance level which can be sustained
     * under normal indoor conditions for at least 10 minutes.
     * Fixed performance mode puts both upper and lower bounds on performance such
     * that any workload run while in a fixed performance mode should complete in
     * a repeatable amount of time.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_FIXED_PERFORMANCE = 3;

    /**
     * Mode: It indicates VR Mode is activated or not. VR mode is intended to
     * provide minimum guarantee for performance for the amount of time the device
     * can sustain it.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_VR = 4;

    /**
     * Mode: It indicates that an application has been launched.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_LAUNCH = 5;

    /**
     * Mode: It indicates that the device is about to enter a period of expensive
     * rendering.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_EXPENSIVE_RENDERING = 6;

    /**
     * Mode: It indicates that the device is about entering/leaving interactive
     * state or on-interactive state.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_INTERACTIVE = 7;

    /**
     * Mode: It indicates the device is in device idle, externally known as doze.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_DEVICE_IDLE = 8;

    /**
     * Mode: It indicates that display is either off or still on but is optimized
     * for low power.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_DISPLAY_INACTIVE = 9;

    /**
     * Mode: It indicates that display is changing layout due to rotation or fold
     * unfold behavior.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_DISPLAY_CHANGE = 17;

    /**
     * SetPowerMode() is called to enable/disable specific hint mode, which
     * may result in adjustment of power/performance parameters of the
     * cpufreq governor and other controls on device side.
     *
     * @param mode Mode which is to be enable/disable.
     * @param enabled true to enable, false to disable the mode.
     */
    public abstract void setPowerMode(int mode, boolean enabled);

    /** Returns whether there hasn't been a user activity event for the given number of ms. */
    public abstract boolean wasDeviceIdleFor(long ms);

    /** Returns information about the last wakeup event. */
    public abstract PowerManager.WakeData getLastWakeup();

    /** Returns information about the last event to go to sleep. */
    public abstract PowerManager.SleepData getLastGoToSleep();

    /** Allows power button to intercept a power key button press. */
    public abstract boolean interceptPowerKeyDown(KeyEvent event);

    /**
     * Internal version of {@link android.os.PowerManager#nap} which allows for napping while the
     * device is not awake.
     */
    public abstract void nap(long eventTime, boolean allowWake);

    /**
     * Returns true if ambient display is suppressed by any app with any token. This method will
     * return false if ambient display is not available.
     */
    public abstract boolean isAmbientDisplaySuppressed();

    /**
     * Notifies PowerManager that the device has entered a postured state (stationary + upright).
     * This may affect dream eligibility.
     */
    public abstract void setDevicePostured(boolean isPostured);

    /**
     * Notifies PowerManager that settings have changed and that it should refresh its state.
     */
    public abstract void updateSettings();

    /**
     * A proxy for {@link PowerManagerInternal} that batches UID state change notifications and
     * executes them asynchronously.
     *
     * <p>When {@link #startUidChanges()} is called, the proxy enters a batching mode. All
     * subsequent UID state change notifications (e.g., {@link #uidActive}, {@link #uidIdle}) are
     * buffered. When {@link #finishUidChanges()} is called, all buffered operations are posted to a
     * handler for asynchronous execution. Calls made outside of a {@code
     * startUidChanges/finishUidChanges} block are also executed asynchronously but are flushed
     * immediately.
     *
     * <p>This class is thread-safe.
     */
    public interface UidChangesBatch {
        /**
         * Signals the beginning of a series of UID changes.
         *
         * <p>Subsequent calls to UID state modification methods will be buffered until {@link
         * #finishUidChanges()} is called.
         */
        void startUidChanges();

        /**
         * Signals the end of a series of UID changes.
         *
         * <p>All buffered operations since the corresponding {@link #startUidChanges()} call are
         * posted for asynchronous execution.
         */
        void finishUidChanges();

        /**
         * Notifies PowerManager that a UID has become active.
         *
         * <p>This operation is buffered if called within a {@code start/finishUidChanges} block.
         */
        void uidActive(int uid);

        /**
         * Notifies PowerManager that a UID has become idle.
         *
         * <p>This operation is buffered if called within a {@code start/finishUidChanges} block.
         */
        void uidIdle(int uid);

        /**
         * Notifies PowerManager that a UID is no longer active.
         *
         * <p>This operation is buffered if called within a {@code start/finishUidChanges} block.
         */
        void uidGone(int uid);

        /**
         * Notifies PowerManager of a process state change for a UID.
         *
         * <p>This operation is buffered if called within a {@code start/finishUidChanges} block.
         */
        void updateUidProcState(int uid, int procState);
    }

    /**
     * Creates a proxy UidChangesBatch object that will do UID state change notifications on the
     * given Looper.
     */
    public abstract UidChangesBatch getBatchProxy(@NonNull Looper looper);
}
