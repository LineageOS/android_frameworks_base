/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.pipeline.battery.ui.composable

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastFirstOrNull
import com.android.systemui.common.ui.compose.load
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepository
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryFrame
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryGlyph
import com.android.systemui.statusbar.pipeline.battery.shared.ui.PathSpec
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws a battery directly on to a [Canvas]. The canvas is scaled to fill its container, and the
 * resulting battery is scaled using a FIT_CENTER type scaling that preserves the aspect ratio.
 */
@Composable
fun BatteryCanvas(
    path: PathSpec,
    innerWidth: Float,
    innerHeight: Float,
    glyphs: List<BatteryGlyph>,
    level: Int?,
    isFull: Boolean,
    colorsProvider: () -> BatteryColors,
    modifier: Modifier = Modifier,
    contentDescription: String = "",
) {

    val totalWidth by
        remember(glyphs) {
            mutableFloatStateOf(
                if (glyphs.isEmpty()) {
                    0f
                } else {
                    // Pads in between each glyph, skipping the first
                    glyphs.drop(1).fold(glyphs.first().width) { acc: Float, next: BatteryGlyph ->
                        acc + INTER_GLYPH_PADDING_PX + next.width
                    }
                }
            )
        }

    Canvas(
        modifier = modifier.fillMaxSize().sysuiResTag(BatteryViewModel.TEST_TAG),
        contentDescription = contentDescription,
    ) {
        val scale = path.scaleTo(size.width, size.height)
        val colors = colorsProvider()

        scale(scale, pivot = Offset.Zero) {
            if (isFull) {
                // Saves a layer since we don't need background here
                drawPath(path = path.path, color = colors.fill)
            } else {
                // First draw the body
                val bgColor =
                    if (glyphs.isEmpty()) {
                        colors.backgroundOnly
                    } else {
                        colors.backgroundWithGlyph
                    }
                drawPath(path.path, bgColor)
                // Then draw the body, clipped to the fill level
                if (level != null && level > 0) {
                    clipRect(0f, 0f, level.scaledLevel(), innerHeight) {
                        drawRoundRect(
                            color = colors.fill,
                            topLeft = Offset.Zero,
                            size = Size(width = innerWidth, height = innerHeight),
                            cornerRadius = CornerRadius(2f),
                        )
                    }
                }
            }

            // Now draw the glyphs
            var horizontalOffset = (BatteryFrame.innerWidth - totalWidth) / 2
            for (glyph in glyphs) {
                // Move the glyph to the right spot
                val verticalOffset = (BatteryFrame.innerHeight - glyph.height) / 2
                inset(
                    // Never try and inset more than half of the available size - see b/400246091.
                    minOf(horizontalOffset, size.width / 2),
                    minOf(verticalOffset, size.height / 2),
                ) {
                    glyph.draw(this, colors)
                }

                horizontalOffset += glyph.width + INTER_GLYPH_PADDING_PX
            }
        }
    }
}

// Experimentally-determined value
private const val INTER_GLYPH_PADDING_PX = 0.8f

/** Calculate the right-edge of the clip for the fill-rect, based on a level of [0-100] */
private fun Int.scaledLevel(): Float {
    val endSide = BatteryFrame.innerWidth
    return ceil((toFloat() / 100f) * endSide)
}

/**
 * A battery icon that will optionally display the percentage inside. Battery state attributions are
 * layered on top with a cutout path around them for visibility.
 *
 * This icon is designed to be parameterized on the height. The only valid way to use it is by
 * explicitly setting `Modifier.height()`, and using `Modifier.wrapContentWidth()` together.
 */
@Composable
fun UnifiedBattery(
    viewModel: BatteryViewModel,
    isDarkProvider: () -> IsAreaDark,
    modifier: Modifier,
) {
    var bounds by remember { mutableStateOf(Rect()) }

    val colorProvider = {
        if (isDarkProvider().isDarkTheme(bounds)) {
            viewModel.colorProfile.dark
        } else {
            viewModel.colorProfile.light
        }
    }

    BatteryLayout(
        attribution = viewModel.attribution,
        iconStyleProvider = { viewModel.batteryIconStyle },
        levelProvider = { viewModel.level },
        showLevelProvider = { viewModel.isBatteryPercentInsideIconSettingEnabled },
        isFullProvider = { viewModel.isFull },
        glyphsProvider = { viewModel.glyphList },
        colorsProvider = colorProvider,
        modifier =
            modifier.sysuiResTag(BatteryViewModel.TEST_TAG).onLayoutRectChanged {
                relativeLayoutBounds ->
                bounds =
                    with(relativeLayoutBounds.boundsInScreen) { Rect(left, top, right, bottom) }
            },
        contentDescription = viewModel.contentDescription.load() ?: "",
    )
}

@Composable
fun BatteryLayout(
    attribution: BatteryGlyph?,
    iconStyleProvider: () -> Int,
    levelProvider: () -> Int?,
    showLevelProvider: () -> Boolean,
    isFullProvider: () -> Boolean,
    glyphsProvider: () -> List<BatteryGlyph>,
    colorsProvider: () -> BatteryColors,
    modifier: Modifier,
    contentDescription: String = "",
) {
    Layout(
        content = {
            val iconStyle = iconStyleProvider()

            if (
                iconStyle == BatteryRepository.ICON_STYLE_CIRCLE ||
                    iconStyle == BatteryRepository.ICON_STYLE_CIRCLE_DOTTED
            ) {
                CircleBatteryBody(
                    attr = attribution,
                    iconStyleProvider = iconStyleProvider,
                    levelProvider = levelProvider,
                    showLevelProvider = showLevelProvider,
                    colorsProvider = colorsProvider,
                    modifier = Modifier.layoutId(BatteryMeasurePolicy.LayoutId.FrameCircle),
                    contentDescription = contentDescription,
                )
            } else if (iconStyle == BatteryRepository.ICON_STYLE_TEXT) {
                // Empty on purpose
            } else {
                BatteryBody(
                    pathSpec = BatteryFrame.bodyPathSpec,
                    levelProvider = levelProvider,
                    glyphsProvider = glyphsProvider,
                    isFullProvider = isFullProvider,
                    colorsProvider = colorsProvider,
                    modifier = Modifier.layoutId(BatteryMeasurePolicy.LayoutId.Frame),
                    contentDescription = contentDescription,
                )
                if (attribution != null) {
                    BatteryAttribution(
                        attr = attribution,
                        colorsProvider = colorsProvider,
                        modifier =
                            Modifier.layoutId(
                                BatteryMeasurePolicy.LayoutId.Attribution(wrapped = attribution)
                            ),
                    )
                } else {
                    BatteryCap(
                        colorsProvider = colorsProvider,
                        isFullProvider = isFullProvider,
                        glyphsProvider = glyphsProvider,
                        modifier = Modifier.layoutId(BatteryMeasurePolicy.LayoutId.Cap),
                    )
                }
            }
        },
        measurePolicy = BatteryMeasurePolicy(),
        // [Offscreen] Enables the BlendMode.Clear usage for the battery attribution
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    )
}

class BatteryMeasurePolicy : MeasurePolicy {
    sealed class LayoutId {
        data object Frame : LayoutId()

        data object FrameCircle : LayoutId()

        data object Cap : LayoutId()

        // We don't have to depend on the whole [BatteryGlyph] here, we just need to know the
        // size so we can scale and measure appropriately
        data class Attribution(val wrapped: BatteryGlyph) : LayoutId()
    }

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val batteryFrame =
            measurables.fastFirstOrNull {
                it.layoutId == LayoutId.Frame || it.layoutId == LayoutId.FrameCircle
            } ?: return layout(0, 0) {}

        // We will scale the entire battery icon based on the given height
        val scale = constraints.maxHeight / BatteryFrame.innerHeight

        val batterySize = BatteryFrame.bodyPathSpec.scaledSize(scale)
        val batteryFramePlaceable =
            batteryFrame.measure(
                constraints =
                    constraints.copy(
                        minWidth =
                            if (batteryFrame.layoutId == LayoutId.FrameCircle) {
                                batterySize.height.roundToInt()
                            } else {
                                batterySize.width.roundToInt()
                            },
                        maxWidth =
                            if (batteryFrame.layoutId == LayoutId.FrameCircle) {
                                batterySize.height.roundToInt()
                            } else {
                                batterySize.width.roundToInt()
                            },
                        minHeight = batterySize.height.roundToInt(),
                        maxHeight = batterySize.height.roundToInt(),
                    )
            )

        val cap = measurables.fastFirstOrNull { it.layoutId == LayoutId.Cap }
        val capPlaceable = run {
            cap?.let {
                val size = BatteryFrame.capPathSpec.scaledSize(scale)
                val w = size.width.roundToInt()
                val h = size.height.roundToInt()
                it.measure(
                    constraints =
                        constraints.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h)
                )
            }
        }

        val attr = measurables.fastFirstOrNull { it.layoutId is LayoutId.Attribution }
        val attrPlaceable = run {
            attr?.let {
                val ps = (it.layoutId as LayoutId.Attribution).wrapped
                val size = ps.scaledSize(scale)
                val w = size.width.roundToInt()
                val h = size.height.roundToInt()
                it.measure(
                    constraints =
                        constraints.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h)
                )
            }
        }

        var totalWidth: Int = batteryFramePlaceable.width
        if (attrPlaceable != null) {
            totalWidth += (attrPlaceable.width * 0.8).roundToInt()
        } else if (capPlaceable != null) {
            // 1dp of padding * scale for the cap
            totalWidth += capPlaceable.width + scale.roundToInt()
        }
        val totalHeight = batterySize.height.roundToInt()
        return layout(totalWidth, totalHeight) {
            if (layoutDirection == LayoutDirection.Rtl) {
                val (offsetX, placeable) =
                    when {
                        // Attr overlaps the battery frame by 20% of its own width
                        attrPlaceable != null ->
                            (attrPlaceable.width * (1 - attrOverlap)).roundToInt() to attrPlaceable

                        // Cap has exactly 1dp (scaled) of space after it
                        capPlaceable != null ->
                            capPlaceable.width + scale.roundToInt() to capPlaceable

                        else -> 0 to null
                    }

                // Place the battery frame first so the layers are in the right order
                batteryFramePlaceable.place(offsetX, 0)

                // Then place the cap or attribution. In RTL, it always is left-aligned
                placeable?.apply {
                    placeCenteredVertically(
                        placeable = this,
                        containerHeight = batteryFramePlaceable.height,
                        xOffset = 0,
                    )
                }
            } else {
                batteryFramePlaceable.place(0, 0)

                val (xOffset, placeable) =
                    when {
                        attrPlaceable != null ->
                            (batteryFramePlaceable.width - (attrOverlap * attrPlaceable.width))
                                .roundToInt() to attrPlaceable
                        capPlaceable != null ->
                            (batteryFramePlaceable.width + scale.roundToInt()) to capPlaceable
                        else -> 0 to null
                    }

                placeable?.apply {
                    placeCenteredVertically(
                        placeable = this,
                        containerHeight = batteryFramePlaceable.height,
                        xOffset = xOffset,
                    )
                }
            }
        }
    }

    private fun Placeable.PlacementScope.placeCenteredVertically(
        placeable: Placeable,
        containerHeight: Int,
        xOffset: Int,
    ) {
        placeable.place(x = xOffset, y = placeable.centerYOffset(containerHeight))
    }

    private fun Placeable.centerYOffset(outerHeight: Int) =
        ((outerHeight - height) / 2f).roundToInt()

    companion object {
        // Overlap the attribution by 20%
        private const val attrOverlap = 0.2f
    }
}

@Composable
fun CircleBatteryBody(
    attr: BatteryGlyph?,
    iconStyleProvider: () -> Int,
    levelProvider: () -> Int?,
    showLevelProvider: () -> Boolean,
    colorsProvider: () -> BatteryColors,
    modifier: Modifier = Modifier,
    contentDescription: String = "",
) {
    val colorError = MaterialTheme.colorScheme.error
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier, contentDescription = contentDescription) {
        val iconStyle = iconStyleProvider()
        val level = levelProvider()
        val showLevel = showLevelProvider()
        val colors = colorsProvider()

        val strokeWidth = size.height / 6.5f
        val radius = size.height / 2f - strokeWidth / 2f
        val center = Offset(size.width / 2, size.height / 2)

        // Draw thin gray ring first
        drawCircle(colors.backgroundOnly, radius, center, style = Stroke(strokeWidth))

        // Draw colored arc representing charge level
        if (level != null && level > 0) {
            drawArc(
                if (attr is BatteryGlyph.Bolt || attr is BatteryGlyph.Defend) {
                    BatteryColors.DarkTheme.Charging.fill
                } else if (attr is BatteryGlyph.Plus) {
                    BatteryColors.DarkTheme.PowerSave.fill
                } else if (level <= 20) {
                    colorError
                } else {
                    colors.attribution
                },
                270f,
                3.6f * level,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style =
                    Stroke(
                        strokeWidth,
                        pathEffect =
                            if (iconStyle == BatteryRepository.ICON_STYLE_CIRCLE_DOTTED) {
                                PathEffect.dashPathEffect(floatArrayOf(3f, 2f), 0f)
                            } else {
                                null
                            },
                    ),
            )
        }

        if (attr != null) {
            // Draw attribution
            inset(strokeWidth * 2f) {
                val attrScale = attr.scaleTo(size.width, size.height)
                val pathBounds = attr.path.getBounds()
                withTransform({
                    scale(attrScale, Offset.Zero)
                    translate(
                        (size.width - (pathBounds.width * attrScale)) / 2f,
                        (size.height - (pathBounds.height * attrScale)) / 2f,
                    )
                }) {
                    drawPath(
                        path = attr.path,
                        color = Color.Black,
                        style = Stroke(2f),
                        blendMode = BlendMode.Clear,
                    )
                    drawPath(attr.path, colors.attribution)
                }
            }
        } else if (showLevel && level != null && level < 100) {
            // Draw charge level
            val textLayoutResult =
                textMeasurer.measure(
                    text = level.toString(),
                    style =
                        TextStyle(
                            color = colors.attribution,
                            fontSize = 6.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )

            drawText(
                textLayoutResult = textLayoutResult,
                topLeft =
                    Offset(
                        size.width / 2 - textLayoutResult.size.width / 2f,
                        size.height / 2 - textLayoutResult.size.height / 2f,
                    ),
            )
        }
    }
}

/**
 * Draws just the round-rect piece of the battery frame. If [glyphsProvider] is non-empty, then this
 * composable also renders the glyphs centered in the frame.
 *
 * Always shows the fill amount, clipped to the given [levelProvider]
 */
@Composable
fun BatteryBody(
    pathSpec: PathSpec,
    levelProvider: () -> Int?,
    glyphsProvider: () -> List<BatteryGlyph>,
    isFullProvider: () -> Boolean,
    colorsProvider: () -> BatteryColors,
    modifier: Modifier = Modifier,
    contentDescription: String = "",
) {
    Canvas(modifier = modifier, contentDescription = contentDescription) {
        val rtl = layoutDirection == LayoutDirection.Rtl

        val level = levelProvider()
        val colors = colorsProvider()
        val glyphs = glyphsProvider()
        val isFull = isFullProvider()
        val frameColor =
            getBatteryFrameColor(isFull = isFull, hasGlyphs = glyphs.isNotEmpty(), colors = colors)

        val totalGlyphWidth =
            if (glyphs.isEmpty()) {
                0f
            } else {
                // Pads in between each glyph, skipping the first
                glyphs.drop(1).fold(glyphs.first().width) { acc: Float, next: BatteryGlyph ->
                    acc + INTER_GLYPH_PADDING_PX + next.width
                }
            }

        val s = pathSpec.scaleTo(size.width, size.height)
        scale(scale = s, pivot = Offset.Zero) {
            if (isFull) {
                // If full, the frameColor is already the fill color.
                drawPath(pathSpec.path, frameColor)
            } else {
                // 1. Draw body background
                drawPath(pathSpec.path, frameColor)

                // 2. clip the fill to the level if we have it
                if (level != null && level > 0) {
                    clipRect(
                        left = if (!rtl) 0f else BatteryFrame.innerWidth - level.scaledLevel(),
                        top = 0f,
                        right = if (!rtl) level.scaledLevel() else BatteryFrame.innerWidth,
                        bottom = BatteryFrame.innerHeight,
                    ) {
                        // 3. Draw the rounded rect fill fully, it'll be clipped above
                        drawRoundRect(
                            color = colors.fill,
                            topLeft = Offset.Zero,
                            size =
                                Size(
                                    width = BatteryFrame.innerWidth,
                                    height = BatteryFrame.innerHeight,
                                ),
                            CornerRadius(x = BatteryFrame.cornerRadius),
                        )
                    }
                }
            }

            // Next: draw the glyphs
            var horizontalOffset = (BatteryFrame.innerWidth - totalGlyphWidth) / 2f
            for (glyph in glyphs) {
                // Move the glyph to the right spot
                val verticalOffset = (BatteryFrame.innerHeight - glyph.height) / 2
                inset(
                    // Never try and inset more than half of the available size - see b/400246091.
                    minOf(horizontalOffset, size.width / 2),
                    minOf(verticalOffset, size.height / 2),
                ) {
                    glyph.draw(this, colors)
                }
                horizontalOffset += glyph.width + INTER_GLYPH_PADDING_PX
            }
        }
    }
}

@Composable
fun BatteryCap(
    colorsProvider: () -> BatteryColors,
    isFullProvider: () -> Boolean,
    glyphsProvider: () -> List<BatteryGlyph>,
    modifier: Modifier = Modifier,
) {
    val pathSpec = BatteryFrame.capPathSpec
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Canvas(modifier = modifier.scale(scaleX = if (rtl) -1f else 1f, scaleY = 1f)) {
        val colors = colorsProvider()
        val isFull = isFullProvider()
        val hasGlyphs = glyphsProvider().isNotEmpty()
        val color = getBatteryFrameColor(isFull = isFull, hasGlyphs = hasGlyphs, colors = colors)

        val s = pathSpec.scaleTo(size.width, size.height)
        scale(s, pivot = Offset.Zero) { drawPath(pathSpec.path, color = color) }
    }
}

@Composable
fun BatteryAttribution(
    attr: BatteryGlyph,
    colorsProvider: () -> BatteryColors,
    modifier: Modifier = Modifier,
) {
    val stroke = remember { Stroke(width = 2f) }
    // Do not RTL the attribution, because they are text-like. '?' shouldn't be flipped, for example
    Canvas(modifier = modifier) {
        val s = attr.scaleTo(size.width, size.height)
        val colors = colorsProvider()
        scale(s, pivot = Offset.Zero) {
            drawPath(
                path = attr.path,
                color = Color.Black,
                style = stroke,
                blendMode = BlendMode.Clear,
            )
            drawPath(attr.path, color = colors.attribution)
        }
    }
}

/** Determines the correct color for the battery frame (body and cap) based on its state. */
private fun getBatteryFrameColor(
    isFull: Boolean,
    hasGlyphs: Boolean,
    colors: BatteryColors,
): Color {
    return when {
        isFull -> colors.fill
        hasGlyphs -> colors.backgroundWithGlyph
        else -> colors.backgroundOnly
    }
}
