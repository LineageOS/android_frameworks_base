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
package android.app;

import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.ArrayList;

/**
 * A class that handles theme changes
 * <p>
 * Use {@link android.content.Context#getSystemService(java.lang.String)}
 * with argument {@link android.content.Context#THEME_SERVICE} to get
 * an instance of this class.
 *
 * Usage: import and create a final {@link IThemeCallback.Stub()} and implement your logic in
 * {@link IThemeCalback#onCallbackAdded(int, int)} and {@link IThemeCallback#onThemeChanged(int, int)}.
 * Then add your callback to the Theme manager.
 *
 * // Create a Handler instance to run on the UI thread
 * private Handler mHandler = new Handler();
 *
 * // Create a theme resId.
 * private int mThemeResId;
 *
 * // define a final callback
 * private final IThemeCallback mCallback = new IThemeCallback.Stub() {
 *
 *      @Overrde
 *      public void onCallbackAdded(int themeMode, int color) {
 *          // Usually this method calls {@link IThemeCallback#onThemeChanged(int, int)}
 *          // Unless special handling is needed for when the callback is registered the first time.
 *          onThemeChanged(themeMode, color);
 *      }
 *
 *      @Override
 *      public void onThemeChanged(int themeMode, int color) {
 *          // @param themeMode primary theme setting value.
 *          // Can be 0 (default theme), 1 (dark theme) or 2 (pixel theme).
 *          // @param color theme style resId.
 *          //
 *          // Your method to handle activity recreating and acquiring the theme style resId.
 *          // IMPORTANT! This method does not run inside the UI thread.
 *          // You will have to make sure that all the methods called from here run inside the UI thread.
 *          mThemeResId = color;
 *          mHandler.post(new Runnable() { // Post to the UI thread
 *              @Override
 *              public void run() {
 *                  recreateActivity();
 *              }
 *          });
 *      }
 * }
 *
 * private void recreateActivity() {
 *     getActivity().recreate();
 * }
 *
 * // Add callback to theme manager
 * // IMPORTANT! Make sure callback is registered and theme is applied BEFORE super.onCreate(Bundle); is called!
 * @Override
 * protected void onCreate(Bundle savedState) {
 *     ThemeManager manager = (ThemeManager) context.getSystemService(Context.THEME_SERVICE);
 *     manager.addCallback(mCallback);
 *     setTheme(Your default theme resId);
 *     getTheme().applyStyle(mThemeResId, true // Override the attributes);
 *     super.onCreate(savedState);
 *     // Rest of your onCreate method
 * }
 *
 * @author Anas Karbila
 * @hide
 */
public class ThemeManager {

    private static final String TAG = "ThemeManager";

    private Context mContext;
    private IThemeService mService;

    public ThemeManager(Context context, IThemeService service) {
        mContext = context;
        mService = service;
    }

    public static boolean isOverlayEnabled() {
        final IOverlayManager om = IOverlayManager.Stub.asInterface(ServiceManager
                .getService("overlay"));
        try {
            return !om.getAllOverlays(0).isEmpty();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void addCallback(IThemeCallback callback) {
        if (mService != null) {
            try {
                mService.addCallback(callback);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to dispatch callback");
            }
        }
    }
}
