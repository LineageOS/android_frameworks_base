/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.biometrics.domain.interactor

import android.app.StatusBarManager.SESSION_BIOMETRIC_PROMPT
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.IIdentityCheckStateListener
import android.hardware.biometrics.PromptInfo
import android.util.Log
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.biometrics.BiometricPromptLogger
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.Utils.getCredentialType
import com.android.systemui.biometrics.Utils.isDeviceCredentialAllowed
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.PromptRepository
import com.android.systemui.biometrics.domain.model.BiometricOperationInfo
import com.android.systemui.biometrics.domain.model.BiometricPromptRequest
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.BiometricUserInfo
import com.android.systemui.biometrics.shared.model.FallbackOptionModel
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.biometrics.shared.model.WatchRangingState
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.domain.interactor.DisplayStateInteractor
import com.android.systemui.display.shared.model.isDefaultOrientation
import com.android.systemui.kairos.awaitClose
import com.android.systemui.log.SessionTracker
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.util.kotlin.combine
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * Business logic for BiometricPrompt's biometric view variants (face, fingerprint, coex, etc.).
 *
 * This is used to cache the calling app's options that were given to the underlying authenticate
 * APIs and should be set before any UI is shown to the user.
 *
 * There can be at most one request active at a given time. Use [resetPrompt] when no request is
 * active to clear the cache.
 *
 * Views that use credential fallback should use [PromptCredentialInteractor] instead.
 */
interface PromptSelectorInteractor {

    /** Static metadata about the current prompt. */
    val prompt: Flow<BiometricPromptRequest.Biometric?>

    /** The kind of prompt to use (biometric, pin, pattern, etc.). */
    val promptKind: StateFlow<PromptKind>

    /** The modalities available in the prompt */
    val modalities: Flow<BiometricModalities>

    /** If using a credential is allowed. */
    val isCredentialAllowed: Flow<Boolean>

    /** If Identity Check is active */
    val isIdentityCheckActive: Flow<Boolean>

    /** The current watch ranging state */
    val watchRangingState: Flow<WatchRangingState>

    /** List of fallback options provided by prompt caller */
    val fallbackOptions: Flow<List<FallbackOptionModel>>

    /**
     * The kind of credential the user may use as a fallback or [PromptKind.None] if unknown or not
     * [isCredentialAllowed]. This is separate from [promptKind], even if [promptKind] is
     * [PromptKind.Biometric], [credentialKind] should still be one of pin/pattern/password.
     */
    val credentialKind: Flow<PromptKind>

    /** PromptKind.Pattern grid size */
    val patternSize: Flow<Byte>

    /**
     * If the API caller or the user's personal preferences require explicit confirmation after
     * successful authentication.
     */
    val isConfirmationRequired: Flow<Boolean>

    /** Fingerprint sensor type */
    val fingerprintSensorType: Flow<FingerprintSensorType>

    /** If Identity Check fallback needs to be cleared */
    val clearIdentityCheckFallback: Flow<Boolean>

    /** The current [BiometricPromptView] shown in the prompt */
    val currentView: Flow<BiometricPromptView>

    /** Switch to the credential view. */
    fun onSwitchToCredential()

    /** Switch to the fallback view. */
    fun onSwitchToFallback()

    /** Switch to the auth view. */
    fun onSwitchToAuth()

    /**
     * Update the kind of prompt (biometric prompt w/ or w/o sensor icon, pin view, pattern view,
     * etc).
     */
    fun setPrompt(
        promptInfo: PromptInfo,
        effectiveUserId: Int,
        requestId: Long,
        modalities: BiometricModalities,
        challenge: Long,
        opPackageName: String,
        onSwitchToCredential: Boolean,
        isLandscape: Boolean,
        updateView: Boolean = true,
    )

    /** Unset the current authentication request. */
    fun resetPrompt(requestId: Long)
}

@SysUISingleton
class PromptSelectorInteractorImpl
@Inject
constructor(
    fingerprintPropertyRepository: FingerprintPropertyRepository,
    private val displayStateInteractor: DisplayStateInteractor,
    private val credentialInteractor: CredentialInteractor,
    private val promptRepository: PromptRepository,
    private val lockPatternUtils: LockPatternUtils,
    private val biometricManager: BiometricManager?,
    @Background private val bgScope: CoroutineScope,
    private val sessionTracker: SessionTracker,
    private val biometricPromptLogger: BiometricPromptLogger,
) : PromptSelectorInteractor {

    override val prompt: Flow<BiometricPromptRequest.Biometric?> =
        combine(
            promptRepository.promptInfo,
            promptRepository.challenge,
            promptRepository.userId,
            promptRepository.promptKind,
            promptRepository.opPackageName,
            promptRepository.modalities,
        ) { promptInfo, challenge, userId, kind, opPackageName, modalities ->
            if (
                promptInfo == null || userId == null || challenge == null || opPackageName == null
            ) {
                return@combine null
            }
            BiometricPromptRequest.Biometric(
                info = promptInfo,
                userInfo =
                    BiometricUserInfo(
                        userId = userId,
                        deviceCredentialOwnerId =
                            credentialInteractor.getCredentialOwnerOrSelfId(userId),
                    ),
                operationInfo = BiometricOperationInfo(gatekeeperChallenge = challenge),
                modalities = modalities,
                opPackageName = opPackageName,
            )
        }

    override val promptKind: StateFlow<PromptKind> = promptRepository.promptKind

    override val modalities: StateFlow<BiometricModalities> = promptRepository.modalities

    override val isConfirmationRequired: Flow<Boolean> =
        promptRepository.isConfirmationRequired.distinctUntilChanged()

    override val isCredentialAllowed: Flow<Boolean> =
        promptRepository.promptInfo
            .map { info -> info?.isDeviceCredentialAllowed ?: false }
            .distinctUntilChanged()

    override val isIdentityCheckActive: Flow<Boolean> =
        promptRepository.promptInfo
            .map { info -> info?.isIdentityCheckActive ?: false }
            .distinctUntilChanged()

    override val clearIdentityCheckFallback: Flow<Boolean> =
        promptRepository.promptInfo
            .map { info -> info?.isClearIdentityCheckFallbackOption() ?: false }
            .distinctUntilChanged()

    override val watchRangingState: Flow<WatchRangingState> =
        callbackFlow {
                val updateWatchRangingState = { state: Int, errorCode: Int ->
                    Log.d(TAG, "authenticationState updated: $state")
                    when (state) {
                        WatchRangingState.WATCH_RANGING_STARTED.ordinal -> {
                            logEvent(
                                SysUiStatsLog
                                    .BIOMETRIC_PROMPT_EVENT__EVENT__EVENT_TYPE_WATCH_RANGING_STARTED
                            )
                        }
                        WatchRangingState.WATCH_RANGING_STOPPED.ordinal -> {
                            logEvent(
                                SysUiStatsLog
                                    .BIOMETRIC_PROMPT_EVENT__EVENT__EVENT_TYPE_WATCH_RANGING_ENDED,
                                errorCode,
                            )
                        }
                        WatchRangingState.WATCH_RANGING_SUCCESSFUL.ordinal -> {
                            logEvent(
                                SysUiStatsLog
                                    .BIOMETRIC_PROMPT_EVENT__EVENT__EVENT_TYPE_WATCH_RANGING_SUCCESS
                            )
                        }
                    }
                    trySendWithFailureLogging(
                        WatchRangingState.entries.first { it.ordinal == state },
                        TAG,
                        "Error sending WatchRangingState",
                    )
                }

                val identityCheckStateListener =
                    object : IIdentityCheckStateListener.Stub() {
                        override fun onWatchRangingStateChanged(state: Int, errorCode: Int) {
                            updateWatchRangingState(state, errorCode)
                        }
                    }

                updateWatchRangingState(
                    WatchRangingState.WATCH_RANGING_IDLE.ordinal,
                    WATCH_RANGING_ERROR_CODE_NONE,
                )
                biometricManager?.registerIdentityCheckStateListener(identityCheckStateListener)
                awaitClose {
                    biometricManager?.unregisterIdentityCheckStateListener(
                        identityCheckStateListener
                    )
                }
            }
            .distinctUntilChanged()
            .shareIn(bgScope, started = SharingStarted.Eagerly, replay = 1)

    override val fallbackOptions: Flow<List<FallbackOptionModel>> = promptRepository.fallbackOptions

    override val credentialKind: Flow<PromptKind> =
        promptRepository.userId.map { userId ->
            if (userId != null) {
                val effectiveUserId = credentialInteractor.getCredentialOwnerOrSelfId(userId)
                getCredentialType(lockPatternUtils, effectiveUserId)
            } else {
                PromptKind.None
            }
        }

    override val patternSize: Flow<Byte> =
        promptRepository.userId.map { userId ->
            if (userId != null) {
                val effectiveUserId = credentialInteractor.getCredentialOwnerOrSelfId(userId)
                lockPatternUtils.getLockPatternSize(effectiveUserId)
            } else {
                LockPatternUtils.PATTERN_SIZE_DEFAULT
            }
        }

    override val fingerprintSensorType: Flow<FingerprintSensorType> =
        fingerprintPropertyRepository.sensorType

    private val _currentView = MutableStateFlow(BiometricPromptView.BIOMETRIC)
    override val currentView: Flow<BiometricPromptView> = _currentView

    override fun onSwitchToCredential() {
        _currentView.value = BiometricPromptView.CREDENTIAL
        setPrompt(
            promptRepository.promptInfo.value!!,
            promptRepository.userId.value!!,
            promptRepository.requestId.value!!,
            promptRepository.modalities.value,
            promptRepository.challenge.value!!,
            promptRepository.opPackageName.value!!,
            onSwitchToCredential = true,
            // isLandscape value is not important when onSwitchToCredential is true
            isLandscape = false,
        )

        when (promptKind.value) {
            PromptKind.Password ->
                logEvent(
                    SysUiStatsLog
                        .BIOMETRIC_PROMPT_EVENT__EVENT__EVENT_TYPE_CREDENTIAL_PASSWORD_VIEW_SHOWN
                )
            PromptKind.Pattern ->
                logEvent(
                    SysUiStatsLog
                        .BIOMETRIC_PROMPT_EVENT__EVENT__EVENT_TYPE_CREDENTIAL_PATTERN_VIEW_SHOWN
                )
            PromptKind.Pin ->
                logEvent(
                    SysUiStatsLog
                        .BIOMETRIC_PROMPT_EVENT__EVENT__EVENT_TYPE_CREDENTIAL_PIN_VIEW_SHOWN
                )
            else ->
                logEvent(
                    SysUiStatsLog.BIOMETRIC_PROMPT_EVENT__EVENT__EVENT_TYPE_CREDENTIAL_VIEW_SHOWN
                )
        }
    }

    override fun onSwitchToAuth() {
        _currentView.value = BiometricPromptView.BIOMETRIC

        setPrompt(
            promptRepository.promptInfo.value!!,
            promptRepository.userId.value!!,
            promptRepository.requestId.value!!,
            promptRepository.modalities.value,
            promptRepository.challenge.value!!,
            promptRepository.opPackageName.value!!,
            onSwitchToCredential = false,
            isLandscape = !displayStateInteractor.currentRotation.value.isDefaultOrientation(),
        )

        logEvent(SysUiStatsLog.BIOMETRIC_PROMPT_EVENT__EVENT__EVENT_TYPE_BIOMETRIC_VIEW_SHOWN)
    }

    override fun onSwitchToFallback() {
        _currentView.value = BiometricPromptView.FALLBACK

        logEvent(SysUiStatsLog.BIOMETRIC_PROMPT_EVENT__EVENT__EVENT_TYPE_FALLBACK_VIEW_SHOWN)
    }

    override fun setPrompt(
        promptInfo: PromptInfo,
        userId: Int,
        requestId: Long,
        modalities: BiometricModalities,
        challenge: Long,
        opPackageName: String,
        onSwitchToCredential: Boolean,
        isLandscape: Boolean,
        updateView: Boolean,
    ) {
        val effectiveUserId = credentialInteractor.getCredentialOwnerOrSelfId(userId)
        val hasCredentialViewShown = promptKind.value.isCredential()
        val showBpForCredential =
            !Utils.isBiometricAllowed(promptInfo) &&
                isDeviceCredentialAllowed(promptInfo) &&
                promptInfo.contentView != null &&
                !promptInfo.isContentViewMoreOptionsButtonUsed
        val showBpWithoutIconForCredential = showBpForCredential && !hasCredentialViewShown
        var kind: PromptKind = PromptKind.None

        if (onSwitchToCredential || _currentView.value == BiometricPromptView.CREDENTIAL) {
            kind = getCredentialType(lockPatternUtils, effectiveUserId)
            if (updateView) {
                _currentView.value = BiometricPromptView.CREDENTIAL
            }
        } else if (Utils.isBiometricAllowed(promptInfo) || showBpWithoutIconForCredential) {
            // TODO(b/330908557): Subscribe to
            // displayStateInteractor.currentRotation.value.isDefaultOrientation() for checking
            // `isLandscape` after removing AuthContainerView.
            kind =
                if (isLandscape) {
                    val paneType =
                        when {
                            displayStateInteractor.isLargeScreen.value && !modalities.hasUdfps ->
                                PromptKind.Biometric.PaneType.ONE_PANE_LARGE_SCREEN_LANDSCAPE
                            showBpWithoutIconForCredential ->
                                PromptKind.Biometric.PaneType.ONE_PANE_NO_SENSOR_LANDSCAPE
                            else -> PromptKind.Biometric.PaneType.TWO_PANE_LANDSCAPE
                        }
                    PromptKind.Biometric(modalities, paneType = paneType)
                } else {
                    PromptKind.Biometric(modalities)
                }
            if (updateView) {
                _currentView.value = BiometricPromptView.BIOMETRIC
            }
        } else if (isDeviceCredentialAllowed(promptInfo)) {
            kind = getCredentialType(lockPatternUtils, effectiveUserId)
            if (updateView) {
                _currentView.value = BiometricPromptView.CREDENTIAL
            }
        }

        promptRepository.setPrompt(
            promptInfo = promptInfo,
            userId = userId,
            modalities = modalities,
            requestId = requestId,
            gatekeeperChallenge = challenge,
            kind = kind,
            opPackageName = opPackageName,
        )
    }

    override fun resetPrompt(requestId: Long) {
        val currentRequestId = promptRepository.requestId.value
        if (currentRequestId != null && currentRequestId == requestId) {
            _currentView.value = BiometricPromptView.BIOMETRIC
        }
        promptRepository.unsetPrompt(requestId)
    }

    fun logEvent(event: Int, errorCode: Int = WATCH_RANGING_ERROR_CODE_NONE) {
        biometricPromptLogger.logPromptEvent(
            sessionTracker.getSessionId(SESSION_BIOMETRIC_PROMPT),
            event,
            errorCode,
        )
    }

    companion object {
        private const val TAG = "PromptSelectorInteractor"
        private const val WATCH_RANGING_ERROR_CODE_NONE = -1
    }
}

/** Biometric Prompt's biometric view variants. */
enum class BiometricPromptView {
    /** Prompt view for credential auth (PIN/Pattern/Password) */
    CREDENTIAL,

    /** Prompt view for biometric authentication */
    BIOMETRIC,

    /** Prompt view for displaying fallback options */
    FALLBACK,
}
