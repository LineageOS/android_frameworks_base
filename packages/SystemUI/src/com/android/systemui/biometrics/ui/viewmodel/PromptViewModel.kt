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

package com.android.systemui.biometrics.ui.viewmodel

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.biometrics.BiometricFingerprintConstants
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.PromptContentView
import android.text.TextPaint
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.keyguard.AuthInteractionProperties
import com.android.systemui.Flags
import com.android.systemui.Flags.msdlFeedback
import com.android.systemui.accessibility.domain.interactor.AccessibilityInteractor
import com.android.systemui.biometrics.UdfpsUtils
import com.android.systemui.biometrics.domain.interactor.BiometricPromptView
import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractor
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.BiometricModality
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.biometrics.ui.BiometricPromptLayoutState
import com.android.systemui.biometrics.ui.BiometricPromptLogoProvider
import com.android.systemui.biometrics.ui.NegativeButtonState
import com.android.systemui.biometrics.ui.PositiveButtonState
import com.android.systemui.biometrics.ui.PromptPosition
import com.android.systemui.biometrics.ui.PromptSize
import com.android.systemui.biometrics.ui.isMedium
import com.android.systemui.biometrics.ui.isNotSmall
import com.android.systemui.biometrics.ui.isSmall
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.display.domain.interactor.DisplayStateInteractor
import com.android.systemui.display.shared.model.DisplayRotation
import com.android.systemui.keyguard.shared.model.AcquiredFingerprintAuthenticationStatus
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.util.kotlin.combine
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.InteractionProperties
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** ViewModel for BiometricPrompt. */
class PromptViewModel
@Inject
constructor(
    displayStateInteractor: DisplayStateInteractor,
    private val promptSelectorInteractor: PromptSelectorInteractor,
    @Application internal val context: Context,
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
    biometricStatusInteractor: BiometricStatusInteractor,
    udfpsUtils: UdfpsUtils,
    accessibilityInteractor: AccessibilityInteractor,
    accessibilityManager: AccessibilityManager,
    promptFallbackViewModelFactory: PromptFallbackViewModel.Factory,
    shadeInteractor: ShadeInteractor,
    promptIconViewModelFactory: PromptIconViewModel.Factory,
    biometricAuthIconViewModelFactory: BiometricAuthIconViewModel.Factory,
    private val promptLogoProvider: BiometricPromptLogoProvider,
) {
    /** Viewmodel for the fallback view */
    val promptFallbackViewModel = promptFallbackViewModelFactory.create()

    // When a11y enabled, increase message delay to ensure messages get read
    private val messageDelay =
        accessibilityManager
            .getRecommendedTimeoutMillis(
                BiometricPrompt.HIDE_DIALOG_DELAY,
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT,
            )
            .toLong()

    /** The set of modalities available for this prompt */
    val modalities: Flow<BiometricModalities> = promptSelectorInteractor.modalities

    /** Whether the shade is being interacted with */
    val isShadeInteracted = shadeInteractor.isUserInteracting

    val udfpsAccessibilityOverlayViewModel =
        BiometricPromptUdfpsAccessibilityOverlayViewModel(
            applicationContext = context,
            deviceEntryUdfpsInteractor = deviceEntryUdfpsInteractor,
            udfpsOverlayInteractor = udfpsOverlayInteractor,
            udfpsUtils = udfpsUtils,
            accessibilityInteractor = accessibilityInteractor,
            promptViewModel = this,
        )

    val udfpsOverlayParams: StateFlow<UdfpsOverlayParams> =
        udfpsOverlayInteractor.udfpsOverlayParams

    private val _isOverlayTouched: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** The kind of credential the user has. */
    val credentialKind: Flow<PromptKind> = promptSelectorInteractor.credentialKind

    /** The kind of prompt to use (biometric, pin, pattern, etc.). */
    val promptKind: StateFlow<PromptKind> = promptSelectorInteractor.promptKind

    /** Whether the sensor icon on biometric prompt ui should be hidden. */
    val hideSensorIcon: Flow<Boolean> = modalities.map { it.isEmpty }.distinctUntilChanged()

    /** If one fallback option set, use as the negative button */
    val usingFallbackAsNegative: Flow<Boolean> =
        promptSelectorInteractor.prompt.map { it?.fallbackOptions?.size == 1 }

    /** The label to use for the cancel button. */
    val negativeButtonText: Flow<String> =
        promptSelectorInteractor.prompt.map {
            if (it?.fallbackOptions?.size == 1) it.fallbackOptions[0].text.toString()
            else it?.negativeButtonText ?: ""
        }

    private val _message: MutableStateFlow<PromptMessage> = MutableStateFlow(PromptMessage.Empty)

    /** A message to show the user, if there is an error, hint, or help to show. */
    val message: Flow<PromptMessage> = _message.asStateFlow()

    private val _isAuthenticating: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** If the user is currently authenticating (i.e. at least one biometric is scanning). */
    val isAuthenticating: Flow<Boolean> = _isAuthenticating.asStateFlow()

    /** Whether an error message is currently being shown. */
    val showingError: Flow<Boolean> = message.map { it.isError }.distinctUntilChanged()

    private val _isAuthenticated: MutableStateFlow<PromptAuthState> =
        MutableStateFlow(PromptAuthState(false))

    /** If the user has successfully authenticated and confirmed (when explicitly required). */
    val isAuthenticated: Flow<PromptAuthState> = _isAuthenticated.asStateFlow()

    /** If the auth is pending confirmation. */
    val isPendingConfirmation: Flow<Boolean> =
        isAuthenticated.map { authState ->
            authState.isAuthenticated && authState.needsUserConfirmation
        }

    private val isRetrySupported: Flow<Boolean> = modalities.map { it.hasFace }

    private val _fingerprintStartMode = MutableStateFlow(FingerprintStartMode.Pending)

    /** Fingerprint sensor state. */
    val fingerprintStartMode: StateFlow<FingerprintStartMode> = _fingerprintStartMode.asStateFlow()

    /** Whether a finger has been acquired by the sensor */
    val hasFingerBeenAcquired: Flow<Boolean> =
        combine(biometricStatusInteractor.fingerprintAcquiredStatus, modalities) {
                status,
                modalities ->
                modalities.hasSfps &&
                    status is AcquiredFingerprintAuthenticationStatus &&
                    status.acquiredInfo == BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
            }
            .distinctUntilChanged()

    /** Whether there is currently a finger on the sensor */
    val hasFingerOnSensor: Flow<Boolean> =
        combine(hasFingerBeenAcquired, _isOverlayTouched) { hasFingerBeenAcquired, overlayTouched ->
            hasFingerBeenAcquired || overlayTouched
        }

    /** the current View that is showing in prompt */
    val currentView = promptSelectorInteractor.currentView

    /** Whether the fallback options screen is currently showing, auth view if false */
    val fallbackShowing: Flow<Boolean> = currentView.map { it == BiometricPromptView.FALLBACK }

    private val forceLargeSize =
        if (Flags.largeScreenBp()) {
            currentView.map { false }
        } else {
            currentView.map { it == BiometricPromptView.CREDENTIAL }
        }
    private val _forceMediumSize = MutableStateFlow(false)

    private val authInteractionProperties = AuthInteractionProperties()
    private val _hapticsToPlay: MutableStateFlow<HapticsToPlay> =
        MutableStateFlow(HapticsToPlay.None)

    /** Event fired to the view indicating a [HapticsToPlay] */
    val hapticsToPlay = _hapticsToPlay.asStateFlow()

    /** The current position of the prompt */
    val position: Flow<PromptPosition> =
        combine(
                forceLargeSize,
                promptKind,
                displayStateInteractor.isLargeScreen,
                displayStateInteractor.isExtraLargeScreen,
                displayStateInteractor.currentRotation,
                fallbackShowing,
                modalities,
            ) {
                forceLarge,
                promptKind,
                isLargeScreen,
                isExtraLargeScreen,
                rotation,
                fallbackShowing,
                modalities ->
                when {
                    isExtraLargeScreen && !modalities.hasUdfps && Flags.largeScreenBp() ->
                        PromptPosition.Center
                    // UDFPS prompts have to follow the sensor around, even on large screens
                    forceLarge ||
                        (isLargeScreen && !modalities.hasUdfps) ||
                        fallbackShowing ||
                        promptKind.isOnePaneNoSensorLandscapeBiometric() -> PromptPosition.Bottom
                    rotation == DisplayRotation.ROTATION_90 -> PromptPosition.Right
                    rotation == DisplayRotation.ROTATION_270 -> PromptPosition.Left
                    rotation == DisplayRotation.ROTATION_180 && modalities.hasUdfps ->
                        PromptPosition.Top
                    else -> PromptPosition.Bottom
                }
            }
            .distinctUntilChanged()

    /** The size of the prompt. */
    val size: Flow<PromptSize> =
        combine(
                forceLargeSize,
                _forceMediumSize,
                modalities,
                currentView,
                promptSelectorInteractor.isConfirmationRequired,
                fingerprintStartMode,
            ) { forceLarge, forceMedium, modalities, currentView, confirmationRequired, fpStartMode
                ->
                when {
                    forceLarge -> PromptSize.LARGE
                    forceMedium || currentView == BiometricPromptView.CREDENTIAL ->
                        PromptSize.MEDIUM
                    modalities.hasFaceOnly && !confirmationRequired -> PromptSize.SMALL
                    modalities.hasFaceAndFingerprint &&
                        !confirmationRequired &&
                        fpStartMode == FingerprintStartMode.Pending -> PromptSize.SMALL
                    else -> PromptSize.MEDIUM
                }
            }
            .distinctUntilChanged()

    /** Prompt panel size padding */
    private val smallHorizontalGuidelinePadding =
        context.resources.getDimensionPixelSize(
            R.dimen.biometric_prompt_land_small_horizontal_guideline_padding
        )
    private val udfpsHorizontalGuidelinePadding =
        context.resources.getDimensionPixelSize(
            R.dimen.biometric_prompt_two_pane_udfps_horizontal_guideline_padding
        )
    private val udfpsHorizontalShorterGuidelinePadding =
        context.resources.getDimensionPixelSize(
            R.dimen.biometric_prompt_two_pane_udfps_shorter_horizontal_guideline_padding
        )
    private val mediumHorizontalGuidelinePadding =
        context.resources.getDimensionPixelSize(
            R.dimen.biometric_prompt_two_pane_medium_horizontal_guideline_padding
        )

    /**
     * If the API caller or the user's personal preferences require explicit confirmation after
     * successful authentication. Confirmation always required when in explicit flow.
     */
    val isConfirmationRequired: Flow<Boolean> =
        combine(_isOverlayTouched, size) { isOverlayTouched, size ->
            !isOverlayTouched && size.isNotSmall
        }

    /**
     * When fingerprint and face modalities are enrolled, indicates whether only face auth has
     * started.
     *
     * True when fingerprint and face modalities are enrolled and implicit flow is active. This
     * occurs in co-ex auth when confirmation is not required and only face auth is started, then
     * becomes false when device transitions to explicit flow after a first error, when the
     * fingerprint sensor is started.
     *
     * False when the dialog opens in explicit flow (fingerprint and face modalities enrolled but
     * confirmation is required), or if user has only fingerprint enrolled, or only face enrolled.
     */
    val faceMode: Flow<Boolean> =
        combine(modalities, isConfirmationRequired, fingerprintStartMode) {
                modalities,
                isConfirmationRequired,
                fingerprintStartMode ->
                modalities.hasFaceAndFingerprint &&
                    !isConfirmationRequired &&
                    fingerprintStartMode == FingerprintStartMode.Pending
            }
            .distinctUntilChanged()

    private val _isIconViewLoaded = MutableStateFlow(false)

    /**
     * For prompts with an iconView, false until the prompt's iconView animation has been loaded in
     * the view, otherwise true by default. Used for BiometricViewSizeBinder to wait for the icon
     * asset to be loaded before determining the prompt size.
     */
    val isIconViewLoaded: Flow<Boolean> =
        combine(hideSensorIcon, _isIconViewLoaded.asStateFlow()) { hideSensorIcon, isIconViewLoaded
                ->
                hideSensorIcon || isIconViewLoaded
            }
            .distinctUntilChanged()

    // Sets whether the prompt's iconView animation has been loaded in the view yet.
    fun setIsIconViewLoaded(iconViewLoaded: Boolean) {
        _isIconViewLoaded.value = iconViewLoaded
    }

    /** (logoIcon, logoDescription) for the prompt. */
    val logoInfo: Flow<Pair<Drawable?, String>> =
        promptSelectorInteractor.prompt
            .map {
                when {
                    it == null -> Pair(null, "")
                    else -> promptLogoProvider.getLogoInfo(it)
                }
            }
            .distinctUntilChanged()

    /** Title for the prompt. */
    val title: Flow<String> =
        promptSelectorInteractor.prompt.map { it?.title ?: "" }.distinctUntilChanged()

    /** Subtitle for the prompt. */
    val subtitle: Flow<String> =
        promptSelectorInteractor.prompt.map { it?.subtitle ?: "" }.distinctUntilChanged()

    /** Custom content view for the prompt. */
    val contentView: Flow<PromptContentView?> =
        promptSelectorInteractor.prompt.map { it?.contentView }.distinctUntilChanged()

    /** ViewModel for the biometric icon in the prompt. */
    val iconViewModel: PromptIconViewModel by lazy {
        promptIconViewModelFactory.create(
            promptViewModel = this,
            biometricAuthIconViewModelFactory = biometricAuthIconViewModelFactory,
        )
    }

    private val originalDescription =
        promptSelectorInteractor.prompt.map { it?.description ?: "" }.distinctUntilChanged()
    /**
     * Description for the prompt. Description view and contentView is mutually exclusive. Pass
     * description down only when contentView is null.
     */
    val description: Flow<String> =
        combine(contentView, originalDescription) { contentView, description ->
            if (contentView == null) description else ""
        }

    private val isIdentityCheckEnabled: Flow<Boolean> =
        promptSelectorInteractor.isIdentityCheckActive

    private val _canTryAgainNow = MutableStateFlow(false)
    /**
     * If authentication can be manually restarted via the try again button or touching a
     * fingerprint sensor.
     */
    val canTryAgainNow: Flow<Boolean> =
        combine(_canTryAgainNow, size, position, isAuthenticated, isRetrySupported) {
            readyToTryAgain,
            size,
            _,
            authState,
            supportsRetry ->
            readyToTryAgain && size.isNotSmall && supportsRetry && authState.isNotAuthenticated
        }

    /** State of the positive (right) button */
    val positiveButtonState: Flow<PositiveButtonState> =
        combine(size, isPendingConfirmation, canTryAgainNow, modalities) {
                size,
                isPendingConfirmation,
                canTryAgain,
                modalities ->
                when {
                    // Try again only on face failures
                    canTryAgain && modalities.hasFaceOnly -> PositiveButtonState.TryAgain

                    // Confirm when authed and confirmation needed
                    size.isNotSmall && isPendingConfirmation -> PositiveButtonState.Confirm

                    else -> PositiveButtonState.Gone
                }
            }
            .distinctUntilChanged()

    /** State of the negative (left) button */
    val negativeButtonState: Flow<NegativeButtonState> =
        combine(
                size,
                isAuthenticated,
                promptSelectorInteractor.isCredentialAllowed,
                isIdentityCheckEnabled,
                promptSelectorInteractor.prompt,
                credentialKind,
                positiveButtonState,
            ) {
                size,
                authState,
                isCredentialAllowed,
                isIdentityCheck,
                prompt,
                credential,
                positiveState ->
                val fallbackOptionsCount = prompt?.fallbackOptions?.size ?: 0
                val hasMultipleFallbackOptions =
                    (if (isCredentialAllowed && isIdentityCheck) 2
                    else (if (isCredentialAllowed) 1 else 0)) + fallbackOptionsCount >= 2

                if (size.isSmall) {
                    NegativeButtonState.Gone
                } else if (authState.isAuthenticated) {
                    // Hide negative button if authed and confirmation not needed
                    if (positiveState == PositiveButtonState.Confirm) {
                        NegativeButtonState.Cancel(context.getString(android.R.string.cancel))
                    } else {
                        NegativeButtonState.Gone
                    }
                } else {
                    when {
                        // If the app provides one, setNegativeButton takes priority
                        prompt?.negativeButtonText != null &&
                            prompt.negativeButtonText.isNotBlank() -> {
                            NegativeButtonState.SetNegative(prompt.negativeButtonText)
                        }

                        hasMultipleFallbackOptions ->
                            NegativeButtonState.FallbackOptions(
                                context.getString(R.string.biometric_dialog_fallback_button)
                            )

                        isCredentialAllowed -> {
                            NegativeButtonState.UseCredential(
                                context.getCredentialString(credential)
                            )
                        }

                        (prompt?.fallbackOptions?.size ?: 0) == 1 -> {
                            NegativeButtonState.SingleFallback(
                                prompt!!.fallbackOptions[0].text.toString()
                            )
                        }

                        else ->
                            NegativeButtonState.Cancel(context.getString(android.R.string.cancel))
                    }
                }
            }
            .distinctUntilChanged()

    private val hasOnlyOneLineTitle: Flow<Boolean> =
        combine(title, subtitle, contentView, description) {
            title,
            subtitle,
            contentView,
            description ->
            if (subtitle.isNotEmpty() || contentView != null || description.isNotEmpty()) {
                false
            } else {
                val maxWidth =
                    context.resources.getDimensionPixelSize(
                        R.dimen.biometric_prompt_two_pane_udfps_shorter_content_width
                    )
                val attributes =
                    context.obtainStyledAttributes(
                        R.style.TextAppearance_AuthCredential_Title,
                        intArrayOf(android.R.attr.textSize),
                    )
                val paint = TextPaint()
                paint.textSize = attributes.getDimensionPixelSize(0, 0).toFloat()
                val textWidth = paint.measureText(title)
                attributes.recycle()
                textWidth / maxWidth <= 1
            }
        }

    /**
     * Rect for positioning prompt guidelines (left, top, right, unused)
     *
     * Negative values are used to signify that guideline measuring should be flipped, measuring
     * from opposite side of the screen
     */
    val guidelineBounds: Flow<Rect> =
        combine(
                iconViewModel.iconPosition,
                promptKind,
                size,
                position,
                modalities,
                hasOnlyOneLineTitle,
            ) { _, promptKind, size, position, modalities, hasOnlyOneLineTitle ->
                var left = 0
                var top = 0
                var right = 0
                when (position) {
                    PromptPosition.Bottom -> {
                        // handled in viewbinder
                    }
                    PromptPosition.Right ->
                        left = getHorizontalPadding(size, modalities, hasOnlyOneLineTitle)
                    PromptPosition.Left ->
                        right = getHorizontalPadding(size, modalities, hasOnlyOneLineTitle)
                    PromptPosition.Top,
                    PromptPosition.Center -> {}
                }
                Rect(left, top, right, 0)
            }
            .distinctUntilChanged()

    private fun getHorizontalPadding(
        size: PromptSize,
        modalities: BiometricModalities,
        hasOnlyOneLineTitle: Boolean,
    ) =
        if (size.isSmall) {
            -smallHorizontalGuidelinePadding
        } else if (modalities.hasUdfps) {
            if (hasOnlyOneLineTitle) {
                -udfpsHorizontalShorterGuidelinePadding
            } else {
                udfpsHorizontalGuidelinePadding
            }
        } else {
            -mediumHorizontalGuidelinePadding
        }

    /** The current layout state of the biometric prompt */
    val layoutState: Flow<BiometricPromptLayoutState> =
        combine(
                currentView,
                position,
                size,
                hideSensorIcon,
                guidelineBounds,
                iconViewModel.iconPosition,
                iconViewModel.iconSize,
            ) { currentView, position, size, hideSensor, guidelines, iconPosition, iconSize ->
                BiometricPromptLayoutState(
                    currentView,
                    position,
                    size,
                    hideSensor,
                    guidelines,
                    iconPosition,
                    iconSize,
                )
            }
            .distinctUntilChanged()

    /** If the indicator (help, error) message should be shown. */
    val isIndicatorMessageVisible: Flow<Boolean> =
        combine(size, position, message) { size, _, message ->
            size.isMedium && message.message.isNotBlank()
        }

    /** If the auth is pending confirmation and the confirm button should be shown. */
    val isConfirmButtonVisible: Flow<Boolean> =
        combine(size, position, isPendingConfirmation) { size, _, isPendingConfirmation ->
            size.isNotSmall && isPendingConfirmation
        }

    /** If the icon can be used as a confirmation button. */
    val isIconConfirmButton: Flow<Boolean> =
        combine(modalities, size) { modalities, size -> modalities.hasUdfps && size.isNotSmall }

    val isFallbackButtonVisible: Flow<Boolean> =
        combine(
            size,
            position,
            isAuthenticated,
            promptSelectorInteractor.isCredentialAllowed,
            isIdentityCheckEnabled,
            promptSelectorInteractor.prompt.map { it?.fallbackOptions?.size ?: 0 },
        ) { size, _, isAuthenticated, credentialAllowed, identityCheck, fallbackOptionsCount ->
            size.isNotSmall &&
                isAuthenticated.isNotAuthenticated &&
                (if (credentialAllowed && identityCheck) 2 else (if (credentialAllowed) 1 else 0)) +
                    fallbackOptionsCount >= 2
        }

    /** If the negative button should be shown. */
    val isNegativeButtonVisible: Flow<Boolean> =
        combine(
            size,
            position,
            isAuthenticated,
            isFallbackButtonVisible,
            promptSelectorInteractor.isCredentialAllowed,
        ) { size, _, authState, fallbackVisible, credentialAllowed ->
            size.isNotSmall &&
                authState.isNotAuthenticated &&
                !credentialAllowed &&
                !fallbackVisible
        }

    /** If the cancel button should be shown (. */
    val isCancelButtonVisible: Flow<Boolean> =
        combine(size, position, isAuthenticated, isNegativeButtonVisible, isConfirmButtonVisible) {
            size,
            _,
            authState,
            showNegativeButton,
            showConfirmButton ->
            size.isNotSmall && authState.isAuthenticated && !showNegativeButton && showConfirmButton
        }

    /** If the try again button show be shown (only the button, see [canTryAgainNow]). */
    val isTryAgainButtonVisible: Flow<Boolean> =
        combine(canTryAgainNow, modalities) { tryAgainIsPossible, modalities ->
            tryAgainIsPossible && modalities.hasFaceOnly
        }

    /** If the credential fallback button show be shown. */
    val isCredentialButtonVisible: Flow<Boolean> =
        combine(
            size,
            position,
            isAuthenticated,
            promptSelectorInteractor.isCredentialAllowed,
            isFallbackButtonVisible,
        ) { size, _, authState, credentialAllowed, fallbackVisible ->
            size.isMedium && authState.isNotAuthenticated && credentialAllowed && !fallbackVisible
        }

    private val history = PromptHistoryImpl()
    private var messageJob: Job? = null

    /**
     * Show a temporary error [message] associated with an optional [failedModality] and play
     * [hapticFeedback].
     *
     * The [messageAfterError] will be shown via [showAuthenticating] when [authenticateAfterError]
     * is set (or via [showHelp] when not set) after the error is dismissed.
     *
     * The error is ignored if the user has already authenticated or if [suppressIf] is true given
     * the currently showing [PromptMessage] and [PromptHistory].
     */
    suspend fun showTemporaryError(
        message: String,
        messageAfterError: String,
        authenticateAfterError: Boolean,
        suppressIf: (PromptMessage, PromptHistory) -> Boolean = { _, _ -> false },
        hapticFeedback: Boolean = true,
        failedModality: BiometricModality = BiometricModality.None,
    ) = coroutineScope {
        if (_isAuthenticated.value.isAuthenticated) {
            if (_isAuthenticated.value.needsUserConfirmation && hapticFeedback) {
                vibrateOnError()
            }
            return@coroutineScope
        }

        _canTryAgainNow.value = supportsRetry(failedModality)

        val suppress = suppressIf(_message.value, history)
        history.failure(failedModality)
        if (suppress) {
            return@coroutineScope
        }

        _isAuthenticating.value = false
        _isAuthenticated.value = PromptAuthState(false)
        _forceMediumSize.value = true
        _message.value = PromptMessage.Error(message)

        if (hapticFeedback) {
            vibrateOnError()
        }

        messageJob?.cancel()
        messageJob = launch {
            delay(messageDelay)
            if (authenticateAfterError) {
                showAuthenticating(messageAfterError)
            } else {
                showHelp(messageAfterError)
            }
        }
    }

    /**
     * Call to ensure the fingerprint sensor has started. Either when the dialog is first shown
     * (most cases) or when it should be enabled after a first error (coex implicit flow).
     */
    fun ensureFingerprintHasStarted(isDelayed: Boolean) {
        if (_fingerprintStartMode.value == FingerprintStartMode.Pending) {
            _fingerprintStartMode.value =
                if (isDelayed) FingerprintStartMode.Delayed else FingerprintStartMode.Normal
        }
    }

    // enable retry only when face fails (fingerprint runs constantly)
    private fun supportsRetry(failedModality: BiometricModality) =
        failedModality == BiometricModality.Face

    /**
     * Show a persistent help message.
     *
     * Will be show even if the user has already authenticated.
     */
    suspend fun showHelp(message: String) {
        val alreadyAuthenticated = _isAuthenticated.value.isAuthenticated
        if (!alreadyAuthenticated) {
            _isAuthenticating.value = false
            _isAuthenticated.value = PromptAuthState(false)
        }

        _message.value =
            if (message.isNotBlank()) PromptMessage.Help(message) else PromptMessage.Empty
        _forceMediumSize.value = true

        messageJob?.cancel()
        messageJob = null
    }

    /**
     * Show a temporary help message and transition back to a fixed message.
     *
     * Ignored if the user has already authenticated.
     */
    suspend fun showTemporaryHelp(message: String, messageAfterHelp: String = "") = coroutineScope {
        if (_isAuthenticated.value.isAuthenticated) {
            return@coroutineScope
        }

        _isAuthenticating.value = false
        _isAuthenticated.value = PromptAuthState(false)
        _message.value =
            if (message.isNotBlank()) PromptMessage.Help(message) else PromptMessage.Empty
        _forceMediumSize.value = true

        messageJob?.cancel()
        messageJob = launch {
            delay(messageDelay)
            showAuthenticating(messageAfterHelp)
        }
    }

    /** Show the user that biometrics are actively running and set [isAuthenticating]. */
    fun showAuthenticating(message: String = "", isRetry: Boolean = false) {
        if (_isAuthenticated.value.isAuthenticated) {
            // TODO(jbolinger): convert to go/tex-apc?
            Log.w(TAG, "Cannot show authenticating after authenticated")
            return
        }

        _isAuthenticating.value = true
        _isAuthenticated.value = PromptAuthState(false)
        _message.value = if (message.isBlank()) PromptMessage.Empty else PromptMessage.Help(message)

        // reset the try again button(s) after the user attempts a retry
        if (isRetry) {
            _canTryAgainNow.value = false
        }

        messageJob?.cancel()
        messageJob = null
    }

    /**
     * Show successfully authentication, set [isAuthenticated], and dismiss the prompt after a
     * [dismissAfterDelay] or prompt for explicit confirmation (if required).
     */
    suspend fun showAuthenticated(
        modality: BiometricModality,
        dismissAfterDelay: Long,
        helpMessage: String = "",
    ) {
        if (_isAuthenticated.value.isAuthenticated) {
            // Treat second authentication with a different modality as confirmation for the first
            if (
                _isAuthenticated.value.needsUserConfirmation &&
                    modality != _isAuthenticated.value.authenticatedModality
            ) {
                confirmAuthenticated()
                return
            }
            // TODO(jbolinger): convert to go/tex-apc?
            Log.w(TAG, "Cannot show authenticated after authenticated")
            return
        }

        _isAuthenticating.value = false
        val needsUserConfirmation = needsExplicitConfirmation(modality)
        _isAuthenticated.value =
            PromptAuthState(true, modality, needsUserConfirmation, dismissAfterDelay)
        _message.value = PromptMessage.Empty

        if (!needsUserConfirmation) {
            vibrateOnSuccess()
        }

        messageJob?.cancel()
        messageJob = null

        if (helpMessage.isNotBlank() && needsUserConfirmation) {
            showHelp(helpMessage)
        }
    }

    private suspend fun needsExplicitConfirmation(modality: BiometricModality): Boolean {
        val confirmationRequired = isConfirmationRequired.first()

        // Only worry about confirmationRequired if face was used to unlock
        if (modality == BiometricModality.Face) {
            return confirmationRequired
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
    fun confirmAuthenticated() {
        val authState = _isAuthenticated.value
        if (authState.isNotAuthenticated) {
            Log.w(TAG, "Cannot confirm authenticated when not authenticated")
            return
        }

        _isAuthenticated.value = authState.asExplicitlyConfirmed()
        _message.value = PromptMessage.Empty

        vibrateOnSuccess()

        messageJob?.cancel()
        messageJob = null
    }

    /**
     * Touch event occurred on the overlay
     *
     * Tracks whether a finger is currently down to set [_isOverlayTouched] to be used as user
     * confirmation
     */
    fun onOverlayTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            _isOverlayTouched.value = true

            if (_isAuthenticated.value.needsUserConfirmation) {
                confirmAuthenticated()
            }
            return true
        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
            _isOverlayTouched.value = false
        }
        return false
    }

    /**
     * Switch to the credential view.
     *
     * TODO(b/251476085): this should be decoupled from the shared panel controller
     */
    fun onSwitchToCredential() {
        promptSelectorInteractor.onSwitchToCredential()
    }

    /** Switch to the fallback view. */
    fun onSwitchToFallback() {
        promptSelectorInteractor.onSwitchToFallback()
    }

    /** Switch to the auth view. */
    fun onSwitchToAuth() {
        promptSelectorInteractor.onSwitchToAuth()
    }

    private fun vibrateOnSuccess() {
        val haptics =
            if (msdlFeedback()) {
                HapticsToPlay.MSDL(MSDLToken.UNLOCK, authInteractionProperties)
            } else {
                HapticsToPlay.HapticConstant(HapticFeedbackConstants.BIOMETRIC_CONFIRM, flag = null)
            }
        _hapticsToPlay.value = haptics
    }

    private fun vibrateOnError() {
        val haptics =
            if (msdlFeedback()) {
                HapticsToPlay.MSDL(MSDLToken.FAILURE, authInteractionProperties)
            } else {
                HapticsToPlay.HapticConstant(HapticFeedbackConstants.BIOMETRIC_REJECT, flag = null)
            }
        _hapticsToPlay.value = haptics
    }

    /** Clears the [hapticsToPlay] variable by setting its constant to the NO_HAPTICS default. */
    fun clearHaptics() {
        _hapticsToPlay.update { HapticsToPlay.None }
    }

    /** The state of haptic feedback to play. */
    sealed interface HapticsToPlay {
        /**
         * Haptics using [HapticFeedbackConstants]. It is composed by a [HapticFeedbackConstants]
         * and a [HapticFeedbackConstants] flag.
         */
        data class HapticConstant(val constant: Int, val flag: Int?) : HapticsToPlay

        /**
         * Haptics using MSDL feedback. It is composed by a [MSDLToken] and optional
         * [InteractionProperties]
         */
        data class MSDL(val token: MSDLToken, val properties: InteractionProperties?) :
            HapticsToPlay

        data object None : HapticsToPlay
    }

    companion object {
        const val TAG = "PromptViewModel"
    }
}

fun Context.getCredentialString(kind: PromptKind): String =
    when (kind) {
        PromptKind.Pin -> this.getString(R.string.biometric_dialog_use_pin)

        PromptKind.Password -> this.getString(R.string.biometric_dialog_use_password)

        PromptKind.Pattern -> this.getString(R.string.biometric_dialog_use_pattern)

        else -> ""
    }

/** How the fingerprint sensor was started for the prompt. */
enum class FingerprintStartMode {
    /** Fingerprint sensor has not started. */
    Pending,

    /** Fingerprint sensor started immediately when prompt was displayed. */
    Normal,

    /** Fingerprint sensor started after the first failure of another passive modality. */
    Delayed;

    /** If this is [Normal] or [Delayed]. */
    val isStarted: Boolean
        get() = this == Normal || this == Delayed
}
