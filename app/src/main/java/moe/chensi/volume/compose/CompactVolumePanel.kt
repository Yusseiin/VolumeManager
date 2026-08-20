package moe.chensi.volume.compose

import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chensi.volume.R
import moe.chensi.volume.ui.theme.Typography
import kotlin.math.roundToInt

private val PILL_WIDTH = 52.dp

/** Height of the pill. This is the number to change if it should be taller or shorter. */
private val PILL_HEIGHT = 240.dp

/**
 * Stock-like compact volume slider: a single vertical pill for whichever stream the volume buttons
 * are currently affecting, with a button underneath that opens the full per-app panel.
 */
@Composable
fun CompactVolumePanel(
    audioManager: AudioManager,
    onChange: (() -> Unit)? = null,
    onExpand: () -> Unit,
) {
    val context = LocalContext.current

    DisposableEffect(context) {
        VolumeChangeObserver.startObserving(context)
        onDispose {
            VolumeChangeObserver.stopObserving()
        }
    }

    // Follow the stream the system actually changed, so the pill always shows what the buttons did,
    // guessing only until the first change comes in
    val guessedStreamType = remember { activeStreamType(audioManager) }
    val changedStreamType = VolumeChangeObserver.lastChangedStreamType
    val streamType = if (changedStreamType >= 0) changedStreamType else guessedStreamType

    val maxVolume = remember(streamType) { audioManager.getStreamMaxVolume(streamType).toFloat() }
    val initialVolume = remember(streamType) { audioManager.getStreamVolume(streamType) }

    // Read from the observer while composing, so every step the system reports gets drawn
    val volume = VolumeChangeObserver.volumeOf(streamType) ?: initialVolume

    val name = streamName(streamType)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VerticalTrackSlider(
            modifier = Modifier
                .width(PILL_WIDTH)
                .height(PILL_HEIGHT),
            value = volume.toFloat(),
            valueRange = 0f..maxVolume,
            onValueChange = { value ->
                // Round, don't truncate, or the maximum needs a pixel perfect drag to the very top
                val target = value.roundToInt()
                if (volume == target) {
                    return@VerticalTrackSlider
                }

                VolumeChangeObserver.setKnownVolume(streamType, target)
                audioManager.setStreamVolume(streamType, target, 0)
                onChange?.invoke()
            },
        ) {
            Text(
                text = "$volume/${maxVolume.toInt()}",
                style = Typography.bodySmall,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            )

            Icon(
                imageVector = streamIcon(streamType),
                contentDescription = name,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .size(24.dp),
            )
        }

        FilledTonalIconButton(onClick = onExpand) {
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = stringResource(R.string.expand_panel),
            )
        }
    }
}

/**
 * Best guess at the stream the volume buttons will act on, used until the first volume change
 * broadcast tells us for sure. Public APIs only.
 */
private fun activeStreamType(audioManager: AudioManager): Int = when (audioManager.mode) {
    AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL

    AudioManager.MODE_RINGTONE -> AudioManager.STREAM_RING

    // Volume keys act on media by default on current Android, whether or not something is playing,
    // so guessing anything else just shows the wrong slider until the first change arrives
    else -> AudioManager.STREAM_MUSIC
}

private fun streamIcon(streamType: Int): ImageVector = when (streamType) {
    AudioManager.STREAM_VOICE_CALL -> Icons.Default.PhoneInTalk
    AudioManager.STREAM_RING -> Icons.Default.RingVolume
    AudioManager.STREAM_ALARM -> Icons.Default.Alarm
    AudioManager.STREAM_NOTIFICATION -> Icons.Default.NotificationsActive
    else -> Icons.AutoMirrored.Default.VolumeUp
}

@Composable
private fun streamName(streamType: Int): String = stringResource(
    when (streamType) {
        AudioManager.STREAM_VOICE_CALL -> R.string.stream_call
        AudioManager.STREAM_RING -> R.string.stream_ring
        AudioManager.STREAM_ALARM -> R.string.stream_alarm
        AudioManager.STREAM_NOTIFICATION -> R.string.stream_notification
        else -> R.string.stream_media
    }
)
