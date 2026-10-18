package dev.boksremote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.boksremote.speaker.PrefsKeyValue
import dev.boksremote.ui.AppTheme
import dev.boksremote.ui.PermissionScreen
import dev.boksremote.ui.ScanScreen
import dev.boksremote.ui.SpeakerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App(application as BoksApp) } }
    }
}

private val BLE_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

@Composable
private fun App(app: BoksApp) {
    val context = LocalContext.current
    fun granted() = BLE_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var hasPermissions by remember { mutableStateOf(granted()) }
    var askedBefore by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermissions = granted()
        askedBefore = true
    }
    // Permissions granted from system settings don't call back, so re-check on every resume.
    LifecycleResumeEffect(Unit) {
        hasPermissions = granted()
        onPauseOrDispose {}
    }
    if (!hasPermissions) {
        PermissionScreen(
            askedBefore = askedBefore,
            onRequest = { launcher.launch(BLE_PERMISSIONS) },
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            },
        )
        return
    }
    // The list keeps its devices connected for their quick settings only while the app is on screen.
    LifecycleStartEffect(Unit) {
        app.setForeground(true)
        onStopOrDispose { app.setForeground(false) }
    }
    val speaker by app.speaker.collectAsState()
    val demoMode by app.demoMode.collectAsState()
    val current = speaker
    RemotePermissionPrompt(opened = current != null)
    if (current == null) {
        ScanScreen(app, demoMode, onDemoModeChange = app::setDemoMode, onOpen = app::open, onOpenDemo = app::openDemo)
    } else {
        BackHandler { app.close() }
        SpeakerScreen(remember(current) { app.panelOf(current) }, onBack = app::close)
    }
}

/**
 * The first time a device is [opened], asks to post notifications, so its remote shows. Only once:
 * after that the Cycle and DMX bridge buttons still ask, where the notice matters most.
 */
@Composable
private fun RemotePermissionPrompt(opened: Boolean) {
    val context = LocalContext.current
    val prefs = remember { PrefsKeyValue(context, "prompts") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(opened) {
        if (!opened || prefs[REMOTE_PROMPT_KEY] != null) return@LaunchedEffect
        prefs[REMOTE_PROMPT_KEY] = "asked"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private const val REMOTE_PROMPT_KEY = "remote_notifications"
