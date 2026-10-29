/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard;

import static android.security.Flags.lockscreenTimeoutDeactivatePinPad;

import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_ADAPTIVE_AUTH_REQUEST;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_DEVICE_ADMIN;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_NONE;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_PREPARE_FOR_UPDATE;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_RESTART;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_RESTART_FOR_MAINLINE_UPDATE;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_TIMEOUT;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_TRUSTAGENT_EXPIRED;
import static com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_USER_REQUEST;
import static com.android.systemui.Flags.pinInputFieldStyledFocusState;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import androidx.annotation.CallSuper;

import com.android.app.animation.Interpolators;
import com.android.internal.widget.LockscreenCredential;
import com.android.systemui.Flags;
import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A Pin based Keyguard input view
 */
public abstract class KeyguardPinBasedInputView extends KeyguardAbsKeyInputView {

    protected PasswordTextView mPasswordEntry;
    private NumPadButton mOkButton;
    private NumPadButton mDeleteButton;
    private NumPadKey[] mButtons = new NumPadKey[10];
    private boolean mScramblePin;

    private List<Integer> mNumbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 0);
    private final List<Integer> mDefaultNumbers = List.of(mNumbers.toArray(new Integer[0]));

    public KeyguardPinBasedInputView(Context context) {
        this(context, null);
    }

    public KeyguardPinBasedInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // send focus to the password field
        return mPasswordEntry.requestFocus(direction, previouslyFocusedRect);
    }

    @Override
    protected void resetState() {
    }

    @Override
    protected void setPasswordEntryEnabled(boolean enabled) {
        mPasswordEntry.setEnabled(enabled);
        if (lockscreenTimeoutDeactivatePinPad()) {
            setNumPadButtonsEnabled(enabled);
        } else {
            mOkButton.setEnabled(enabled);
        }
        if (enabled && !mPasswordEntry.hasFocus()) {
            mPasswordEntry.requestFocus();
        }
    }

    @Override
    protected void setPasswordEntryInputEnabled(boolean enabled) {
        mPasswordEntry.setEnabled(enabled);
        if (lockscreenTimeoutDeactivatePinPad()) {
            setNumPadButtonsEnabled(enabled);
        } else {
            mOkButton.setEnabled(enabled);
        }
        if (enabled) {
            mPasswordEntry.requestFocus();
        }
    }

    private void setNumPadButtonsEnabled(boolean enabled) {
        setButtonState(mOkButton, enabled);
        setButtonState(mDeleteButton, enabled);
        for (NumPadKey button : mButtons) {
            setButtonState(button, enabled);
        }
    }

    private void setButtonState(View button, boolean enabled) {
        button.setEnabled(enabled);
        button.setClickable(enabled);
        button.setFocusable(enabled);
    }


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            mDeleteButton.performClick();
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            int number = keyCode - KeyEvent.KEYCODE_0;
            performNumberClick(number);
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            int number = keyCode - KeyEvent.KEYCODE_NUMPAD_0;
            performNumberClick(number);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (KeyEvent.isConfirmKey(keyCode)) {
            mOkButton.performClick();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected int getPromptReasonStringRes(int reason) {
        switch (reason) {
            case PROMPT_REASON_RESTART:
                return R.string.kg_prompt_reason_restart_pin;
            case PROMPT_REASON_RESTART_FOR_MAINLINE_UPDATE:
                return R.string.kg_prompt_after_update_pin;
            case PROMPT_REASON_TIMEOUT:
                return R.string.kg_prompt_reason_timeout_pin;
            case PROMPT_REASON_DEVICE_ADMIN:
                return R.string.kg_prompt_reason_device_admin;
            case PROMPT_REASON_USER_REQUEST:
                return R.string.kg_prompt_after_user_lockdown_pin;
            case PROMPT_REASON_PREPARE_FOR_UPDATE:
                return R.string.kg_prompt_added_security_pin;
            case PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT:
                return R.string.kg_prompt_reason_timeout_pin;
            case PROMPT_REASON_TRUSTAGENT_EXPIRED:
                return R.string.kg_prompt_reason_timeout_pin;
            case PROMPT_REASON_ADAPTIVE_AUTH_REQUEST:
                return R.string.kg_prompt_after_adaptive_auth_lock;
            case PROMPT_REASON_NONE:
                return 0;
            default:
                return R.string.kg_prompt_reason_timeout_pin;
        }
    }

    protected void updatePinScrambling(boolean scramblePin) {
        if (scramblePin || scramblePin != mScramblePin) {
            mScramblePin = scramblePin;
            if (scramblePin) {
                Collections.shuffle(mNumbers);
            } else {
                mNumbers = new ArrayList<>(mDefaultNumbers);
            }
            // Keys in pad order (1-9, then 0), i.e. the order in which mNumbers is laid out
            for (int i = 0; i < mNumbers.size(); i++) {
                mButtons[(i + 1) % mButtons.length].setDigit(mNumbers.get(i));
            }
        }
    }

    protected int getNumberIndex(int number) {
        if (mScramblePin) {
            return (mNumbers.indexOf(number) + 1) % mNumbers.size();
        }
        return number;
    }

    private void performNumberClick(int number) {
        if (number >= 0 && number <= 9) {
            mButtons[getNumberIndex(number)].performClick();
        }
    }

    @Override
    protected void resetPasswordText(boolean animate, boolean announce) {
        mPasswordEntry.reset(animate, announce);
    }

    @Override
    protected LockscreenCredential getEnteredCredential() {
        return LockscreenCredential.createPinOrNone(mPasswordEntry.getText());
    }

    @Override
    @CallSuper
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPasswordEntry = findViewById(getPasswordTextViewId());

        // Set selected property on so the view can send accessibility events.
        mPasswordEntry.setSelected(true);
        if (!pinInputFieldStyledFocusState()) {
            mPasswordEntry.setDefaultFocusHighlightEnabled(false);
        }

        mOkButton = findViewById(R.id.key_enter);

        if (Flags.bouncerUiRevamp2()) {
            mOkButton.setImageResource(R.drawable.pin_bouncer_confirm);
        }
        mDeleteButton = findViewById(R.id.delete_button);
        if (Flags.bouncerUiRevamp2()) {
            mDeleteButton.setDrawableForTransparentMode(R.drawable.pin_bouncer_delete_filled);
            mDeleteButton.setDefaultDrawable(R.drawable.pin_bouncer_delete_outline);
            mDeleteButton.setImageResource(R.drawable.pin_bouncer_delete_outline);
        }
        mDeleteButton.setVisibility(View.VISIBLE);
        mDeleteButton.setAccessibilityDelegate(
                new AccessibilityDelegate() {
                    public void onInitializeAccessibilityNodeInfo(View host,
                            AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        final AccessibilityAction longClick = new AccessibilityAction(
                                AccessibilityAction.ACTION_LONG_CLICK.getId(),
                                getContext().getResources().getString(
                                        R.string.keyguard_accessibility_pin_delete_long_click));
                        info.addAction(longClick);
                        info.setTextEntryKey(true);
                    }
                });

        mButtons[0] = findViewById(R.id.key0);
        mButtons[1] = findViewById(R.id.key1);
        mButtons[2] = findViewById(R.id.key2);
        mButtons[3] = findViewById(R.id.key3);
        mButtons[4] = findViewById(R.id.key4);
        mButtons[5] = findViewById(R.id.key5);
        mButtons[6] = findViewById(R.id.key6);
        mButtons[7] = findViewById(R.id.key7);
        mButtons[8] = findViewById(R.id.key8);
        mButtons[9] = findViewById(R.id.key9);

        mPasswordEntry.requestFocus();
        super.onFinishInflate();
        reloadColors();
    }

    NumPadKey[] getButtons() {
        return mButtons;
    }

    /**
     * Reload colors from resources.
     **/
    public void reloadColors() {
        for (NumPadKey key : mButtons) {
            key.reloadColors();
        }
        mPasswordEntry.reloadColors();
        mDeleteButton.reloadColors();
        mOkButton.reloadColors();
    }

    @Override
    public CharSequence getTitle() {
        return getContext().getString(
                com.android.internal.R.string.keyguard_accessibility_pin_unlock);
    }

    /**
     * Begins an error animation for this view.
     **/
    public void startErrorAnimation() {
        AnimatorSet animatorSet = new AnimatorSet();
        List<Animator> animators = new ArrayList();
        List<View> buttons = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            buttons.add(mButtons[i]);
        }
        buttons.add(mDeleteButton);
        buttons.add(mButtons[0]);
        buttons.add(mOkButton);

        int delay = 0;
        for (int i = 0; i < buttons.size(); i++) {
            final View button = buttons.get(i);
            AnimatorSet animateWrapper = new AnimatorSet();
            animateWrapper.setStartDelay(delay);

            ValueAnimator scaleDownAnimator =  ValueAnimator.ofFloat(1f, 0.8f);
            scaleDownAnimator.setInterpolator(Interpolators.STANDARD);
            scaleDownAnimator.addUpdateListener(valueAnimator -> {
                button.setScaleX((float) valueAnimator.getAnimatedValue());
                button.setScaleY((float) valueAnimator.getAnimatedValue());
            });
            scaleDownAnimator.setDuration(50);

            ValueAnimator scaleUpAnimator =  ValueAnimator.ofFloat(0.8f, 1f);
            scaleUpAnimator.setInterpolator(Interpolators.STANDARD);
            scaleUpAnimator.addUpdateListener(valueAnimator -> {
                button.setScaleX((float) valueAnimator.getAnimatedValue());
                button.setScaleY((float) valueAnimator.getAnimatedValue());
            });
            scaleUpAnimator.setDuration(617);

            animateWrapper.playSequentially(scaleDownAnimator, scaleUpAnimator);
            animators.add(animateWrapper);
            delay += 33;
        }
        animatorSet.playTogether(animators);
        animatorSet.start();
    }
}
