/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED;

import static com.android.internal.widget.flags.Flags.hideLastCharWithPhysicalInput;
import static com.android.systemui.Flags.pinInputFieldStyledFocusState;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.input.InputManager;
import android.uilatencystats.UiLatencyStatsManager;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.domain.interactor.KeyguardKeyboardInteractor;
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.res.R;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.wrapper.LockPatternCheckerWrapper;

import lineageos.providers.LineageSettings;

import java.util.Optional;

public abstract class KeyguardPinBasedInputViewController<T extends KeyguardPinBasedInputView>
        extends KeyguardAbsKeyInputViewController<T> implements InputManager.InputDeviceListener {

    private FalsingCollector mFalsingCollector = null;
    private final KeyguardKeyboardInteractor mKeyguardKeyboardInteractor;
    protected PasswordTextView mPasswordEntry;
    private Boolean mShowAnimations;
    private InputManager mInputManager;

    private final OnKeyListener mOnKeyListener = (v, keyCode, event) -> {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return mView.onKeyDown(keyCode, event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return mView.onKeyUp(keyCode, event);
        }
        return false;
    };

    private final OnTouchListener mActionButtonTouchListener = (v, event) -> {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (mFalsingCollector != null) {
                mFalsingCollector.avoidGesture();
            }
            mBouncerHapticPlayer.playDeleteKeyPressFeedback();
        }
        return false;
    };

    protected KeyguardPinBasedInputViewController(T view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecurityMode securityMode,
            LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            LatencyTracker latencyTracker,
            EmergencyButtonController emergencyButtonController,
            FalsingCollector falsingCollector,
            FeatureFlags featureFlags,
            SelectedUserInteractor selectedUserInteractor,
            KeyguardKeyboardInteractor keyguardKeyboardInteractor,
            BouncerHapticPlayer bouncerHapticPlayer,
            UserActivityNotifier userActivityNotifier,
            InputManager inputManager,
            LockPatternCheckerWrapper lockPatternCheckerWrapper,
            Optional<UiLatencyStatsManager> uiLatencyStatsManager) {
        super(view, keyguardUpdateMonitor, securityMode, lockPatternUtils, keyguardSecurityCallback,
                messageAreaControllerFactory, latencyTracker, falsingCollector,
                emergencyButtonController, featureFlags, selectedUserInteractor,
                bouncerHapticPlayer, userActivityNotifier, lockPatternCheckerWrapper,
                uiLatencyStatsManager);
        mFalsingCollector = falsingCollector;
        mKeyguardKeyboardInteractor = keyguardKeyboardInteractor;
        mPasswordEntry = mView.findViewById(mView.getPasswordTextViewId());
        mInputManager = inputManager;
        mShowAnimations = null;
    }

    private void updateAnimations(Boolean showAnimations) {
        if (!hideLastCharWithPhysicalInput()) return;

        if (showAnimations == null) {
            showAnimations = !mLockPatternUtils
                .isPinEnhancedPrivacyEnabled(mSelectedUserInteractor.getSelectedUserId());
        }
        if (mShowAnimations != null && showAnimations.equals(mShowAnimations)) return;
        mShowAnimations = showAnimations;

        for (NumPadKey button : mView.getButtons()) {
            button.setAnimationEnabled(mShowAnimations);
        }
        mPasswordEntry.setShowPassword(mShowAnimations);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        if (!hideLastCharWithPhysicalInput()) return;

        // If we were showing animations before maybe the new device is a keyboard.
        if (mShowAnimations) {
            updateAnimations(null);
        }
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        if (!hideLastCharWithPhysicalInput()) return;

        // If we were hiding animations because of a keyboard the keyboard may have been unplugged.
        if (!mShowAnimations) {
            updateAnimations(null);
        }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        if (!hideLastCharWithPhysicalInput()) return;

        updateAnimations(null);
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        updatePinScrambling();

        boolean showAnimations = !mLockPatternUtils
                .isPinEnhancedPrivacyEnabled(mSelectedUserInteractor.getSelectedUserId());
        if (hideLastCharWithPhysicalInput()) {
            mInputManager.registerInputDeviceListener(this, null);
            updateAnimations(showAnimations);
        } else {
            mPasswordEntry.setShowPassword(showAnimations);
        }

        for (NumPadKey button : mView.getButtons()) {
            button.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mFalsingCollector.avoidGesture();
                }
                return false;
            });
            if (!hideLastCharWithPhysicalInput()) {
                button.setAnimationEnabled(showAnimations);
            }
            button.setBouncerHapticHelper(mBouncerHapticPlayer);
        }
        mPasswordEntry.setOnKeyListener(mOnKeyListener);
        mPasswordEntry.setUserActivityListener(this::onUserInput);

        View deleteButton = mView.findViewById(R.id.delete_button);
        deleteButton.setOnTouchListener(mActionButtonTouchListener);

        deleteButton.setOnClickListener(v -> {
            // check for time-based lockouts
            if (mPasswordEntry.isEnabled()) {
                mPasswordEntry.deleteLastChar();
            }
        });
        deleteButton.setOnLongClickListener(v -> {
            // check for time-based lockouts
            if (mPasswordEntry.isEnabled()) {
                mView.resetPasswordText(true /* animate */, true /* announce */);
            }
            mBouncerHapticPlayer.playDeleteKeyLongPressedFeedback();
            return true;
        });

        View okButton = mView.findViewById(R.id.key_enter);
        if (okButton != null) {
            okButton.setOnClickListener(v -> {
                if (mPasswordEntry.isEnabled()) {
                    verifyPasswordAndUnlock();
                }
            });
        }
        if (pinInputFieldStyledFocusState()) {
            collectFlow(mPasswordEntry, mKeyguardKeyboardInteractor.isAnyKeyboardConnected(),
                    this::setKeyboardBasedFocusOutline);

            /**
             * new UI Specs for PIN Input field have new dimensions go/pin-focus-states.
             * However we want these changes behind a flag, and resource files cannot be flagged
             * hence the dimension change in code. When the flags are removed these dimensions
             * should be set in resources permanently and the code below removed.
             */
            ViewGroup.LayoutParams layoutParams = mPasswordEntry.getLayoutParams();
            layoutParams.width = (int) getResources().getDimension(
                    R.dimen.keyguard_pin_field_width);
            layoutParams.height = (int) getResources().getDimension(
                    R.dimen.keyguard_pin_field_height);
        }

        mPasswordEntry.sendAccessibilityEvent(TYPE_VIEW_FOCUSED);
    }

    private void setKeyboardBasedFocusOutline(boolean isAnyKeyboardConnected) {
        Drawable background = mPasswordEntry.getBackground();
        if (!(background instanceof StateListDrawable)) return;
        Drawable stateDrawable = ((StateListDrawable) background).getStateDrawable(0);
        if (!(stateDrawable instanceof GradientDrawable gradientDrawable)) return;

        int color = getResources().getColor(R.color.bouncer_password_focus_color);
        if (!isAnyKeyboardConnected) {
            gradientDrawable.setStroke(0, color);
        } else {
            int strokeWidthInDP = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3,
                    getResources().getDisplayMetrics());
            gradientDrawable.setStroke(strokeWidthInDP, color);
        }
    }


    @Override
    public void startAppearAnimation() {
        super.startAppearAnimation();
        updatePinScrambling();
    }

    private void updatePinScrambling() {
        mView.updatePinScrambling(
                LineageSettings.System.getIntForUser(getContext().getContentResolver(),
                        LineageSettings.System.LOCKSCREEN_PIN_SCRAMBLE_LAYOUT, 0,
                        mSelectedUserInteractor.getSelectedUserId()) == 1);
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();

        if (hideLastCharWithPhysicalInput()) {
            mInputManager.unregisterInputDeviceListener(this);
        }

        for (NumPadKey button : mView.getButtons()) {
            button.setOnTouchListener(null);
            button.setBouncerHapticHelper(null);
        }
    }

    @Override
    public void onResume(int reason) {
        super.onResume(reason);
        // It's possible to reach a state here where mPasswordEntry believes it is focused
        // but it is not actually focused. This state will prevent the view from gaining focus,
        // as requestFocus will no-op since the focus flag is already set. By clearing focus first,
        // it's guaranteed that the view has focus.
        mPasswordEntry.clearFocus();
        mPasswordEntry.requestFocus();
    }

    @Override
    void resetState() {
        mMessageAreaController.setMessage(getInitialMessageResId());
        mView.setPasswordEntryEnabled(true);
    }

    @Override
    protected void startErrorAnimation() {
        super.startErrorAnimation();
        mView.startErrorAnimation();
    }

    @Override
    protected int getInitialMessageResId() {
        return R.string.keyguard_enter_your_pin;
    }
}
