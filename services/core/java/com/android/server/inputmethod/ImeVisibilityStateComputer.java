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

import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HIDDEN;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.ACCESSIBILITY_REQUESTING_NO_SOFT_KEYBOARD;
import static android.internal.perfetto.protos.Inputmethodmanagerservice.InputMethodManagerServiceProto.INPUT_SHOWN;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.MotionEvent.TOOL_TYPE_UNKNOWN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.SoftInputModeFlags;

import static com.android.internal.inputmethod.InputMethodDebug.softInputModeToString;
import static com.android.server.inputmethod.ImeProtoLogGroup.IME_VIS_STATE_COMPUTER_DEBUG;
import static com.android.server.inputmethod.InputMethodManagerService.FALLBACK_DISPLAY_ID;
import static com.android.server.inputmethod.InputMethodManagerService.computeImeDisplayIdForTarget;

import android.accessibilityservice.AccessibilityService;
import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.IBinder;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.protolog.ProtoLog;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.io.PrintWriter;
import java.util.WeakHashMap;

/**
 * A computer used by {@link InputMethodManagerService} that computes the IME visibility state
 * according the given {@link ImeTargetWindowState} from the focused window or the app requested IME
 * visibility from {@link InputMethodManager}.
 */
public final class ImeVisibilityStateComputer {

    static final String TAG = "ImeVisibilityStateComputer";

    @UserIdInt
    private final int mUserId;

    private final InputMethodManagerService mService;
    private final UserManagerInternal mUserManagerInternal;
    private final WindowManagerInternal mWindowManagerInternal;

    final InputMethodManagerService.ImeDisplayValidator mImeDisplayValidator;

    /**
     * A map used to track the requested IME target window and its state. The key represents the
     * token of the window and the value is the corresponding IME target window state.
     */
    @GuardedBy("ImfLock.class")
    private final WeakHashMap<IBinder, ImeTargetWindowState> mRequestWindowStateMap =
            new WeakHashMap<>();

    /**
     * Set if we last told the input method to show itself.
     */
    @GuardedBy("ImfLock.class")
    private boolean mInputShown;

    /**
     * Set if we called {@link com.android.server.wm.ImeTargetVisibilityPolicy#showImeScreenshot}.
     */
    @GuardedBy("ImfLock.class")
    private boolean mRequestedImeScreenshot;

    /** Whether there is a visible IME layering target overlay. */
    @GuardedBy("ImfLock.class")
    private boolean mHasVisibleImeLayeringOverlay;

    /** The window token of the current visible IME input target. */
    @GuardedBy("ImfLock.class")
    private IBinder mCurVisibleImeInputTarget;

    /**
     * The token of the last window that we confirmed that IME started talking to. This is always
     * updated upon reports from the input method. If the window state is already changed before
     * the report is handled, this field just keeps the last value.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    private IBinder mLastImeTargetWindow;

    /**
     * The policy to configure the IME visibility.
     */
    private final ImeVisibilityPolicy mPolicy;

    public ImeVisibilityStateComputer(@NonNull InputMethodManagerService service,
            @UserIdInt int userId) {
        this(service,
                LocalServices.getService(UserManagerInternal.class),
                LocalServices.getService(WindowManagerInternal.class),
                LocalServices.getService(WindowManagerInternal.class)::getDisplayImePolicy,
                new ImeVisibilityPolicy(), userId);
    }

    @VisibleForTesting
    public ImeVisibilityStateComputer(@NonNull InputMethodManagerService service,
            @NonNull Injector injector) {
        this(service, injector.getUserManagerService(), injector.getWmService(),
                injector.getImeValidator(), new ImeVisibilityPolicy(), injector.getUserId());
    }

    interface Injector {
        @NonNull
        UserManagerInternal getUserManagerService();

        @NonNull
        WindowManagerInternal getWmService();

        @NonNull
        InputMethodManagerService.ImeDisplayValidator getImeValidator();

        @UserIdInt
        int getUserId();
    }

    private ImeVisibilityStateComputer(InputMethodManagerService service,
            UserManagerInternal userManagerInternal,
            WindowManagerInternal wmService,
            InputMethodManagerService.ImeDisplayValidator imeDisplayValidator,
            ImeVisibilityPolicy imePolicy, @UserIdInt int userId) {
        mUserId = userId;
        mService = service;
        mUserManagerInternal = userManagerInternal;
        mWindowManagerInternal = wmService;
        mImeDisplayValidator = imeDisplayValidator;
        mPolicy = imePolicy;
    }

    @GuardedBy("ImfLock.class")
    void setHasVisibleImeLayeringOverlay(boolean hasVisibleOverlay) {
        mHasVisibleImeLayeringOverlay = hasVisibleOverlay;
    }

    @GuardedBy("ImfLock.class")
    void onImeInputTargetVisibilityChanged(@NonNull IBinder imeInputTarget,
            boolean visibleAndNotRemoved) {
        if (visibleAndNotRemoved) {
            mCurVisibleImeInputTarget = imeInputTarget;
            return;
        }
        if (mHasVisibleImeLayeringOverlay
                && mCurVisibleImeInputTarget == imeInputTarget) {
            final int reason = SoftInputShowHideReason.HIDE_WHEN_INPUT_TARGET_INVISIBLE;
            final var userData = mService.getUserData(mUserId);
            final var client = userData.mImeBindingState.mFocusedWindowClient;
            final var statsToken = ImeTracker.forLogging().onStart(ImeTracker.TYPE_HIDE,
                    ImeTracker.ORIGIN_SERVER, reason, false /* fromUser */, mUserId,
                    client != null ? client.mSelfReportedDisplayId : INVALID_DISPLAY);
            mService.setImeVisibilityOnFocusedWindowClient(false /* visible */, userData,
                    statsToken);
        }
        mCurVisibleImeInputTarget = null;
    }

    /**
     * Called when {@link InputMethodManagerService} is processing the show IME request.
     *
     * @return {@code true} when the show request can proceed.
     */
    @GuardedBy("ImfLock.class")
    boolean isAllowedByAccessibilityAndDisplayPolicy() {
        return !mPolicy.mA11yRequestingNoSoftKeyboard && !mPolicy.mImeHiddenByDisplayPolicy;
    }

    @GuardedBy("ImfLock.class")
    int computeImeDisplayId(int displayId) {
        final int displayToShowIme;
        final PackageManager pm = mService.mContext.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                && mUserManagerInternal.isVisibleBackgroundFullUser(mUserId)) {
            displayToShowIme = mService.computeImeDisplayIdForVisibleBackgroundUserOnAutomotive(
                    displayId, mUserId, mImeDisplayValidator);
        } else {
            final int imeFallbackDisplayId = mWindowManagerInternal.getImeFallbackDisplayId();
            displayToShowIme = computeImeDisplayIdForTarget(displayId, mImeDisplayValidator,
                    imeFallbackDisplayId != INVALID_DISPLAY
                            ? imeFallbackDisplayId : FALLBACK_DISPLAY_ID);
        }
        final boolean imeHiddenByPolicy = displayToShowIme == INVALID_DISPLAY;
        mPolicy.setImeHiddenByDisplayPolicy(imeHiddenByPolicy);
        return displayToShowIme;
    }

    /**
     * Request to show/hide IME from the given window.
     *
     * @param windowToken The window which requests to show/hide IME.
     * @param showIme {@code true} means to show IME, {@code false} otherwise.
     */
    @GuardedBy("ImfLock.class")
    void requestImeVisibility(IBinder windowToken, boolean showIme) {
        ImeTargetWindowState state = getOrCreateWindowState(windowToken);
        if (!mPolicy.mPendingA11yRequestingHideKeyboard) {
            state.setRequestedImeVisible(showIme);
        } else {
            // As A11y requests no IME is just a temporary, so we don't change the requested IME
            // visible in case the last visibility state goes wrong after leaving from the a11y
            // policy.
            mPolicy.mPendingA11yRequestingHideKeyboard = false;
        }
        // create a placeholder token for IMS so that IMS cannot inject windows into client app.
        state.setRequestImeToken(new Binder());
        setWindowStateInner(windowToken, state);
    }

    @GuardedBy("ImfLock.class")
    ImeTargetWindowState getOrCreateWindowState(IBinder windowToken) {
        ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
        if (state == null) {
            state = new ImeTargetWindowState(SOFT_INPUT_STATE_UNSPECIFIED, 0 /* windowFlags */,
                    false /* imeFocusChanged */, false /* hasFocusedEditor */,
                    false /* isStartInputByWindowGainFocus */, TOOL_TYPE_UNKNOWN);
        }
        return state;
    }

    @GuardedBy("ImfLock.class")
    @Nullable
    ImeTargetWindowState getWindowStateOrNull(@Nullable IBinder windowToken) {
        return mRequestWindowStateMap.get(windowToken);
    }

    @GuardedBy("ImfLock.class")
    void setWindowState(@NonNull IBinder windowToken, @NonNull ImeTargetWindowState newState) {
        final ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
        if (state != null && newState.hasEditorFocused()) {
            // Inherit the last requested IME visible state when the target window is still
            // focused with an editor.
            newState.setRequestedImeVisible(state.isRequestedImeVisible());
        }
        setWindowStateInner(windowToken, newState);
    }

    @GuardedBy("ImfLock.class")
    private void setWindowStateInner(IBinder windowToken, @NonNull ImeTargetWindowState newState) {
        ProtoLog.v(IME_VIS_STATE_COMPUTER_DEBUG, "setWindowStateInner, windowToken=%s, state=%s",
                windowToken, newState);
        mRequestWindowStateMap.put(windowToken, newState);
    }

    static class ImeVisibilityResult {
        private final boolean mVisible;
        private final @SoftInputShowHideReason int mReason;

        ImeVisibilityResult(boolean visible, @SoftInputShowHideReason int reason) {
            mVisible = visible;
            mReason = reason;
        }

        public boolean isVisible() {
            return mVisible;
        }

        @SoftInputShowHideReason int getReason() {
            return mReason;
        }
    }

    /**
     * Computes the IME visibility state from the given IME target window state.
     *
     * @param state               the state of the IME target window.
     * @param allowVisible        whether the soft input modes
     *                            {@link WindowManager.LayoutParams#SOFT_INPUT_STATE_VISIBLE} and
     *                            {@link WindowManager.LayoutParams#SOFT_INPUT_STATE_ALWAYS_VISIBLE}
     *                            are allowed.
     * @param imeRequestedVisible whether the IME target window has the IME insets type in the
     *                            requestedVisibleTypes (see
     *                            {@link InputMethodManager#hasViewImeRequestedVisible}).
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    ImeVisibilityResult computeState(@NonNull ImeTargetWindowState state, boolean allowVisible,
            boolean imeRequestedVisible) {
        // TODO: Output the request IME visibility state according to the requested window state
        final var softInputMode = state.getSoftInputModeState();
        final int softInputVisibility = softInputMode & SOFT_INPUT_MASK_STATE;
        // Should we auto-show the IME even if the caller has not
        // specified what should be done with it?
        // We only do this automatically if the window can resize
        // to accommodate the IME (so what the user sees will give
        // them good context without input information being obscured
        // by the IME) or if running on a large screen where there
        // is more room for the target window + IME.
        final boolean doAutoShow =
                (softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                        == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        || mService.mRes.getConfiguration().isLayoutSizeAtLeast(
                        Configuration.SCREENLAYOUT_SIZE_LARGE);
        final boolean isForwardNavigation =
                (softInputMode & WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0;

        // We shows the IME when the system allows the IME focused target window to restore the
        // IME visibility (e.g. switching to the app task when last time the IME is visible).
        // Note that we don't restore IME visibility for some cases (e.g. when the soft input
        // state is ALWAYS_HIDDEN or STATE_HIDDEN with forward navigation).
        // Because the app might leverage these flags to hide soft-keyboard with showing their own
        // UI for input.
        if (state.hasEditorFocused() && shouldRestoreImeVisibility(state)) {
            ProtoLog.v(IME_VIS_STATE_COMPUTER_DEBUG, "Will show input to restore visibility");
            // Inherit the last requested IME visible state when the target window is still
            // focused with an editor.
            state.setRequestedImeVisible(true);
            setWindowStateInner(getWindowTokenFrom(state), state);
            return new ImeVisibilityResult(true /* visible */,
                    SoftInputShowHideReason.SHOW_RESTORE_IME_VISIBILITY);
        }

        switch (softInputVisibility) {
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED:
                if (state.hasImeFocusChanged() && (!state.hasEditorFocused())) {
                    if (WindowManager.LayoutParams.mayUseInputMethod(state.getWindowFlags())) {
                        // There is no focus view, and this window will
                        // be behind any soft input window, so hide the
                        // soft input window if it is shown.
                        ProtoLog.v(IME_VIS_STATE_COMPUTER_DEBUG,
                                "Unspecified window will hide input");
                        return new ImeVisibilityResult(false /* visible */,
                                SoftInputShowHideReason.HIDE_UNSPECIFIED_WINDOW);
                    }
                } else if (state.hasEditorFocused() && doAutoShow && isForwardNavigation) {
                    // There is a focus view, and we are navigating forward
                    // into the window, so show the input window for the user.
                    // We only do this automatically if the window can resize
                    // to accommodate the IME (so what the user sees will give
                    // them good context without input information being obscured
                    // by the IME) or if running on a large screen where there
                    // is more room for the target window + IME.
                    ProtoLog.v(IME_VIS_STATE_COMPUTER_DEBUG, "Unspecified window will show input");
                    return new ImeVisibilityResult(true /* visible */,
                            SoftInputShowHideReason.SHOW_AUTO_EDITOR_FORWARD_NAV);
                }
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED:
                // Do nothing but preserving the last IME requested visibility state.
                final ImeTargetWindowState lastState = getWindowStateOrNull(mLastImeTargetWindow);
                if (lastState != null) {
                    state.setRequestedImeVisible(lastState.isRequestedImeVisible());
                }
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN:
                // In this case, we don't have to manipulate the requested visible types of
                // the WindowState, as they're already in the correct state
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                // In this case, we don't have to manipulate the requested visible types of
                // the WindowState, as they're already in the correct state
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE:
                if (isForwardNavigation) {
                    if (allowVisible) {
                        ProtoLog.v(IME_VIS_STATE_COMPUTER_DEBUG,
                                "Window asks to show input going forward");
                        return new ImeVisibilityResult(true /* visible */,
                                SoftInputShowHideReason.SHOW_STATE_VISIBLE_FORWARD_NAV);
                    } else {
                        Slog.e(TAG, "SOFT_INPUT_STATE_VISIBLE is ignored because"
                                + " there is no focused view that also returns true from"
                                + " View#onCheckIsTextEditor()");
                    }
                }
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE:
                ProtoLog.v(IME_VIS_STATE_COMPUTER_DEBUG, "Window asks to always show input");
                if (allowVisible) {
                    if (state.hasImeFocusChanged()) {
                        return new ImeVisibilityResult(true /* visible */,
                                SoftInputShowHideReason.SHOW_STATE_ALWAYS_VISIBLE);
                    }
                } else {
                    Slog.e(TAG, "SOFT_INPUT_STATE_ALWAYS_VISIBLE is ignored because"
                            + " there is no focused view that also returns true from"
                            + " View#onCheckIsTextEditor()");
                }
                break;
        }

        if (!state.hasImeFocusChanged()) {
            // On previous platforms, when Dialogs re-gained focus, the Activity behind
            // would briefly gain focus first, and dismiss the IME.
            // On R that behavior has been fixed, but unfortunately apps have come
            // to rely on this behavior to hide the IME when the editor no longer has focus
            // To maintain compatibility, we are now hiding the IME when we don't have
            // an editor upon refocusing a window.
            if (state.isStartInputByWindowGainFocus()) {
                ProtoLog.v(IME_VIS_STATE_COMPUTER_DEBUG,
                        "Same window without editor will hide input");
                return new ImeVisibilityResult(false /* visible */,
                        SoftInputShowHideReason.HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR);
            }
        }
        if (!state.hasEditorFocused() && (mInputShown || imeRequestedVisible)
                && state.isStartInputByWindowGainFocus()
                && mService.mInputMethodDeviceConfigs.shouldHideImeWhenNoEditorFocus()) {
            // Hide the soft-keyboard when the system do nothing for softInputModeState
            // of the window being gained focus without an editor. This behavior benefits
            // to resolve some unexpected IME visible cases while that window with following
            // configurations being switched from an IME shown window:
            // 1) SOFT_INPUT_STATE_UNCHANGED state without an editor
            // 2) SOFT_INPUT_STATE_VISIBLE state without an editor
            // 3) SOFT_INPUT_STATE_ALWAYS_VISIBLE state without an editor
            ProtoLog.v(IME_VIS_STATE_COMPUTER_DEBUG, "Window without editor will hide input");
            state.setRequestedImeVisible(false);
            return new ImeVisibilityResult(false /* visible */,
                    SoftInputShowHideReason.HIDE_WINDOW_GAINED_FOCUS_WITHOUT_EDITOR);
        }
        return null;
    }

    /**
     * Checks if we should show or hide the IME screenshot, based on the given IME target window and
     * the interactive state.
     *
     * @param windowToken the token of the {@link ImeTargetWindowState} to check.
     * @param interactive whether the system is currently interactive.
     * @return {@code true} if the screenshot should be shown, {@code false} if it should be hidden,
     * or {@code null} if it should remain unchanged.
     */
    @Nullable
    @GuardedBy("ImfLock.class")
    Boolean shouldShowImeScreenshot(IBinder windowToken, boolean interactive) {
        final ImeTargetWindowState state = getWindowStateOrNull(windowToken);
        if (state != null && state.isRequestedImeVisible() && mInputShown && !interactive) {
            mRequestedImeScreenshot = true;
            return true;
        }
        if (interactive && mRequestedImeScreenshot) {
            mRequestedImeScreenshot = false;
            return false;
        }
        return null;
    }

    @GuardedBy("ImfLock.class")
    IBinder getWindowTokenFrom(IBinder requestImeToken, @UserIdInt int userId) {
        for (IBinder windowToken : mRequestWindowStateMap.keySet()) {
            final ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
            if (state.getRequestImeToken() == requestImeToken) {
                return windowToken;
            }
        }
        final var userData = mService.getUserData(userId);
        // Fallback to the focused window for some edge cases (e.g. relaunching the activity)
        return userData.mImeBindingState.mFocusedWindow;
    }

    @GuardedBy("ImfLock.class")
    IBinder getWindowTokenFrom(ImeTargetWindowState windowState) {
        for (IBinder windowToken : mRequestWindowStateMap.keySet()) {
            final ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
            if (state == windowState) {
                return windowToken;
            }
        }
        return null;
    }

    @GuardedBy("ImfLock.class")
    boolean shouldRestoreImeVisibility(@NonNull ImeTargetWindowState state) {
        final int softInputMode = state.getSoftInputModeState();
        switch (softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE) {
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                return false;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN:
                if ((softInputMode & SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                    return false;
                }
        }
        return mWindowManagerInternal.shouldRestoreImeVisibility(getWindowTokenFrom(state));
    }

    @UserIdInt
    @VisibleForTesting
    int getUserId() {
        return mUserId;
    }

    @GuardedBy("ImfLock.class")
    boolean isInputShown() {
        return mInputShown;
    }

    @GuardedBy("ImfLock.class")
    void setInputShown(boolean inputShown) {
        mInputShown = inputShown;
    }

    @GuardedBy("ImfLock.class")
    @Nullable
    IBinder getLastImeTargetWindow() {
        return mLastImeTargetWindow;
    }

    @GuardedBy("ImfLock.class")
    void setLastImeTargetWindow(@Nullable IBinder imeTargetWindow) {
        mLastImeTargetWindow = imeTargetWindow;
    }

    @GuardedBy("ImfLock.class")
    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        proto.write(ACCESSIBILITY_REQUESTING_NO_SOFT_KEYBOARD,
                mPolicy.isA11yRequestNoSoftKeyboard());
        proto.write(INPUT_SHOWN, mInputShown);
    }

    @GuardedBy("ImfLock.class")
    void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        final Printer p = new PrintWriterPrinter(pw);
        p.println(prefix + "mImeHiddenByDisplayPolicy=" + mPolicy.isImeHiddenByDisplayPolicy());
        p.println(prefix + "mInputShown=" + mInputShown);
        p.println(prefix + "mLastImeTargetWindow=" + mLastImeTargetWindow);
    }

    /**
     * A settings class to manage all IME related visibility policies or settings.
     *
     * This is used for the visibility computer to manage and tell
     * {@link InputMethodManagerService} if the requested IME visibility is valid from
     * application call or the focus window.
     */
    static class ImeVisibilityPolicy {
        /**
         * {@code true} if the Ime policy has been set to
         * {@link WindowManager#DISPLAY_IME_POLICY_HIDE}.
         *
         * This prevents the IME from showing when it otherwise may have shown.
         */
        @GuardedBy("ImfLock.class")
        private boolean mImeHiddenByDisplayPolicy;

        /**
         * Set when the accessibility service requests to hide IME by
         * {@link AccessibilityService.SoftKeyboardController#setShowMode}
         */
        @GuardedBy("ImfLock.class")
        private boolean mA11yRequestingNoSoftKeyboard;

        /**
         * Used when A11y request to hide IME temporary when receiving
         * {@link AccessibilityService#SHOW_MODE_HIDDEN} from
         * {@link android.provider.Settings.Secure#ACCESSIBILITY_SOFT_KEYBOARD_MODE} without
         * changing the requested IME visible state.
         */
        @GuardedBy("ImfLock.class")
        private boolean mPendingA11yRequestingHideKeyboard;

        @GuardedBy("ImfLock.class")
        void setImeHiddenByDisplayPolicy(boolean hideIme) {
            mImeHiddenByDisplayPolicy = hideIme;
        }

        @GuardedBy("ImfLock.class")
        boolean isImeHiddenByDisplayPolicy() {
            return mImeHiddenByDisplayPolicy;
        }

        @GuardedBy("ImfLock.class")
        void setA11yRequestNoSoftKeyboard(int keyboardShowMode) {
            mA11yRequestingNoSoftKeyboard =
                    (keyboardShowMode & AccessibilityService.SHOW_MODE_MASK) == SHOW_MODE_HIDDEN;
            if (mA11yRequestingNoSoftKeyboard) {
                mPendingA11yRequestingHideKeyboard = true;
            }
        }

        @GuardedBy("ImfLock.class")
        boolean isA11yRequestNoSoftKeyboard() {
            return mA11yRequestingNoSoftKeyboard;
        }
    }

    @GuardedBy("ImfLock.class")
    ImeVisibilityPolicy getImePolicy() {
        return mPolicy;
    }

    /**
     * State information about an IME target window (i.e. a window for which we started an
     * input connection). One of the IME target windows is set as the current
     * {@link com.android.server.wm.DisplayContent#mImeInputTarget}.
     */
    static class ImeTargetWindowState {

        ImeTargetWindowState(@SoftInputModeFlags int softInputModeState,
                @WindowManager.LayoutParams.Flags int windowFlags, boolean imeFocusChanged,
                boolean hasFocusedEditor, boolean isStartInputByWindowGainFocus,
                @MotionEvent.ToolType int toolType) {
            mSoftInputModeState = softInputModeState;
            mWindowFlags = windowFlags;
            mImeFocusChanged = imeFocusChanged;
            mHasFocusedEditor = hasFocusedEditor;
            mIsStartInputByWindowGainFocus = isStartInputByWindowGainFocus;
            mToolType = toolType;
        }

        /** {@link WindowManager.LayoutParams#softInputMode} of the IME target window. */
        @SoftInputModeFlags
        private final int mSoftInputModeState;

        /** Window flags of the IME target window. */
        @WindowManager.LayoutParams.Flags
        private final int mWindowFlags;

        /** Whether the IME focus changed from the previous window. */
        private final boolean mImeFocusChanged;

        /**
         * Whether the window has a focused view that is a text editor.
         *
         * @see android.view.View#onCheckIsTextEditor
         */
        private final boolean mHasFocusedEditor;

        /**
         * Whether this became the IME target (started an input connection) due to the window
         * gaining input focus.
         *
         * @see com.android.internal.inputmethod.StartInputFlags#WINDOW_GAINED_FOCUS
         */
        private final boolean mIsStartInputByWindowGainFocus;

        /**
         * The type of tool that was used to click editor.
         *
         * @see MotionEvent#getToolType
         */
        @MotionEvent.ToolType
        private final int mToolType;

        /** Whether the client of the window requested the IME to be visible. */
        @GuardedBy("ImfLock.class")
        private boolean mRequestedImeVisible;

        /**
         * A identifier for knowing the requester of {@link InputMethodManager#showSoftInput} or
         * {@link InputMethodManager#hideSoftInputFromWindow}.
         */
        @GuardedBy("ImfLock.class")
        private IBinder mRequestImeToken;

        @AnyThread
        @SoftInputModeFlags
        int getSoftInputModeState() {
            return mSoftInputModeState;
        }

        @AnyThread
        @WindowManager.LayoutParams.Flags
        int getWindowFlags() {
            return mWindowFlags;
        }

        @AnyThread
        boolean hasImeFocusChanged() {
            return mImeFocusChanged;
        }

        @AnyThread
        boolean hasEditorFocused() {
            return mHasFocusedEditor;
        }

        @AnyThread
        boolean isStartInputByWindowGainFocus() {
            return mIsStartInputByWindowGainFocus;
        }

        @AnyThread
        @MotionEvent.ToolType
        int getToolType() {
            return mToolType;
        }

        @GuardedBy("ImfLock.class")
        private void setRequestedImeVisible(boolean requestedImeVisible) {
            mRequestedImeVisible = requestedImeVisible;
        }

        @GuardedBy("ImfLock.class")
        boolean isRequestedImeVisible() {
            return mRequestedImeVisible;
        }

        @GuardedBy("ImfLock.class")
        void setRequestImeToken(@NonNull IBinder token) {
            mRequestImeToken = token;
        }

        @GuardedBy("ImfLock.class")
        IBinder getRequestImeToken() {
            return mRequestImeToken;
        }

        @Override
        public String toString() {
            return "ImeTargetWindowState{"
                    + " softInputModeState " + softInputModeToString(mSoftInputModeState)
                    + " windowFlags: " + Integer.toHexString(mWindowFlags)
                    + " imeFocusChanged " + mImeFocusChanged
                    + " hasFocusedEditor " + mHasFocusedEditor
                    + " isStartInputByWindowGainFocus " + mIsStartInputByWindowGainFocus
                    + " toolType: " + mToolType
                    + " requestedImeVisible " + mRequestedImeVisible
                    + " requestImeToken " + mRequestImeToken
                    + "}";
        }
    }
}
