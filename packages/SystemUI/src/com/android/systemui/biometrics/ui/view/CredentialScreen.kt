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

package com.android.systemui.biometrics.ui.view

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.biometrics.PromptInfo
import android.view.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.internal.widget.LockPatternView
import com.android.systemui.biometrics.domain.interactor.BiometricPromptView
import com.android.systemui.biometrics.domain.model.BiometricOperationInfo
import com.android.systemui.biometrics.domain.model.BiometricPromptRequest
import com.android.systemui.biometrics.shared.model.BiometricUserInfo
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.biometrics.ui.viewmodel.BiometricPromptHeaderViewModelImpl
import com.android.systemui.biometrics.ui.viewmodel.CredentialHeaderViewModel
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel
import com.android.systemui.compose.modifiers.sysUiResTagContainer
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import kotlinx.coroutines.delay

@Composable
fun CredentialScreen(
    viewModelFactory: CredentialViewModel.Factory,
    onCancel: () -> Unit = {}, // TODO: These three callbacks are Spaghetti related
    onCredentialMatched: (ByteArray, Boolean) -> Unit,
    onFallbackSelected: (Int) -> Unit = {},
    onContentViewMoreOptionsButtonPressed: () -> Unit = {},
) {
    val viewModel = rememberViewModel("CredentialScreen") { viewModelFactory.create() }
    val credentialKind by
        viewModel.credentialKind.collectAsStateWithLifecycle(initialValue = PromptKind.None)
    val currentView by
        viewModel.currentView.collectAsStateWithLifecycle(
            initialValue = BiometricPromptView.BIOMETRIC
        )
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle(initialValue = "")
    LaunchedEffect(errorMessage) {
        if (errorMessage.isNotBlank()) {
            // TODO: Pull this out and consider a11y
            val maxErrorDuration = 3000L
            delay(maxErrorDuration)
            viewModel.resetErrorMessage()
        }
    }

    val stealthMode by viewModel.stealthMode.collectAsStateWithLifecycle(initialValue = false)
    val isTwoPane by viewModel.isTwoPane.collectAsStateWithLifecycle(initialValue = false)
    val isCredentialAllowed by
        viewModel.isCredentialAllowed.collectAsStateWithLifecycle(initialValue = true)

    val fallbackOptions by
        viewModel.fallbackOptions.collectAsStateWithLifecycle(initialValue = emptyList())
    val showFallback by
        viewModel.showFallbackButton.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    val (fallbackText, fallbackAction) =
        remember(fallbackOptions) {
            if (fallbackOptions.size > 1) {
                // Show fallback page button if several options
                Pair(
                    context.getString(R.string.biometric_dialog_fallback_button),
                    { viewModel.onSwitchToFallbackScreen() },
                )
            } else if (fallbackOptions.size == 1) {
                // Show first option directly if only one
                Pair(fallbackOptions[0].text.toString(), { onFallbackSelected(0) })
            } else {
                Pair("", {})
            }
        }

    // TODO: Probably a better way to handle this
    val initialHeader =
        BiometricPromptHeaderViewModelImpl(
            request =
                BiometricPromptRequest.Credential.Password(
                    info = PromptInfo(),
                    userInfo = BiometricUserInfo(0),
                    operationInfo = BiometricOperationInfo(0L),
                    opPackageName = "",
                ),
            user = BiometricUserInfo(0, 0),
            title = "",
            subtitle = "",
            description = "",
            icon = ColorDrawable(Color.TRANSPARENT),
            logoDescription = "",
            showEmergencyCallButton = false,
        )
    val header by viewModel.header.collectAsStateWithLifecycle(initialValue = initialHeader)

    val verifyPinPassAction: suspend (CharSequence) -> ByteArray? = { credential ->
        viewModel.checkCredential(credential, header)
    }

    val verifyPatternAction: suspend (List<LockPatternView.Cell>, Byte) -> ByteArray? = { pattern, patternSize ->
        viewModel.checkCredential(pattern, patternSize, header)
    }

    val handleSuccess: (ByteArray) -> Unit = { attestation ->
        onCredentialMatched(attestation, isCredentialAllowed)
    }

    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val isSeascape =
        remember(configuration) {
            val rotation = view.display?.rotation ?: Surface.ROTATION_0
            rotation == Surface.ROTATION_270
        }

    PlatformTheme {
        BoxWithConstraints(
            modifier = Modifier.wrapContentHeight().sysUiResTagContainer(),
            contentAlignment = Alignment.Center,
        ) {
            val credentialInput =
                @Composable {
                    when (credentialKind) {
                        PromptKind.Pin -> {
                            CredentialPinView(
                                onVerify = verifyPinPassAction,
                                onSuccess = { credential -> handleSuccess(credential) },
                                onPinPress = viewModel::performPinPressFeedback,
                                isVisible = currentView == BiometricPromptView.CREDENTIAL,
                                error = errorMessage,
                            )
                        }

                        PromptKind.Password -> {
                            CredentialPasswordView(
                                onVerify = verifyPinPassAction,
                                onSuccess = { credential -> handleSuccess(credential) },
                                isVisible = currentView == BiometricPromptView.CREDENTIAL,
                                error = errorMessage,
                                userId = header.user.userIdForPasswordEntry,
                            )
                        }

                        PromptKind.Pattern -> {
                            CredentialPatternView(
                                onVerify = verifyPatternAction,
                                onSuccess = { credential -> handleSuccess(credential) },
                                onPatternCellAdded = viewModel::performPatternDotFeedback,
                                stealthMode = stealthMode,
                                isVisible = currentView == BiometricPromptView.CREDENTIAL,
                                error = errorMessage,
                            )
                        }

                        else -> {}
                    }
                }

            val footer =
                @Composable {
                    CredentialFooter(
                        onFallbackClick = fallbackAction,
                        fallbackText = fallbackText,
                        showFallback = showFallback,
                        onEmergencyCall = { viewModel.doEmergencyCall(view.context) },
                        showEmergencyCall = header.showEmergencyCallButton,
                    )
                }

            if (isTwoPane) {
                LandscapeCredentialLayout(
                    header = header,
                    content = credentialInput,
                    footer = footer,
                    onCancel = onCancel,
                    isReversed = isSeascape,
                    onContentViewMoreOptionsButtonPressed = onContentViewMoreOptionsButtonPressed,
                )
            } else {
                PortraitCredentialLayout(
                    header = header,
                    content = credentialInput,
                    footer = footer,
                    onCancel = onCancel,
                    isPin = credentialKind == PromptKind.Pin,
                    onContentViewMoreOptionsButtonPressed = onContentViewMoreOptionsButtonPressed,
                )
            }
        }
    }
}

@Composable
private fun PortraitCredentialLayout(
    header: CredentialHeaderViewModel,
    content: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    onCancel: () -> Unit,
    isPin: Boolean = false,
    onContentViewMoreOptionsButtonPressed: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxContentHeight = maxHeight * 0.6f
        Column(
            modifier =
                Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(weight = 1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter,
            ) {
                PromptHeader(
                    header = header,
                    onContentViewMoreOptionsButtonPressed = onContentViewMoreOptionsButtonPressed,
                )
            }
            if (isPin) {
                // Constrain pin to max of 60% of screen height
                Box(
                    modifier = Modifier.heightIn(max = maxContentHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    content()
                }
            } else {
                content()
            }
            footer()
        }

        FilledIconButton(
            onClick = onCancel,
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.cancel),
            )
        }
    }
}

@Composable
private fun LandscapeCredentialLayout(
    header: CredentialHeaderViewModel,
    content: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    onCancel: () -> Unit,
    isReversed: Boolean,
    onContentViewMoreOptionsButtonPressed: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .fillMaxHeight()
                .navigationBarsPadding()
                .padding(24.dp)
                .widthIn(max = 800.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        val headerPane =
            @Composable {
                Box(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier =
                            Modifier.fillMaxHeight()
                                .padding(
                                    end = if (isReversed) 0.dp else 24.dp,
                                    start = if (isReversed) 24.dp else 0.dp,
                                ),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                        ) {
                            PromptHeaderLandscape(
                                header = header,
                                onContentViewMoreOptionsButtonPressed =
                                    onContentViewMoreOptionsButtonPressed,
                            )
                        }
                        footer()
                    }

                    FilledIconButton(
                        onClick = onCancel,
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        modifier =
                            Modifier.align(Alignment.TopEnd)
                                .padding(end = if (isReversed) 0.dp else 24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                }
            }

        val inputPane =
            @Composable {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().imePadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        content()
                    }
                }
            }

        // TODO: UX clarification on bio flip
        if (isReversed) {
            inputPane()
            headerPane()
        } else {
            headerPane()
            inputPane()
        }
    }
}
