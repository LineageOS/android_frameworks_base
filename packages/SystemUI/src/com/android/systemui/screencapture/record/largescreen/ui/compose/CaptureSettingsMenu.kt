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

package com.android.systemui.screencapture.record.largescreen.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.res.R
import com.android.systemui.screencapture.common.ui.compose.LoadingIcon
import com.android.systemui.screencapture.common.ui.compose.StyledTooltip
import com.android.systemui.screencapture.common.ui.compose.loadIcon
import com.android.systemui.screencapture.record.largescreen.ui.viewmodel.PreCaptureToolbarViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CaptureSettingsMenu(viewModel: PreCaptureToolbarViewModel, screenRecordingSelected: Boolean) {
    val recordParameters = viewModel.recordParametersViewModel

    val settingsButtonContentDescription =
        stringResource(R.string.screen_capture_toolbar_settings_button_a11y)
    val expandedStateDescription =
        stringResource(R.string.screen_capture_a11y_settings_menu_expanded)
    val collapsedStateDescription =
        stringResource(R.string.screen_capture_a11y_settings_menu_collapsed)

    val settingsButtonIcon by
        loadIcon(viewModel = viewModel, resId = R.drawable.ic_settings, contentDescription = null)

    Box {
        var showMenu by remember { mutableStateOf(false) }
        StyledTooltip(tooltipText = settingsButtonContentDescription) {
            IconToggleButton(
                checked = showMenu,
                onCheckedChange = { showMenu = it },
                shape = IconButtonDefaults.smallSquareShape,
                modifier =
                    Modifier.semantics {
                        this.role = Role.DropdownList
                        this.contentDescription = settingsButtonContentDescription
                        this.stateDescription =
                            if (showMenu) expandedStateDescription else collapsedStateDescription
                    },
            ) {
                LoadingIcon(settingsButtonIcon)
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = DpOffset(x = 0.dp, y = 28.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            if (viewModel.showClicksAndKeysSupported) {
                val showClicksIcon by
                    loadIcon(
                        viewModel = viewModel,
                        resId = R.drawable.ic_settings,
                        contentDescription = null,
                    )
                SettingsMenuItem(
                    text = stringResource(R.string.screen_capture_show_clicks_and_keys),
                    leadingIcon = showClicksIcon,
                    checked = recordParameters.shouldShowTaps,
                    onCheckedChange = { recordParameters.shouldShowTaps = it },
                    enabled = screenRecordingSelected,
                )
            }

            val deviceAudioIcon by
                loadIcon(
                    viewModel = viewModel,
                    resId = R.drawable.ic_speaker_rounded,
                    contentDescription = null,
                )
            val microphoneAudioIcon by
                loadIcon(
                    viewModel = viewModel,
                    resId = R.drawable.ic_mic_expressive,
                    contentDescription = null,
                )

            SettingsMenuItem(
                text = stringResource(R.string.screen_capture_device_audio),
                leadingIcon = deviceAudioIcon,
                checked = recordParameters.shouldRecordDevice,
                onCheckedChange = { recordParameters.shouldRecordDevice = it },
                enabled = screenRecordingSelected,
            )
            SettingsMenuItem(
                text = stringResource(R.string.screen_capture_microphone_audio),
                leadingIcon = microphoneAudioIcon,
                checked = recordParameters.shouldRecordMicrophone,
                onCheckedChange = { recordParameters.shouldRecordMicrophone = it },
                enabled = screenRecordingSelected,
            )

            if (viewModel.frontCameraSupported) {
                val frontCameraIcon by
                    loadIcon(
                        viewModel = viewModel,
                        resId = R.drawable.ic_person_filled,
                        contentDescription = null,
                    )
                SettingsMenuItem(
                    text = stringResource(R.string.screen_capture_front_camera),
                    leadingIcon = frontCameraIcon,
                    checked = recordParameters.shouldShowFrontCamera,
                    onCheckedChange = { recordParameters.shouldShowFrontCamera = it },
                    enabled = screenRecordingSelected,
                )
            }

            val lowQualityIcon by
                loadIcon(
                    viewModel = viewModel,
                    resId = R.drawable.ic_sr_quality,
                    contentDescription = null,
                )

            SettingsMenuItem(
                text = stringResource(R.string.screenrecord_lowquality_label),
                leadingIcon = lowQualityIcon,
                checked = recordParameters.lowQuality,
                onCheckedChange = { recordParameters.setLowQuality(it) },
                enabled = screenRecordingSelected,
            )

            val storageIcon by
                loadIcon(
                    viewModel = viewModel,
                    resId = R.drawable.ic_storage,
                    contentDescription = null,
                )

            SettingsMenuItem(
                text = stringResource(R.string.screenrecord_longer_timeout_switch_label),
                leadingIcon = storageIcon,
                checked = recordParameters.longerDuration,
                onCheckedChange = { recordParameters.setLongerDuration(it) },
                enabled = screenRecordingSelected,
            )

            val hevcIcon by
                loadIcon(
                    viewModel = viewModel,
                    resId = R.drawable.ic_hevc,
                    contentDescription = null,
                )

            SettingsMenuItem(
                text = stringResource(R.string.screenrecord_hevc_switch_label),
                leadingIcon = hevcIcon,
                checked = recordParameters.hevc,
                onCheckedChange = { recordParameters.setHevc(it) },
                enabled = screenRecordingSelected,
            )

            if (viewModel.customSaveLocationSupported) {
                SaveLocationDropdown(
                    viewModel = viewModel,
                    onClose = { showMenu = false },
                    modifier =
                        Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsMenuItem(
    text: String,
    leadingIcon: IconModel.Loaded?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = { onCheckedChange(!checked) },
        leadingIcon = {
            leadingIcon?.let {
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(icon = leadingIcon)
                }
            }
        },
        trailingIcon = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
        enabled = enabled,
    )
}
