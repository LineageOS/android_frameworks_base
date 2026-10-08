/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.display;


import static android.app.PropertyInvalidatedCache.MODULE_SYSTEM;
import static android.hardware.display.DisplayManager.EventType;
import static android.Manifest.permission.MANAGE_DISPLAYS;
import static android.view.Display.HdrCapabilities.HdrType;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityThread;
import android.app.PropertyInvalidatedCache;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.hardware.OverlayProperties;
import android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.graphics.common.DisplayDecorationSupport;
import android.media.projection.IMediaProjection;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.sysprop.DisplayProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.display.feature.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Manager communication with the display manager service on behalf of
 * an application process.  You're probably looking for {@link DisplayManager}.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodPartiallyAllowlisted
@android.ravenwood.annotation.RavenwoodKeepPartialClass
public final class DisplayManagerGlobal {
    private static final String TAG = "DisplayManager";

    private static final String EXTRA_LOGGING_PACKAGE_NAME =
            DisplayProperties.debug_vri_package().orElse(null);
    private static String sCurrentPackageName = ActivityThread.currentPackageName();
    // To enable these logs, run:
    // adb shell setprop persist.debug.vri_package <package_name>
    private static boolean sExtraDisplayListenerLogging = initExtraLogging();

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.DisplayManager DEBUG && adb reboot'
    private static final boolean DEBUG = DisplayManager.DEBUG || sExtraDisplayListenerLogging;


    @IntDef(prefix = {"EVENT_DISPLAY_"}, flag = true, value = {
            EVENT_DISPLAY_CONNECTED,
            EVENT_DISPLAY_ADDED,
            EVENT_DISPLAY_BASIC_CHANGED,
            EVENT_DISPLAY_REFRESH_RATE_CHANGED,
            EVENT_DISPLAY_STATE_CHANGED,
            EVENT_DISPLAY_COMMITTED_STATE_CHANGED,
            EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED,
            EVENT_DISPLAY_BRIGHTNESS_CHANGED,
            EVENT_DISPLAY_REMOVED,
            EVENT_DISPLAY_DISCONNECTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisplayEvent {}

    /**
     * The order of the events here is important.
     * It determines the order in which they will be handled.
     * See {@link DisplayListenerDelegate#handleDisplayEventsInner}
     */
    public static final int EVENT_DISPLAY_CONNECTED = 1 << 0;
    public static final int EVENT_DISPLAY_ADDED = 1 << 1;
    public static final int EVENT_DISPLAY_BASIC_CHANGED = 1 << 2;
    public static final int EVENT_DISPLAY_REFRESH_RATE_CHANGED = 1 << 3;
    public static final int EVENT_DISPLAY_STATE_CHANGED = 1 << 4;
    public static final int EVENT_DISPLAY_COMMITTED_STATE_CHANGED = 1 << 5;
    public static final int EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED = 1 << 6;
    public static final int EVENT_DISPLAY_BRIGHTNESS_CHANGED = 1 << 7;
    public static final int EVENT_DISPLAY_REMOVED = 1 << 8;
    public static final int EVENT_DISPLAY_DISCONNECTED = 1 << 9;

    @LongDef(prefix = {"INTERNAL_EVENT_FLAG_"}, flag = true, value = {
            INTERNAL_EVENT_FLAG_TOPOLOGY_UPDATED,
            INTERNAL_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED,
            INTERNAL_EVENT_FLAG_DISPLAY_ADDED,
            INTERNAL_EVENT_FLAG_DISPLAY_BASIC_CHANGED,
            INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE,
            INTERNAL_EVENT_FLAG_DISPLAY_STATE,
            INTERNAL_EVENT_FLAG_DISPLAY_COMMITTED_STATE_CHANGED,
            INTERNAL_EVENT_FLAG_DISPLAY_HDR_SDR_RATIO_CHANGED,
            INTERNAL_EVENT_FLAG_DISPLAY_BRIGHTNESS_CHANGED,
            INTERNAL_EVENT_FLAG_DISPLAY_REMOVED,
            INTERNAL_EVENT_FLAG_DISPLAY_SNAPSHOT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InternalEventFlag {}

    public static final long INTERNAL_EVENT_FLAG_TOPOLOGY_UPDATED = 1L << 0;
    public static final long INTERNAL_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED = 1L << 1;
    public static final long INTERNAL_EVENT_FLAG_DISPLAY_ADDED = 1L << 2;
    public static final long INTERNAL_EVENT_FLAG_DISPLAY_BASIC_CHANGED = 1L << 3;
    public static final long INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE = 1L << 4;
    public static final long INTERNAL_EVENT_FLAG_DISPLAY_STATE = 1L << 5;
    public static final long INTERNAL_EVENT_FLAG_DISPLAY_COMMITTED_STATE_CHANGED = 1L << 6;
    public static final long INTERNAL_EVENT_FLAG_DISPLAY_HDR_SDR_RATIO_CHANGED = 1L << 7;
    public static final long INTERNAL_EVENT_FLAG_DISPLAY_BRIGHTNESS_CHANGED = 1L << 8;
    public static final long INTERNAL_EVENT_FLAG_DISPLAY_REMOVED = 1L << 9;
    public static final long INTERNAL_EVENT_FLAG_DISPLAY_SNAPSHOT = 1L << 10;

    @UnsupportedAppUsage
    private static DisplayManagerGlobal sInstance;

    // Guarded by mLock
    private boolean mDispatchNativeCallbacks = false;
    private float mNativeCallbackReportedRefreshRate;
    private final Object mLock = new Object();

    @UnsupportedAppUsage
    private final IDisplayManager mDm;
    private final @Nullable DisplayManagerInternal mDmInternal;

    // This field is volatile to allow reading it without acquiring mLock. Since getDisplayInfo is
    // called frequently, avoiding lock acquisition on the read path improves performance.
    // The happens-before guarantee of volatile ensures that once mCallback is set, all subsequent
    // reads will see the updated value.
    private volatile DisplayManagerCallback mCallback;
    private @InternalEventFlag long mRegisteredInternalEventFlag = 0;
    private final CopyOnWriteArrayList<DisplayListenerDelegate> mDisplayListeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<DisplayTopologyListenerDelegate> mTopologyListeners =
            new CopyOnWriteArrayList<>();

    private final SparseArray<DisplayInfo> mDisplayInfoCache = new SparseArray<>();
    private final ColorSpace mWideColorSpace;
    private final OverlayProperties mOverlayProperties;

    private int mWifiDisplayScanNestCount;

    private final Binder mToken = new Binder();

    // Guarded by mLock
    private boolean mShouldImplicitlyRegisterRrChanges = false;
    // Guarded by mLock
    private boolean mShouldImplicitlyRegisterAdded = false;
    // Guarded by mLock
    private boolean mShouldImplicitlyRegisterConnected = false;
    // Guarded by mLock
    private final DisplayIdsCache mDisplayIdsCache;

    @VisibleForTesting
    public DisplayManagerGlobal(IDisplayManager dm) {
        if (Process.myUid() != Process.SYSTEM_UID // cache display IDs for non-system processes
                && Flags.displayIdsCache()) {
            mDisplayIdsCache = new DisplayIdsCache();
        } else {
            mDisplayIdsCache = null;
        }

        mDm = dm;
        if (Process.myUid() == Process.SYSTEM_UID) {
            mDmInternal = LocalServices.getService(DisplayManagerInternal.class);
        } else {
            mDmInternal = null;
        }
        initExtraLogging();

        try {
            mWideColorSpace =
                    ColorSpace.get(
                            ColorSpace.Named.values()[mDm.getPreferredWideGamutColorSpaceId()]);
            mOverlayProperties = mDm.getOverlaySupport();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    private PropertyInvalidatedCache<Integer, DisplayInfo> mDisplayCache =
            new PropertyInvalidatedCache<>(
                    new PropertyInvalidatedCache
                            .Args(MODULE_SYSTEM)
                            .maxEntries(8)
                            .api(CACHE_KEY_DISPLAY_INFO_API)
                            .isolateUids(false)
                            .cacheNulls(Flags.enableNullDisplayInfoCache()),
                    CACHE_KEY_DISPLAY_INFO_API, null) {

                @Override
                public DisplayInfo recompute(Integer id) {
                    try {
                        return mDm.getDisplayInfo(id);
                    } catch (RemoteException ex) {
                        throw ex.rethrowFromSystemServer();
                    }
                }
            };

    /**
     * Gets an instance of the display manager global singleton.
     *
     * This method is actually unsupported on Ravenwood, however to support
     * {@link android.app.ResourcesManager} we make this method always return null.
     *
     * @return The display manager instance, may be null early in system startup
     * before the display manager has been fully initialized.
     */
    @UnsupportedAppUsage
    @android.ravenwood.annotation.RavenwoodKeep
    public static DisplayManagerGlobal getInstance() {
        synchronized (DisplayManagerGlobal.class) {
            if (sInstance == null) {
                IBinder b = ServiceManager.getService(Context.DISPLAY_SERVICE);
                if (b != null) {
                    sInstance = new DisplayManagerGlobal(IDisplayManager.Stub.asInterface(b));
                }
            }
            return sInstance;
        }
    }

    /**
     * Get information about a particular logical display.
     *
     * @param displayId The logical display id.
     * @return Information about the specified display, or null if it does not exist.
     * This object belongs to an internal cache and should be treated as if it were immutable.
     */
    @UnsupportedAppUsage
    @Nullable
    public DisplayInfo getDisplayInfo(int displayId) {
        if (mDmInternal != null) {
            if (DEBUG) {
                Log.d(TAG, "getDisplayInfo: displayId=" + displayId + ", using internal service");
            }
            return mDmInternal.getDisplayInfo(displayId);
        }
        return getDisplayInfoInternal(displayId);
    }

    /**
     * Gets information about a particular logical display
     * See {@link getDisplayInfo}
     */
    private @Nullable DisplayInfo getDisplayInfoInternal(int displayId) {
        DisplayInfo info = null;
        if (mDisplayCache != null) {
            info = mDisplayCache.query(displayId);
        } else {
            try {
                if (DEBUG) {
                    Log.d(TAG, "getDisplayInfo: displayId=" + displayId
                            + ", package=" + ActivityThread.currentPackageName());
                }
                info = mDm.getDisplayInfo(displayId);
            } catch (RemoteException ex) {
                ex.rethrowFromSystemServer();
            }
        }
        if (info == null) {
            return null;
        }

        registerCallbackIfNeeded();

        if (DEBUG) {
            Log.d(TAG, "getDisplayInfo: displayId=" + displayId + ", info=" + info);
        }
        return info;
    }

    /**
     * Gets all currently valid logical display ids.
     *
     * @return An array containing all display ids.
     */
    @UnsupportedAppUsage
    public int[] getDisplayIds() {
        return getDisplayIds(/* includeDisabled= */ false);
    }

    /**
     * Gets all currently valid logical display ids.
     *
     * @param includeDisabled True if the returned list of displays includes disabled displays.
     * @return An array containing all display ids.
     */
    public int[] getDisplayIds(boolean includeDisabled) {
        if (mDmInternal != null) {
            if (DEBUG) {
                Log.d(TAG, "getDisplayIds: includeDisabled=" + includeDisabled
                        + ", using internal service");
            }
            return mDmInternal.getDisplayIds(includeDisabled);
        }
        try {
            synchronized (mLock) {
                if (mDisplayIdsCache != null) {
                    // If caching is not enabled for the requested type of display IDs,
                    // then implicitly register for the corresponding events.
                    // This allows the cache to be enabled and populated when the first snapshot
                    // arrives, even if no explicit display listener was registered for these
                    // specific event types yet.
                    if (!mDisplayIdsCache.isCachingEnabledLocked(includeDisabled)) {
                        if (includeDisabled) {
                            mShouldImplicitlyRegisterConnected = true;
                        } else {
                            mShouldImplicitlyRegisterAdded = true;
                        }
                        // updateCallbackIfNeededLocked will enable caching now.
                        registerCallbackIfNeeded();
                        updateCallbackIfNeededLocked();
                    }
                    // If the cache is valid - there is no reason to make getDisplayIds binder call.
                    if (mDisplayIdsCache.isCacheValidLocked(includeDisabled)) {
                        if (DEBUG) {
                            Slog.d(TAG, "getDisplayIds from cache"
                                    + " includeDisabled=" + includeDisabled
                                    + " package=" + ActivityThread.currentPackageName());
                        }
                        if (includeDisabled) {
                            return mDisplayIdsCache.getConnectedLocked();
                        } else {
                            return mDisplayIdsCache.getAddedLocked();
                        }
                    }
                }
                if (DEBUG) {
                    Slog.d(TAG, "getDisplayIds from API includeDisabled=" + includeDisabled
                            + " package=" + ActivityThread.currentPackageName());
                }
                int[] displayIds = mDm.getDisplayIds(includeDisabled);
                registerCallbackIfNeeded();
                return displayIds;
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Check if specified UID's content is present on display and should be granted access to it.
     *
     * @param uid UID to be checked.
     * @param displayId id of the display where presence of the content is checked.
     * @return {@code true} if UID is present on display, {@code false} otherwise.
     */
    public boolean isUidPresentOnDisplay(int uid, int displayId) {
        try {
            return mDm.isUidPresentOnDisplay(uid, displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets information about a logical display.
     *
     * The display metrics may be adjusted to provide compatibility
     * for legacy applications or limited screen areas.
     *
     * @param displayId The logical display id.
     * @param daj The compatibility info and activityToken.
     * @return The display object, or null if there is no display with the given id.
     */
    public Display getCompatibleDisplay(int displayId, DisplayAdjustments daj) {
        DisplayInfo displayInfo = getDisplayInfo(displayId);
        if (displayInfo == null) {
            return null;
        }
        return new Display(this, displayId, displayInfo, daj);
    }

    /**
     * Gets information about a logical display.
     *
     * The display metrics may be adjusted to provide compatibility
     * for legacy applications or limited screen areas.
     *
     * @param displayId The logical display id.
     * @param resources Resources providing compatibility info.
     * @return The display object, or null if there is no display with the given id.
     */
    public Display getCompatibleDisplay(int displayId, Resources resources) {
        DisplayInfo displayInfo = getDisplayInfo(displayId);
        if (displayInfo == null) {
            return null;
        }
        return new Display(this, displayId, displayInfo, resources);
    }

    /**
     * Gets information about a logical display without applying any compatibility metrics.
     *
     * @param displayId The logical display id.
     * @return The display object, or null if there is no display with the given id.
     */
    @UnsupportedAppUsage
    public Display getRealDisplay(int displayId) {
        return getCompatibleDisplay(displayId, DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
    }

    /**
     * Register a listener for display-related changes.
     *
     * @param listener The listener that will be called when display changes occur.
     * @param handler Handler for the thread that will be receiving the callbacks. May be null.
     * If null, listener will use the handler for the current thread, and if still null,
     * the handler for the main thread.
     * If that is still null, a runtime exception will be thrown.
     * @param packageName of the calling package.
     * @param isEventFilterExplicit Indicates if the client explicitly supplied the display events
     *                              to be subscribed to.
     */
    public void registerDisplayListener(@NonNull DisplayListener listener,
            @Nullable Handler handler, @InternalEventFlag long internalEventFlagsMask,
            String packageName, boolean isEventFilterExplicit) {
        Looper looper = getLooperForHandler(handler);
        Handler springBoard = Handler.createAsync(looper);
        registerDisplayListener(listener, new HandlerExecutor(springBoard), internalEventFlagsMask,
                packageName, isEventFilterExplicit);
    }

    /**
     * Register a listener for display-related changes.
     *
     * @param listener The listener that will be called when display changes occur.
     * @param handler Handler for the thread that will be receiving the callbacks. May be null.
     * If null, listener will use the handler for the current thread, and if still null,
     * the handler for the main thread.
     * If that is still null, a runtime exception will be thrown.
     * @param internalEventFlagsMask Mask of events to be listened to.
     * @param packageName of the calling package.
     */
    public void registerDisplayListener(@NonNull DisplayListener listener,
            @Nullable Handler handler, @InternalEventFlag long internalEventFlagsMask,
            String packageName) {
        registerDisplayListener(listener, handler, internalEventFlagsMask, packageName, true);
    }


    /**
     * Register a listener for display-related changes.
     *
     * @param listener The listener that will be called when display changes occur.
     * @param executor Executor for the thread that will be receiving the callbacks. Cannot be null.
     * @param internalEventFlagsMask Mask of events to be listened to.
     * @param packageName of the calling package.
     * @param isEventFilterExplicit Indicates if the explicit events to be subscribed to
     *                                     were supplied or not
     */
    public void registerDisplayListener(@NonNull DisplayListener listener,
            @NonNull Executor executor, @InternalEventFlag long internalEventFlagsMask,
            String packageName, boolean isEventFilterExplicit) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        if (internalEventFlagsMask == 0) {
            throw new IllegalArgumentException("The set of events to listen to must not be empty.");
        }

        if (extraLogging()) {
            Slog.i(TAG, "Registering Display Listener: "
                    + Long.toBinaryString(internalEventFlagsMask)
                    + ", packageName: " + packageName);
        }

        synchronized (mLock) {
            int index = findDisplayListenerLocked(listener);
            DisplayListenerDelegate delegate;
            if (index < 0) {
                delegate = new DisplayListenerDelegate(listener, executor,
                        internalEventFlagsMask, packageName, isEventFilterExplicit);
                mDisplayListeners.add(delegate);
                registerCallbackIfNeeded();
            } else {
                delegate = mDisplayListeners.get(index);
                delegate.setEventsMask(internalEventFlagsMask);
            }
            updateCallbackIfNeededLocked();
        }
        maybeLogAllDisplayListeners();
    }


    /**
     * Registers all the clients to INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE events if qualified
     */
    public void registerForRefreshRateChanges() {
        if (!Flags.delayImplicitRrRegistrationUntilRrAccessed()) {
            return;
        }
        synchronized (mLock) {
            if (!mShouldImplicitlyRegisterRrChanges) {
                mShouldImplicitlyRegisterRrChanges = true;
                Slog.i(TAG, "Implicitly registering for refresh rate");
                updateCallbackIfNeededLocked();
            }
        }
    }

    public void unregisterDisplayListener(DisplayListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        if (extraLogging()) {
            Slog.i(TAG, "Unregistering Display Listener: " + listener);
        }

        synchronized (mLock) {
            int index = findDisplayListenerLocked(listener);
            if (index >= 0) {
                DisplayListenerDelegate d = mDisplayListeners.get(index);
                d.clearEvents();
                mDisplayListeners.remove(index);
                updateCallbackIfNeededLocked();
            }
        }
        maybeLogAllDisplayListeners();
    }

    private void maybeLogAllDisplayListeners() {
        if (!extraLogging()) {
            return;
        }

        Slog.i(TAG, "Currently Registered Display Listeners:");
        int i = 0;
        for (DisplayListenerDelegate d : mDisplayListeners) {
            Slog.i(TAG, i + ": " + d);
            i++;
        }
    }

    private void maybeLogAllTopologyListeners() {
        if (!extraLogging()) {
            return;
        }
        Slog.i(TAG, "Currently registered display topology listeners:");
        int i = 0;
        for (DisplayTopologyListenerDelegate d : mTopologyListeners) {
            Slog.i(TAG, i + ": " + d);
            i++;
        }
    }

    /**
     * Called when there is a display-related window configuration change. Reroutes the event from
     * WindowManager to make sure the {@link Display} fields are up-to-date in the last callback.
     * @param displayId the logical display that was changed.
     */
    public void handleDisplayChangeFromWindowManager(int displayId) {
        // There can be racing condition between DMS and WMS callbacks, so force triggering the
        // listener to make sure the client can get the onDisplayChanged callback even if
        // DisplayInfo is not changed (Display read from both DisplayInfo and WindowConfiguration).
        handleDisplayEvents(displayId, EVENT_DISPLAY_BASIC_CHANGED, true /* forceUpdate */);
    }

    private static Looper getLooperForHandler(@Nullable Handler handler) {
        Looper looper = handler != null ? handler.getLooper() : Looper.myLooper();
        if (looper == null) {
            looper = Looper.getMainLooper();
        }
        if (looper == null) {
            throw new RuntimeException("Could not get Looper for the UI thread.");
        }
        return looper;
    }

    private int findDisplayListenerLocked(DisplayListener listener) {
        final int numListeners = mDisplayListeners.size();
        for (int i = 0; i < numListeners; i++) {
            if (mDisplayListeners.get(i).mListener == listener) {
                return i;
            }
        }
        return -1;
    }

    @InternalEventFlag
    private long calculateEventsMaskLocked() {
        long mask = 0;
        if (mShouldImplicitlyRegisterConnected) {
            mask |= INTERNAL_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED;
        }
        if (mShouldImplicitlyRegisterAdded) {
            mask |= INTERNAL_EVENT_FLAG_DISPLAY_ADDED | INTERNAL_EVENT_FLAG_DISPLAY_REMOVED;
        }
        final int numListeners = mDisplayListeners.size();
        for (int i = 0; i < numListeners; i++) {
            DisplayListenerDelegate displayListenerDelegate = mDisplayListeners.get(i);
            if (!Flags.delayImplicitRrRegistrationUntilRrAccessed()
                    || mShouldImplicitlyRegisterRrChanges) {
                displayListenerDelegate.implicitlyRegisterForRRChanges();
            }
            mask |= displayListenerDelegate.internalEventFlagsMask;
        }

        if (mDispatchNativeCallbacks) {
            mask |= INTERNAL_EVENT_FLAG_DISPLAY_ADDED
                    | INTERNAL_EVENT_FLAG_DISPLAY_BASIC_CHANGED
                    | INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE
                    | INTERNAL_EVENT_FLAG_DISPLAY_REMOVED;
        }
        if (!mTopologyListeners.isEmpty()) {
            mask |= INTERNAL_EVENT_FLAG_TOPOLOGY_UPDATED;
        }
        return mask;
    }

    private DisplayTopologyListenerDelegate findTopologyListenerLocked(
            @NonNull Consumer<DisplayTopology> listener) {
        for (DisplayTopologyListenerDelegate delegate : mTopologyListeners) {
            if (delegate.mListener == listener) {
                return delegate;
            }
        }
        return null;
    }

    private void registerCallbackIfNeeded() {
        if (mCallback == null) {
            mCallback = new DisplayManagerCallback();
            synchronized (mLock) {
                updateCallbackIfNeededLocked();
            }
        }
    }

    private void updateCallbackIfNeededLocked() {
        long mask = calculateEventsMaskLocked();
        if (DEBUG) {
            Log.d(TAG, "Mask for listener: " + mask
                        + " package=" + ActivityThread.currentPackageName());
        }
        if (mask != mRegisteredInternalEventFlag) {
            if (mDisplayIdsCache != null) {
                // If any of the listeners are subscribed to these events, then we can have cache.
                mDisplayIdsCache.setConnectedCachingEnabledLocked((
                        mask & INTERNAL_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED) != 0);
                mDisplayIdsCache.setAddedCachingEnabledLocked(
                        (mask & INTERNAL_EVENT_FLAG_DISPLAY_ADDED) != 0
                        && (mask & INTERNAL_EVENT_FLAG_DISPLAY_REMOVED) != 0);
            }
            try {
                mDm.registerCallbackWithEventMask(mCallback, mask);
                mRegisteredInternalEventFlag = mask;
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    private void handleDisplayEvents(int displayId, int eventMask, boolean forceUpdate) {
        final DisplayInfo info;
        boolean shouldNotifyNativeListeners  = false;
        synchronized (mLock) {
            info = getDisplayInfoInternal(displayId);
            if ((((eventMask & EVENT_DISPLAY_BASIC_CHANGED) != 0)
                    || (eventMask & EVENT_DISPLAY_REFRESH_RATE_CHANGED) != 0)
                    && mDispatchNativeCallbacks) {
                // Choreographer only supports a single display, so only dispatch refresh rate
                // changes for the default display.
                if (displayId == Display.DEFAULT_DISPLAY) {
                    if (info != null
                            && mNativeCallbackReportedRefreshRate != info.getRefreshRate()) {
                        mNativeCallbackReportedRefreshRate = info.getRefreshRate();
                        shouldNotifyNativeListeners = true;
                    }
                }
            }
            if (mDisplayIdsCache != null) {
                eventMask = mDisplayIdsCache.updateCacheLocked(displayId, eventMask);
                if (eventMask == 0) {
                    return;
                }
            }
        }

        if (shouldNotifyNativeListeners) {
            // Signal native callbacks if we ever set a refresh rate.
            nSignalNativeCallbacks(mNativeCallbackReportedRefreshRate);
        }
        // Accepting an Executor means the listener may be synchronously invoked, so we must
        // not be holding mLock when we do so
        for (DisplayListenerDelegate listener : mDisplayListeners) {
            listener.sendDisplayEvents(displayId, eventMask, info, forceUpdate);
        }
    }

    private void handleDisplaySnapshot(int[] connected, int[] added) {
        if (mDisplayIdsCache == null) {
            return;
        }
        synchronized (mLock) {
            mDisplayIdsCache.updateCacheLocked(connected, added);
        }
    }

    /**
     * Enable a connected display that is currently disabled.
     * @hide
     */
    @RequiresPermission("android.permission.MANAGE_DISPLAYS")
    public void enableConnectedDisplay(int displayId) {
        try {
            mDm.enableConnectedDisplay(displayId);
        } catch (RemoteException ex) {
            Log.e(TAG, "Error trying to enable external display", ex);
        }
    }

    /**
     * Disable a connected display that is currently enabled.
     * @hide
     */
    @RequiresPermission("android.permission.MANAGE_DISPLAYS")
    public void disableConnectedDisplay(int displayId) {
        try {
            mDm.disableConnectedDisplay(displayId);
        } catch (RemoteException ex) {
            Log.e(TAG, "Error trying to enable external display", ex);
        }
    }

    /**
     * Request to power a display OFF or reset it to a power state it supposed to have.
     * @param displayId the id of the display
     * @param state one of {@link android.view.Display#STATE_UNKNOWN} (to reset the state to
     *  the one the display should have had now), {@link android.view.Display#STATE_OFF}.
     * @return true if successful, false otherwise
     * @hide
     */
    @RequiresPermission("android.permission.MANAGE_DISPLAYS")
    public boolean requestDisplayPower(int displayId, int state) {
        try {
            return mDm.requestDisplayPower(displayId, state);
        } catch (RemoteException ex) {
            Log.e(TAG, "Error trying to request display power:"
                    + " state=" + state, ex);
            return false;
        }
    }

    public void startWifiDisplayScan() {
        synchronized (mLock) {
            if (mWifiDisplayScanNestCount++ == 0) {
                registerCallbackIfNeeded();
                try {
                    mDm.startWifiDisplayScan();
                } catch (RemoteException ex) {
                    throw ex.rethrowFromSystemServer();
                }
            }
        }
    }

    public void stopWifiDisplayScan() {
        synchronized (mLock) {
            if (--mWifiDisplayScanNestCount == 0) {
                try {
                    mDm.stopWifiDisplayScan();
                } catch (RemoteException ex) {
                    throw ex.rethrowFromSystemServer();
                }
            } else if (mWifiDisplayScanNestCount < 0) {
                Log.wtf(TAG, "Wifi display scan nest count became negative: "
                        + mWifiDisplayScanNestCount);
                mWifiDisplayScanNestCount = 0;
            }
        }
    }

    public void connectWifiDisplay(String deviceAddress) {
        if (deviceAddress == null) {
            throw new IllegalArgumentException("deviceAddress must not be null");
        }

        try {
            mDm.connectWifiDisplay(deviceAddress);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public void pauseWifiDisplay() {
        try {
            mDm.pauseWifiDisplay();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public void resumeWifiDisplay() {
        try {
            mDm.resumeWifiDisplay();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    @UnsupportedAppUsage
    public void disconnectWifiDisplay() {
        try {
            mDm.disconnectWifiDisplay();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public void renameWifiDisplay(String deviceAddress, String alias) {
        if (deviceAddress == null) {
            throw new IllegalArgumentException("deviceAddress must not be null");
        }

        try {
            mDm.renameWifiDisplay(deviceAddress, alias);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public void forgetWifiDisplay(String deviceAddress) {
        if (deviceAddress == null) {
            throw new IllegalArgumentException("deviceAddress must not be null");
        }

        try {
            mDm.forgetWifiDisplay(deviceAddress);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    @UnsupportedAppUsage
    public WifiDisplayStatus getWifiDisplayStatus() {
        try {
            return mDm.getWifiDisplayStatus();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the HDR types that have been disabled by user.
     * @param userDisabledHdrTypes the HDR types to disable. The HDR types are any of
     */
    public void setUserDisabledHdrTypes(@HdrType int[] userDisabledHdrTypes) {
        try {
            mDm.setUserDisabledHdrTypes(userDisabledHdrTypes);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets whether or not the user disabled HDR types are returned from
     * {@link Display#getHdrCapabilities}.
     *
     * @param areUserDisabledHdrTypesAllowed If true, the user-disabled
     * types are ignored and returned, if the display supports them. If
     * false, the user-disabled types are taken into consideration and
     * are never returned, even if the display supports them.
     */
    public void setAreUserDisabledHdrTypesAllowed(boolean areUserDisabledHdrTypesAllowed) {
        try {
            mDm.setAreUserDisabledHdrTypesAllowed(areUserDisabledHdrTypesAllowed);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether or not the user-disabled HDR types are returned from
     * {@link Display#getHdrCapabilities}.
     */
    public boolean areUserDisabledHdrTypesAllowed() {
        try {
            return mDm.areUserDisabledHdrTypesAllowed();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the HDR formats disabled by the user.
     *
     */
    public int[] getUserDisabledHdrTypes() {
        try {
            return mDm.getUserDisabledHdrTypes();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Resets the implicit registration of refresh rate change callbacks
     *
     */
    public void resetImplicitRefreshRateCallbackStatus() {
        if (Flags.delayImplicitRrRegistrationUntilRrAccessed()) {
            synchronized (mLock) {
                mShouldImplicitlyRegisterRrChanges = false;
            }
        }
    }

    /**
     * Overrides HDR modes for a display device.
     *
     */
    @RequiresPermission(Manifest.permission.ACCESS_SURFACE_FLINGER)
    public void overrideHdrTypes(int displayId, int[] modes) {
        try {
            mDm.overrideHdrTypes(displayId, modes);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }


    public void requestColorMode(int displayId, int colorMode) {
        try {
            mDm.requestColorMode(displayId, colorMode);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public VirtualDisplay createVirtualDisplay(@NonNull Context context, MediaProjection projection,
            @NonNull VirtualDisplayConfig virtualDisplayConfig, VirtualDisplay.Callback callback,
            @Nullable Executor executor) {
        VirtualDisplayCallback callbackWrapper = new VirtualDisplayCallback(callback, executor);
        IMediaProjection projectionToken = projection != null ? projection.getProjection() : null;
        int displayId;
        try {
            displayId = mDm.createVirtualDisplay(virtualDisplayConfig, callbackWrapper,
                    projectionToken, context.getPackageName());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
        return createVirtualDisplayWrapper(virtualDisplayConfig, callbackWrapper,
                displayId);
    }

    /**
     * Create a VirtualDisplay wrapper object for a newly created virtual display ; to be called
     * once the display has been created in system_server.
     */
    @Nullable
    public VirtualDisplay createVirtualDisplayWrapper(VirtualDisplayConfig virtualDisplayConfig,
            IVirtualDisplayCallback callbackWrapper, int displayId) {
        if (displayId < 0) {
            Log.e(TAG, "Could not create virtual display: " + virtualDisplayConfig.getName());
            return null;
        }
        Display display = getRealDisplay(displayId);
        if (display == null) {
            Log.wtf(TAG, "Could not obtain display info for newly created "
                    + "virtual display: " + virtualDisplayConfig.getName());
            try {
                mDm.releaseVirtualDisplay(callbackWrapper);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
            return null;
        }
        if (mDisplayIdsCache != null) {
            synchronized (mLock) {
                // This virtual display is created synchronously, so we assume that
                // the display has already been created when we get here.
                // It is important to add the display id in the cache even before we receive
                // the onDisplayAdded callback, because some apps may expect the display to be
                // available through {@link DisplayManager#getDisplays} immediately after creation.
                mDisplayIdsCache.injectLocked(displayId);
            }
        }
        return new VirtualDisplay(this, display, callbackWrapper,
                virtualDisplayConfig.getSurface());
    }

    public void setVirtualDisplaySurface(IVirtualDisplayCallback token, Surface surface) {
        try {
            mDm.setVirtualDisplaySurface(token, surface);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public void resizeVirtualDisplay(IVirtualDisplayCallback token,
            int width, int height, int densityDpi) {
        try {
            mDm.resizeVirtualDisplay(token, width, height, densityDpi);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Releases virtual display identified token and displayId.
     */
    public void releaseVirtualDisplay(IVirtualDisplayCallback token, int displayId) {
        try {
            mDm.releaseVirtualDisplay(token);
            if (mDisplayIdsCache != null) {
                synchronized (mLock) {
                    mDisplayIdsCache.evictLocked(displayId);
                }
            }
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    void setVirtualDisplayRotation(IVirtualDisplayCallback token, @Surface.Rotation int rotation) {
        try {
            mDm.setVirtualDisplayRotation(token, rotation);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the stable device display size, in pixels.
     */
    public Point getStableDisplaySize() {
        try {
            return mDm.getStableDisplaySize();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves brightness change events.
     */
    public List<BrightnessChangeEvent> getBrightnessEvents(String callingPackage) {
        try {
            ParceledListSlice<BrightnessChangeEvent> events =
                    mDm.getBrightnessEvents(callingPackage);
            if (events == null) {
                return Collections.emptyList();
            }
            return events.getList();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves Brightness Info for the specified display.
     */
    public BrightnessInfo getBrightnessInfo(int displayId) {
        try {
            return mDm.getBrightnessInfo(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the preferred wide gamut color space for all displays.
     * The wide gamut color space is returned from composition pipeline
     * based on hardware capability.
     *
     * @hide
     */
    public ColorSpace getPreferredWideGamutColorSpace() {
        return mWideColorSpace;
    }

    /**
     * Gets the overlay properties for all displays.
     *
     * @hide
     */
    public OverlayProperties getOverlaySupport() {
        return mOverlayProperties;
    }

    /**
     * Sets the global brightness configuration for a given user.
     *
     * @hide
     */
    public void setBrightnessConfigurationForUser(BrightnessConfiguration c, int userId,
            String packageName) {
        try {
            mDm.setBrightnessConfigurationForUser(c, userId, packageName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the brightness configuration for a given display.
     *
     * @hide
     */
    public void setBrightnessConfigurationForDisplay(BrightnessConfiguration c,
            String uniqueDisplayId, int userId, String packageName) {
        try {
            mDm.setBrightnessConfigurationForDisplay(c, uniqueDisplayId, userId, packageName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the brightness configuration for a given display or null if one hasn't been set.
     *
     * @hide
     */
    public BrightnessConfiguration getBrightnessConfigurationForDisplay(String uniqueDisplayId,
            int userId) {
        try {
            return mDm.getBrightnessConfigurationForDisplay(uniqueDisplayId, userId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the global brightness configuration for a given user or null if one hasn't been set.
     *
     * @hide
     */
    public BrightnessConfiguration getBrightnessConfigurationForUser(int userId) {
        try {
            return mDm.getBrightnessConfigurationForUser(userId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the default brightness configuration or null if one hasn't been configured.
     *
     * @hide
     */
    public BrightnessConfiguration getDefaultBrightnessConfiguration() {
        try {
            return mDm.getDefaultBrightnessConfiguration();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the last requested minimal post processing setting for the display with displayId.
     *
     * @hide
     */
    public boolean isMinimalPostProcessingRequested(int displayId) {
        try {
            return mDm.isMinimalPostProcessingRequested(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Temporarily sets the brightness of the display.
     *
     * @param brightness The brightness value from 0.0f to 1.0f.
     *
     * @hide Requires signature permission.
     */
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
    public void setTemporaryBrightness(int displayId, float brightness) {
        try {
            mDm.setTemporaryBrightness(displayId, brightness);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the brightness mode of the display.
     *
     * @param displayId      the id of the display.
     * @param brightnessMode The brightness mode:
     *          - {@link android.provider.Settings.System#SCREEN_BRIGHTNESS_MODE_AUTOMATIC}
     *          - {@link android.provider.Settings.System#SCREEN_BRIGHTNESS_MODE_MANUAL}
     * @return whether the mode successfully changed.
     * @hide
     */
    @RequiresPermission(Manifest.permission.CONFIGURE_DISPLAY_BRIGHTNESS)
    public boolean setTemporaryBrightnessMode(int displayId, int brightnessMode) {
        try {
            return mDm.setTemporaryBrightnessMode(displayId, brightnessMode);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the brightness of the display.
     *
     * @param brightness The brightness value from 0.0f to 1.0f.
     *
     * @hide
     */
    public void setBrightness(int displayId, float brightness) {
        try {
            mDm.setBrightness(displayId, brightness);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see DisplayManager#setBrightness(int, float, int)
     */
    @RequiresPermission(Manifest.permission.WRITE_SETTINGS)
    public void setBrightness(int displayId, float value,
            @DisplayManager.BrightnessUnit int unit) {
        try {
            mDm.setBrightnessByUnit(displayId, value, unit);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Report whether/how the display supports DISPLAY_DECORATION.
     *
     * @param displayId The display whose support is being queried.
     *
     * @hide
     */
    public DisplayDecorationSupport getDisplayDecorationSupport(int displayId) {
        try {
            return mDm.getDisplayDecorationSupport(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the brightness of the display.
     *
     * @param displayId The display from which to get the brightness
     *
     * @hide
     */
    public float getBrightness(int displayId) {
        try {
            return mDm.getBrightness(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see DisplayManager#getBrightness(int, int)
     */
    public float getBrightness(int displayId, @DisplayManager.BrightnessUnit int unit) {
        try {
            return mDm.getBrightnessByUnit(displayId, unit);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Temporarily sets the auto brightness adjustment factor.
     * <p>
     * Requires the {@link android.Manifest.permission#CONTROL_DISPLAY_BRIGHTNESS} permission.
     * </p>
     *
     * @param adjustment The adjustment factor from -1.0 to 1.0.
     *
     * @hide Requires signature permission.
     */
    public void setTemporaryAutoBrightnessAdjustment(float adjustment) {
        try {
            mDm.setTemporaryAutoBrightnessAdjustment(adjustment);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the minimum brightness curve, which guarantess that any brightness curve that dips
     * below it is rejected by the system.
     * This prevent auto-brightness from setting the screen so dark as to prevent the user from
     * resetting or disabling it, and maps lux to the absolute minimum nits that are still readable
     * in that ambient brightness.
     *
     * @return The minimum brightness curve (as lux values and their corresponding nits values).
     */
    public Pair<float[], float[]> getMinimumBrightnessCurve() {
        try {
            Curve curve = mDm.getMinimumBrightnessCurve();
            return Pair.create(curve.getX(), curve.getY());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves ambient brightness stats.
     */
    public List<AmbientBrightnessDayStats> getAmbientBrightnessStats() {
        try {
            ParceledListSlice<AmbientBrightnessDayStats> stats = mDm.getAmbientBrightnessStats();
            if (stats == null) {
                return Collections.emptyList();
            }
            return stats.getList();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the default display mode, according to the refresh rate and the resolution chosen by the
     * user. Persists selected mode.
     * @hide
     */
    @RequiresPermission("android.permission.MODIFY_USER_PREFERRED_DISPLAY_MODE")
    public void setUserPreferredDisplayMode(int displayId, Display.Mode mode) {
        setUserPreferredDisplayMode(displayId, mode, true);
    }

    /**
     * Sets the default display mode, according to the refresh rate and the resolution chosen by the
     * user. Allows to set display mode without persisting.
     * @hide
     */
    @RequiresPermission("android.permission.MODIFY_USER_PREFERRED_DISPLAY_MODE")
    public void setUserPreferredDisplayMode(int displayId, Display.Mode mode, boolean storeMode) {
        try {
            mDm.setUserPreferredDisplayMode(displayId, mode, storeMode);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Resets the default display mode from persistence
     * @hide
     */
    @RequiresPermission("android.permission.MODIFY_USER_PREFERRED_DISPLAY_MODE")
    public void resetUserPreferredDisplayMode(int displayId) {
        try {
            mDm.resetUserPreferredDisplayMode(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user preferred display mode.
     */
    public Display.Mode getUserPreferredDisplayMode(int displayId) {
        try {
            return mDm.getUserPreferredDisplayMode(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the system preferred display mode.
     */
    public Display.Mode getSystemPreferredDisplayMode(int displayId) {
        try {
            return mDm.getSystemPreferredDisplayMode(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the {@link HdrConversionMode} for the device.
     */
    public void setHdrConversionMode(@NonNull HdrConversionMode hdrConversionMode) {
        try {
            mDm.setHdrConversionMode(hdrConversionMode);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the {@link HdrConversionMode} of the device, which is set by the user.
     * The HDR conversion mode chosen by user is returned irrespective of whether HDR conversion
     * is disabled by an app.
     */
    public HdrConversionMode getHdrConversionModeSetting() {
        try {
            return mDm.getHdrConversionModeSetting();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the {@link HdrConversionMode} of the device.
     */
    public HdrConversionMode getHdrConversionMode() {
        try {
            return mDm.getHdrConversionMode();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the HDR output types supported by the device.
     */
    public @HdrType int[] getSupportedHdrOutputTypes() {
        try {
            return mDm.getSupportedHdrOutputTypes();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * When enabled the app requested display resolution and refresh rate is always selected
     * in DisplayModeDirector regardless of user settings and policies for low brightness, low
     * battery etc.
     */
    public void setShouldAlwaysRespectAppRequestedMode(boolean enabled) {
        try {
            mDm.setShouldAlwaysRespectAppRequestedMode(enabled);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether DisplayModeDirector is running in a mode which always selects the app
     * requested display mode and ignores user settings and policies for low brightness, low
     * battery etc.
     */
    public boolean shouldAlwaysRespectAppRequestedMode() {
        try {
            return mDm.shouldAlwaysRespectAppRequestedMode();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the refresh rate switching type.
     *
     * @hide
     */
    public void setRefreshRateSwitchingType(@DisplayManager.SwitchingType int newValue) {
        try {
            mDm.setRefreshRateSwitchingType(newValue);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the refresh rate switching type.
     *
     * @hide
     */
    @DisplayManager.SwitchingType
    public int getRefreshRateSwitchingType() {
        try {
            return mDm.getRefreshRateSwitchingType();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets allowed display mode ids
     *
     * @hide
     */
    @RequiresPermission("android.permission.RESTRICT_DISPLAY_MODES")
    public void requestDisplayModes(int displayId, @Nullable int[] modeIds) {
        try {
            mDm.requestDisplayModes(mToken, displayId, modeIds);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets max allowed refresh rate
     *
     * @hide
     */
    @RequiresPermission("android.permission.RESTRICT_DISPLAY_MODES")
    public void requestMaxRefreshRate(int displayId, float maxRefreshRate) {
        try {
            mDm.requestMaxRefreshRate(mToken, displayId, maxRefreshRate);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @param displayId The ID of the display
     * @return The highest HDR/SDR ratio of the ratios defined in Display Device Config. If no
     * HDR/SDR map is defined, this always returns 1.
     */
    @FlaggedApi(com.android.server.display.feature.flags.Flags.FLAG_HIGHEST_HDR_SDR_RATIO_API)
    public float getHighestHdrSdrRatio(int displayId) {
        try {
            return mDm.getHighestHdrSdrRatio(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see DisplayManager#getDozeBrightnessSensorValueToBrightness
     */
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
    @Nullable
    public float[] getDozeBrightnessSensorValueToBrightness(int displayId) {
        try {
            return mDm.getDozeBrightnessSensorValueToBrightness(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see DisplayManager#getDefaultDozeBrightness
     */
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_BRIGHTNESS)
    @FloatRange(from = 0f, to = 1f)
    public float getDefaultDozeBrightness(int displayId) {
        try {
            return mDm.getDefaultDozeBrightness(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see DisplayManager#setExternalDisplayConnectionPreference(String, int)
     */
    @RequiresPermission(MANAGE_DISPLAYS)
    public void setExternalDisplayConnectionPreference(String uniqueId, int connectionPreference) {
        try {
            mDm.setConnectionPreference(uniqueId, connectionPreference);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see DisplayManager#getExternalDisplayConnectionPreference(String)
     */
    public int getExternalDisplayConnectionPreference(String uniqueId) {
        try {
            return mDm.getConnectionPreference(uniqueId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see DisplayManager#setUserPreferredHdrMode(int, int)
     */
    @RequiresPermission(MANAGE_DISPLAYS)
    public void setUserPreferredHdrMode(
            int displayId, @DisplayManager.HdrPreference int hdrPreference) {
        try {
            mDm.setUserPreferredHdrMode(displayId, hdrPreference);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see DisplayManager#getUserPreferredHdrMode(int)
     */
    @RequiresPermission(MANAGE_DISPLAYS)
    @DisplayManager.HdrPreference
    public int getUserPreferredHdrMode(int displayId) {
        try {
            return mDm.getUserPreferredHdrMode(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see DisplayManager#getDisplayTopology
     */
    @Nullable
    public DisplayTopology getDisplayTopology() {
        try {
            return mDm.getDisplayTopology();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see DisplayManager#setDisplayTopology
     */
    @RequiresPermission(MANAGE_DISPLAYS)
    public void setDisplayTopology(DisplayTopology topology) {
        if (topology == null) {
            throw new IllegalArgumentException("Topology must not be null");
        }
        try {
            mDm.setDisplayTopology(topology);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see DisplayManager#registerTopologyListener
     */
    public void registerTopologyListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<DisplayTopology> listener, String packageName) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (extraLogging()) {
            Slog.i(TAG, "Registering display topology listener: packageName=" + packageName);
        }
        synchronized (mLock) {
            DisplayTopologyListenerDelegate delegate = findTopologyListenerLocked(listener);
            if (delegate == null) {
                mTopologyListeners.add(new DisplayTopologyListenerDelegate(listener, executor,
                        packageName));
                registerCallbackIfNeeded();
                updateCallbackIfNeededLocked();
            }
        }
        maybeLogAllTopologyListeners();
    }

    /**
     * @see DisplayManager#unregisterTopologyListener
     */
    public void unregisterTopologyListener(@NonNull Consumer<DisplayTopology> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (extraLogging()) {
            Slog.i(TAG, "Unregistering display topology listener: " + listener);
        }
        synchronized (mLock) {
            DisplayTopologyListenerDelegate delegate = findTopologyListenerLocked(listener);
            if (delegate != null) {
                mTopologyListeners.remove(delegate);
                updateCallbackIfNeededLocked();
            }
        }
        maybeLogAllTopologyListeners();
    }

    private final class DisplayManagerCallback extends IDisplayManagerCallback.Stub {
        @Override
        public void onDisplayEvent(int displayId, int eventMask) {
            if (DEBUG) {
                Log.d(TAG, "onDisplayEvent: displayId=" + displayId + ", event="
                        + eventsToString(eventMask)
                        + " package=" + ActivityThread.currentPackageName());
            }
            handleDisplayEvents(displayId, eventMask, false /* forceUpdate */);
        }

        @Override
        public void onTopologyChanged(DisplayTopology topology) {
            if (DEBUG) {
                Log.d(TAG, "onTopologyChanged: " + topology
                        + " package=" + ActivityThread.currentPackageName());
            }
            for (DisplayTopologyListenerDelegate listener : mTopologyListeners) {
                listener.onTopologyChanged(topology);
            }
        }

        @Override
        public void onDisplaySnapshot(int[] connected, int[] added) {
            if (DEBUG) {
                Log.d(TAG, "onDisplaySnapshot: connected[" + Arrays.toString(connected) + "]"
                        + " added[" + Arrays.toString(added) + "]"
                        + " package=" + ActivityThread.currentPackageName());
            }
            handleDisplaySnapshot(connected, added);
        }
    }

    @VisibleForTesting
    public static final class DisplayListenerDelegate {
        @VisibleForTesting public volatile long internalEventFlagsMask;

        private final DisplayListener mListener;

        // Indicates if the client explicitly supplied the display events to be subscribed to.
        private final boolean mIsEventFilterExplicit;

        private final DisplayInfo mDisplayInfo = new DisplayInfo();
        private final Executor mExecutor;
        private final AtomicLong mGenerationId = new AtomicLong(1);
        private final String mPackageName;

        DisplayListenerDelegate(DisplayListener listener, @NonNull Executor executor,
                @InternalEventFlag long internalEventFlag, String packageName,
                boolean isEventFilterExplicit) {
            mExecutor = executor;
            mListener = listener;
            internalEventFlagsMask = internalEventFlag;
            mPackageName = packageName;
            mIsEventFilterExplicit = isEventFilterExplicit;
        }

        void sendDisplayEvents(int displayId, int eventMask, @Nullable DisplayInfo info,
                boolean forceUpdate) {
            if (extraLogging()) {
                Slog.i(TAG, "Sending Display Events: " + eventsToString(eventMask));
            }

            long generationId = this.mGenerationId.get();
            mExecutor.execute(() -> {
                // If the generation id's don't match we were canceled
                if (generationId == this.mGenerationId.get()) {
                    handleDisplayEventsInner(displayId, eventMask, info, forceUpdate);
                }
            });
        }

        @VisibleForTesting
        public boolean isEventFilterExplicit() {
            return mIsEventFilterExplicit;
        }

        void clearEvents() {
            mGenerationId.incrementAndGet();
        }

        void setEventsMask(@InternalEventFlag long newInternalEventFlagsMask) {
            internalEventFlagsMask = newInternalEventFlagsMask;
        }

        private void implicitlyRegisterForRRChanges() {
            // For backward compatibility, if the user didn't supply the explicit events while
            // subscribing, register them to refresh rate change events if they subscribed to
            // display changed events
            if ((internalEventFlagsMask & INTERNAL_EVENT_FLAG_DISPLAY_BASIC_CHANGED) != 0
                    && !mIsEventFilterExplicit) {
                setEventsMask(internalEventFlagsMask
                        | INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE);
            }
        }

        private void handleDisplayEventsInner(int displayId, int eventMask,
                @Nullable DisplayInfo info, boolean forceUpdate) {
            // For each display event do handleDisplayEventInner
            int remainingEvents = eventMask;
            while (remainingEvents != 0) {
                // Isolate the lowest single event bit (e.g., 1, 2, 4, 8...)
                int nextEvent = Integer.lowestOneBit(remainingEvents);
                handleDisplayEventInner(displayId, nextEvent, info, forceUpdate);
                // Remove the processed event from the mask
                remainingEvents &= ~nextEvent;
            }
        }

        private void handleDisplayEventInner(int displayId, @DisplayEvent int event,
                @Nullable DisplayInfo info, boolean forceUpdate) {
            if (extraLogging()) {
                Slog.i(TAG,
                        "DLD(" + eventToString(event) + ", display=" + displayId + ", mEventsMask="
                                + Long.toBinaryString(internalEventFlagsMask) + ", mPackageName="
                                + mPackageName + ", displayInfo=" + info + ", listener="
                                + mListener.getClass() + ")");
            }
            if (DEBUG) {
                Trace.beginSection(
                        TextUtils.trimToSize(
                                "DLD(" + eventToString(event)
                                + ", display=" + displayId
                                + ", listener=" + mListener.getClass() + ")", 127));
            }
            switch (event) {
                case EVENT_DISPLAY_ADDED:
                    if ((internalEventFlagsMask & INTERNAL_EVENT_FLAG_DISPLAY_ADDED) != 0) {
                        mListener.onDisplayAdded(displayId);
                    }
                    break;
                case EVENT_DISPLAY_BASIC_CHANGED:
                    if ((internalEventFlagsMask
                            & INTERNAL_EVENT_FLAG_DISPLAY_BASIC_CHANGED) != 0) {
                        if (info != null && (forceUpdate || !info.equals(mDisplayInfo))) {
                            if (extraLogging()) {
                                Slog.i(TAG, "Sending onDisplayChanged: Display Changed. Info: "
                                        + info);
                            }
                            mDisplayInfo.copyFrom(info);
                            mListener.onDisplayChanged(displayId);
                        }
                    }
                    break;
                case EVENT_DISPLAY_BRIGHTNESS_CHANGED:
                    if ((internalEventFlagsMask
                            & INTERNAL_EVENT_FLAG_DISPLAY_BRIGHTNESS_CHANGED) != 0) {
                        mListener.onDisplayChanged(displayId);
                    }
                    break;
                case EVENT_DISPLAY_REMOVED:
                    if ((internalEventFlagsMask & INTERNAL_EVENT_FLAG_DISPLAY_REMOVED)
                            != 0) {
                        mListener.onDisplayRemoved(displayId);
                    }
                    break;
                case EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED:
                    if ((internalEventFlagsMask
                            & INTERNAL_EVENT_FLAG_DISPLAY_HDR_SDR_RATIO_CHANGED) != 0) {
                        mListener.onDisplayChanged(displayId);
                    }
                    break;
                case EVENT_DISPLAY_CONNECTED:
                    if ((internalEventFlagsMask
                            & INTERNAL_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED) != 0) {
                        mListener.onDisplayConnected(displayId);
                    }
                    break;
                case EVENT_DISPLAY_DISCONNECTED:
                    if ((internalEventFlagsMask
                            & INTERNAL_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED) != 0) {
                        mListener.onDisplayDisconnected(displayId);
                    }
                    break;
                case EVENT_DISPLAY_REFRESH_RATE_CHANGED:
                    if ((internalEventFlagsMask
                            & INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE) != 0) {
                        mListener.onDisplayChanged(displayId);
                    }
                    break;
                case EVENT_DISPLAY_STATE_CHANGED:
                    if ((internalEventFlagsMask
                            & INTERNAL_EVENT_FLAG_DISPLAY_STATE) != 0) {
                        mListener.onDisplayChanged(displayId);
                    }
                    break;
                case EVENT_DISPLAY_COMMITTED_STATE_CHANGED:
                    if ((internalEventFlagsMask
                            & INTERNAL_EVENT_FLAG_DISPLAY_COMMITTED_STATE_CHANGED) != 0) {
                        mListener.onDisplayChanged(displayId);
                    }
                    break;
            }
            if (DEBUG) {
                Trace.endSection();
            }
        }

        @Override
        public String toString() {
            return "flag: {" + internalEventFlagsMask + "}, for " + mListener.getClass()
                    + " - mPackageName: " + mPackageName;
        }
    }

    /**
     * Assists in dispatching VirtualDisplay lifecycle event callbacks on a given Executor.
     */
    public static final class VirtualDisplayCallback extends IVirtualDisplayCallback.Stub {
        @Nullable private final VirtualDisplay.Callback mCallback;
        @Nullable private final Executor mExecutor;

        /**
         * Creates a virtual display callback.
         *
         * @param callback The callback to call for virtual display events, or {@code null} if the
         * caller does not wish to receive callback events.
         * @param executor The executor to call the {@code callback} on. Must not be {@code null} if
         * the callback is not {@code null}.
         */
        public VirtualDisplayCallback(VirtualDisplay.Callback callback, Executor executor) {
            mCallback = callback;
            mExecutor = mCallback != null ? Objects.requireNonNull(executor) : null;
        }

        // These methods are called from the binder thread, but the AIDL is oneway, so it should be
        // safe to call the callback on arbitrary executors directly without risking blocking
        // the system.

        @Override // Binder call
        public void onPaused() {
            if (mCallback != null) {
                mExecutor.execute(mCallback::onPaused);
            }
        }

        @Override // Binder call
        public void onResumed() {
            if (mCallback != null) {
                mExecutor.execute(mCallback::onResumed);
            }
        }

        @Override // Binder call
        public void onStopped() {
            if (mCallback != null) {
                mExecutor.execute(mCallback::onStopped);
            }
        }
    }

    private static final class DisplayTopologyListenerDelegate {
        private final Consumer<DisplayTopology> mListener;
        private final Executor mExecutor;
        private final String mPackageName;

        DisplayTopologyListenerDelegate(@NonNull Consumer<DisplayTopology> listener,
                @NonNull @CallbackExecutor Executor executor, String packageName) {
            mExecutor = executor;
            mListener = listener;
            mPackageName = packageName;
        }

        @Override
        public String toString() {
            return "DisplayTopologyListener {packageName=" + mPackageName + "}";
        }

        void onTopologyChanged(DisplayTopology topology) {
            if (extraLogging()) {
                Slog.i(TAG, "Sending topology update: " + topology);
            }
            mExecutor.execute(() -> mListener.accept(topology));
        }
    }

    /**
     * The API portion of the key that identifies the unique PropertyInvalidatedCache token which
     * changes every time we update the system's display configuration.
     */
    private static final String CACHE_KEY_DISPLAY_INFO_API = "display_info";

    /**
     * Invalidates the contents of the display info cache for all applications. Can only
     * be called by system_server.
     */
    public static void invalidateLocalDisplayInfoCaches() {
        PropertyInvalidatedCache.invalidateCache(MODULE_SYSTEM, CACHE_KEY_DISPLAY_INFO_API);
    }

    /**
     * Disables the binder call cache.
     */
    public void disableLocalDisplayInfoCaches() {
        mDisplayCache = null;
    }

    private static native void nSignalNativeCallbacks(float refreshRate);

    /**
     * Called from AChoreographer via JNI.
     * Registers AChoreographer so that refresh rate callbacks can be dispatched from DMS.
     * Public for unit testing to be able to call this method.
     */
    @VisibleForTesting
    public void registerNativeChoreographerForRefreshRateCallbacks() {
        synchronized (mLock) {
            mDispatchNativeCallbacks = true;
            if (Flags.delayImplicitRrRegistrationUntilRrAccessed()) {
                if (!mShouldImplicitlyRegisterRrChanges) {
                    Slog.i(TAG, "Choreographer implicitly registered for the refresh rate.");
                }
                mShouldImplicitlyRegisterRrChanges = true;
            }
            registerCallbackIfNeeded();
            updateCallbackIfNeededLocked();
            DisplayInfo display = getDisplayInfoInternal(Display.DEFAULT_DISPLAY);
            if (display != null) {
                // We need to tell AChoreographer instances the current refresh rate so that apps
                // can get it for free once a callback first registers.
                mNativeCallbackReportedRefreshRate = display.getRefreshRate();
                nSignalNativeCallbacks(mNativeCallbackReportedRefreshRate);
            }
        }
    }

    /**
     * Called from AChoreographer via JNI.
     * Unregisters AChoreographer from receiving refresh rate callbacks.
     * Public for unit testing to be able to call this method.
     */
    @VisibleForTesting
    public void unregisterNativeChoreographerForRefreshRateCallbacks() {
        synchronized (mLock) {
            mDispatchNativeCallbacks = false;
            updateCallbackIfNeededLocked();
        }
    }

    /** Converts an event mask to a string. */
    public static String eventsToString(int eventMask) {
        if (eventMask == 0) {
            return "NONE";
        }

        StringBuilder sb = new StringBuilder();
        int remainingEvents = eventMask;
        while (remainingEvents != 0) {
            int nextEvent = Integer.lowestOneBit(remainingEvents);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(eventToString(nextEvent));
            remainingEvents &= ~nextEvent;
        }
        return sb.toString();
    }

    private static String eventToString(@DisplayEvent int event) {
        switch (event) {
            case EVENT_DISPLAY_ADDED:
                return "ADDED";
            case EVENT_DISPLAY_BASIC_CHANGED:
                return "BASIC_CHANGED";
            case EVENT_DISPLAY_REMOVED:
                return "REMOVED";
            case EVENT_DISPLAY_BRIGHTNESS_CHANGED:
                return "BRIGHTNESS_CHANGED";
            case EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED:
                return "HDR_SDR_RATIO_CHANGED";
            case EVENT_DISPLAY_CONNECTED:
                return "EVENT_DISPLAY_CONNECTED";
            case EVENT_DISPLAY_DISCONNECTED:
                return "EVENT_DISPLAY_DISCONNECTED";
            case EVENT_DISPLAY_REFRESH_RATE_CHANGED:
                return "EVENT_DISPLAY_REFRESH_RATE_CHANGED";
            case EVENT_DISPLAY_STATE_CHANGED:
                return "EVENT_DISPLAY_STATE_CHANGED";
            case EVENT_DISPLAY_COMMITTED_STATE_CHANGED:
                return "EVENT_DISPLAY_COMMITTED_STATE_CHANGED";
        }
        return "UNKNOWN";
    }


    private static boolean initExtraLogging() {
        if (sCurrentPackageName == null) {
            sCurrentPackageName = ActivityThread.currentPackageName();
            sExtraDisplayListenerLogging = !TextUtils.isEmpty(EXTRA_LOGGING_PACKAGE_NAME)
                    && EXTRA_LOGGING_PACKAGE_NAME.equals(sCurrentPackageName);
        }
        return sExtraDisplayListenerLogging;
    }

    private static boolean extraLogging() {
        return sExtraDisplayListenerLogging;
    }


    /**
     * Maps the supplied public and private event flags to a unified InternalEventFlag
     * @param eventFlags A bitmask of the event types for which this listener is subscribed.
     * @param privateEventFlags A bitmask of the private event types for which this listener
     *                          is subscribed.
     * @return returns the bitmask of both public and private event flags unified to
     * InternalEventFlag
     */
    public @InternalEventFlag long mapFiltersToInternalEventFlag(@EventType long eventFlags,
            @DisplayManager.PrivateEventType long privateEventFlags) {
        return mapPrivateEventFlags(privateEventFlags) | mapPublicEventFlags(eventFlags);
    }

    private long mapPrivateEventFlags(@DisplayManager.PrivateEventType long privateEventFlags) {
        long baseEventMask = 0;
        if ((privateEventFlags & DisplayManager.PRIVATE_EVENT_TYPE_HDR_SDR_RATIO_CHANGED) != 0) {
            baseEventMask |= INTERNAL_EVENT_FLAG_DISPLAY_HDR_SDR_RATIO_CHANGED;
        }

        if ((privateEventFlags
                & DisplayManager.PRIVATE_EVENT_TYPE_DISPLAY_CONNECTION_CHANGED) != 0) {
            baseEventMask |= INTERNAL_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED;
        }

        if (Flags.committedStateSeparateEvent()) {
            if ((privateEventFlags
                    & DisplayManager.PRIVATE_EVENT_TYPE_DISPLAY_COMMITTED_STATE_CHANGED) != 0) {
                baseEventMask |= INTERNAL_EVENT_FLAG_DISPLAY_COMMITTED_STATE_CHANGED;
            }
        }
        return baseEventMask;
    }

    private long mapPublicEventFlags(@EventType long eventFlags) {
        long baseEventMask = 0;
        if ((eventFlags & DisplayManager.EVENT_TYPE_DISPLAY_ADDED) != 0) {
            baseEventMask |= INTERNAL_EVENT_FLAG_DISPLAY_ADDED;
        }

        if ((eventFlags & DisplayManager.EVENT_TYPE_DISPLAY_CHANGED) != 0) {
            baseEventMask |= INTERNAL_EVENT_FLAG_DISPLAY_BASIC_CHANGED;
        }

        if ((eventFlags & DisplayManager.EVENT_TYPE_DISPLAY_REMOVED) != 0) {
            baseEventMask |= INTERNAL_EVENT_FLAG_DISPLAY_REMOVED;
        }

        if ((eventFlags & DisplayManager.EVENT_TYPE_DISPLAY_REFRESH_RATE) != 0) {
            baseEventMask |= INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE;
        }

        if (Flags.displayListenerPerformanceImprovements()) {
            if ((eventFlags & DisplayManager.EVENT_TYPE_DISPLAY_STATE) != 0) {
                baseEventMask |= INTERNAL_EVENT_FLAG_DISPLAY_STATE;
            }
        }

        if ((eventFlags & DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS) != 0) {
            baseEventMask |= INTERNAL_EVENT_FLAG_DISPLAY_BRIGHTNESS_CHANGED;
        }

        return baseEventMask;
    }

    @VisibleForTesting
    public CopyOnWriteArrayList<DisplayListenerDelegate> getDisplayListeners() {
        return mDisplayListeners;
    }

    /**
     * Cache of display ids: connected and added. The cache gets updated with
     * {@link #EVENT_DISPLAY_CONNECTED}, {@link #EVENT_DISPLAY_ADDED},
     * {@link #EVENT_DISPLAY_REMOVED}, {@link #EVENT_DISPLAY_DISCONNECTED}.
     * The cache must be first initialized with {@link #updateCacheLocked}.
     * The cache is possible to init only if it is enabled with
     * {@link #setConnectedCachingEnabledLocked} or {@link #setAddedCachingEnabledLocked}.
     *
     * @hide
     */
    @VisibleForTesting
    public static class DisplayIdsCache {
        // Mark a display id as controlled by the current process, but not yet received via
        // onDisplayAdded or onDisplayRemoved events from the system server. This is used to
        // avoid throwing an exception when duplicate display events are
        // received for these displays, but also prevent races when display id gets added
        // and/or removed on this process much quicker than onDisplayAdded/Removed are
        // received from the system server
        private static final int FLAG_LOCALLY_CONTROLLED = 1;
        // Mark a display id as CONNECTED
        private static final int FLAG_CONNECTED = 1 << 1;
        // Mark a display id as ADDED, must be set together with CONNECTED, because
        // a display id is always ADDED after it is CONNECTED.
        private static final int FLAG_ADDED = 1 << 2;

        // Mask for locally added display ids, injected by the current process via injectLocked().
        // This mask is used to optimize the processing of duplicate display events, and also to
        // avoid races when display id gets added and/or removed by this process much quicker than
        // onDisplayAdded/Removed are received from the system server.
        private static final int LOCALLY_ADDED_MASK =
                FLAG_LOCALLY_CONTROLLED | FLAG_CONNECTED | FLAG_ADDED;

        // Mask for locally removed display ids, set by the current process via evictLocked().
        private static final int LOCALLY_REMOVED_MASK = FLAG_LOCALLY_CONTROLLED;

        private static final boolean mIsValidationEnabled = Flags.displayIdsCacheValidation();

        private boolean mIsConnectedCachingEnabled;
        private boolean mIsAddedCachingEnabled;
        private boolean mIsConnectedCacheValid;
        private boolean mIsAddedCacheValid;
        private final SparseIntArray mIdsCache = new SparseIntArray();

        @Nullable
        private int[] mConnectedIdsCacheArray = null;
        @Nullable
        private int[] mAddedIdsCacheArray = null;

        /**
         * This is used to speed up the discovery of the displays controlled by the current process,
         * so that current application could call getDisplays() and see these displays immediately.
         */
        @VisibleForTesting
        public void injectLocked(int displayId) {
            if (mIsAddedCachingEnabled || mIsConnectedCachingEnabled) {
                if (DEBUG) {
                    Log.d(TAG, "injectLocked"
                            + " package=" + ActivityThread.currentPackageName()
                            + " displayId=" + displayId);
                }
                // the cache is enabled, so add the display id to the cache.
                mIdsCache.put(displayId, LOCALLY_ADDED_MASK);
                invalidateArrayCaches();
            }
        }

        /**
         * This is used to speed up the removal of the displays controlled by the current process,
         * so that current application could call getDisplays() and no longer see these displays
         * immediately.
         */
        @VisibleForTesting
        public void evictLocked(int displayId) {
            if (mIsAddedCachingEnabled || mIsConnectedCachingEnabled) {
                if (DEBUG) {
                    Log.d(TAG, "evictLocked"
                            + " package=" + ActivityThread.currentPackageName()
                            + " displayId=" + displayId);
                }
                int index = mIdsCache.indexOfKey(displayId);
                if (index < 0) {
                    return;
                }
                // Cache is enabled, and id is present in the cache.
                // Mark it as locally controlled only, so that it won't be returned by getDisplays
                // but other methods of this class will still see it as already "removed"
                // and "disconnected".
                mIdsCache.setValueAt(index, LOCALLY_REMOVED_MASK);
                invalidateArrayCaches();
            }
        }

        /**
         * Initialize the cache given the snapshot of connected and/or added displayIds.
         */
        @VisibleForTesting
        public void updateCacheLocked(int[] connected, int[] added) {
            if (DEBUG) {
                Log.d(TAG, "updateCacheLocked"
                        + " package=" + ActivityThread.currentPackageName()
                        + " connectedCaching=" + mIsConnectedCachingEnabled
                        + " addedCaching=" + mIsAddedCachingEnabled
                        + " connectedCacheValid=" + mIsConnectedCacheValid
                        + " addedCacheValid=" + mIsAddedCacheValid
                        + " connected=" + Arrays.toString(connected)
                        + " added=" + Arrays.toString(added));
            }
            if (mIsAddedCachingEnabled && added.length > 0) {
                // Remove all ids which are not locally controlled and have only ADDED and
                // CONNECTED flags. Then insert the ground-truth added ids received with snapshot.
                // We only clear entries that exactly match FLAG_CONNECTED | FLAG_ADDED to avoid
                // removing displays that are marked with FLAG_LOCALLY_CONTROLLED, as those
                // are managed by the current process and should persist in the cache
                // until explicitly removed.
                clearIdsByFlags(FLAG_CONNECTED | FLAG_ADDED);
                for (int i = 0; i < added.length; i++) {
                    // this will potentially override the locally controlled flag, but that is ok,
                    // because we received this id in the snapshot which means that it is
                    // potentially not locally controlled anymore.
                    mIdsCache.put(added[i], FLAG_CONNECTED | FLAG_ADDED);
                }
                mIsAddedCacheValid = true;
            }
            if (mIsConnectedCachingEnabled && connected.length > 0) {
                // Remove all ids which are not locally controlled and have only CONNECTED flags.
                // Then insert the ground-truth connected ids received with snapshot.
                clearIdsByFlags(FLAG_CONNECTED);
                for (int i = 0; i < connected.length; i++) {
                    // If the display is not already present in the cache as ADDED, then add it with
                    // CONNECTED flag only.
                    if ((mIdsCache.get(connected[i], 0) & FLAG_ADDED) == 0) {
                        // this will potentially override the locally controlled flag, but that is
                        // ok, because we received this id in the snapshot which means that it is
                        // potentially not locally controlled anymore.
                        mIdsCache.put(connected[i], FLAG_CONNECTED);
                    }
                }
                mIsConnectedCacheValid = true;
            }
            invalidateArrayCaches();
        }

        /**
         * Sets whether caching is enabled.
         */
        @VisibleForTesting
        public void setConnectedCachingEnabledLocked(boolean isCachingEnabled) {
            mIsConnectedCachingEnabled = isCachingEnabled;
            // If caching is no longer enabled, then the cache is no longer valid either.
            if (DEBUG && mIsConnectedCacheValid && !isCachingEnabled) {
                Log.d(TAG, "setConnectedCachingEnabledLocked disabling cache"
                        + " package=" + ActivityThread.currentPackageName());
            }
            if (mIsConnectedCacheValid && !isCachingEnabled) {
                // We only clear/update the cache if it was previously valid and is now being
                // disabled. This prevents race conditions where the cache could be cleared
                // unintentionally if this method is called multiple times with
                // isCachingEnabled=false.
                clearIdsByFlags(FLAG_CONNECTED);
                mIsConnectedCacheValid = false;
            }
        }

        /**
         * Sets whether caching is enabled.
         */
        @VisibleForTesting
        public void setAddedCachingEnabledLocked(boolean isCachingEnabled) {
            mIsAddedCachingEnabled = isCachingEnabled;
            // If caching is no longer enabled, then the cache is no longer valid either.
            if (DEBUG && mIsAddedCacheValid && !isCachingEnabled) {
                Log.d(TAG, "setAddedCachingEnabledLocked disabling cache"
                        + " package=" + ActivityThread.currentPackageName());
            }
            if (mIsAddedCacheValid && !isCachingEnabled) {
                // We only clear/update the cache if it was previously valid and is now being
                // disabled. This prevents race conditions where the cache could be cleared
                // unintentionally if this method is called multiple times with
                // isCachingEnabled=false.

                // Drop ADDED flag from non-locally-controlled displays in the cache, because it is
                // stale if caching is disabled.
                clearFlagsForNotControlledLocally(FLAG_ADDED);
                if (!mIsConnectedCacheValid) {
                    // If connected cache is not valid, it means that we can clear all display ids
                    // which have only CONNECTED flag.
                    // This will keep only locally controlled displays in the cache.
                    clearIdsByFlags(FLAG_CONNECTED);
                }
                mIsAddedCacheValid = false;
            }
        }

        /**
         * Incrementally update the cache given displayId, and the event(s).
         *
         * System server sends REMOVED and DISCONNECTED events for all displays, even those to which
         * this uid never had access to before.
         * For example virtual displays which the current UID has no access to. System server
         * may not have information about UIDs which have access to this display id by the time of
         * sending these events, so will send these events anyway (b/458435043).
         * This method uses currently cached display ids to avoid sending
         * display events to the current process, in case it is not supposed to have access to
         * the displayId.
         *
         * @return eventMask, with potentially skipped REMOVED and DISCONNECTED events.
         */
        @VisibleForTesting
        public int updateCacheLocked(int displayId, int eventMask) {
            if (!mIsConnectedCacheValid && !mIsAddedCacheValid) {
                return eventMask;
            }
            int outEventMask = eventMask;
            // Given that the cache is valid:
            // This event can NOT be a duplicate of the snapshot which might have been
            // recently received by the listener. This is because EVENT and SNAPSHOT message
            // delivery is strongly ordered by the binder, but also that DisplayManagerService
            // uses global lock while processing CONNECT/DISCONNECT, ADD/REMOVE displays, and
            // SNAPSHOT uses the same global lock. So the client must receive the consistent state
            // with or without the respective displays. It would be a regression if it does not!
            // Local mDisplayIdsCache gets updated IN ORDER due to binder execution guarantees,
            // which ensures that there is NO WAY of having a duplicated event, such as ADDED
            // while it is already present in the cache (received in the snapshot a moment ago).
            if (mIsAddedCacheValid && (eventMask & EVENT_DISPLAY_ADDED) != 0) {
                int index = getIndexAndValidateNotAdded(displayId);
                if (index < 0) {
                    mIdsCache.put(displayId, FLAG_CONNECTED | FLAG_ADDED);
                    invalidateArrayCaches();
                } else {
                    int value = mIdsCache.valueAt(index);
                    if (value == LOCALLY_ADDED_MASK) {
                        // The display was locally injected, so it's already marked as ADDED and
                        // CONNECTED. We just need to clear the local control flag and forward the
                        // event, since this is the first time the system is acknowledging it.
                        endLocalControl(index);
                    } else if (value == LOCALLY_REMOVED_MASK || (value & FLAG_ADDED) != 0) {
                        /// The value is found, but it is not locally injected
                        // (value != LOCALLY_ADDED_MASK), then it is either already removed display,
                        // or it is a duplicated event. In either case we should not send these
                        // events to the client.
                        outEventMask &= ~(EVENT_DISPLAY_CONNECTED | EVENT_DISPLAY_ADDED);
                        endLocalControl(index);
                    } else {
                        mIdsCache.setValueAt(index, FLAG_CONNECTED | FLAG_ADDED);
                        invalidateArrayCaches();
                    }
                }
            } else if (mIsConnectedCacheValid && (eventMask & EVENT_DISPLAY_CONNECTED) != 0) {
                int index = getIndexAndValidateNotConnected(displayId);
                if (index < 0) {
                    // If the display is not present in the cache, then add it with CONNECTED flag
                    // only.
                    mIdsCache.put(displayId, FLAG_CONNECTED);
                    invalidateArrayCaches();
                } else {
                    int value = mIdsCache.valueAt(index);
                    if (value == LOCALLY_ADDED_MASK) {
                        // The display is locally injected.
                        // The event must be sent to the listener, because it was not sent before.
                        // Don't end local control yet, because display is "ADDED" so we still need
                        // to wait for the ADDED event to be sent from the system server.
                    } else if (value == LOCALLY_REMOVED_MASK || (value & FLAG_CONNECTED) != 0) {
                        // The value is found, but it is not locally injected
                        // (value != LOCALLY_ADDED_MASK), then it is either already removed display,
                        // or it is a duplicated event. In either case we should not send these
                        // events to the client.
                        outEventMask &= ~EVENT_DISPLAY_CONNECTED;
                        endLocalControl(index);
                    } else {
                        mIdsCache.setValueAt(index, FLAG_CONNECTED);
                        invalidateArrayCaches();
                    }
                }
            }

            if ((mIsConnectedCacheValid && (eventMask & EVENT_DISPLAY_DISCONNECTED) != 0)) {
                int index = mIdsCache.indexOfKey(displayId);
                if (index < 0) {
                    // Unknown display id, don't send these events to the listener.
                    outEventMask &= ~(EVENT_DISPLAY_DISCONNECTED | EVENT_DISPLAY_REMOVED);
                } else {
                    mIdsCache.removeAt(index);
                    invalidateArrayCaches();
                }
            } else if ((mIsAddedCacheValid && (eventMask & EVENT_DISPLAY_REMOVED) != 0)) {
                int index = mIdsCache.indexOfKey(displayId);
                if (index < 0) {
                    // Unknown display id, don't send these events to the listener.
                    outEventMask &= ~EVENT_DISPLAY_REMOVED;
                } else if (mIsConnectedCacheValid) {
                    // A display can be REMOVED (e.g. disabled) but still CONNECTED.
                    // If connected displays are being cached, we only drop the ADDED flag
                    // here, so it remains in the connected list.
                    mIdsCache.setValueAt(index, mIdsCache.valueAt(index) & (~FLAG_ADDED));
                    endLocalControl(index);
                    invalidateArrayCaches();
                } else {
                    // if connected cache is not valid, it means that the display can be safely
                    // removed from the cache, because it has never received "connected" and will
                    // never receive "disconnected" event.
                    mIdsCache.removeAt(index);
                    invalidateArrayCaches();
                }
            }

            return outEventMask;
        }

        boolean isCachingEnabledLocked(boolean includeDisabled) {
            return includeDisabled ? mIsConnectedCachingEnabled : mIsAddedCachingEnabled;
        }

        boolean isCacheValidLocked(boolean includeDisabled) {
            return includeDisabled ? mIsConnectedCacheValid : mIsAddedCacheValid;
        }

        /**
         * Returns current cache of connected display ids or null if cache is invalid.
         */
        @VisibleForTesting
        @Nullable
        public int[] getConnectedLocked() {
            if (!mIsConnectedCacheValid) {
                return null;
            }
            if (mConnectedIdsCacheArray == null) {
                mConnectedIdsCacheArray = filterIdsMatchingFlag(FLAG_CONNECTED);
            }
            return mConnectedIdsCacheArray;
        }

        /**
         * Returns current cache of added displays or null if cache is invalid.
         */
        @VisibleForTesting
        @Nullable
        public int[] getAddedLocked() {
            if (!mIsAddedCacheValid) {
                return null;
            }
            if (mAddedIdsCacheArray == null) {
                mAddedIdsCacheArray = filterIdsMatchingFlag(FLAG_ADDED);
            }
            return mAddedIdsCacheArray;
        }

        /**
         * Helper to invalidate the cached arrays on any write operation.
         */
        private void invalidateArrayCaches() {
            mConnectedIdsCacheArray = null;
            mAddedIdsCacheArray = null;
        }

        private int getIndexAndValidateNotConnected(int displayId) {
            int index = mIdsCache.indexOfKey(displayId);
            if (!mIsValidationEnabled || index < 0) {
                return index;
            }
            int value = mIdsCache.valueAt(index);
            if ((value & FLAG_LOCALLY_CONTROLLED) == 0 // Not controlled
                    && (value & FLAG_CONNECTED) != 0) { // Already connected
                throw new IllegalStateException("DisplayId " + displayId
                        + " is already present in the connected ids!");
            }
            return index;
        }

        private int getIndexAndValidateNotAdded(int displayId) {
            int index = mIdsCache.indexOfKey(displayId);
            if (!mIsValidationEnabled || index < 0) {
                return index;
            }
            int value = mIdsCache.valueAt(index);
            if ((value & FLAG_LOCALLY_CONTROLLED) == 0 // Not controlled
                    && (value & FLAG_ADDED) != 0) { // Already added
                throw new IllegalStateException("DisplayId " + displayId
                        + " is already present in the added ids!");
            }
            return index;
        }

        private void endLocalControl(int index) {
            if (index < 0) {
                return;
            }
            int value = mIdsCache.valueAt(index);
            if (value == FLAG_LOCALLY_CONTROLLED) {
                mIdsCache.removeAt(index);
            } else if (value != 0) {
                mIdsCache.setValueAt(index, value & (~FLAG_LOCALLY_CONTROLLED));
            }
        }

        /**
         * Removes the given flags from all cache entries that are not marked as locally controlled.
         * This is used when a cache type is disabled to clear stale state without affecting
         * displays managed by the current process.
         * @param flags The flags to remove.
         */
        private void clearFlagsForNotControlledLocally(int flags) {
            for (int i = mIdsCache.size() - 1; i >= 0; i--) {
                int value = mIdsCache.valueAt(i);
                if ((value & FLAG_LOCALLY_CONTROLLED) == 0) {
                    mIdsCache.setValueAt(i, value & (~flags));
                }
            }
            invalidateArrayCaches();
        }

        private void clearIdsByFlags(int flags) {
            for (int i = mIdsCache.size() - 1; i >= 0; i--) {
                // Only consider entries that contain precisely the flags being cleared
                if (mIdsCache.valueAt(i) == flags) {
                    mIdsCache.removeAt(i);
                }
            }
            invalidateArrayCaches();
        }

        private int[] filterIdsMatchingFlag(int flag) {
            int idsToReturn = 0;
            for (int i = 0; i < mIdsCache.size(); i++) {
                int value = mIdsCache.valueAt(i);
                if ((value & flag) != 0) {
                    idsToReturn++;
                }
            }
            int[] res = new int[idsToReturn];
            int j = 0;
            for (int i = 0; i < mIdsCache.size(); i++) {
                int value = mIdsCache.valueAt(i);
                if ((value & flag) != 0) {
                    res[j++] = mIdsCache.keyAt(i);
                }
            }
            return res;
        }
    }
}
