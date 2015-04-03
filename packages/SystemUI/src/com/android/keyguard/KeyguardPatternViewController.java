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

import static android.security.Flags.lockscreenIndicateDuplicateGuesses;

import static com.android.internal.util.LatencyTracker.ACTION_CHECK_CREDENTIAL;
import static com.android.internal.util.LatencyTracker.ACTION_CHECK_CREDENTIAL_UNLOCKED;
import static com.android.systemui.flags.Flags.LOCKSCREEN_ENABLE_LANDSCAPE;

import android.content.res.ColorStateList;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.uilatencystats.UiLatencyStatsManager;
import android.util.Log;
import android.util.PluralsMessageFormatter;
import android.view.MotionEvent;
import android.view.View;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternChecker;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;
import com.android.internal.widget.LockPatternView.Cell;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.keyguard.EmergencyButtonController.EmergencyButtonCallback;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.Flags;
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel;
import com.android.systemui.bouncer.shared.model.BouncerMessageStrings;
import com.android.systemui.bouncer.shared.model.LockoutMessageModel;
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer;
import com.android.systemui.classifier.FalsingClassifier;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public class KeyguardPatternViewController
        extends KeyguardInputViewController<KeyguardPatternView> {

    // how many cells the user has to cross before we poke the wakelock
    private static final int MIN_PATTERN_BEFORE_POKE_WAKELOCK = 2;

    // how long before we clear the wrong pattern
    private static final int PATTERN_CLEAR_TIMEOUT_MS = 2000;

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final LockPatternUtils mLockPatternUtils;
    private final LatencyTracker mLatencyTracker;
    private final FalsingCollector mFalsingCollector;
    private final EmergencyButtonController mEmergencyButtonController;
    private final Optional<UiLatencyStatsManager> mUiLatencyStatsManager;
    private final DevicePostureController mPostureController;
    private final DevicePostureController.Callback mPostureCallback =
            posture -> mView.onDevicePostureChanged(posture);
    private LockPatternView mLockPatternView;
    private CountDownTimer mCountdownTimer;
    private AsyncTask<?, ?, ?> mPendingLockCheck;
    private static final String TAG = "KeyguardPatternViewController";

    private EmergencyButtonCallback mEmergencyButtonCallback = new EmergencyButtonCallback() {
        @Override
        public void onEmergencyButtonClickedWhenInCall() {
            getKeyguardSecurityCallback().reset();
        }
    };

    private final LockPatternView.ExternalHapticsPlayer mExternalHapticsPlayer =
            mBouncerHapticPlayer::playPatternDotFeedback;

    /**
     * Useful for clearing out the wrong pattern after a delay
     */
    private Runnable mCancelPatternRunnable = new Runnable() {
        @Override
        public void run() {
            mLockPatternView.clearPattern();
        }
    };

    private class UnlockPatternListener implements LockPatternView.OnPatternListener {

        @Override
        public void onPatternStart() {
            mLockPatternView.removeCallbacks(mCancelPatternRunnable);
            mMessageAreaController.setMessage("");
        }

        @Override
        public void onPatternCleared() {
        }

        @Override
        public void onPatternCellAdded(List<Cell> pattern) {
            getKeyguardSecurityCallback().userActivity();
            getKeyguardSecurityCallback().onUserInput();
        }

        @Override
        public void onPatternDetected(final List<LockPatternView.Cell> pattern, byte patternSize) {
            mKeyguardUpdateMonitor.setCredentialAttempted();
            mLockPatternView.disableInput();
            if (mPendingLockCheck != null) {
                mPendingLockCheck.cancel(false);
            }

            final int userId = mSelectedUserInteractor.getSelectedUserId();
            if (pattern.size() < LockPatternUtils.MIN_PATTERN_REGISTER_FAIL) {
                // Treat single-sized patterns as erroneous taps.
                if (pattern.size() == 1) {
                    mFalsingCollector.updateFalseConfidence(FalsingClassifier.Result.falsed(
                            0.7, getClass().getSimpleName(), "empty pattern input"));
                }
                mLockPatternView.enableInput();
                onPatternChecked(userId, false, Duration.ZERO,
                        false /* not valid - too short */, false /* isDuplicate */);
                return;
            }

            mLatencyTracker.onActionStart(ACTION_CHECK_CREDENTIAL);
            mLatencyTracker.onActionStart(ACTION_CHECK_CREDENTIAL_UNLOCKED);
            mPendingLockCheck = LockPatternChecker.checkCredential(
                    mLockPatternUtils,
                    LockscreenCredential.createPattern(pattern, patternSize),
                    userId,
                    new LockPatternChecker.OnCheckCallback() {

                        @Override
                        public void onEarlyMatched() {
                            mLatencyTracker.onActionEnd(ACTION_CHECK_CREDENTIAL);
                            onPatternChecked(
                                    userId,
                                    true /* matched */,
                                    Duration.ZERO /* timeout */,
                                    true /* isValidPattern */,
                                    false /* isDuplicate */);
                        }

                        @Override
                        public void onChecked(VerifyCredentialResponse response) {
                            boolean matched = response.isMatched();
                            Duration timeout = response.getTimeout();
                            boolean isDuplicate = lockscreenIndicateDuplicateGuesses()
                                    && response.isCredAlreadyTried();
                            mLatencyTracker.onActionEnd(ACTION_CHECK_CREDENTIAL_UNLOCKED);
                            mLockPatternView.enableInput();
                            mPendingLockCheck = null;
                            if (!matched) {
                                onPatternChecked(
                                        userId,
                                        false /* matched */,
                                        timeout,
                                        true /* isValidPattern */,
                                        isDuplicate);
                            }
                        }

                        @Override
                        public void onCancelled() {
                            // We already got dismissed with the early matched callback, so we
                            // cancelled the check. However, we still need to note down the latency.
                            mLatencyTracker.onActionEnd(ACTION_CHECK_CREDENTIAL_UNLOCKED);
                        }
                    });
            if (pattern.size() > MIN_PATTERN_BEFORE_POKE_WAKELOCK) {
                getKeyguardSecurityCallback().userActivity();
                getKeyguardSecurityCallback().onUserInput();
            }
        }

        private void onPatternChecked(int userId, boolean matched, Duration timeout,
                boolean isValidPattern, boolean isDuplicate) {
            boolean dismissKeyguard = mSelectedUserInteractor.getSelectedUserId() == userId;
            if (matched) {
                mBouncerHapticPlayer.playAuthenticationFeedback(
                        /* authenticationSucceeded= */true
                );
                getKeyguardSecurityCallback().reportUnlockAttempt(userId, true,
                        Duration.ZERO, isDuplicate);
                if (dismissKeyguard) {
                    mLockPatternView.setDisplayMode(LockPatternView.DisplayMode.Correct);
                    mLatencyTracker.onActionStart(LatencyTracker.ACTION_LOCKSCREEN_UNLOCK);
                    mUiLatencyStatsManager.ifPresent(m -> m.reportEvent(
                            UiLatencyStatsManager.EVENT_LOCK_SCREEN_UNLOCK_START,
                            SystemClock.elapsedRealtime()));
                    Log.i(TAG,
                            "StartUnlock. "
                            + "User: " + userId
                            + " TS: " + SystemClock.uptimeMillis()
                    );
                    getKeyguardSecurityCallback().dismiss(true, userId, SecurityMode.Pattern);
                }
            } else {
                mBouncerHapticPlayer.playAuthenticationFeedback(
                        /* authenticationSucceeded= */false
                );
                mLockPatternView.setDisplayMode(LockPatternView.DisplayMode.Wrong);
                if (isValidPattern) {
                    getKeyguardSecurityCallback()
                            .reportUnlockAttempt(userId, false, timeout, isDuplicate);
                    if (timeout.isPositive()) {
                        Duration lockoutEndTime = mLockPatternUtils.getLockoutEndTime(userId);
                        handleAttemptLockout(lockoutEndTime);
                    }
                }
                if (timeout.isZero()) {
                    int wrongPatternStringId =
                            isDuplicate
                                    ? R.string.kg_primary_auth_duplicate_guess_pattern
                                    : R.string.kg_wrong_pattern;
                    mMessageAreaController.setMessage(wrongPatternStringId);
                    mLockPatternView.postDelayed(mCancelPatternRunnable, PATTERN_CLEAR_TIMEOUT_MS);
                }
            }
        }
    }

    protected KeyguardPatternViewController(KeyguardPatternView view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecurityMode securityMode,
            LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback,
            LatencyTracker latencyTracker,
            FalsingCollector falsingCollector,
            EmergencyButtonController emergencyButtonController,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            DevicePostureController postureController, FeatureFlags featureFlags,
            SelectedUserInteractor selectedUserInteractor, BouncerHapticPlayer bouncerHapticPlayer,
            Optional<UiLatencyStatsManager> uiLatencyStatsManager
    ) {
        super(view, securityMode, keyguardSecurityCallback, emergencyButtonController,
                messageAreaControllerFactory, featureFlags, selectedUserInteractor,
                bouncerHapticPlayer);
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mLockPatternUtils = lockPatternUtils;
        mLatencyTracker = latencyTracker;
        mFalsingCollector = falsingCollector;
        mEmergencyButtonController = emergencyButtonController;
        view.setIsLockScreenLandscapeEnabled(
                featureFlags.isEnabled(LOCKSCREEN_ENABLE_LANDSCAPE));
        mLockPatternView = mView.findViewById(R.id.lockPatternView);
        mPostureController = postureController;
        mUiLatencyStatsManager = uiLatencyStatsManager;
    }

    @Override
    public void onInit() {
        super.onInit();
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        int userId = mSelectedUserInteractor.getSelectedUserId();
        mLockPatternView.setOnPatternListener(new UnlockPatternListener());
        mLockPatternView.setSaveEnabled(false);
        boolean visiblePatternEnabled = mLockPatternUtils.isVisiblePatternEnabled(
                mSelectedUserInteractor.getSelectedUserId());
        mLockPatternView.setInStealthMode(!visiblePatternEnabled);
        if (Flags.bouncerUiRevamp2()) {
            mLockPatternView.setKeepDotActivated(visiblePatternEnabled);
        }
        mLockPatternView.setLockPatternUtils(mLockPatternUtils);
        mLockPatternView.setLockPatternSize(mLockPatternUtils.getLockPatternSize(userId));
        mLockPatternView.setVisibleDots(mLockPatternUtils.isVisibleDotsEnabled(userId));
        mLockPatternView.setShowErrorPath(mLockPatternUtils.isShowErrorPath(userId));
        mLockPatternView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mFalsingCollector.avoidGesture();
            }
            return false;
        });
        mEmergencyButtonController.setEmergencyButtonCallback(mEmergencyButtonCallback);

        View cancelBtn = mView.findViewById(R.id.cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(view -> {
                getKeyguardSecurityCallback().reset();
                getKeyguardSecurityCallback().onCancelClicked();
            });
        }
        mView.onDevicePostureChanged(mPostureController.getDevicePosture());
        mPostureController.addCallback(mPostureCallback);
        // if the user is currently locked out, enforce it.
        Duration lockoutEndTime = mLockPatternUtils.getLockoutEndTime(
                mSelectedUserInteractor.getSelectedUserId());
        if (!lockoutEndTime.isZero()) {
            handleAttemptLockout(lockoutEndTime);
        }
        mLockPatternView.setExternalHapticsPlayer(mExternalHapticsPlayer);
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mLockPatternView.setOnPatternListener(null);
        mLockPatternView.setOnTouchListener(null);
        mEmergencyButtonController.setEmergencyButtonCallback(null);
        View cancelBtn = mView.findViewById(R.id.cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(null);
        }
        mPostureController.removeCallback(mPostureCallback);
        mLockPatternView.setExternalHapticsPlayer(null);
    }

    @Override
    public void reset() {
        // reset lock pattern
        mLockPatternView.setInStealthMode(!mLockPatternUtils.isVisiblePatternEnabled(
                mSelectedUserInteractor.getSelectedUserId()));
        mLockPatternView.enableInput();
        mLockPatternView.setEnabled(true);
        mLockPatternView.clearPattern();

        displayDefaultSecurityMessage();
    }

    @Override
    public void onResume(int reason) {
        super.onResume(reason);
    }

    @Override
    public void onPause() {
        super.onPause();

        if (mCountdownTimer != null) {
            mCountdownTimer.cancel();
            mCountdownTimer = null;
        }

        if (mPendingLockCheck != null) {
            mPendingLockCheck.cancel(false);
            mPendingLockCheck = null;
        }
        displayDefaultSecurityMessage();
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void showPromptReason(int reason) {
        /// TODO: move all this logic into the MessageAreaController?
        int resId =  0;
        switch (reason) {
            case PROMPT_REASON_RESTART:
                resId = R.string.kg_prompt_reason_restart_pattern;
                break;
            case PROMPT_REASON_RESTART_FOR_MAINLINE_UPDATE:
                resId = R.string.kg_prompt_after_update_pattern;
                break;
            case PROMPT_REASON_TIMEOUT:
                resId = R.string.kg_prompt_reason_timeout_pattern;
                break;
            case PROMPT_REASON_DEVICE_ADMIN:
                resId = R.string.kg_prompt_reason_device_admin;
                break;
            case PROMPT_REASON_USER_REQUEST:
                resId = R.string.kg_prompt_after_user_lockdown_pattern;
                break;
            case PROMPT_REASON_PREPARE_FOR_UPDATE:
                resId = R.string.kg_prompt_added_security_pattern;
                break;
            case PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT:
                resId = R.string.kg_prompt_reason_timeout_pattern;
                break;
            case PROMPT_REASON_TRUSTAGENT_EXPIRED:
                resId = R.string.kg_prompt_reason_timeout_pattern;
                break;
            case PROMPT_REASON_ADAPTIVE_AUTH_REQUEST:
                resId = R.string.kg_prompt_after_adaptive_auth_lock;
                break;
            case PROMPT_REASON_NONE:
                break;
            default:
                resId = R.string.kg_prompt_reason_timeout_pattern;
                break;
        }
        if (resId != 0) {
            mMessageAreaController.setMessage(getResources().getText(resId), /* animate= */ false);
        }
    }

    @Override
    public void showMessage(CharSequence message, ColorStateList colorState, boolean animated) {
        if (mMessageAreaController == null) {
            return;
        }
        if (colorState != null) {
            mMessageAreaController.setNextMessageColor(colorState);
        }
        mMessageAreaController.setMessage(message, animated);
    }

    @Override
    public void startAppearAnimation() {
        super.startAppearAnimation();
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return mView.startDisappearAnimation(
                mKeyguardUpdateMonitor.needsSlowUnlockTransition(), finishRunnable);
    }

    private void displayDefaultSecurityMessage() {
        mMessageAreaController.setMessage(getInitialMessageResId());
    }

    private void handleAttemptLockout(Duration lockoutEndTime) {
        mLockPatternView.clearPattern();
        mLockPatternView.setEnabled(false);
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        final long secondsInFuture = (long) Math.ceil(
                (lockoutEndTime.toMillis() - elapsedRealtime) / 1000.0);
        getKeyguardSecurityCallback().onAttemptLockoutStart(secondsInFuture);
        mCountdownTimer = new CountDownTimer(secondsInFuture * 1000, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                final long secondsRemaining = Math.round(millisUntilFinished / 1000.0);
                LockoutMessageModel lockoutMessageModel =
                        BouncerMessageStrings.INSTANCE.primaryAuthLockedOut(
                                AuthenticationMethodModel.Pattern.INSTANCE, secondsRemaining);
                mMessageAreaController.setMessage(
                        PluralsMessageFormatter.format(
                                mView.getResources(),
                                lockoutMessageModel.primaryFormatterArgs(),
                                lockoutMessageModel.getPrimaryMessage()),
                        /* animate= */ false
                );
            }

            @Override
            public void onFinish() {
                mLockPatternView.setEnabled(true);
                displayDefaultSecurityMessage();
            }

        }.start();
    }

    @Override
    protected int getInitialMessageResId() {
        return R.string.keyguard_enter_your_pattern;
    }
}
