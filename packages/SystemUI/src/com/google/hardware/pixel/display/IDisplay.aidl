/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.google.hardware.pixel.display;

import com.google.hardware.pixel.display.HbmState;
import com.google.hardware.pixel.display.LbeState;

interface IDisplay {
    /**
     * Query Display High Brightness Mode Supported
     *
     * @return true if HBM is supported on this platform.
     *         If false, HBM is not supported.
     */
    boolean isHbmSupported();

    /**
     * Set Display High Brightness Mode
     *
     * @param state to be queried.
     */
    void setHbmState(in HbmState state);

    /**
     * Get Display High Brightness Mode State
     *
     * @return state of display high brightness mode
     */
    HbmState getHbmState();

    /**
     * Query Display Local Brightness Enhancement Supported
     *
     * @return true if LBE is supported on this platform.
     *         If false, LBE is not supported.
     */
    boolean isLbeSupported();

    /**
     * Set Display Local Brightness Enhancement
     *
     * @param state to be queried.
     */
    void setLbeState(in LbeState state);

    /**
     * Set Display Local Brightness Enhancement Ambient Light
     *
     * @param ambientLux the proper ambient light in Lux.
     *        The value is equal to zero or above zero.
     */
    void setLbeAmbientLight(in int ambientLux);

    /**
     * Get Display Local Brightness Enhancement State
     *
     * @return state of display local brightness enhancement
     */
    LbeState getLbeState();

    /**
     * Query Display Local High Brightness Mode Supported
     *
     * @return true if LHBM is supported on this platform.
     *         If false, LHBM is not supported.
     */
    boolean isLhbmSupported();

    /**
     * Set Display Local High Brightness Mode
     *
     * @param enabled true if LHBM should be enabled, false otherwise.
     */
    void setLhbmState(in boolean enabled);

    /**
     * Get Display Local High Brightness Mode State
     *
     * @return true if LHBM is ON, false otherwise.
     */
    boolean getLhbmState();
}
