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

package com.android.systemui.screencapture.record.smallscreen.ui.compose

import android.content.res.Resources
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.load
import com.android.systemui.res.R
import com.android.systemui.screencapture.common.ui.compose.LoadingIcon
import com.android.systemui.screencapture.common.ui.compose.loadIcon
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModel
import com.android.systemui.screencapture.record.smallscreen.ui.viewmodel.RecordDetailsTargetViewModel
import com.android.systemui.screencapture.record.ui.viewmodel.ScreenCaptureRecordParametersViewModel

@Composable
fun RecordDetailsSettings(
    parametersViewModel: ScreenCaptureRecordParametersViewModel,
    targetViewModel: RecordDetailsTargetViewModel,
    drawableLoaderViewModel: DrawableLoaderViewModel,
    onAppSelectorClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(parametersViewModel.shouldShowHint) {
        if (parametersViewModel.shouldShowHint) {
            Toast.makeText(context, R.string.screen_record_selfie_hint, Toast.LENGTH_SHORT).show()
            parametersViewModel.onCameraHintShown()
        }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.padding(vertical = 12.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            AnimatedVisibility(visible = targetViewModel.canChangeTarget) {
                CaptureTargetSelector(
                    items = targetViewModel.items,
                    selectedItemIndex = targetViewModel.selectedIndex,
                    onItemSelected = { targetViewModel.select(it) },
                    itemToString = { it.label.load()!! },
                    isItemEnabled = { it.isSelectable },
                    viewModel = drawableLoaderViewModel,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            AppSelectorButton(
                visible = targetViewModel.shouldShowAppSelector,
                appLabel = targetViewModel.selectedAppLabel?.toString(),
                viewModel = drawableLoaderViewModel,
                onClick = onAppSelectorClicked,
            )

            RichSwitch(
                visible = true,
                icon =
                    loadIcon(
                        viewModel = drawableLoaderViewModel,
                        resId = R.drawable.ic_phone_expressive,
                        contentDescription = null,
                    ),
                label = stringResource(R.string.screen_record_record_device_audio_label),
                checked = parametersViewModel.shouldRecordDevice,
                enabled = parametersViewModel.canChangeAudioSource,
                disabledMessageRes = R.string.screen_record_record_audio_during_recording_warning,
                onCheckedChange = { parametersViewModel.shouldRecordDevice = it },
                modifier = Modifier,
            )
            RichSwitch(
                visible = true,
                icon =
                    loadIcon(
                        viewModel = drawableLoaderViewModel,
                        resId = R.drawable.ic_mic_expressive,
                        contentDescription = null,
                    ),
                label = stringResource(R.string.screen_record_record_microphone_label),
                checked = parametersViewModel.shouldRecordMicrophone,
                enabled = parametersViewModel.canChangeAudioSource,
                disabledMessageRes = R.string.screen_record_record_audio_during_recording_warning,
                onCheckedChange = { parametersViewModel.shouldRecordMicrophone = it },
                modifier = Modifier,
            )
            RichSwitch(
                visible = parametersViewModel.canUseFrontCamera,
                icon =
                    loadIcon(
                        viewModel = drawableLoaderViewModel,
                        resId = R.drawable.ic_selfie_expressive,
                        contentDescription = null,
                    ),
                label = stringResource(R.string.screen_record_should_show_camera_label),
                checked = parametersViewModel.shouldShowFrontCamera,
                onCheckedChange = { parametersViewModel.shouldShowFrontCamera = it },
                modifier = Modifier,
            )
            RichSwitch(
                visible = targetViewModel.canShowTouches,
                icon =
                    loadIcon(
                        viewModel = drawableLoaderViewModel,
                        resId = R.drawable.ic_touch_expressive,
                        contentDescription = null,
                    ),
                label = stringResource(R.string.screen_record_should_show_touches_label),
                checked = parametersViewModel.shouldShowTaps,
                onCheckedChange = { parametersViewModel.shouldShowTaps = it },
                modifier = Modifier,
            )
            RichSwitch(
                visible = true,
                icon =
                    loadIcon(
                        viewModel = drawableLoaderViewModel,
                        resId = R.drawable.ic_sr_quality,
                        contentDescription = null,
                    ),
                label = stringResource(R.string.screenrecord_lowquality_label),
                checked = parametersViewModel.lowQuality,
                onCheckedChange = { parametersViewModel.setLowQuality(it) },
                modifier = Modifier,
            )
            RichSwitch(
                visible = true,
                icon =
                    loadIcon(
                        viewModel = drawableLoaderViewModel,
                        resId = R.drawable.ic_storage,
                        contentDescription = null,
                    ),
                label = stringResource(R.string.screenrecord_longer_timeout_switch_label),
                checked = parametersViewModel.longerDuration,
                onCheckedChange = { parametersViewModel.setLongerDuration(it) },
                modifier = Modifier,
            )
            SettingsRow(visible = true, modifier = Modifier.padding(top = 4.dp)) {
                Crossfade(
                    targetState = targetViewModel.warningMessageRes,
                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                ) { warningMessageRes ->
                    Text(
                        text = stringResource(warningMessageRes),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier.animateContentSize(
                                MaterialTheme.motionScheme.fastSpatialSpec()
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RichSwitch(
    icon: State<IconModel?>,
    label: String,
    checked: Boolean,
    visible: Boolean,
    onCheckedChange: (isChecked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledMessageRes: Int = Resources.ID_NULL,
) {
    val context = LocalContext.current
    require(enabled || disabledMessageRes != Resources.ID_NULL) {
        "Provide disabled message for a disabled switch"
    }
    val disabledMessage: String? = if (enabled) null else stringResource(disabledMessageRes)
    SettingsRow(
        visible = visible,
        modifier =
            modifier
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = { newValue ->
                        if (enabled) {
                            onCheckedChange(newValue)
                        } else {
                            Toast.makeText(context, disabledMessage, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                .clearAndSetSemantics {
                    if (enabled) {
                        contentDescription = label
                    } else {
                        contentDescription = "$label. ${disabledMessage!!}"
                        disabled()
                    }
                },
    ) {
        LoadingIcon(icon = icon.value, modifier = Modifier.size(40.dp).padding(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp).weight(1f).basicMarquee(),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier,
        )
    }
}

@Composable
private fun AppSelectorButton(
    appLabel: String?,
    viewModel: DrawableLoaderViewModel,
    onClick: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    SettingsRow(
        visible = visible,
        modifier = modifier.semantics { role = Role.Button }.clickable(onClick = onClick),
    ) {
        LoadingIcon(
            icon =
                loadIcon(
                        viewModel = viewModel,
                        resId = R.drawable.ic_apps_expressive,
                        contentDescription = null,
                    )
                    .value,
            modifier = Modifier.size(40.dp).padding(8.dp),
        )
        Column(modifier = Modifier.padding(horizontal = 8.dp).weight(weight = 1f).basicMarquee()) {
            Text(
                text = stringResource(R.string.screen_record_single_app_hint),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier,
            )
            AnimatedVisibility(visible = !appLabel.isNullOrEmpty()) {
                Text(
                    text = appLabel ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier,
                )
            }
        }

        LoadingIcon(
            icon =
                loadIcon(
                        viewModel = viewModel,
                        resId = R.drawable.ic_chevron_forward_expressive,
                        contentDescription = null,
                    )
                    .value,
            modifier = Modifier.padding(12.dp).size(16.dp),
        )
    }
}

@Composable
private fun SettingsRow(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                expandVertically(
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    expandFrom = Alignment.Top,
                ),
        exit =
            fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                shrinkVertically(
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    shrinkTowards = Alignment.Top,
                ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.heightIn(min = 64.dp).padding(horizontal = 20.dp).fillMaxWidth(),
            content = content,
        )
    }
}
