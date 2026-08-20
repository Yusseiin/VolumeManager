package moe.chensi.volume.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vertical counterpart of [TrackSlider]: the fill grows from the bottom up, dragging up raises the
 * value, and tapping jumps to the tapped position. Like [TrackSlider], [content] is drawn twice so
 * whatever it contains (usually an icon) picks up [onTrackColor] or [onFillColor] depending on
 * whether the fill covers it.
 */
@Composable
fun VerticalTrackSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackColor: Color = MaterialTheme.colorScheme.primaryContainer,
    onTrackColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    fillColor: Color = MaterialTheme.colorScheme.primary,
    onFillColor: Color = MaterialTheme.colorScheme.onPrimary,
    cornerRadius: Dp = 24.dp,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val latestValue by rememberUpdatedState(coercedValue)
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }

    val totalRange = valueRange.endInclusive - valueRange.start
    val fillHeightPercentage =
        if (totalRange == 0f) 0f else (coercedValue - valueRange.start) / totalRange

    Box(
        modifier = modifier
            // Gestures go before `clip`: clipping applies to hit testing as well as
            // drawing, so with rounded ends only a narrow band down the middle of the
            // pill reaches the very top and bottom
            .pointerInput(enabled) {
                if (enabled) {
                    // The value follows the finger: the bottom of the pill is zero and the top is
                    // the maximum, wherever the gesture started
                    fun update(y: Float) {
                        val percentage = (1f - y / size.height.toFloat()).coerceIn(0f, 1f)
                        val newValue = valueRange.start + percentage * totalRange
                        if (newValue != latestValue) {
                            onValueChange(newValue)
                        }
                    }

                    detectTapGestures { offset -> update(offset.y) }
                }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    fun update(y: Float) {
                        val percentage = (1f - y / size.height.toFloat()).coerceIn(0f, 1f)
                        val newValue = valueRange.start + percentage * totalRange
                        if (newValue != latestValue) {
                            onValueChange(newValue)
                        }
                    }

                    detectVerticalDragGestures(
                        onDragStart = { offset -> update(offset.y) }
                    ) { change, _ -> update(change.position.y) }
                }
            }
            .clip(GenericShape { size, _ ->
                addRoundRect(
                    RoundRect(
                        0f, 0f, size.width, size.height, cornerRadius = CornerRadius(cornerRadiusPx)
                    )
                )
            })
            .background(trackColor)
            .semantics {
                // This app is itself an accessibility service, so its own sliders should at least
                // be readable and settable by one
                progressBarRangeInfo = ProgressBarRangeInfo(coercedValue, valueRange)
                setProgress { target ->
                    val coerced = target.coerceIn(valueRange.start, valueRange.endInclusive)
                    if (coerced == latestValue) {
                        false
                    } else {
                        onValueChange(coerced)
                        true
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier.matchParentSize()
        ) {
            CompositionLocalProvider(LocalContentColor provides onTrackColor) {
                content()
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(GenericShape { size, _ ->
                    addRoundRect(
                        RoundRect(
                            0f,
                            size.height * (1f - fillHeightPercentage),
                            size.width,
                            size.height,
                            cornerRadius = CornerRadius(cornerRadiusPx)
                        )
                    )
                })
                .background(fillColor)
        ) {
            CompositionLocalProvider(LocalContentColor provides onFillColor) {
                content()
            }
        }
    }
}
