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

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

public final class LcdPowerSaveManager {

    private static final String TAG = "LcdPowerSaveManager";

    /**
     * Power save request flag:
     * whether the local touch is activated during the screen off
     * while the power save is working.
     * <p>
     * If this flag is set, the phone response the local touch
     * NOTE: First local touch will resume LCD and not to be sent to view.
     * </p>
     */
    public static final int FLAG_LOCAL_TOUCH_ACTIVATED = 0x00000001;

    /**
     * Power save request flag:
     * whether the screen is resumed with user activity or not during the screen off
     * while the power save is working.
     * NOTE: Power key and first local touch are not controlled by this flag.
     * Power key always resume LCD.
     * First local touch resume LCD if FLAG_LOCAL_TOUCH_ACTIVATED is set.
     * <p>
     * If this flag is set, the LCD is resumed by user activity.
     * </p>
     */
    public static final int FLAG_LCD_ON_BY_USER_ACTIVITY = 0x00000010;

    /**
     * The service name for access LCD power save service
     */
    public static final String LCD_POWER_SAVE_SERVICE = "mirror_power_save";

    final ILcdPowerSave mService;
    final Context mContext;

    public LcdPowerSaveManager(Context context, ILcdPowerSave service) {
        mContext = context;
        mService = service;
    }

    /**
     * Creates a new power save request with the specified flags.
     * @param flags Specifies the optional flags combined using
     * the logical OR operator.</br>
     * NOTE: Set it to 0, means all of flags do not to be needed.</br>
     * NOTE: Activate is prioritized. If a flag is activated by a request
     * and unactivated by another one at the same time, the flag will be activated finally.</br>
     *
     * @param tag Your class name (or other tag) for debugging purposes.
     */
    public PowerSaveRequest newPowerSaveRequest(int flags, String tag) {
        return new PowerSaveRequest(flags, tag, mContext.getOpPackageName());
    }

    public final class PowerSaveRequest {
        private final int mFlags;
        private final String mTag;
        private final String mPackageName;
        private final IBinder mToken;
        private boolean mHeld;

        PowerSaveRequest(int flags, String tag, String packageName) {
            mFlags = flags;
            mTag = tag;
            mPackageName = packageName;
            mToken = new Binder();
        }

        @Override
        protected void finalize() throws Throwable {
            synchronized (mToken) {
                if (mHeld) {
                    try {
                        mService.releasePowerSave(mToken);
                    } catch (RemoteException e) {
                    }
                }
            }
        }

        /**
         * The power save request acquire.
         * <p>
         * Start the timer of the power save service.
         * The value of the timer is same as the sleep time in the settings.
         * When the timer expired, the LCD will be turned off.
         * Make sure release the power save request when you do not need it.
         * </p>
         */
        public void acquire() {
            synchronized (mToken) {
                acquireLocked();
            }
        }

        /**
         * Releases the power save request.
         * <p>
         * Finish the power save service.
         * The power save service may finish after you release the power save request,
         * or it may not if there are other power save requests still held.
         * </p>
         */
        public void release() {
            synchronized (mToken) {
                releaseLocked();
            }
        }

        /**
         * Resume the LCD temporary.
         * <p>
         * Resume the LCD temporary but not release the power save request.
         * When the timer expires, the LCD will be turned off again.
         * Be sure your acquired the power save request before this API is called.
         * </p>
         */
        public void resumeLcd() {
            synchronized (mToken) {
                resumeLcdLocked();
            }
        }

        /**
         * Returns true if the power save request has been acquired
         * but not yet released.
         *
         * @return True if the power save request is held.
         */
        public boolean isHeld() {
            synchronized (mToken) {
                return mHeld;
            }
        }

        private void acquireLocked() {
            if (!mHeld) {
                try {
                    mService.acquirePowerSave(mToken, mFlags, mTag, mPackageName);
                } catch (RemoteException e) {
                }
                mHeld = true;
            }
        }

        private void releaseLocked() {
            if (mHeld) {
                try {
                    mService.releasePowerSave(mToken);
                } catch (RemoteException e) {
                }
                mHeld = false;
            }
        }

        private void resumeLcdLocked() {
            try {
                mService.resumeLcd(mToken);
            } catch (RemoteException e) {
            }
        }
    }
}
