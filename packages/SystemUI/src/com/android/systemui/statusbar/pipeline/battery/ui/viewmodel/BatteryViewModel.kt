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

package com.android.systemui.statusbar.pipeline.battery.ui.viewmodel

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepository
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryAttributionModel.Charging
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryAttributionModel.Defend
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryAttributionModel.PowerSave
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryAttributionModel.Unknown
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryGlyph
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.text.NumberFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
sealed class BatteryViewModel(
    val interactor: BatteryInteractor,
    shouldShowPercent: Flow<Boolean>,
    @Application context: Context,
) : HydratedActivatable() {

    val level by interactor.level.hydratedStateOf(initialValue = 0)

    val isFull by interactor.isFull.hydratedStateOf(initialValue = false)

    val isCharging: Boolean by interactor.isCharging.hydratedStateOf(initialValue = false)

    val isBatteryPercentSettingEnabled: Boolean by
        interactor.showPercentNextToIcon.hydratedStateOf(initialValue = false)

    val isBatteryPercentInsideIconSettingEnabled: Boolean by
        interactor.showPercentInsideIcon.hydratedStateOf(initialValue = false)

    /** A [List<BatteryGlyph>] representation of the current [level] */
    private val levelGlyphs: Flow<List<BatteryGlyph>> =
        interactor.level.map { it?.glyphRepresentation() ?: emptyList() }

    private val _glyphList: Flow<List<BatteryGlyph>> =
        shouldShowPercent.flatMapLatest {
            if (it) {
                levelGlyphs
            } else {
                flowOf(emptyList())
            }
        }

    /** A [List<BatteryGlyph>] representation of the battery percent. */
    open val glyphList: List<BatteryGlyph> by _glyphList.hydratedStateOf(initialValue = emptyList())

    /** The current attribution, if any */
    protected val attributionGlyph: Flow<BatteryGlyph?> =
        interactor.batteryAttributionType.map {
            when (it) {
                Charging -> BatteryGlyph.Bolt

                PowerSave -> BatteryGlyph.Plus

                Defend -> BatteryGlyph.Defend

                Unknown -> BatteryGlyph.Question

                else -> null
            }
        }

    val attribution: BatteryGlyph? by attributionGlyph.hydratedStateOf(initialValue = null)

    private val _colorProfile: Flow<ColorProfile> =
        combine(interactor.batteryAttributionType, interactor.isCritical) { attr, isCritical ->
            when (attr) {
                Charging,
                Defend ->
                    ColorProfile(
                        dark = BatteryColors.DarkTheme.Charging,
                        light = BatteryColors.LightTheme.Charging,
                    )

                PowerSave ->
                    ColorProfile(
                        dark = BatteryColors.DarkTheme.PowerSave,
                        light = BatteryColors.LightTheme.PowerSave,
                    )

                else ->
                    if (isCritical) {
                        ColorProfile(
                            dark = BatteryColors.DarkTheme.Error,
                            light = BatteryColors.LightTheme.Error,
                        )
                    } else {
                        ColorProfile(
                            dark = BatteryColors.DarkTheme.Default,
                            light = BatteryColors.LightTheme.Default,
                        )
                    }
            }
        }

    /** For the current battery state, what is the relevant color profile to use */
    val colorProfile: ColorProfile by
        _colorProfile.hydratedStateOf(
            initialValue =
                ColorProfile(
                    dark = BatteryColors.DarkTheme.Default,
                    light = BatteryColors.LightTheme.Default,
                )
        )

    val contentDescription: ContentDescription by
        combine(interactor.batteryAttributionType, interactor.level) { attr, level ->
                when (attr) {
                    Defend -> {
                        val descr =
                            context.getString(
                                R.string.accessibility_battery_level_charging_paused,
                                level,
                            )

                        ContentDescription.Loaded(descr)
                    }
                    Charging -> {
                        val descr =
                            context.getString(R.string.accessibility_battery_level_charging, level)
                        ContentDescription.Loaded(descr)
                    }
                    PowerSave -> {
                        val descr =
                            context.getString(
                                R.string.accessibility_battery_level_battery_saver_with_percent,
                                level,
                            )
                        ContentDescription.Loaded(descr)
                    }
                    Unknown -> {
                        val descr = context.getString(R.string.accessibility_battery_unknown)
                        ContentDescription.Loaded(descr)
                    }
                    else -> {
                        val descr = context.getString(R.string.accessibility_battery_level, level)
                        ContentDescription.Loaded(descr)
                    }
                }
            }
            .hydratedStateOf(initialValue = ContentDescription.Loaded(null))

    val batteryIconStyle: Int by
        interactor.batteryIconStyle.hydratedStateOf(initialValue = BatteryRepository.ICON_STYLE_DEFAULT)

    /** For use in the shade, where we might need to show an estimate */
    val batteryTimeRemainingEstimate: String? by
        interactor.isCharging
            .flatMapLatest { charging ->
                if (charging) {
                    flowOf(null)
                } else {
                    interactor.batteryTimeRemainingEstimate
                }
            }
            .hydratedStateOf(traceName = "timeRemainingEstimate", initialValue = null)

    val batteryPercent: String? by
        interactor.level
            .map { level ->
                if (level == null) {
                    null
                } else {
                    NumberFormat.getPercentInstance().format(level / 100f)
                }
            }
            .hydratedStateOf(
                traceName = "batteryPercent",
                initialValue = null,
            )

    /** Base factory class so implementations can take any kind of view model */
    interface Factory {
        fun create(): BatteryViewModel
    }

    /** View model that shows the percentage based on the percent setting */
    class BasedOnUserSetting
    @AssistedInject
    constructor(interactor: BatteryInteractor, @Application context: Context) :
        BatteryViewModel(
            interactor = interactor,
            shouldShowPercent = interactor.showPercentInsideIcon,
            context = context,
        ) {

        @AssistedFactory
        fun interface Factory : BatteryViewModel.Factory {
            override fun create(): BasedOnUserSetting
        }
    }

    /**
     * BatteryViewModel that shows percentage when the device is charging, or when the setting is
     * enabled
     */
    class ShowPercentWhenChargingOrSetting
    @AssistedInject
    constructor(interactor: BatteryInteractor, @Application context: Context) :
        BatteryViewModel(
            interactor = interactor,
            shouldShowPercent =
                combine(interactor.isCharging, interactor.showPercentNextToIcon) {
                    charging,
                    settingEnabled ->
                    charging || settingEnabled
                },
            context = context,
        ) {

        @AssistedFactory
        fun interface Factory : BatteryViewModel.Factory {
            override fun create(): ShowPercentWhenChargingOrSetting
        }
    }

    /** BatteryViewModel that always shows the percentage */
    class AlwaysShowPercent
    @AssistedInject
    constructor(interactor: BatteryInteractor, @Application context: Context) :
        BatteryViewModel(
            interactor = interactor,
            shouldShowPercent = flowOf(true),
            context = context,
        ) {

        @AssistedFactory
        fun interface Factory : BatteryViewModel.Factory {
            override fun create(): AlwaysShowPercent
        }
    }

    companion object {
        /**
         * Status bar battery height, based on a 26.5x13 base canvas. Defined in [sp] so that the
         * icon properly scales when the font size changes (consistent with other status bar icons)
         */
        fun getStatusBarBatteryHeight(context: Context): TextUnit {
            return (13 * getScaleFactor(context)).sp
        }

        /**
         * [TextStyle] for status bar battery text [Composable]s. The size of this text will scale
         * consistent with display size changes
         */
        @OptIn(ExperimentalMaterial3ExpressiveApi::class) // Required for bodyMediumEmphasized style
        @Composable
        fun getStatusBarBatteryTextStyle(context: Context): TextStyle {
            val baseStyle = MaterialTheme.typography.bodyMediumEmphasized
            return baseStyle.copy(fontSize = baseStyle.fontSize * getScaleFactor(context))
        }

        private fun getScaleFactor(context: Context): Float {
            return context.resources.getFloat(R.dimen.status_bar_icon_scale_factor)
        }

        /** Resource id used to identify battery composable view in SysUI tests */
        const val TEST_TAG = "battery"

        fun Int.glyphRepresentation(): List<BatteryGlyph> = toString().map { it.toGlyph() }

        private fun Char.toGlyph(): BatteryGlyph =
            when (this) {
                '0' -> BatteryGlyph.Zero
                '1' -> BatteryGlyph.One
                '2' -> BatteryGlyph.Two
                '3' -> BatteryGlyph.Three
                '4' -> BatteryGlyph.Four
                '5' -> BatteryGlyph.Five
                '6' -> BatteryGlyph.Six
                '7' -> BatteryGlyph.Seven
                '8' -> BatteryGlyph.Eight
                '9' -> BatteryGlyph.Nine
                else -> throw IllegalArgumentException("cannot make glyph from char ($this)")
            }
    }
}

/** Wrap the light and dark color into a single object so the view can decide which one it needs */
data class ColorProfile(val dark: BatteryColors.DarkTheme, val light: BatteryColors.LightTheme)
