/*
 * Copyright (C) 2025 crDroid Android Project
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

package com.android.systemui.statusbar.pipeline.battery.ui.composable

import android.graphics.Rect
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel

@Composable
fun BatteryWithPercent(
    viewModel: BatteryViewModel,
    isDarkProvider: () -> IsAreaDark,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
    showPercent: Boolean = true,
    showEstimate: Boolean = false,
) {
    val batteryHeight =
        with(LocalDensity.current) {
            BatteryViewModel.getStatusBarBatteryHeight(LocalContext.current).toDp()
        }

    val textStyle =
        with(LocalDensity.current) {
            BatteryViewModel.getStatusBarBatteryTextStyle(LocalContext.current).copy(
                platformStyle = PlatformTextStyle(
                    includeFontPadding = false
                ),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                )
            )
        }

    var bounds by remember { mutableStateOf(Rect()) }

    val colorProducer = {
        if (isDarkProvider().isDarkTheme(bounds)) {
            BatteryColors.DarkTheme.Default.fill
        } else {
            BatteryColors.LightTheme.Default.fill
        }
    }

    Row(
        modifier =
            modifier.wrapContentWidth().onLayoutRectChanged { relativeLayoutBounds ->
                bounds =
                    with(relativeLayoutBounds.boundsInScreen) { Rect(left, top, right, bottom) }
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            UnifiedBattery(
                viewModel = viewModel,
                isDarkProvider = isDarkProvider,
                modifier = Modifier.height(batteryHeight).wrapContentWidth(),
            )
        }

        if (showPercent) {
            viewModel.batteryPercent?.let {
                BasicText(
                    text = it,
                    color = colorProducer,
                    style = textStyle,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }

        if (showEstimate) {
            viewModel.batteryTimeRemainingEstimate?.let {
                BasicText(
                    text = it,
                    color = colorProducer,
                    style = textStyle,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = 1),
                )
            }
        }
    }
}
