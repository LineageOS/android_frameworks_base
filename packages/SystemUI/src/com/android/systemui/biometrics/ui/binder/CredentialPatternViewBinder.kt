package com.android.systemui.biometrics.ui.binder

import android.hardware.biometrics.Flags
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternView
import com.android.systemui.biometrics.ui.CredentialPatternView
import com.android.systemui.biometrics.ui.CredentialView
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import kotlinx.coroutines.flow.combine

/** Sub-binder for the [CredentialPatternView]. */
object CredentialPatternViewBinder {

    /** Bind the view. */
    fun bind(
        view: CredentialPatternView,
        host: CredentialView.Host,
        viewModel: CredentialViewModel,
    ) {
        val lockPatternView: LockPatternView = view.requireViewById(R.id.lockPattern)

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (com.android.systemui.Flags.msdlFeedback()) {
                    lockPatternView.setExternalHapticsPlayer {
                        viewModel.performPatternDotFeedback()
                    }
                }
                // observe credential validation attempts and submit/cancel buttons
                launch {
                    viewModel.header.collect { header ->
                        lockPatternView.setOnPatternListener(
                            OnPatternDetectedListener { pattern, patternSize ->
                                if (pattern.isPatternTooShort()) {
                                    // Pattern size is less than the minimum
                                    // do not count it as a failed attempt
                                    viewModel.showPatternTooShortError()
                                } else {
                                    lockPatternView.isEnabled = false
                                    launch {
                                        viewModel.checkCredential(pattern, patternSize, header)
                                    }
                                }
                            }
                        )
                    }
                }

                launch { viewModel.stealthMode.collect { lockPatternView.isInStealthMode = it } }

                // dismiss on a valid credential check
                launch {
                    combine(viewModel.validatedAttestation, viewModel.isCredentialAllowed, ::Pair)
                        .collect { (attestation, isAllowed) ->
                            val matched = attestation != null
                            lockPatternView.isEnabled = !matched
                            if (matched) {
                                host.onCredentialMatched(attestation!!, isAllowed)
                            }
                        }
                }
            }
        }
    }
}

private class OnPatternDetectedListener(
    private val onDetected: (pattern: List<LockPatternView.Cell>, patternSize: Byte) -> Unit
) : LockPatternView.OnPatternListener {
    override fun onPatternCellAdded(pattern: List<LockPatternView.Cell>) {}

    override fun onPatternCleared() {}

    override fun onPatternStart() {}

    override fun onPatternDetected(pattern: List<LockPatternView.Cell>, patternSize: Byte) {
        onDetected(pattern, patternSize)
    }
}

private fun List<LockPatternView.Cell>.isPatternTooShort(): Boolean =
    size < LockPatternUtils.MIN_PATTERN_REGISTER_FAIL
