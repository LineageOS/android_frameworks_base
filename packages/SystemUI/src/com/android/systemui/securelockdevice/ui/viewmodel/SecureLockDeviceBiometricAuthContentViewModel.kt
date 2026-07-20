/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.securelockdevice.ui.viewmodel

import android.hardware.biometrics.BiometricPrompt
import android.os.SystemClock
import android.security.Flags.secureLockDevice
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.app.tracing.coroutines.traceCoroutine
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.biometrics.shared.model.BiometricModality
import com.android.systemui.biometrics.ui.viewmodel.BiometricAuthIconViewModel
import com.android.systemui.biometrics.ui.viewmodel.PromptAuthState
import com.android.systemui.bouncer.domain.interactor.BouncerActionButtonInteractor
import com.android.systemui.bouncer.shared.model.SecureLockDeviceBouncerActionButtonModel
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer
import com.android.systemui.deviceentry.domain.interactor.BiometricMessageInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFingerprintAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.SystemUIDeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.shared.model.FaceMessage
import com.android.systemui.deviceentry.shared.model.FingerprintMessage
import com.android.systemui.deviceentry.shared.model.SuccessFaceAuthenticationStatus
import com.android.systemui.deviceentry.ui.viewmodel.AlternateBouncerUdfpsAccessibilityOverlayViewModel
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.securelockdevice.domain.interactor.SecureLockDeviceInteractor
import com.android.systemui.util.kotlin.pairwise
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Models UI state for the content on the bouncer overlay during secure lock device. */
class SecureLockDeviceBiometricAuthContentViewModel
@AssistedInject
constructor(
    accessibilityManager: AccessibilityManager,
    private val actionButtonInteractor: BouncerActionButtonInteractor,
    biometricAuthIconViewModelFactory: BiometricAuthIconViewModel.Factory,
    biometricMessageInteractor: BiometricMessageInteractor,
    private val bouncerHapticPlayer: BouncerHapticPlayer,
    private val deviceEntryFaceAuthInteractor: SystemUIDeviceEntryFaceAuthInteractor,
    deviceEntryFingerprintAuthInteractor: DeviceEntryFingerprintAuthInteractor,
    private val secureLockDeviceInteractor: SecureLockDeviceInteractor,
    val udfpsAccessibilityOverlayViewModel: AlternateBouncerUdfpsAccessibilityOverlayViewModel,
    val interactionJankMonitor: InteractionJankMonitor,
) : HydratedActivatable(enableEnqueuedActivations = true) {
    /** @see SecureLockDeviceInteractor.isSecureLockDeviceEnabled */
    val isSecureLockDeviceEnabled = secureLockDeviceInteractor.isSecureLockDeviceEnabled

    /** @see SecureLockDeviceInteractor.shouldListenForBiometricAuth */
    val shouldListenForBiometricAuth: Boolean by
        secureLockDeviceInteractor.shouldListenForBiometricAuth.hydratedStateOf(
            initialValue = false
        )

    /** @see SecureLockDeviceInteractor.enrolledStrongBiometricModalities */
    val enrolledStrongBiometrics = secureLockDeviceInteractor.enrolledStrongBiometricModalities

    /** @see SecureLockDeviceInteractor.requiresStrongBiometricAuthForSecureLockDevice */
    private val requiresStrongBiometricAuthForSecureLockDevice: StateFlow<Boolean> =
        secureLockDeviceInteractor.requiresStrongBiometricAuthForSecureLockDevice

    private val _isAuthenticating: MutableStateFlow<Boolean> = MutableStateFlow(false)
    /** If the user is currently authenticating (i.e. at least one biometric is scanning). */
    val isAuthenticating: Flow<Boolean> = _isAuthenticating.asStateFlow()

    private val _showingError: MutableStateFlow<Boolean> = MutableStateFlow(false)
    /** Whether an error message is currently being shown. */
    val showingError: Flow<Boolean> = _showingError.asStateFlow()

    private val _isAuthenticated: MutableStateFlow<PromptAuthState> =
        MutableStateFlow(PromptAuthState(false))
    /** If the user has successfully authenticated and confirmed (when explicitly required). */
    val isAuthenticated: Flow<PromptAuthState> = _isAuthenticated.asStateFlow()

    /**
     * If authenticated and confirmation is not required, or authenticated and explicitly confirmed
     * and confirmation is required.
     */
    val isAuthenticationComplete: Boolean by
        isAuthenticated
            .map { authState ->
                authState.isAuthenticatedAndConfirmed ||
                    authState.isAuthenticatedAndExplicitlyConfirmed
            }
            .hydratedStateOf("isAuthenticationComplete", initialValue = false)

    /**
     * True when the biometric authentication success animation has finished playing, and the
     * biometric auth UI can be dismissed.
     */
    private val _isReadyToDismissBiometricAuth = MutableStateFlow(false)
    val isReadyToDismissBiometricAuth: Boolean by
        _isReadyToDismissBiometricAuth.hydratedStateOf(
            "isReadyToDismissBiometricAuth",
            initialValue = false,
        )

    /** Whether the biometric auth view is currently visible. */
    var isVisible: Boolean by mutableStateOf(false)
        private set

    /**
     * Models UI state for the biometric icon shown in secure lock device biometric authentication.
     */
    val iconViewModel: BiometricAuthIconViewModel by lazy {
        biometricAuthIconViewModelFactory.create(
            promptViewModel = null,
            secureLockDeviceViewModel = this,
        )
    }

    private val isRetrySupported: Flow<Boolean> = enrolledStrongBiometrics.map { it.hasFaceOnly }

    private val _canTryAgainNow = MutableStateFlow(false)

    /**
     * If authentication can be manually restarted via the try again button or touching a
     * fingerprint sensor.
     */
    val canTryAgainNow: Flow<Boolean> =
        combine(_canTryAgainNow, isAuthenticated, isRetrySupported) {
            readyToTryAgain,
            authState,
            supportsRetry ->
            readyToTryAgain && supportsRetry && authState.isNotAuthenticated
        }

    /**
     * The bouncer action button (Confirm / Try again). If `null`, the button should not be shown.
     */
    var actionButton: SecureLockDeviceBouncerActionButtonModel? by mutableStateOf(null)
        private set

    /** Face help message. */
    private val faceHelpMessage: Flow<FaceMessage> = biometricMessageInteractor.faceHelpMessage

    /** Face error message. */
    private val faceErrorMessage: Flow<FaceMessage> = biometricMessageInteractor.faceErrorMessage

    /** Face failure message. */
    private val faceFailureMessage: Flow<FaceMessage> =
        biometricMessageInteractor.faceFailureMessage

    /** Fingerprint help message. */
    private val fingerprintHelpMessage: Flow<FingerprintMessage> =
        biometricMessageInteractor.fingerprintHelpMessage

    /** Fingerprint error message. */
    private val fingerprintErrorMessage: Flow<FingerprintMessage> =
        biometricMessageInteractor.fingerprintErrorMessage

    /** Fingerprint failure message. */
    private val fingerprintFailureMessage: Flow<FingerprintMessage> =
        biometricMessageInteractor.fingerprintFailMessage

    /** Fingerprint success status. */
    private val fingerprintSuccessStatus: Flow<SuccessFingerprintAuthenticationStatus> =
        deviceEntryFingerprintAuthInteractor.fingerprintSuccess

    /** Emits on face authentication success. */
    private val faceSuccessStatus: Flow<SuccessFaceAuthenticationStatus> =
        deviceEntryFaceAuthInteractor.faceSuccess

    private var lastAnimatedFaceAuthSuccessTime: Long? by mutableStateOf(null)

    private var activationTime: Long = 0

    private var displayErrorJob: Job? = null

    // When a11y enabled, increase message delay to ensure messages get read
    private val displayErrorLength =
        accessibilityManager
            .getRecommendedTimeoutMillis(
                BiometricPrompt.HIDE_DIALOG_DELAY,
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT,
            )
            .toLong()

    /**
     * Show a temporary error associated with an optional [failedModality] and play
     * [hapticFeedback].
     *
     * The [messageAfterError] will be shown via [showAuthenticating] when [authenticateAfterError]
     * is set (or via [showHelp] when not set) after the error is dismissed.
     *
     * The error is ignored if the user has already authenticated.
     */
    @VisibleForTesting
    suspend fun showTemporaryError(
        authenticateAfterError: Boolean,
        hapticFeedback: Boolean = true,
        failedModality: BiometricModality = BiometricModality.None,
        // False for screenshot testing only - displays an error without clearing it
        clearError: Boolean = true,
    ) = coroutineScope {
        if (_isAuthenticated.value.isAuthenticated) {
            return@coroutineScope
        }
        _canTryAgainNow.value = supportsRetry(failedModality)
        _isAuthenticating.value = false
        _showingError.value = true
        _isAuthenticated.value = PromptAuthState(false)

        if (hapticFeedback) {
            bouncerHapticPlayer.playAuthenticationFeedback(/* authenticationSucceeded= */ false)
        }

        displayErrorJob?.cancel()
        if (clearError) {
            displayErrorJob = launch {
                delay(displayErrorLength)
                if (authenticateAfterError) {
                    showAuthenticating()
                } else {
                    showHelp()
                }
            }
        }
    }

    private fun supportsRetry(failedModality: BiometricModality) =
        failedModality == BiometricModality.Face

    /**
     * Show a persistent help message.
     *
     * Will be shown even if the user has already authenticated.
     */
    @VisibleForTesting
    fun showHelp() {
        val alreadyAuthenticated = _isAuthenticated.value.isAuthenticated
        if (!alreadyAuthenticated) {
            _isAuthenticating.value = false
            _isAuthenticated.value = PromptAuthState(false)
        }

        _showingError.value = false
        displayErrorJob?.cancel()
        displayErrorJob = null
    }

    /** Show the user that biometrics are actively running and set [isAuthenticating]. */
    @VisibleForTesting
    fun showAuthenticating(isRetry: Boolean = false) {
        _isAuthenticating.value = true
        deviceEntryFaceAuthInteractor.onSecureLockDeviceBiometricAuthRequested()

        _isAuthenticated.value = PromptAuthState(false)

        // reset the try again button(s) after the user attempts a retry
        if (isRetry) {
            _canTryAgainNow.value = false
        }

        _showingError.value = false
        displayErrorJob?.cancel()
        displayErrorJob = null
    }

    /**
     * Show successful authentication, set [isAuthenticated], and enter the device, or prompt for
     * explicit confirmation (if required).
     */
    @VisibleForTesting
    suspend fun showAuthenticated(modality: BiometricModality) = coroutineScope {
        _isAuthenticating.value = false
        val needsUserConfirmation = needsExplicitConfirmation(modality)
        _isAuthenticated.value = PromptAuthState(true, modality, needsUserConfirmation)

        if (!needsUserConfirmation) {
            secureLockDeviceInteractor.suppressBouncerMessages()
            bouncerHapticPlayer.playAuthenticationFeedback(/* authenticationSucceeded= */ true)
        }

        _showingError.value = false
        displayErrorJob?.cancel()
        displayErrorJob = null
        if (needsUserConfirmation) {
            showHelp()
        }
    }

    /** Whether authentication by [modality] requires explicit user confirmation. */
    private fun needsExplicitConfirmation(modality: BiometricModality): Boolean {
        // Only worry about confirmationRequired if face was used to unlock
        if (modality == BiometricModality.Face) {
            return true
        }
        // fingerprint only never requires confirmation
        return false
    }

    /**
     * Set the prompt's auth state to authenticated and confirmed.
     *
     * This should only be used after [showAuthenticated] when the operation requires explicit user
     * confirmation.
     */
    private suspend fun confirmAuthenticated() = coroutineScope {
        val authState = _isAuthenticated.value
        if (authState.isNotAuthenticated) {
            Log.w(TAG, "Cannot confirm authenticated when not authenticated")
            return@coroutineScope
        }
        secureLockDeviceInteractor.suppressBouncerMessages()
        _isAuthenticated.value = authState.asExplicitlyConfirmed()
        bouncerHapticPlayer.playAuthenticationFeedback(/* authenticationSucceeded= */ true)

        _showingError.value = false
        displayErrorJob?.cancel()
        displayErrorJob = null
    }

    private suspend fun hasFingerprint(): Boolean {
        return enrolledStrongBiometrics.first().hasFingerprint
    }

    private fun CoroutineScope.listenForFaceMessages() {
        // Listen for any events from face authentication and update the child view models
        launch {
            faceErrorMessage.collectLatest {
                showTemporaryError(
                    authenticateAfterError = hasFingerprint(),
                    failedModality = BiometricModality.Face,
                )
            }
        }

        launch {
            faceFailureMessage.collectLatest {
                showTemporaryError(
                    authenticateAfterError = hasFingerprint(),
                    failedModality = BiometricModality.Face,
                )
            }
        }

        launch {
            faceHelpMessage.collectLatest {
                showTemporaryError(
                    authenticateAfterError = hasFingerprint(),
                    hapticFeedback = false,
                )
            }
        }

        // This is required to ensure that a stale face authentication success will not
        // re-authenticate the user (e.g. if secure lock device biometric auth is interrupted
        // after authenticating a user's face but before the user confirmation)
        launch {
            faceSuccessStatus.debounce(DEBOUNCE_FACE_AUTH_SUCCESS_MS).collectLatest {
                if (
                    it.createdAt >= activationTime &&
                        it.createdAt != secureLockDeviceInteractor.lastProcessedFaceAuthSuccessTime
                ) {
                    showAuthenticated(modality = BiometricModality.Face)
                    lastAnimatedFaceAuthSuccessTime = it.createdAt
                }
            }
        }
    }

    private fun CoroutineScope.listenForFingerprintMessages() {
        // Listen for any events from fingerprint authentication and update the child view
        // models
        launch {
            fingerprintErrorMessage.collectLatest {
                showTemporaryError(
                    authenticateAfterError = true,
                    failedModality = BiometricModality.Fingerprint,
                )
            }
        }

        launch {
            fingerprintFailureMessage.collectLatest {
                showTemporaryError(
                    authenticateAfterError = true,
                    failedModality = BiometricModality.Fingerprint,
                )
            }
        }

        launch {
            fingerprintHelpMessage.collectLatest {
                showTemporaryError(authenticateAfterError = true, hapticFeedback = false)
            }
        }

        launch {
            fingerprintSuccessStatus.collectLatest {
                showAuthenticated(modality = BiometricModality.Fingerprint)
            }
        }
    }

    /** Notifies that the user has confirmed the strong face authentication success on the UI. */
    fun onConfirmButtonClicked() {
        enqueueOnActivatedScope { confirmAuthenticated() }
    }

    /**
     * Notifies that the user has pressed the try again button to retry authentication during secure
     * lock device.
     */
    fun onTryAgainButtonClicked() {
        // Update state when try again is clicked and then request for authentication
        secureLockDeviceInteractor.onUserRequestedRetry()
        showAuthenticating(isRetry = true)
    }

    /**
     * Listener for confirm or try again button click events during secure lock device biometric
     * auth.
     */
    fun onActionButtonClicked(actionButtonModel: SecureLockDeviceBouncerActionButtonModel) {
        when (actionButtonModel) {
            is SecureLockDeviceBouncerActionButtonModel.ConfirmStrongBiometricAuthButtonModel -> {
                if (secureLockDevice()) {
                    onConfirmButtonClicked()
                }
            }
            is SecureLockDeviceBouncerActionButtonModel.TryAgainButtonModel -> {
                if (secureLockDevice()) {
                    onTryAgainButtonClicked()
                }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): SecureLockDeviceBiometricAuthContentViewModel
    }

    /**
     * Called to activate the view model and start listening for biometric authentication events.
     */
    override suspend fun onActivated(): Nothing {
        activationTime = SystemClock.elapsedRealtime()
        coroutineScope {
            launch {
                requiresStrongBiometricAuthForSecureLockDevice.pairwise(false).collect { (prev, cur)
                    ->
                    if (!prev && cur) {
                        secureLockDeviceInteractor.onBiometricAuthRequested()
                    }
                }
            }

            launch {
                secureLockDeviceInteractor.isBiometricAuthVisible.collectLatest {
                    isBiometricAuthVisible ->
                    if (isBiometricAuthVisible) {
                        launch { traceCoroutine("iconViewModel") { iconViewModel.activate() } }
                        showAuthenticating()

                        listenForFaceMessages()
                        listenForFingerprintMessages()

                        launch {
                            actionButtonInteractor.secureLockDeviceActionButton.collect {
                                actionButton =
                                    when (it) {
                                        is SecureLockDeviceBouncerActionButtonModel.ConfirmStrongBiometricAuthButtonModel,
                                        is SecureLockDeviceBouncerActionButtonModel.TryAgainButtonModel ->
                                            it
                                        else -> null
                                    }
                            }
                        }

                        launch {
                            isAuthenticated.collect {
                                secureLockDeviceInteractor.onBiometricAuthenticatedStateUpdated(it)
                            }
                        }

                        launch {
                            canTryAgainNow.collect { canTryAgainNow ->
                                secureLockDeviceInteractor.onRetryAvailableChanged(canTryAgainNow)
                            }
                        }

                        launch {
                            showingError.collect { showingError ->
                                secureLockDeviceInteractor.onShowingError(showingError)
                            }
                        }

                        launch {
                            _isReadyToDismissBiometricAuth
                                .filter { it }
                                .collect {
                                    secureLockDeviceInteractor.onReadyToDismissBiometricAuth()
                                    isVisible = false
                                }
                        }
                    } else {
                        _isAuthenticating.value = false
                        _showingError.value = false
                    }
                }
            }
            awaitCancellation()
        }
    }

    /** Called when the view model is deactivated to cancel any active jobs. */
    override suspend fun onDeactivated() {
        displayErrorJob?.cancel()
        displayErrorJob = null
        if (secureLockDeviceInteractor.isBiometricAuthVisible.value) {
            secureLockDeviceInteractor.onBiometricAuthUiHidden()
        }
        isVisible = false
    }

    /**
     * Called from [com.android.keyguard.KeyguardSecureLockDeviceBiometricAuthViewController] when
     * [SceneContainerFlag.isEnabled] is false or from
     * [com.android.systemui.bouncer.ui.composable.BouncerContent] when
     * [SceneContainerFlag.isEnabled] is true, to indicate that the secure lock device biometric
     * authentication screen should be shown.
     */
    fun startAppearAnimation() {
        isVisible = true
    }

    // TODO (b/427071498): remove when SceneContainerFlag is removed
    /**
     * Runs actions to complete when the disappear animation has finished in the legacy keyguard
     * implementation.
     */
    fun onDisappearAnimationFinished() {
        SceneContainerFlag.assertInLegacyMode()
        secureLockDeviceInteractor.onDisappearAnimationFinished()
    }

    /**
     * Indicates the final animation (i.e. successful fingerprint, face confirmed, etc.) has
     * finished playing and the biometric auth screen can be dismissed
     */
    private fun onReadyToDismissBiometricAuth() {
        _isReadyToDismissBiometricAuth.value = true
    }

    /**
     * Indicates the pending face authentication confirmation animation has played and updates
     * [SecureLockDeviceInteractor.lastProcessedFaceAuthSuccessTime]
     */
    private fun onPendingConfirmationAnimationPlayed() {
        if (lastAnimatedFaceAuthSuccessTime != null) {
            secureLockDeviceInteractor.lastProcessedFaceAuthSuccessTime =
                lastAnimatedFaceAuthSuccessTime
        }
    }

    fun onIconAnimationFinished() {
        if (iconViewModel.isPendingConfirmationState) {
            onPendingConfirmationAnimationPlayed()
        } else if (isAuthenticationComplete) {
            onReadyToDismissBiometricAuth()
        }
    }

    companion object {
        const val TAG = "SecureLockDeviceBiometricAuthContentViewModel"
        const val DEBOUNCE_FACE_AUTH_SUCCESS_MS = 500L
    }
}
