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

package com.android.systemui.brightness.data.repository

import com.android.systemui.brightness.data.model.LinearBrightness
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for tracking brightness in the current display.
 *
 * Values are in a linear space, as used by [android.hardware.display.DisplayManager].
 */
public interface ScreenBrightnessRepository {
    /** Current brightness as a value between [minLinearBrightness] and [maxLinearBrightness] */
    public val linearBrightness: Flow<LinearBrightness>

    /** Current minimum value for the brightness */
    public val minLinearBrightness: Flow<LinearBrightness>

    /** Current maximum value for the brightness */
    public val maxLinearBrightness: Flow<LinearBrightness>

    /** Whether the current brightness value is overridden by the application window */
    public val isBrightnessOverriddenByWindow: StateFlow<Boolean>

    /** Gets the current values for min and max brightness */
    public suspend fun getMinMaxLinearBrightness(): Pair<LinearBrightness, LinearBrightness>

    /**
     * Sets the temporary value for the brightness. This should change the display brightness but
     * not trigger any updates.
     */
    public fun setTemporaryBrightness(value: LinearBrightness)

    /** Sets the brightness definitively. */
    public fun setBrightness(value: LinearBrightness)

    public val isAutoBrightnessEnabledFlow: StateFlow<Boolean>

    public fun toggleBrightnessMode()
}
