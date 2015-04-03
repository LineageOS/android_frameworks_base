/*
 * Copyright 2023 The Android Open Source Project
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

import android.os.UserHandle
import android.util.Log
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternUtils.credentialTypeToString
import com.android.internal.widget.LockPatternView
import com.android.internal.widget.LockscreenCredential
import com.android.keyguard.KeyguardSecurityModel.SecurityMode
import com.android.systemui.authentication.data.repository.AuthenticationRepository.Companion.WARM_UP_THROTTLE_DURATION
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationPatternCoordinate
import com.android.systemui.authentication.shared.model.AuthenticationResult
import com.android.systemui.authentication.shared.model.AuthenticationResultModel
import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import dagger.Provides
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime

class FakeAuthenticationRepository(private val currentTimeMs: () -> Long) :
    AuthenticationRepository {

    override val hintedPinLength: Int = HINTING_PIN_LENGTH

    private val _isPatternVisible = MutableStateFlow(true)
    override val isPatternVisible: StateFlow<Boolean> = _isPatternVisible.asStateFlow()

    private val _patternSize = MutableStateFlow(LockPatternUtils.PATTERN_SIZE_DEFAULT)
    override val patternSize: StateFlow<Byte> = _patternSize.asStateFlow()

    override val hasLockoutOccurred = MutableStateFlow(false)

    private val _isAutoConfirmFeatureEnabled = MutableStateFlow(false)
    override val isAutoConfirmFeatureEnabled: StateFlow<Boolean> =
        _isAutoConfirmFeatureEnabled.asStateFlow()

    private val _authenticationMethod =
        MutableStateFlow<AuthenticationMethodModel>(DEFAULT_AUTHENTICATION_METHOD)
    override val authenticationMethod: StateFlow<AuthenticationMethodModel> =
        _authenticationMethod.asStateFlow()

    override val minPatternLength: Int = 4

    override val minPasswordLength: Int = 4

    private val _isPinEnhancedPrivacyEnabled = MutableStateFlow(false)
    override val isPinEnhancedPrivacyEnabled: StateFlow<Boolean> =
        _isPinEnhancedPrivacyEnabled.asStateFlow()

    private val _isShowPasswordsTouchEnabled = MutableStateFlow(true)
    override val isShowPasswordsTouchEnabled: StateFlow<Boolean> =
        _isShowPasswordsTouchEnabled.asStateFlow()

    private val _isShowPasswordsPhysicalEnabled = MutableStateFlow(false)
    override val isShowPasswordsPhysicalEnabled: StateFlow<Boolean> =
        _isShowPasswordsPhysicalEnabled.asStateFlow()

    private var credentialOverride: List<Any>? = null
    private var securityMode: SecurityMode = DEFAULT_AUTHENTICATION_METHOD.toSecurityMode()

    var lockoutStartedReportCount = 0

    private val credentialCheckingMutex = Mutex(locked = false)

    private val currentTime: Duration
        get() = currentTimeMs().milliseconds

    var maximumTimeToLock: Long = 0
    var fakePowerButtonInstantlyLocks: Boolean = true

    private var previousAttempts = listOf<LockscreenCredential>()

    var lockoutOverride: Duration? = null
        private set

    fun overrideLockout(lockout: Duration) {
        lockoutOverride = lockout
    }

    override var lastWarmUpTrigger = ZERO - WARM_UP_THROTTLE_DURATION

    override suspend fun triggerAuthWarmUp() {
        // Do nothing.
    }

    fun setAuthenticationMethod(authenticationMethod: AuthenticationMethodModel) {
        _authenticationMethod.value = authenticationMethod
        securityMode = authenticationMethod.toSecurityMode()
    }

    fun overrideCredential(pin: List<Int>) {
        credentialOverride = pin
    }

    override suspend fun reportAuthenticationAttempt(
        result: AuthenticationResult,
        isDuplicate: Boolean,
    ) {
        when (result) {
            AuthenticationResult.SUCCEEDED -> {
                _failedAuthenticationAttempts.value = 0
                previousAttempts = listOf()
                _lockoutEndTime = null
                hasLockoutOccurred.value = false
                lockoutStartedReportCount = 0
            }
            AuthenticationResult.FAILED -> {
                _failedAuthenticationAttempts.value++
            }
            AuthenticationResult.SKIPPED -> {}
        }
        _isDuplicateAttempt.value = isDuplicate
    }

    private var _failedAuthenticationAttempts = MutableStateFlow(0)
    override val failedAuthenticationAttempts: StateFlow<Int> =
        _failedAuthenticationAttempts.asStateFlow()

    private var _lockoutEndTime: Duration? = null
    override val lockoutEndTime: Duration?
        get() = if (currentTime < (_lockoutEndTime ?: 0.milliseconds)) _lockoutEndTime else null

    private var _isDuplicateAttempt = MutableStateFlow(false)
    override val isDuplicateAttempt: StateFlow<Boolean> = _isDuplicateAttempt.asStateFlow()

    private var deferred: CompletableDeferred<Unit>? = null

    override suspend fun reportLockoutStarted(duration: Duration) {
        _lockoutEndTime = (currentTime + duration).takeIf { duration.isPositive() }
        hasLockoutOccurred.value = true
        lockoutStartedReportCount++
    }

    override suspend fun getMaxFailedUnlockAttemptsForWipe(): Int =
        MAX_FAILED_AUTH_TRIES_BEFORE_WIPE

    var profileWithMinFailedUnlockAttemptsForWipe: Int = UserHandle.USER_SYSTEM

    override suspend fun getProfileWithMinFailedUnlockAttemptsForWipe(): Int =
        profileWithMinFailedUnlockAttemptsForWipe

    override suspend fun getPinLength(): Int {
        return (credentialOverride ?: DEFAULT_PIN).size
    }

    fun setAutoConfirmFeatureEnabled(isEnabled: Boolean) {
        _isAutoConfirmFeatureEnabled.value = isEnabled
    }

    fun deferFullSuccessResult(): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().also { deferred = it }
    }

    override suspend fun checkCredential(
        credential: LockscreenCredential,
        onEarlyMatched: () -> Unit,
    ): AuthenticationResultModel {
        return credentialCheckingMutex.withLock {
            Log.d(
                TAG,
                "previousAttempts: ${previousAttempts.joinToString { it.asString() }}, current: ${credential.asString()}",
            )
            if (credential in previousAttempts) {
                return AuthenticationResultModel(
                    isSuccessful = false,
                    lockoutDuration = Duration.ZERO,
                    isDuplicate = true,
                )
            }
            Log.d(TAG, "checking credential")
            val expectedCredential = credentialOverride ?: getExpectedCredential(securityMode)
            val isSuccessful =
                when {
                    credential.type != getCurrentCredentialType(securityMode) -> false
                    credential.type == LockPatternUtils.CREDENTIAL_TYPE_PIN ->
                        credential.isPin && credential.matches(expectedCredential)
                    credential.type == LockPatternUtils.CREDENTIAL_TYPE_PASSWORD ->
                        credential.isPassword && credential.matches(expectedCredential)
                    credential.type == LockPatternUtils.CREDENTIAL_TYPE_PATTERN ->
                        credential.isPattern && credential.matches(expectedCredential)
                    else -> error("Unexpected credential type ${credential.type}!")
                }
            Log.d(TAG, "checked credential, isSuccessful: $isSuccessful")

            if (isSuccessful) {
                onEarlyMatched()
                deferred?.let {
                    it.await()
                    deferred = null
                }
            } else {
                previousAttempts += credential.duplicate()
                lockoutOverride?.let {
                    lockoutOverride = null
                    return@checkCredential AuthenticationResultModel(
                        isSuccessful = false,
                        lockoutDuration = it,
                    )
                }
            }

            val failedAttempts = _failedAuthenticationAttempts.value
            if (isSuccessful || failedAttempts < MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT - 1) {
                AuthenticationResultModel(isSuccessful, lockoutDuration = Duration.ZERO)
            } else {
                AuthenticationResultModel(isSuccessful = false, lockoutDuration = LOCKOUT_DURATION)
            }
        }
    }

    fun LockscreenCredential.asString() =
        "${credentialTypeToString(type)}:${credential.contentToString()}"

    fun setPinEnhancedPrivacyEnabled(isEnabled: Boolean) {
        _isPinEnhancedPrivacyEnabled.value = isEnabled
    }

    fun setIsShowPasswordsTouchEnabled(isEnabled: Boolean) {
        _isShowPasswordsTouchEnabled.value = isEnabled
    }

    fun setIsShowPasswordsPhysicalEnabled(isEnabled: Boolean) {
        _isShowPasswordsPhysicalEnabled.value = isEnabled
    }

    /**
     * Pauses any future credential checking. The test must call [unpauseCredentialChecking] to
     * flush the accumulated credential checks.
     */
    suspend fun pauseCredentialChecking() {
        credentialCheckingMutex.lock()
    }

    /**
     * Unpauses future credential checking, if it was paused using [pauseCredentialChecking]. This
     * doesn't flush any pending coroutine jobs; the test code may still choose to do that using
     * `runCurrent`.
     */
    fun unpauseCredentialChecking() {
        credentialCheckingMutex.unlock()
    }

    override suspend fun getMaximumTimeToLock(): Long {
        return maximumTimeToLock
    }

    override fun getPowerButtonInstantlyLocks(): Boolean {
        return fakePowerButtonInstantlyLocks
    }

    private fun getExpectedCredential(securityMode: SecurityMode): List<Any> {
        return when (val credentialType = getCurrentCredentialType(securityMode)) {
            LockPatternUtils.CREDENTIAL_TYPE_PIN -> credentialOverride ?: DEFAULT_PIN
            LockPatternUtils.CREDENTIAL_TYPE_PASSWORD -> DEFAULT_PASSWORD
            LockPatternUtils.CREDENTIAL_TYPE_PATTERN -> DEFAULT_PATTERN.toCells()
            else -> error("Unsupported credential type $credentialType!")
        }
    }

    companion object {
        private const val TAG = "FakeAuthenticationRepository"
        val DEFAULT_AUTHENTICATION_METHOD = AuthenticationMethodModel.Pin
        val DEFAULT_PATTERN =
            listOf(
                AuthenticationPatternCoordinate(2, 0),
                AuthenticationPatternCoordinate(2, 1),
                AuthenticationPatternCoordinate(2, 2),
                AuthenticationPatternCoordinate(1, 1),
                AuthenticationPatternCoordinate(0, 0),
                AuthenticationPatternCoordinate(0, 1),
                AuthenticationPatternCoordinate(0, 2),
            )
        val WRONG_PATTERN = DEFAULT_PATTERN.reversed()
        const val MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT = 5
        const val MAX_FAILED_AUTH_TRIES_BEFORE_WIPE =
            MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT +
                LockPatternUtils.FAILED_ATTEMPTS_BEFORE_WIPE_GRACE
        val LOCKOUT_DURATION = 30.seconds
        const val LOCKOUT_DURATION_SECONDS = 30
        const val HINTING_PIN_LENGTH = 6
        val DEFAULT_PIN = buildList { repeat(HINTING_PIN_LENGTH) { add(it + 1) } }
        val WRONG_PIN = buildList { repeat(HINTING_PIN_LENGTH) { add(9 - it) } }
        val DEFAULT_PASSWORD = "password".toList()
        val WRONG_PASSWORD = "wrong_password".toList()

        private fun AuthenticationMethodModel.toSecurityMode(): SecurityMode {
            return when (this) {
                is AuthenticationMethodModel.Pin -> SecurityMode.PIN
                is AuthenticationMethodModel.Password -> SecurityMode.Password
                is AuthenticationMethodModel.Pattern -> SecurityMode.Pattern
                is AuthenticationMethodModel.None -> SecurityMode.None
                is AuthenticationMethodModel.Sim -> SecurityMode.SimPin
                is AuthenticationMethodModel.Biometric -> SecurityMode.SecureLockDeviceBiometricAuth
            }
        }

        @LockPatternUtils.CredentialType
        private fun getCurrentCredentialType(securityMode: SecurityMode): Int {
            return when (securityMode) {
                SecurityMode.PIN,
                SecurityMode.SimPin,
                SecurityMode.SimPuk -> LockPatternUtils.CREDENTIAL_TYPE_PIN
                SecurityMode.Password -> LockPatternUtils.CREDENTIAL_TYPE_PASSWORD
                SecurityMode.Pattern -> LockPatternUtils.CREDENTIAL_TYPE_PATTERN
                SecurityMode.None -> LockPatternUtils.CREDENTIAL_TYPE_NONE
                else -> error("Unsupported SecurityMode $securityMode!")
            }
        }

        private fun LockscreenCredential.matches(expectedCredential: List<Any>): Boolean {
            @Suppress("UNCHECKED_CAST")
            return when {
                isPin ->
                    credential.map { byte -> byte.toInt().toChar() - '0' } == expectedCredential
                isPassword -> credential.map { byte -> byte.toInt().toChar() } == expectedCredential
                isPattern ->
                    credential.contentEquals(
                        LockPatternUtils.patternToByteArray(
                            expectedCredential as List<LockPatternView.Cell>,
                            LockPatternUtils.PATTERN_SIZE_DEFAULT
                        )
                    )
                else -> error("Unsupported credential type $type!")
            }
        }

        private fun List<AuthenticationPatternCoordinate>.toCells(): List<LockPatternView.Cell> {
            return map { coordinate -> LockPatternView.Cell.of(
                coordinate.y, coordinate.x, LockPatternUtils.PATTERN_SIZE_DEFAULT) }
        }
    }
}

@Module(includes = [FakeAuthenticationRepositoryModule.Bindings::class])
object FakeAuthenticationRepositoryModule {
    @Provides
    @SysUISingleton
    fun provideFake(scope: TestScope) =
        FakeAuthenticationRepository(currentTimeMs = { scope.currentTime })

    @Module
    interface Bindings {
        @Binds fun bindFake(fake: FakeAuthenticationRepository): AuthenticationRepository
    }
}
