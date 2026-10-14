package dev.boksremote.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.boksremote.ble.Scanner
import dev.boksremote.protocol.Advertisement
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(scanner: Scanner, onOpen: (Advertisement) -> Unit) {
    val results by scanner.results.collectAsState()
    val scanning by scanner.scanning.collectAsState()
    // Scanner.bluetoothEnabled is a plain getter, so bump this to re-read it after the enable prompt returns.
    var bluetoothCheck by remember { mutableIntStateOf(0) }
    val bluetoothOn = remember(bluetoothCheck, scanning) { scanner.bluetoothEnabled }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothCheck++
        scanner.start()
    }
    DisposableEffect(Unit) {
        scanner.start()
        onDispose { scanner.stop() }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pullState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Boks Remote") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        // First-seen order: sorting by live RSSI made rows jump under the user's finger.
        val list = results.values.toList()
        PullToRefreshBox(
            isRefreshing = false,
            // The scan shows its own progress, so the pull indicator only acknowledges the pull. It
            // never sees isRefreshing change, which is what normally sends it away, so hide it here.
            onRefresh = { scanner.start(); scope.launch { pullState.animateToHidden() } },
            state = pullState,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                !bluetoothOn -> EmptyState(
                    icon = Icons.Default.BluetoothDisabled,
                    title = "Bluetooth is off",
                    body = "Turn on Bluetooth to find speakers and lights nearby.",
                    action = "Turn on Bluetooth",
                    onAction = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                )
                list.isEmpty() && scanning -> EmptyState(
                    icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                    title = "Looking for speakers and lights…",
                    body = "Make sure your SOUNDBOKS or Lightboks is switched on and close by.",
                    searching = true,
                )
                list.isEmpty() -> EmptyState(
                    icon = Icons.Default.Speaker,
                    title = "No speakers or lights found",
                    body = "Check that your speaker or Lightboks is on, then scan again.",
                    action = "Scan again",
                    onAction = scanner::start,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "header") { ScanHeader(scanning, onScan = scanner::start) }
                    items(list, key = { it.address }) { ad ->
                        DeviceRow(ad, Modifier.animateItem()) { onOpen(ad) }
                    }
                    if (!scanning) {
                        item(key = "hint") {
                            Text(
                                "Don't see yours? Switch it on, bring the phone closer and scan again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Nearby" over the list, with the scan's state where the old full-width progress bar was. */
@Composable
private fun ScanHeader(scanning: Boolean, onScan: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Nearby", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (scanning) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            Spacer(Modifier.width(8.dp))
            Text("Scanning", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
        } else {
            TextButton(onClick = onScan) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan again")
            }
        }
    }
}

@Composable
private fun DeviceRow(ad: Advertisement, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceBadge(ad.family, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(shownName(ad.name) ?: ad.model?.label ?: "SOUNDBOKS device", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(ad.model?.label?.takeIf { it != ad.name }, ad.firmware?.let { "Firmware $it" })
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (ad.security?.pincodeEnabled == true || ad.security?.locked == true) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = if (ad.security.locked) "Anti-theft locked" else "Pincode protected",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                )
            }
            Icon(
                signalIcon(ad.rssi),
                contentDescription = "Signal ${ad.rssi} dBm",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

/** Rough BLE bands: above -65 dBm is next to the phone, below -85 the link gets unreliable. */
private fun signalIcon(rssi: Int): ImageVector = when {
    rssi > -65 -> Icons.Default.SignalCellularAlt
    rssi > -85 -> Icons.Default.SignalCellularAlt2Bar
    else -> Icons.Default.SignalCellularAlt1Bar
}

/** Scrollable so pull-to-refresh also works on the empty states. While [searching], rings pulse out of the icon. */
@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String, action: String? = null, onAction: () -> Unit = {}, searching: Boolean = false) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
            if (searching) Pulse(Modifier.matchParentSize())
            IconBadge(icon, size = 88.dp, iconSize = 40.dp, container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/** Two rings that grow from the 88 dp badge to the edge and fade, half a cycle apart. */
@Composable
private fun Pulse(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val progress by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "pulse",
    )
    Canvas(modifier) {
        val inner = 44.dp.toPx()
        val outer = size.minDimension / 2
        for (k in 0..1) {
            val f = (progress + k * 0.5f) % 1f
            drawCircle(color.copy(alpha = 0.3f * (1 - f)), radius = inner + (outer - inner) * f)
        }
    }
}

/**
 * After the user denies twice Android stops showing the prompt and the launcher returns at once,
 * so from the second ask on the screen also offers the app's system settings.
 */
@Composable
fun PermissionScreen(askedBefore: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            IconBadge(Icons.Default.Bluetooth, size = 88.dp, iconSize = 40.dp)
            Spacer(Modifier.height(24.dp))
            Text("Find your devices", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Boks Remote controls SOUNDBOKS speakers and the Lightboks over Bluetooth. Allow Nearby devices so it can find them. " +
                    "No account or location access is needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("Allow Nearby devices") }
            if (askedBefore) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenSettings) { Text("Open app settings") }
            }
        }
    }
}
