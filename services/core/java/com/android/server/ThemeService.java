/**
 * Copyright (C) 2016-2017 The ParanoidAndroid Project
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
package com.android.server;

import android.app.IThemeCallback;
import android.app.IThemeService;
import android.content.Context;
import android.database.ContentObserver;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A service to select and use custom themes.
 * The service is responsible for enabling and disabling the custom theme.
 *
 * @author Anas Karbila
 * @hide
 */
public class ThemeService extends IThemeService.Stub implements IBinder.DeathRecipient {

    private static final String TAG = ThemeService.class.getSimpleName();

    private final List<IThemeCallback> mCallbacks = new ArrayList<>();

    private ThemeObserver mObserver;
    private Context mContext;

    public ThemeService(Context context) {
        mContext = context;
        mObserver = new ThemeObserver();
        mObserver.register();
    }

    @Override
    public void binderDied() {
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            final IThemeCallback callback = mCallbacks.get(i);
            try {
                returnToDefaultTheme(mContext);
                if (callback != null) {
                    callback.onThemeChanged(getThemeMode(), getTheme(getAccentColor()));
                }
            } catch (DeadObjectException e) {
                Log.w(TAG, "Death object while calling onThemeChanged: ", e);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to call onThemeChanged: ", e);
            } catch (NullPointerException e) {
                Log.w(TAG, "NullPointer while calling onThemeChanged: ", e);
            }
        }
        mCallbacks.clear();
        mObserver.unregister();
    }

    @Override
    public void addCallback(IThemeCallback callback) {
        synchronized (mCallbacks) {
            if (!mCallbacks.contains(callback)) {
                mCallbacks.add(callback);
            }
            dispatchCallbackAdded();
        }
    }

    private int getTheme(int color) {
        final boolean isDarkMode = getThemeMode() == 3;
        final boolean isGreymode = getThemeMode() == 1;
        switch (color) {
            case 1:
                return isDarkMode ? R.style.Theme_DeviceDefault_Dark_Green
                        : isGreymode ? R.style.Theme_DeviceDefault_Grey_Green
                        : R.style.Theme_DeviceDefault_White_Green;
            case 2:
                return isDarkMode ? R.style.Theme_DeviceDefault_Dark_Cyan
                        : isGreymode ? R.style.Theme_DeviceDefault_Grey_Cyan
                        : R.style.Theme_DeviceDefault_White_Cyan;
            case 3:
                return isDarkMode ? R.style.Theme_DeviceDefault_Dark_Blue
                        : isGreymode ? R.style.Theme_DeviceDefault_Grey_Blue
                        : R.style.Theme_DeviceDefault_White_Blue;
            case 4:
                return isDarkMode ? R.style.Theme_DeviceDefault_Dark_Yellow
                        : isGreymode ? R.style.Theme_DeviceDefault_Grey_Yellow
                        : R.style.Theme_DeviceDefault_White_Yellow;
            case 5:
                return isDarkMode ? R.style.Theme_DeviceDefault_Dark_Orange
                        : isGreymode ? R.style.Theme_DeviceDefault_Grey_Orange
                        : R.style.Theme_DeviceDefault_White_Orange;
            case 6:
                return isDarkMode ? R.style.Theme_DeviceDefault_Dark_Red
                        : isGreymode ? R.style.Theme_DeviceDefault_Grey_Red
                        : R.style.Theme_DeviceDefault_White_Red;
            case 7:
                return isDarkMode ? R.style.Theme_DeviceDefault_Dark_Pink
                        : isGreymode ? R.style.Theme_DeviceDefault_Grey_Pink
                        : R.style.Theme_DeviceDefault_White_Pink;
            case 8:
                return isDarkMode ? R.style.Theme_DeviceDefault_Dark_Purple
                        : isGreymode ? R.style.Theme_DeviceDefault_Grey_Purple
                        : R.style.Theme_DeviceDefault_White_Purple;
            case 9:
                return isDarkMode ? R.style.Theme_DeviceDefault_Dark_Grey
                        : isGreymode ? R.style.Theme_DeviceDefault_Grey_Grey
                        : R.style.Theme_DeviceDefault_White_Grey;
            case 0:
            default:
                return getPrimaryTheme(getThemeMode());
        }
    }

    private int getPrimaryTheme(int color) {
        switch (color) {
            case 3: // dark theme
                return R.style.Theme_DeviceDefault_Dark;
            case 1: // grey theme
                return R.style.Theme_DeviceDefault_Grey;
            case 0: // default theme
            case 2: // pixel theme
            default:
                return R.style.Theme_DeviceDefault_White;
        }
    }

    private int getThemeMode() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.THEME_PRIMARY_COLOR, 2);
    }

    private int getAccentColor() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.THEME_ACCENT_COLOR, 1);
    }

    public static void returnToDefaultTheme(Context context) {
        Settings.Secure.putInt(context.getContentResolver(),
                Settings.Secure.THEME_PRIMARY_COLOR, 0);
        Settings.Secure.putInt(context.getContentResolver(),
                Settings.Secure.THEME_ACCENT_COLOR, 0);
    }

    private void dispatchCallbackAdded() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            IThemeCallback callback = mCallbacks.get(i);
            try {
                if (callback != null) {
                    callback.onCallbackAdded(getThemeMode(), getTheme(getAccentColor()));
                }
            } catch (RemoteException ex) {
                // Callback is dead
            } catch (NullPointerException e) {
                Log.e(TAG, "NullPointer while calling onCallbackAdded: ", e);
            }
        }
    }

    private void dispatchThemeSettingChanged() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            IThemeCallback callback = mCallbacks.get(i);
            try {
                if (callback != null) {
                    callback.onThemeChanged(getThemeMode(), getTheme(getAccentColor()));
                }
            } catch (RemoteException ex) {
                // Callback is dead
            } catch (NullPointerException e) {
                Log.e(TAG, "NullPointer while calling onCallbackAdded: ", e);
            }
        }
    }

    private class ThemeObserver extends ContentObserver {
        private boolean mRegistered;

        public ThemeObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            dispatchThemeSettingChanged();
        }

        protected void register() {
            if (!mRegistered) {
                mContext.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.THEME_PRIMARY_COLOR), true, this);
                mContext.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.THEME_ACCENT_COLOR), true, this);
                mRegistered = true;
                dispatchCallbackAdded();
            }
        }

        protected void unregister() {
            if (mRegistered) {
                mContext.getContentResolver().unregisterContentObserver(this);
                mRegistered = false;
            }
        }
    }
}
