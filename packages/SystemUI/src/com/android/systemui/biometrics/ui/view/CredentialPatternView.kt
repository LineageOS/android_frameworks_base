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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.internal.widget.LockPatternView
import com.android.internal.widget.LockPatternView.Cell
import com.android.internal.widget.LockPatternView.DisplayMode
import kotlinx.coroutines.launch

@Composable
fun CredentialPatternView(
    onVerify: suspend (List<Cell>, Byte) -> ByteArray?,
    onSuccess: (ByteArray) -> Unit,
    onPatternCellAdded: () -> Unit,
    stealthMode: Boolean,
    isVisible: Boolean,
    error: String,
) {
    val scope = rememberCoroutineScope()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PromptErrorText(error = error)

        // TODO: Existing bouncer pattern is not easily used here, using AndroidView for now
        AndroidView(
            modifier =
                Modifier.widthIn(max = 348.dp) // TODO: Use resource
                    .weight(1f, fill = false)
                    .aspectRatio(1f)
                    .fillMaxWidth(),
            factory = { context ->
                LockPatternView(context).apply {
                    setOnPatternListener(
                        object : LockPatternView.OnPatternListener {
                            // TODO: These will likely need to be updated for mouse
                            override fun onPatternStart() {}

                            override fun onPatternCleared() {}

                            override fun onPatternCellAdded(pattern: List<Cell>?) {
                                onPatternCellAdded()
                            }

                            override fun onPatternDetected(pattern: List<Cell>, patternSize: Byte) {
                                if (pattern.isEmpty()) return

                                isEnabled = false

                                scope.launch {
                                    val attestation = onVerify(pattern, patternSize)

                                    if (attestation != null) {
                                        onSuccess(attestation)
                                    } else {
                                        setDisplayMode(DisplayMode.Wrong)

                                        clearPattern()
                                        isEnabled = true
                                        setDisplayMode(DisplayMode.Correct)
                                    }
                                }
                            }
                        }
                    )
                }
            },
            update = { view ->
                view.isInStealthMode = stealthMode

                if (!isVisible) {
                    view.clearPattern()
                }
            },
        )
    }
}
