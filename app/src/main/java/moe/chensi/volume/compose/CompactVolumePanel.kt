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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chensi.volume.R
import moe.chensi.volume.ui.theme.Typography

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

    var streamType by remember { mutableIntStateOf(activeStreamType(audioManager)) }
    var volume by remember { mutableIntStateOf(audioManager.getStreamVolume(streamType)) }
    var maxVolume by remember { mutableFloatStateOf(audioManager.getStreamMaxVolume(streamType).toFloat()) }

    DisposableEffect(context) {
        VolumeChangeObserver.startObserving(context)
        onDispose {
            VolumeChangeObserver.stopObserving()
        }
    }

    val volumeChangedCount = VolumeChangeObserver.volumeChangedCount

    // Follow the stream the system actually changed, so the pill always shows what the buttons did
    LaunchedEffect(volumeChangedCount) {
        val changedStreamType = VolumeChangeObserver.lastChangedStreamType
        if (changedStreamType >= 0) {
            streamType = changedStreamType
        }
        maxVolume = audioManager.getStreamMaxVolume(streamType).toFloat()
        volume = audioManager.getStreamVolume(streamType)
    }

    val name = streamName(streamType)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        VerticalTrackSlider(
            modifier = Modifier
                .width(52.dp)
                .height(176.dp),
            value = volume.toFloat(),
            valueRange = 0f..maxVolume,
            onValueChange = { value ->
                val target = value.toInt()
                if (volume == target) {
                    return@VerticalTrackSlider
                }

                volume = target
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
private fun activeStreamType(audioManager: AudioManager): Int = when {
    audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL

    audioManager.isMusicActive -> AudioManager.STREAM_MUSIC
    else -> AudioManager.STREAM_RING
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
