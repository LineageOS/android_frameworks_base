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

package com.android.systemui.authentication.data.repository

import android.annotation.UserIdInt
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.IntentFilter
import android.os.UserHandle
import android.security.Flags.lockscreenIndicateDuplicateGuesses
import android.security.Flags.secureLockDevice
import android.text.ShowSecretsSetting
import android.util.Log
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_BIOMETRIC_AUTH_REQUIRED_FOR_SECURE_LOCK_DEVICE
import com.android.internal.widget.LockscreenCredential
import com.android.keyguard.KeyguardSecurityModel
import com.android.systemui.authentication.data.repository.AuthenticationRepository.Companion.WARM_UP_THROTTLE_DURATION
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Biometric
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.None
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Password
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Pattern
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Pin
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Sim
import com.android.systemui.authentication.shared.model.AuthenticationResult
import com.android.systemui.authentication.shared.model.AuthenticationResultModel
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.scene.domain.SceneFrameworkTableLog
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.onSubscriberAdded
import com.android.systemui.util.time.SystemClock
import dagger.Binds
import dagger.Module
import java.util.function.Function
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Defines interface for classes that can access authentication-related application state. */
interface AuthenticationRepository {
    /**
     * The exact length a PIN should be for us to enable PIN length hinting.
     *
     * A PIN that's shorter or longer than this is not eligible for the UI to render hints showing
     * how many digits the current PIN is, even if [isAutoConfirmFeatureEnabled] is enabled.
     *
     * Note that PIN length hinting is only available if the PIN auto confirmation feature is
     * available.
     */
    val hintedPinLength: Int

    /** Whether the pattern should be visible for the currently-selected user. */
    val isPatternVisible: StateFlow<Boolean>

    /** The current pattern size. */
    val patternSize: StateFlow<Byte>

    /**
     * Whether the auto confirm feature is enabled for the currently-selected user.
     *
     * Note that the length of the PIN is also important to take into consideration, please see
     * [hintedPinLength].
     */
    val isAutoConfirmFeatureEnabled: StateFlow<Boolean>

    /**
     * The number of failed authentication attempts for the selected user since their last
     * successful authentication.
     */
    val failedAuthenticationAttempts: StateFlow<Int>

    /**
     * Timestamp for when the current lockout (aka "throttling") will end, allowing the user to
     * attempt authentication again. Returns `null` if no lockout is active.
     *
     * Note that the value should be compared to [SystemClock.elapsedRealtime].milliseconds.
     *
     * Also note that the value may change when the selected user is changed.
     */
    val lockoutEndTime: Duration?

    /**
     * Whether lockout has occurred at least once since the last successful authentication of any
     * user.
     */
    val hasLockoutOccurred: StateFlow<Boolean>

    /**
     * Whether the primary authentication attempt was the same as a previous attempt since the last
     * successful authentication.
     */
    val isDuplicateAttempt: StateFlow<Boolean>

    /**
     * The currently-configured authentication method. This determines how the authentication
     * challenge needs to be completed in order to unlock an otherwise locked device.
     *
     * Note: there may be other ways to unlock the device that "bypass" the need for this
     * authentication challenge (notably, biometrics like fingerprint or face unlock).
     */
    val authenticationMethod: StateFlow<AuthenticationMethodModel>

    /** The minimal length of a pattern. */
    val minPatternLength: Int

    /** The minimal length of a password. */
    val minPasswordLength: Int

    /** Whether the "enhanced PIN privacy" setting is enabled for the current user. */
    val isPinEnhancedPrivacyEnabled: StateFlow<Boolean>

    /** Whether characters in password/secret fields should be shown for touch input. */
    val isShowPasswordsTouchEnabled: StateFlow<Boolean>

    /** Whether characters in password/secret fields should be shown for physical keyboard input. */
    val isShowPasswordsPhysicalEnabled: StateFlow<Boolean>

    /**
     * Checks the given [LockscreenCredential] to see if it's correct, returning an
     * [AuthenticationResultModel] representing what happened.
     */
    suspend fun checkCredential(
        credential: LockscreenCredential,
        onEarlyMatched: () -> Unit,
    ): AuthenticationResultModel

    /** Returns the length of the PIN or `0` if the current auth method is not PIN. */
    suspend fun getPinLength(): Int

    /** Reports an authentication attempt. Skipped attempts are not reported downstream. */
    suspend fun reportAuthenticationAttempt(
        result: AuthenticationResult,
        isDuplicate: Boolean = false,
    )

    /** Reports that the user has entered a temporary device lockout (throttling). */
    suspend fun reportLockoutStarted(duration: Duration)

    /**
     * Returns the current maximum number of login attempts that are allowed before the device or
     * profile is wiped.
     *
     * If there is no wipe policy, returns `0`.
     *
     * @see [DevicePolicyManager.getMaximumFailedPasswordsForWipe]
     */
    suspend fun getMaxFailedUnlockAttemptsForWipe(): Int

    /**
     * Returns the user that will be wiped first when too many failed attempts are made to unlock
     * the device by the selected user. That user is either the same as the current user ID or
     * belongs to the same profile group.
     *
     * When there is no such policy, returns [UserHandle.USER_NULL]. E.g. managed profile user may
     * be wiped as a result of failed primary profile password attempts when using unified
     * challenge. Primary user may be wiped as a result of failed password attempts on the managed
     * profile of an organization-owned device.
     */
    @UserIdInt suspend fun getProfileWithMinFailedUnlockAttemptsForWipe(): Int

    /**
     * Returns the device policy enforced maximum time to lock the device, in milliseconds. When the
     * device goes to sleep, this is the maximum time the device policy allows to wait before
     * locking the device, despite what the user setting might be set to.
     */
    suspend fun getMaximumTimeToLock(): Long

    /**
     * Returns true if the power button should instantly lock the device, false otherwise.
     *
     * If the device is not secure, return true - this is a quirk of the settings app. If you have
     * swipe security set, you can no longer access the "power button locks instantly" setting in
     * the UI and it defaults to true, so the swipe lockscreen will always show up after pressing
     * the power button.
     *
     * WARNING: This causes a blocking IPC to LockPatternUtils (b/446735679).
     */
    fun getPowerButtonInstantlyLocks(): Boolean

    /**
     * The last time a warm up signal was triggered to the system, as a Duration wrapping
     * [SystemClock.elapsedRealtime] milliseconds. [ZERO - WARM_UP_THROTTLE_DURATION] serves as the
     * first value to allow for a warm up to be triggered immediately after boot.
     */
    var lastWarmUpTrigger: Duration

    /**
     * Sends a signal to the system to trigger warm ups of any LSKF auth system components that may
     * be in a low power state. The signal is expected to be handled by the system and return
     * instantly without throwing any exceptions.
     *
     * The signal is expected to only be sent down to the system a maximum of once for every
     * [WARM_UP_THROTTLE_DURATION].
     */
    suspend fun triggerAuthWarmUp()

    companion object {
        val WARM_UP_THROTTLE_DURATION = 5.seconds
    }
}

@SysUISingleton
class AuthenticationRepositoryImpl
@Inject
constructor(
    @Background private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Application private val context: Context,
    private val clock: SystemClock,
    private val getSecurityMode: Function<Int, KeyguardSecurityModel.SecurityMode>,
    private val userRepository: UserRepository,
    private val lockPatternUtils: LockPatternUtils,
    private val devicePolicyManager: DevicePolicyManager,
    broadcastDispatcher: BroadcastDispatcher,
    mobileConnectionsRepository: MobileConnectionsRepository,
    @SceneFrameworkTableLog private val tableLogBuffer: TableLogBuffer,
) : AuthenticationRepository {

    override val hintedPinLength: Int = 6

    override val patternSize: StateFlow<Byte> =
        refreshingFlow(
            initialValue = LockPatternUtils.PATTERN_SIZE_DEFAULT,
            getFreshValue = lockPatternUtils::getLockPatternSize,
        )

    override val isPatternVisible: StateFlow<Boolean> =
        refreshingFlow(
            initialValue = true,
            getFreshValue = lockPatternUtils::isVisiblePatternEnabled,
        )

    override val isAutoConfirmFeatureEnabled: StateFlow<Boolean> =
        refreshingFlow(
            initialValue =
                lockPatternUtils.isAutoPinConfirmEnabled(userRepository.getSelectedUserInfo().id),
            getFreshValue = lockPatternUtils::isAutoPinConfirmEnabled,
        )

    override val authenticationMethod: StateFlow<AuthenticationMethodModel> =
        combine(userRepository.selectedUserInfo, mobileConnectionsRepository.isAnySimSecure) {
                selectedUserInfo,
                _ ->
                selectedUserInfo.id
            }
            .flatMapLatest { selectedUserId ->
                broadcastDispatcher
                    .broadcastFlow(
                        filter =
                            IntentFilter(
                                DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
                            ),
                        user = UserHandle.of(selectedUserId),
                    )
                    .onStart { emit(Unit) }
                    .map { selectedUserId }
            }
            .map {
                withContext(backgroundDispatcher) {
                    getAuthenticationMethodBlocking(selectedUserId)
                }
            }
            .logDiffsForTable(tableLogBuffer = tableLogBuffer, initialValue = None)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = getAuthenticationMethodBlocking(selectedUserId),
            )

    override val minPatternLength: Int = LockPatternUtils.MIN_LOCK_PATTERN_SIZE

    override val minPasswordLength: Int = LockPatternUtils.MIN_LOCK_PASSWORD_SIZE

    override val isPinEnhancedPrivacyEnabled: StateFlow<Boolean> =
        refreshingFlow(
            initialValue = true,
            getFreshValue = { userId -> lockPatternUtils.isPinEnhancedPrivacyEnabled(userId) },
        )

    override val isShowPasswordsTouchEnabled: StateFlow<Boolean> =
        userRepository.selectedUserInfo
            .map { it.id }
            .distinctUntilChanged()
            .flatMapLatest { userId ->
                callbackFlow {
                    val userContext = context.createContextAsUser(UserHandle.of(userId), 0)
                    val unregister =
                        ShowSecretsSetting.registerCallback(userContext) {
                            trySend(ShowSecretsSetting.shouldShowTouchInput(userContext))
                        }
                    trySend(ShowSecretsSetting.shouldShowTouchInput(userContext))
                    awaitClose { unregister.run() }
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    override val isShowPasswordsPhysicalEnabled: StateFlow<Boolean> =
        userRepository.selectedUserInfo
            .map { it.id }
            .distinctUntilChanged()
            .flatMapLatest { userId ->
                callbackFlow {
                    val userContext = context.createContextAsUser(UserHandle.of(userId), 0)
                    val unregister =
                        ShowSecretsSetting.registerCallback(userContext) {
                            trySend(ShowSecretsSetting.shouldShowPhysicalInput(userContext))
                        }
                    trySend(ShowSecretsSetting.shouldShowPhysicalInput(userContext))
                    awaitClose { unregister.run() }
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    private val _failedAuthenticationAttempts = MutableStateFlow(0)
    override val failedAuthenticationAttempts: StateFlow<Int> =
        _failedAuthenticationAttempts.asStateFlow()

    override val lockoutEndTime: Duration?
        get() =
            lockPatternUtils.getLockoutEndTime(selectedUserId).toKotlinDuration().takeIf {
                clock.elapsedRealtime().milliseconds < it
            }

    private val _hasLockoutOccurred = MutableStateFlow(false)
    override val hasLockoutOccurred: StateFlow<Boolean> = _hasLockoutOccurred.asStateFlow()

    private val _isDuplicateAttempt = MutableStateFlow(false)
    override val isDuplicateAttempt: StateFlow<Boolean> = _isDuplicateAttempt.asStateFlow()

    override var lastWarmUpTrigger = ZERO - WARM_UP_THROTTLE_DURATION

    init {
        if (SceneContainerFlag.isEnabled) {
            // Hydrate failedAuthenticationAttempts initially and whenever the selected user
            // changes.
            applicationScope.launch {
                userRepository.selectedUserInfo.collect {
                    _failedAuthenticationAttempts.value = getFailedAuthenticationAttemptCount()
                }
            }
        }
    }

    override suspend fun checkCredential(
        credential: LockscreenCredential,
        onEarlyMatched: () -> Unit,
    ): AuthenticationResultModel {
        return withContext(backgroundDispatcher) {
            val response =
                lockPatternUtils.checkCredential(credential, selectedUserId) { onEarlyMatched() }
            return@withContext AuthenticationResultModel(
                isSuccessful = response.isMatched,
                lockoutDuration = response.timeout.toKotlinDuration(),
                isDuplicate = lockscreenIndicateDuplicateGuesses() && response.isCredAlreadyTried,
            )
        }
    }

    override suspend fun getPinLength(): Int {
        return withContext(backgroundDispatcher) { lockPatternUtils.getPinLength(selectedUserId) }
    }

    override suspend fun reportAuthenticationAttempt(
        result: AuthenticationResult,
        isDuplicate: Boolean,
    ) {
        withContext(backgroundDispatcher) {
            when (result) {
                AuthenticationResult.SUCCEEDED -> {
                    if (
                        secureLockDevice() &&
                            SceneContainerFlag.isEnabled &&
                            lockPatternUtils
                                .getStrongAuthForUser(selectedUserId)
                                .and(STRONG_BIOMETRIC_AUTH_REQUIRED_FOR_SECURE_LOCK_DEVICE) != 0
                    ) {
                        Log.d(
                            TAG,
                            "Device is in secure lock device mode; awaiting second factor " +
                                "biometric authentication before unlocking.",
                        )
                    } else {
                        lockPatternUtils.userPresent(selectedUserId)
                        lockPatternUtils.reportSuccessfulPasswordAttempt(selectedUserId)
                    }
                    _hasLockoutOccurred.value = false
                }
                AuthenticationResult.FAILED -> {
                    lockPatternUtils.reportFailedPasswordAttempt(selectedUserId)
                }
                AuthenticationResult.SKIPPED -> {
                    // Skipped, we don't want to use any downstream attempts here.
                }
            }
            _isDuplicateAttempt.value = isDuplicate
            // Skipped attempts don't change anything downstream.
            if (result != AuthenticationResult.SKIPPED) {
                _failedAuthenticationAttempts.value = getFailedAuthenticationAttemptCount()
            }
        }
    }

    override suspend fun reportLockoutStarted(duration: Duration) {
        withContext(backgroundDispatcher) {
            lockPatternUtils.reportPasswordLockout(duration.toJavaDuration(), selectedUserId)
        }
        _hasLockoutOccurred.value = true
    }

    private suspend fun getFailedAuthenticationAttemptCount(): Int {
        return withContext(backgroundDispatcher) {
            lockPatternUtils.getCurrentFailedPasswordAttempts(selectedUserId)
        }
    }

    override suspend fun getMaxFailedUnlockAttemptsForWipe(): Int {
        return withContext(backgroundDispatcher) {
            lockPatternUtils.getMaximumFailedPasswordsForWipe(selectedUserId)
        }
    }

    override suspend fun getProfileWithMinFailedUnlockAttemptsForWipe(): Int {
        return withContext(backgroundDispatcher) {
            devicePolicyManager.getProfileWithMinimumFailedPasswordsForWipe(selectedUserId)
        }
    }

    override suspend fun getMaximumTimeToLock(): Long {
        return withContext(backgroundDispatcher) {
            devicePolicyManager.getMaximumTimeToLock(/* admin= */ null, selectedUserId)
        }
    }

    override fun getPowerButtonInstantlyLocks(): Boolean {
        return !lockPatternUtils.isSecure(selectedUserId) ||
            lockPatternUtils.getPowerButtonInstantlyLocks(selectedUserId)
    }

    override suspend fun triggerAuthWarmUp() {
        withContext(backgroundDispatcher) {
            val now = clock.elapsedRealtime().milliseconds
            lockPatternUtils.prepareToVerifyCredential(selectedUserId)
            Log.d(TAG, "Triggered a background auth warm up for user $selectedUserId at $now")
        }
    }

    private val selectedUserId: Int
        @UserIdInt get() = userRepository.getSelectedUserInfo().id

    /**
     * Returns a [StateFlow] that's automatically kept fresh. The passed-in [getFreshValue] is
     * invoked on a background thread every time the selected user is changed and every time a new
     * downstream subscriber is added to the flow.
     *
     * Initially, the flow will emit [initialValue] while it refreshes itself in the background by
     * invoking the [getFreshValue] function and emitting the fresh value when that's done.
     *
     * Every time the selected user is changed, the flow will re-invoke [getFreshValue] and emit the
     * new value.
     *
     * Every time a new downstream subscriber is added to the flow it first receives the latest
     * cached value that's either the [initialValue] or the latest previously fetched value. In
     * addition, adding a new downstream subscriber also triggers another [getFreshValue] call and a
     * subsequent emission of that newest value.
     */
    private fun <T> refreshingFlow(
        initialValue: T,
        getFreshValue: suspend (selectedUserId: Int) -> T,
    ): StateFlow<T> {
        val flow = MutableStateFlow(initialValue)
        applicationScope.launch {
            combine(
                    // Emits a value initially and every time the selected user is changed.
                    userRepository.selectedUserInfo.map { it.id }.distinctUntilChanged(),
                    // Emits a value only when the number of downstream subscribers of this flow
                    // increases.
                    flow.onSubscriberAdded(),
                ) { selectedUserId, _ ->
                    selectedUserId
                }
                .collect { selectedUserId ->
                    flow.value = withContext(backgroundDispatcher) { getFreshValue(selectedUserId) }
                }
        }

        return flow.asStateFlow()
    }

    /**
     * Returns the authentication method for the given user ID. This is a blocking call that
     * normally should only be made off the main thread.
     */
    private fun getAuthenticationMethodBlocking(@UserIdInt userId: Int): AuthenticationMethodModel {
        return when (getSecurityMode.apply(userId)) {
            KeyguardSecurityModel.SecurityMode.PIN -> Pin
            KeyguardSecurityModel.SecurityMode.SimPin,
            KeyguardSecurityModel.SecurityMode.SimPuk -> Sim
            KeyguardSecurityModel.SecurityMode.Password -> Password
            KeyguardSecurityModel.SecurityMode.Pattern -> Pattern
            KeyguardSecurityModel.SecurityMode.None -> None
            KeyguardSecurityModel.SecurityMode.SecureLockDeviceBiometricAuth -> Biometric
            KeyguardSecurityModel.SecurityMode.Invalid -> error("Invalid security mode!")
        }
    }

    companion object {
        private const val TAG = "AuthenticationRepository"
    }
}

@Module
interface AuthenticationRepositoryModule {
    @Binds fun repository(impl: AuthenticationRepositoryImpl): AuthenticationRepository
}
