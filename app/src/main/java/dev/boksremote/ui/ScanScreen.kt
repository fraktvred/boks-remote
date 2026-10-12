package dev.boksremote.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.boksremote.ble.Scanner
import dev.boksremote.protocol.Advertisement
import dev.boksremote.protocol.Family

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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Boks Remote") },
                actions = {
                    if (bluetoothOn) {
                        IconButton(onClick = { if (scanning) scanner.stop() else scanner.start() }) {
                            Icon(
                                if (scanning) Icons.Default.Stop else Icons.Default.Refresh,
                                contentDescription = if (scanning) "Stop scanning" else "Scan again",
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Reserve the bar's height so the list doesn't shift when scanning starts or stops.
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            // First-seen order: sorting by live RSSI made rows jump under the user's finger.
            val list = results.values.toList()
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { scanner.stop(); scanner.start() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    !bluetoothOn -> EmptyState(
                        icon = Icons.Default.BluetoothDisabled,
                        title = "Bluetooth is off",
                        body = "Turn on Bluetooth to find speakers nearby.",
                        action = "Turn on Bluetooth",
                        onAction = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    )
                    list.isEmpty() && scanning -> EmptyState(
                        icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                        title = "Looking for speakers…",
                        body = "Make sure your SOUNDBOKS or Lightboks is switched on and close by.",
                    )
                    list.isEmpty() -> EmptyState(
                        icon = Icons.Default.Speaker,
                        title = "No speakers found",
                        body = "Check that the speaker is on, then scan again.",
                        action = "Scan again",
                        onAction = scanner::start,
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(list, key = { it.address }) { ad ->
                            SpeakerRow(ad, Modifier.animateItem()) { onOpen(ad) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeakerRow(ad: Advertisement, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (ad.family == Family.LIGHT) Icons.Default.Lightbulb else Icons.Default.Speaker)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(ad.name ?: ad.model?.label ?: "SOUNDBOKS device", style = MaterialTheme.typography.titleMedium)
                val details = listOfNotNull(ad.model?.label?.takeIf { it != ad.name }, ad.firmware?.let { "Firmware $it" })
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (ad.security?.pincodeEnabled == true || ad.security?.locked == true) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = if (ad.security.locked) "Anti-theft locked" else "Pincode protected",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                )
            }
            Icon(
                signalIcon(ad.rssi),
                contentDescription = "Signal ${ad.rssi} dBm",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Rough BLE bands: above -65 dBm is next to the phone, below -85 the link gets unreliable. */
private fun signalIcon(rssi: Int): ImageVector = when {
    rssi > -65 -> Icons.Default.SignalCellularAlt
    rssi > -85 -> Icons.Default.SignalCellularAlt2Bar
    else -> Icons.Default.SignalCellularAlt1Bar
}

/** Scrollable so pull-to-refresh also works on the empty states. */
@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        IconBadge(icon, size = 88.dp, iconSize = 40.dp, container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) { Text(action) }
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
            Text("Find your speaker", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Boks Remote controls your speaker over Bluetooth. Allow Nearby devices so it can find it. " +
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
