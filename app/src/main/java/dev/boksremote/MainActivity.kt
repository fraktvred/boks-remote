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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
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
    val speaker by app.speaker.collectAsState()
    val demoMode by app.demoMode.collectAsState()
    val current = speaker
    if (current == null) {
        ScanScreen(app.scanner, demoMode, onDemoModeChange = app::setDemoMode, onOpen = app::open, onOpenDemo = app::openDemo)
    } else {
        BackHandler { app.close() }
        SpeakerScreen(current, app, onBack = app::close)
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}
