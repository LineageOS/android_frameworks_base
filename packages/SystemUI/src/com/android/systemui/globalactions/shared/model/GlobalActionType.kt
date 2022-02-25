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

package com.android.systemui.globalactions.shared.model

enum class GlobalActionType(val configKey: String) {
    POWER("power"),
    AIRPLANE("airplane"),
    BUGREPORT("bugreport"),
    FEEDBACK("feedback"),
    SILENT("silent"),
    USERS("users"),
    SETTINGS("settings"),
    LOCKDOWN("lockdown"),
    LOCK("lock"),
    VOICEASSIST("voiceassist"),
    ASSIST("assist"),
    RESTART("restart"),
    LOGOUT("logout"),
    EMERGENCY("emergency"),
    SCREENSHOT("screenshot"),
    SYSTEM_UPDATE("system_update"),
    STANDBY("standby"),
    DEVICECONTROLS("device_controls");

    companion object {
        private val KEY_MAP = entries.associateBy { it.configKey }

        fun fromConfigKey(configKey: String): GlobalActionType? {
            return KEY_MAP[configKey]
        }
    }
}
