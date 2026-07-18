/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.brightness.domain.interactor

import com.android.systemui.brightness.domain.model.GammaBrightness
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public interface ScreenBrightnessInteractor {
    /** Maximum value in the Gamma space for brightness */
    public val maxGammaBrightness: GammaBrightness

    /** Minimum value in the Gamma space for brightness */
    public val minGammaBrightness: GammaBrightness

    /**
     * Brightness in the Gamma space for the current display. It will always represent a value
     * between [minGammaBrightness] and [maxGammaBrightness]
     */
    public val gammaBrightness: Flow<GammaBrightness>

    /** Whether the brightness is overridden by the window and cannot be changed by a user */
    public val brightnessOverriddenByWindow: StateFlow<Boolean>

    /** Sets the brightness temporarily, while the user is changing it. */
    public suspend fun setTemporaryBrightness(gammaBrightness: GammaBrightness)

    /** Sets the brightness definitely. */
    public suspend fun setBrightness(gammaBrightness: GammaBrightness)

    public val isAutoBrightnessEnabledFlow: StateFlow<Boolean>

    public fun toggleBrightnessMode()
}
