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

package com.android.server.inputmethod;

import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.BOUND_TO_METHOD;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.CONCURRENT_MULTI_USER_MODE_ENABLED;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.CUR_ATTRIBUTE;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.CUR_CLIENT;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.CUR_FOCUSED_WINDOW_SOFT_INPUT_MODE;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.IN_FULLSCREEN_MODE;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.IS_INTERACTIVE;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.LAST_IME_TARGET_WINDOW_NAME;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.PREVENT_IME_STARTUP_UNLESS_TEXT_EDITOR;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.SYSTEM_READY;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;
import static android.os.IServiceManager.DUMP_FLAG_PROTO;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_HIDE;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;
import static android.view.inputmethod.ConnectionlessHandwritingCallback.CONNECTIONLESS_HANDWRITING_ERROR_OTHER;
import static android.view.inputmethod.ConnectionlessHandwritingCallback.CONNECTIONLESS_HANDWRITING_ERROR_UNSUPPORTED;
import static android.view.inputmethod.ImeTracker.DEBUG_IME_VISIBILITY;

import static com.android.server.EventLogTags.IMF_HIDE_IME;
import static com.android.server.EventLogTags.IMF_SHOW_IME;
import static com.android.server.inputmethod.ImeProtoLogGroup.IMMS_DEBUG;
import static com.android.server.inputmethod.ImeProtoLogGroup.IMMS_WITH_LOGCAT;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.ImeTargetWindowState;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.ImeVisibilityResult;
import static com.android.server.inputmethod.InputMethodBindingController.IME_BACKGROUND_BIND_FLAGS;
import static com.android.server.inputmethod.InputMethodBindingController.IME_CONNECTION_BIND_FLAGS;
import static com.android.server.inputmethod.InputMethodSettings.INVALID_SUBTYPE_HASHCODE;
import static com.android.server.inputmethod.InputMethodSubtypeSwitchingController.MODE_AUTO;
import static com.android.server.inputmethod.InputMethodUtils.NOT_A_SUBTYPE_INDEX;
import static com.android.server.inputmethod.InputMethodUtils.isSoftInputModeStateVisibleAllowed;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.Manifest;
import android.annotation.AnyThread;
import android.annotation.BinderThread;
import android.annotation.DrawableRes;
import android.annotation.DurationMillisLong;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.SpecialUsers.CanBeALL;
import android.annotation.SpecialUsers.CanBeCURRENT;
import android.annotation.UiThread;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.ActivityManagerInternal;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Region;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManager;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.InputMethodService.BackDispositionMode;
import android.inputmethodservice.InputMethodService.ImeWindowVisibility;
import android.internal.perfetto.protos.Inputmethodeditor.InputMethodManagerServiceTraceFileProto;
import android.internal.perfetto.protos.Inputmethodeditor.InputMethodManagerServiceTraceProto;
import android.media.AudioManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IntArray;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManager.DisplayImePolicy;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.Flags;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodManager.IMPickerEntryPoint;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.internal.inputmethod.IAccessibilityInputMethodSession;
import com.android.internal.inputmethod.IBooleanListener;
import com.android.internal.inputmethod.IConnectionlessHandwritingCallback;
import com.android.internal.inputmethod.IImeSwitcherMenu;
import com.android.internal.inputmethod.IImeSwitcherMenuListener;
import com.android.internal.inputmethod.IImeTracker;
import com.android.internal.inputmethod.IInputContentUriToken;
import com.android.internal.inputmethod.IInputMethod;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IInputMethodPrivilegedOperations;
import com.android.internal.inputmethod.IInputMethodSession;
import com.android.internal.inputmethod.IInputMethodSessionCallback;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteComputerControlInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.ImeSwitcherMenuItemSafeList;
import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.InlineSuggestionsRequestCallback;
import com.android.internal.inputmethod.InlineSuggestionsRequestInfo;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.InputMethodInfoSafeList;
import com.android.internal.inputmethod.InputMethodNavButtonFlags;
import com.android.internal.inputmethod.InputMethodSubtypeSafeList;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.inputmethod.UnbindReason;
import com.android.internal.os.TransferPipe;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.AccessibilityManagerInternal;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.input.InputManagerInternal;
import com.android.server.inputmethod.InputMethodManagerInternal.InputMethodListListener;
import com.android.server.inputmethod.InputMethodSubtypeSwitchingController.ImeSubtypeListItem;
import com.android.server.pm.UserManagerInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.utils.PriorityDump;
import com.android.server.wm.WindowManagerInternal;

import lineageos.hardware.LineageHardwareManager;
import lineageos.providers.LineageSettings;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * This class provides a system service that manages input methods.
 */
public final class InputMethodManagerService implements IInputMethodManagerImpl.Callback,
        Handler.Callback {

    // Virtual device id for test.
    private static final Integer VIRTUAL_STYLUS_ID_FOR_TEST = 999999;
    static final boolean DEBUG = false;
    static final String TAG = "InputMethodManagerService";

    /**
     * Timeout in milliseconds in {@link #systemRunning()} to make sure that users are initialized
     * in {@link Lifecycle#initializeUsersAsync(int[])}.
     */
    @DurationMillisLong
    private static final long SYSTEM_READY_USER_INIT_TIMEOUT = 3000;
    @Nullable
    private ArrayList<InputMethodInfo> mAllowedImesByPolicyForTest;

    /**
     * Indicates that the annotated field is shared by all the users.
     *
     * <p>See b/305849394 for details.</p>
     */
    @Retention(SOURCE)
    @Target({ElementType.FIELD})
    private @interface SharedByAllUsersField {
    }

    /**
     * Indicates that the annotated field is not yet ready for concurrent multi-user support.
     *
     * <p>See b/305849394 for details.</p>
     */
    @Retention(SOURCE)
    @Target({ElementType.FIELD})
    private @interface MultiUserUnawareField {
    }

    private static final int MSG_HIDE_INPUT_METHOD = 1035;
    private static final int MSG_REMOVE_IME_SURFACE = 1060;
    private static final int MSG_REMOVE_IME_SURFACE_FROM_WINDOW = 1061;

    private static final int MSG_RESET_HANDWRITING = 1090;
    private static final int MSG_START_HANDWRITING = 1100;
    private static final int MSG_FINISH_HANDWRITING = 1110;
    private static final int MSG_REMOVE_HANDWRITING_WINDOW = 1120;

    private static final int MSG_PREPARE_HANDWRITING_DELEGATION = 1130;

    private static final int MSG_SET_INTERACTIVE = 3030;

    private static final int MSG_DISPATCH_ON_INPUT_METHOD_LIST_UPDATED = 5010;

    private static final int MSG_NOTIFY_IME_UID_TO_AUDIO_SERVICE = 7000;

    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";
    private static final String HANDLER_THREAD_NAME = "android.imms";
    private static final String PACKAGE_MONITOR_THREAD_NAME = "android.imms2";

    /**
     * When set, {@link #startInputUncheckedLocked} will return
     * {@link InputBindResult#NO_EDITOR} instead of starting an IME connection
     * unless {@link StartInputFlags#IS_TEXT_EDITOR} is set. This behavior overrides
     * {@link LayoutParams#SOFT_INPUT_STATE_VISIBLE SOFT_INPUT_STATE_VISIBLE} and
     * {@link LayoutParams#SOFT_INPUT_STATE_ALWAYS_VISIBLE SOFT_INPUT_STATE_ALWAYS_VISIBLE}
     * starting from {@link android.os.Build.VERSION_CODES#P}.
     */
    @SharedByAllUsersField
    private final boolean mPreventImeStartupUnlessTextEditor;

    /**
     * These IMEs are known not to behave well when evicted from memory and thus are exempt
     * from the IME startup avoidance behavior that is enabled by
     * {@link #mPreventImeStartupUnlessTextEditor}.
     */
    @SharedByAllUsersField
    @Nullable
    private final String[] mNonPreemptibleInputMethods;

     /**
     * These apps are exempt from the IME startup prevention behaviour that is enabled by
     * {@link #mPreventImeStartupUnlessTextEditor}.
     */
    @SharedByAllUsersField
    @Nullable
    private String[] mPreventImeStartupBypassedApps;

    /**
     * See {@link #shouldEnableConcurrentMultiUserMode(Context)} about when set to be {@code true}.
     */
    @SharedByAllUsersField
    private final boolean mConcurrentMultiUserModeEnabled;

    /**
     * Returns {@code true} if the concurrent multi-user mode is enabled.
     *
     * <p>Currently not compatible with profiles (e.g. work profile).</p>
     *
     * @param context {@link Context} to be used to query
     *                {@link PackageManager#FEATURE_AUTOMOTIVE}
     * @return {@code true} if the concurrent multi-user mode is enabled.
     */
    static boolean shouldEnableConcurrentMultiUserMode(@NonNull Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                && UserManager.isVisibleBackgroundUsersEnabled()
                && context.getResources().getBoolean(android.R.bool.config_perDisplayFocusEnabled);
    }

    /**
     * Figures out the target IME user ID for a given {@link Binder} IPC.
     *
     * @param callingProcessUserId the user ID of the calling process
     * @return the user ID to be used for this {@link Binder} call
     */
    @GuardedBy("ImfLock.class")
    @UserIdInt
    @BinderThread
    private int resolveImeUserIdLocked(@UserIdInt int callingProcessUserId) {
        return mConcurrentMultiUserModeEnabled ? callingProcessUserId : mCurrentImeUserId;
    }

    /**
     * Figures out the target IME user ID associated with the given {@code displayId}.
     *
     * @param displayId the display ID to be queried about
     * @return User ID to be used for this {@code displayId}.
     */
    @GuardedBy("ImfLock.class")
    @UserIdInt
    private int resolveImeUserIdFromDisplayIdLocked(int displayId) {
        return mConcurrentMultiUserModeEnabled
                ? mUserManagerInternal.getUserAssignedToDisplay(displayId) : mCurrentImeUserId;
    }

    /**
     * Figures out the target IME user ID associated with the given {@code windowToken}.
     *
     * @param windowToken the Window token to be queried about
     * @return User ID to be used for this {@code displayId}.
     */
    @GuardedBy("ImfLock.class")
    @UserIdInt
    private int resolveImeUserIdFromWindowLocked(@NonNull IBinder windowToken) {
        if (mConcurrentMultiUserModeEnabled) {
            final int displayId = mWindowManagerInternal.getDisplayIdForWindow(windowToken);
            return mUserManagerInternal.getUserAssignedToDisplay(displayId);
        }
        return mCurrentImeUserId;
    }

    final Context mContext;
    final Resources mRes;
    private final Handler mHandler;

    private final InputMethodManagerInternal mInputMethodManagerInternal;
    @NonNull
    private final Handler mIoHandler;

    /**
     * The user ID whose IME should be used if {@link #mConcurrentMultiUserModeEnabled} is
     * {@code false}, otherwise remains to be the initial value, which is obtained by
     * {@link ActivityManagerInternal#getCurrentUserId()} while the device is booting up.
     *
     * <p>Never get confused with {@link ActivityManagerInternal#getCurrentUserId()}, which is
     * in general useless when designing and implementing interactions between apps and IMEs.</p>
     *
     * <p>You can also not assume that the IME client process belongs to {@link #mCurrentImeUserId}.
     * A most important outlier is System UI process, which always runs under
     * {@link UserHandle#USER_SYSTEM} in all the known configurations including Headless System User
     * Mode (HSUM).</p>
     */
    @MultiUserUnawareField
    @UserIdInt
    @GuardedBy("ImfLock.class")
    int mCurrentImeUserId;

    /** Holds all user related data */
    @SharedByAllUsersField
    private final UserDataRepository mUserDataRepository;

    @MultiUserUnawareField
    final SettingsObserver mSettingsObserver;
    final WindowManagerInternal mWindowManagerInternal;
    private final ActivityManagerInternal mActivityManagerInternal;
    final PackageManagerInternal mPackageManagerInternal;
    final InputManagerInternal mInputManagerInternal;
    final ImePlatformCompatUtils mImePlatformCompatUtils;
    @SharedByAllUsersField
    final InputMethodDeviceConfigs mInputMethodDeviceConfigs;

    final UserManagerInternal mUserManagerInternal;

    @SharedByAllUsersField
    @NonNull
    private final ImeSwitcherMenu mImeSwitcherMenu;

    /**
     * The interface to send calls to the IME Switcher Menu controller. This is set only after the
     * IME Switcher Menu is fully initialized in SystemUI.
     */
    @Nullable
    @GuardedBy("ImfLock.class")
    private IImeSwitcherMenu mIImeSwitcherMenu;

    /** The interface to receive callbacks from the IME Switcher Menu controller. */
    @NonNull
    @SharedByAllUsersField
    private final IImeSwitcherMenuListener mImeSwitcherMenuListener =
            new IImeSwitcherMenuListener.Stub() {

                @RequiresNoPermission
                @Override
                public void onVisibilityChanged(boolean visible, int displayId,
                        @UserIdInt int userId) {
                    if (!Flags.imeSwitcherMenuSystemui()) {
                        return;
                    }
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        synchronized (ImfLock.class) {
                            final var userData = getUserData(userId);
                            userData.mImeSwitcherMenuVisible = visible;
                            updateSystemUiLocked(userId);
                            sendOnNavButtonFlagsChangedLocked(userData);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }

                @RequiresNoPermission
                @Override
                public void onImeAndSubtypeSelected(@NonNull String imeId,
                        @IntRange(from = NOT_A_SUBTYPE_INDEX) int subtypeIndex,
                        @UserIdInt int userId) {
                    if (!Flags.imeSwitcherMenuSystemui()) {
                        return;
                    }
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        synchronized (ImfLock.class) {
                            switchToInputMethodLocked(imeId, subtypeIndex, userId);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            };

    /** The recipient of death for the {@link #mImeSwitcherMenu}. */
    @NonNull
    @SharedByAllUsersField
    private final IBinder.DeathRecipient mImeSwitcherMenuDeathRecipient;

    interface ImeSwitcherMenu {

        void show(@NonNull List<ImeSubtypeListItem> items, @Nullable String selectedImeId,
                int selectedSubtypeIndex, boolean isScreenLocked,
                @IMPickerEntryPoint int entryPoint, int displayId, @UserIdInt int userId);

        void hide(int displayId, @UserIdInt int userId);

        boolean isShowing(@Nullable UserData userData);

        void onImeAndSubtypeChanged(@Nullable String imeId, int subtypeIndex,
                @Nullable Intent settingsIntent, @UserIdInt int userId);

        void dump(@NonNull Printer pw, @NonNull String prefix);
    }

    private final class ImeSwitcherMenuWrapper implements ImeSwitcherMenu {

        @Override
        public void show(@NonNull List<ImeSubtypeListItem> items, @Nullable String selectedImeId,
                @IntRange(from = NOT_A_SUBTYPE_INDEX) int selectedSubtypeIndex,
                boolean isScreenLocked, @IMPickerEntryPoint int entryPoint, int displayId,
                @UserIdInt int userId) {
            if (mIImeSwitcherMenu != null) {
                final var menuItems = new ArrayList<IImeSwitcherMenu.Item>();
                for (int i = 0; i < items.size(); i++) {
                    final var item = items.get(i);
                    final var menuItem = new IImeSwitcherMenu.Item();
                    menuItem.imeName = item.mImeName;
                    menuItem.subtypeName = item.mSubtypeName;
                    menuItem.subtypeShortLabel = item.mSubtypeShortLabel;
                    menuItem.subtypeIconResId = item.mSubtypeIconResId;
                    menuItem.layoutName = item.mLayoutName;
                    menuItem.imeId = item.mImi.getId();
                    menuItem.imePackageName = item.mImi.getPackageName();
                    menuItem.subtypeIndex = item.mSubtypeIndex;
                    menuItems.add(menuItem);
                }

                final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
                final var selectedImi = settings.getMethodMap().get(selectedImeId);
                final var selectedImeSettingsIntent = selectedImi != null
                        ? selectedImi.createImeLanguageSettingsActivityIntent() : null;
                try {
                    mIImeSwitcherMenu.show(ImeSwitcherMenuItemSafeList.create(menuItems),
                            selectedImeId, selectedSubtypeIndex, selectedImeSettingsIntent,
                            isScreenLocked, entryPoint, displayId, userId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed show IME Switcher Menu for user: " + userId
                            + " on display: " + displayId, e);
                }
            }
        }

        @Override
        public void hide(int displayId, @UserIdInt int userId) {
            if (mIImeSwitcherMenu != null) {
                try {
                    mIImeSwitcherMenu.hide(userId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to hide IME Switcher Menu for user: " + userId, e);
                }
            }
        }

        @Override
        public boolean isShowing(@Nullable UserData userData) {
            return userData != null && userData.mImeSwitcherMenuVisible;
        }

        @Override
        public void onImeAndSubtypeChanged(@Nullable String imeId, int subtypeIndex,
                @Nullable Intent settingsIntent, @UserIdInt int userId) {
            if (mIImeSwitcherMenu != null) {
                try {
                    mIImeSwitcherMenu.notifyImeAndSubtypeChanged(imeId, subtypeIndex,
                            settingsIntent, userId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify IME Switcher Menu of new selected IME: " + imeId
                            + " and subtype index: " + subtypeIndex + " for user: " + userId, e);
                }
            }
        }

        public void dump(@NonNull Printer pw, @NonNull String prefix) {
            // This is dumped in the ImeSwitcherMenuController.
        }
    }

    /**
     * Cache the result of {@code LocalServices.getService(AudioManagerInternal.class)}.
     *
     * <p>This field is used only within {@link #handleMessage(Message)} hence synchronization is
     * not necessary.</p>
     */
    @Nullable
    private AudioManagerInternal mAudioManagerInternal = null;
    @Nullable
    private VirtualDeviceManagerInternal mVdmInternal = null;
    @Nullable
    private DisplayManagerInternal mDisplayManagerInternal = null;

    // Mapping from deviceId to the device-specific imeId for that device.
    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    private final SparseArray<String> mVirtualDeviceMethodMap = new SparseArray<>();

    @Nullable
    private StatusBarManagerInternal mStatusBarManagerInternal;
    @GuardedBy("ImfLock.class")
    @MultiUserUnawareField
    private final HandwritingModeController mHwController;
    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    private IntArray mStylusIds;

    private LineageHardwareManager mLineageHardware;

    class SettingsObserver extends ContentObserver {
        /**
         * <em>This constructor must be called within the lock.</em>
         */
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void registerContentObserverForAllUsers() {
            ContentResolver resolver = mContext.getContentResolver();
            if (mLineageHardware.isSupported(
                    LineageHardwareManager.FEATURE_HIGH_TOUCH_POLLING_RATE)) {
                resolver.registerContentObserverAsUser(LineageSettings.System.getUriFor(
                        LineageSettings.System.HIGH_TOUCH_POLLING_RATE_ENABLE),
                        false, this, UserHandle.ALL);
            }
            if (mLineageHardware.isSupported(
                    LineageHardwareManager.FEATURE_HIGH_TOUCH_SENSITIVITY)) {
                resolver.registerContentObserverAsUser(LineageSettings.System.getUriFor(
                        LineageSettings.System.HIGH_TOUCH_SENSITIVITY_ENABLE),
                        false, this, UserHandle.ALL);
            }
            if (mLineageHardware.isSupported(LineageHardwareManager.FEATURE_TOUCH_HOVERING)) {
                resolver.registerContentObserverAsUser(LineageSettings.Secure.getUriFor(
                        LineageSettings.Secure.FEATURE_TOUCH_HOVERING),
                        false, this, UserHandle.ALL);
            }
            resolver.registerContentObserverAsUser(LineageSettings.System.getUriFor(
                    LineageSettings.System.NAVIGATION_BAR_HINT),
                    false, this, UserHandle.ALL);
            resolver.registerContentObserverAsUser(LineageSettings.System.getUriFor(
                    LineageSettings.System.NAVIGATION_BAR_IME_SPACE),
                    false, this, UserHandle.ALL);
            resolver.registerContentObserverAsUser(Settings.Secure.getUriFor(
                    Settings.Secure.NAVIGATION_MODE),
                    false, this, UserHandle.ALL);
        }

        @Override
        public void onChange(boolean selfChange, @NonNull Collection<Uri> uris, int flags,
                @UserIdInt int userId) {
            uris.forEach(uri -> onChangeInternal(uri, userId));
        }

        private void onChangeInternal(@NonNull Uri uri, @UserIdInt int userId) {
            final Uri highTouchPollingRateUri = LineageSettings.System.getUriFor(
                    LineageSettings.System.HIGH_TOUCH_POLLING_RATE_ENABLE);
            final Uri touchSensitivityUri = LineageSettings.System.getUriFor(
                    LineageSettings.System.HIGH_TOUCH_SENSITIVITY_ENABLE);
            final Uri touchHoveringUri = LineageSettings.Secure.getUriFor(
                    LineageSettings.Secure.FEATURE_TOUCH_HOVERING);
            final Uri navBarHintUri = LineageSettings.System.getUriFor(
                    LineageSettings.System.NAVIGATION_BAR_HINT);
            final Uri navBarImeSpaceUri = LineageSettings.System.getUriFor(
                    LineageSettings.System.NAVIGATION_BAR_IME_SPACE);
            final Uri navigationModeUri = Settings.Secure.getUriFor(
                    Settings.Secure.NAVIGATION_MODE);
            synchronized (ImfLock.class) {
                if (!mConcurrentMultiUserModeEnabled && mCurrentImeUserId != userId) {
                    return;
                }
                if (highTouchPollingRateUri.equals(uri)) {
                    updateTouchPollingRate();
                } else if (touchSensitivityUri.equals(uri)) {
                    updateTouchSensitivity();
                } else if (touchHoveringUri.equals(uri)) {
                    updateTouchHovering();
                } else if (navBarHintUri.equals(uri) || navBarImeSpaceUri.equals(uri)
                        || navigationModeUri.equals(uri)) {
                    sendOnNavButtonFlagsChangedLocked(getUserData(userId));
                }
            }
        }
    }

    private final ImeTracing.ServiceDumper mDumper = new ImeTracing.ServiceDumper() {
        /**
         * {@inheritDoc}
         */
        @Override
        public void dumpToProto(ProtoOutputStream proto, @Nullable byte[] icProto) {
            dumpDebug(proto, InputMethodManagerServiceTraceProto.INPUT_METHOD_MANAGER_SERVICE);
        }
    };

    static class SessionState {

        @NonNull
        final ClientState mClient;
        @NonNull
        final IInputMethodInvoker mIme;

        @Nullable
        IInputMethodSession mSession;
        @Nullable
        InputChannel mChannel;

        @UserIdInt
        final int mUserId;

        @Override
        public String toString() {
            return "SessionState{uid=" + mClient.mUid + " pid=" + mClient.mPid
                    + " ime="
                    + Integer.toHexString(IInputMethodInvoker.getBinderIdentityHashCode(mIme))
                    + " session=" + Integer.toHexString(System.identityHashCode(mSession))
                    + " channel=" + mChannel
                    + " userId=" + mUserId
                    + "}";
        }

        SessionState(@NonNull ClientState client, @NonNull IInputMethodInvoker ime,
                @Nullable IInputMethodSession session, @NonNull InputChannel channel,
                @UserIdInt int userId) {
            mClient = client;
            mIme = ime;
            mSession = session;
            mChannel = channel;
            mUserId = userId;
        }
    }

    /**
     * Record session state for an accessibility service.
     */
    static class AccessibilitySessionState {

        @NonNull
        final ClientState mClient;
        // Id of the accessibility service.
        final int mId;

        @Nullable
        IAccessibilityInputMethodSession mSession;

        @Override
        public String toString() {
            return "AccessibilitySessionState{uid=" + mClient.mUid + " pid=" + mClient.mPid
                    + " id=" + Integer.toHexString(mId)
                    + " session=" + Integer.toHexString(System.identityHashCode(mSession))
                    + "}";
        }

        AccessibilitySessionState(@NonNull ClientState client, int id,
                @NonNull IAccessibilityInputMethodSession session) {
            mClient = client;
            mId = id;
            mSession = session;
        }
    }

    /**
     * Manages the IME clients.
     */
    @SharedByAllUsersField
    @NonNull
    private final ClientController mClientController;

    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    @Nullable
    private ImeShellCommandController mShellCommandController;

    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    @Nullable
    private EnabledInputMethodsController mEnabledInputMethodsController;

    /**
     * Set once the system is ready to run third party code.
     */
    @SharedByAllUsersField
    boolean mSystemReady;

    @AnyThread
    @NonNull
    UserData getUserData(@UserIdInt int userId) {
        return mUserDataRepository.getOrCreate(userId);
    }

    @AnyThread
    @NonNull
    InputMethodBindingController getInputMethodBindingController(@UserIdInt int userId) {
        return getUserData(userId).mBindingController;
    }

    @GuardedBy("ImfLock.class")
    @NonNull
    private ImeShellCommandController getImeShellCommandControllerLocked() {
        if (mShellCommandController == null) {
            mShellCommandController = new ImeShellCommandController(this);
        }
        return mShellCommandController;
    }

    @GuardedBy("ImfLock.class")
    @NonNull
    EnabledInputMethodsController getEnabledInputMethodsControllerLocked() {
        if (mEnabledInputMethodsController == null) {
            mEnabledInputMethodsController = new EnabledInputMethodsController(this);
        }
        return mEnabledInputMethodsController;
    }

    /**
     * Map of window perceptible states indexed by their associated window tokens.
     *
     * <p>The value {@code true} indicates that IME has not been mostly hidden via
     * {@link android.view.InsetsController} for the given window.
     */
    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    private final WeakHashMap<IBinder, Boolean> mFocusedWindowPerceptible = new WeakHashMap<>();

    /**
     * The display ID of the input method indicates the fallback display which returned by
     * {@link #computeImeDisplayIdForTarget}.
     */
    static final int FALLBACK_DISPLAY_ID = DEFAULT_DISPLAY;

    /**
     * True if the device is currently interactive with user.  The value is true initially.
     */
    @MultiUserUnawareField
    boolean mIsInteractive = true;

    @SharedByAllUsersField
    private final MyPackageMonitor mMyPackageMonitor = new MyPackageMonitor();

    @SharedByAllUsersField
    private final String mSlotIme;

    /**
     * Registered {@link InputMethodListListener}.
     * This variable can be accessed from both of MainThread and BinderThread.
     */
    @SharedByAllUsersField
    private final CopyOnWriteArrayList<InputMethodListListener> mInputMethodListListeners =
            new CopyOnWriteArrayList<>();

    /**
     * Mapping of startInput token to IME target window token. This is set before dispatching
     * the startInput to the IME (in {@link #attachNewInputLocked}), and read when the IME replied
     * to it (in {@link #reportStartInputLocked}). When read, it is reported as the new IME target
     * in WindowManagerService.
     */
    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    private final WeakHashMap<IBinder, IBinder> mImeTargetWindowMap = new WeakHashMap<>();

    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    @NonNull
    private final StartInputHistory mStartInputHistory = new StartInputHistory();

    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    @NonNull
    private final SoftInputShowHideHistory mSoftInputShowHideHistory =
            new SoftInputShowHideHistory();

    @SharedByAllUsersField
    @NonNull
    private final ImeTrackerService mImeTrackerService;

    @GuardedBy("ImfLock.class")
    private void onSecureSettingsChangedLocked(@NonNull String key, @UserIdInt int userId) {
        switch (key) {
            case Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE: {
                final int accessibilitySoftKeyboardSetting = Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, 0, userId);
                final var userData = getUserData(userId);
                final var visibilityStateComputer = userData.mVisibilityStateComputer;
                visibilityStateComputer.getImePolicy().setA11yRequestNoSoftKeyboard(
                        accessibilitySoftKeyboardSetting);
                if (visibilityStateComputer.getImePolicy().isA11yRequestNoSoftKeyboard()) {
                    final var statsToken = createStatsTokenForFocusedClient(false /* show */,
                            SoftInputShowHideReason.HIDE_SETTINGS_ON_CHANGE, userId);
                    setImeVisibilityOnFocusedWindowClient(false, userData, statsToken);
                } else if (isShowRequestedForCurrentWindow(userId)) {
                    final var statsToken = createStatsTokenForFocusedClient(true /* show */,
                            SoftInputShowHideReason.SHOW_SETTINGS_ON_CHANGE, userId);
                    setImeVisibilityOnFocusedWindowClient(true, userData, statsToken);
                }
                break;
            }
            case Settings.Secure.STYLUS_HANDWRITING_ENABLED: {
                InputMethodManager.invalidateLocalStylusHandwritingAvailabilityCaches();
                InputMethodManager
                        .invalidateLocalConnectionlessStylusHandwritingAvailabilityCaches();
                break;
            }
            case Settings.Secure.DEFAULT_INPUT_METHOD:
            case Settings.Secure.ENABLED_INPUT_METHODS:
            case Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE: {
                boolean enabledChanged = false;
                String newEnabled = InputMethodSettingsRepository.get(userId)
                        .getEnabledInputMethodsStr();
                final var userData = getUserData(userId);
                if (!userData.mLastEnabledInputMethodsStr.equals(newEnabled)) {
                    userData.mLastEnabledInputMethodsStr = newEnabled;
                    enabledChanged = true;
                }
                updateInputMethodsFromSettingsLocked(enabledChanged, userId);
                break;
            }
            case Settings.Secure.IME_SWITCHER_BUTTON_IN_NAVBAR_ENABLED: {
                final var userData = getUserData(userId);
                updateSystemUiLocked(userId);
                sendOnNavButtonFlagsChangedLocked(userData);
                break;
            }
        }
    }

    /**
     * {@link BroadcastReceiver} that is intended to listen to broadcasts sent to all the users.
     */
    private final class ImmsBroadcastReceiverForAllUsers extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                if (Flags.imeSwitcherMenuSystemui()) {
                    // Tracked by the IME Switcher Menu Controller.
                    return;
                }
                final PendingResult pendingResult = getPendingResult();
                if (pendingResult == null) {
                    return;
                }
                // sender userId can be a real user ID or USER_ALL.
                final int senderUserId = pendingResult.getSendingUserId();
                synchronized (ImfLock.class) {
                    if (senderUserId != UserHandle.USER_ALL && senderUserId != mCurrentImeUserId) {
                        // A background user is trying to hide the dialog. Ignore.
                        return;
                    }
                    final int userId = mCurrentImeUserId;
                    final var bindingController = getInputMethodBindingController(userId);
                    mImeSwitcherMenu.hide(bindingController.getCurDisplayId(), userId);
                }
            } else {
                Slog.w(TAG, "Unexpected intent " + intent);
            }
        }
    }

    /**
     * Handles {@link Intent#ACTION_LOCALE_CHANGED}.
     *
     * <p>Note: For historical reasons, {@link Intent#ACTION_LOCALE_CHANGED} has been sent to all
     * the users.</p>
     */
    @WorkerThread
    void onActionLocaleChanged(@NonNull LocaleList prevLocales, @NonNull LocaleList newLocales) {
        ProtoLog.v(IMMS_DEBUG, "onActionLocaleChanged prev=%s new=%s", prevLocales, newLocales);
        synchronized (ImfLock.class) {
            if (!mSystemReady) {
                return;
            }
            for (int userId : mUserManagerInternal.getUserIds()) {
                // Does InputMethodInfo really have data dependency on system locale?
                // TODO(b/356679261): Check if we really need to update RawInputMethodInfo here.
                {
                    final var userData = getUserData(userId);
                    final var additionalSubtypeMap = AdditionalSubtypeMapRepository.get(userId);
                    final var rawMethodMap = queryRawInputMethodServiceMap(mContext, userId);
                    userData.mRawInputMethodMap.set(rawMethodMap);
                    final var methodMap = rawMethodMap.toInputMethodMap(additionalSubtypeMap,
                            DirectBootAwareness.AUTO,
                            userData.mIsUnlockingOrUnlocked.get());
                    final var settings = InputMethodSettings.create(methodMap, userId);
                    InputMethodSettingsRepository.put(userId, settings);
                }
                postInputMethodSettingUpdatedLocked(true /* resetDefaultEnabledIme */, userId);
                // If the locale is changed, needs to reset the default ime
                resetDefaultImeLocked(mContext, userId);
                updateInputMethodsFromSettingsLocked(true, userId);
            }
        }
    }

    final class MyPackageMonitor extends PackageMonitor {
        /**
         * Remembers package names passed to {@link #onPackageDataCleared(String, int)}.
         *
         * <p>This field must be accessed only from callback methods in {@link PackageMonitor},
         * which should be bound to {@link #getRegisteredHandler()}.</p>
         */
        private final ArrayList<String> mDataClearedPackages = new ArrayList<>();

        private MyPackageMonitor() {
            super(true);
        }

        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            synchronized (ImfLock.class) {
                final int userId = getChangingUserId();
                final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
                final String selectedImeId = settings.getSelectedInputMethod();
                final List<InputMethodInfo> methodList = settings.getMethodList();
                final int numImes = methodList.size();
                if (selectedImeId != null) {
                    for (int i = 0; i < numImes; i++) {
                        InputMethodInfo imi = methodList.get(i);
                        if (imi.getId().equals(selectedImeId)) {
                            for (String pkg : packages) {
                                if (imi.getPackageName().equals(pkg)) {
                                    if (!doit) {
                                        return true;
                                    }
                                    resetSelectedInputMethodAndSubtypeLocked("", userId);
                                    chooseNewDefaultIMELocked(userId);
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        }

        @Override
        public void onBeginPackageChanges() {
            clearPackageChangeState();
        }

        @Override
        public void onPackageDataCleared(String packageName, int uid) {
            mDataClearedPackages.add(packageName);
        }

        @Override
        public void onFinishPackageChanges() {
            onFinishPackageChangesInternal();
            clearPackageChangeState();
        }

        private void clearPackageChangeState() {
            // No need to lock them because we access these fields only on getRegisteredHandler().
            mDataClearedPackages.clear();
        }

        private void onFinishPackageChangesInternal() {
            final int userId = getChangingUserId();
            final var userData = getUserData(userId);

            userData.mRawInputMethodMap.set(queryRawInputMethodServiceMap(mContext, userId));

            final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);

            InputMethodInfo selectedImi = null;
            final String selectedImeId = settings.getSelectedInputMethod();
            final List<InputMethodInfo> methodList = settings.getMethodList();

            final ArrayList<String> imesToClearAdditionalSubtypes = new ArrayList<>();
            final ArrayList<String> imesToBeDisabled = new ArrayList<>();
            final int numImes = methodList.size();
            for (int i = 0; i < numImes; i++) {
                InputMethodInfo imi = methodList.get(i);
                final String imiId = imi.getId();
                if (imiId.equals(selectedImeId)) {
                    selectedImi = imi;
                }
                if (mDataClearedPackages.contains(imi.getPackageName())) {
                    imesToClearAdditionalSubtypes.add(imiId);
                }
                int change = isPackageDisappearing(imi.getPackageName());
                if (change == PACKAGE_PERMANENT_CHANGE) {
                    Slog.i(TAG, "Input method uninstalled, disabling: " + imi.getComponent());
                    imesToBeDisabled.add(imi.getId());
                } else if (change == PACKAGE_UPDATING) {
                    Slog.i(TAG, "Input method reinstalling, clearing additional subtypes: "
                            + imi.getComponent());
                    imesToClearAdditionalSubtypes.add(imiId);
                }
            }

            // Clear additional subtypes as a batch operation.
            final var additionalSubtypeMap = AdditionalSubtypeMapRepository.get(userId);
            final AdditionalSubtypeMap newAdditionalSubtypeMap =
                    additionalSubtypeMap.cloneWithRemoveOrSelf(imesToClearAdditionalSubtypes);
            final boolean additionalSubtypeChanged =
                    (newAdditionalSubtypeMap != additionalSubtypeMap);
            if (additionalSubtypeChanged) {
                AdditionalSubtypeMapRepository.putAndSave(userId, newAdditionalSubtypeMap,
                        settings.getMethodMap());
            }

            final var newMethodMap = userData.mRawInputMethodMap.get().toInputMethodMap(
                    newAdditionalSubtypeMap,
                    DirectBootAwareness.AUTO,
                    userData.mIsUnlockingOrUnlocked.get());

            final boolean noUpdate = InputMethodMap.areSame(settings.getMethodMap(), newMethodMap);
            if (noUpdate && imesToBeDisabled.isEmpty()) {
                return;
            }

            // Here we start remaining tasks that need to be done with the lock (b/340221861).
            synchronized (ImfLock.class) {
                final int numImesToBeDisabled = imesToBeDisabled.size();
                for (int i = 0; i < numImesToBeDisabled; ++i) {
                    setInputMethodEnabledLocked(imesToBeDisabled.get(i), false /* enabled */,
                            userId);
                }
                if (noUpdate) {
                    return;
                }
                InputMethodSettingsRepository.put(userId,
                        InputMethodSettings.create(newMethodMap, userId));
                postInputMethodSettingUpdatedLocked(false /* resetDefaultEnabledIme */, userId);

                boolean changed = false;

                if (selectedImi != null) {
                    int change = isPackageDisappearing(selectedImi.getPackageName());
                    if (change == PACKAGE_TEMPORARY_CHANGE
                            || change == PACKAGE_PERMANENT_CHANGE) {
                        final PackageManager userAwarePackageManager =
                                getPackageManagerForUser(mContext, userId);
                        ServiceInfo si = null;
                        try {
                            si = userAwarePackageManager.getServiceInfo(selectedImi.getComponent(),
                                    PackageManager.ComponentInfoFlags.of(0));
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                        if (si == null) {
                            // Uh oh, current input method is no longer around!
                            // Pick another one...
                            Slog.i(TAG, "Current input method removed: " + selectedImeId);
                            final var bindingController = getInputMethodBindingController(userId);
                            updateSystemUiLocked(0 /* vis */,
                                    bindingController.getBackDisposition(), userId);
                            if (!chooseNewDefaultIMELocked(userId)) {
                                changed = true;
                                selectedImi = null;
                                Slog.i(TAG, "Unsetting current input method");
                                resetSelectedInputMethodAndSubtypeLocked("", userId);
                            }
                        }
                    }
                }

                if (selectedImi == null) {
                    // We currently don't have a default input method... is
                    // one now available?
                    changed = chooseNewDefaultIMELocked(userId);
                } else if (!changed && isPackageModified(selectedImi.getPackageName())) {
                    // Even if the current input method is still available, current subtype could
                    // be obsolete when the package is modified in practice.
                    changed = true;
                }

                if (changed) {
                    updateInputMethodsFromSettingsLocked(false, userId);
                }
            }
        }
    }

    private static final class UserSwitchHandlerTask implements Runnable {

        @NonNull
        private final InputMethodManagerService mService;

        /** The ID of the user to switch to. */
        @UserIdInt
        final int mNewUserId;

        /** Whether this is a switch between user profiles or full users. */
        final boolean mProfileSwitch;

        /** The IME client for which to reset the input connection, at the end of the switch. */
        @Nullable
        IInputMethodClientInvoker mClientToBeReset;

        UserSwitchHandlerTask(@NonNull InputMethodManagerService service, @UserIdInt int newUserId,
                boolean profileSwitch, @Nullable IInputMethodClientInvoker clientToBeReset) {
            mService = service;
            mNewUserId = newUserId;
            mProfileSwitch = profileSwitch;
            mClientToBeReset = clientToBeReset;
        }

        @Override
        public void run() {
            synchronized (ImfLock.class) {
                if (mService.mUserSwitchHandlerTask != this) {
                    // This task was already canceled before it is handled here. So do nothing.
                    return;
                }
                mService.switchUserOnHandlerLocked(mNewUserId, mProfileSwitch, mClientToBeReset);
                mService.mUserSwitchHandlerTask = null;
            }
        }
    }

    /**
     * When non-{@code null}, this represents pending user-switch task, which is to be executed as
     * a handler callback.  This needs to be set and unset only within the lock.
     */
    @Nullable
    @GuardedBy("ImfLock.class")
    @MultiUserUnawareField
    private UserSwitchHandlerTask mUserSwitchHandlerTask;

    /**
     * {@link SystemService} used to publish and manage the lifecycle of
     * {@link InputMethodManagerService}.
     */
    public static final class Lifecycle extends SystemService
            implements UserManagerInternal.UserLifecycleListener {
        private final InputMethodManagerService mService;

        public Lifecycle(Context context) {
            this(context, createServiceForProduction(context));

            // For production code, hook up user lifecycle
            mService.mUserManagerInternal.addUserLifecycleListener(this);

            // Hook up resource change first before initializeUsersAsync() starts reading the
            // seemingly initial data so that we can eliminate the race condition.
            InputMethodDrawsNavBarResourceMonitor.registerCallback(context, mService.mIoHandler,
                    mService::onUpdateResourceOverlay);

            // Also schedule user init tasks onto an I/O thread.
            initializeUsersAsync(mService.mUserManagerInternal.getUserIds());
        }

        @VisibleForTesting
        Lifecycle(Context context, @NonNull InputMethodManagerService inputMethodManagerService) {
            super(context);
            mService = inputMethodManagerService;
        }

        /**
         * Does initialization then instantiate {@link InputMethodManagerService} for production
         * configurations.
         *
         * <p>We have this abstraction just because several unit tests directly initialize
         * {@link InputMethodManagerService} with some mocked/emulated dependencies.</p>
         *
         * @param context {@link Context} to be used to set up
         * @return {@link InputMethodManagerService} object to be used
         */
        @NonNull
        private static InputMethodManagerService createServiceForProduction(
                @NonNull Context context) {
            final ServiceThread thread = new ServiceThread(HANDLER_THREAD_NAME,
                    Process.THREAD_PRIORITY_FOREGROUND, false /* allowIo */);
            thread.start();

            final ServiceThread ioThread = new ServiceThread(PACKAGE_MONITOR_THREAD_NAME,
                    Process.THREAD_PRIORITY_FOREGROUND, true /* allowIo */);
            ioThread.start();

            SecureSettingsWrapper.setContentResolver(context.getContentResolver());

            return new InputMethodManagerService(context,
                    shouldEnableConcurrentMultiUserMode(context), thread.getLooper(),
                    Handler.createAsync(ioThread.getLooper()),
                    null /* bindingControllerForTesting */);
        }

        @Override
        public void onStart() {
            mService.publishLocalService();
            IInputMethodManagerImpl.Callback service =
                    new ZeroJankProxy(mService.mHandler::post, mService);
            publishBinderService(Context.INPUT_METHOD_SERVICE,
                    IInputMethodManagerImpl.create(service), false /*allowIsolated*/,
                    DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PRIORITY_NORMAL | DUMP_FLAG_PROTO);
            mService.registerImeRequestedChangedListener();
        }

        @Override
        public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
            // Called on ActivityManager thread.
            synchronized (ImfLock.class) {
                if (mService.mConcurrentMultiUserModeEnabled) {
                    // In concurrent multi-user mode, we in general do not rely on the concept of
                    // current user.
                    return;
                }
                mService.scheduleSwitchUserTaskLocked(to.getUserIdentifier(),
                        false /* profileSwitch */, null /* clientToBeReset */);
            }
        }

        @Override
        public void onBootPhase(int phase) {
            // Called on ActivityManager thread.
            // TODO: Dispatch this to a worker thread as needed.
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mService.systemRunning();
            }
        }

        @Override
        public void onUserCreated(UserInfo user, @Nullable Object token) {
            // Called directly from UserManagerService. Do not block the calling thread.
            final int userId = user.id;
            AdditionalSubtypeMapRepository.onUserCreated(userId);
            initializeUsersAsync(new int[]{ userId });
        }

        @Override
        public void onUserRemoved(UserInfo user) {
            // Called directly from UserManagerService. Do not block the calling thread.
            final int userId = user.id;
            SecureSettingsWrapper.onUserRemoved(userId);
            AdditionalSubtypeMapRepository.remove(userId);
            InputMethodSettingsRepository.remove(userId);
            mService.mUserDataRepository.remove(userId);
            if (mService.mConcurrentMultiUserModeEnabled) {
                // In concurrent multi-user mode, we in general do not rely on the concept of
                // current user.
                return;
            }
            synchronized (ImfLock.class) {
                final int nextOrCurrentUser = mService.mUserSwitchHandlerTask != null
                        ? mService.mUserSwitchHandlerTask.mNewUserId : mService.mCurrentImeUserId;
                if (userId == nextOrCurrentUser) {
                    // The current user was removed without a pending user switch, or the user
                    // of the pending user switch was removed. Switch to the current full user from
                    // ActivityManager to allow starting input on it or one of its profiles later.
                    // Note: full users cannot be removed while they are the current user, as they
                    // require a user switch beforehand.
                    final int amUserId = mService.mActivityManagerInternal.getCurrentUserId();
                    // For the pending switch case, we cannot determine whether this would lead to
                    // a profile switch between the current IMMS and ActivityManager users, fallback
                    // to non-profile switch.
                    final boolean profileSwitch = mService.mUserSwitchHandlerTask == null
                            && user.isProfile() && user.profileGroupId == amUserId;
                    mService.scheduleSwitchUserTaskLocked(amUserId, profileSwitch,
                            null /* clientToBeReset */);
                }
            }
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            // Called on ActivityManager thread. Do not block the calling thread.
            final int userId = user.getUserIdentifier();
            final var userData = mService.getUserData(userId);
            final boolean userUnlocked = true;
            userData.mIsUnlockingOrUnlocked.set(userUnlocked);
            SecureSettingsWrapper.onUserUnlocking(userId);
            final var methodMap = userData.mRawInputMethodMap.get().toInputMethodMap(
                    AdditionalSubtypeMapRepository.get(userId), DirectBootAwareness.AUTO,
                    userUnlocked);
            final var newSettings = InputMethodSettings.create(methodMap, userId);
            InputMethodSettingsRepository.put(userId, newSettings);
            mService.mIoHandler.post(() -> {
                synchronized (ImfLock.class) {
                    if (!mService.mSystemReady) {
                        return;
                    }
                    // We need to rebuild IMEs.
                    mService.postInputMethodSettingUpdatedLocked(false /* resetDefaultEnabledIme */,
                            userId);
                    mService.updateInputMethodsFromSettingsLocked(true /* enabledChanged */,
                            userId);
                }
            });
        }

        @Override
        public void onUserStarting(TargetUser user) {
            // Called on ActivityManager thread.
            final int userId = user.getUserIdentifier();
            SecureSettingsWrapper.onUserStarting(userId);
            mService.mIoHandler.post(() -> {
                synchronized (ImfLock.class) {
                    if (mService.mSystemReady) {
                        mService.onUserReadyLocked(userId);
                    }
                }
            });
        }

        @AnyThread
        private void initializeUsersAsync(@UserIdInt int[] userIds) {
            Slog.d(TAG, "Schedule initialization for users=" + Arrays.toString(userIds));
            mService.mIoHandler.post(() -> {
                final var service = mService;
                final var context = service.mContext;
                final var userManagerInternal = service.mUserManagerInternal;

                for (int userId : userIds) {
                    Slog.d(TAG, "Start initialization for user=" + userId);
                    final var userData = mService.getUserData(userId);

                    AdditionalSubtypeMapRepository.initializeIfNecessary(userId);
                    final var additionalSubtypeMap = AdditionalSubtypeMapRepository.get(userId);
                    final var rawMethodMap = queryRawInputMethodServiceMap(context, userId);
                    userData.mRawInputMethodMap.set(rawMethodMap);

                    final boolean unlocked = userManagerInternal.isUserUnlockingOrUnlocked(userId);
                    userData.mIsUnlockingOrUnlocked.set(unlocked);
                    final var methodMap = rawMethodMap.toInputMethodMap(additionalSubtypeMap,
                            DirectBootAwareness.AUTO, unlocked);

                    final var settings = InputMethodSettings.create(methodMap, userId);
                    InputMethodSettingsRepository.put(userId, settings);

                    final int profileParentId = userManagerInternal.getProfileParentId(userId);
                    final boolean value =
                            InputMethodDrawsNavBarResourceMonitor.evaluate(context,
                                    profileParentId);
                    userData.mImeDrawsNavBar.set(value);

                    userData.mBackgroundLoadLatch.countDown();
                    Slog.d(TAG, "Complete initialization for user=" + userId);
                }
            });
        }

        @Override
        public void onUserStopped(@NonNull TargetUser user) {
            final int userId = user.getUserIdentifier();
            // Called on ActivityManager thread.

            // Following operations should be trivial and fast enough, so do not dispatch them to
            // the IO thread.
            SecureSettingsWrapper.onUserStopped(userId);
            final var userData = mService.getUserData(userId);
            final var additionalSubtypeMap = AdditionalSubtypeMapRepository.get(userId);
            final var rawMethodMap = userData.mRawInputMethodMap.get();
            final boolean userUnlocked = false;  // Stopping a user also locks their storage.
            userData.mIsUnlockingOrUnlocked.set(userUnlocked);
            final var methodMap = rawMethodMap.toInputMethodMap(additionalSubtypeMap,
                    DirectBootAwareness.AUTO, userUnlocked);
            InputMethodSettingsRepository.put(userId,
                    InputMethodSettings.create(methodMap, userId));
        }
    }

    /**
     * Schedules a switch to the given user. If there is a pending switch to the given user, this
     * only updates the {@code clientToBeReset}. Otherwise, any pending switch is cancelled. If
     * the switch is requested on the {@link #mCurrentImeUserId}, this is a no-op.
     *
     * @param newUserId       the ID of the user to switch to.
     * @param profileSwitch   whether this is switch between user profiles or full users.
     * @param clientToBeReset the IME client for which to reset the input connection, at the end of
     *                        the switch.
     *
     * @return whether there is a pending user switch (either pre-exiting or new).
     */
    @GuardedBy("ImfLock.class")
    private boolean scheduleSwitchUserTaskLocked(@UserIdInt int newUserId, boolean profileSwitch,
            @Nullable IInputMethodClientInvoker clientToBeReset) {
        if (mUserSwitchHandlerTask != null) {
            // Already have a pending user switch.
            if (newUserId == mUserSwitchHandlerTask.mNewUserId) {
                // Pending user switch for the given user, update client only.
                mUserSwitchHandlerTask.mClientToBeReset = clientToBeReset;
                return true;
            }
            // Pending user switch for a different user, cancel it.
            ProtoLog.i(IMMS_WITH_LOGCAT, "Removing scheduled user switch to userId=%d",
                    mUserSwitchHandlerTask.mNewUserId);
            mIoHandler.removeCallbacks(mUserSwitchHandlerTask);
            mUserSwitchHandlerTask = null;
        }
        if (newUserId == mCurrentImeUserId) {
            // Switching to the current user, this is a no-op.
            return false;
        }
        // Hide IME before user switch task as it may block main handler a while and delay any
        // subsequent hide request.
        final var userData = getUserData(mCurrentImeUserId);
        final var statsToken = createStatsTokenForFocusedClient(false /* show */,
                SoftInputShowHideReason.HIDE_SWITCH_USER, mCurrentImeUserId);
        hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow,
                false /* updateTargetWindow */, statsToken,
                SoftInputShowHideReason.HIDE_SWITCH_USER, mCurrentImeUserId);
        final var task = new UserSwitchHandlerTask(this, newUserId, profileSwitch, clientToBeReset);
        mUserSwitchHandlerTask = task;
        ProtoLog.i(IMMS_WITH_LOGCAT,
                "Scheduling user switch newUserId=%d currentUserId=%d profileSwitch=%b",
                newUserId, mCurrentImeUserId, profileSwitch);
        mIoHandler.post(task);
        return true;
    }

    @VisibleForTesting
    InputMethodManagerService(
            Context context,
            boolean concurrentMultiUserModeEnabled,
            @NonNull Looper uiLooper,
            @NonNull Handler ioHandler,
            @Nullable IntFunction<InputMethodBindingController> bindingControllerForTesting) {
        synchronized (ImfLock.class) {
            mConcurrentMultiUserModeEnabled = concurrentMultiUserModeEnabled;
            mContext = context;
            mRes = context.getResources();

            mHandler = Handler.createAsync(uiLooper, this);
            mIoHandler = ioHandler;
            SystemLocaleWrapper.onStart(context, this::onActionLocaleChanged, mIoHandler);
            mImeTrackerService = new ImeTrackerService(mHandler);
            // Note: SettingsObserver doesn't register observers in its constructor.
            mSettingsObserver = new SettingsObserver(mHandler);
            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
            mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
            mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
            mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
            mImePlatformCompatUtils = new ImePlatformCompatUtils();
            mInputMethodDeviceConfigs = new InputMethodDeviceConfigs();
            mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
            mSlotIme = mContext.getString(com.android.internal.R.string.status_bar_ime);

            ProtoLog.init(ImeProtoLogGroup.values());

            mCurrentImeUserId = mActivityManagerInternal.getCurrentUserId();
            final IntFunction<InputMethodBindingController> bindingControllerFactory = userId ->
                    new InputMethodBindingController(userId, this, IME_CONNECTION_BIND_FLAGS,
                            IME_BACKGROUND_BIND_FLAGS);
            final IntFunction<ImeVisibilityStateComputer> visibilityStateComputerFactory = userId ->
                    new ImeVisibilityStateComputer(this, userId);
            mUserDataRepository = new UserDataRepository(
                    bindingControllerForTesting != null ? bindingControllerForTesting
                            : bindingControllerFactory, visibilityStateComputerFactory);

            mImeSwitcherMenu = Flags.imeSwitcherMenuSystemui()
                    ? new ImeSwitcherMenuWrapper() : new InputMethodMenuController();
            mImeSwitcherMenuDeathRecipient = () -> {
                synchronized (ImfLock.class) {
                    mUserDataRepository.forAllUserData((u -> u.mImeSwitcherMenuVisible = false));
                    mIImeSwitcherMenu = null;
                }
            };

            mClientController = new ClientController(mPackageManagerInternal);
            mClientController.addClientControllerCallback(this::onClientRemoved);

            mPreventImeStartupUnlessTextEditor = mRes.getBoolean(
                    com.android.internal.R.bool.config_preventImeStartupUnlessTextEditor);
            if (mPreventImeStartupUnlessTextEditor) {
                mPreventImeStartupBypassedApps = mRes.getStringArray(
                        com.android.internal.R.array.config_preventImeStartupBypassedApps);
                mNonPreemptibleInputMethods = mRes.getStringArray(
                        com.android.internal.R.array.config_nonPreemptibleInputMethods);
            } else {
                mNonPreemptibleInputMethods = null;
            }
            Runnable discardDelegationTextRunnable = this::discardHandwritingDelegationText;
            mHwController = new HandwritingModeController(mContext, uiLooper,
                    new InkWindowInitializer(), discardDelegationTextRunnable);
            registerDeviceListenerAndCheckStylusSupport();
            mInputMethodManagerInternal = new LocalServiceImpl();
        }
    }

    private final class InkWindowInitializer implements Runnable {
        public void run() {
            synchronized (ImfLock.class) {
                final IInputMethodInvoker curIme = getInputMethodBindingController(
                        mCurrentImeUserId).getCurIme();
                if (curIme != null) {
                    curIme.initInkWindow();
                }
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void onUpdateEditorToolTypeLocked(@MotionEvent.ToolType int toolType,
            @UserIdInt int userId) {
        final IInputMethodInvoker curIme = getInputMethodBindingController(userId).getCurIme();
        if (curIme != null) {
            curIme.updateEditorToolType(toolType);
        }
    }

    private void discardHandwritingDelegationText() {
        synchronized (ImfLock.class) {
            final IInputMethodInvoker curIme = getInputMethodBindingController(mCurrentImeUserId)
                    .getCurIme();
            if (curIme != null) {
                curIme.discardHandwritingDelegationText();
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void resetDefaultImeLocked(Context context, @UserIdInt int userId) {
        final var bindingController = getInputMethodBindingController(userId);
        // Do not reset the default (current) IME when it is a 3rd-party IME
        final String selectedImeId = bindingController.getSelectedImeId();
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final InputMethodInfo selectedImi = settings.getMethodMap().get(selectedImeId);
        if (selectedImi != null && !selectedImi.isSystem()) {
            return;
        }
        final List<InputMethodInfo> suitableImes = InputMethodInfoUtils.getDefaultEnabledImes(
                context, settings.getEnabledInputMethodList());
        if (suitableImes.isEmpty()) {
            Slog.i(TAG, "No default found");
            return;
        }
        final InputMethodInfo defIm = suitableImes.getFirst();
        ProtoLog.v(IMMS_DEBUG, "Default found, using %s", defIm.getId());
        setSelectedInputMethodAndSubtypeLocked(defIm, NOT_A_SUBTYPE_INDEX, false, userId);
    }

    @NonNull
    static PackageManager getPackageManagerForUser(@NonNull Context context,
            @UserIdInt int userId) {
        return context.getUserId() == userId
                ? context.getPackageManager()
                : context.createContextAsUser(UserHandle.of(userId), 0 /* flags */)
                        .getPackageManager();
    }

    /**
     * Handles switching to the given user.
     *
     * @param newUserId       the ID of the user to switch to.
     * @param profileSwitch   whether this is a switch between user profiles or full users.
     * @param clientToBeReset the IME client for which to reset the input connection, at the end of
     *                        the switch.
     */
    @GuardedBy("ImfLock.class")
    private void switchUserOnHandlerLocked(@UserIdInt int newUserId, boolean profileSwitch,
            @Nullable IInputMethodClientInvoker clientToBeReset) {
        final int prevUserId = mCurrentImeUserId;
        ProtoLog.i(IMMS_WITH_LOGCAT, "Switching user stage 1/3. newUserId=%d prevUserId=%d",
                newUserId, prevUserId);

        // Clean up stuff for mCurrentImeUserId, which soon becomes the previous user.

        // Note that in b/197848765 we want to see if we can keep the binding alive for better
        // profile switching.
        final var bindingController = getInputMethodBindingController(prevUserId);
        if (Flags.warmWorkProfileIme() && profileSwitch && !mPreventImeStartupUnlessTextEditor) {
            bindingController.setInactive();
        } else if (Flags.warmWorkProfileIme() && !mPreventImeStartupUnlessTextEditor) {
            // Unbind the IMEs of all profiles of the previous user, if still bound,
            // and restores the default active state.
            for (final int profileId : getProfileIds(prevUserId)) {
                final var controller = getInputMethodBindingController(profileId);
                controller.unbindIme();
                controller.setActive();
            }
        } else {
            bindingController.unbindIme();
        }

        unbindCurrentClientLocked(UnbindReason.SWITCH_USER, prevUserId);

        // Hereafter we start initializing things for "newUserId".

        final var newUserData = getUserData(newUserId);

        // TODO(b/342027196): Double check if we need to always reset upon user switching.
        newUserData.mLastEnabledInputMethodsStr = "";

        mCurrentImeUserId = newUserId;
        final String defaultImiId = SecureSettingsWrapper.getString(
                Settings.Secure.DEFAULT_INPUT_METHOD, null, newUserId);

        ProtoLog.i(IMMS_WITH_LOGCAT, "Switching user stage 2/3. newUserId=%d defaultImiId=%s",
                newUserId, defaultImiId);

        // For secondary users, the list of enabled IMEs may not have been updated since the
        // callbacks to PackageMonitor are ignored for the secondary user. Here, defaultImiId may
        // not be empty even if the IME has been uninstalled by the primary user.
        // Even in such cases, IMMS works fine because it will find the most applicable
        // IME for that user.
        final boolean initialUserSwitch = TextUtils.isEmpty(defaultImiId);

        final InputMethodSettings newSettings = InputMethodSettingsRepository.get(newUserId);
        postInputMethodSettingUpdatedLocked(initialUserSwitch /* resetDefaultEnabledIme */,
                newUserId);
        final String newSelectedImeId = newSettings.getSelectedInputMethod();
        if (TextUtils.isEmpty(newSelectedImeId)) {
            // This is the first time of the user switch and
            // set the current ime to the proper one.
            resetDefaultImeLocked(mContext, newUserId);
        }
        updateInputMethodsFromSettingsLocked(true, newUserId);

        // Special workaround for b/356879517.
        // KeyboardLayoutManager still expects onInputMethodSubtypeChangedForKeyboardLayoutMapping
        // to be called back upon IME user switching, while we are actively deprecating the concept
        // of "current IME user" at b/350386877.
        // TODO(b/356879517): Come up with a way to avoid this special handling.
        if (newUserData.mSubtypeForKeyboardLayoutMapping != null) {
            final var imiAndSubtype = newUserData.mSubtypeForKeyboardLayoutMapping;
            mInputManagerInternal.onInputMethodSubtypeChangedForKeyboardLayoutMapping(
                    newUserId, imiAndSubtype.first, imiAndSubtype.second);
        }

        if (initialUserSwitch) {
            InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(
                    getPackageManagerForUser(mContext, newUserId),
                    newSettings.getEnabledInputMethodList());
        }

        updateTouchPollingRate();
        updateTouchSensitivity();
        updateTouchHovering();

        ProtoLog.i(IMMS_WITH_LOGCAT, "Switching user stage 3/3. newUserId=%d selectedImeId=%s",
                newUserId, newSelectedImeId);

        if (mIsInteractive && clientToBeReset != null) {
            final ClientState cs = mClientController.getClient(clientToBeReset.asBinder());
            if (cs == null) {
                // The client is already gone.
                return;
            }
            cs.mClient.scheduleStartInputIfNecessary(newUserData.mInFullscreenMode);
        }

        if (Flags.warmWorkProfileIme()) {
            newUserData.mBindingController.setActive();
        }
    }

    private void waitForUserInitialization() {
        final int[] userIds = mUserManagerInternal.getUserIds();
        final long deadlineNanos = SystemClock.elapsedRealtimeNanos()
                + TimeUnit.MILLISECONDS.toNanos(SYSTEM_READY_USER_INIT_TIMEOUT);
        boolean interrupted = false;
        try {
            for (int userId : userIds) {
                final var latch = getUserData(userId).mBackgroundLoadLatch;
                boolean awaitResult;
                while (true) {
                    try {
                        final long remainingNanos =
                                Math.max(deadlineNanos - SystemClock.elapsedRealtimeNanos(), 0);
                        awaitResult = latch.await(remainingNanos, TimeUnit.NANOSECONDS);
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (!awaitResult) {
                    Slog.w(TAG, "Timed out for user#" + userId + " to be initialized");
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * TODO(b/32343335): The entire systemRunning() method needs to be revisited.
     */
    public void systemRunning() {
        waitForUserInitialization();

        synchronized (ImfLock.class) {
            ProtoLog.v(IMMS_DEBUG, "--- systemReady");
            if (!mSystemReady) {
                mSystemReady = true;

                // Must happen before registerContentObserverLocked
                mLineageHardware = LineageHardwareManager.getInstance(mContext);

                updateTouchPollingRate();
                updateTouchSensitivity();
                updateTouchHovering();

                mStatusBarManagerInternal =
                        LocalServices.getService(StatusBarManagerInternal.class);
                hideStatusBarIconLocked(mCurrentImeUserId);
                final var bindingController = getInputMethodBindingController(mCurrentImeUserId);
                updateSystemUiLocked(bindingController.getImeWindowVis(),
                        bindingController.getBackDisposition(), mCurrentImeUserId);

                mMyPackageMonitor.register(mContext, UserHandle.ALL, mIoHandler);
                mSettingsObserver.registerContentObserverForAllUsers();
                SecureSettingsChangeCallback.register(mHandler, mContext.getContentResolver(),
                        new String[] {
                                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                                Settings.Secure.DEFAULT_INPUT_METHOD,
                                Settings.Secure.ENABLED_INPUT_METHODS,
                                Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE,
                                Settings.Secure.STYLUS_HANDWRITING_ENABLED,
                                Settings.Secure.IME_SWITCHER_BUTTON_IN_NAVBAR_ENABLED,
                        }, (key, flags, userId) -> {
                            synchronized (ImfLock.class) {
                                onSecureSettingsChangedLocked(key, userId);
                            }
                        });

                final IntentFilter broadcastFilterForAllUsers = new IntentFilter();
                broadcastFilterForAllUsers.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
                mContext.registerReceiverAsUser(new ImmsBroadcastReceiverForAllUsers(),
                        UserHandle.ALL, broadcastFilterForAllUsers, null, null,
                        Context.RECEIVER_EXPORTED);

                AdditionalSubtypeMapRepository.startWriterThread();

                for (int userId : mUserManagerInternal.getUserIds()) {
                    onUserReadyLocked(userId);
                }
            }
        }
    }

    @GuardedBy("ImfLock.class")
    void onUserReadyLocked(@UserIdInt int userId) {
        if (!mUserManagerInternal.isUserRunning(userId)) {
            return;
        }

        final String defaultImiId = SecureSettingsWrapper.getString(
                Settings.Secure.DEFAULT_INPUT_METHOD, null, userId);
        final boolean imeSelectedOnBoot = !TextUtils.isEmpty(defaultImiId);
        final var settings = InputMethodSettingsRepository.get(userId);
        postInputMethodSettingUpdatedLocked(!imeSelectedOnBoot /* resetDefaultEnabledIme */,
                userId);
        updateInputMethodsFromSettingsLocked(true, userId);
        InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(
                getPackageManagerForUser(mContext, userId), settings.getEnabledInputMethodList());
    }

    void registerImeRequestedChangedListener() {
        mWindowManagerInternal.setOnImeRequestedChangedListener(
                (windowToken, imeVisible, statsToken) -> {
                    if (imeVisible) {
                        showCurrentInputInternal(windowToken, statsToken, false /* forceShow */);
                    } else {
                        hideCurrentInputInternal(windowToken, statsToken);
                    }
                });
    }

    @BinderThread
    @Nullable
    @Override
    public InputMethodInfo getCurrentInputMethodInfoAsUser(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        final var bindingController = getInputMethodBindingController(userId);
        final String selectedImeId;
        synchronized (ImfLock.class) {
            selectedImeId = bindingController.getSelectedImeId();
        }
        return InputMethodSettingsRepository.get(userId).getMethodMap().get(selectedImeId);
    }

    @BinderThread
    @NonNull
    @Override
    public InputMethodInfoSafeList getInputMethodList(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        if (!mUserManagerInternal.exists(userId)) {
            return InputMethodInfoSafeList.create(null);
        }
        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            return InputMethodInfoSafeList.create(getInputMethodListInternal(
                    userId, directBootAwareness, callingUid));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @BinderThread
    @NonNull
    @Override
    public InputMethodInfoSafeList getEnabledInputMethodList(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        if (!mUserManagerInternal.exists(userId)) {
            return InputMethodInfoSafeList.create(null);
        }
        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            return InputMethodInfoSafeList.create(
                    getEnabledInputMethodListInternal(userId, callingUid));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean isStylusHandwritingAvailableAsUser(
            @UserIdInt int userId, boolean connectionless) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }

        synchronized (ImfLock.class) {
            if (!isStylusHandwritingEnabled(mContext, userId)) {
                return false;
            }

            // Check if selected IME of current user supports handwriting.
            if (userId == mCurrentImeUserId) {
                final var bindingController = getInputMethodBindingController(userId);
                return bindingController.getSupportsStylusHandwriting()
                        && (!connectionless
                        || bindingController.getSupportsConnectionlessStylusHandwriting());
            }
            final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
            final InputMethodInfo selectedImi = settings.getMethodMap().get(
                    settings.getSelectedInputMethod());
            return selectedImi != null && selectedImi.supportsStylusHandwriting()
                    && (!connectionless || selectedImi.supportsConnectionlessStylusHandwriting());
        }
    }

    private boolean isStylusHandwritingEnabled(
            @NonNull Context context, @UserIdInt int userId) {
        // If user is a profile, use preference of it`s parent profile.
        final int profileParentUserId = mUserManagerInternal.getProfileParentId(userId);
        return Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.STYLUS_HANDWRITING_ENABLED,
                Settings.Secure.STYLUS_HANDWRITING_DEFAULT_VALUE, profileParentUserId) != 0;
    }

    List<InputMethodInfo> getInputMethodListInternal(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness, int callingUid) {
        final var userData = getUserData(userId);
        final var methodMap = userData.mRawInputMethodMap.get().toInputMethodMap(
                AdditionalSubtypeMapRepository.get(userId), directBootAwareness,
                userData.mIsUnlockingOrUnlocked.get());
        final var settings = InputMethodSettings.create(methodMap, userId);
        // Create a copy.
        final ArrayList<InputMethodInfo> methodList = new ArrayList<>(settings.getMethodList());
        // filter caller's access to input methods
        methodList.removeIf(imi ->
                !canCallerAccessInputMethod(imi.getPackageName(), callingUid, userId, settings));
        return methodList;
    }

    List<InputMethodInfo> getEnabledInputMethodListInternal(@UserIdInt int userId,
            int callingUid) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final ArrayList<InputMethodInfo> methodList = settings.getEnabledInputMethodList();
        // filter caller's access to input methods
        methodList.removeIf(imi ->
                !canCallerAccessInputMethod(imi.getPackageName(), callingUid, userId, settings));
        return methodList;
    }

    /**
     * Gets enabled subtypes of the specified {@link InputMethodInfo}.
     *
     * @param imiId                           if null, returns enabled subtypes for the current
     *                                        {@link InputMethodInfo}
     * @param allowsImplicitlyEnabledSubtypes {@code true} to return the implicitly enabled
     *                                        subtypes
     * @param userId                          the user ID to be queried about
     */
    @NonNull
    @Override
    public InputMethodSubtypeSafeList getEnabledInputMethodSubtypeList(String imiId,
            boolean allowsImplicitlyEnabledSubtypes, @UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }

        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            return InputMethodSubtypeSafeList.create(
                    getEnabledInputMethodSubtypeListInternal(imiId,
                        allowsImplicitlyEnabledSubtypes, userId, callingUid));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private List<InputMethodSubtype> getEnabledInputMethodSubtypeListInternal(String imiId,
            boolean allowsImplicitlyEnabledSubtypes, @UserIdInt int userId, int callingUid) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final InputMethodInfo imi = settings.getMethodMap().get(imiId);
        if (imi == null) {
            return Collections.emptyList();
        }
        if (!canCallerAccessInputMethod(imi.getPackageName(), callingUid, userId, settings)) {
            return Collections.emptyList();
        }
        return settings.getEnabledInputMethodSubtypeList(
                imi, allowsImplicitlyEnabledSubtypes);
    }

    /**
     * Called by each application process as a preparation to start interacting with
     * {@link InputMethodManagerService}.
     *
     * <p>As a general principle, IPCs from the application process that take
     * {@link IInputMethodClient} will be rejected without this step.</p>
     *
     * @param client                  {@link android.os.Binder} proxy that is associated with the
     *                                singleton instance of
     *                                {@link android.view.inputmethod.InputMethodManager} that runs
     *                                on the client process
     * @param fallbackInputConnection communication channel for the fallback {@link InputConnection}
     * @param selfReportedDisplayId   self-reported display ID to which the client is associated.
     *                                Whether the client is still allowed to access to this display
     *                                or not needs to be evaluated every time the client interacts
     *                                with the display
     */
    @Override
    public void addClient(@NonNull IInputMethodClient client,
            @NonNull IRemoteInputConnection fallbackInputConnection, int selfReportedDisplayId) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(fallbackInputConnection, "fallbackInputConnection must not be null");
        // Here there are two scenarios where this method is called:
        // A. IMM is being instantiated in a different process and this is an IPC from that process
        // B. IMM is being instantiated in the same process but Binder.clearCallingIdentity() is
        //    called in the caller side if necessary.
        // In either case the following UID/PID should be the ones where InputMethodManager is
        // actually running.
        final int callerUid = Binder.getCallingUid();
        final int callerPid = Binder.getCallingPid();
        final var clientInvoker = IInputMethodClientInvoker.create(client, mHandler);
        synchronized (ImfLock.class) {
            mClientController.addClient(clientInvoker, fallbackInputConnection,
                    selfReportedDisplayId, callerUid, callerPid);
        }
    }

    /**
     * Called when the given IME client (application) was removed (e.g. its process was killed).
     *
     * @param client the client that was removed.
     */
    @GuardedBy("ImfLock.class")
    private void onClientRemoved(@NonNull ClientState client) {
        clearClientSessionLocked(client);
        clearClientSessionForAccessibilityLocked(client);
        // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
        @SuppressWarnings("GuardedBy") Consumer<UserData> clientRemovedForUser =
                userData -> onClientRemovedInternalLocked(client, userData);
        mUserDataRepository.forAllUserData(clientRemovedForUser);
    }

    /**
     * Called when the given IME client (application) was removed (e.g. its process was killed).
     * This will hide the IME, notify it that its client was unbound ({@code unbindInput}), and
     * clear the user data related to this client.
     *
     * <p>Only takes effect if the client is the {@link UserData#mCurClient} of the given user.
     *
     * @param client   the client that was removed.
     * @param userData the data of the user to check for client removal.
     */
    @GuardedBy("ImfLock.class")
    private void onClientRemovedInternalLocked(@NonNull ClientState client,
            @NonNull UserData userData) {
        final int userId = userData.mUserId;
        if (userData.mCurClient == client) {
            final var statsToken = createStatsTokenForFocusedClient(false /* show */,
                    SoftInputShowHideReason.HIDE_REMOVE_CLIENT, userId);
            hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow,
                    true /* updateTargetWindow */, statsToken,
                    SoftInputShowHideReason.HIDE_REMOVE_CLIENT, userId);
            if (userData.mBoundToMethod) {
                userData.mBoundToMethod = false;
                final IInputMethodInvoker curIme = userData.mBindingController.getCurIme();
                if (curIme != null) {
                    // When we unbind input, we are unbinding the client, so we always
                    // unbind ime and a11y together.
                    curIme.unbindInput();
                    AccessibilityManagerInternal.get().unbindInput();
                }
            }
            userData.mBoundToAccessibility = false;
            userData.mCurClient = null;
            if (userData.mImeBindingState.mFocusedWindowClient == client) {
                userData.mImeBindingState = ImeBindingState.newEmptyState();
            }
        }
        userData.mComputerControlInputConnectionMap.remove(client.mSelfReportedDisplayId);
    }

    @VisibleForTesting
    @Nullable
    @GuardedBy("ImfLock.class")
    ClientState getClientStateLocked(@NonNull IInputMethodClient client) {
        return mClientController.getClient(client.asBinder());
    }

    /**
     * Unbinds the current IME client (application) of the given user. This will notify the IME that
     * its client was unbound, and also notify the client that it is no longer bound to the IME.
     *
     * @param reason the reason for unbinding the client.
     * @param userId the ID of the user whose client to unbind.
     */
    @GuardedBy("ImfLock.class")
    void unbindCurrentClientLocked(@UnbindReason int reason, @UserIdInt int userId) {
        final var userData = getUserData(userId);
        if (userData.mCurClient != null) {
            ProtoLog.v(IMMS_DEBUG, "unbindCurrentClientLocked: client=%s",
                    userData.mCurClient.mClient.asBinder());
            final var bindingController = userData.mBindingController;
            if (userData.mBoundToMethod) {
                userData.mBoundToMethod = false;
                final IInputMethodInvoker curIme = bindingController.getCurIme();
                if (curIme != null) {
                    curIme.unbindInput();
                }
            }
            userData.mBoundToAccessibility = false;

            // Since we set active false to current client and set mCurClient to null, let's unbind
            // all accessibility too. That means, when input method get disconnected (including
            // switching ime), we also unbind accessibility
            userData.mCurClient.mClient.setActive(false /* active */, false /* fullscreen */);

            userData.mCurClient.mClient.onUnbindMethod(bindingController.getSequenceNumber(),
                    reason);
            userData.mCurClient.mSessionRequested = false;
            userData.mCurClient.mSessionRequestedForAccessibility = false;
            userData.mCurClient = null;
            ImeTracker.forLogging().onFailed(userData.mCurStatsToken,
                    ImeTracker.PHASE_SERVER_WAIT_IME);
            userData.mCurStatsToken = null;
            mImeSwitcherMenu.hide(bindingController.getCurDisplayId(), userId);
        }
    }

    @GuardedBy("ImfLock.class")
    private boolean isShowRequestedForCurrentWindow(@UserIdInt int userId) {
        final var userData = getUserData(userId);
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        final ImeTargetWindowState targetWindowState = visibilityStateComputer.getWindowStateOrNull(
                userData.mImeBindingState.mFocusedWindow);
        return targetWindowState != null && targetWindowState.isRequestedImeVisible();
    }

    @GuardedBy("ImfLock.class")
    @NonNull
    InputBindResult attachNewInputLocked(@StartInputReason int startInputReason, boolean initial,
            @UserIdInt int userId) {
        final var userData = getUserData(userId);
        final var bindingController = userData.mBindingController;
        if (!userData.mBoundToMethod) {
            bindingController.getCurIme().bindInput(userData.mCurClient.mBinding);
            userData.mBoundToMethod = true;
        }

        final var focusedWindow = userData.mImeBindingState.mFocusedWindow;
        final Binder startInputToken = new Binder();
        mImeTargetWindowMap.put(startInputToken, focusedWindow);
        final boolean restarting = !initial;
        final StartInputInfo info = new StartInputInfo(userId,
                bindingController.getCurToken(), bindingController.getCurDisplayId(),
                bindingController.getCurImeId(), startInputReason,
                restarting, UserHandle.getUserId(userData.mCurClient.mUid),
                userData.mCurClient.mSelfReportedDisplayId,
                focusedWindow, userData.mCurEditorInfo,
                userData.mImeBindingState.mFocusedWindowSoftInputMode,
                bindingController.getSequenceNumber());
        mStartInputHistory.addEntry(info);

        // Seems that PackageManagerInternal#grantImplicitAccess() doesn't handle cross-user
        // implicit visibility (e.g. IME[user=10] -> App[user=0]) thus we do this only for the
        // same-user scenarios.
        // That said ignoring cross-user scenario will never affect IMEs that do not have
        // INTERACT_ACROSS_USERS(_FULL) permissions, which is actually almost always the case.
        if (userId == UserHandle.getUserId(userData.mCurClient.mUid)) {
            mPackageManagerInternal.grantImplicitAccess(userId, null /* intent */,
                    UserHandle.getAppId(bindingController.getCurImeUid()),
                    userData.mCurClient.mUid, true /* direct */);
        }

        @InputMethodNavButtonFlags final int navButtonFlags =
                getInputMethodNavButtonFlagsLocked(userData);
        final SessionState session = userData.mCurClient.mCurSession;
        setEnabledSessionLocked(session, userData);
        session.mIme.startInput(startInputToken, userData.mCurInputConnection,
                userData.mCurEditorInfo, restarting, navButtonFlags,
                userData.mCurImeBackCallbackReceiver);
        // Calculating imeTargetStale can be done as a part of updateImeTargetWindow(), but it's
        // only when optimizeImeInputTargetUpdate is enabled. For now, we perform separate calls.
        final boolean imeTargetStale = Flags.forceHideForStaleWindow()
                && focusedWindow != null
                && mWindowManagerInternal.isImeInputTargetStaleForUpdate(focusedWindow);
        if (Flags.optimizeImeInputTargetUpdate()) {
            if (focusedWindow != null) {
                mWindowManagerInternal.updateImeTargetWindow(focusedWindow);
            }
            userData.mVisibilityStateComputer.setLastImeTargetWindow(focusedWindow);
        }
        if (isShowRequestedForCurrentWindow(userId) && focusedWindow != null) {
            ProtoLog.v(IMMS_DEBUG, "Attach new input asks to show input");
            // Re-use current statsToken, if it exists.
            final var statsToken = userData.mCurStatsToken != null ? userData.mCurStatsToken
                    : createStatsTokenForFocusedClient(true /* show */,
                            SoftInputShowHideReason.ATTACH_NEW_INPUT, userId);
            userData.mCurStatsToken = null;
            // If the screen was turned off and configuration change (device was previously in
            // landscape) happened, the IME was not redrawn. Therefore, we need to dispatch
            // another show request. As the IME is still visible from IMMS's perspective, we
            // need to enforce it, otherwise it would early return.
            final boolean imeBound = userData.mBindingController.getCurIme() != null;
            showCurrentInputInternal(focusedWindow, statsToken, imeBound /* forceShow */);
        } else if (imeTargetStale) {
            // TODO(b/429304155): come back to this and properly address the underlying issue.
            // When attaching to a new input target that doesn't want the IME, we explicitly
            // hide any currently showing IME. This prevents a stale IME surface from a previous
            // target from remaining visible.
            ProtoLog.d(IMMS_WITH_LOGCAT, "Attach new input but force hide");
            // TODO(b/429304155): Use another ShowHideReason for this statsToken.
            final var statsToken = createStatsTokenForFocusedClient(false /* show */,
                    SoftInputShowHideReason.HIDE_SOFT_INPUT, userId);
            hideCurrentInputLocked(focusedWindow, false /* updateTargetWindow */, statsToken,
                    SoftInputShowHideReason.HIDE_SOFT_INPUT, userId);
        }

        final var curImeId = bindingController.getCurImeId();
        final InputMethodInfo curImi = InputMethodSettingsRepository.get(userId).getMethodMap()
                .get(curImeId);
        final boolean suppressesSpellChecker = curImi != null && curImi.suppressesSpellChecker();
        final SparseArray<IAccessibilityInputMethodSession> accessibilityInputMethodSessions =
                createAccessibilityInputMethodSessions(
                        userData.mCurClient.mAccessibilitySessions);
        if (bindingController.getSupportsStylusHandwriting() && hasSupportedStylusLocked()) {
            mHwController.setInkWindowInitializer(new InkWindowInitializer());
        }
        return new InputBindResult(InputBindResult.ResultCode.SUCCESS_WITH_IME_SESSION,
                session.mSession, accessibilityInputMethodSessions,
                (session.mChannel != null ? session.mChannel.dup() : null),
                curImeId, bindingController.getSequenceNumber(), suppressesSpellChecker);
    }

    @GuardedBy("ImfLock.class")
    private void attachNewAccessibilityLocked(@StartInputReason int startInputReason,
            boolean initial, @UserIdInt int userId) {
        final var userData = getUserData(userId);

        if (!userData.mBoundToAccessibility) {
            AccessibilityManagerInternal.get().bindInput();
            userData.mBoundToAccessibility = true;
        }

        // TODO(b/187453053): grantImplicitAccess to accessibility services access? if so, need to
        //  record accessibility services uid.

        // We don't start input when session for a11y is created. We start input when
        // input method start input, a11y manager service is always on.
        if (startInputReason != StartInputReason.SESSION_CREATED_BY_ACCESSIBILITY) {
            setEnabledSessionForAccessibilityLocked(userData.mCurClient.mAccessibilitySessions,
                    userData);
            AccessibilityManagerInternal.get().startInput(
                    userData.mCurRemoteAccessibilityInputConnection,
                    userData.mCurEditorInfo, !initial /* restarting */);
        }
    }

    @NonNull
    private SparseArray<IAccessibilityInputMethodSession> createAccessibilityInputMethodSessions(
            @NonNull SparseArray<AccessibilitySessionState> accessibilitySessions) {
        final var accessibilityInputMethodSessions =
                new SparseArray<IAccessibilityInputMethodSession>();
        for (int i = 0; i < accessibilitySessions.size(); i++) {
            accessibilityInputMethodSessions.append(accessibilitySessions.keyAt(i),
                    accessibilitySessions.valueAt(i).mSession);
        }
        return accessibilityInputMethodSessions;
    }

    /**
     * Called by {@link #startInputOrWindowGainedFocusInternalLocked} to bind/unbind/attach the
     * selected InputMethod to the given focused IME client.
     *
     * <p>Note that this should be called after validating if the IME client has IME focus.
     *
     * @see WindowManagerInternal#hasInputMethodClientFocus(IBinder, int, int, int)
     */
    @GuardedBy("ImfLock.class")
    @NonNull
    private InputBindResult startInputUncheckedLocked(@NonNull ClientState cs,
            @Nullable IRemoteInputConnection inputConnection,
            @Nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            @NonNull EditorInfo editorInfo, @StartInputFlags int startInputFlags,
            @StartInputReason int startInputReason,
            int unverifiedTargetSdkVersion,
            @NonNull ResultReceiver imeBackCallbackReceiver,
            @NonNull InputMethodBindingController bindingController) {

        final int userId = bindingController.getUserId();
        final var userData = getUserData(userId);
        final var visibilityStateComputer = userData.mVisibilityStateComputer;

        // Compute the final shown display ID with validated cs.selfReportedDisplayId for this
        // session & other conditions.
        final int imeDisplayId =
                visibilityStateComputer.computeImeDisplayId(cs.mSelfReportedDisplayId);
        bindingController.setSelectedDisplayId(imeDisplayId);

        // Potentially override the selected input method if the new display belongs to a virtual
        // device with a custom IME.
        String selectedImeId = bindingController.getSelectedImeId();
        final String deviceImeId = computeCurrentDeviceImeIdLocked(selectedImeId, userId);
        if (deviceImeId == null) {
            visibilityStateComputer.getImePolicy().setImeHiddenByDisplayPolicy(true);
        } else if (!Objects.equals(deviceImeId, selectedImeId)) {
            setInputMethodLocked(deviceImeId, NOT_A_SUBTYPE_INDEX, bindingController.getDeviceId(),
                    userId);
            selectedImeId = deviceImeId;
        }

        if (visibilityStateComputer.getImePolicy().isImeHiddenByDisplayPolicy()) {
            final var statsToken = createStatsTokenForFocusedClient(false /* show */,
                    SoftInputShowHideReason.HIDE_DISPLAY_IME_POLICY_HIDE, userId);
            hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow,
                    true /* updateTargetWindow */, statsToken,
                    SoftInputShowHideReason.HIDE_DISPLAY_IME_POLICY_HIDE, userId);
            return InputBindResult.NO_IME;
        }

        // If no method is currently selected, do nothing.
        if (selectedImeId == null) {
            return InputBindResult.NO_IME;
        }

        // If configured, we want to avoid starting up the IME if it is not supposed to be showing
        if (shouldPreventImeStartupLocked(selectedImeId, startInputFlags,
                unverifiedTargetSdkVersion, userId, editorInfo)) {
            ProtoLog.v(IMMS_DEBUG, "Avoiding IME startup and unbinding current input method.");
            bindingController.unbindIme();
            unbindCurrentClientLocked(UnbindReason.DISCONNECT_IME, userId);
            return InputBindResult.NO_EDITOR;
        }

        if (userData.mCurClient != cs) {
            prepareClientSwitchLocked(cs, userId);
        }

        final boolean connectionWasActive = userData.mCurInputConnection != null;

        // Bump up the sequence for this client and attach it.
        bindingController.advanceSequenceNumber();

        userData.mCurClient = cs;
        userData.mCurInputConnection = inputConnection;
        userData.mCurRemoteAccessibilityInputConnection = remoteAccessibilityInputConnection;
        userData.mCurImeBackCallbackReceiver = imeBackCallbackReceiver;
        // Override the locale hints if the app is running on a virtual device.
        if (mVdmInternal == null) {
            mVdmInternal = LocalServices.getService(VirtualDeviceManagerInternal.class);
        }
        if (mVdmInternal != null && editorInfo.hintLocales == null) {
            LocaleList hintsFromVirtualDevice = mVdmInternal.getPreferredLocaleListForUid(cs.mUid);
            if (hintsFromVirtualDevice != null) {
                editorInfo.hintLocales = hintsFromVirtualDevice;
            }
        }
        userData.mCurEditorInfo = editorInfo;

        // Notify input manager if the connection state changes.
        final boolean connectionIsActive = userData.mCurInputConnection != null;
        if (connectionIsActive != connectionWasActive) {
            mInputManagerInternal.notifyInputMethodConnectionActive(connectionIsActive);
        }

        // Check if the input method is changing.
        // We expect the caller has already verified that the client is allowed to access this
        // display ID.
        final String curImeId = bindingController.getCurImeId();
        final int curDisplayId = bindingController.getCurDisplayId();
        if (curImeId != null && curImeId.equals(bindingController.getSelectedImeId())
                && curDisplayId == bindingController.getSelectedDisplayId()) {
            if (cs.mCurSession != null) {
                // Fast case: if we are already connected to the input method,
                // then just return it.
                // This doesn't mean a11y sessions are there. When a11y service is
                // enabled while this client is switched out, this client doesn't have the session.
                // A11yManagerService will only request missing sessions (will not request existing
                // sessions again). Note when an a11y service is disabled, it will clear its
                // session from all clients, so we don't need to worry about disabled a11y services.
                cs.mSessionRequestedForAccessibility = false;
                requestClientSessionForAccessibilityLocked(cs);
                // we can always attach to accessibility because AccessibilityManagerService is
                // always on.
                attachNewAccessibilityLocked(startInputReason,
                        (startInputFlags & StartInputFlags.INITIAL_CONNECTION) != 0, userId);
                return attachNewInputLocked(startInputReason,
                        (startInputFlags & StartInputFlags.INITIAL_CONNECTION) != 0, userId);
            }

            final var bindResult = bindingController.tryReuseConnection();
            if (bindResult != null) {
                return bindResult;
            }
        }

        bindingController.unbindIme();
        return bindingController.bindIme();
    }

    /**
     * Update the current deviceId and return the relevant imeId for this device.
     *
     * <p>1. If the device changes to virtual and its custom IME is not available, then disable
     * IME.</p>
     * <p>2. If the device changes to virtual with valid custom IME, then return the custom IME. If
     * the old device was default, then store the current imeId so it can be restored.</p>
     * <p>3. If the device changes to default, restore the default device IME.</p>
     * <p>4. Otherwise keep the current imeId.</p>
     */
    @Nullable
    @GuardedBy("ImfLock.class")
    private String computeCurrentDeviceImeIdLocked(@Nullable String selectedImeId,
            @UserIdInt int userId) {
        if (mVdmInternal == null) {
            mVdmInternal = LocalServices.getService(VirtualDeviceManagerInternal.class);
        }
        if (mVdmInternal == null) {
            return selectedImeId;
        }

        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final var bindingController = getInputMethodBindingController(userId);
        final int oldDeviceId = bindingController.getDeviceId();
        final int selectedDisplayId = bindingController.getSelectedDisplayId();
        int newDeviceId = mVdmInternal.getDeviceIdForDisplayId(selectedDisplayId);
        if (newDeviceId != DEVICE_ID_DEFAULT) {
            // Only show custom IME on trusted displays.
            if (mDisplayManagerInternal == null) {
                mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
            }
            int displayFlags = mDisplayManagerInternal.getDisplayInfo(selectedDisplayId).flags;
            if ((displayFlags & Display.FLAG_TRUSTED) != Display.FLAG_TRUSTED) {
                // If the display is not trusted, fallback to the default device IME.
                newDeviceId = DEVICE_ID_DEFAULT;
            }
        }
        bindingController.setDeviceId(newDeviceId);
        if (newDeviceId == DEVICE_ID_DEFAULT) {
            if (oldDeviceId == DEVICE_ID_DEFAULT) {
                return selectedImeId;
            }
            final String defaultDeviceImeId = settings.getSelectedDefaultDeviceInputMethod();
            ProtoLog.v(IMMS_DEBUG, "Restoring default device input method: %s", defaultDeviceImeId);
            settings.putSelectedDefaultDeviceInputMethod(null);
            return defaultDeviceImeId;
        }

        final String deviceImeId = mVirtualDeviceMethodMap.get(newDeviceId, selectedImeId);
        if (Objects.equals(deviceImeId, selectedImeId)) {
            return selectedImeId;
        } else if (!settings.getMethodMap().containsKey(deviceImeId)) {
            ProtoLog.v(IMMS_DEBUG, "Disabling IME on virtual device with id %s because its custom"
                            + " input method is not available: %s", newDeviceId, deviceImeId);
            return null;
        }

        if (oldDeviceId == DEVICE_ID_DEFAULT) {
            ProtoLog.v(IMMS_DEBUG, "Storing default device input method %s", selectedImeId);
            settings.putSelectedDefaultDeviceInputMethod(selectedImeId);
        }
        ProtoLog.v(IMMS_DEBUG, "Switching current input method from %s to device-specific one %s"
                        + " because the current display %s belongs to device with id %s",
                selectedImeId, deviceImeId, selectedDisplayId, newDeviceId);
        return deviceImeId;
    }

    @GuardedBy("ImfLock.class")
    private boolean shouldPreventImeStartupLocked(
            @NonNull String selectedImeId,
            @StartInputFlags int startInputFlags,
            int unverifiedTargetSdkVersion,
            @UserIdInt int userId,
            @NonNull EditorInfo editorInfo) {
        // Fast-path for the majority of cases
        if (!mPreventImeStartupUnlessTextEditor) {
            return false;
        }
        if (isShowRequestedForCurrentWindow(userId)) {
            return false;
        }
        if (isSoftInputModeStateVisibleAllowed(unverifiedTargetSdkVersion, startInputFlags)) {
            return false;
        }
        if (Flags.preventImeStartupBypassedApps() && ArrayUtils.contains(
                mPreventImeStartupBypassedApps, editorInfo.packageName)) {
            return false;
        }
        final InputMethodInfo selectedImi = InputMethodSettingsRepository.get(userId).getMethodMap()
                .get(selectedImeId);
        if (selectedImi == null) {
            return false;
        }
        return !ArrayUtils.contains(mNonPreemptibleInputMethods, selectedImi.getPackageName());
    }

    @Override
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    public void setPreventImeStartupBypassedAppsForTest(@Nullable List<String> allowedPackages) {
        if (allowedPackages == null) {
            mPreventImeStartupBypassedApps = mContext.getResources().getStringArray(
                    com.android.internal.R.array.config_preventImeStartupBypassedApps);
        } else {
            mPreventImeStartupBypassedApps = allowedPackages.toArray(new String[0]);
        }
    }

    @GuardedBy("ImfLock.class")
    private void prepareClientSwitchLocked(ClientState cs, @UserIdInt int userId) {
        // If the client is changing, we need to switch over to the new
        // one.
        unbindCurrentClientLocked(UnbindReason.SWITCH_CLIENT, userId);
        // If the screen is on, inform the new client it is active
        if (mIsInteractive) {
            cs.mClient.setActive(true /* active */, false /* fullscreen */);
        }
    }

    @FunctionalInterface
    interface ImeDisplayValidator {
        @DisplayImePolicy
        int getDisplayImePolicy(int displayId);
    }

    /**
     * Find the display where the IME should be shown.
     *
     * @param displayId the ID of the display where the IME client target is
     * @param checker   instance of {@link ImeDisplayValidator} which is used for
     *                  checking display config to adjust the final target display
     * @return the ID of the display where the IME should be shown or
     * {@link android.view.Display#INVALID_DISPLAY} if the display has an ImePolicy of
     * {@link WindowManager#DISPLAY_IME_POLICY_HIDE}
     */
    static int computeImeDisplayIdForTarget(int displayId, @NonNull ImeDisplayValidator checker) {
        return computeImeDisplayIdForTargetInner(displayId, checker, FALLBACK_DISPLAY_ID);
    }

    /**
     * Find the display where the IME should be shown for a visible background user.
     *
     * @param displayId the ID of the display where the IME client target is
     * @param userId the ID of the user who own the IME
     * @param checker   instance of {@link ImeDisplayValidator} which is used for
     *                  checking display config to adjust the final target display
     * @return the ID of the display where the IME should be shown or
     * {@link android.view.Display#INVALID_DISPLAY} if the display has an ImePolicy of
     * {@link WindowManager#DISPLAY_IME_POLICY_HIDE}
     */
    int computeImeDisplayIdForVisibleBackgroundUserOnAutomotive(
            int displayId, @UserIdInt int userId, @NonNull ImeDisplayValidator checker) {
        // Visible background user can be assigned to a secondary display, not the default display.
        // The main display assigned to the user will be used as the fallback display.
        final int mainDisplayId = mUserManagerInternal.getMainDisplayAssignedToUser(userId);
        return computeImeDisplayIdForTargetInner(displayId, checker, mainDisplayId);
    }

    private static int computeImeDisplayIdForTargetInner(
            int displayId, @NonNull ImeDisplayValidator checker, int fallbackDisplayId) {
        if (displayId == fallbackDisplayId || displayId == INVALID_DISPLAY) {
            return fallbackDisplayId;
        }

        // Show IME window on fallback display when the display doesn't support system decorations
        // or the display is virtual and isn't owned by system for security concern.
        final int result = checker.getDisplayImePolicy(displayId);
        if (result == DISPLAY_IME_POLICY_LOCAL) {
            return displayId;
        } else if (result == DISPLAY_IME_POLICY_HIDE) {
            return INVALID_DISPLAY;
        }
        return fallbackDisplayId;
    }

    /**
     * Initializes the given IME, sending the token, privileged operations and navigation button
     * flags.
     *
     * @param ime    the interface used to make calls on the IME to initialize.
     * @param token  the token used to uniquely identify the IME and the WindowToken created for it.
     * @param userId the ID of the user whose IME to initialize.
     */
    @GuardedBy("ImfLock.class")
    void initializeImeLocked(@NonNull IInputMethodInvoker ime, @NonNull IBinder token,
            @UserIdInt int userId) {
        final var userData = getUserData(userId);
        ime.initializeInternal(token,
                new InputMethodPrivilegedOperationsImpl(this, token, userData),
                getInputMethodNavButtonFlagsLocked(userData));
    }

    @AnyThread
    void scheduleResetStylusHandwriting() {
        mHandler.obtainMessage(MSG_RESET_HANDWRITING).sendToTarget();
    }

    @AnyThread
    void schedulePrepareStylusHandwritingDelegation(@UserIdInt int userId,
            @NonNull String delegatePackageName, @NonNull String delegatorPackageName) {
        mHandler.obtainMessage(
                MSG_PREPARE_HANDWRITING_DELEGATION, userId, 0 /* unused */,
                new Pair<>(delegatePackageName, delegatorPackageName)).sendToTarget();
    }

    @AnyThread
    void scheduleRemoveStylusHandwritingWindow() {
        mHandler.obtainMessage(MSG_REMOVE_HANDWRITING_WINDOW).sendToTarget();
    }

    @AnyThread
    private void scheduleNotifyImeUidToAudioService(int uid) {
        mHandler.removeMessages(MSG_NOTIFY_IME_UID_TO_AUDIO_SERVICE);
        mHandler.obtainMessage(MSG_NOTIFY_IME_UID_TO_AUDIO_SERVICE, uid, 0 /* unused */)
                .sendToTarget();
    }

    /**
     * Called when an IME session is created.
     *
     * @param ime     the IME that created the IME session.
     * @param session the created IME session.
     * @param channel the input channel.
     * @param userId  the ID of the user for which the IME session was created.
     */
    @BinderThread
    void onSessionCreated(IInputMethodInvoker ime, @Nullable IInputMethodSession session,
            @NonNull InputChannel channel, @UserIdInt int userId) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.onSessionCreated");
        try {
            synchronized (ImfLock.class) {
                if (mUserSwitchHandlerTask != null) {
                    // We have a pending user-switching task so it's better to just ignore this
                    // session.
                    channel.dispose();
                    return;
                }
                final var userData = getUserData(userId);
                final IInputMethodInvoker curIme = userData.mBindingController.getCurIme();
                if (curIme != null && ime != null && curIme.asBinder() == ime.asBinder()) {
                    if (userData.mCurClient != null) {
                        clearClientSessionLocked(userData.mCurClient);
                        userData.mCurClient.mCurSession = new SessionState(userData.mCurClient, ime,
                                session, channel, userId);
                        InputBindResult res = attachNewInputLocked(
                                StartInputReason.SESSION_CREATED_BY_IME, true, userId);
                        attachNewAccessibilityLocked(StartInputReason.SESSION_CREATED_BY_IME, true,
                                userId);
                        if (res.method != null) {
                            userData.mCurClient.mClient.onBindMethod(res);
                        }
                        return;
                    }
                }
            }

            // Session abandoned.  Close its associated input channel.
            channel.dispose();
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    /**
     * Resets the IME and IME client of the given user. This sets no IME as selected, and unbinds
     * the current IME and the current IME client of the user.
     *
     * @param reason the reason for unbinding the client.
     * @param userId the ID of the user whose IME and IME client to reset.
     */
    @GuardedBy("ImfLock.class")
    void resetCurrentMethodAndClientLocked(@UnbindReason int reason, @UserIdInt int userId) {
        final var bindingController = getInputMethodBindingController(userId);
        bindingController.setSelectedImeId(null /* imeId */);

        // Callback before clean-up binding states.
        bindingController.unbindIme();
        unbindCurrentClientLocked(reason, userId);
    }

    /**
     * Requests an IME session for the given IME client. This will send a call to the IME process,
     * with a callback to be invoked when the session is created.
     *
     * @param cs     the IME client to request an IME session for.
     * @param userId the ID of the user to request an IME session for.
     */
    @GuardedBy("ImfLock.class")
    void requestClientSessionLocked(@NonNull ClientState cs, @UserIdInt int userId) {
        if (!cs.mSessionRequested) {
            ProtoLog.v(IMMS_DEBUG, "Creating new session for client %s", cs);
            final InputChannel[] channels = InputChannel.openInputChannelPair(cs.toString());
            final InputChannel serverChannel = channels[0];
            final InputChannel clientChannel = channels[1];

            cs.mSessionRequested = true;

            final var bindingController = getInputMethodBindingController(userId);
            final IInputMethodInvoker curIme = bindingController.getCurIme();
            final var callback = new IInputMethodSessionCallback.Stub() {
                @Override
                public void sessionCreated(@Nullable IInputMethodSession session) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        onSessionCreated(curIme, session, serverChannel, userId);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            };

            curIme.createSession(clientChannel, callback);
        }
    }

    /**
     * Requests an IME session for all accessibility services of the given IME client, if they don't
     * already have a session.
     *
     * @param cs the IME client whose accessibility services to request IME sessions for.
     */
    @GuardedBy("ImfLock.class")
    void requestClientSessionForAccessibilityLocked(@NonNull ClientState cs) {
        if (!cs.mSessionRequestedForAccessibility) {
            ProtoLog.v(IMMS_DEBUG, "Creating new accessibility sessions for client %s", cs);
            cs.mSessionRequestedForAccessibility = true;
            ArraySet<Integer> ignoreSet = new ArraySet<>();
            for (int i = 0; i < cs.mAccessibilitySessions.size(); i++) {
                ignoreSet.add(cs.mAccessibilitySessions.keyAt(i));
            }
            AccessibilityManagerInternal.get().createImeSession(ignoreSet);
        }
    }

    /**
     * Finishes and clears the current IME session of the given IME client.
     *
     * @param cs the client whose current IME session to finish and clear.
     */
    @GuardedBy("ImfLock.class")
    void clearClientSessionLocked(@NonNull ClientState cs) {
        finishSessionLocked(cs.mCurSession);
        cs.mCurSession = null;
        cs.mSessionRequested = false;
    }

    /**
     * Finishes and clears all the accessibility IME sessions of the given IME client.
     *
     * @param cs the client whose accessibility IME sessions to finish and clear.
     */
    @GuardedBy("ImfLock.class")
    void clearClientSessionForAccessibilityLocked(@NonNull ClientState cs) {
        for (int i = 0; i < cs.mAccessibilitySessions.size(); i++) {
            finishSessionForAccessibilityLocked(cs.mAccessibilitySessions.valueAt(i));
        }
        cs.mAccessibilitySessions.clear();
        cs.mSessionRequestedForAccessibility = false;
    }

    /**
     * Finishes and clears the accessibility session with the given ID of the given IME client.
     *
     * @param cs the client whose accessibility session to finish and clear.
     * @param id the ID of the accessibility session to finish and clear.
     */
    @GuardedBy("ImfLock.class")
    void clearClientSessionForAccessibilityLocked(@NonNull ClientState cs, int id) {
        AccessibilitySessionState session = cs.mAccessibilitySessions.get(id);
        if (session != null) {
            finishSessionForAccessibilityLocked(session);
            cs.mAccessibilitySessions.remove(id);
        }
    }

    /**
     * Finishes the IME session and input channel of the given session state.
     *
     * @param sessionState the session state whose IME session and input channel to finish.
     */
    @GuardedBy("ImfLock.class")
    private void finishSessionLocked(@Nullable SessionState sessionState) {
        if (sessionState != null) {
            if (sessionState.mSession != null) {
                try {
                    sessionState.mSession.finishSession();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Session failed to close due to remote exception", e);
                    final int userId = sessionState.mUserId;
                    final var bindingController = getInputMethodBindingController(userId);
                    updateSystemUiLocked(0 /* vis */, bindingController.getBackDisposition(),
                            userId);
                }
                sessionState.mSession = null;
            }
            if (sessionState.mChannel != null) {
                sessionState.mChannel.dispose();
                sessionState.mChannel = null;
            }
        }
    }

    /**
     * Finishes the IME session of the given accessibility session state.
     *
     * @param sessionState the accessibility session state whose IME session to finish.
     */
    @GuardedBy("ImfLock.class")
    private void finishSessionForAccessibilityLocked(
            @Nullable AccessibilitySessionState sessionState) {
        if (sessionState != null) {
            if (sessionState.mSession != null) {
                try {
                    sessionState.mSession.finishSession();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Session failed to close due to remote exception", e);
                }
                sessionState.mSession = null;
            }
        }
    }

    /**
     * Called when the IME of the given user has connected.
     *
     * <p>This will first finish and clear any existing IME sessions on the current IME client of
     * the given user. Then it will re-request IME sessions on the client, for the newly connected
     * IME.
     *
     * @param imeId  the ID of the IME that has connected.
     * @param imeUid the UID of the IME that has connected.
     * @param userId the ID of the user whose IME has connected.
     */
    @GuardedBy("ImfLock.class")
    void onImeConnected(@NonNull String imeId, int imeUid, @UserIdInt int userId) {
        final var userData = getUserData(userId);
        if (userData.mCurClient != null) {
            clearClientSessionLocked(userData.mCurClient);
            clearClientSessionForAccessibilityLocked(userData.mCurClient);
            requestClientSessionLocked(userData.mCurClient, userId);
            requestClientSessionForAccessibilityLocked(userData.mCurClient);
        }

        scheduleNotifyImeUidToAudioService(imeUid);
        final var bindingController = getInputMethodBindingController(userId);
        final InputMethodInfo imi = InputMethodSettingsRepository.get(userId).getMethodMap()
                .get(imeId);
        if (imi != null && bindingController.getSupportsStylusHandwriting()
                != imi.supportsStylusHandwriting()) {
            bindingController.setSupportsStylusHandwriting(imi.supportsStylusHandwriting());
            InputMethodManager.invalidateLocalStylusHandwritingAvailabilityCaches();
        }
        if (imi != null && bindingController.getSupportsConnectionlessStylusHandwriting()
                != imi.supportsConnectionlessStylusHandwriting()) {
            bindingController.setSupportsConnectionlessStylusHandwriting(
                    imi.supportsConnectionlessStylusHandwriting());
            InputMethodManager.invalidateLocalConnectionlessStylusHandwritingAvailabilityCaches();
        }
        // Reset Handwriting event receiver. Always call this as it handles changes in the newly
        // connected IME supporting Stylus Handwriting. If unchanged, this is a no-op.
        scheduleResetStylusHandwriting();
    }

    /**
     * Called when the IME of the given user has disconnected, either due to a service
     * disconnection, or due to an explicit IME unbind.
     *
     * <p>This will first finish and clear the current IME session and all the accessibility IME
     * sessions for all the IME clients. Then it will finish and clear the enabled IME session and
     * all the enabled accessibility IME sessions for the given user. Lastly, it will notify the
     * system the IME is no longer visible (even if it is currently hidden) to reset all the state.
     *
     * @param userId the ID of the user whose IME has disconnected.
     */
    @GuardedBy("ImfLock.class")
    void onImeDisconnected(@UserIdInt int userId) {
        // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
        @SuppressWarnings("GuardedBy") Consumer<ClientState> clearClientSession = c -> {
            // TODO(b/305849394): Figure out what we should do for single user IME mode.
            final boolean shouldClearClientSession =
                    !mConcurrentMultiUserModeEnabled || UserHandle.getUserId(c.mUid) == userId;
            if (shouldClearClientSession) {
                clearClientSessionLocked(c);
                clearClientSessionForAccessibilityLocked(c);
            }
        };
        mClientController.forAllClients(clearClientSession);

        final var userData = getUserData(userId);
        finishSessionLocked(userData.mEnabledSession);
        userData.mEnabledSession = null;
        for (int i = 0; i < userData.mEnabledAccessibilitySessions.size(); i++) {
            finishSessionForAccessibilityLocked(userData.mEnabledAccessibilitySessions.valueAt(i));
        }
        userData.mEnabledAccessibilitySessions.clear();

        scheduleNotifyImeUidToAudioService(Process.INVALID_UID);
        hideStatusBarIconLocked(userId);
        userData.mInFullscreenMode = false;
        userData.mVisibilityStateComputer.setInputShown(false);
        // Reset IME window status when unbinding.
        userData.mBindingController.setImeWindowVis(0 /* vis */);
        userData.mBindingController.setBackDisposition(InputMethodService.BACK_DISPOSITION_DEFAULT);
        updateSystemUiLocked(userId);
        mWindowManagerInternal.setDismissImeOnBackKeyPressed(false);
        scheduleResetStylusHandwriting();
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void updateStatusIconLocked(String packageName, @DrawableRes int iconId,
            @NonNull UserData userData) {
        final int userId = userData.mUserId;
        // To minimize app compat risk, ignore background users' request for single-user mode.
        // TODO(b/357178609): generalize the logic and remove this special rule.
        if (!mConcurrentMultiUserModeEnabled && userId != mCurrentImeUserId) {
            return;
        }
        if (iconId == 0) {
            ProtoLog.v(IMMS_DEBUG, "hide the small icon for the input method");
            hideStatusBarIconLocked(userId);
        } else if (packageName != null) {
            ProtoLog.v(IMMS_DEBUG, "show a small icon for the input method");
            final PackageManager userAwarePackageManager =
                    getPackageManagerForUser(mContext, userId);
            ApplicationInfo applicationInfo = null;
            try {
                applicationInfo = userAwarePackageManager.getApplicationInfo(packageName,
                        PackageManager.ApplicationInfoFlags.of(0));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            final CharSequence contentDescription = applicationInfo != null
                    ? userAwarePackageManager.getApplicationLabel(applicationInfo)
                    : null;
            if (mStatusBarManagerInternal != null) {
                mStatusBarManagerInternal.setIcon(mSlotIme, packageName, iconId, 0,
                        contentDescription != null
                                ? contentDescription.toString() : null);
                mStatusBarManagerInternal.setIconVisibility(mSlotIme, true);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void hideStatusBarIconLocked(@UserIdInt int userId) {
        // To minimize app compat risk, ignore background users' request for single-user mode.
        // TODO(b/357178609): generalize the logic and remove this special rule.
        if (!mConcurrentMultiUserModeEnabled && userId != mCurrentImeUserId) {
            return;
        }
        if (mStatusBarManagerInternal != null) {
            mStatusBarManagerInternal.setIconVisibility(mSlotIme, false);
        }
    }

    @GuardedBy("ImfLock.class")
    @InputMethodNavButtonFlags
    private int getInputMethodNavButtonFlagsLocked(@NonNull UserData userData) {
        final int userId = userData.mUserId;
        // Whether the current display has a navigation bar. When this is false (e.g. emulator),
        // the IME should not draw the IME navigation bar.
        final int curDisplayId = userData.mBindingController.getCurDisplayId();
        final boolean hasNavigationBar = mWindowManagerInternal
                .hasNavigationBar(curDisplayId != INVALID_DISPLAY ? curDisplayId : DEFAULT_DISPLAY);
        final boolean imeDrawsImeNavBar = userData.mImeDrawsNavBar.get() && hasNavigationBar
                && !shouldHideImeSpace(userId);
        final boolean showImeSwitcherButton = shouldShowImeSwitcherButtonLocked(userId);
        final boolean isImeSwitcherButtonInNavbarEnabled =
                !Flags.imeSwitcherButtonInNavbarSetting() || InputMethodSettingsRepository.get(
                        userId).isImeSwitcherButtonInNavBarEnabled();
        return (imeDrawsImeNavBar ? InputMethodNavButtonFlags.IME_DRAWS_IME_NAV_BAR : 0)
                | (showImeSwitcherButton ? InputMethodNavButtonFlags.SHOW_IME_SWITCHER_BUTTON : 0)
                | (isImeSwitcherButtonInNavbarEnabled
                ? InputMethodNavButtonFlags.IME_SWITCHER_BUTTON_ENABLED
                : 0);
    }

    /**
     * Whether the IME navigation bar space should be dropped, which is only allowed in gestural
     * navigation mode with the navigation hint disabled.
     */
    private boolean shouldHideImeSpace(@UserIdInt int userId) {
        final ContentResolver resolver = mContext.getContentResolver();
        // Profiles inherit the navigation bar from their parent, see onUpdateResourceOverlay().
        final int parentId = mUserManagerInternal.getProfileParentId(userId);
        return Settings.Secure.getIntForUser(resolver, Settings.Secure.NAVIGATION_MODE,
                        -1, parentId) == NAV_BAR_MODE_GESTURAL
                && LineageSettings.System.getIntForUser(resolver,
                        LineageSettings.System.NAVIGATION_BAR_HINT, 1, parentId) == 0
                && LineageSettings.System.getIntForUser(resolver,
                        LineageSettings.System.NAVIGATION_BAR_IME_SPACE, 1, parentId) == 0;
    }

    /**
     * Whether the IME Switcher Button should be shown when the IME is shown, for the given user.
     * Note, the caller is responsible for checking the provided IME visibility.
     *
     * @param userId the ID of the user to check.
     */
    @GuardedBy("ImfLock.class")
    private boolean shouldShowImeSwitcherButtonLocked(@UserIdInt int userId) {
        // When the IME Switcher Menu is shown, the IME Switcher button should be hidden.
        final var userData = getUserData(userId);
        if (mImeSwitcherMenu.isShowing(userData)) {
            return false;
        }
        // When we are switching IMEs, the IME Switcher button should be hidden.
        final var bindingController = userData.mBindingController;
        if (!Objects.equals(bindingController.getCurImeId(),
                bindingController.getSelectedImeId())) {
            return false;
        }
        if (mWindowManagerInternal.isKeyguardShowingAndNotOccluded()
                && mWindowManagerInternal.isKeyguardSecure(userId)) {
            return false;
        }

        return hasMultipleSubtypesForSwitcher(false /* nonAuxOnly */, userId);
    }

    /**
     * Checks whether there at least two subtypes that should be shown for the IME switcher menu,
     * across all enabled IMEs for the given user.
     *
     * @param nonAuxOnly whether to check only for non auxiliary subtypes.
     * @param userId     the id of the user for which to check the number of subtypes.
     */
    private static boolean hasMultipleSubtypesForSwitcher(boolean nonAuxOnly,
            @UserIdInt int userId) {
        final var settings = InputMethodSettingsRepository.get(userId);
        List<InputMethodInfo> imes = settings.getEnabledInputMethodListWithFilter(
                InputMethodInfo::shouldShowInInputMethodPicker);
        final int numImes = imes.size();
        if (numImes > 2) return true;
        if (numImes < 1) return false;
        int nonAuxCount = 0;
        int auxCount = 0;
        InputMethodSubtype nonAuxSubtype = null;
        InputMethodSubtype auxSubtype = null;
        for (int i = 0; i < numImes; ++i) {
            final InputMethodInfo imi = imes.get(i);
            final List<InputMethodSubtype> subtypes =
                    settings.getEnabledInputMethodSubtypeList(imi, true);
            final int subtypeCount = subtypes.size();
            if (subtypeCount == 0) {
                ++nonAuxCount;
            } else {
                for (int j = 0; j < subtypeCount; ++j) {
                    final InputMethodSubtype subtype = subtypes.get(j);
                    if (!subtype.isAuxiliary()) {
                        ++nonAuxCount;
                        nonAuxSubtype = subtype;
                    } else {
                        ++auxCount;
                        auxSubtype = subtype;
                    }
                }
            }
        }
        if (nonAuxOnly) {
            return nonAuxCount > 1;
        } else if (nonAuxCount > 1 || auxCount > 1) {
            return true;
        } else if (nonAuxCount == 1 && auxCount == 1) {
            if (nonAuxSubtype != null && auxSubtype != null
                    && (nonAuxSubtype.getLocale().equals(auxSubtype.getLocale())
                    || auxSubtype.overridesImplicitlyEnabledSubtype()
                    || nonAuxSubtype.overridesImplicitlyEnabledSubtype())
                    && nonAuxSubtype.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER)) {
                return false;
            }
            return true;
        }
        return false;
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    @SuppressWarnings("deprecation")
    private void setImeWindowStatusLocked(@ImeWindowVisibility int vis,
            @BackDispositionMode int backDisposition, @NonNull UserData userData) {
        final int topFocusedDisplayId = mWindowManagerInternal.getTopFocusedDisplayId();

        // Skip update IME status when current token display is not same as focused display.
        // Note that we still need to update IME status when focusing external display
        // that does not support system decoration and fallback to show IME on default
        // display since it is intentional behavior.
        final var bindingController = userData.mBindingController;
        final int curDisplayId = bindingController.getCurDisplayId();
        if (curDisplayId != topFocusedDisplayId && curDisplayId != FALLBACK_DISPLAY_ID) {
            return;
        }
        bindingController.setImeWindowVis(vis);
        bindingController.setBackDisposition(backDisposition);
        updateSystemUiLocked(vis, backDisposition, userData.mUserId);

        final boolean dismissImeOnBackKeyPressed;
        switch (backDisposition) {
            case InputMethodService.BACK_DISPOSITION_WILL_DISMISS:
                dismissImeOnBackKeyPressed = true;
                break;
            case InputMethodService.BACK_DISPOSITION_WILL_NOT_DISMISS:
                dismissImeOnBackKeyPressed = false;
                break;
            case InputMethodService.BACK_DISPOSITION_DEFAULT:
            default:
                dismissImeOnBackKeyPressed = ((vis & InputMethodService.IME_VISIBLE) != 0);
                break;
        }
        mWindowManagerInternal.setDismissImeOnBackKeyPressed(dismissImeOnBackKeyPressed);
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void reportStartInputLocked(IBinder startInputToken, @NonNull UserData userData) {
        if (Flags.optimizeImeInputTargetUpdate()) {
            return;
        }
        final IBinder targetWindowToken = mImeTargetWindowMap.get(startInputToken);
        if (targetWindowToken != null) {
            mWindowManagerInternal.updateImeTargetWindow(targetWindowToken);
        }
        userData.mVisibilityStateComputer.setLastImeTargetWindow(targetWindowToken);
    }

    @GuardedBy("ImfLock.class")
    private void updateImeWindowStatusLocked(boolean disableImeIcon, int displayId) {
        final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
        if (disableImeIcon) {
            final var bindingController = getInputMethodBindingController(userId);
            updateSystemUiLocked(0 /* vis */, bindingController.getBackDisposition(), userId);
        } else {
            updateSystemUiLocked(userId);
        }
    }

    // Caution! This method is called in this class. Handle multi-user carefully
    @GuardedBy("ImfLock.class")
    private void updateSystemUiLocked(@UserIdInt int userId) {
        final var bindingController = getInputMethodBindingController(userId);
        updateSystemUiLocked(bindingController.getImeWindowVis(),
                bindingController.getBackDisposition(), userId);
    }

    @GuardedBy("ImfLock.class")
    private void updateSystemUiLocked(@ImeWindowVisibility int vis,
            @BackDispositionMode int backDisposition, @UserIdInt int userId) {
        // To minimize app compat risk, ignore background users' request for single-user mode.
        // TODO(b/357178609): generalize the logic and remove this special rule.
        if (!mConcurrentMultiUserModeEnabled && userId != mCurrentImeUserId) {
            return;
        }
        final var userData = getUserData(userId);
        final var bindingController = userData.mBindingController;
        if (bindingController.getCurToken() == null) {
            return;
        }
        final int curDisplayId = bindingController.getCurDisplayId();
        ProtoLog.v(IMMS_DEBUG, "IME window vis: %d active: %d visible: %d displayId: %d", vis,
                (vis & InputMethodService.IME_ACTIVE), (vis & InputMethodService.IME_VISIBLE),
                curDisplayId);
        final IBinder focusedWindowToken = userData.mImeBindingState.mFocusedWindow;
        final Boolean windowPerceptible = focusedWindowToken != null
                ? mFocusedWindowPerceptible.get(focusedWindowToken) : null;

        // TODO: Move this clearing calling identity block to setImeWindowStatusLocked after making
        //  sure all updateSystemUi happens on system privilege.
        final long ident = Binder.clearCallingIdentity();
        try {
            if (windowPerceptible != null && !windowPerceptible) {
                vis &= ~InputMethodService.IME_VISIBLE;
            }
            if (mImeSwitcherMenu.isShowing(userData)
                    || !Objects.equals(bindingController.getCurImeId(),
                        bindingController.getSelectedImeId())) {
                // When the IME Switcher Menu is shown, or we are switching IMEs,
                // the back button should be in the default state (as if the IME is not shown).
                backDisposition = InputMethodService.BACK_DISPOSITION_ADJUST_NOTHING;
            }
            final boolean showImeSwitcherButton = shouldShowImeSwitcherButtonLocked(userId);
            if (mStatusBarManagerInternal != null) {
                mStatusBarManagerInternal.setImeWindowStatus(curDisplayId, vis, backDisposition,
                        showImeSwitcherButton);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("ImfLock.class")
    void updateInputMethodsFromSettingsLocked(boolean enabledMayChange, @UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        if (enabledMayChange) {
            final PackageManager userAwarePackageManager = getPackageManagerForUser(mContext,
                    userId);

            List<InputMethodInfo> enabled = settings.getEnabledInputMethodList();
            for (int i = 0; i < enabled.size(); i++) {
                // We allow the user to select "disabled until used" apps, so if they
                // are enabling one of those here we now need to make it enabled.
                InputMethodInfo imm = enabled.get(i);
                ApplicationInfo ai = null;
                try {
                    ai = userAwarePackageManager.getApplicationInfo(imm.getPackageName(),
                            PackageManager.ApplicationInfoFlags.of(
                                    PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS));
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                if (ai != null && ai.enabledSetting
                        == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                    ProtoLog.v(IMMS_DEBUG, "Update state(%s): DISABLED_UNTIL_USED -> DEFAULT",
                            imm.getId());
                    userAwarePackageManager.setApplicationEnabledSetting(imm.getPackageName(),
                            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                            PackageManager.DONT_KILL_APP);
                }
            }
        }

        final var userData = getUserData(userId);
        if (userData.mBindingController.getDeviceId() == DEVICE_ID_DEFAULT) {
            String ime = SecureSettingsWrapper.getString(
                    Settings.Secure.DEFAULT_INPUT_METHOD, null, userId);
            String defaultDeviceIme = SecureSettingsWrapper.getString(
                    Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD, null, userId);
            if (defaultDeviceIme != null && !Objects.equals(ime, defaultDeviceIme)) {
                ProtoLog.v(IMMS_DEBUG,
                        "Current input method %s differs from the stored default device input "
                                + "method for user %d - restoring %s",
                        ime, userId, defaultDeviceIme);
                SecureSettingsWrapper.putString(
                        Settings.Secure.DEFAULT_INPUT_METHOD, defaultDeviceIme, userId);
                SecureSettingsWrapper.putString(
                        Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD, null, userId);
            }
        }

        // We are assuming that whoever is changing DEFAULT_INPUT_METHOD and
        // ENABLED_INPUT_METHODS is taking care of keeping them correctly in
        // sync, so we will never have a DEFAULT_INPUT_METHOD that is not
        // enabled.
        String selectedImeId = settings.getSelectedInputMethod();
        // There is no input method selected, try to choose new applicable input method.
        if (TextUtils.isEmpty(selectedImeId) && chooseNewDefaultIMELocked(userId)) {
            selectedImeId = settings.getSelectedInputMethod();
        }
        if (!TextUtils.isEmpty(selectedImeId)) {
            try {
                setInputMethodLocked(selectedImeId,
                        settings.getSelectedInputMethodSubtypeIndex(selectedImeId), userId);
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Unknown input method from prefs: " + selectedImeId, e);
                resetCurrentMethodAndClientLocked(UnbindReason.SWITCH_IME_FAILED, userId);
            }
        } else {
            // There is no longer an input method set, so stop any current one.
            resetCurrentMethodAndClientLocked(UnbindReason.NO_IME, userId);
        }

        userData.mSwitchingController.update(mContext, settings);
        sendOnNavButtonFlagsChangedLocked(userData);
    }

    private void updateTouchPollingRate() {
        if (!mLineageHardware.isSupported(LineageHardwareManager.FEATURE_HIGH_TOUCH_POLLING_RATE)) {
            return;
        }
        // If user is a profile, use preference of its parent profile.
        final int profileParentUserId = mUserManagerInternal.getProfileParentId(mCurrentImeUserId);
        final boolean enabled = LineageSettings.System.getIntForUser(mContext.getContentResolver(),
                LineageSettings.System.HIGH_TOUCH_POLLING_RATE_ENABLE, 0, profileParentUserId) == 1;
        mLineageHardware.set(LineageHardwareManager.FEATURE_HIGH_TOUCH_POLLING_RATE, enabled);
    }

    private void updateTouchSensitivity() {
        if (!mLineageHardware.isSupported(LineageHardwareManager.FEATURE_HIGH_TOUCH_SENSITIVITY)) {
            return;
        }
        // If user is a profile, use preference of its parent profile.
        final int profileParentUserId = mUserManagerInternal.getProfileParentId(mCurrentImeUserId);
        final boolean enabled = LineageSettings.System.getIntForUser(mContext.getContentResolver(),
                LineageSettings.System.HIGH_TOUCH_SENSITIVITY_ENABLE, 0, profileParentUserId) == 1;
        mLineageHardware.set(LineageHardwareManager.FEATURE_HIGH_TOUCH_SENSITIVITY, enabled);
    }

    private void updateTouchHovering() {
        if (!mLineageHardware.isSupported(LineageHardwareManager.FEATURE_TOUCH_HOVERING)) {
            return;
        }
        // If user is a profile, use preference of its parent profile.
        final int profileParentUserId = mUserManagerInternal.getProfileParentId(mCurrentImeUserId);
        final boolean enabled = LineageSettings.Secure.getIntForUser(mContext.getContentResolver(),
                LineageSettings.Secure.FEATURE_TOUCH_HOVERING, 0, profileParentUserId) == 1;
        mLineageHardware.set(LineageHardwareManager.FEATURE_TOUCH_HOVERING, enabled);
    }

    @GuardedBy("ImfLock.class")
    private void notifyInputMethodSubtypeChangedLocked(@UserIdInt int userId,
            @NonNull InputMethodInfo imi, @Nullable InputMethodSubtype subtype) {
        final InputMethodSubtype normalizedSubtype =
                subtype != null && subtype.isSuitableForPhysicalKeyboardLayoutMapping()
                        ? subtype : null;
        final InputMethodInfo normalizedImi = subtype != null ? imi : null;

        final var userData = getUserData(userId);

        // A workaround for b/356879517. KeyboardLayoutManager has relied on an implementation
        // detail that IMMS triggers this callback only for the current IME user.
        // TODO(b/357663774): Figure out how to better handle this scenario.
        userData.mSubtypeForKeyboardLayoutMapping = Pair.create(normalizedImi, normalizedSubtype);
        if (userId != mCurrentImeUserId) {
            return;
        }
        mInputManagerInternal.onInputMethodSubtypeChangedForKeyboardLayoutMapping(userId,
                normalizedImi, normalizedSubtype);
    }

    @GuardedBy("ImfLock.class")
    void setInputMethodLocked(String id, int subtypeIndex, @UserIdInt int userId) {
        setInputMethodLocked(id, subtypeIndex, DEVICE_ID_DEFAULT, userId);
    }

    @GuardedBy("ImfLock.class")
    void setInputMethodLocked(String id, int subtypeIndex, int deviceId, @UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final InputMethodInfo imi = settings.getMethodMap().get(id);
        if (imi == null) {
            throw getExceptionForUnknownImeId(id);
        }

        final var bindingController = getInputMethodBindingController(userId);
        // See if we need to notify a subtype change within the same IME.
        if (id.equals(bindingController.getSelectedImeId())) {
            final int subtypeCount = imi.getSubtypeCount();
            if (subtypeCount <= 0) {
                notifyInputMethodSubtypeChangedLocked(userId, imi, null);
                return;
            }
            final InputMethodSubtype oldSubtype = bindingController.getCurrentSubtype();
            final InputMethodSubtype newSubtype;
            if (subtypeIndex >= 0 && subtypeIndex < subtypeCount) {
                newSubtype = imi.getSubtypeAt(subtypeIndex);
            } else {
                // If subtype is null, try to find the most applicable one from
                // getCurrentInputMethodSubtype.
                subtypeIndex = NOT_A_SUBTYPE_INDEX;
                // TODO(b/347083680): The method below has questionable behaviors.
                newSubtype = bindingController.getCurrentInputMethodSubtype();
                if (newSubtype != null) {
                    for (int i = 0; i < subtypeCount; ++i) {
                        if (Objects.equals(newSubtype, imi.getSubtypeAt(i))) {
                            subtypeIndex = i;
                            break;
                        }
                    }
                }
            }
            if (!Objects.equals(newSubtype, oldSubtype)) {
                setSelectedInputMethodAndSubtypeLocked(imi, subtypeIndex, true, userId);
                final IInputMethodInvoker curIme = bindingController.getCurIme();
                if (curIme != null) {
                    updateSystemUiLocked(bindingController.getImeWindowVis(),
                            bindingController.getBackDisposition(), userId);
                    curIme.changeInputMethodSubtype(newSubtype);
                }
            }
            return;
        }

        // Changing to a different IME.
        if (bindingController.getDeviceId() != DEVICE_ID_DEFAULT && deviceId == DEVICE_ID_DEFAULT) {
            // This change should only be applicable to the default device but the current input
            // method is a custom one specific to a virtual device. So only update the settings
            // entry used to restore the default device input method once we want to show the IME
            // back on the default device.
            settings.putSelectedDefaultDeviceInputMethod(id);
            return;
        }
        final IInputMethodInvoker curIme = bindingController.getCurIme();
        if (curIme != null) {
            curIme.removeStylusHandwritingWindow();
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            setSelectedInputMethodAndSubtypeLocked(imi, subtypeIndex, false, userId);
            // setSelectedInputMethodAndSubtypeLocked saves the selectedImeId in the history, so it
            // must be updated after the call.
            bindingController.setSelectedImeId(id);

            if (mActivityManagerInternal.isSystemReady()) {
                Intent intent = new Intent(Intent.ACTION_INPUT_METHOD_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                intent.putExtra("input_method_id", id);
                mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            }
            bindingController.unbindIme();
            unbindCurrentClientLocked(UnbindReason.SWITCH_IME, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Shows the current bound IME on the current IME client of the user of the given windowToken.
     *
     * @param windowToken the token of the IME client window.
     * @param statsToken  the token tracking the current IME request.
     * @param forceShow   whether to send a show request even if
     * {@link ImeVisibilityStateComputer#isInputShown} is {@code true}.
     * @return            {@code true} if the request was sent to the IME, {@code false} otherwise.
     */
    // TODO(b/353463205) check callers to see if we can make statsToken @NonNull
    boolean showCurrentInputInternal(IBinder windowToken, @NonNull ImeTracker.Token statsToken,
            boolean forceShow) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.showCurrentInputInternal");
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#showSoftInput", mDumper);
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdFromWindowLocked(windowToken);
            final long ident = Binder.clearCallingIdentity();
            try {
                ProtoLog.v(IMMS_DEBUG, "Client requesting input be shown");
                return showCurrentInputLocked(windowToken, statsToken,
                        SoftInputShowHideReason.SHOW_SOFT_INPUT, userId, forceShow);
            } finally {
                Binder.restoreCallingIdentity(ident);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }
    }

    // TODO(b/353463205) check callers to see if we can make statsToken @NonNull
    boolean hideCurrentInputInternal(IBinder windowToken, @NonNull ImeTracker.Token statsToken) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.hideCurrentInputInternal");
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#hideSoftInput", mDumper);
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdFromWindowLocked(windowToken);
            final long ident = Binder.clearCallingIdentity();
            try {
                ProtoLog.v(IMMS_DEBUG, "Client requesting input be hidden");
                return hideCurrentInputLocked(windowToken, true /* updateTargetWindow */,
                        statsToken, SoftInputShowHideReason.HIDE_SOFT_INPUT, userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }
    }

    @BinderThread
    @Override
    public void startStylusHandwriting(IInputMethodClient client) {
        startStylusHandwriting(client, false /* usesDelegation */);
    }

    @BinderThread
    @Override
    public void startConnectionlessStylusHandwriting(IInputMethodClient client, int userId,
            @Nullable CursorAnchorInfo cursorAnchorInfo, @Nullable String delegatePackageName,
            @Nullable String delegatorPackageName,
            @NonNull IConnectionlessHandwritingCallback callback) {
        synchronized (ImfLock.class) {
            final var bindingController = getInputMethodBindingController(userId);
            if (!bindingController.getSupportsConnectionlessStylusHandwriting()) {
                Slog.w(TAG, "Connectionless stylus handwriting mode unsupported by IME.");
                try {
                    callback.onError(CONNECTIONLESS_HANDWRITING_ERROR_UNSUPPORTED);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to report CONNECTIONLESS_HANDWRITING_ERROR_UNSUPPORTED", e);
                    e.rethrowAsRuntimeException();
                }
                return;
            }
        }

        IConnectionlessHandwritingCallback immsCallback = callback;
        boolean isForDelegation = delegatePackageName != null && delegatorPackageName != null;
        if (isForDelegation) {
            synchronized (ImfLock.class) {
                if (!mClientController.verifyClientAndPackageMatch(client, delegatorPackageName)) {
                    Slog.w(TAG, "startConnectionlessStylusHandwriting() fail");
                    try {
                        callback.onError(CONNECTIONLESS_HANDWRITING_ERROR_OTHER);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to report CONNECTIONLESS_HANDWRITING_ERROR_OTHER", e);
                        e.rethrowAsRuntimeException();
                    }
                    throw new IllegalArgumentException("Delegator doesn't match UID");
                }
            }
            immsCallback = new IConnectionlessHandwritingCallback.Stub() {
                @Override
                public void onResult(CharSequence text) throws RemoteException {
                    synchronized (ImfLock.class) {
                        mHwController.prepareStylusHandwritingDelegation(
                                userId, delegatePackageName, delegatorPackageName,
                                /* connectionless= */ true);
                    }
                    callback.onResult(text);
                }

                @Override
                public void onError(int errorCode) throws RemoteException {
                    callback.onError(errorCode);
                }
            };
        }

        if (!startStylusHandwriting(
                client, false, immsCallback, cursorAnchorInfo, isForDelegation)) {
            try {
                callback.onError(CONNECTIONLESS_HANDWRITING_ERROR_OTHER);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to report CONNECTIONLESS_HANDWRITING_ERROR_OTHER", e);
                e.rethrowAsRuntimeException();
            }
        }
    }

    private void startStylusHandwriting(IInputMethodClient client, boolean acceptingDelegation) {
        startStylusHandwriting(client, acceptingDelegation, null, null, false);
    }

    private boolean startStylusHandwriting(IInputMethodClient client, boolean acceptingDelegation,
            IConnectionlessHandwritingCallback connectionlessCallback,
            CursorAnchorInfo cursorAnchorInfo, boolean isConnectionlessForDelegation) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.startStylusHandwriting");
        try {
            ImeTracing.getInstance().triggerManagerServiceDump(
                    "InputMethodManagerService#startStylusHandwriting", mDumper);
            final int uid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(uid);
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdLocked(callingUserId);
                if (!acceptingDelegation) {
                    mHwController.clearPendingHandwritingDelegation();
                }
                if (!canInteractWithImeLocked(uid, client, "startStylusHandwriting",
                        null /* statsToken */, userId)) {
                    return false;
                }
                if (!hasSupportedStylusLocked()) {
                    Slog.w(TAG, "No supported Stylus hardware found on device. Ignoring"
                            + " startStylusHandwriting()");
                    return false;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    final var bindingController = getInputMethodBindingController(userId);
                    if (!bindingController.getSupportsStylusHandwriting()) {
                        Slog.w(TAG,
                                "Stylus HW unsupported by IME. Ignoring startStylusHandwriting()");
                        return false;
                    }

                    final OptionalInt requestId = mHwController.getCurrentRequestId();
                    if (!requestId.isPresent()) {
                        Slog.e(TAG, "Stylus handwriting was not initialized.");
                        return false;
                    }
                    if (!mHwController.isStylusGestureOngoing()) {
                        Slog.e(TAG,
                                "There is no ongoing stylus gesture to start stylus handwriting.");
                        return false;
                    }
                    if (mHwController.hasOngoingStylusHandwritingSession()) {
                        // prevent duplicate calls to startStylusHandwriting().
                        Slog.e(TAG,
                                "Stylus handwriting session is already ongoing."
                                        + " Ignoring startStylusHandwriting().");
                        return false;
                    }
                    ProtoLog.v(IMMS_DEBUG, "Client requesting Stylus Handwriting to be started");
                    final IInputMethodInvoker curIme = bindingController.getCurIme();
                    if (curIme != null) {
                        curIme.canStartStylusHandwriting(requestId.getAsInt(),
                                connectionlessCallback, cursorAnchorInfo,
                                isConnectionlessForDelegation);
                        return true;
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
        return false;
    }

    @Override
    public void prepareStylusHandwritingDelegation(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName) {
        if (!isStylusHandwritingEnabled(mContext, userId)) {
            Slog.w(TAG, "Can not prepare stylus handwriting delegation. Stylus handwriting"
                    + " pref is disabled for user: " + userId);
            return;
        }
        synchronized (ImfLock.class) {
            if (!mClientController.verifyClientAndPackageMatch(client,
                    delegatorPackageName)) {
                Slog.w(TAG, "prepareStylusHandwritingDelegation() fail");
                throw new IllegalArgumentException("Delegator doesn't match Uid");
            }
        }
        schedulePrepareStylusHandwritingDelegation(
                userId, delegatePackageName, delegatorPackageName);
    }

    @Override
    public void acceptStylusHandwritingDelegationAsync(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags, IBooleanListener callback) {
        boolean result = acceptStylusHandwritingDelegation(
                client, userId, delegatePackageName, delegatorPackageName, flags);
        try {
            callback.onResult(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to report result=" + result, e);
            e.rethrowAsRuntimeException();
        }
    }

    @Override
    public boolean acceptStylusHandwritingDelegation(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags) {
        if (!isStylusHandwritingEnabled(mContext, userId)) {
            Slog.w(TAG, "Can not accept stylus handwriting delegation. Stylus handwriting"
                    + " pref is disabled for user: " + userId);
            return false;
        }
        if (!verifyDelegator(client, delegatePackageName, delegatorPackageName, flags)) {
            return false;
        }
        synchronized (ImfLock.class) {
            final var bindingController = getInputMethodBindingController(userId);
            if (mHwController.isDelegationUsingConnectionlessFlow()) {
                final IInputMethodInvoker curIme = bindingController.getCurIme();
                if (curIme == null) {
                    return false;
                }
                curIme.commitHandwritingDelegationTextIfAvailable();
                mHwController.clearPendingHandwritingDelegation();
            } else {
                startStylusHandwriting(client, true /* acceptingDelegation */);
            }
        }
        return true;
    }

    private boolean verifyDelegator(
            @NonNull IInputMethodClient client,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags) {
        synchronized (ImfLock.class) {
            if (!mClientController.verifyClientAndPackageMatch(client, delegatePackageName)) {
                Slog.w(TAG, "Delegate package does not belong to the same user. Ignoring"
                        + " startStylusHandwriting");
                return false;
            }
            boolean homeDelegatorAllowed =
                    (flags & InputMethodManager.HANDWRITING_DELEGATE_FLAG_HOME_DELEGATOR_ALLOWED)
                            != 0;
            if (!delegatorPackageName.equals(mHwController.getDelegatorPackageName())
                    && !(homeDelegatorAllowed
                            && mHwController.isDelegatorFromDefaultHomePackage())) {
                Slog.w(TAG,
                        "Delegator package does not match. Ignoring startStylusHandwriting");
                return false;
            }
            if (!delegatePackageName.equals(mHwController.getDelegatePackageName())) {
                Slog.w(TAG,
                        "Delegate package does not match. Ignoring startStylusHandwriting");
                return false;
            }
        }
        return true;
    }

    @BinderThread
    @Override
    public void reportPerceptible(@NonNull IBinder windowToken, boolean perceptible) {
        Binder.withCleanCallingIdentity(() -> {
            Objects.requireNonNull(windowToken, "windowToken must not be null");
            synchronized (ImfLock.class) {
                Boolean windowPerceptible = mFocusedWindowPerceptible.get(windowToken);
                final int userId = resolveImeUserIdFromWindowLocked(windowToken);
                final var userData = getUserData(userId);
                if (userData.mImeBindingState.mFocusedWindow != windowToken
                        || (windowPerceptible != null && windowPerceptible == perceptible)) {
                    return;
                }
                mFocusedWindowPerceptible.put(windowToken, perceptible);
                updateSystemUiLocked(userId);
            }
        });
    }

    /**
     * Shows the currently bound IME on the current IME client of the given user.
     *
     * @param windowToken the token of the IME client window.
     * @param statsToken  the token tracking the current IME request.
     * @param reason      the reason for requesting to show the IME.
     * @param userId      the ID of the user to show the IME for.
     * @param forceShow   whether to send a show request even if
     * {@link ImeVisibilityStateComputer#isInputShown} is {@code true}.
     * @return            {@code true} if the request was sent to the IME, {@code false} otherwise.
     */
    @GuardedBy("ImfLock.class")
    private boolean showCurrentInputLocked(IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @SoftInputShowHideReason int reason,
            @UserIdInt int userId, boolean forceShow) {
        final var userData = getUserData(userId);
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        if (!visibilityStateComputer.isAllowedByAccessibilityAndDisplayPolicy()) {
            ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_ACCESSIBILITY);
            return false;
        }

        if (!mSystemReady) {
            ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_SYSTEM_READY);
            return false;
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_SYSTEM_READY);

        if (visibilityStateComputer.isInputShown() && !forceShow) {
            // We already called showSoftInput on the IME, no need to dispatch a new show request.
            ImeTracker.forLogging().onCancelled(statsToken,
                    ImeTracker.PHASE_SERVER_ALREADY_VISIBLE);
            maybeReportToolType(userId);
            return false;
        }
        visibilityStateComputer.requestImeVisibility(windowToken, true);

        // Ensure binding the connection when IME is going to show.
        final var bindingController = userData.mBindingController;
        bindingController.setImeVisibleOrReconnect();
        final IInputMethodInvoker curIme = bindingController.getCurIme();
        ImeTracker.forLogging().onCancelled(userData.mCurStatsToken,
                ImeTracker.PHASE_SERVER_WAIT_IME);
        final boolean readyToDispatchToIme = curIme != null && userData.mCurClient != null
                && userData.mCurClient.mCurSession != null;
        if (readyToDispatchToIme) {
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_HAS_IME);
            userData.mCurStatsToken = null;

            maybeReportToolType(userId);
            performShowIme(curIme, windowToken, statsToken, reason, userData);
            visibilityStateComputer.setInputShown(true);
            return true;
        } else {
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_WAIT_IME);
            userData.mCurStatsToken = statsToken;
        }
        return false;
    }

    /**
     * Performs showing IME on top of the given window.
     *
     * @param ime            the interface used to make calls on the IME to be shown.
     * @param showInputToken a token that represents the requester to show IME
     * @param statsToken     the token tracking the current IME request
     * @param reason         the reason for requesting to show IME
     * @param userData       the data of the target user when performing show IME
     */
    @GuardedBy("ImfLock.class")
    void performShowIme(@NonNull IInputMethodInvoker ime, IBinder showInputToken,
            @NonNull ImeTracker.Token statsToken, @SoftInputShowHideReason int reason,
            @NonNull UserData userData) {
        ProtoLog.v(IMMS_DEBUG, "Calling %s.showSoftInput(%s) for reason: %s", ime,
                showInputToken, InputMethodDebug.softInputDisplayReasonToString(reason));
        // TODO(b/192412909): Check if we can always call onShowHideSoftInputRequested() or not.
        if (ime.showSoftInput(statsToken)) {
            if (DEBUG_IME_VISIBILITY) {
                EventLog.writeEvent(IMF_SHOW_IME,
                        statsToken.getTag(),
                        Objects.toString(userData.mImeBindingState.mFocusedWindow),
                        InputMethodDebug.softInputDisplayReasonToString(reason),
                        InputMethodDebug.softInputModeToString(
                                userData.mImeBindingState.mFocusedWindowSoftInputMode));
            }
            // TODO(b/419459695): Check if we still need to pass the input token
            onShowHideSoftInputRequested(true /* show */, showInputToken, reason, statsToken,
                    userData.mUserId);
        }
    }

    @GuardedBy("ImfLock.class")
    private void maybeReportToolType(@UserIdInt int userId) {
        // TODO(b/356638981): This needs to be compatible with visible background users.
        int lastDeviceId = mInputManagerInternal.getLastUsedInputDeviceId();
        final InputManager im = mContext.getSystemService(InputManager.class);
        if (im == null) {
            return;
        }
        InputDevice device = im.getInputDevice(lastDeviceId);
        if (device == null) {
            return;
        }
        int toolType;
        if (isStylusDevice(device)) {
            toolType = MotionEvent.TOOL_TYPE_STYLUS;
        } else if (isFingerDevice(device)) {
            toolType = MotionEvent.TOOL_TYPE_FINGER;
        } else {
            // other toolTypes are irrelevant and reported as unknown.
            toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        }
        onUpdateEditorToolTypeLocked(toolType, userId);
    }

    @Override
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    public void hideSoftInputFromServerForTest() {
        final int callingUserId = UserHandle.getCallingUserId();
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdLocked(callingUserId);
            final var userData = getUserData(userId);
            final var statsToken = createStatsTokenForFocusedClient(false /* show */,
                    SoftInputShowHideReason.HIDE_SOFT_INPUT, userId);
            hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow,
                    true /* updateTargetWindow */, statsToken,
                    SoftInputShowHideReason.HIDE_SOFT_INPUT, userId);
        }
    }

    /**
     * Hides the IME for the given focused window and user.
     *
     * <p>This will also update the {@link ImeTargetWindowState} through
     * {@link ImeVisibilityStateComputer#requestImeVisibility} if {@code updateTargetWindow} is set.
     * Otherwise, this will only send the hide signal to the IME without updating the target window
     * state, such that the requested visibility can be later restored when this window gains focus.
     *
     * @param windowToken        the token of the current focused window.
     * @param updateTargetWindow whether to update the {@link ImeTargetWindowState}.
     * @param statsToken         the token tracking the IME hide request.
     * @param reason             the reason for requesting to hide the IME.
     * @param userId             the ID of the user to hide the IME for.
     * @return whether the hide request was sent to the IME or not.
     */
    @GuardedBy("ImfLock.class")
    private boolean hideCurrentInputLocked(IBinder windowToken, boolean updateTargetWindow,
            @NonNull ImeTracker.Token statsToken, @SoftInputShowHideReason int reason,
            @UserIdInt int userId) {
        final var userData = getUserData(userId);
        final var bindingController = userData.mBindingController;
        final var visibilityStateComputer = userData.mVisibilityStateComputer;

        // There is a chance that IMM#hideSoftInput() is called in a transient state where
        // ImeVisibilityStateComputer#mInputShown is already updated to be true whereas the user's
        // ImeWindowVis is still waiting to be updated with the new value sent from IME process.
        // Even in such a transient state historically we have accepted an incoming call of
        // IMM#hideSoftInput() from the application process as a valid request, and have even
        // promised such a behavior with CTS since Android Eclair. That's why we need to accept
        // IMM#hideSoftInput() even when only ImeVisibilityStateComputer#mInputShown indicates that
        // the software keyboard is shown.
        final IInputMethodInvoker curIme = bindingController.getCurIme();
        final boolean shouldHideSoftInput = curIme != null
                && (visibilityStateComputer.isInputShown()
                || (bindingController.getImeWindowVis() & InputMethodService.IME_ACTIVE) != 0);

        if (updateTargetWindow) {
            visibilityStateComputer.requestImeVisibility(windowToken, false);
        }
        if (shouldHideSoftInput) {
            // The IME will report its visible state again after the call reaches the IME process as
            // an IPC. Hence the inconsistency between ImeVisibilityStateComputer#mInputShown and
            // the user's ImeWindowVis should be resolved spontaneously in the final state.
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_SHOULD_HIDE);
            performHideIme(curIme, windowToken, statsToken, reason, userData);
        } else {
            ImeTracker.forLogging().onCancelled(statsToken, ImeTracker.PHASE_SERVER_SHOULD_HIDE);
        }
        bindingController.unbindVisibleConnection();
        visibilityStateComputer.setInputShown(false);
        // Cancel existing statsToken for show IME as we got a hide request.
        ImeTracker.forLogging().onCancelled(userData.mCurStatsToken,
                ImeTracker.PHASE_SERVER_WAIT_IME);
        userData.mCurStatsToken = null;
        return shouldHideSoftInput;
    }

    /**
     * Performs hiding IME to the given window
     *
     * @param ime            the interface used to make calls on the IME to hide.
     * @param hideInputToken a token that represents the requester to hide IME
     * @param statsToken     the token tracking the current IME request
     * @param reason         the reason for requesting to hide IME
     * @param userData       the data of the target user when performing show IME
     */
    @GuardedBy("ImfLock.class")
    void performHideIme(@NonNull IInputMethodInvoker ime, IBinder hideInputToken,
            @NonNull ImeTracker.Token statsToken, @SoftInputShowHideReason int reason,
            @NonNull UserData userData) {
        ProtoLog.v(IMMS_DEBUG, "Calling %s.hideSoftInput(%s) for reason: %s", ime,
                hideInputToken, InputMethodDebug.softInputDisplayReasonToString(reason));
        // TODO(b/192412909): Check if we can always call onShowHideSoftInputRequested() or not.
        if (ime.hideSoftInput(statsToken)) {
            if (DEBUG_IME_VISIBILITY) {
                EventLog.writeEvent(IMF_HIDE_IME,
                        statsToken.getTag(),
                        Objects.toString(userData.mImeBindingState.mFocusedWindow),
                        InputMethodDebug.softInputDisplayReasonToString(reason),
                        InputMethodDebug.softInputModeToString(
                                userData.mImeBindingState.mFocusedWindowSoftInputMode));
            }
            // TODO(b/419459695): Check if we still need to pass the input token
            onShowHideSoftInputRequested(false /* show */, hideInputToken, reason, statsToken,
                    userData.mUserId);
        }
    }

    /**
     * Checks whether the specified IME client has IME focus or not.
     *
     * @param windowToken the token of the IME client window.
     * @param cs          the IME client state.
     */
    private boolean isImeClientFocused(IBinder windowToken, @NonNull ClientState cs) {
        final int imeClientFocus = mWindowManagerInternal.hasInputMethodClientFocus(
                windowToken, cs.mUid, cs.mPid, cs.mSelfReportedDisplayId);
        return imeClientFocus == WindowManagerInternal.ImeClientFocusResult.HAS_IME_FOCUS;
    }

    @Override
    public void startInputOrWindowGainedFocus(
            @StartInputReason int startInputReason, @NonNull IInputMethodClient client,
            @Nullable IBinder windowToken, @StartInputFlags int startInputFlags,
            @SoftInputModeFlags int softInputMode,
            @WindowManager.LayoutParams.Flags int windowFlags, @Nullable EditorInfo editorInfo,
            @Nullable IRemoteInputConnection inputConnection,
            @Nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            @Nullable IRemoteComputerControlInputConnection remoteComputerControlInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ResultReceiver imeBackCallbackReceiver, boolean imeRequestedVisible,
            int startInputSeq) {
        final var res = startInputOrWindowGainedFocusWithResult(startInputReason, client,
                windowToken, startInputFlags, softInputMode, windowFlags, editorInfo,
                inputConnection, remoteAccessibilityInputConnection,
                remoteComputerControlInputConnection, unverifiedTargetSdkVersion, userId,
                imeBackCallbackReceiver, imeRequestedVisible);
        synchronized (ImfLock.class) {
            final ClientState cs = mClientController.getClient(client.asBinder());
            if (cs != null) {
                cs.mClient.onStartInputResult(res, startInputSeq);
                // For first-time client bind, MSG_BIND should arrive after MSG_START_INPUT_RESULT.
                if (res.result == InputBindResult.ResultCode.SUCCESS_WAITING_IME_SESSION) {
                    requestClientSessionLocked(cs, userId);
                    requestClientSessionForAccessibilityLocked(cs);
                }
            } else {
                // client is unbound.
                Slog.i(TAG, "Client that requested startInputOrWindowGainedFocus is no longer"
                        + " bound. InputBindResult: " + res + " for startInputSeq: "
                        + startInputSeq);
            }
        }
    }

    @VisibleForTesting
    @NonNull
    InputBindResult startInputOrWindowGainedFocusWithResult(
            @StartInputReason int startInputReason, IInputMethodClient client, IBinder windowToken,
            @StartInputFlags int startInputFlags, @SoftInputModeFlags int softInputMode,
            @WindowManager.LayoutParams.Flags int windowFlags, @Nullable EditorInfo editorInfo,
            @Nullable IRemoteInputConnection inputConnection,
            @Nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            @Nullable IRemoteComputerControlInputConnection remoteComputerControlInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ResultReceiver imeBackCallbackReceiver, boolean imeRequestedVisible) {
        ProtoLog.v(IMMS_DEBUG,
                "startInputOrWindowGainedFocus: userId=%d callingUserId=%d editorInfo=%s",
                userId, UserHandle.getCallingUserId(), editorInfo);
        if (UserHandle.getCallingUserId() != userId) {
            ProtoLog.v(IMMS_DEBUG,
                    "startInputOrWindowGainedFocus callingUserId != userid (%d != %d)",
                    UserHandle.getCallingUserId(), userId);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);

            if (editorInfo == null || editorInfo.targetInputMethodUser == null
                    || editorInfo.targetInputMethodUser.getIdentifier() != userId) {
                throw new InvalidParameterException("EditorInfo#targetInputMethodUser must also be "
                        + "specified for cross-user startInputOrWindowGainedFocus()");
            }
        }
        if (windowToken == null) {
            Slog.e(TAG, "windowToken cannot be null.");
            return InputBindResult.NULL;
        }
        // The user represented by userId, must be running.
        if (!mUserManagerInternal.isUserRunning(userId)) {
            // There is a chance that we hit here because of race condition. Let's just
            // return an error code instead of crashing the caller process, which at
            // least has INTERACT_ACROSS_USERS_FULL permission thus is likely to be an
            // important process.
            Slog.w(TAG, "User #" + userId + " is not running.");
            return InputBindResult.INVALID_USER;
        }
        final var userData = getUserData(userId);
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                    "IMMS.startInputOrWindowGainedFocus");
            ImeTracing.getInstance().triggerManagerServiceDump(
                    "InputMethodManagerService#startInputOrWindowGainedFocus", mDumper);
            final InputBindResult result;
            synchronized (ImfLock.class) {
                final var bindingController = userData.mBindingController;
                // If the system is not yet ready, we shouldn't be running third party code.
                if (!mSystemReady) {
                    return new InputBindResult(InputBindResult.ResultCode.ERROR_SYSTEM_NOT_READY,
                            null /* method */, null /* accessibilitySessions */, null /* channel */,
                            bindingController.getSelectedImeId(),
                            bindingController.getSequenceNumber(),
                            false /* isInputMethodSuppressingSpellChecker */);
                }
                final ClientState cs = mClientController.getClient(client.asBinder());
                if (cs == null) {
                    throw new IllegalArgumentException("Unknown client " + client.asBinder());
                }
                // Keep track on computer control input connection that was last provided by the
                // client on a particular display.
                if (mVdmInternal == null) {
                    mVdmInternal = LocalServices.getService(VirtualDeviceManagerInternal.class);
                }
                if (remoteComputerControlInputConnection != null && mVdmInternal != null
                        && mVdmInternal.isComputerControlDisplay(cs.mSelfReportedDisplayId)) {
                    userData.mComputerControlInputConnectionMap.put(cs.mSelfReportedDisplayId,
                            new InputMethodManagerInternal.ComputerControlInputConnectionData(
                                    remoteComputerControlInputConnection, editorInfo));
                } else {
                    userData.mComputerControlInputConnectionMap.remove(
                            cs.mSelfReportedDisplayId);
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    // Ensure that caller's focused window and display parameters are allowed to
                    // display input method.
                    final int imeClientFocus = mWindowManagerInternal.hasInputMethodClientFocus(
                            windowToken, cs.mUid, cs.mPid, cs.mSelfReportedDisplayId);
                    switch (imeClientFocus) {
                        case WindowManagerInternal.ImeClientFocusResult.DISPLAY_ID_MISMATCH:
                            Slog.e(TAG,
                                    "startInputOrWindowGainedFocusInternal: display ID mismatch.");
                            return InputBindResult.DISPLAY_ID_MISMATCH;
                        case WindowManagerInternal.ImeClientFocusResult.NOT_IME_TARGET_WINDOW:
                            // Check with the window manager to make sure this client actually
                            // has a window with focus.  If not, reject.  This is thread safe
                            // because if the focus changes some time before or after, the
                            // next client receiving focus that has any interest in input will
                            // be calling through here after that change happens.
                            ProtoLog.v(IMMS_DEBUG,
                                    "Focus gain on non-focused client %s (uid=%d pid=%d)",
                                    cs.mClient, cs.mUid, cs.mPid);
                            return InputBindResult.NOT_IME_TARGET_WINDOW;
                        case WindowManagerInternal.ImeClientFocusResult.INVALID_DISPLAY_ID:
                            return InputBindResult.INVALID_DISPLAY_ID;
                    }

                    if (!mConcurrentMultiUserModeEnabled) {
                        // The target user is that of the pending user switch if there is any,
                        // otherwise it is the current user
                        final int targetUserId = mUserSwitchHandlerTask != null
                                ? mUserSwitchHandlerTask.mNewUserId : mCurrentImeUserId;
                        // Allow this user (potentially requiring a switch) if:
                        //  * it is the target user OR
                        //  * it is a profile of the target user.
                        // If there is a pending user switch to a different full user, and this user
                        // is a profile of the current full user, then deny it.
                        final boolean isAllowed = userId == targetUserId
                                || ArrayUtils.contains(getProfileIds(targetUserId), userId);
                        if (!isAllowed) {
                            Slog.w(TAG, "A background user " + userId + " is requesting window."
                                    + " Hiding IME.");
                            Slog.w(TAG, "If you need to impersonate a foreground user/profile from"
                                    + " a background user, use EditorInfo.targetInputMethodUser"
                                    + " with INTERACT_ACROSS_USERS_FULL permission.");
                            final var statsToken = createStatsTokenForFocusedClient(
                                    false /* show */, SoftInputShowHideReason.HIDE_INVALID_USER,
                                    userId);
                            hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow,
                                    true /* updateTargetWindow */, statsToken,
                                    SoftInputShowHideReason.HIDE_INVALID_USER, userId);
                            return InputBindResult.INVALID_USER;
                        }
                        // Schedule a pending user switch, and cancel any ongoing one. If we do
                        // schedule a new one, it must be a profile switch.
                        if (scheduleSwitchUserTaskLocked(userId, true /* profileSwitch */,
                                cs.mClient)) {
                            // Pending user switch scheduled, signal the client to wait.
                            return InputBindResult.USER_SWITCHING;
                        }
                        // No pending user switch, already in the right user.
                    }

                    if (editorInfo != null && !InputMethodUtils.checkIfPackageBelongsToUid(
                            mPackageManagerInternal, cs.mUid, editorInfo.packageName)) {
                        Slog.e(TAG, "Rejecting this client as it reported an invalid package name."
                                + " uid=" + cs.mUid + " package=" + editorInfo.packageName);
                        return InputBindResult.INVALID_PACKAGE_NAME;
                    }

                    result = startInputOrWindowGainedFocusInternalLocked(startInputReason,
                            client, windowToken, startInputFlags, softInputMode, windowFlags,
                            editorInfo, inputConnection, remoteAccessibilityInputConnection,
                            unverifiedTargetSdkVersion, bindingController, imeBackCallbackReceiver,
                            cs, imeRequestedVisible);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
            if (result == null) {
                // This must never happen, but just in case.
                Slog.wtf(TAG, "InputBindResult is @NonNull. startInputReason="
                        + InputMethodDebug.startInputReasonToString(startInputReason)
                        + " windowFlags=#" + Integer.toHexString(windowFlags)
                        + " editorInfo=" + editorInfo);
                return InputBindResult.NULL;
            }

            return result;
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    /**
     * Returns List of {@link InputMethodInfo} that are allowed for the user
     * {@link EditorInfo#targetDevicePolicyUser}, {@code null} means all are allowed.
     * @param editorInfo {@link EditorInfo} of the editor user is interacting with.
     */
    @Nullable
    @GuardedBy("ImfLock.class")
    private List<InputMethodInfo> getAllowedImesByPolicyLocked(@Nullable EditorInfo editorInfo) {
        if (!Flags.enforceDevicePolicyIme()) {
            return null;
        }
        if (editorInfo == null || editorInfo.targetDevicePolicyUser == null) {
            return null;
        }
        int dpUserId = editorInfo.targetDevicePolicyUser.getIdentifier();
        Set<String> allowedImePackages =
                LocalServices.getService(DevicePolicyManagerInternal.class)
                        .getPermittedInputMethodPackages(dpUserId);
        List<InputMethodInfo> allowedImes = getAllowedImesForPackages(allowedImePackages, dpUserId);
        // The getPermittedInputMethods() method returns null if no DPM restrictions are set for the
        // user. In this test-only scenario, we proceed with the test-specific IMEs only when we
        // can confirm no real policy is active. This prevents adb shell commands from circumventing
        // an otherwise active DPM policy.
        if (mAllowedImesByPolicyForTest != null && !mAllowedImesByPolicyForTest.isEmpty()
                && allowedImePackages == null) {
            mAllowedImesByPolicyForTest.forEach(imi ->
                    ProtoLog.d(IMMS_DEBUG, "Test-only allowed IME: " + imi.getPackageName())
            );
            return mAllowedImesByPolicyForTest;
        }

        ProtoLog.v(IMMS_DEBUG,
                "startInputOrWindowGainedFocus editorInfo.targetDevicePolicyUser: %d",
                editorInfo.targetDevicePolicyUser.getIdentifier());
        StringBuilder allowedImesLog = new StringBuilder("allowedImes: ");
        if (allowedImes != null) {
            allowedImes.forEach(allowedIme ->
                    allowedImesLog.append(allowedIme.getId()).append(", "));
        } else {
            allowedImesLog.append("null");
        }
        ProtoLog.d(IMMS_DEBUG, allowedImesLog.toString());
        return allowedImes;
    }

    private List<InputMethodInfo> getAllowedImesForPackages(
            @Nullable Set<String> allowedImePackages, @UserIdInt int dpUserId) {
        if (allowedImePackages == null) {
            return null;
        }
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_USERS, null);
        List<InputMethodInfo> allowedImes = null;
        UserInfo parentUserInfo = UserManager.get(mContext).getProfileParent(dpUserId);
        List<InputMethodInfo> imes = InputMethodManagerInternal
                .get().getInputMethodListAsUser(parentUserInfo.id);
        if (imes == null) {
            return null;
        }
        allowedImes = new ArrayList<>();
        for (InputMethodInfo ime : imes) {
            if (ime.isAuxiliaryIme()) {
                continue;
            }
            if (ime.isSystem() || allowedImePackages.contains(ime.getPackageName())) {
                ProtoLog.d(IMMS_DEBUG, "getAllowedImesForPackages: add " + ime.getPackageName());
                allowedImes.add(ime);
            }
        }
        return allowedImes;
    }

    @GuardedBy("ImfLock.class")
    @NonNull
    private InputBindResult startInputOrWindowGainedFocusInternalLocked(
            @StartInputReason int startInputReason, IInputMethodClient client,
            @NonNull IBinder windowToken, @StartInputFlags int startInputFlags,
            @SoftInputModeFlags int softInputMode,
            @WindowManager.LayoutParams.Flags int windowFlags, EditorInfo editorInfo,
            @Nullable IRemoteInputConnection inputContext,
            @Nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @NonNull InputMethodBindingController bindingController,
            @NonNull ResultReceiver imeBackCallbackReceiver, @NonNull ClientState cs,
            boolean imeRequestedVisible) {
        ProtoLog.v(IMMS_DEBUG, "startInputOrWindowGainedFocusInternalLocked: reason=%s"
                    + " client=%s"
                    + " inputContext=%s"
                    + " editorInfo=%s"
                    + " startInputFlags=%s"
                    + " softInputMode=%s"
                    + " windowFlags=#%s"
                    + " unverifiedTargetSdkVersion=%d"
                    + " bindingController=%s"
                    + " imeBackCallbackReceiver=%s"
                    + " cs=%s"
                    + " imeRequestedVisible=%b",
                InputMethodDebug.startInputReasonToString(startInputReason), client.asBinder(),
                inputContext, editorInfo, InputMethodDebug.startInputFlagsToString(startInputFlags),
                InputMethodDebug.softInputModeToString(softInputMode),
                Integer.toHexString(windowFlags), unverifiedTargetSdkVersion, bindingController,
                imeBackCallbackReceiver, cs, imeRequestedVisible);

        final int userId = bindingController.getUserId();
        final var userData = getUserData(userId);
        enforceDevicePolicyLocked(userId, bindingController, editorInfo);

        final boolean sameWindowFocused = userData.mImeBindingState.mFocusedWindow == windowToken;
        final boolean isTextEditor = (startInputFlags & StartInputFlags.IS_TEXT_EDITOR) != 0;
        final boolean isStartInputByWindowGainFocus =
                (startInputFlags & StartInputFlags.WINDOW_GAINED_FOCUS) != 0;
        final int toolType = editorInfo != null
                ? editorInfo.getInitialToolType() : MotionEvent.TOOL_TYPE_UNKNOWN;

        // Init the IME target window state (e.g. whether there is a focused editor or IME focus has
        // changed from another window).
        final var targetWindowState = new ImeTargetWindowState(softInputMode, windowFlags,
                !sameWindowFocused /* imeFocusChanged */, isTextEditor /* hasFocusedEditor */,
                isStartInputByWindowGainFocus, toolType);
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        visibilityStateComputer.setWindowState(windowToken, targetWindowState);

        if (sameWindowFocused && isTextEditor) {
            ProtoLog.v(IMMS_DEBUG,
                    "Window already focused, ignoring focus gain of: %s editorInfo=%s, token=%s, "
                            + "startInputReason=%s",
                    client, editorInfo, windowToken,
                    InputMethodDebug.startInputReasonToString(startInputReason));
            if (editorInfo != null) {
                return startInputUncheckedLocked(cs, inputContext,
                        remoteAccessibilityInputConnection, editorInfo, startInputFlags,
                        startInputReason, unverifiedTargetSdkVersion, imeBackCallbackReceiver,
                        bindingController);
            }
            return new InputBindResult(
                    InputBindResult.ResultCode.SUCCESS_REPORT_WINDOW_FOCUS_ONLY,
                    null, null, null, null, -1, false);
        }

        userData.mImeBindingState = new ImeBindingState(userId, windowToken /* focusedWindow */,
                softInputMode, cs, editorInfo);
        mFocusedWindowPerceptible.put(windowToken, true);

        // We want to start input before showing the IME, but after closing
        // it.  We want to do this after closing it to help the IME disappear
        // more quickly (not get stuck behind it initializing itself for the
        // new focused input, even if its window wants to hide the IME).
        boolean didStart = false;
        InputBindResult res = null;

        final ImeVisibilityResult imeVisRes = visibilityStateComputer.computeState(
                targetWindowState,
                isSoftInputModeStateVisibleAllowed(unverifiedTargetSdkVersion, startInputFlags),
                imeRequestedVisible);
        if (imeVisRes != null) {
            final boolean isShow;
            switch (imeVisRes.getReason()) {
                case SoftInputShowHideReason.SHOW_RESTORE_IME_VISIBILITY:
                case SoftInputShowHideReason.SHOW_AUTO_EDITOR_FORWARD_NAV:
                case SoftInputShowHideReason.SHOW_STATE_VISIBLE_FORWARD_NAV:
                case SoftInputShowHideReason.SHOW_STATE_ALWAYS_VISIBLE:
                    isShow = true;

                    if (editorInfo != null) {
                        res = startInputUncheckedLocked(cs, inputContext,
                                remoteAccessibilityInputConnection, editorInfo, startInputFlags,
                                startInputReason, unverifiedTargetSdkVersion,
                                imeBackCallbackReceiver, bindingController);
                        didStart = true;
                    }
                    break;
                default:
                    isShow = false;
                    break;
            }
            final var statsToken = createStatsTokenForFocusedClient(isShow, imeVisRes.getReason(),
                    userId);
            setImeVisibilityOnFocusedWindowClient(imeVisRes.isVisible(), userData, statsToken);
            if (imeVisRes.getReason() == SoftInputShowHideReason.HIDE_UNSPECIFIED_WINDOW) {
                // If focused display changed, we should unbind the IME
                // to make app window in previous display relayout after Ime
                // window token removed.
                // Note that we can trust client's display ID as long as it matches
                // to the display ID obtained from the window.
                if (cs.mSelfReportedDisplayId != bindingController.getCurDisplayId()) {
                    bindingController.unbindIme();
                }
            }
        }
        if (!didStart) {
            if (editorInfo != null) {
                res = startInputUncheckedLocked(cs, inputContext,
                        remoteAccessibilityInputConnection, editorInfo, startInputFlags,
                        startInputReason, unverifiedTargetSdkVersion,
                        imeBackCallbackReceiver, bindingController);
            } else {
                res = InputBindResult.NULL_EDITOR_INFO;
            }
        }
        return res;
    }

    /**
     * Only allow IMEs allowed by DevicePolicy to unlock work profile.
     */
    @GuardedBy("ImfLock.class")
    private void enforceDevicePolicyLocked(
            @UserIdInt int userId, @NonNull InputMethodBindingController bindingController,
            @Nullable EditorInfo editorInfo) {
        if (!Flags.enforceDevicePolicyIme()) {
            return;
        }

        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final String prevSelectedImeId = settings.getSelectedInputMethod();
        ProtoLog.d(IMMS_DEBUG, "enforceDevicePolicy prevSelectedImeId %s",
                prevSelectedImeId);
        // When allowedImes are provided, DevicePolicy must be enforced for selecting IME.
        // If current IME is not in allowedImes, reselect a new IME based on DevicePolicy.
        List<InputMethodInfo> allowedImes = getAllowedImesByPolicyLocked(editorInfo);
        boolean reselectIme = editorInfo != null && allowedImes != null && !allowedImes.isEmpty();
        if (reselectIme) {
            for (InputMethodInfo ime : allowedImes) {
                String imeId = ime.getId();
                if (prevSelectedImeId.equals(imeId)) {
                    // current IME is approved. Do nothing.
                    reselectIme = false;
                    break;
                }
            }
        }

        if (reselectIme && editorInfo.targetDevicePolicyUser != null) {
            final int policyUserId = editorInfo.targetDevicePolicyUser.getIdentifier();
            ProtoLog.d(IMMS_DEBUG, "enforceDevicePolicy for policyUserId " + policyUserId);
            final InputMethodSettings policyUserSettings =
                    InputMethodSettingsRepository.get(policyUserId);
            // first try selecting a system-default IME from allowedImes list.
            if (!chooseNewDefaultIMELocked(allowedImes, policyUserId)) {
                ProtoLog.d(IMMS_DEBUG, "enforceDevicePolicy chooseNewDefaultIMELocked"
                                + " didn't find any active IME, calling enableAllowedIme");
                // If unable to choose find allowedIme from defaults, enable the first allowed IME.
                final InputMethodInfo imi = allowedImes.get(0);
                enableAllowedImeLocked(imi, policyUserSettings);
                chooseNewIMELocked(imi, policyUserId);
            }
            final String newSelectedImeId = policyUserSettings.getSelectedInputMethod();
            bindingController.setImeIdToRestoreOnNextSession(prevSelectedImeId);
            ProtoLog.d(IMMS_DEBUG, "enforceDevicePolicy found IME to"
                    + " enable for policy id: %s and will enabled it for userId: %d",
                    newSelectedImeId,
                    userId);
            setInputMethodLocked(
                    newSelectedImeId,
                    policyUserSettings.getSelectedInputMethodSubtypeIndex(newSelectedImeId),
                    userId);
        } else if (allowedImes == null
                && bindingController.getImeIdToRestoreOnNextSession() != null) {
            // restore user IME when no longer enforced by DevicePolicy.
            String imeId = bindingController.getImeIdToRestoreOnNextSession();
            setInputMethodLocked(
                    imeId,
                    settings.getSelectedInputMethodSubtypeIndex(imeId),
                    userId);
            ProtoLog.d(IMMS_DEBUG, "enforceDevicePolicy restore IME %s",
                    imeId);
            bindingController.setImeIdToRestoreOnNextSession(null /* imeId */);
        }
    }

    @NonNull
    private int[] getProfileIds(@UserIdInt int userId) {
        return mUserManagerInternal.getProfileIds(userId, /* enabledOnly */ false,
                /* includeAlwaysVisible */ true);
    }

    @GuardedBy("ImfLock.class")
    private boolean canInteractWithImeLocked(int uid, IInputMethodClient client, String methodName,
            @Nullable ImeTracker.Token statsToken, @UserIdInt int userId) {
        final var userData = getUserData(userId);
        if (userData.mCurClient == null || client == null
                || userData.mCurClient.mClient.asBinder() != client.asBinder()) {
            // We need to check if this is the current client with
            // focus in the window manager, to allow this call to
            // be made before input is started in it.
            final ClientState cs = mClientController.getClient(client.asBinder());
            if (cs == null) {
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_CLIENT_KNOWN);
                throw new IllegalArgumentException("unknown client " + client.asBinder());
            }
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_CLIENT_KNOWN);
            if (!isImeClientFocused(userData.mImeBindingState.mFocusedWindow, cs)) {
                Slog.w(TAG, String.format("Ignoring %s of uid %d : %s", methodName, uid, client));
                return false;
            }
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_CLIENT_FOCUSED);
        return true;
    }

    @GuardedBy("ImfLock.class")
    private boolean canShowInputMethodPickerLocked(IInputMethodClient client,
            @UserIdInt int userId) {
        final int uid = Binder.getCallingUid();
        final var userData = getUserData(userId);
        if (userData.mImeBindingState.mFocusedWindowClient != null && client != null
                && userData.mImeBindingState.mFocusedWindowClient.mClient.asBinder()
                == client.asBinder()) {
            return true;
        }
        if (userId != UserHandle.getUserId(uid)) {
            return false;
        }
        final var curImeIntent = getInputMethodBindingController(userId).getCurImeIntent();
        if (curImeIntent != null && InputMethodUtils.checkIfPackageBelongsToUid(
                mPackageManagerInternal, uid, curImeIntent.getComponent().getPackageName())) {
            return true;
        }
        return false;
    }

    @Override
    public void showInputMethodPickerFromClient(IInputMethodClient client,
            int auxiliarySubtypeMode) {
        if (mConcurrentMultiUserModeEnabled) {
            Slog.w(TAG, "showInputMethodPickerFromClient is not enabled on automotive");
            return;
        }
        final int callingUserId = UserHandle.getCallingUserId();
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdLocked(callingUserId);
            if (!canShowInputMethodPickerLocked(client, userId)) {
                Slog.w(TAG, "Ignoring showInputMethodPickerFromClient of uid "
                        + Binder.getCallingUid() + ": " + client);
                return;
            }
            final var userData = getUserData(userId);
            // Always call subtype picker, because subtype picker is a superset of input method
            // picker.
            final int displayId = (userData.mCurClient != null)
                    ? userData.mCurClient.mSelfReportedDisplayId : DEFAULT_DISPLAY;
            mHandler.post(() -> {
                synchronized (ImfLock.class) {
                    showInputMethodPickerLocked(auxiliarySubtypeMode,
                            InputMethodManager.IM_PICKER_ENTRY_POINT_DEFAULT, displayId, userId);
                }
            });
        }
    }

    @IInputMethodManagerImpl.PermissionVerified(allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.WRITE_SECURE_SETTINGS})
    @Override
    public void showInputMethodPickerFromSystem(
            int auxiliarySubtypeMode, @IMPickerEntryPoint int entryPoint, int displayId) {
        mHandler.post(() -> {
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                showInputMethodPickerLocked(auxiliarySubtypeMode, entryPoint, displayId, userId);
            }
        });
    }

    @IInputMethodManagerImpl.PermissionVerified(allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.WRITE_SECURE_SETTINGS})
    @Override
    public void toggleInputMethodPickerFromSystem(
            int auxiliarySubtypeMode, @IMPickerEntryPoint int entryPoint, int displayId) {
        mHandler.post(() -> {
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                toggleInputMethodPickerLocked(auxiliarySubtypeMode, entryPoint, displayId, userId);
            }
        });
    }

    @IInputMethodManagerImpl.PermissionVerified(allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.WRITE_SECURE_SETTINGS})
    @Override
    public void hideInputMethodPickerFromSystem(int displayId) {
        mHandler.post(() -> {
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                hideInputMethodPickerLocked(displayId, userId);
            }
        });
    }

    /**
     * A test API for CTS to make sure that the input method menu is showing for the given user.
     *
     * @param userId the ID of the user to check the menu visibility for.
     */
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    public boolean isInputMethodPickerShownForTest(@UserIdInt int userId) {
        synchronized (ImfLock.class) {
            return mImeSwitcherMenu.isShowing(getUserData(userId));
        }
    }

    @IInputMethodManagerImpl.PermissionVerified(allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.WRITE_SECURE_SETTINGS})
    @Override
    public void onImeSwitchButtonClickFromSystem(int displayId) {
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
            final var userData = getUserData(userId);

            onImeSwitchButtonClickLocked(displayId, userData);
        }
    }

    /**
     * Handles a click on the IME switch button. Depending on the number of enabled IME subtypes,
     * this will either switch to the next IME/subtype, or show the input method picker dialog.
     *
     * @param displayId The ID of the display where the input method picker dialog should be shown.
     * @param userData  The data of the user for which to switch IMEs or show the picker dialog.
     */
    @BinderThread
    @GuardedBy("ImfLock.class")
    private void onImeSwitchButtonClickLocked(int displayId, @NonNull UserData userData) {
        final int userId = userData.mUserId;
        if (hasMultipleSubtypesForSwitcher(true /* nonAuxOnly */, userId)) {
            switchToNextInputMethodLocked(false /* onlyCurrentIme */, userData);
        } else {
            showInputMethodPickerFromSystem(
                    InputMethodManager.SHOW_IM_PICKER_MODE_INCLUDE_AUXILIARY_SUBTYPES,
                    InputMethodManager.IM_PICKER_ENTRY_POINT_DEFAULT, displayId);
        }
    }

    /**
     * A test API for CTS to check whether the IME Switcher button should be shown when the IME
     * is shown.
     */
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    public boolean shouldShowImeSwitcherButtonForTest() {
        final int callingUserId = UserHandle.getCallingUserId();
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdLocked(callingUserId);
            return shouldShowImeSwitcherButtonLocked(userId);
        }
    }

    @IInputMethodManagerImpl.PermissionVerified(allOf = {
            Manifest.permission.WRITE_SECURE_SETTINGS,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.STATUS_BAR_SERVICE,
    })
    @Override
    public void registerImeSwitcherMenu(@NonNull IImeSwitcherMenu imeSwitcherMenu) {
        if (!Flags.imeSwitcherMenuSystemui()) {
            return;
        }
        Objects.requireNonNull(imeSwitcherMenu, "imeSwitcherMenu must not be null");
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != UserHandle.USER_SYSTEM && mConcurrentMultiUserModeEnabled) {
            // Skip registration for non-system user since multi-registration is not supported.
            // TODO(b/477290989): remove skip logic with support of menu for concurrent multi-user.
            Slog.w(TAG, "Attempting to register IME Switcher Menu for non-system user");
            return;
        }
        synchronized (ImfLock.class) {
            if (mIImeSwitcherMenu != null) {
                throw new IllegalArgumentException("IME Switcher Menu already registered");
            }
            mIImeSwitcherMenu = imeSwitcherMenu;
            try {
                imeSwitcherMenu.registerListener(mImeSwitcherMenuListener);
                imeSwitcherMenu.asBinder().linkToDeath(mImeSwitcherMenuDeathRecipient,
                        0 /* flags */);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to register IME Switcher Menu listener", e);
            }
        }
    }

    @NonNull
    private static IllegalArgumentException getExceptionForUnknownImeId(
            @Nullable String imeId) {
        return new IllegalArgumentException("Unknown id: " + imeId);
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void setInputMethodAndSubtypeLocked(String id, @Nullable InputMethodSubtype subtype,
            @NonNull UserData userData) {
        final int callingUid = Binder.getCallingUid();
        final int userId = userData.mUserId;
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final InputMethodInfo imi = settings.getMethodMap().get(id);
        if (imi == null || !canCallerAccessInputMethod(
                imi.getPackageName(), callingUid, userId, settings)) {
            throw getExceptionForUnknownImeId(id);
        }
        final int subtypeIndex = subtype != null
                ? SubtypeUtils.getSubtypeIndexFromHashCode(imi, subtype.hashCode())
                : NOT_A_SUBTYPE_INDEX;
        setInputMethodWithSubtypeIndexLocked(id, subtypeIndex, userId);
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private boolean switchToPreviousInputMethodLocked(@NonNull UserData userData) {
        final int userId = userData.mUserId;
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final Pair<String, String> lastIme = settings.getLastInputMethodAndSubtype();
        final InputMethodInfo lastImi;
        if (lastIme != null) {
            lastImi = settings.getMethodMap().get(lastIme.first);
        } else {
            lastImi = null;
        }
        final var bindingController = userData.mBindingController;
        final var currentSubtype = bindingController.getCurrentSubtype();
        String targetLastImiId = null;
        int subtypeIndex = NOT_A_SUBTYPE_INDEX;
        if (lastIme != null && lastImi != null) {
            final boolean sameIme = lastImi.getId().equals(bindingController.getSelectedImeId());
            final int lastSubtypeHash = Integer.parseInt(lastIme.second);
            final int currentSubtypeHash = currentSubtype == null ? NOT_A_SUBTYPE_INDEX
                    : currentSubtype.hashCode();
            // If the last IME is the same as the current IME and the last subtype is not
            // defined, there is no need to switch to the last IME.
            if (!sameIme || lastSubtypeHash != currentSubtypeHash) {
                targetLastImiId = lastIme.first;
                subtypeIndex = SubtypeUtils.getSubtypeIndexFromHashCode(lastImi, lastSubtypeHash);
            }
        }

        if (TextUtils.isEmpty(targetLastImiId)
                && !InputMethodUtils.canAddToLastInputMethod(currentSubtype)) {
            // This is a safety net. If the currentSubtype can't be added to the history
            // and the framework couldn't find the last ime, we will make the last ime be
            // the most applicable enabled keyboard subtype of the system imes.
            final List<InputMethodInfo> enabled = settings.getEnabledInputMethodList();
            final int enabledCount = enabled.size();
            final String locale;
            if (currentSubtype != null
                    && !TextUtils.isEmpty(currentSubtype.getLocale())) {
                locale = currentSubtype.getLocale();
            } else {
                locale = SystemLocaleWrapper.get(userId).get(0).toString();
            }
            for (int i = 0; i < enabledCount; ++i) {
                final InputMethodInfo imi = enabled.get(i);
                if (imi.getSubtypeCount() > 0 && imi.isSystem()) {
                    InputMethodSubtype keyboardSubtype =
                            SubtypeUtils.findLastResortApplicableSubtype(
                                    SubtypeUtils.getSubtypes(imi),
                                    SubtypeUtils.SUBTYPE_MODE_KEYBOARD, locale, true);
                    if (keyboardSubtype != null) {
                        targetLastImiId = imi.getId();
                        subtypeIndex = SubtypeUtils.getSubtypeIndexFromHashCode(imi,
                                keyboardSubtype.hashCode());
                        if (keyboardSubtype.getLocale().equals(locale)) {
                            break;
                        }
                    }
                }
            }
        }

        if (!TextUtils.isEmpty(targetLastImiId)) {
            ProtoLog.v(IMMS_DEBUG, "Switch to: %s, %s, from: %s, %d", lastImi.getId(),
                    lastIme.second, bindingController.getSelectedImeId(), subtypeIndex);
            setInputMethodWithSubtypeIndexLocked(targetLastImiId, subtypeIndex, userId);
            return true;
        } else {
            return false;
        }
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private boolean switchToNextInputMethodLocked(boolean onlyCurrentIme,
            @NonNull UserData userData) {
        final var bindingController = userData.mBindingController;
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userData.mUserId);
        final var selectedImi = settings.getMethodMap().get(bindingController.getSelectedImeId());
        if (selectedImi == null) {
            return false;
        }
        final ImeSubtypeListItem nextSubtype = userData.mSwitchingController.getNext(selectedImi,
                bindingController.getCurrentSubtype(), onlyCurrentIme, false /* forHardware */,
                MODE_AUTO, true /* forward */);
        if (nextSubtype == null) {
            return false;
        }
        setInputMethodWithSubtypeIndexLocked(nextSubtype.mImi.getId(), nextSubtype.mSubtypeIndex,
                userData.mUserId);
        return true;
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private boolean shouldOfferSwitchingToNextInputMethodLocked(@NonNull UserData userData) {
        final var bindingController = userData.mBindingController;
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userData.mUserId);
        final var selectedImi = settings.getMethodMap().get(bindingController.getSelectedImeId());
        if (selectedImi == null) {
            return false;
        }
        final ImeSubtypeListItem nextSubtype = userData.mSwitchingController.getNext(selectedImi,
                bindingController.getCurrentSubtype(), false /* onlyCurrentIme */,
                false /* forHardware */, MODE_AUTO, true /* forward */);
        return nextSubtype != null;
    }

    @Override
    public InputMethodSubtype getLastInputMethodSubtype(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        synchronized (ImfLock.class) {
            return InputMethodSettingsRepository.get(userId).getLastInputMethodSubtype();
        }
    }

    @Override
    public void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes,
            @UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        final int callingUid = Binder.getCallingUid();

        // By this IPC call, only a process which shares the same uid with the IME can add
        // additional input method subtypes to the IME.
        if (TextUtils.isEmpty(imiId) || subtypes == null) return;
        final ArrayList<InputMethodSubtype> toBeAdded = new ArrayList<>();
        for (InputMethodSubtype subtype : subtypes) {
            if (!toBeAdded.contains(subtype)) {
                toBeAdded.add(subtype);
            } else {
                Slog.w(TAG, "Duplicated subtype definition found: "
                        + subtype.getLocale() + ", " + subtype.getMode());
            }
        }
        final var userData = getUserData(userId);
        synchronized (ImfLock.class) {
            if (!mSystemReady) {
                return;
            }

            final var additionalSubtypeMap = AdditionalSubtypeMapRepository.get(userId);
            final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
            final var newAdditionalSubtypeMap = settings.getNewAdditionalSubtypeMap(
                    imiId, toBeAdded, additionalSubtypeMap, mPackageManagerInternal, callingUid);
            if (additionalSubtypeMap != newAdditionalSubtypeMap) {
                AdditionalSubtypeMapRepository.putAndSave(userId, newAdditionalSubtypeMap,
                        settings.getMethodMap());
                final long ident = Binder.clearCallingIdentity();
                try {
                    final var methodMap = userData.mRawInputMethodMap.get().toInputMethodMap(
                            AdditionalSubtypeMapRepository.get(userId), DirectBootAwareness.AUTO,
                            userData.mIsUnlockingOrUnlocked.get());
                    final var newSettings = InputMethodSettings.create(methodMap, userId);
                    InputMethodSettingsRepository.put(userId, newSettings);
                    postInputMethodSettingUpdatedLocked(false /* resetDefaultEnabledIme */, userId);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    @Override
    public void setExplicitlyEnabledInputMethodSubtypes(String imeId,
            @NonNull int[] subtypeHashCodes, @UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        final int callingUid = Binder.getCallingUid();
        final ComponentName imeComponentName =
                imeId != null ? ComponentName.unflattenFromString(imeId) : null;
        if (imeComponentName == null || !InputMethodUtils.checkIfPackageBelongsToUid(
                mPackageManagerInternal, callingUid, imeComponentName.getPackageName())) {
            throw new SecurityException("Calling UID=" + callingUid + " does not belong to imeId="
                    + imeId);
        }
        Objects.requireNonNull(subtypeHashCodes, "subtypeHashCodes must not be null");

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (ImfLock.class) {
                final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
                if (!settings.setEnabledInputMethodSubtypes(imeId, subtypeHashCodes)) {
                    return;
                }
                // To avoid unnecessary "updateInputMethodsFromSettingsLocked" from happening.
                final var userData = getUserData(userId);
                userData.mLastEnabledInputMethodsStr = settings.getEnabledInputMethodsStr();
                updateInputMethodsFromSettingsLocked(false /* enabledChanged */, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * This is kept due to {@code @UnsupportedAppUsage} in
     * {@link InputMethodManager#getInputMethodWindowVisibleHeight()} and a dependency in
     * {@link InputMethodService#onCreate()}.
     * @return {@link WindowManagerInternal#getInputMethodWindowVisibleHeight(int)}
     *
     * @deprecated TODO(b/113914148): Check if we can remove this
     */
    @Override
    @Deprecated
    public int getInputMethodWindowVisibleHeight(@NonNull IInputMethodClient client) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getCallingUserId();
        return Binder.withCleanCallingIdentity(() -> {
            final int curDisplayId;
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdLocked(callingUserId);
                if (!canInteractWithImeLocked(callingUid, client,
                        "getInputMethodWindowVisibleHeight", null /* statsToken */, userId)) {
                    return 0;
                }
                final var bindingController = getInputMethodBindingController(userId);
                // This should probably use the caller's display id, but because this is unsupported
                // and maintained only for compatibility, there's no point in fixing it.
                curDisplayId = bindingController.getCurDisplayId();
            }
            return mWindowManagerInternal.getInputMethodWindowVisibleHeight(curDisplayId);
        });
    }

    @Override
    public void removeImeSurfaceFromWindow(@NonNull IBinder windowToken) {
        // No permission check, because we'll only execute the request if the calling window is
        // also the current IME client.
        mHandler.obtainMessage(MSG_REMOVE_IME_SURFACE_FROM_WINDOW, windowToken).sendToTarget();
    }

    private void registerDeviceListenerAndCheckStylusSupport() {
        final InputManager im = mContext.getSystemService(InputManager.class);
        final IntArray stylusIds = getStylusInputDeviceIds(im);
        if (stylusIds.size() > 0) {
            synchronized (ImfLock.class) {
                mStylusIds = new IntArray();
                mStylusIds.addAll(stylusIds);
            }
        }
        im.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
                InputDevice device = im.getInputDevice(deviceId);
                if (device != null && isStylusDevice(device)) {
                    add(deviceId);
                }
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                remove(deviceId);
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
                InputDevice device = im.getInputDevice(deviceId);
                if (device == null) {
                    return;
                }
                if (isStylusDevice(device)) {
                    add(deviceId);
                } else {
                    remove(deviceId);
                }
            }

            private void add(int deviceId) {
                synchronized (ImfLock.class) {
                    addStylusDeviceIdLocked(deviceId);
                }
            }

            private void remove(int deviceId) {
                synchronized (ImfLock.class) {
                    removeStylusDeviceIdLocked(deviceId);
                }
            }
        }, mHandler);
    }

    @GuardedBy("ImfLock.class")
    private void addStylusDeviceIdLocked(int deviceId) {
        if (mStylusIds == null) {
            mStylusIds = new IntArray();
        } else if (mStylusIds.indexOf(deviceId) != -1) {
            return;
        }
        Slog.d(TAG, "New Stylus deviceId" + deviceId + " added.");
        mStylusIds.add(deviceId);
        // a new Stylus is detected. If IME supports handwriting, and we don't have
        // handwriting initialized, lets do it now.
        final var bindingController = getInputMethodBindingController(mCurrentImeUserId);
        if (mHwController.getCurrentRequestId().isEmpty()
                && bindingController.getSupportsStylusHandwriting()) {
            scheduleResetStylusHandwriting();
        }
    }

    private void removeStylusDeviceIdLocked(int deviceId) {
        if (mStylusIds == null || mStylusIds.size() == 0) {
            return;
        }
        int index;
        if ((index = mStylusIds.indexOf(deviceId)) != -1) {
            mStylusIds.remove(index);
            Slog.d(TAG, "Stylus deviceId: " + deviceId + " removed.");
        }
        if (mStylusIds.size() == 0) {
            // no more supported stylus(es) in system.
            mHwController.reset();
            scheduleRemoveStylusHandwritingWindow();
        }
    }

    private static boolean isStylusDevice(InputDevice inputDevice) {
        return inputDevice.supportsSource(InputDevice.SOURCE_STYLUS)
                || inputDevice.supportsSource(InputDevice.SOURCE_BLUETOOTH_STYLUS);
    }

    private static boolean isFingerDevice(InputDevice inputDevice) {
        return inputDevice.supportsSource(InputDevice.SOURCE_TOUCHSCREEN);
    }

    @GuardedBy("ImfLock.class")
    private boolean hasSupportedStylusLocked() {
        return mStylusIds != null && mStylusIds.size() != 0;
    }

    /**
     * Helper method that adds a virtual stylus id for next handwriting session test if
     * a stylus deviceId is not already registered on device.
     */
    @BinderThread
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public void addVirtualStylusIdForTestSession(IInputMethodClient client) {
        final int uid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(uid);
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdLocked(callingUserId);
            if (!canInteractWithImeLocked(uid, client, "addVirtualStylusIdForTestSession",
                    null /* statsToken */, userId)) {
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                ProtoLog.v(IMMS_DEBUG, "Adding virtual stylus id for session");
                addStylusDeviceIdLocked(VIRTUAL_STYLUS_ID_FOR_TEST);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /**
     * Helper method to set a stylus idle-timeout after which handwriting {@code InkWindow}
     * will be removed.
     *
     * @param timeout to set in milliseconds. To reset to default, use a value <= zero
     */
    @BinderThread
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public void setStylusWindowIdleTimeoutForTest(
            IInputMethodClient client, @DurationMillisLong long timeout) {
        final int uid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(uid);
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdLocked(callingUserId);
            if (!canInteractWithImeLocked(uid, client, "setStylusWindowIdleTimeoutForTest",
                    null /* statsToken */, userId)) {
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                ProtoLog.v(IMMS_DEBUG, "Setting stylus window idle timeout");
                final IInputMethodInvoker curIme = getInputMethodBindingController(
                        mCurrentImeUserId).getCurIme();
                if (curIme != null) {
                    curIme.setStylusWindowIdleTimeoutForTest(timeout);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    @IInputMethodManagerImpl.PermissionVerified(allOf = {Manifest.permission.WRITE_SECURE_SETTINGS,
            Manifest.permission.TEST_INPUT_METHOD,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public boolean enableInputMethodForTesting(@NonNull String imeId,
            @CanBeALL @CanBeCURRENT @UserIdInt int userId) {
        synchronized (ImfLock.class) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return getEnabledInputMethodsControllerLocked().
                        enableInputMethodForTesting(imeId, userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    @IInputMethodManagerImpl.PermissionVerified(allOf = {Manifest.permission.WRITE_SECURE_SETTINGS,
            Manifest.permission.TEST_INPUT_METHOD,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public boolean disableInputMethodForTesting(@NonNull String imeId,
            @CanBeALL @CanBeCURRENT @UserIdInt int userId) {
        synchronized (ImfLock.class) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return getEnabledInputMethodsControllerLocked()
                        .disableInputMethodForTesting(imeId, userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    @IInputMethodManagerImpl.PermissionVerified(allOf = {Manifest.permission.WRITE_SECURE_SETTINGS,
            Manifest.permission.TEST_INPUT_METHOD,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public boolean setInputMethodForTesting(@NonNull String imeId,
            @CanBeALL @CanBeCURRENT @UserIdInt int userId) {
        synchronized (ImfLock.class) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return getEnabledInputMethodsControllerLocked()
                        .setInputMethodForTesting(imeId, userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    @IInputMethodManagerImpl.PermissionVerified(allOf = {Manifest.permission.WRITE_SECURE_SETTINGS,
            Manifest.permission.TEST_INPUT_METHOD,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public void resetInputMethodsForTesting(@CanBeALL @CanBeCURRENT @UserIdInt int userId) {
        synchronized (ImfLock.class) {
            final long identity = Binder.clearCallingIdentity();
            try {
                getEnabledInputMethodsControllerLocked().resetInputMethodsForTesting(userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @BinderThread
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public void setAllowedImesByPolicyForTest(
            @NonNull IInputMethodClient client, @Nullable List<String> allowedPackages) {
        final int uid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(uid);
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdLocked(callingUserId);
            final long ident = Binder.clearCallingIdentity();
            try {
                ProtoLog.v(IMMS_DEBUG, "Setting mAllowedImesByPolicyForTest");
                if (allowedPackages == null) {
                    mAllowedImesByPolicyForTest = null;
                    return;
                }
                List<InputMethodInfo> imes = InputMethodManagerInternal
                        .get().getInputMethodListAsUser(userId);
                mAllowedImesByPolicyForTest = new ArrayList<>(allowedPackages.size());
                for (InputMethodInfo ime : imes) {
                    if (allowedPackages.contains(ime.getPackageName())) {
                        mAllowedImesByPolicyForTest.add(ime);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void removeVirtualStylusIdForTestSessionLocked() {
        removeStylusDeviceIdLocked(VIRTUAL_STYLUS_ID_FOR_TEST);
    }

    private static IntArray getStylusInputDeviceIds(InputManager im) {
        IntArray stylusIds = new IntArray();
        for (int id : im.getInputDeviceIds()) {
            InputDevice device = im.getInputDevice(id);
            if (device != null && device.isEnabled() && isStylusDevice(device)) {
                stylusIds.add(id);
            }
        }

        return stylusIds;
    }

    @BinderThread
    @Override
    public boolean isImeTraceEnabled() {
        return ImeTracing.getInstance().isEnabled();
    }

    @BinderThread
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void startImeTrace() {
        ImeTracing.getInstance().startTrace(null /* printwriter */);
        synchronized (ImfLock.class) {
            mClientController.forAllClients(c -> c.mClient.setImeTraceEnabled(true /* enabled */));
        }
    }

    @BinderThread
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void stopImeTrace() {
        ImeTracing.getInstance().stopTrace(null /* printwriter */);
        synchronized (ImfLock.class) {
            mClientController.forAllClients(c -> c.mClient.setImeTraceEnabled(false /* enabled */));
        }
    }

    // TODO(b/356239178): Make dump proto multi-user aware.
    private void dumpDebug(ProtoOutputStream proto, long fieldId) {
        synchronized (ImfLock.class) {
            final int userId = mCurrentImeUserId;
            final var userData = getUserData(userId);
            final var visibilityStateComputer = userData.mVisibilityStateComputer;
            final long token = proto.start(fieldId);
            proto.write(CUR_CLIENT, Objects.toString(userData.mCurClient));
            userData.mImeBindingState.dumpDebug(proto, mWindowManagerInternal);
            proto.write(LAST_IME_TARGET_WINDOW_NAME, mWindowManagerInternal.getWindowName(
                    visibilityStateComputer.getLastImeTargetWindow()));
            proto.write(CUR_FOCUSED_WINDOW_SOFT_INPUT_MODE, InputMethodDebug.softInputModeToString(
                    userData.mImeBindingState.mFocusedWindowSoftInputMode));
            if (userData.mCurEditorInfo != null) {
                userData.mCurEditorInfo.dumpDebug(proto, CUR_ATTRIBUTE);
            }
            visibilityStateComputer.dumpDebug(proto, fieldId);
            userData.mBindingController.dumpDebug(proto);
            proto.write(IN_FULLSCREEN_MODE, userData.mInFullscreenMode);
            proto.write(SYSTEM_READY, mSystemReady);
            proto.write(BOUND_TO_METHOD, userData.mBoundToMethod);
            proto.write(IS_INTERACTIVE, mIsInteractive);
            proto.write(CONCURRENT_MULTI_USER_MODE_ENABLED, mConcurrentMultiUserModeEnabled);
            proto.write(PREVENT_IME_STARTUP_UNLESS_TEXT_EDITOR, mPreventImeStartupUnlessTextEditor);
            proto.end(token);
        }
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void notifyUserActionLocked(@NonNull UserData userData) {
        ProtoLog.v(IMMS_DEBUG, "Got the notification of a user action.");
        final var bindingController = userData.mBindingController;
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userData.mUserId);
        final var selectedImi = settings.getMethodMap().get(bindingController.getSelectedImeId());
        if (selectedImi != null) {
            userData.mSwitchingController.onUserAction(selectedImi,
                    bindingController.getCurrentSubtype());
        }
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void resetStylusHandwritingLocked(int requestId) {
        final OptionalInt curRequest = mHwController.getCurrentRequestId();
        if (curRequest.isEmpty() || curRequest.getAsInt() != requestId) {
            Slog.w(TAG, "IME requested to finish handwriting with a mismatched requestId: "
                    + requestId);
        }
        removeVirtualStylusIdForTestSessionLocked();
        scheduleResetStylusHandwriting();
    }

    @GuardedBy("ImfLock.class")
    private void setInputMethodWithSubtypeIndexLocked(String id, int subtypeIndex,
            @UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        if (settings.getMethodMap().get(id) != null
                && settings.getEnabledInputMethodListWithFilter(
                        (info) -> info.getId().equals(id)).isEmpty()) {
            throw new IllegalStateException("Requested IME is not enabled: " + id);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            setInputMethodLocked(id, subtypeIndex, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Called right after {@link IInputMethod#showSoftInput} or {@link IInputMethod#hideSoftInput}.
     */
    @GuardedBy("ImfLock.class")
    void onShowHideSoftInputRequested(boolean show, IBinder requestImeToken,
            @SoftInputShowHideReason int reason, @Nullable ImeTracker.Token statsToken,
            @UserIdInt int userId) {
        final var userData = getUserData(userId);
        final IBinder requestToken = userData.mVisibilityStateComputer
                .getWindowTokenFrom(requestImeToken, userId);
        final WindowManagerInternal.ImeTargetInfo info =
                mWindowManagerInternal.onToggleImeRequested(
                        show, userData.mImeBindingState.mFocusedWindow, requestToken,
                        userData.mBindingController.getCurDisplayId());
        mSoftInputShowHideHistory.addEntry(new SoftInputShowHideHistory.Entry(
                userData.mImeBindingState.mFocusedWindowClient,
                userData.mImeBindingState.mFocusedWindowEditorInfo, info.mFocusedWindowName,
                userData.mImeBindingState.mFocusedWindowSoftInputMode, reason,
                userData.mInFullscreenMode, info.mRequestWindowName, info.mImeLayeringTargetName,
                info.mImeInputTargetName, info.mImeControlTargetName, info.mImeParentName, userId));

        if (statsToken != null) {
            mImeTrackerService.onImmsUpdate(statsToken, info.mRequestWindowName);
        }
    }

    @VisibleForTesting
    @BinderThread
    @GuardedBy("ImfLock.class")
    void setMyImeVisibilityLocked(
            boolean visible, @NonNull ImeTracker.Token statsToken, @NonNull UserData userData) {
        if (userData.mImeBindingState.mFocusedWindowClient != null
                && userData.mImeBindingState.mFocusedWindowClient != userData.mCurClient) {
            Slog.e(
                    TAG,
                    "Clients differ in setMyImeVisibilityLocked; visible: "
                            + visible
                            + ", mCurClient: "
                            + userData.mCurClient
                            + ", mFocusedWindowClient: "
                            + userData.mImeBindingState.mFocusedWindowClient);
        }

        if (Flags.setSelfVisibilityOnlyOnce()) {
            setImeVisibilityOnFocusedWindowClient(visible, userData, statsToken);
            return;
        }

        if (userData.mCurClient != null) {
            userData.mCurClient.mClient.setImeVisibility(visible, statsToken);
            setImeVisibilityOnFocusedWindowClient(visible, userData, statsToken);
        } else {
            ImeTracker.forLogging()
                    .onFailed(statsToken, ImeTracker.PHASE_SERVER_SET_VISIBILITY_ON_FOCUSED_WINDOW);
        }
    }

    @GuardedBy("ImfLock.class")
    private void setEnabledSessionLocked(SessionState session, @NonNull UserData userData) {
        if (userData.mEnabledSession != session) {
            if (userData.mEnabledSession != null && userData.mEnabledSession.mSession != null) {
                ProtoLog.v(IMMS_DEBUG, "Disabling: " + userData.mEnabledSession);
                userData.mEnabledSession.mIme.setSessionEnabled(userData.mEnabledSession.mSession,
                        false /* enabled */);
            }
            userData.mEnabledSession = session;
            if (userData.mEnabledSession != null && userData.mEnabledSession.mSession != null) {
                ProtoLog.v(IMMS_DEBUG, "Enabling: " + userData.mEnabledSession);
                userData.mEnabledSession.mIme.setSessionEnabled(userData.mEnabledSession.mSession,
                        true /* enabled */);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void setEnabledSessionForAccessibilityLocked(
            @NonNull SparseArray<AccessibilitySessionState> accessibilitySessions,
            @NonNull UserData userData) {
        if (accessibilitySessions.contentEquals(userData.mEnabledAccessibilitySessions)) {
            return;
        }

        setEnabledSessionForAccessibilityInternalLocked(
                /* sessionsToUpdate= */ userData.mEnabledAccessibilitySessions,
                /* sessionsExcluded= */ accessibilitySessions,
                /* enabled= */ false);
        setEnabledSessionForAccessibilityInternalLocked(
                /* sessionsToUpdate= */ accessibilitySessions,
                /* sessionsExcluded= */ userData.mEnabledAccessibilitySessions,
                /* enabled= */ true);

        userData.mEnabledAccessibilitySessions.clear();
        for (int i = 0; i < accessibilitySessions.size(); i++) {
            userData.mEnabledAccessibilitySessions.put(
                    accessibilitySessions.keyAt(i), accessibilitySessions.valueAt(i));
        }
    }

    @GuardedBy("ImfLock.class")
    private void setEnabledSessionForAccessibilityInternalLocked(
            @NonNull SparseArray<AccessibilitySessionState> sessionsToUpdate,
            @NonNull SparseArray<AccessibilitySessionState> sessionsExcluded,
            boolean enabled) {
        final int size = sessionsToUpdate.size();
        final var sessionsToNotify = new SparseArray<IAccessibilityInputMethodSession>(size);
        for (int i = 0; i < size; i++) {
            final AccessibilitySessionState sessionState = sessionsToUpdate.valueAt(i);
            if (sessionState == null) {
                continue;
            }
            final int a11yServiceId = sessionsToUpdate.keyAt(i);
            if (sessionsExcluded.get(a11yServiceId) != sessionState) {
                sessionsToNotify.append(a11yServiceId, sessionState.mSession);
            }
        }
        if (sessionsToNotify.size() > 0) {
            AccessibilityManagerInternal.get().setImeSessionEnabled(sessionsToNotify, enabled);
        }
    }

    @GuardedBy("ImfLock.class")
    private void showInputMethodPickerLocked(int auxiliarySubtypeMode,
            @IMPickerEntryPoint int entryPoint, int displayId, @UserIdInt int userId) {
        final var userData = getUserData(userId);
        final boolean showAuxSubtypes;
        switch (auxiliarySubtypeMode) {
            // This is undocumented so far, but IMM#showInputMethodPicker() has been
            // implemented so that auxiliary subtypes will be excluded when the soft
            // keyboard is invisible.
            case InputMethodManager.SHOW_IM_PICKER_MODE_AUTO ->
                    showAuxSubtypes = userData.mVisibilityStateComputer.isInputShown();
            case InputMethodManager.SHOW_IM_PICKER_MODE_INCLUDE_AUXILIARY_SUBTYPES ->
                    showAuxSubtypes = true;
            case InputMethodManager.SHOW_IM_PICKER_MODE_EXCLUDE_AUXILIARY_SUBTYPES ->
                    showAuxSubtypes = false;
            default -> {
                Slog.e(TAG, "Unknown subtype picker mode=" + auxiliarySubtypeMode);
                return;
            }
        }
        final boolean isScreenLocked = mWindowManagerInternal.isKeyguardLocked()
                && mWindowManagerInternal.isKeyguardSecure(userId);
        final boolean includeAuxiliary = showAuxSubtypes && !isScreenLocked;
        if (DEBUG && isScreenLocked && showAuxSubtypes) {
            Slog.w(TAG, "Auxiliary subtypes are not allowed to be shown in lock screen.");
        }
        final List<ImeSubtypeListItem> items = userData.mSwitchingController
                .getItems(true /* forMenu */, includeAuxiliary);
        if (items.isEmpty()) {
            Slog.w(TAG, "Show switching menu failed, items is empty,"
                    + " showAuxSubtypes: " + showAuxSubtypes
                    + " isScreenLocked: " + isScreenLocked
                    + " userId: " + userId);
            return;
        }

        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final String selectedImeId = settings.getSelectedInputMethod();
        int selectedSubtypeIndex = settings.getSelectedInputMethodSubtypeIndex(selectedImeId);
        ProtoLog.v(IMMS_DEBUG, "Show IME switcher menu, showAuxSubtypes=%b displayId=%d"
                        + " selectedImeId=%s selectedSubtypeIndex=%d", showAuxSubtypes, displayId,
                selectedImeId, selectedSubtypeIndex);

        if (selectedSubtypeIndex == NOT_A_SUBTYPE_INDEX) {
            // TODO(b/351124299): Check if this fallback logic is still necessary.
            final var bindingController = getInputMethodBindingController(userId);
            final var curSubtype = bindingController.getCurrentInputMethodSubtype();
            if (curSubtype != null) {
                final var selectedImi = settings.getMethodMap()
                        .get(bindingController.getSelectedImeId());
                selectedSubtypeIndex = SubtypeUtils.getSubtypeIndexFromHashCode(selectedImi,
                        curSubtype.hashCode());
            }
        }

        mImeSwitcherMenu.show(items, selectedImeId, selectedSubtypeIndex, isScreenLocked,
                entryPoint, displayId, userId);
    }

    @GuardedBy("ImfLock.class")
    private void toggleInputMethodPickerLocked(int auxiliarySubtypeMode,
            @IMPickerEntryPoint int entryPoint, int displayId, @UserIdInt int userId) {
        if (mImeSwitcherMenu.isShowing(getUserData(userId))) {
            hideInputMethodPickerLocked(displayId, userId);
        } else {
            showInputMethodPickerLocked(auxiliarySubtypeMode, entryPoint, displayId, userId);
        }
    }

    @GuardedBy("ImfLock.class")
    private void hideInputMethodPickerLocked(int displayId, @UserIdInt int userId) {
        mImeSwitcherMenu.hide(displayId, userId);
    }

    @SuppressWarnings("unchecked")
    @UiThread
    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_HIDE_INPUT_METHOD: {
                @SoftInputShowHideReason final int reason = msg.arg1;
                final int originatingDisplayId = msg.arg2;
                synchronized (ImfLock.class) {
                    final int userId = resolveImeUserIdFromDisplayIdLocked(originatingDisplayId);
                    final var userData = getUserData(userId);
                    final var statsToken = createStatsTokenForFocusedClient(false /* show */,
                            reason, userId);
                    setImeVisibilityOnFocusedWindowClient(false, userData, statsToken);
                }
                return true;
            }
            case MSG_REMOVE_IME_SURFACE: {
                synchronized (ImfLock.class) {
                    // TODO(b/305849394): Needs to figure out what to do where for background users.
                    final int userId = mCurrentImeUserId;
                    final var userData = getUserData(userId);
                    try {
                        if (userData.mEnabledSession != null
                                && userData.mEnabledSession.mSession != null
                                && !isShowRequestedForCurrentWindow(userId)) {
                            userData.mEnabledSession.mSession.removeImeSurface();
                        }
                    } catch (RemoteException ignored) {
                    }
                }
                return true;
            }
            case MSG_REMOVE_IME_SURFACE_FROM_WINDOW: {
                IBinder windowToken = (IBinder) msg.obj;
                synchronized (ImfLock.class) {
                    final int userId = resolveImeUserIdFromWindowLocked(windowToken);
                    final var userData = getUserData(userId);
                    try {
                        if (windowToken == userData.mImeBindingState.mFocusedWindow
                                && userData.mEnabledSession != null
                                && userData.mEnabledSession.mSession != null) {
                            userData.mEnabledSession.mSession.removeImeSurface();
                        }
                    } catch (RemoteException ignored) {
                    }
                }
                return true;
            }

            // ---------------------------------------------------------

            case MSG_SET_INTERACTIVE:
                handleSetInteractive(msg.arg1 != 0);
                return true;

            // --------------------------------------------------------------
            case MSG_DISPATCH_ON_INPUT_METHOD_LIST_UPDATED: {
                final int userId = msg.arg1;
                final List<InputMethodInfo> imes = (List<InputMethodInfo>) msg.obj;
                mInputMethodListListeners.forEach(
                        listener -> listener.onInputMethodListUpdated(imes, userId));
                return true;
            }

            // ---------------------------------------------------------------
            case MSG_NOTIFY_IME_UID_TO_AUDIO_SERVICE: {
                if (mAudioManagerInternal == null) {
                    mAudioManagerInternal = LocalServices.getService(AudioManagerInternal.class);
                }
                if (mAudioManagerInternal != null) {
                    mAudioManagerInternal.setInputMethodServiceUid(msg.arg1 /* uid */);
                }
                return true;
            }

            case MSG_RESET_HANDWRITING: {
                synchronized (ImfLock.class) {
                    final var bindingController =
                            getInputMethodBindingController(mCurrentImeUserId);
                    if (bindingController.getSupportsStylusHandwriting()
                            && bindingController.getCurIme() != null
                            && hasSupportedStylusLocked()) {
                        Slog.d(TAG, "Initializing Handwriting Spy");
                        mHwController.initializeHandwritingSpy(bindingController.getCurDisplayId());
                    } else {
                        mHwController.reset();
                    }
                }
                return true;
            }
            case MSG_PREPARE_HANDWRITING_DELEGATION:
                synchronized (ImfLock.class) {
                    int userId = msg.arg1;
                    final var pair = (Pair<String, String>) msg.obj;
                    String delegate = pair.first;
                    String delegator = pair.second;
                    mHwController.prepareStylusHandwritingDelegation(
                            userId, delegate, delegator, /* connectionless= */ false);
                }
                return true;
            case MSG_START_HANDWRITING:
                final var handwritingRequest = (HandwritingRequest) msg.obj;
                synchronized (ImfLock.class) {
                    final var userData = handwritingRequest.userData;
                    final var bindingController = userData.mBindingController;
                    final IInputMethodInvoker curIme = bindingController.getCurIme();
                    if (curIme == null || userData.mImeBindingState.mFocusedWindow == null) {
                        return true;
                    }
                    final HandwritingModeController.HandwritingSession session =
                            mHwController.startHandwritingSession(
                                    handwritingRequest.requestId,
                                    handwritingRequest.pid,
                                    bindingController.getCurImeUid(),
                                    userData.mImeBindingState.mFocusedWindow);
                    if (session == null) {
                        Slog.e(TAG,
                                "Failed to start handwriting session for requestId: " + msg.arg1);
                        return true;
                    }

                    if (!curIme.startStylusHandwriting(session.getRequestId(),
                            session.getHandwritingChannel(), session.getRecordedEvents())) {
                        // When failed to issue IPCs, re-initialize handwriting state.
                        Slog.w(TAG, "Resetting handwriting mode.");
                        scheduleResetStylusHandwriting();
                    }
                }
                return true;
            case MSG_FINISH_HANDWRITING:
                synchronized (ImfLock.class) {
                    final IInputMethodInvoker curIme = getInputMethodBindingController(
                            mCurrentImeUserId).getCurIme();
                    if (curIme != null && mHwController.getCurrentRequestId().isPresent()) {
                        curIme.finishStylusHandwriting();
                    }
                }
                return true;
            case MSG_REMOVE_HANDWRITING_WINDOW:
                synchronized (ImfLock.class) {
                    final IInputMethodInvoker curIme = getInputMethodBindingController(
                            mCurrentImeUserId).getCurIme();
                    if (curIme != null) {
                        curIme.removeStylusHandwritingWindow();
                    }
                }
                return true;
        }
        return false;
    }

    private record HandwritingRequest(int requestId, int pid, @NonNull UserData userData) { }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void onStylusHandwritingReadyLocked(int requestId, int pid,
            @NonNull UserData userData) {
        mHandler.obtainMessage(MSG_START_HANDWRITING,
                new HandwritingRequest(requestId, pid, userData)).sendToTarget();
    }

    private void handleSetInteractive(final boolean interactive) {
        synchronized (ImfLock.class) {
            // TODO(b/305849394): Support multiple IMEs.
            final int userId = mCurrentImeUserId;
            final var userData = getUserData(userId);
            final var bindingController = userData.mBindingController;
            mIsInteractive = interactive;
            updateSystemUiLocked(
                    interactive ? bindingController.getImeWindowVis() : 0,
                    bindingController.getBackDisposition(), userId);
            // Inform the current client of the change in active status
            if (userData.mCurClient == null) {
                return;
            }
            if (mImePlatformCompatUtils.shouldUseSetInteractiveProtocol(
                    bindingController.getCurImeUid())) {
                // Apply IME screenshot visibility before notifying the client, as it could dismiss
                // the IME.
                final var visibilityStateComputer = userData.mVisibilityStateComputer;
                final Boolean showScreenshot = visibilityStateComputer.shouldShowImeScreenshot(
                        userData.mImeBindingState.mFocusedWindow, interactive);
                if (showScreenshot != null) {
                    applyImeScreenshotVisibility(
                            userData.mImeBindingState.mFocusedWindow, showScreenshot, userId);
                }
                // Eligible IME processes use new "setInteractive" protocol.
                userData.mCurClient.mClient.setInteractive(mIsInteractive,
                        userData.mInFullscreenMode);
            } else {
                // Legacy IME processes continue using legacy "setActive" protocol.
                userData.mCurClient.mClient.setActive(mIsInteractive, userData.mInFullscreenMode);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private boolean chooseNewDefaultIMELocked(
            @Nullable List<InputMethodInfo> allowedImes, @UserIdInt int userId) {
        final InputMethodInfo imi = InputMethodInfoUtils.getMostApplicableDefaultIME(allowedImes);
        if (imi != null) {
            ProtoLog.v(IMMS_DEBUG, "New default IME was selected: %s", imi.getId());
            resetSelectedInputMethodAndSubtypeLocked(imi.getId(), userId);
            return true;
        }

        return false;
    }

    @GuardedBy("ImfLock.class")
    private void chooseNewIMELocked(
            @NonNull InputMethodInfo imi, @UserIdInt int userId) {
        ProtoLog.v(IMMS_DEBUG, "New DP-allowed IME was selected: %s", imi.getId());
        resetSelectedInputMethodAndSubtypeLocked(imi.getId(), userId);
    }

    @GuardedBy("ImfLock.class")
    boolean chooseNewDefaultIMELocked(@UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final InputMethodInfo imi = InputMethodInfoUtils.getMostApplicableDefaultIME(
                settings.getEnabledInputMethodList());
        if (imi != null) {
            ProtoLog.v(IMMS_DEBUG, "New default IME was selected: %s", imi.getId());
            resetSelectedInputMethodAndSubtypeLocked(imi.getId(), userId);
            return true;
        }

        return false;
    }

    @NonNull
    static RawInputMethodMap queryRawInputMethodServiceMap(Context context, @UserIdInt int userId) {
        final Context userAwareContext = context.getUserId() == userId
                ? context
                : context.createContextAsUser(UserHandle.of(userId), 0 /* flags */);

        final int flags = PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

        // Beware that package visibility filtering will be enforced based on the effective calling
        // identity (Binder.getCallingUid()), but our use case always expect Binder.getCallingUid()
        // to return Process.SYSTEM_UID here. The actual filtering is implemented separately with
        // canCallerAccessInputMethod().
        // TODO(b/343108534): Use PackageManagerInternal#queryIntentServices() to pass SYSTEM_UID.
        final List<ResolveInfo> services = userAwareContext.getPackageManager().queryIntentServices(
                new Intent(InputMethod.SERVICE_INTERFACE),
                PackageManager.ResolveInfoFlags.of(flags));

        // Note: This is a temporary solution for Bug 261723412.
        // TODO(b/339761278): Remove this workaround after switching to InputMethodInfoSafeList.
        final List<String> enabledInputMethodList =
                InputMethodUtils.getEnabledInputMethodIdsForFiltering(context, userId);

        return filterInputMethodServices(enabledInputMethodList, userAwareContext, services);
    }

    @NonNull
    static RawInputMethodMap filterInputMethodServices(
            List<String> enabledInputMethodList, Context userAwareContext,
            List<ResolveInfo> services) {
        final ArrayMap<String, Integer> imiPackageCount = new ArrayMap<>();
        final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>(services.size());

        for (int i = 0; i < services.size(); ++i) {
            ResolveInfo ri = services.get(i);
            ServiceInfo si = ri.serviceInfo;
            final String imeId = InputMethodInfo.computeId(ri);
            if (!android.Manifest.permission.BIND_INPUT_METHOD.equals(si.permission)) {
                Slog.w(TAG, "Skipping an input method " + imeId
                        + ": it does not require the permission "
                        + android.Manifest.permission.BIND_INPUT_METHOD);
                continue;
            }

            ProtoLog.v(IMMS_DEBUG, "Checking %s", imeId);

            // The number of service is at most MAX_IMES_PER_PACKAGE for each package unless
            // it's a system app or it's explicitly enabled.
            final String packageName = si.packageName;
            final int imiCountInPkg = imiPackageCount.getOrDefault(packageName, 0);
            if (!si.applicationInfo.isSystemApp() && !enabledInputMethodList.contains(imeId)
                    && imiCountInPkg >= InputMethodInfo.MAX_IMES_PER_PACKAGE) {
                Slog.w(TAG,
                        "Skipping an input method " + imeId + ": too many services in a package.");
                continue;
            }

            try {
                final InputMethodInfo imi = new InputMethodInfo(userAwareContext, ri,
                        Collections.emptyList());
                if (imi.isVrOnly()) {
                    continue;  // Skip VR-only IME, which isn't supported for now.
                }
                methodMap.put(imi.getId(), imi);
                imiPackageCount.put(packageName, imiCountInPkg + 1);
                ProtoLog.v(IMMS_DEBUG, "Found an input method %s", imi);
            } catch (Exception e) {
                Slog.wtf(TAG, "Unable to load input method " + imeId, e);
            }
        }
        return RawInputMethodMap.of(methodMap);
    }

    @GuardedBy("ImfLock.class")
    void postInputMethodSettingUpdatedLocked(boolean resetDefaultEnabledIme,
            @UserIdInt int userId) {
        ProtoLog.v(IMMS_DEBUG, "--- re-buildInputMethodList reset = %b"
                + " \n ------ caller=%s", resetDefaultEnabledIme, Debug.getCallers(10));
        if (!mSystemReady) {
            Slog.e(TAG, "buildInputMethodListLocked is not allowed until system is ready");
            return;
        }

        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);

        boolean reenableMinimumNonAuxSystemImes = false;
        // TODO: The following code should find better place to live.
        if (!resetDefaultEnabledIme) {
            boolean enabledImeFound = false;
            boolean enabledNonAuxImeFound = false;
            final List<InputMethodInfo> enabledImes = settings.getEnabledInputMethodList();
            final int numImes = enabledImes.size();
            for (int i = 0; i < numImes; ++i) {
                final InputMethodInfo imi = enabledImes.get(i);
                if (settings.getMethodMap().containsKey(imi.getId())) {
                    enabledImeFound = true;
                    if (!imi.isAuxiliaryIme()) {
                        enabledNonAuxImeFound = true;
                        break;
                    }
                }
            }
            if (!enabledImeFound) {
                ProtoLog.v(IMMS_DEBUG,
                        "All the enabled IMEs are gone. Reset default enabled IMEs.");
                resetDefaultEnabledIme = true;
                resetSelectedInputMethodAndSubtypeLocked("", userId);
            } else if (!enabledNonAuxImeFound) {
                ProtoLog.v(IMMS_DEBUG, "All the enabled non-Aux IMEs are gone. Do partial reset.");
                reenableMinimumNonAuxSystemImes = true;
            }
        }

        if (resetDefaultEnabledIme || reenableMinimumNonAuxSystemImes) {
            reenableImesLocked(reenableMinimumNonAuxSystemImes, settings);
        }

        final String selectedImeId = settings.getSelectedInputMethod();
        if (!TextUtils.isEmpty(selectedImeId)) {
            if (!settings.getMethodMap().containsKey(selectedImeId)) {
                Slog.w(TAG, "Selected IME is uninstalled. Choose new default IME.");
                if (chooseNewDefaultIMELocked(userId)) {
                    updateInputMethodsFromSettingsLocked(true, userId);
                }
            } else {
                // Double check that the default IME is certainly enabled.
                setInputMethodEnabledLocked(selectedImeId, true, userId);
            }
        }

        updateDefaultVoiceImeIfNeededLocked(userId);

        final var userData = getUserData(userId);
        userData.mSwitchingController.update(mContext, settings);

        sendOnNavButtonFlagsChangedLocked(userData);

        // Notify InputMethodListListeners of the new installed InputMethods.
        final List<InputMethodInfo> inputMethodList = settings.getMethodList();
        mHandler.obtainMessage(MSG_DISPATCH_ON_INPUT_METHOD_LIST_UPDATED,
                userId, 0 /* unused */, inputMethodList).sendToTarget();
    }

    @GuardedBy("ImfLock.class")
    void sendOnNavButtonFlagsChangedLocked(@NonNull UserData userData) {
        final IInputMethodInvoker curIme = userData.mBindingController.getCurIme();
        if (curIme != null) {
            curIme.onNavButtonFlagsChanged(getInputMethodNavButtonFlagsLocked(userData));
        }
    }

    @WorkerThread
    private void onUpdateResourceOverlay(@UserIdInt int userId) {
        final int profileParentId = mUserManagerInternal.getProfileParentId(userId);
        final boolean value =
                InputMethodDrawsNavBarResourceMonitor.evaluate(mContext, profileParentId);
        final var profileUserIds = mUserManagerInternal.getProfileIds(profileParentId, false);
        final ArrayList<UserData> updatedUsers = new ArrayList<>();
        for (int profileUserId : profileUserIds) {
            final var userData = getUserData(profileUserId);
            userData.mImeDrawsNavBar.set(value);
            updatedUsers.add(userData);
        }
        synchronized (ImfLock.class) {
            updatedUsers.forEach(this::sendOnNavButtonFlagsChangedLocked);
        }
    }

    @GuardedBy("ImfLock.class")
    private void updateDefaultVoiceImeIfNeededLocked(@UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final String systemSpeechRecognizer =
                mContext.getString(com.android.internal.R.string.config_systemSpeechRecognizer);
        final String currentDefaultVoiceImeId = settings.getDefaultVoiceInputMethod();
        final InputMethodInfo newSystemVoiceIme = InputMethodInfoUtils.chooseSystemVoiceIme(
                settings.getMethodMap(), systemSpeechRecognizer, currentDefaultVoiceImeId);
        if (newSystemVoiceIme == null) {
            ProtoLog.v(IMMS_DEBUG, "Found no valid default Voice IME. If the user is still locked,"
                    + " this may be expected.");
            // Clear DEFAULT_VOICE_INPUT_METHOD when necessary.  Note that InputMethodSettings
            // does not update the actual Secure Settings until the user is unlocked.
            if (!TextUtils.isEmpty(currentDefaultVoiceImeId)) {
                settings.putDefaultVoiceInputMethod("");
                // We don't support disabling the voice ime when a package is removed from the
                // config.
            }
            return;
        }
        if (TextUtils.equals(currentDefaultVoiceImeId, newSystemVoiceIme.getId())) {
            return;
        }
        ProtoLog.v(IMMS_DEBUG, "Enabling the default Voice IME: %s userId: %d", newSystemVoiceIme,
                userId);
        setInputMethodEnabledLocked(newSystemVoiceIme.getId(), true, userId);
        settings.putDefaultVoiceInputMethod(newSystemVoiceIme.getId());
    }

    private void reenableImesLocked(
            boolean reenableMinimumNonAuxSystemImesOnly, @NonNull InputMethodSettings settings) {
        final ArrayList<InputMethodInfo> defaultEnabledImes =
                InputMethodInfoUtils.getDefaultEnabledImes(mContext, settings.getMethodList(),
                        reenableMinimumNonAuxSystemImesOnly);
        final int numImes = defaultEnabledImes.size();
        for (int i = 0; i < numImes; ++i) {
            final InputMethodInfo imi = defaultEnabledImes.get(i);
            ProtoLog.v(IMMS_DEBUG, "--- enable ime = %s", imi);
            setInputMethodEnabledLocked(imi.getId(), true, settings.getUserId());
        }
    }

    @GuardedBy("ImfLock.class")
    private void enableAllowedImeLocked(
            @NonNull InputMethodInfo allowedIme, @NonNull InputMethodSettings settings) {
        ProtoLog.v(IMMS_DEBUG, "--- enable DP-allowed ime = %s", allowedIme);
        setInputMethodEnabledLocked(allowedIme.getId(), true /* enabled */, settings.getUserId());
    }

    /**
     * Enable or disable the given IME by updating {@link Settings.Secure#ENABLED_INPUT_METHODS}.
     *
     * @param id      ID of the IME is to be manipulated. It is OK to pass IME ID that is currently
     *                not recognized by the system
     * @param enabled {@code true} if {@code id} needs to be enabled
     * @param userId  the user ID to be updated
     * @return {@code true} if the IME was previously enabled
     */
    @GuardedBy("ImfLock.class")
    boolean setInputMethodEnabledLocked(
            @NonNull String id, boolean enabled, @UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        if (enabled) {
            final String enabledImeIdsStr = settings.getEnabledInputMethodsStr();
            final String newEnabledImeIdsStr = InputMethodUtils.concatEnabledImeIds(
                    enabledImeIdsStr, id);
            if (TextUtils.equals(enabledImeIdsStr, newEnabledImeIdsStr)) {
                // We are enabling this input method, but it is already enabled.
                // Nothing to do. The previous state was enabled.
                return true;
            }
            settings.putEnabledInputMethodsStr(newEnabledImeIdsStr);
            // Previous state was disabled.
            return false;
        } else {
            final List<Pair<String, ArrayList<String>>> enabledInputMethodsList = settings
                    .getEnabledInputMethodsAndSubtypeList();
            StringBuilder builder = new StringBuilder();
            if (settings.buildAndPutEnabledInputMethodsStrRemovingId(
                    builder, enabledInputMethodsList, id)) {
                final var bindingController = getInputMethodBindingController(userId);
                if (bindingController.getDeviceId() == DEVICE_ID_DEFAULT) {
                    // Disabled input method is currently selected, switch to another one.
                    final String selectedImeId = settings.getSelectedInputMethod();
                    if (id.equals(selectedImeId) && !chooseNewDefaultIMELocked(userId)) {
                        Slog.i(TAG, "Can't find new IME, unsetting the current input method.");
                        resetSelectedInputMethodAndSubtypeLocked("", userId);
                    }
                } else if (id.equals(settings.getSelectedDefaultDeviceInputMethod())) {
                    // Disabled default device IME while using a virtual device one, choose a
                    // new default one but only update the settings.
                    InputMethodInfo newDefaultIme =
                            InputMethodInfoUtils.getMostApplicableDefaultIME(
                                    settings.getEnabledInputMethodList());
                    settings.putSelectedDefaultDeviceInputMethod(
                            newDefaultIme == null ? null : newDefaultIme.getId());
                }
                // Previous state was enabled.
                return true;
            } else {
                // We are disabling the input method but it is already disabled.
                // Nothing to do.  The previous state was disabled.
                return false;
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void setSelectedInputMethodAndSubtypeLocked(InputMethodInfo imi, int subtypeIndex,
            boolean setSubtypeOnly, @UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final var bindingController = getInputMethodBindingController(userId);
        settings.saveCurrentInputMethodAndSubtypeToHistory(bindingController.getSelectedImeId(),
                bindingController.getCurrentSubtype());

        // Set Subtype here
        final int newSubtypeHashcode;
        final InputMethodSubtype newSubtype;
        if (imi == null || subtypeIndex < 0) {
            newSubtypeHashcode = INVALID_SUBTYPE_HASHCODE;
            newSubtype = null;
        } else {
            if (subtypeIndex < imi.getSubtypeCount()) {
                InputMethodSubtype subtype = imi.getSubtypeAt(subtypeIndex);
                newSubtypeHashcode = subtype.hashCode();
                newSubtype = subtype;
            } else {
                // TODO(b/347093491): Probably this should be determined from the new subtype.
                newSubtypeHashcode = INVALID_SUBTYPE_HASHCODE;
                // If the subtype is not specified, choose the most applicable one
                // TODO(b/347083680): The method below has questionable behaviors.
                newSubtype = bindingController.getCurrentInputMethodSubtype();
            }
        }
        settings.putSelectedSubtype(newSubtypeHashcode);
        bindingController.setCurrentSubtype(newSubtype);
        notifyInputMethodSubtypeChangedLocked(settings.getUserId(), imi, newSubtype);

        if (!setSubtypeOnly) {
            // Set InputMethod here
            settings.putSelectedInputMethod(imi != null ? imi.getId() : "");
        }

        getUserData(userId).mSwitchingController.onInputMethodSubtypeChanged();
        if (Flags.imeSwitcherMenuSystemui()) {
            final var imeId = imi != null ? imi.getId() : null;
            final int index = newSubtype != null
                    ? SubtypeUtils.getSubtypeIndexFromHashCode(imi, newSubtype.hashCode())
                    : NOT_A_SUBTYPE_INDEX;
            final var settingsIntent = imi != null
                    ? imi.createImeLanguageSettingsActivityIntent() : null;
            mImeSwitcherMenu.onImeAndSubtypeChanged(imeId, index, settingsIntent, userId);
        }
    }

    @GuardedBy("ImfLock.class")
    void resetSelectedInputMethodAndSubtypeLocked(String newDefaultIme,
            @UserIdInt int userId) {
        final var bindingController = getInputMethodBindingController(userId);
        bindingController.setSelectedDisplayId(INVALID_DISPLAY);
        bindingController.setDeviceId(DEVICE_ID_DEFAULT);

        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        settings.putSelectedDefaultDeviceInputMethod(null);

        final InputMethodInfo imi = settings.getMethodMap().get(newDefaultIme);
        int lastSubtypeIndex = NOT_A_SUBTYPE_INDEX;
        // newDefaultIme is empty when there is no candidate for the selected IME.
        if (imi != null && !TextUtils.isEmpty(newDefaultIme)) {
            String subtypeHashCode = settings.getLastSubtypeForInputMethod(newDefaultIme);
            if (subtypeHashCode != null) {
                try {
                    lastSubtypeIndex = SubtypeUtils.getSubtypeIndexFromHashCode(imi,
                            Integer.parseInt(subtypeHashCode));
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "HashCode for subtype looks broken: " + subtypeHashCode, e);
                }
            }
        }
        setSelectedInputMethodAndSubtypeLocked(imi, lastSubtypeIndex, false, userId);
    }

    /**
     * Gets the current subtype of this input method.
     *
     * @param userId User ID to be queried about
     * @return the current {@link InputMethodSubtype} for the specified user
     */
    @Nullable
    @Override
    public InputMethodSubtype getCurrentInputMethodSubtype(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        synchronized (ImfLock.class) {
            final var bindingController = getInputMethodBindingController(userId);
            // TODO(b/347083680): The method below has questionable behaviors.
            return bindingController.getCurrentInputMethodSubtype();
        }
    }

    @GuardedBy("ImfLock.class")
    boolean switchToInputMethodLocked(@NonNull String imeId, int subtypeIndex,
            @UserIdInt int userId) {
        final var settings = InputMethodSettingsRepository.get(userId);
        final var enabledImes = settings.getEnabledInputMethodList();
        if (!CollectionUtils.any(enabledImes, imi -> imi.getId().equals(imeId))) {
            return false; // IME is not found or not enabled.
        }
        setInputMethodLocked(imeId, subtypeIndex, userId);
        return true;
    }

    /**
     * Filter the access to the input method by rules of the package visibility. Return {@code true}
     * if the given input method is the currently selected one or visible to the caller.
     *
     * @param targetPkgName the package name of input method to check
     * @param callingUid    the caller that is going to access the input method
     * @param userId        the user ID where the input method resides
     * @param settings      the input method settings under the given user ID
     * @return {@code true} if caller is able to access the input method
     */
    private boolean canCallerAccessInputMethod(@NonNull String targetPkgName, int callingUid,
            @UserIdInt int userId, @NonNull InputMethodSettings settings) {
        final String selectedImeId = settings.getSelectedInputMethod();
        final ComponentName selectedImeComponent = selectedImeId != null
                ? InputMethodUtils.convertIdToComponentName(selectedImeId) : null;
        if (selectedImeComponent != null
                && selectedImeComponent.getPackageName().equals(targetPkgName)) {
            return true;
        }
        final boolean canAccess = !mPackageManagerInternal.filterAppAccess(
                targetPkgName, callingUid, userId);
        if (DEBUG && !canAccess) {
            Slog.d(TAG, "Input method " + targetPkgName
                    + " is not visible to the caller " + callingUid);
        }
        return canAccess;
    }

    @GuardedBy("ImfLock.class")
    private void switchKeyboardLayoutLocked(int direction, @NonNull UserData userData) {
        final int userId = userData.mUserId;
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);

        final var bindingController = userData.mBindingController;
        final InputMethodInfo selectedImi = settings.getMethodMap()
                .get(bindingController.getSelectedImeId());
        if (selectedImi == null) {
            return;
        }
        final var currentSubtype = bindingController.getCurrentSubtype();
        final var nextItem = userData.mSwitchingController.getNext(selectedImi, currentSubtype,
                false /* onlyCurrentIme */, true /* forHardware */, MODE_AUTO,
                direction > 0 /* forward */);
        if (nextItem == null) {
            Slog.i(TAG, "Hardware keyboard switching shortcut,"
                    + " next input method and subtype not found");
            return;
        }

        final var nextSubtype = nextItem.mSubtypeIndex > NOT_A_SUBTYPE_INDEX
                ? nextItem.mImi.getSubtypeAt(nextItem.mSubtypeIndex) : null;

        // TODO(b/476928567): nextImi should be equivalent to nextItem.mImi in most cases, but this
        //  is not guaranteed.
        final InputMethodInfo nextImi = settings.getMethodMap().get(nextItem.mImi.getId());
        if (nextImi == null) {
            Slog.e(TAG, "Switching controller's next IMI " + nextItem.mImi.getId()
                    + " not found in settings");
            return;
        }

        final int subtypeCount = nextImi.getSubtypeCount();
        if (subtypeCount == 0) {
            if (nextSubtype == null) {
                setInputMethodLocked(nextImi.getId(), NOT_A_SUBTYPE_INDEX, userId);
            } else {
                Slog.e(TAG, "Switching controller's next IMI " + nextItem.mImi.getId()
                        + " has 0 subtypes, but expected subtype " + nextSubtype);
            }
            return;
        }

        for (int i = 0; i < subtypeCount; ++i) {
            final var subtype = nextImi.getSubtypeAt(i);
            if (Objects.equals(nextSubtype, subtype)) {
                setInputMethodLocked(nextImi.getId(), i, userId);
                return;
            }
        }

        Slog.e(TAG, "Switching controller's next IMI " + nextItem.mImi.getId()
                + " does not contain subtype " + nextSubtype);
    }


    /**
     * Applies the IME screenshot visibility on the given IME target window.
     *
     * @param imeTarget the token of the IME target window.
     * @param show      whether to show or remove the screenshot.
     * @param userId    the ID of the user to apply the screenshot visibility for.
     */
    @GuardedBy("ImfLock.class")
    void applyImeScreenshotVisibility(IBinder imeTarget, boolean show, @UserIdInt int userId) {
        final int displayId = getInputMethodBindingController(userId).getSelectedDisplayId();
        if (show) {
            showImeScreenshot(imeTarget, displayId, userId);
        } else {
            removeImeScreenshot(imeTarget, displayId, userId);
        }
    }

    /**
     * Shows the IME screenshot and attaches it to the given IME target window.
     *
     * @param imeTarget the token of the IME target window.
     * @param displayId the ID of the display to show the screenshot on.
     * @param userId    the ID of the user to show the screenshot for.
     */
    @VisibleForTesting
    @GuardedBy("ImfLock.class")
    void showImeScreenshot(IBinder imeTarget, int displayId, @UserIdInt int userId) {
        if (mWindowManagerInternal.showImeScreenshot(imeTarget, displayId)) {
            onShowHideSoftInputRequested(false /* show */, imeTarget,
                    SoftInputShowHideReason.SHOW_IME_SCREENSHOT_FROM_IMMS, null /* statsToken */,
                    userId);
        }
    }

    /**
     * Removes the IME screenshot from the given display.
     *
     * @param imeTarget the token of the IME target window.
     * @param displayId the ID of the display to remove the screenshot from.
     * @param userId    the ID of the user to remove the screenshot for.
     */
    @VisibleForTesting
    @GuardedBy("ImfLock.class")
    void removeImeScreenshot(IBinder imeTarget, int displayId, @UserIdInt int userId) {
        if (mWindowManagerInternal.removeImeScreenshot(displayId)) {
            onShowHideSoftInputRequested(false /* show */, imeTarget,
                    SoftInputShowHideReason.REMOVE_IME_SCREENSHOT_FROM_IMMS, null /* statsToken */,
                    userId);
        }
    }

    private void publishLocalService() {
        LocalServices.addService(InputMethodManagerInternal.class, mInputMethodManagerInternal);
    }

    private final class LocalServiceImpl extends InputMethodManagerInternal {

        @ImfLockFree
        @Override
        public void setInteractive(boolean interactive) {
            // Do everything in handler so as not to block the caller.
            mHandler.obtainMessage(MSG_SET_INTERACTIVE, interactive ? 1 : 0, 0).sendToTarget();
        }

        @ImfLockFree
        @Override
        public void hideInputMethod(@SoftInputShowHideReason int reason,
                int originatingDisplayId) {
            mHandler.removeMessages(MSG_HIDE_INPUT_METHOD);
            mHandler.obtainMessage(MSG_HIDE_INPUT_METHOD, reason, originatingDisplayId)
                    .sendToTarget();
        }

        @ImfLockFree
        @NonNull
        @Override
        public List<InputMethodInfo> getInputMethodListAsUser(@UserIdInt int userId) {
            return getInputMethodListInternal(userId, DirectBootAwareness.AUTO, Process.SYSTEM_UID);
        }

        @ImfLockFree
        @NonNull
        @Override
        public List<InputMethodInfo> getEnabledInputMethodListAsUser(@UserIdInt int userId) {
            return getEnabledInputMethodListInternal(userId, Process.SYSTEM_UID);
        }

        @ImfLockFree
        @NonNull
        @Override
        public List<InputMethodSubtype> getEnabledInputMethodSubtypeListAsUser(
                String imiId, boolean allowsImplicitlyEnabledSubtypes, @UserIdInt int userId) {
            return getEnabledInputMethodSubtypeListInternal(imiId, allowsImplicitlyEnabledSubtypes,
                    userId, Process.SYSTEM_UID);
        }

        @Override
        public void onCreateInlineSuggestionsRequest(@UserIdInt int userId,
                @NonNull InlineSuggestionsRequestInfo requestInfo,
                @NonNull InlineSuggestionsRequestCallback cb) {
            // Get the device global touch exploration state before lock to avoid deadlock.
            final boolean touchExplorationEnabled = AccessibilityManagerInternal.get()
                    .isTouchExplorationEnabled(userId);

            synchronized (ImfLock.class) {
                getInputMethodBindingController(userId).onCreateInlineSuggestionsRequest(
                        requestInfo, cb, touchExplorationEnabled);
            }
        }

        @Override
        public boolean switchToInputMethod(@NonNull String imeId, int subtypeIndex,
                @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                return switchToInputMethodLocked(imeId, subtypeIndex, userId);
            }
        }

        @Override
        public boolean setInputMethodEnabled(String imeId, boolean enabled, @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
                if (!settings.getMethodMap().containsKey(imeId)) {
                    return false; // IME is not found.
                }
                setInputMethodEnabledLocked(imeId, enabled, userId);
                return true;
            }
        }

        // String is not constant because it's using TextUtils.formatSimple
        @SuppressWarnings("CompileTimeConstant")
        @Override
        public void setVirtualDeviceInputMethodForAllUsers(int deviceId, @Nullable String imeId) {
            Preconditions.checkArgument(deviceId != DEVICE_ID_DEFAULT,
                    TextUtils.formatSimple("DeviceId %d is not a virtual device id.", deviceId));
            synchronized (ImfLock.class) {
                if (imeId == null) {
                    mVirtualDeviceMethodMap.remove(deviceId);
                } else if (mVirtualDeviceMethodMap.contains(deviceId)) {
                    throw new IllegalArgumentException("Virtual device " + deviceId
                            + " already has a custom input method component");
                } else {
                    mVirtualDeviceMethodMap.put(deviceId, imeId);
                }
            }
        }

        @ImfLockFree
        @Override
        public void registerInputMethodListListener(InputMethodListListener listener) {
            mInputMethodListListeners.addIfAbsent(listener);
        }

        @Override
        public boolean transferTouchFocusToImeWindow(@NonNull IBinder sourceInputToken,
                int displayId, @UserIdInt int userId) {
            //TODO(b/150843766): Check if Input Token is valid.
            final IBinder curHostInputToken;
            synchronized (ImfLock.class) {
                final var bindingController = getInputMethodBindingController(userId);
                if (displayId != bindingController.getCurDisplayId()) {
                    return false;
                }
                curHostInputToken = bindingController.getCurHostInputToken();
                if (curHostInputToken == null) {
                    return false;
                }
            }
            return mInputManagerInternal.transferTouchGesture(
                    sourceInputToken, curHostInputToken, /* transferEntireGesture */ false);
        }

        @Override
        public void reportImeControl(@Nullable IBinder windowToken) {
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdFromWindowLocked(windowToken);
                final var userData = getUserData(userId);
                if (userData.mImeBindingState.mFocusedWindow != windowToken) {
                    // A perceptible value was set for the focused window, but it is no longer in
                    // control, so we reset the perceptible for the window passed as argument.
                    mFocusedWindowPerceptible.put(windowToken, true);
                }
            }
        }

        @Override
        public void onImeParentChanged(int displayId) {
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                final var userData = getUserData(userId);
                // Hide the IME method menu only when the IME parent is changed by the
                // input target changed, in case seeing the dialog dismiss flickering during
                // the next focused window starting the input connection.
                final var visibilityStateComputer = userData.mVisibilityStateComputer;
                if (visibilityStateComputer.getLastImeTargetWindow()
                        != userData.mImeBindingState.mFocusedWindow) {
                    final var bindingController = getInputMethodBindingController(userId);
                    mImeSwitcherMenu.hide(bindingController.getCurDisplayId(), userId);
                }
            }
        }

        @ImfLockFree
        @Override
        public void removeImeSurface(int displayId) {
            mHandler.obtainMessage(MSG_REMOVE_IME_SURFACE).sendToTarget();
        }

        @Override
        public void setHasVisibleImeLayeringOverlay(boolean hasVisibleOverlay, int displayId) {
            synchronized (ImfLock.class) {
                final var userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                getUserData(userId).mVisibilityStateComputer.setHasVisibleImeLayeringOverlay(
                        hasVisibleOverlay);
            }
        }

        @Override
        public void onImeInputTargetVisibilityChanged(@NonNull IBinder imeInputTarget,
                boolean visibleAndNotRemoved, int displayId) {
            synchronized (ImfLock.class) {
                final var userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                getUserData(userId).mVisibilityStateComputer.onImeInputTargetVisibilityChanged(
                        imeInputTarget, visibleAndNotRemoved);
            }
        }

        @ImfLockFree
        @Override
        public void updateImeWindowStatus(boolean disableImeIcon, int displayId) {
            mHandler.post(() -> {
                synchronized (ImfLock.class) {
                    updateImeWindowStatusLocked(disableImeIcon, displayId);
                }
            });
        }

        /**
         * TODO(b/481908434): remove after IME Switcher menu is migrated completely to System UI
         * (b/460776726).
         */
        @Override
        public void updateShouldShowImeSwitcherButton(int displayId, @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                updateSystemUiLocked(userId);
                final var userData = getUserData(userId);
                sendOnNavButtonFlagsChangedLocked(userData);
            }
        }

        @Override
        public void onSessionForAccessibilityCreated(int accessibilityConnectionId,
                @NonNull IAccessibilityInputMethodSession session, @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                final var userData = getUserData(userId);
                // TODO(b/305829876): Implement user ID verification
                if (userData.mCurClient != null) {
                    clearClientSessionForAccessibilityLocked(userData.mCurClient,
                            accessibilityConnectionId);
                    userData.mCurClient.mAccessibilitySessions.put(
                            accessibilityConnectionId,
                            new AccessibilitySessionState(userData.mCurClient,
                                    accessibilityConnectionId,
                                    session));

                    attachNewAccessibilityLocked(StartInputReason.SESSION_CREATED_BY_ACCESSIBILITY,
                            true, userId);

                    final SessionState sessionState = userData.mCurClient.mCurSession;
                    final IInputMethodSession imeSession = sessionState == null
                            ? null : sessionState.mSession;
                    final SparseArray<IAccessibilityInputMethodSession>
                            accessibilityInputMethodSessions =
                            createAccessibilityInputMethodSessions(
                                    userData.mCurClient.mAccessibilitySessions);
                    final var bindingController = userData.mBindingController;
                    final InputBindResult res = new InputBindResult(
                            InputBindResult.ResultCode.SUCCESS_WITH_ACCESSIBILITY_SESSION,
                            imeSession, accessibilityInputMethodSessions, /* channel */ null,
                            bindingController.getCurImeId(), bindingController.getSequenceNumber(),
                            /* isInputMethodSuppressingSpellChecker */ false);
                    userData.mCurClient.mClient.onBindAccessibilityService(res,
                            accessibilityConnectionId);
                }
            }
        }

        @Override
        public void unbindAccessibilityFromCurrentClient(int accessibilityConnectionId,
                @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                final var userData = getUserData(userId);
                final var bindingController = userData.mBindingController;
                // TODO(b/305829876): Implement user ID verification
                if (userData.mCurClient != null) {
                    ProtoLog.v(IMMS_DEBUG, "unbindAccessibilityFromCurrentClientLocked: client=%s",
                            userData.mCurClient.mClient.asBinder());
                    // A11yManagerService unbinds the disabled accessibility service. We don't need
                    // to do it here.
                    userData.mCurClient.mClient.onUnbindAccessibilityService(
                            bindingController.getSequenceNumber(),
                            accessibilityConnectionId);
                }
                // We only have sessions when we bound to an input method. Remove this session
                // from all clients.
                if (bindingController.getCurIme() != null) {
                    // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
                    @SuppressWarnings("GuardedBy") Consumer<ClientState> clearClientSession = c ->
                            clearClientSessionForAccessibilityLocked(c, accessibilityConnectionId);
                    mClientController.forAllClients(clearClientSession);

                    AccessibilitySessionState session = userData.mEnabledAccessibilitySessions.get(
                            accessibilityConnectionId);
                    if (session != null) {
                        finishSessionForAccessibilityLocked(session);
                        userData.mEnabledAccessibilitySessions.remove(accessibilityConnectionId);
                    }
                }
            }
        }

        @ImfLockFree
        @Override
        public void maybeFinishStylusHandwriting() {
            mHandler.removeMessages(MSG_FINISH_HANDWRITING);
            mHandler.obtainMessage(MSG_FINISH_HANDWRITING).sendToTarget();
        }

        @Override
        public void onSwitchKeyboardLayoutShortcut(int direction, int displayId,
                IBinder targetWindowToken) {
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                switchKeyboardLayoutLocked(direction, getUserData(userId));
            }
        }

        @Nullable
        @Override
        public ComputerControlInputConnectionData getComputerControlInputConnectionData(
                @UserIdInt int userId, int displayId) {
            synchronized (ImfLock.class) {
                final UserData userData = getUserData(userId);
                return userData.mComputerControlInputConnectionMap.get(displayId);
            }
        }
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    @Nullable
    private IInputContentUriToken createInputContentUriTokenLocked(@NonNull Uri contentUri,
            @NonNull String packageName, @NonNull UserData userData) {
        Objects.requireNonNull(packageName, "packageName must not be null");
        Objects.requireNonNull(contentUri, "contentUri must not be null");
        final String contentUriScheme = contentUri.getScheme();
        if (!"content".equals(contentUriScheme)) {
            throw new InvalidParameterException("contentUri must have content scheme");
        }

        final int uid = Binder.getCallingUid();
        if (userData.mBindingController.getSelectedImeId() == null) {
            return null;
        }
        // We cannot simply distinguish a bad IME that reports an arbitrary package name from
        // an unfortunate IME whose internal state is already obsolete due to the asynchronous
        // nature of our system.  Let's compare it with our internal record.
        final var curPackageName = userData.mCurEditorInfo != null
                ? userData.mCurEditorInfo.packageName : null;
        if (!TextUtils.equals(curPackageName, packageName)) {
            Slog.e(TAG, "Ignoring createInputContentUriTokenLocked mCurEditorInfo.packageName="
                    + curPackageName + " packageName=" + packageName);
            return null;
        }
        // This user ID can never be spoofed.
        final int appUserId = UserHandle.getUserId(userData.mCurClient.mUid);
        // This user ID may be invalid if "contentUri" embedded an invalid user ID.
        final int contentUriOwnerUserId = ContentProvider.getUserIdFromUri(contentUri,
                userData.mUserId);
        final Uri contentUriWithoutUserId = ContentProvider.getUriWithoutUserId(contentUri);
        // Note: InputContentUriTokenHandler.take() checks whether the IME (specified by "uid")
        // actually has the right to grant a read permission for "contentUriWithoutUserId" that
        // is claimed to belong to "contentUriOwnerUserId".  For example, specifying random
        // content URI and/or contentUriOwnerUserId just results in a SecurityException thrown
        // from InputContentUriTokenHandler.take() and can never be allowed beyond what is
        // actually allowed to "uid", which is guaranteed to be the IME's one.
        return new InputContentUriTokenHandler(contentUriWithoutUserId, uid,
                packageName, contentUriOwnerUserId, appUserId);
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void reportFullscreenModeLocked(boolean fullscreen, @NonNull UserData userData) {
        if (userData.mCurClient != null) {
            userData.mInFullscreenMode = fullscreen;
            userData.mCurClient.mClient.reportFullscreenMode(fullscreen);
        }
    }

    private final PriorityDump.PriorityDumper mPriorityDumper = new PriorityDump.PriorityDumper() {
        /**
         * {@inheritDoc}
         */
        @BinderThread
        @Override
        public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args,
                boolean asProto) {
            if (asProto) {
                dumpAsProtoNoCheck(fd);
            } else {
                dumpAsStringNoCheck(fd, pw, args, true /* isCritical */);
            }
        }

        /**
         * {@inheritDoc}
         */
        @BinderThread
        @Override
        public void dumpHigh(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            dumpNormal(fd, pw, args, asProto);
        }

        /**
         * {@inheritDoc}
         */
        @BinderThread
        @Override
        public void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            if (asProto) {
                dumpAsProtoNoCheck(fd);
            } else {
                dumpAsStringNoCheck(fd, pw, args, false /* isCritical */);
            }
        }

        /**
         * {@inheritDoc}
         */
        @BinderThread
        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            dumpNormal(fd, pw, args, asProto);
        }

        @BinderThread
        private void dumpAsProtoNoCheck(FileDescriptor fd) {
            final ProtoOutputStream proto = new ProtoOutputStream(fd);
            // Dump in the format of an ImeTracing trace with a single entry.
            final long magicNumber =
                    ((long) InputMethodManagerServiceTraceFileProto.MAGIC_NUMBER_H << 32)
                            | InputMethodManagerServiceTraceFileProto.MAGIC_NUMBER_L;
            final long timeOffsetNs = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
                    - SystemClock.elapsedRealtimeNanos();
            proto.write(InputMethodManagerServiceTraceFileProto.MAGIC_NUMBER,
                    magicNumber);
            proto.write(InputMethodManagerServiceTraceFileProto.REAL_TO_ELAPSED_TIME_OFFSET_NANOS,
                    timeOffsetNs);
            final long token = proto.start(InputMethodManagerServiceTraceFileProto.ENTRY);
            proto.write(InputMethodManagerServiceTraceProto.ELAPSED_REALTIME_NANOS,
                    SystemClock.elapsedRealtimeNanos());
            proto.write(InputMethodManagerServiceTraceProto.WHERE,
                    "InputMethodManagerService.mPriorityDumper#dumpAsProtoNoCheck");
            dumpDebug(proto, InputMethodManagerServiceTraceProto.INPUT_METHOD_MANAGER_SERVICE);
            proto.end(token);
            proto.flush();
        }
    };

    @BinderThread
    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        PriorityDump.dump(mPriorityDumper, fd, pw, args);
    }

    @BinderThread
    private void dumpAsStringNoCheck(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @NonNull String[] args, boolean isCritical) {
        final int argUserId = parseUserIdFromDumpArgs(args);
        final Printer p = new PrintWriterPrinter(pw);
        p.println("Input Method Manager Service state:");
        p.println("  mSystemReady=" + mSystemReady);
        p.println("  mInteractive=" + mIsInteractive);
        p.println("  mConcurrentMultiUserModeEnabled=" + mConcurrentMultiUserModeEnabled);
        p.println("  mPreventImeStartupUnlessTextEditor=" + mPreventImeStartupUnlessTextEditor);
        final int currentImeUserId;
        synchronized (ImfLock.class) {
            currentImeUserId = mCurrentImeUserId;
            p.println("  mCurrentImeUserId=" + currentImeUserId);
            p.println("  mStylusIds=" + (mStylusIds != null
                    ? Arrays.toString(mStylusIds.toArray()) : ""));
        }
        p.println("  mMenuController:");
        mImeSwitcherMenu.dump(p, "    ");
        dumpClientController(p);
        dumpUserRepository(p);

        // TODO(b/365868861): Make StartInputHistory and ImeTracker multi-user aware.
        synchronized (ImfLock.class) {
            p.println("  mStartInputHistory:");
            mStartInputHistory.dump(pw, "    ");

            p.println("  mSoftInputShowHideHistory:");
            mSoftInputShowHideHistory.dump(pw, "    ");
        }

        p.println("  mImeTrackerService#History:");
        mImeTrackerService.dump(pw, "    ");

        if (mConcurrentMultiUserModeEnabled && argUserId == UserHandle.USER_NULL) {
            mUserDataRepository.forAllUserData(
                    u -> dumpAsStringNoCheckForUser(u, fd, pw, args, isCritical));
        } else {
            final int userId = argUserId != UserHandle.USER_NULL ? argUserId : currentImeUserId;
            final var userData = getUserData(userId);
            dumpAsStringNoCheckForUser(userData, fd, pw, args, isCritical);
        }
    }

    @UserIdInt
    private static int parseUserIdFromDumpArgs(@NonNull String[] args) {
        final int userIdx = Arrays.binarySearch(args, "--user");
        if (userIdx == -1 || userIdx == args.length - 1) {
            return UserHandle.USER_NULL;
        }
        return Integer.parseInt(args[userIdx + 1]);
    }

    // TODO(b/356239178): Update dump format output to better group per-user info.
    @BinderThread
    private void dumpAsStringNoCheckForUser(@NonNull UserData userData, @NonNull FileDescriptor fd,
            @NonNull PrintWriter pw, @NonNull String[] args, boolean isCritical) {
        final Printer p = new PrintWriterPrinter(pw);
        final ClientState client;
        final IInputMethodInvoker ime;
        p.println("  UserId=" + userData.mUserId);
        synchronized (ImfLock.class) {
            final var bindingController = userData.mBindingController;
            client = userData.mCurClient;
            ime = bindingController.getCurIme();
            p.println("    mBindingController:");
            bindingController.dump(pw, "      ");
            p.println("    mCurClient=" + client);
            p.println("    mFocusedWindowPerceptible=" + mFocusedWindowPerceptible);
            p.println("    mImeBindingState:");
            userData.mImeBindingState.dump(p, "      ");
            p.println("    mBoundToMethod=" + userData.mBoundToMethod);
            p.println("    mEnabledSession=" + userData.mEnabledSession);
            p.println("    mVisibilityStateComputer:");
            userData.mVisibilityStateComputer.dump(pw, "      ");
            p.println("    mInFullscreenMode=" + userData.mInFullscreenMode);
            p.println("    mEnabledA11ySessions=" + userData.mEnabledAccessibilitySessions);

            final var settings = InputMethodSettingsRepository.get(userData.mUserId);
            final List<InputMethodInfo> methodList = settings.getMethodList();
            final int numImes = methodList.size();
            p.println("    Input Methods:");
            for (int i = 0; i < numImes; i++) {
                final InputMethodInfo imi = methodList.get(i);
                p.println("      InputMethod #" + i + ":");
                imi.dump(p, "        ");
            }
        }

        // Exit here for critical dump, as remaining sections require IPCs to other processes.
        if (isCritical) {
            return;
        }

        p.println("");
        if (client != null) {
            pw.flush();
            try {
                TransferPipe.dumpAsync(client.mClient.asBinder(), fd, args);
            } catch (IOException | RemoteException e) {
                p.println("Failed to dump input method client: " + e);
            }
        } else {
            p.println("No input method client.");
        }
        synchronized (ImfLock.class) {
            final var focusedWindowClient = userData.mImeBindingState.mFocusedWindowClient;
            if (focusedWindowClient != null && client != focusedWindowClient) {
                p.println("");
                p.println("Warning: Current input method client doesn't match the last focused"
                        + " window.");
                p.println("Dumping input method client in the last focused window just in case.");
                p.println("");
                pw.flush();
                try {
                    TransferPipe.dumpAsync(focusedWindowClient.mClient.asBinder(), fd, args);
                } catch (IOException | RemoteException e) {
                    p.println("Failed to dump input method client in focused window: " + e);
                }
            }
        }

        p.println("");
        if (ime != null) {
            pw.flush();
            try {
                TransferPipe.dumpAsync(ime.asBinder(), fd, args);
            } catch (IOException | RemoteException e) {
                p.println("Failed to dump input method service: " + e);
            }
        } else {
            p.println("No input method service.");
        }
    }

    private void dumpClientController(@NonNull Printer p) {
        p.println("  mClientController:");
        // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
        @SuppressWarnings("GuardedBy") Consumer<ClientState> clientControllerDump = c -> {
            p.println("    " + c + ":");
            p.println("      client=" + c.mClient);
            p.println("      fallbackInputConnection=" + c.mFallbackInputConnection);
            p.println("      sessionRequested=" + c.mSessionRequested);
            p.println("      sessionRequestedForAccessibility="
                    + c.mSessionRequestedForAccessibility);
            p.println("      curSession=" + c.mCurSession);
            p.println("      selfReportedDisplayId=" + c.mSelfReportedDisplayId);
            p.println("      uid=" + c.mUid);
            p.println("      pid=" + c.mPid);
        };
        synchronized (ImfLock.class) {
            mClientController.forAllClients(clientControllerDump);
        }
    }

    private void dumpUserRepository(@NonNull Printer p) {
        p.println("  mUserDataRepository:");
        // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
        @SuppressWarnings("GuardedBy") Consumer<UserData> userDataDump = u -> {
            p.println("    userId=" + u.mUserId);
            p.println("      unlocked=" + u.mIsUnlockingOrUnlocked.get());
            if (Flags.warmWorkProfileIme()) {
                p.println("      hasBackgroundConnection="
                        + u.mBindingController.hasBackgroundConnection());
            }
            p.println("      hasMainConnection=" + u.mBindingController.hasMainConnection());
            p.println("      hasVisibleConnection=" + u.mBindingController.hasVisibleConnection());
            p.println("      boundToMethod=" + u.mBoundToMethod);
            p.println("      curClient=" + u.mCurClient);
            if (u.mCurEditorInfo != null) {
                p.println("      curEditorInfo:");
                u.mCurEditorInfo.dump(p, "        ", false /* dumpExtras */);
            } else {
                p.println("      curEditorInfo: null");
            }
            p.println("      imeBindingState:");
            u.mImeBindingState.dump(p, "        ");
            p.println("      enabledSession=" + u.mEnabledSession);
            p.println("      inFullscreenMode=" + u.mInFullscreenMode);
            p.println("      imeDrawsNavBar=" + u.mImeDrawsNavBar.get());
            p.println("      imeSwitcherMenuVisible=" + u.mImeSwitcherMenuVisible);
            p.println("      switchingController:");
            u.mSwitchingController.dump(p, "        ");
            p.println("      mLastEnabledInputMethodsStr=" + u.mLastEnabledInputMethodsStr);
            p.println("      active computer control input connections on display ids:"
                    + u.mComputerControlInputConnectionMap.keySet());
        };
        synchronized (ImfLock.class) {
            mUserDataRepository.forAllUserData(userDataDump);
        }
    }

    @BinderThread
    @Override
    public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args, @Nullable ShellCallback callback,
            @NonNull ResultReceiver resultReceiver, @NonNull Binder self) {
        final int callingUid = Binder.getCallingUid();
        // Reject any incoming calls from non-shell users, including ones from the system user.
        if (callingUid != Process.ROOT_UID && callingUid != Process.SHELL_UID) {
            // Note that Binder#onTransact() will automatically close "in", "out", and "err" when
            // returned from this method, hence there is no need to close those FDs.
            // "resultReceiver" is the only thing that needs to be taken care of here.
            if (resultReceiver != null) {
                resultReceiver.send(ImeShellCommandController.ShellCommandResult.FAILURE, null);
            }
            final String errorMsg = "InputMethodManagerService does not support shell commands from"
                    + " non-shell users. callingUid=" + callingUid
                    + " args=" + Arrays.toString(args);
            if (Process.isCoreUid(callingUid)) {
                // Let's not crash the calling process if the caller is one of core components.
                Slog.e(TAG, errorMsg);
                return;
            }
            throw new SecurityException(errorMsg);
        }
        final ImeShellCommandController controller;
        synchronized (ImfLock.class) {
            controller = getImeShellCommandControllerLocked();
        }
        controller.exec(self, in, out, err, args, callback, resultReceiver);
    }

    // ----------------------------------------------------------------------
    // Shell command handlers:

    void handleShellCommandTraceInputMethod() {
        boolean isImeTraceEnabled = ImeTracing.getInstance().isEnabled();
        synchronized (ImfLock.class) {
            @SuppressWarnings("GuardedBy")
            Consumer<ClientState> consumer = c -> c.mClient.setImeTraceEnabled(isImeTraceEnabled);
            mClientController.forAllClients(consumer);
        }
    }

    @GuardedBy("ImfLock.class")
    boolean setImeVisibilityOnFocusedWindowClient(boolean visible, UserData userData,
            @NonNull ImeTracker.Token statsToken) {
        if (userData.mImeBindingState.mFocusedWindowClient != null) {
            userData.mImeBindingState.mFocusedWindowClient.mClient.setImeVisibility(visible,
                    statsToken);
            return true;
        }
        ImeTracker.forLogging().onFailed(statsToken,
                ImeTracker.PHASE_SERVER_SET_VISIBILITY_ON_FOCUSED_WINDOW);
        return false;
    }

    /**
     * @param userId the actual user handle obtained by {@link UserHandle#getIdentifier()}
     *               and *not* pseudo ids like {@link UserHandle#USER_ALL etc}
     * @return {@code true} if userId has debugging privileges
     * i.e. {@link UserManager#DISALLOW_DEBUGGING_FEATURES} is {@code false}
     */
    boolean userHasDebugPriv(@UserIdInt int userId, ShellCommand shellCommand) {
        if (mUserManagerInternal.hasUserRestriction(
                UserManager.DISALLOW_DEBUGGING_FEATURES, userId)) {
            shellCommand.getErrPrintWriter().println("User #" + userId
                    + " is restricted with DISALLOW_DEBUGGING_FEATURES.");
            return false;
        }
        return true;
    }

    @Override
    public IImeTracker getImeTrackerService() {
        return mImeTrackerService;
    }

    /**
     * Creates an IME request tracking token for the current focused client.
     *
     * @param show   whether this is a show or a hide request
     * @param reason the reason why the IME request was created
     */
    @NonNull
    @GuardedBy("ImfLock.class")
    ImeTracker.Token createStatsTokenForFocusedClient(boolean show,
            @SoftInputShowHideReason int reason, @UserIdInt int userId) {
        final var userData = getUserData(userId);
        final var client = userData.mImeBindingState.mFocusedWindowClient;
        final int uid = client != null ? client.mUid : -1;
        final var packageName = userData.mImeBindingState.mFocusedWindowEditorInfo != null
                ? userData.mImeBindingState.mFocusedWindowEditorInfo.packageName
                : "uid(" + uid + ")";
        return ImeTracker.forLogging().onStart(packageName, uid,
                show ? ImeTracker.TYPE_SHOW : ImeTracker.TYPE_HIDE, ImeTracker.ORIGIN_SERVER,
                reason, false /* fromUser */, userId,
                client != null ? client.mSelfReportedDisplayId : INVALID_DISPLAY);
    }

    private static final class InputMethodPrivilegedOperationsImpl
            extends IInputMethodPrivilegedOperations.Stub {
        @NonNull
        private final InputMethodManagerService mImms;
        @NonNull
        private final IBinder mToken;
        @NonNull
        private final UserData mUserData;

        InputMethodPrivilegedOperationsImpl(@NonNull InputMethodManagerService imms,
                @NonNull IBinder token, @NonNull UserData userData) {
            mImms = imms;
            mToken = token;
            mUserData = userData;
        }

        @BinderThread
        @Override
        public void setImeWindowStatusAsync(@ImeWindowVisibility int vis,
                @BackDispositionMode int backDisposition) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.setImeWindowStatusLocked(vis, backDisposition, mUserData);
            }
        }

        @BinderThread
        @Override
        public void reportStartInputAsync(IBinder startInputToken) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.reportStartInputLocked(startInputToken, mUserData);
            }
        }

        @BinderThread
        @Override
        public void setHandwritingSurfaceNotTouchable(boolean notTouchable) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.mHwController.setNotTouchable(notTouchable);
            }
        }

        @BinderThread
        @Override
        public void setHandwritingTouchableRegion(Region region) {
            synchronized (ImfLock.class) {
                mImms.mHwController.setHandwritingTouchableRegion(region);
            }
        }

        @BinderThread
        @Override
        public void createInputContentUriToken(Uri contentUri, String packageName,
                AndroidFuture future /* T=IBinder */) {
            @SuppressWarnings("unchecked") final AndroidFuture<IBinder> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(null);
                        return;
                    }
                    typedFuture.complete(mImms.createInputContentUriTokenLocked(
                            contentUri, packageName, mUserData).asBinder());
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void reportFullscreenModeAsync(boolean fullscreen) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.reportFullscreenModeLocked(fullscreen, mUserData);
            }
        }

        @BinderThread
        @Override
        public void setInputMethod(String id, AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Void> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(null);
                        return;
                    }
                    mImms.setInputMethodAndSubtypeLocked(id, null /* subtype */, mUserData);
                    typedFuture.complete(null);
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void setInputMethodAndSubtype(String id, InputMethodSubtype subtype,
                AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Void> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(null);
                        return;
                    }
                    mImms.setInputMethodAndSubtypeLocked(id, subtype, mUserData);
                    typedFuture.complete(null);
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void hideMySoftInput(@NonNull ImeTracker.Token statsToken,
                AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Void> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        ImeTracker.forLogging().onFailed(statsToken,
                                ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                        typedFuture.complete(null);
                        return;
                    }
                    ImeTracker.forLogging().onProgress(statsToken,
                            ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                    Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.hideMySoftInput");
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        mImms.setMyImeVisibilityLocked(false, statsToken, mUserData);
                        typedFuture.complete(null);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                    }
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void showMySoftInput(@NonNull ImeTracker.Token statsToken,
                AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Void> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        ImeTracker.forLogging().onFailed(statsToken,
                                ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                        typedFuture.complete(null);
                        return;
                    }
                    ImeTracker.forLogging().onProgress(statsToken,
                            ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                    Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.showMySoftInput");
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        mImms.setMyImeVisibilityLocked(true, statsToken, mUserData);
                        typedFuture.complete(null);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                    }
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void updateStatusIconAsync(String packageName, @DrawableRes int iconId) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    mImms.updateStatusIconLocked(packageName, iconId, mUserData);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @BinderThread
        @Override
        public void switchToPreviousInputMethod(AndroidFuture future /* T=Boolean */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Boolean> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(false);
                        return;
                    }
                    typedFuture.complete(mImms.switchToPreviousInputMethodLocked(mUserData));
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void switchToNextInputMethod(boolean onlyCurrentIme,
                AndroidFuture future /* T=Boolean */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Boolean> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(false);
                        return;
                    }
                    typedFuture.complete(mImms.switchToNextInputMethodLocked(onlyCurrentIme,
                            mUserData));
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void shouldOfferSwitchingToNextInputMethod(AndroidFuture future /* T=Boolean */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Boolean> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(false);
                        return;
                    }
                    typedFuture.complete(mImms.shouldOfferSwitchingToNextInputMethodLocked(
                            mUserData));
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void onImeSwitchButtonClickFromClient(int displayId) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.onImeSwitchButtonClickLocked(displayId, mUserData);
            }
        }

        @BinderThread
        @Override
        public void notifyUserActionAsync() {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.notifyUserActionLocked(mUserData);
            }
        }

        @BinderThread
        @Override
        public void onStylusHandwritingReady(int requestId, int pid) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.onStylusHandwritingReadyLocked(requestId, pid, mUserData);
            }
        }

        @BinderThread
        @Override
        public void resetStylusHandwriting(int requestId) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.resetStylusHandwritingLocked(requestId);
            }
        }

        @BinderThread
        @Override
        public void switchKeyboardLayoutAsync(int direction) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    mImms.switchKeyboardLayoutLocked(direction, mUserData);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        /**
         * Returns true iff the caller is identified to be the current input method with the token.
         *
         * @param token the window token given to the input method when it was started
         * @param userData {@link UserData} of the calling IME process
         * @return true if and only if non-null valid token is specified
         */
        @GuardedBy("ImfLock.class")
        private static boolean calledWithValidTokenLocked(@NonNull IBinder token,
                @NonNull UserData userData) {
            Objects.requireNonNull(token, "token must not be null");
            final var bindingController = userData.mBindingController;
            if (!bindingController.isActive()) {
                Slog.e(TAG, "Ignoring " + Debug.getCaller() + " due to inactive binding controller."
                        + " uid: " + Binder.getCallingUid() + " token: " + token);
                return false;
            }
            if (token != bindingController.getCurToken()) {
                Slog.e(TAG, "Ignoring " + Debug.getCaller() + " due to an invalid token."
                        + " uid:" + Binder.getCallingUid() + " token:" + token);
                return false;
            }
            return true;
        }
    }
}
