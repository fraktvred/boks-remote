package dev.boksremote.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.boksremote.BridgeControl
import dev.boksremote.DevicePanel
import dev.boksremote.protocol.Family
import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.Feature
import dev.boksremote.speaker.SpeakerState
import dev.boksremote.speaker.StereoRole
import dev.boksremote.speaker.TeamUpMode
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** About 5 % of the 0..255 wire range per tap of the volume buttons. */
private const val LEVEL_STEP = 13

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakerScreen(panel: DevicePanel, onBack: () -> Unit) {
    val speaker = panel.device
    val s by speaker.state.collectAsState()
    val bridge by panel.bridge.status.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    // The controller reports failures through the one-shot message; keep the ones the screen itself
    // shows (connection failure, wrong pincode) instead of only flashing them in a snackbar.
    var connectionError by remember { mutableStateOf<String?>(null) }
    var pincodeError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.message) {
        val message = s.message ?: return@LaunchedEffect
        speaker.clearMessage()
        when {
            s.pincodeRequired -> pincodeError = message
            s.connection == ConnectionState.FAILED || s.connection == ConnectionState.DISCONNECTED -> connectionError = message
            else -> snackbar.showSnackbar(message)
        }
    }
    var renaming by remember { mutableStateOf(false) }
    var confirmPowerOff by remember { mutableStateOf(false) }
    val connected = s.connection == ConnectionState.CONNECTED
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(shownName(s.name) ?: s.model?.label ?: s.kind.replaceFirstChar(Char::uppercase), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to device list") } },
                actions = {
                    if (Feature.RENAME in s.features && connected) {
                        IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                    }
                    if (connected) {
                        IconButton(onClick = { confirmPowerOff = true }) { Icon(Icons.Default.PowerSettingsNew, "Power off") }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AnimatedContent(
            // Disconnected and failed share a panel, so switching between them doesn't fade.
            targetState = if (s.connection == ConnectionState.FAILED) ConnectionState.DISCONNECTED else s.connection,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "connection",
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) { shown ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Branch on the panel's own state, not s.connection: a panel fading out would otherwise
                // redraw as the panel for the new state, flashing "Disconnected" right after connecting.
                when (shown) {
                    ConnectionState.CONNECTED -> Controls(s, panel)
                    ConnectionState.CONNECTING -> StatusPanel {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(24.dp))
                        Text("Connecting to ${shownName(s.name) ?: s.model?.label ?: "the ${s.kind}"}…", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text("Keep the phone close to the ${s.kind}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(24.dp))
                        TextButton(onClick = onBack) { Text("Cancel") }
                        if (bridge.running) BridgeReconnectNote(panel.bridge)
                    }
                    else -> StatusPanel {
                        IconBadge(Icons.Default.BluetoothDisabled, size = 88.dp, iconSize = 40.dp, container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(24.dp))
                        Text(
                            if (s.connection == ConnectionState.FAILED) "Couldn't connect" else "Disconnected",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            connectionError ?: "The ${s.kind} may be off or out of range.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { connectionError = null; speaker.connect() }) { Text("Reconnect") }
                        TextButton(onClick = onBack) { Text("Back to device list") }
                        if (bridge.running) BridgeReconnectNote(panel.bridge)
                    }
                }
            }
        }
    }

    if (s.pincodeRequired) {
        PincodeDialog(
            kind = s.kind,
            error = pincodeError,
            onSubmit = { pincodeError = null; speaker.submitPincode(it) },
            onCancel = onBack,
        )
    }
    if (renaming) RenameDialog(s.name.orEmpty(), onDismiss = { renaming = false }) { speaker.setName(it); renaming = false }
    if (confirmPowerOff) {
        AlertDialog(
            onDismissRequest = { confirmPowerOff = false },
            icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null) },
            title = { Text("Power off ${shownName(s.name) ?: "the ${s.kind}"}?") },
            text = {
                Text(
                    if (Feature.RESTART in s.features) "You'll need to switch it on at the ${s.kind} again. Restart turns it back on by itself."
                    else "You'll need to switch it on at the ${s.kind} again.",
                )
            },
            confirmButton = {
                Row {
                    if (Feature.RESTART in s.features) TextButton(onClick = { speaker.restart(); confirmPowerOff = false }) { Text("Restart") }
                    TextButton(onClick = { panel.control.powerOff(); confirmPowerOff = false }) { Text("Power off") }
                }
            },
            dismissButton = { TextButton(onClick = { confirmPowerOff = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Controls(s: SpeakerState, panel: DevicePanel) {
    val speaker = panel.device
    val bridge by panel.bridge.status.collectAsState()
    val stage = panel.stage
    val cycle by stage.cycle.status.collectAsState()
    val sleep by stage.sleep.collectAsState()
    val stageStatus by stage.status.collectAsState()
    val sceneLight by stage.light.collectAsState()
    // The DMX bridge drives every channel of a leased light, so its controls would only fight it.
    val leased = stageStatus.leased && sceneLight === speaker
    val control = panel.control
    DeviceHeader(s, speaker.demo)
    if (s.locked) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    "This ${s.kind} is anti-theft locked. Unlock it in the official SOUNDBOKS app first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
    s.volume?.let { volume ->
        LevelCard(
            "Volume",
            Icons.AutoMirrored.Filled.VolumeUp,
            volume,
            speaker::setVolume,
            stepIcons = Icons.AutoMirrored.Filled.VolumeDown to Icons.AutoMirrored.Filled.VolumeUp,
            zeroLabel = "Muted",
        ) {
            val muted = volume == 0
            IconToggleButton(checked = muted, onCheckedChange = { speaker.toggleMute() }) {
                Icon(
                    if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeMute,
                    contentDescription = if (muted) "Unmute" else "Mute",
                    tint = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val brightness = s.brightness
    if (leased) {
        SectionCard("Light", Icons.Default.Lightbulb) {
            HintText("The DMX bridge has the light. Its colours, brightness, scenes, Cycle and Sleep fade come back when the bridge stops.")
        }
    } else if (brightness != null) {
        // Drawn in the light's colours, or a neutral stand-in while it runs its defaults. Moving the fader takes over from a running fade.
        val colors = s.palette?.takeUnless { s.paletteIsDefault }?.colors ?: DEFAULT_ART
        LightLookCard(brightness, colors, s.energyLevel, control::setBrightness, control::setMode)
    } else {
        s.energyLevel?.let { level ->
            SectionCard("Light mode", Icons.Default.Lightbulb) { LightModePicker(level, control::setMode) }
        }
    }
    if (Feature.LIGHT_CONTENT in s.features) {
        if (!leased) {
            ScenesSection(s, panel) { PaletteSection(s, control, panel.workspace.palettes) }
            SectionCard("Cycle", Icons.Default.Autorenew, trailing = { if (cycle.running) LiveBadge(receiving = true) }) { CycleSection(panel) }
            SectionCard("Sleep fade", Icons.Default.Bedtime, trailing = { if (sleep.running) LiveBadge(receiving = true) }) { SleepSection(panel) }
            SectionCard("Effects", Icons.Default.Whatshot, trailing = { HintText("Hold, slide up to lock") }) { FxPad(speaker) }
        }
        // Folded by default: most sessions never use it, and the status line says enough until they do.
        var dmxExpanded by rememberSaveable { mutableStateOf(false) }
        SectionCard(
            "DMX bridge",
            Icons.Default.SettingsInputAntenna,
            trailing = { if (bridge.running) LiveBadge(receiving = bridge.packetsPerSecond > 0) },
            expanded = dmxExpanded,
            onExpandedChange = { dmxExpanded = it },
        ) {
            if (dmxExpanded) DmxSection(panel.bridge) else DmxSummary(panel.bridge)
        }
    }
    if (Feature.EQ in s.features) {
        SectionCard("Sound profiles & EQ", Icons.Default.Equalizer) { EqSection(s, speaker, panel.workspace.eqPresets) }
        RoomTuning(s, speaker, panel.workspace.eqPresets)
    }
    if (s.teamUp != null || s.stereoRole != null) {
        SectionCard("Team-up", Icons.Default.Groups) {
            s.teamUp?.let { mode ->
                Choice(TeamUpMode.entries, mode, { it.label }, speaker::setTeamUp)
                HintText(
                    when (mode) {
                        TeamUpMode.SOLO -> "Plays on its own."
                        TeamUpMode.HOST -> "Other speakers can join this one and play the same audio."
                        TeamUpMode.JOIN -> "Joins a nearby speaker that is hosting."
                    },
                )
            }
            s.stereoRole?.let { role ->
                ControlLabel("Stereo channel")
                Choice(StereoRole.entries, role, { it.label }, speaker::setStereoRole)
            }
        }
    }
    s.autoOffMinutes?.let { minutes ->
        SectionCard("Auto power-off", Icons.Default.Timer) {
            val options = listOf(0, 15, 30, 60, 120)
            Choice(options, minutes.takeIf { it in options }, ::autoOffLabel, speaker::setAutoOff)
            if (minutes !in options) HintText("Currently ${autoOffLabel(minutes)}, set elsewhere.")
            if (minutes > 0) AutoOffCountdown(minutes, s.idleSince, s.activeInputs)
        }
    }
}

/**
 * The auto power-off timer as a bar that drains, with the time left. The speaker doesn't report its
 * timer, so this counts from when the app last saw no input active ([SpeakerState.idleSince]).
 */
@Composable
private fun AutoOffCountdown(minutes: Int, idleSince: Long?, inputs: List<String>) {
    val totalMs = minutes * 60_000L
    val leftMs by produceState(totalMs, idleSince, totalMs) {
        while (true) {
            value = if (idleSince == null) totalMs else (idleSince + totalMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            delay(250)
        }
    }
    val fraction by animateFloatAsState(leftMs.toFloat() / totalMs, label = "auto-off")
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    HintText(
        when {
            idleSince == null -> "Paused while ${inputs.joinToString(" and ").ifEmpty { "something" }} plays."
            leftMs == 0L -> "Turning off…"
            else -> "Turns off in about ${countdown(leftMs / 1000)} unless something plays."
        },
    )
}

private fun countdown(seconds: Long) = when {
    seconds >= 3600 -> "${seconds / 3600}:${(seconds % 3600 / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    else -> "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun autoOffLabel(minutes: Int) = when {
    minutes == 0 -> "Never"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "$minutes min"
}

/** Who this is and what it is doing; the facts that used to be a single line of text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceHeader(s: SpeakerState, demo: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DeviceBadge(s.family, size = 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.model?.label ?: "SOUNDBOKS device", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (demo) Pill(null, "Demo")
                s.battery?.let { Pill(batteryIcon(it), "$it%") }
                s.activeInputs.forEach { Pill(null, it) }
                s.firmware?.let { Pill(null, "Firmware $it") }
            }
        }
    }
}

private fun batteryIcon(percent: Int): ImageVector = when {
    percent >= 90 -> Icons.Default.BatteryFull
    percent >= 60 -> Icons.Default.Battery6Bar
    percent >= 35 -> Icons.Default.Battery4Bar
    percent >= 10 -> Icons.Default.Battery2Bar
    else -> Icons.Default.Battery0Bar
}

@Composable
private fun Pill(icon: ImageVector?, text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
internal fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** The DMX controls are hidden while the light is away, so the running bridge stays visible and stoppable here. */
@Composable
private fun BridgeReconnectNote(bridge: BridgeControl) {
    Spacer(Modifier.height(32.dp))
    Text("The DMX bridge is still running and reconnects to the light by itself.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = bridge::stop) { Text("Stop bridge") }
}

/**
 * 0..255 wire value shown as a percentage in the card header. Writes stream while dragging; the
 * controller drops stale values. [stepIcons] adds -/+ buttons for small, precise changes;
 * [zeroLabel] replaces "0%" and [action] sits next to the percentage.
 */
@Composable
private fun LevelCard(
    title: String,
    icon: ImageVector,
    value: Int,
    onChange: (Int) -> Unit,
    stepIcons: Pair<ImageVector, ImageVector>? = null,
    zeroLabel: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value / 255f) }
    if (!dragging) local = value / 255f
    fun step(delta: Int) = onChange(((local * 255).roundToInt() + delta).coerceIn(0, 255))

    SectionCard(
        title,
        icon,
        trailing = {
            val percent = (local * 100).roundToInt()
            Text(
                if (percent == 0 && zeroLabel != null) zeroLabel else "$percent%",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            action?.invoke()
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stepIcons != null) {
                FilledTonalIconButton(onClick = { step(-LEVEL_STEP) }) { Icon(stepIcons.first, "$title down") }
                Spacer(Modifier.width(8.dp))
            }
            Slider(
                value = local,
                onValueChange = { dragging = true; local = it; onChange((it * 255).roundToInt()) },
                onValueChangeFinished = { dragging = false },
                modifier = Modifier.weight(1f),
            )
            if (stepIcons != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = { step(LEVEL_STEP) }) { Icon(stepIcons.second, "$title up") }
            }
        }
    }
}

/** What the connected device is called in sentences: the Lightboks, or a speaker. */
private val SpeakerState.kind get() = if (family == Family.LIGHT) "Lightboks" else "speaker"

@Composable
private fun StatusPanel(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 96.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

@Composable
private fun PincodeDialog(kind: String, error: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text("Enter pincode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This $kind is protected by its owner. The pincode is remembered on this phone.")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.trim() },
                    label = { Text("Pincode") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(pin) }, enabled = pin.isNotEmpty()) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(32) },
                label = { Text("Name") },
                supportingText = { Text("${name.length}/32") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank() && name.trim() != current) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
