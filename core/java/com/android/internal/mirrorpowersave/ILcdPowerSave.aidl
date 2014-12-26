/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.mirrorpowersave;

import android.os.IBinder;

/** {@hide} */
interface ILcdPowerSave {
    void acquirePowerSave(IBinder lock, int flags, String tag, String packageName);
    void releasePowerSave(IBinder lock);
    void resumeLcd(IBinder lock);
}

