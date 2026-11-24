/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.display.brightness;

import static com.android.server.display.BrightnessMappingStrategy.INVALID_NITS;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.PowerManager;
import android.util.IndentingPrintWriter;
import android.util.MathUtils;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.AutomaticBrightnessController;
import com.android.server.display.BrightnessMappingStrategy;
import com.android.server.display.BrightnessSetting;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.ScreenOffBrightnessSensorController;
import com.android.server.display.brightness.strategy.AutoBrightnessFallbackStrategy;
import com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy;
import com.android.server.display.brightness.strategy.DisplayBrightnessStrategy;
import com.android.server.display.feature.DisplayManagerFlags;

import java.io.PrintWriter;

/**
 * Deploys different DozeBrightnessStrategy to choose the current brightness for a specified
 * display. Applies the chosen brightness.
 */
public final class DisplayBrightnessController {

    // The ID of the display tied to this DisplayBrightnessController
    private final int mDisplayId;

    // The lock which is to be used to synchronize the resources being used in this class.
    // mLock can be synchronized on while holding DisplayManagerService.mSyncRoot, so never call
    // methods that acquiring DisplayManagerService.mSyncRoot under mLock,
    // e.g. some BrightnessSetting methods.
    private final Object mLock = new Object();

    // The default screen brightness to be used when no value is available in BrightnessSetting.
    private final float mScreenBrightnessDefault;

    // This is used to persist the changes happening to the brightness.
    private final BrightnessSetting mBrightnessSetting;

    // A runnable to update the clients registered via DisplayManagerGlobal
    // .EVENT_DISPLAY_BRIGHTNESS_CHANGED about the brightness change. Called when
    // mCurrentScreenBrightness is updated.
    private final Runnable mOnBrightnessChangeRunnable;

    // The screen brightness that has changed but not taken effect yet. If after applying min
    // and max constraints this is different from the current  screen brightness then this
    // is coming from something other than us and should be considered a user interaction.
    @GuardedBy("mLock")
    private float mPendingUnthrottledScreenBrightness;

    // The last observed screen brightness, either set by us or by the settings app on
    // behalf of the user.
    @GuardedBy("mLock")
    private float mCurrentScreenBrightness;

    @GuardedBy("mLock")
    private float mCurrentMaxBrightness;

    @GuardedBy("mLock")
    private float mCurrentMinBrightness;

    @GuardedBy("mLock")
    private float mCurrentUnthrottledBrightness;

    // The last brightness that was set by the user and not temporary. Set to
    // PowerManager.BRIGHTNESS_INVALID_FLOAT when a brightness has yet to be recorded.
    @GuardedBy("mLock")
    private float mLastUserSetScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;

    // Represents if the system has adjusted the brightness based on the user suggested value. Will
    // be false if the brightness change is coming from a non-user source
    private boolean mUserSetScreenBrightnessUpdated;

    // The listener which is to be notified everytime there is a change in the brightness in the
    // BrightnessSetting.
    private BrightnessSetting.BrightnessSettingListener mBrightnessSettingListener;

    // Selects an appropriate strategy based on the request provided by the clients.
    @GuardedBy("mLock")
    private final DisplayBrightnessStrategySelector mDisplayBrightnessStrategySelector;

    // Currently selected DisplayBrightnessStrategy.
    @GuardedBy("mLock")
    private DisplayBrightnessStrategy mDisplayBrightnessStrategy;

    // The executor on which the mOnBrightnessChangeRunnable is executed. This ensures that the
    // callback is not executed in sync and is not blocking the thread from which it is called.
    private final HandlerExecutor mBrightnessChangeExecutor;

    // True if we want to persist the brightness value in nits even if the underlying display
    // device changes.
    private final boolean mPersistBrightnessNitsForDefaultDisplay;

    // The controller for the automatic brightness level.
    // TODO(b/265415257): Move to the automatic brightness strategy
    @Nullable
    @VisibleForTesting
    AutomaticBrightnessController mAutomaticBrightnessController;

    // True if the stylus is being used
    private boolean mIsStylusBeingUsed;

    /**
     * The constructor of DisplayBrightnessController.
     */
    public DisplayBrightnessController(Context context, int displayId,
            float defaultScreenBrightness, BrightnessSetting brightnessSetting,
            Runnable onBrightnessChangeRunnable, HandlerExecutor brightnessChangeExecutor,
            DisplayManagerFlags flags, DisplayDeviceConfig config) {
        this(context, new Injector(), displayId, defaultScreenBrightness, brightnessSetting,
                onBrightnessChangeRunnable, brightnessChangeExecutor, flags, config);
    }

    @VisibleForTesting
    DisplayBrightnessController(Context context, Injector injector, int displayId,
            float defaultScreenBrightness, BrightnessSetting brightnessSetting,
            Runnable onBrightnessChangeRunnable, HandlerExecutor brightnessChangeExecutor,
            DisplayManagerFlags flags, DisplayDeviceConfig config) {
        mDisplayId = displayId;
        mBrightnessSetting = brightnessSetting;
        mPendingUnthrottledScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mScreenBrightnessDefault = BrightnessUtils.clampAbsoluteBrightness(defaultScreenBrightness);
        mCurrentScreenBrightness = getScreenBrightnessSetting();
        mCurrentUnthrottledBrightness = mCurrentScreenBrightness;
        mCurrentMaxBrightness = PowerManager.BRIGHTNESS_MAX;
        mCurrentMinBrightness = PowerManager.BRIGHTNESS_MIN;
        mOnBrightnessChangeRunnable = onBrightnessChangeRunnable;
        mDisplayBrightnessStrategySelector = injector.getDisplayBrightnessStrategySelector(context,
                displayId, flags, config);
        mBrightnessChangeExecutor = brightnessChangeExecutor;
        mPersistBrightnessNitsForDefaultDisplay = context.getResources().getBoolean(
                com.android.internal.R.bool.config_persistBrightnessNitsForDefaultDisplay);
    }

    /**
     * Updates the display brightness. This delegates the responsibility of selecting an appropriate
     * strategy to DisplayBrightnessStrategySelector, which is then applied to evaluate the
     * DisplayBrightnessState. In the future,
     * 1. This will account for clamping the brightness if needed.
     * 2. This will notify the system about the updated brightness
     *
     * @param displayPowerRequest The request to update the brightness
     * @param targetDisplayState  The target display state of the system
     */
    public DisplayBrightnessState updateBrightness(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            int targetDisplayState,
            DisplayManagerInternal.DisplayOffloadSession displayOffloadSession,
            boolean isBedtimeModeWearEnabled,
            boolean isChargingModeEnabled) {
        DisplayBrightnessState state;
        synchronized (mLock) {
            mDisplayBrightnessStrategy = mDisplayBrightnessStrategySelector.selectStrategy(
                    constructStrategySelectionRequest(displayPowerRequest, targetDisplayState,
                            displayOffloadSession, isBedtimeModeWearEnabled,
                            isChargingModeEnabled));
            state = mDisplayBrightnessStrategy
                        .updateBrightness(constructStrategyExecutionRequestLocked(
                                displayPowerRequest, displayOffloadSession));
        }

        // This is a temporary measure until AutomaticBrightnessStrategy works as a traditional
        // strategy.
        // TODO: Remove when AutomaticBrightnessStrategy is populating the values directly.
        if (state != null) {
            state = addAutomaticBrightnessState(state);
        }
        return state;
    }

    /**
     * Sets the temporary brightness
     */
    public void setTemporaryBrightness(Float temporaryBrightness) {
        synchronized (mLock) {
            setTemporaryBrightnessLocked(temporaryBrightness);
        }
    }

    /**
     * Updates the brightness override from WindowManager.
     *
     * @param request The request to override the brightness
     * @return whether this request will result in a change of the brightness
     */
    public boolean updateWindowManagerBrightnessOverride(
            DisplayManagerInternal.DisplayBrightnessOverrideRequest request) {
        synchronized (mLock) {
            return mDisplayBrightnessStrategySelector.getOverrideBrightnessStrategy()
                    .updateWindowManagerBrightnessOverride(request);
        }
    }

    /**
     * Sets the brightness to follow
     */
    public void setBrightnessToFollow(float brightnessToFollow, boolean slowChange) {
        synchronized (mLock) {
            mDisplayBrightnessStrategySelector.getFollowerDisplayBrightnessStrategy()
                    .setBrightnessToFollow(brightnessToFollow, slowChange);
        }
    }

    /**
     * Sets the brightness from the offload session.
     * @return Whether the offload brightness has changed
     */
    public boolean setBrightnessFromOffload(float brightness) {
        synchronized (mLock) {
            if (mDisplayBrightnessStrategySelector.getOffloadBrightnessStrategy() != null
                    && !BrightnessSynchronizer.floatEquals(mDisplayBrightnessStrategySelector
                    .getOffloadBrightnessStrategy().getOffloadScreenBrightness(), brightness)) {
                mDisplayBrightnessStrategySelector.getOffloadBrightnessStrategy()
                        .setOffloadScreenBrightness(brightness);
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a boolean flag indicating if the light sensor is to be used to decide the screen
     * brightness when dozing
     */
    public boolean isAllowAutoBrightnessWhileDozing() {
        synchronized (mLock) {
            return mDisplayBrightnessStrategySelector.isAllowAutoBrightnessWhileDozing();
        }
    }

    /**
     * Returns the config value indicating the auto brightness while dozing is to be
     * allowed ot not. Note that this is a config value, but the actual status can differ from this.
     */
    public boolean isAllowAutoBrightnessWhileDozingConfig() {
        synchronized (mLock) {
            return mDisplayBrightnessStrategySelector.isAllowAutoBrightnessWhileDozingConfig();
        }
    }

    /**
     * Sets the current screen brightness to the supplied value, and notifies all the listeners
     * requesting for change events on brightness change.
     */
    public void setAndNotifyCurrentScreenBrightness(float brightnessValue) {
        final boolean hasBrightnessChanged;
        synchronized (mLock) {
            float brightnessAdjusted = getBrightnessConstrainedLocked(brightnessValue);
            hasBrightnessChanged = (brightnessAdjusted != mCurrentScreenBrightness);
            mCurrentScreenBrightness = brightnessAdjusted;
            mCurrentUnthrottledBrightness = brightnessValue;
        }
        if (hasBrightnessChanged) {
            notifyCurrentScreenBrightness();
        }
    }

    /**
     * Returns the last observed screen brightness.
     */
    public float getCurrentBrightness() {
        synchronized (mLock) {
            return mCurrentScreenBrightness;
        }
    }

    @VisibleForTesting
    float getPendingScreenBrightness() {
        synchronized (mLock) {
            return mPendingUnthrottledScreenBrightness;
        }
    }

    /**
     * Updates pending brightness with value from settings
     */
    public void handleSettingsChange() {
        float brightness = getScreenBrightnessSetting();
        synchronized (mLock) {
            mPendingUnthrottledScreenBrightness = brightness;
        }
    }

    /**
     * Returns if the system has adjusted the brightness based on the user suggested value. Will
     * be false if the brightness change is coming from a non-user source.
     *
     * Todo: 294444204 This is a temporary workaround, and should be moved to the manual brightness
     * strategy once that is introduced
     */
    public boolean getIsUserSetScreenBrightnessUpdated() {
        return mUserSetScreenBrightnessUpdated;
    }

    /**
     * Registers the BrightnessSettingListener with the BrightnessSetting, which will be notified
     * everytime there is a change in the brightness.
     */
    public void registerBrightnessSettingChangeListener(
            BrightnessSetting.BrightnessSettingListener brightnessSettingListener) {
        mBrightnessSettingListener = brightnessSettingListener;
        mBrightnessSetting.registerListener(mBrightnessSettingListener);
    }

    /**
     * Returns the last user set brightness which is not temporary.
     */
    public float getLastUserSetScreenBrightness() {
        synchronized (mLock) {
            return mLastUserSetScreenBrightness;
        }
    }

    /**
     * Returns the current screen brightnessSetting which is responsible for saving the brightness
     * in the persistent store
     */
    private float getScreenBrightnessSetting() {
        float brightness = mBrightnessSetting.getBrightness();
        if (Float.isNaN(brightness)) {
            brightness = mScreenBrightnessDefault;
        }
        return BrightnessUtils.clampAbsoluteBrightness(brightness);
    }

    /**
     * Returns the current screen brightnessSetting constrained by current min and max values
     */
    public float getScreenBrightnessSettingConstrained() {
        float brightness = mBrightnessSetting.getBrightness();
        if (Float.isNaN(brightness)) {
            brightness = mScreenBrightnessDefault;
        }
        float brightnessAdjusted;
        synchronized (mLock) {
            brightnessAdjusted = getBrightnessConstrainedLocked(brightness);
        }
        return brightnessAdjusted;
    }

    /**
     * Notifies the brightnessSetting to persist the supplied brightness value.
     */
    private void setBrightnessInternal(float brightnessValue, float maxBrightness) {
        // Update the setting, which will eventually call back into DPC to have us actually
        // update the display with the new value.
        mBrightnessSetting.setBrightness(brightnessValue);
        if (mDisplayId == Display.DEFAULT_DISPLAY && mPersistBrightnessNitsForDefaultDisplay) {
            float nits = convertToNits(brightnessValue);
            float currentlyStoredNits = mBrightnessSetting.getBrightnessNitsForDefaultDisplay();
            // Don't override settings if the brightness is set to max, but the currently
            // stored value is greater. On multi-screen device, when switching between a
            // screen with a wider brightness range and one with a narrower brightness range,
            // the stored value shouldn't change.
            if (nits != INVALID_NITS && !(brightnessValue >= maxBrightness
                    && currentlyStoredNits > nits)) {
                mBrightnessSetting.setBrightnessNitsForDefaultDisplay(nits);
            }
        }
    }

    /**
     * Notifies the brightnessSetting to persist the supplied brightness value for a user.
     */
    public void setBrightness(float brightnessValue, int userSerial) {
        mBrightnessSetting.setUserSerial(userSerial);
        setBrightness(brightnessValue);
    }

    /**
     * Notifies the brightnessSetting to persist the supplied brightness value.
     */
    public void setBrightness(float brightnessValue) {
        float maxBrightness;
        synchronized (mLock) {
            maxBrightness = mCurrentMaxBrightness;
        }
        setBrightnessInternal(brightnessValue, maxBrightness);
    }

    /**
     * Flush the brightness update that has been made to the persistent data store.
     */
    public void saveBrightnessIfNeeded() {
        mBrightnessSetting.saveIfNeeded();
    }

    /**
     * Sets the current screen brightness, and notifies the BrightnessSetting about the change.
     */
    public void updateScreenBrightnessSetting(float brightnessValue,
            float minBrightness, float maxBrightness) {
        float constrainedBrightness = MathUtils
                .constrain(brightnessValue, minBrightness, maxBrightness);
        boolean constrainedBrightnessChanged = false;
        boolean rawBrightnessChanged = false;
        synchronized (mLock) {
            mCurrentMaxBrightness = maxBrightness;
            mCurrentMinBrightness = minBrightness;
            if (!BrightnessUtils.isValidBrightnessValue(brightnessValue)) {
                return;
            }
            if (constrainedBrightness != mCurrentScreenBrightness) {
                constrainedBrightnessChanged = true;
            }
            if (brightnessValue != mCurrentUnthrottledBrightness) {
                rawBrightnessChanged = true;
            }

            mCurrentScreenBrightness = constrainedBrightness;
            mCurrentUnthrottledBrightness = brightnessValue;
        }

        if (constrainedBrightnessChanged) {
            notifyCurrentScreenBrightness();
        }
        if (rawBrightnessChanged) {
            setBrightnessInternal(brightnessValue, maxBrightness);
        }
    }

    /**
     * Sets up the auto brightness and the relevant state for the associated display
     */
    public void setUpAutoBrightness(AutomaticBrightnessController automaticBrightnessController,
            SensorManager sensorManager,
            DisplayDeviceConfig displayDeviceConfig, Handler handler,
            BrightnessMappingStrategy brightnessMappingStrategy, boolean isDisplayEnabled,
            int leadDisplayId) {
        setAutomaticBrightnessController(automaticBrightnessController);
        setUpAutoBrightnessFallbackStrategy(sensorManager, displayDeviceConfig, handler,
                brightnessMappingStrategy, isDisplayEnabled, leadDisplayId);
    }

    public ScreenOffBrightnessSensorController getScreenOffBrightnessSensorController() {
        return mDisplayBrightnessStrategySelector.getAutoBrightnessFallbackStrategy()
                .getScreenOffBrightnessSensorController();
    }

    /**
     * Remove the Automatic Brightness Controller.
     */
    public void resetAutoBrightness() {
        setAutomaticBrightnessController(null);
        AutoBrightnessFallbackStrategy autoBrightnessFallbackStrategy =
                getAutoBrightnessFallbackStrategy();
        if (autoBrightnessFallbackStrategy != null) {
            autoBrightnessFallbackStrategy.stop();
        }
    }

    /**
     * TODO(b/253226419): Remove once auto-brightness is a fully-functioning strategy.
     */
    public AutomaticBrightnessStrategy getAutomaticBrightnessStrategy() {
        return mDisplayBrightnessStrategySelector.getAutomaticBrightnessStrategy();
    }

    /**
     * Convert a brightness float scale value to a nit value. Adjustments, such as RBC, are not
     * applied. This is used when storing the brightness in nits for the default display and when
     * passing the brightness value to follower displays.
     *
     * @param brightness The float scale value
     * @return The nit value or {@link INVALID_NITS} if no conversion is
     * possible.
     */
    public float convertToNits(float brightness) {
        if (mAutomaticBrightnessController == null) {
            return INVALID_NITS;
        }
        return mAutomaticBrightnessController.convertToNits(brightness);
    }

    /**
     * Convert a brightness float scale value to a nit value. Adjustments, such as RBC are applied.
     * This is used when sending the brightness value to
     * {@link com.android.server.display.BrightnessTracker}.
     *
     * @param brightness The float scale value
     * @return The nit value or {@link INVALID_NITS} if no conversion is
     * possible.
     */
    public float convertToAdjustedNits(float brightness) {
        if (mAutomaticBrightnessController == null) {
            return INVALID_NITS;
        }
        return mAutomaticBrightnessController.convertToAdjustedNits(brightness);
    }

    /**
     * Convert a brightness nit value to a float scale value. It is assumed that the nit value
     * provided does not have adjustments, such as RBC, applied.
     *
     * @param nits The nit value
     * @return The float scale value or {@link PowerManager.BRIGHTNESS_INVALID_FLOAT} if no
     * conversion is possible.
     */
    public float getBrightnessFromNits(float nits) {
        if (mAutomaticBrightnessController == null) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        }
        return mAutomaticBrightnessController.getBrightnessFromNits(nits);
    }

    /**
     * Convert a brightness nit value to a float scale value. It is assumed that the nit value
     * provided might have adjustments, such as RBC, applied.
     *
     * @param nits The nit value
     * @return The float scale value or {@link PowerManager.BRIGHTNESS_INVALID_FLOAT} if no
     * conversion is possible.
     */
    public float getBrightnessFromAdjustedNits(float nits) {
        if (mAutomaticBrightnessController == null) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        }
        return mAutomaticBrightnessController.getBrightnessFromAdjustedNits(nits);
    }

    /**
     * @return The brightness manually selected by the user, scaled for doze.
     */
    public float getManualDozeBrightness() {
        synchronized (mLock) {
            return mDisplayBrightnessStrategySelector.getDozeBrightnessStrategy()
                    .getManualDozeBrightness(getCurrentBrightness());
        }
    }

    /**
     * Update the config values. Needs to be called when the underlying display device changes.
     * @param config The Display Device Config
     */
    public void onDisplayChanged(DisplayDeviceConfig config) {
        synchronized (mLock) {
            mDisplayBrightnessStrategySelector.onDisplayChanged(config);
        }
        // The underlying display device changed, load the brightness persisted for it unless
        // the nit based brightness of the default display is to be kept instead.
        if (mBrightnessSetting != null && !(mDisplayId == Display.DEFAULT_DISPLAY
                && mPersistBrightnessNitsForDefaultDisplay)) {
            mBrightnessSetting.reloadFromDisplayDevice();
        }
    }

    /**
     * Stops the associated listeners when the display is stopped. Invoked when the {@link
     * #mDisplayId} is being removed.
     */
    public void stop() {
        if (mBrightnessSetting != null) {
            mBrightnessSetting.unregisterListener(mBrightnessSettingListener);
        }
        AutoBrightnessFallbackStrategy autoBrightnessFallbackStrategy =
                getAutoBrightnessFallbackStrategy();
        if (autoBrightnessFallbackStrategy != null) {
            autoBrightnessFallbackStrategy.stop();
        }
    }

    private AutoBrightnessFallbackStrategy getAutoBrightnessFallbackStrategy() {
        synchronized (mLock) {
            return mDisplayBrightnessStrategySelector.getAutoBrightnessFallbackStrategy();
        }
    }

    /**
     * Used to dump the state.
     *
     * @param writer The PrintWriter used to dump the state.
     */
    public void dump(PrintWriter writer) {
        writer.println();
        writer.println("DisplayBrightnessController:");
        writer.println("  mDisplayId=: " + mDisplayId);
        writer.println("  mScreenBrightnessDefault=" + mScreenBrightnessDefault);
        writer.println("  mPersistBrightnessNitsForDefaultDisplay="
                + mPersistBrightnessNitsForDefaultDisplay);
        writer.println("  mIsStylusBeingUsed="
                + mIsStylusBeingUsed);
        synchronized (mLock) {
            writer.println("  mCurrentMinBrightness=" + mCurrentMinBrightness);
            writer.println("  mCurrentMaxBrightness=" + mCurrentMaxBrightness);
            writer.println("  mPendingUnthrottledScreenBrightness="
                    + mPendingUnthrottledScreenBrightness);
            writer.println("  mCurrentScreenBrightness=" + mCurrentScreenBrightness);
            writer.println("  mCurrentUnthrottledBrightness=" + mCurrentUnthrottledBrightness);
            writer.println("  mLastUserSetScreenBrightness="
                    + mLastUserSetScreenBrightness);
            if (mDisplayBrightnessStrategy != null) {
                writer.println("  Last selected DisplayBrightnessStrategy= "
                        + mDisplayBrightnessStrategy.getName());
            }
            IndentingPrintWriter ipw = new IndentingPrintWriter(writer, " ");
            mDisplayBrightnessStrategySelector.dump(ipw);
        }
    }

    /**
     * We want to return true if the user has set the screen brightness.
     * RBC on, off, and intensity changes will return false.
     * Slider interactions whilst in RBC will return true, just as when in non-rbc.
     */
    @VisibleForTesting
    boolean updateUserSetScreenBrightness() {
        mUserSetScreenBrightnessUpdated = false;
        synchronized (mLock) {
            if (!BrightnessUtils.isValidBrightnessValue(mPendingUnthrottledScreenBrightness)) {
                return false;
            }
            float pendingBrightnessConstrained = getBrightnessConstrainedLocked(
                    mPendingUnthrottledScreenBrightness);
            mCurrentUnthrottledBrightness = mPendingUnthrottledScreenBrightness;
            mPendingUnthrottledScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            setTemporaryBrightnessLocked(PowerManager.BRIGHTNESS_INVALID_FLOAT);

            if (mCurrentScreenBrightness == pendingBrightnessConstrained) {
                return false;
            }
            mCurrentScreenBrightness = pendingBrightnessConstrained;
            mLastUserSetScreenBrightness = pendingBrightnessConstrained;
        }
        notifyCurrentScreenBrightness();
        mUserSetScreenBrightnessUpdated = true;
        return true;
    }

    /**
     * Notifies if the stylus is currently being used or not.
     */
    public void setStylusBeingUsed(boolean isEnabled) {
        mIsStylusBeingUsed = isEnabled;
    }

    @VisibleForTesting
    boolean isStylusBeingUsed() {
        return mIsStylusBeingUsed;
    }

    @VisibleForTesting
    static class Injector {
        DisplayBrightnessStrategySelector getDisplayBrightnessStrategySelector(Context context,
                int displayId, DisplayManagerFlags flags, DisplayDeviceConfig config) {
            return new DisplayBrightnessStrategySelector(context, /* injector= */ null, displayId,
                    flags, config);
        }
    }

    @VisibleForTesting
    BrightnessSetting.BrightnessSettingListener getBrightnessSettingListener() {
        return mBrightnessSettingListener;
    }

    /**
     * Returns the current selected DisplayBrightnessStrategy
     */
    public DisplayBrightnessStrategy getCurrentDisplayBrightnessStrategy() {
        synchronized (mLock) {
            return mDisplayBrightnessStrategy;
        }
    }

    /**
     * Set the {@link AutomaticBrightnessController} which is needed to perform nit-to-float-scale
     * conversion.
     * @param automaticBrightnessController The ABC
     */
    @VisibleForTesting
    void setAutomaticBrightnessController(
            @Nullable AutomaticBrightnessController automaticBrightnessController) {
        mAutomaticBrightnessController = automaticBrightnessController;
        getAutomaticBrightnessStrategy()
                .setAutomaticBrightnessController(automaticBrightnessController);
        if (automaticBrightnessController != null) {
            loadNitBasedBrightnessSetting();
        }
    }

    private void setUpAutoBrightnessFallbackStrategy(SensorManager sensorManager,
            DisplayDeviceConfig displayDeviceConfig, Handler handler,
            BrightnessMappingStrategy brightnessMappingStrategy, boolean isDisplayEnabled,
            int leadDisplayId) {
        AutoBrightnessFallbackStrategy autoBrightnessFallbackStrategy =
                getAutoBrightnessFallbackStrategy();
        if (autoBrightnessFallbackStrategy != null) {
            autoBrightnessFallbackStrategy.setupAutoBrightnessFallbackSensor(
                    sensorManager, displayDeviceConfig, handler, brightnessMappingStrategy,
                    isDisplayEnabled, leadDisplayId);
        }
    }

    /**
     * TODO(b/253226419): Remove once auto-brightness is a fully-functioning strategy.
     */
    private DisplayBrightnessState addAutomaticBrightnessState(DisplayBrightnessState state) {
        AutomaticBrightnessStrategy autoStrat = getAutomaticBrightnessStrategy();

        DisplayBrightnessState.Builder builder = DisplayBrightnessState.Builder.from(state);
        builder.setShouldUseAutoBrightness(
                autoStrat != null && autoStrat.shouldUseAutoBrightness());
        return builder.build();
    }

    @GuardedBy("mLock")
    private void setTemporaryBrightnessLocked(float temporaryBrightness) {
        mDisplayBrightnessStrategySelector.getTemporaryDisplayBrightnessStrategy()
                .setTemporaryScreenBrightness(temporaryBrightness);
    }

    private void notifyCurrentScreenBrightness() {
        mBrightnessChangeExecutor.execute(mOnBrightnessChangeRunnable);
    }

    /**
     * Loads the brightness value. If this is the default display and the config says that we should
     * persist the nit value, the nit value for the default display will be loaded.
     */
    private void loadNitBasedBrightnessSetting() {
        float currentBrightnessSetting = Float.NaN;
        if (mDisplayId == Display.DEFAULT_DISPLAY && mPersistBrightnessNitsForDefaultDisplay) {
            float brightnessNitsForDefaultDisplay =
                    mBrightnessSetting.getBrightnessNitsForDefaultDisplay();
            if (brightnessNitsForDefaultDisplay != INVALID_NITS) {
                float brightnessForDefaultDisplay = getBrightnessFromNits(
                        brightnessNitsForDefaultDisplay);
                if (BrightnessUtils.isValidBrightnessValue(brightnessForDefaultDisplay)) {
                    mBrightnessSetting.setBrightnessNoNotify(brightnessForDefaultDisplay);
                    currentBrightnessSetting = brightnessForDefaultDisplay;
                }
            }
        }

        if (Float.isNaN(currentBrightnessSetting)) {
            currentBrightnessSetting = getScreenBrightnessSetting();
        }

        synchronized (mLock) {
            mCurrentScreenBrightness = currentBrightnessSetting;
        }
    }

    private StrategySelectionRequest constructStrategySelectionRequest(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            int targetDisplayState,
            DisplayManagerInternal.DisplayOffloadSession displayOffloadSession,
            boolean isBedtimeModeEnabled, boolean isChargingModeEnabled) {
        boolean userSetBrightnessChanged = updateUserSetScreenBrightness();
        float lastUserSetScreenBrightness;
        synchronized (mLock) {
            lastUserSetScreenBrightness = mLastUserSetScreenBrightness;
        }
        return new StrategySelectionRequest(displayPowerRequest, targetDisplayState,
                lastUserSetScreenBrightness, userSetBrightnessChanged, displayOffloadSession,
                mIsStylusBeingUsed, isBedtimeModeEnabled, isChargingModeEnabled);
    }

    @GuardedBy("mLock")
    private StrategyExecutionRequest constructStrategyExecutionRequestLocked(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            DisplayManagerInternal.DisplayOffloadSession offloadSession) {
        return new StrategyExecutionRequest(displayPowerRequest, mCurrentUnthrottledBrightness,
                mUserSetScreenBrightnessUpdated, mIsStylusBeingUsed, offloadSession);
    }

    @GuardedBy("mLock")
    private float getBrightnessConstrainedLocked(float brightness) {
        return MathUtils.constrain(brightness, mCurrentMinBrightness, mCurrentMaxBrightness);
    }
}
