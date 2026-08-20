package moe.chensi.volume

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.pm.PackageInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.chensi.volume.data.App
import moe.chensi.volume.data.AppPreferencesStore
import moe.chensi.volume.system.AudioPlaybackConfigurationProxy
import moe.chensi.volume.system.NotificationManagerProxy
import moe.chensi.volume.system.PackageManagerProxy
import org.joor.Reflect
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

@SuppressLint("PrivateApi")
class Manager(context: Context, dataStore: DataStore<Preferences>) {
    companion object {
        private const val TAG = "VolumeManager.Manager"

        const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
    }

    enum class ShizukuStatus {
        Uninstalled, Disconnected, PermissionDenied, Connected
    }

    private var _shizukuStatus by mutableStateOf(ShizukuStatus.Disconnected)
    val shizukuStatus
        get() = _shizukuStatus

    val audioManager = context.getSystemService(AudioManager::class.java)!!.apply {
        Reflect.onClass(AudioManager::class.java).call("getService").get<Any>()
            .apply { ToggleableBinderProxy.wrap(this) }
    }

    val activityManager = context.getSystemService(ActivityManager::class.java)!!.apply {
        Reflect.onClass(ActivityManager::class.java).call("getService").get<Any>()
            .apply { ToggleableBinderProxy.wrap(this) }
    }
    private val packageManager by lazy { PackageManagerProxy.get(context) }
    val notificationManagerProxy = NotificationManagerProxy(context)

    private val appPreferencesStore = AppPreferencesStore(dataStore)
    private val _systemSliderVisibility = mutableStateMapOf<String, Boolean>()
    val systemSliderVisibility: Map<String, Boolean>
        get() = _systemSliderVisibility

    fun isSystemSliderVisible(id: String): Boolean {
        return _systemSliderVisibility[id] ?: true
    }

    fun setSystemSliderVisible(id: String, visible: Boolean) {
        if ((_systemSliderVisibility[id] ?: true) == visible) {
            return
        }

        _systemSliderVisibility[id] = visible
        appPreferencesStore.setSystemSliderVisible(id, visible)
    }

    private val appContext = context.applicationContext

    private val scope = CoroutineScope(Dispatchers.IO)

    val apps = mutableStateMapOf<String, App>()

    /**
     * Without this the list only ever grows: an uninstalled app stays in it until the process
     * restarts, and a newly installed one shows up only by chance, when [getApp] happens to miss.
     */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.data?.schemeSpecificPart ?: return

            when (intent.action) {
                Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                    apps.remove(packageName)
                    appPreferencesStore.remove(packageName)
                }

                // Reading package info goes through Shizuku, which is far too slow to do on the
                // main thread this receiver runs on
                Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> scope.launch {
                    // A replaced app's stored `PackageInfo` is stale, so rebuild its entry
                    apps.remove(packageName)
                    packageManager.getPackageInfo(packageName)?.let { addApp(it) }
                }
            }
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }

        appContext.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun addApp(packageInfo: PackageInfo) {
        val appInfo = packageInfo.applicationInfo ?: return
        val packageName = packageInfo.packageName

        apps[packageName] = App(
            packageManager,
            packageInfo,
            packageManager.loadLabel(appInfo),
            appPreferencesStore.getOrCreate(packageName),
            { preferences -> appPreferencesStore.save(packageName, preferences) }
        )
    }

    private fun reloadApps() {
        for (packageInfo in packageManager.getInstalledPackagesForAllUsers()) {
            if (!apps.containsKey(packageInfo.packageName)) {
                addApp(packageInfo)
            }
        }
    }

    private fun getApp(packageName: String): App? {
        val app = apps[packageName]
        if (app != null) {
            return app
        }

        // Maybe just installed?
        reloadApps()
        return apps[packageName]
    }

    @EnableBinderProxy
    private fun initialize() {
        reloadApps()
        registerPackageReceiver()

        val playbackConfigurations = audioManager.activePlaybackConfigurations
        processAudioPlaybackConfigurations(playbackConfigurations)

        audioManager.registerAudioPlaybackCallback(
            object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    for (app in apps.values) {
                        app.clearPlayers()
                    }
                    processAudioPlaybackConfigurations(configs)
                }
            }, null
        )
    }

    /**
     * Set a stream's volume, through Shizuku so that values a normal app is not allowed to write go
     * through as well.
     *
     * Zero on the ring and notification streams is not an index the framework accepts, it is a mute:
     * asking for it directly gets quietly clamped to one. Muting those streams individually also
     * keeps the phone's ringer mode alone, so silencing notifications doesn't silence the ringer.
     */
    @EnableBinderProxy
    fun setStreamVolume(streamType: Int, index: Int) {
        val mutable = streamType == AudioManager.STREAM_RING ||
                streamType == AudioManager.STREAM_NOTIFICATION

        if (mutable && index == 0) {
            audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)

            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "muted stream $streamType, now ${audioManager.getStreamVolume(streamType)}, " +
                            "muted = ${audioManager.isStreamMute(streamType)}"
                )
            }

            return
        }

        if (mutable && audioManager.isStreamMute(streamType)) {
            audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
        }

        audioManager.setStreamVolume(streamType, index, 0)
    }

    @SuppressLint("DiscouragedPrivateApi")
    @EnableBinderProxy
    fun processAudioPlaybackConfigurations(configs: List<AudioPlaybackConfiguration>) {
        val runningProcesses = activityManager.runningAppProcesses

        for (config in configs) {
            val proxy = AudioPlaybackConfigurationProxy(config)

            val pid = proxy.clientPid
            val process = runningProcesses.find { process -> process.pid == pid } ?: continue

            val packageName = process.pkgList[0] ?: continue
            val app = getApp(packageName) ?: continue

            app.addPlayer(proxy)
        }
    }

    init {
        val isShizukuInstalled = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        if (!isShizukuInstalled) {
            _shizukuStatus = ShizukuStatus.Uninstalled
        } else if (!Shizuku.pingBinder()) {
            _shizukuStatus = ShizukuStatus.Disconnected
        }

        Shizuku.addBinderReceivedListenerSticky {
            if (Shizuku.isPreV11()) {
                return@addBinderReceivedListenerSticky
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _shizukuStatus = ShizukuStatus.Connected
                start()
            } else {
                _shizukuStatus = ShizukuStatus.PermissionDenied
            }
        }

        Shizuku.addBinderDeadListener {
            _shizukuStatus = ShizukuStatus.Disconnected
        }

        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                _shizukuStatus = ShizukuStatus.Connected
                start()
            }
        }

        ShizukuProvider.requestBinderForNonProviderProcess(context)
    }

    private fun start() {
        appPreferencesStore.track { first ->
            if (!first) {
                val (values, indices) = appPreferencesStore.snapshot()
                for ((packageName, index) in indices) {
                    // Replace with new reference
                    getApp(packageName)?.setPreferences(values[index])
                }
            }

            _systemSliderVisibility.clear()
            _systemSliderVisibility.putAll(appPreferencesStore.systemSliderVisibility)

            if (first) {
                initialize()
            }
        }
    }
}
