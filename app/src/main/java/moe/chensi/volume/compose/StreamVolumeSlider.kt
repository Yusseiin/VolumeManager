package moe.chensi.volume.compose

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.chensi.volume.ui.theme.Typography
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"

private const val TAG = "VolumeManager.Volume"

@SuppressLint("StaticFieldLeak")
internal object VolumeChangeObserver {
    private val displayableStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_VOICE_CALL,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_NOTIFICATION
    )

    private val refCount = AtomicInteger(0)
    private var receiver: BroadcastReceiver? = null
    private var registeredContext: Context? = null
    private var _lastChangedStreamType by mutableIntStateOf(-1)

    /** Stream type carried by the last volume change broadcast, or -1 if none was seen yet. */
    val lastChangedStreamType: Int get() = _lastChangedStreamType

    /**
     * Volume of each stream as the system last reported it, or as a slider optimistically set it.
     *
     * The broadcast already carries the new level, so this avoids re-reading [AudioManager], whose
     * client side cache can still hold the previous value right after a change. Being plain state
     * that callers read while composing also means every step gets drawn: re-reading from a
     * `LaunchedEffect` keyed on a change counter dropped values, because the effect is cancelled
     * before it runs when the next step arrives first, so rapid presses skipped numbers.
     */
    private val streamVolumes = mutableStateMapOf<Int, Int>()

    fun volumeOf(streamType: Int): Int? = streamVolumes[streamType]

    /** Show the value the user just asked for, without waiting for the system to broadcast it. */
    fun setKnownVolume(streamType: Int, volume: Int) {
        streamVolumes[streamType] = volume
    }

    /**
     * Re-read the volumes from [audioManager].
     *
     * A change made anywhere else arrives as a broadcast, but a volume key handled by this app
     * apparently produces none that reaches us, which left the popup frozen while a key was held.
     */
    fun refresh(audioManager: AudioManager) {
        for (streamType in displayableStreams) {
            streamVolumes[streamType] = audioManager.getStreamVolume(streamType)
        }
    }

    @Synchronized
    fun startObserving(context: Context) {
        if (refCount.incrementAndGet() == 1) {
            registeredContext = context.applicationContext
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) {
                        return
                    }

                    val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                    if (streamType < 0) {
                        return
                    }

                    _lastChangedStreamType = streamType

                    val volume = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                    Log.i(TAG, "broadcast stream = $streamType, volume = $volume")

                    if (volume >= 0) {
                        streamVolumes[streamType] = volume
                    }
                }
            }
            registeredContext!!.registerReceiver(
                receiver!!,
                IntentFilter(VOLUME_CHANGED_ACTION),
                Context.RECEIVER_NOT_EXPORTED
            )
        }
    }

    @Synchronized
    fun stopObserving() {
        if (refCount.decrementAndGet() == 0) {
            receiver?.let {
                registeredContext!!.unregisterReceiver(it)
                receiver = null
            }
            registeredContext = null

            // These go stale while nothing is watching
            streamVolumes.clear()
            _lastChangedStreamType = -1
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamVolumeSlider(
    streamType: Int,
    icon: ImageVector,
    name: String,
    audioManager: AudioManager,
    setStreamVolume: (streamType: Int, index: Int) -> Unit,
    footer: (@Composable () -> Unit)? = null,
    onChange: (() -> Unit)? = null
) {
    val context = LocalContext.current

    DisposableEffect(context) {
        VolumeChangeObserver.startObserving(context)
        onDispose {
            VolumeChangeObserver.stopObserving()
        }
    }

    val maxVolume = remember(streamType) { audioManager.getStreamMaxVolume(streamType).toFloat() }

    // Compare against what was last asked for rather than against the value read back: a muted
    // stream reads as zero whatever its index is, which made this swallow the very request that
    // would have muted it, leaving the slider stuck one step above the bottom
    var lastRequested by remember(streamType) { mutableIntStateOf(-1) }
    val initialVolume = remember(streamType) { audioManager.getStreamVolume(streamType) }
    val volume = VolumeChangeObserver.volumeOf(streamType) ?: initialVolume

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackSlider(
            modifier = Modifier.weight(1f),
            cornerRadius = 20.dp,
            value = volume.toFloat(),
            valueRange = 0f..maxVolume,
            onValueChange = { value ->
                // Round, don't truncate: truncating maps everything below the last step down, so
                // the maximum was only reachable by landing exactly on the end of the track
                val target = value.roundToInt()
                if (target == lastRequested) {
                    return@TrackSlider
                }

                lastRequested = target
                VolumeChangeObserver.setKnownVolume(streamType, target)
                setStreamVolume(streamType, target)
                Log.i(
                    TAG,
                    "slider stream = $streamType, asked for $target, got ${
                        audioManager.getStreamVolume(streamType)
                    }"
                )
                onChange?.invoke()
            },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp, 8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = name,
                    modifier = Modifier.size(32.dp),
                )
                StreamSliderTextContent(name = name, valueText = "$volume/${maxVolume.toInt()}")
            }
        }

        footer?.invoke()
    }
}

@Composable
internal fun RowScope.StreamSliderTextContent(name: String, valueText: String) {
    Text(
        text = name,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )

    Text(
        text = valueText,
        style = Typography.bodySmall,
        maxLines = 1,
    )
}
