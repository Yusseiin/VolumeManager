package moe.chensi.volume.data

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bounded, process-wide cache of app icons.
 *
 * Decoding every installed app's icon and holding onto it costs tens of megabytes on a device with
 * hundreds of apps, so only the most recently used ones are kept and the rest are decoded again if
 * they come back on screen.
 */
internal object AppIconCache {
    internal const val ICON_SIZE = 128

    private const val MAX_ENTRIES = 64

    private val cache =
        object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) =
                size > MAX_ENTRIES
        }

    suspend fun get(app: App): ImageBitmap {
        synchronized(cache) { cache[app.packageName] }?.let { return it }

        // Talks to the package manager over Shizuku and decodes a bitmap, never on the main thread
        val icon = withContext(Dispatchers.IO) { app.decodeIcon() }

        synchronized(cache) { cache[app.packageName] = icon }
        return icon
    }
}
