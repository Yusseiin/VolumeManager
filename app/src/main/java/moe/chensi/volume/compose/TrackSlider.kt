package moe.chensi.volume.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
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

@Composable
fun TrackSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackColor: Color = MaterialTheme.colorScheme.primaryContainer,
    onTrackColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    fillColor: Color = MaterialTheme.colorScheme.primary,
    onFillColor: Color = MaterialTheme.colorScheme.onPrimary,
    cornerRadius: Dp = 8.dp,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val latestValue by rememberUpdatedState(coercedValue)
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }

    val totalRange = valueRange.endInclusive - valueRange.start
    val fillWidthPercentage = if (totalRange == 0f) 0f else (coercedValue - valueRange.start) / totalRange

    Box(
        modifier = modifier
            .fillMaxWidth()
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
            }
            .pointerInput(enabled) {
                if (enabled) {
                    // The value follows the finger. Moving it by however far the finger travelled
                    // instead meant the reachable range depended on where the drag started, so
                    // neither end could be reached from the middle.
                    fun update(x: Float) {
                        val percentage = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        val newValue = valueRange.start + percentage * totalRange
                        if (newValue != latestValue) {
                            onValueChange(newValue)
                        }
                    }

                    detectTapGestures { offset -> update(offset.x) }
                }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    fun update(x: Float) {
                        val percentage = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        val newValue = valueRange.start + percentage * totalRange
                        if (newValue != latestValue) {
                            onValueChange(newValue)
                        }
                    }

                    detectHorizontalDragGestures(
                        onDragStart = { offset -> update(offset.x) }
                    ) { change, _ -> update(change.position.x) }
                }
            },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
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
                            0f,
                            fillWidthPercentage * size.width,
                            size.height,
                            cornerRadius = CornerRadius(with(density) { 2.dp.toPx() })
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

