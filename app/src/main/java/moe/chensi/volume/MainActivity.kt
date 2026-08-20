@file:OptIn(ExperimentalMaterial3Api::class)

package moe.chensi.volume

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.chensi.volume.compose.AboutDialog
import moe.chensi.volume.compose.AppVolumeList
import moe.chensi.volume.compose.CrashReportDialog
import moe.chensi.volume.compose.SystemVolumePanel
import moe.chensi.volume.compose.ToggleButton
import moe.chensi.volume.ui.theme.VolumeManagerTheme
import org.joor.Reflect
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

@SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "VolumeManager.Activity"

        private const val SERVICE_NAME_SEPARATOR = ":"

        /** How long to let the system bind the service before deciding that it is dead. */
        private const val SERVICE_BIND_GRACE = 1500L

        private const val SERVICE_TOGGLE_DELAY = 300L
    }

    private lateinit var application: MyApplication

    @Suppress("SameParameterValue")
    @SuppressLint("MissingPermission")
    private fun grantSelfPermission(permission: String) {
        var state = this@MainActivity.checkSelfPermission(permission)
        if (state == PackageManager.PERMISSION_GRANTED) {
            return
        }

        // Grant permission via `PackageManager` doesn't work on some Samsung devices
        val process = Reflect.onClass(Shizuku::class.java).call(
            "newProcess", arrayOf("pm", "grant", packageName, permission), null, null
        ).get<ShizukuRemoteProcess>()
        process.waitFor()

        state = this@MainActivity.checkSelfPermission(permission)
        if (state == PackageManager.PERMISSION_GRANTED) {
            return
        }

        throw SecurityException("Can't grant self permission $permission")
    }

    private fun enableAccessibilityService(name: String) {
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

        var enabledAccessibilityServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        if (enabledAccessibilityServices.isNullOrBlank()) {
            enabledAccessibilityServices = name
        } else if (enabledAccessibilityServices.contains(name)) {
            return
        } else {
            enabledAccessibilityServices += SERVICE_NAME_SEPARATOR + name
        }

        Settings.Secure.putString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            enabledAccessibilityServices
        )

        enabledAccessibilityServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (enabledAccessibilityServices == null || !enabledAccessibilityServices.contains(name)) {
            throw SecurityException("Can't enable accessibility service $name")
        }
    }

    val powerManager by lazy { getSystemService(PowerManager::class.java)!! }
    var isIgnoringBatteryOptimization by mutableStateOf(false)
    private fun checkBatteryOptimization() {
        isIgnoringBatteryOptimization =
            powerManager.isIgnoringBatteryOptimizations(applicationInfo.packageName)
    }

    private fun disableAccessibilityService(name: String) {
        val enabledAccessibilityServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return

        val remaining = enabledAccessibilityServices.split(SERVICE_NAME_SEPARATOR)
            .filter { it.isNotBlank() && it != name }
            .joinToString(SERVICE_NAME_SEPARATOR)

        Settings.Secure.putString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, remaining
        )
    }

    /**
     * Make sure the accessibility service is enabled *and* actually running.
     *
     * Android marks the service as crashed whenever its process is killed, which happens every time
     * the app is reinstalled, and a crashed service stays in the enabled list while receiving
     * nothing at all: volume keys silently go to the system instead. Simply writing the setting
     * again does not help, the component has to leave the list and come back.
     */
    private fun ensureAccessibilityServiceRunning() {
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Not granted yet; ServiceStatus() grants it and enables the service on first compose
            return
        }

        val name = ComponentName(this, Service::class.java).flattenToString()

        // Writing secure settings is a binder call, keep it off the main thread
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                enableAccessibilityService(name)

                delay(SERVICE_BIND_GRACE)
                if (Service.isConnected) {
                    return@launch
                }

                Log.i(TAG, "service is enabled but not running, re-binding it")
                disableAccessibilityService(name)
                delay(SERVICE_TOGGLE_DELAY)
                enableAccessibilityService(name)
            } catch (e: Exception) {
                Log.e(TAG, "Can't make sure the accessibility service is running", e)
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        application = super.getApplication() as MyApplication
        val manager = application.manager

        CrashHandler.ensureInitialized(this)
        val showCrashReport =
            CrashHandler.hasCrashReport() && CrashHandler.readCrashReport() != null

        checkBatteryOptimization()

        setContent {
            var showAll by remember { mutableStateOf(false) }
            var crashReport by remember { mutableStateOf<String?>(null) }
            var showAboutDialog by remember { mutableStateOf(false) }

            LaunchedEffect(showCrashReport) {
                if (showCrashReport) {
                    crashReport = CrashHandler.readCrashReport()
                }
            }

            if (crashReport != null) {
                crashReport?.let { report ->
                    Dialog(
                        onDismissRequest = { }, properties = DialogProperties(
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                            usePlatformDefaultWidth = false
                        )
                    ) {
                        VolumeManagerTheme {
                            CrashReportDialog(
                                crashReport = report, onDismiss = {
                                    CrashHandler.clearCrashReport()
                                    crashReport = null
                                })
                        }
                    }
                }
            }

            if (showAboutDialog) {
                Dialog(
                    onDismissRequest = { showAboutDialog = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    VolumeManagerTheme {
                        AboutDialog(onDismiss = { showAboutDialog = false })
                    }
                }
            }

            VolumeManagerTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(), topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.app_title)) }, actions = {
                            if (manager.shizukuStatus == Manager.ShizukuStatus.Connected) {
                                ToggleButton(
                                    checked = showAll,
                                    checkedIcon = Icons.Default.Check,
                                    checkedDescription = stringResource(R.string.save),
                                    uncheckedIcon = Icons.Default.Settings,
                                    uncheckedDescription = stringResource(R.string.settings)
                                ) {
                                    showAll = it
                                }
                            }

                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                    TooltipAnchorPosition.Below, 12.dp
                                ),
                                tooltip = { PlainTooltip { Text(stringResource(R.string.about)) } },
                                state = rememberTooltipState()
                            ) {
                                IconButton(onClick = { showAboutDialog = true }) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = stringResource(R.string.about)
                                    )
                                }
                            }

                            if (BuildConfig.DEBUG) {
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                        TooltipAnchorPosition.Below, 12.dp
                                    ),
                                    tooltip = { PlainTooltip { Text(stringResource(R.string.test_crash_tooltip)) } },
                                    state = rememberTooltipState()
                                ) {
                                    IconButton(onClick = { throw RuntimeException("Test crash triggered from UI") }) {
                                        Icon(
                                            Icons.Default.BugReport,
                                            contentDescription = stringResource(R.string.test_crash)
                                        )
                                    }
                                }
                            }
                        })
                    }) { innerPadding ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(innerPadding)
                            // Enough of an inset that the slider ends are not under the edge
                            // gesture areas, and don't look clipped by the screen
                            .padding(24.dp, 0.dp)
                    ) {
                        when (manager.shizukuStatus) {
                            Manager.ShizukuStatus.Uninstalled -> {
                                val context = LocalContext.current
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(
                                        16.dp, Alignment.CenterVertically
                                    )
                                ) {
                                    Text(stringResource(R.string.shizuku_not_installed))
                                    Text(
                                        textAlign = TextAlign.Center,
                                        text = stringResource(R.string.shizuku_not_installed_hint)
                                    )
                                    Button(
                                        onClick = {
                                            val intent = Intent(
                                                Intent.ACTION_VIEW,
                                                "https://play.google.com/store/apps/details?id=${Manager.SHIZUKU_PACKAGE_NAME}".toUri()
                                            )
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        }) {
                                        Text(stringResource(R.string.shizuku_play_store))
                                    }
                                    Button(
                                        onClick = {
                                            val intent = Intent(
                                                Intent.ACTION_VIEW,
                                                "https://github.com/RikkaApps/Shizuku/releases".toUri()
                                            )
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        }) {
                                        Text(stringResource(R.string.shizuku_github))
                                    }
                                }
                            }

                            Manager.ShizukuStatus.Disconnected -> Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(
                                    16.dp, Alignment.CenterVertically
                                )
                            ) {
                                Text(stringResource(R.string.shizuku_waiting))
                                Text(
                                    textAlign = TextAlign.Center,
                                    text = stringResource(R.string.shizuku_waiting_hint)
                                )
                            }

                            Manager.ShizukuStatus.PermissionDenied -> Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(
                                    16.dp, Alignment.CenterVertically
                                )
                            ) {
                                Text(stringResource(R.string.shizuku_ready))
                                Text(
                                    textAlign = TextAlign.Center,
                                    text = stringResource(R.string.shizuku_permission_hint, stringResource(R.string.app_title))
                                )

                                Button(onClick = { Shizuku.requestPermission(0) }) {
                                    Text(text = stringResource(R.string.request_permission))
                                }
                            }

                            Manager.ShizukuStatus.Connected -> {
                                ServiceStatus()

                                AppVolumeList(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 16.dp),
                                    apps = manager.apps,
                                    showEmpty = true,
                                    showAll = showAll,
                                    onShowAll = { showAll = true },
                                    content = {
                                        item("system_volume_panel_main") {
                                            SystemVolumePanel(
                                                audioManager = manager.audioManager,
                                                setStreamVolume = manager::setStreamVolume,
                                                notificationManagerProxy = manager.notificationManagerProxy,
                                                showCallVolumeAlways = true,
                                                applyVisibilityFilter = !showAll,
                                                allowVisibilityConfig = showAll,
                                                isSliderVisible = manager::isSystemSliderVisible,
                                                onSliderVisibilityChange = manager::setSystemSliderVisible,
                                            )
                                        }
                                    })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        checkBatteryOptimization()
        ensureAccessibilityServiceRunning()
    }


    data class ErrorInfo(val message: String, val stack: String)

    @SuppressLint("BatteryLife")
    fun openBatterySettings() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", applicationInfo.packageName, null)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    @Composable
    fun ServiceStatus() {
        var errorInfo by remember { mutableStateOf<ErrorInfo?>(null) }

        LaunchedEffect(0) {
            // `grantSelfPermission` waits for a Shizuku process to exit and both calls talk to
            // system services, so none of this can run on the main thread
            errorInfo = withContext(Dispatchers.IO) {
                try {
                    grantSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                } catch (e: Exception) {
                    Log.e(TAG, "Can't add WRITE_SECURE_SETTINGS permission", e)
                    return@withContext ErrorInfo(
                        e.message ?: e.toString(), e.stackTraceToString()
                    )
                }

                try {
                    enableAccessibilityService(
                        ComponentName(this@MainActivity, Service::class.java).flattenToString()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Can't enable accessibility service", e)
                }

                null
            }
        }

        errorInfo?.let { info ->
            val context = LocalContext.current

            AlertDialog(
                onDismissRequest = { errorInfo = null },
                title = { Text(stringResource(R.string.permission_error_title)) },
                text = { Text(info.message) },
                confirmButton = {
                    Button(onClick = { errorInfo = null }) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val clip = ClipData.newPlainText("error_message", info.stack)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(
                            context,
                            context.getString(R.string.copied_to_clipboard),
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Text(stringResource(R.string.copy_full_message))
                    }
                })
        }

        Log.i(TAG, "Manufacturer: ${Build.MANUFACTURER}")

        if (!isIgnoringBatteryOptimization) {
            Button(onClick = { openBatterySettings() }) {
                Text(text = stringResource(R.string.disable_battery_optimization))
            }
        }
    }
}
