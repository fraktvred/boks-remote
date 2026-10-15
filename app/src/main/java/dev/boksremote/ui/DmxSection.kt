package dev.boksremote.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.boksremote.BridgeControl
import dev.boksremote.dmx.DmxConfig
import dev.boksremote.dmx.DmxProtocol
import dev.boksremote.dmx.DmxStatus
import dev.boksremote.dmx.LightboksPersonality

@Composable
fun DmxSection(bridge: BridgeControl) {
    val status by bridge.status.collectAsState()
    val config by bridge.config.collectAsState()
    var showMap by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bridge.start() }

    HintText("Receive DMX over Wi-Fi from a lighting desk or software and drive this Lightboks. Keeps running with the screen off.")
    // Status first while running: it says where to send, which is what the user needs at that moment.
    // Outside a Column scope the default grows the panel out of its top-left corner; it opens downward instead.
    AnimatedVisibility(visible = status.running, enter = OPEN, exit = CLOSE) { BridgeStatusCard(status, config) }
    ConfigEditor(config, onChange = bridge::update)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status.running) {
            Button(
                onClick = bridge::stop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Stop bridge")
            }
        } else {
            // Ask for notification permission once so the "bridge running" notice is visible; start either way.
            Button(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Start bridge")
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { showMap = !showMap }) {
            Text("Channel map")
            Icon(if (showMap) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
    }
    AnimatedVisibility(visible = showMap, enter = OPEN, exit = CLOSE) { ChannelMap(config) }
}

/** The folded DMX card: whether the bridge runs and what it is doing, in a line or two. */
@Composable
fun DmxSummary(bridge: BridgeControl) {
    val status by bridge.status.collectAsState()
    val config by bridge.config.collectAsState()
    val target = "${config.protocol.label} universe ${config.universe}"
    if (!status.running) {
        HintText("Off · $target")
        return
    }
    Text(
        when {
            status.packetsPerSecond > 0 -> "Receiving $target from ${status.source}"
            status.addresses.isEmpty() -> "Running · no Wi-Fi or hotspot connection"
            else -> "Waiting for $target on ${status.addresses.first()}"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    val problem = listOfNotNull(
        "Light disconnected, reconnecting…".takeIf { status.reconnecting },
        status.networkError,
        status.lightError?.let { "Light: $it" },
    ).firstOrNull()
    if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** Header badge for the DMX card: a pulsing dot while packets arrive, a steady one while the bridge waits. */
@Composable
internal fun LiveBadge(receiving: Boolean) {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    val color = if (receiving) Color(0xFF2E7D32) else MaterialTheme.colorScheme.tertiary
    Surface(shape = CircleShape, color = color.copy(alpha = 0.15f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).alpha(if (receiving) pulse else 1f).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(if (receiving) "Live" else "Waiting", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun BridgeStatusCard(status: DmxStatus, config: DmxConfig) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val target = "${config.protocol.label} universe ${config.universe}"
            if (status.addresses.isEmpty()) {
                Text("No Wi-Fi or hotspot connection", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                HintText("Join the lighting network, or turn on this phone's hotspot and connect the desk to it.")
            } else {
                HintText("Send $target to")
                status.addresses.forEach {
                    Text(it, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                }
                if (config.protocol == DmxProtocol.ARTNET) {
                    HintText("Art-Net desks also find this phone by polling, as \"Boks Remote\".")
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(
                when {
                    status.packetsPerSecond > 0 -> "Receiving ${status.packetsPerSecond} packets/s from ${status.source}"
                    else -> "Waiting for DMX…"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            status.look?.let { look ->
                HintText("Dimmer ${look.dimmer} · ${look.mode.label}${look.fx?.let { " · ${it.label}" } ?: ""}")
            }
            if (status.reconnecting) Text("Light disconnected, reconnecting…", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            listOfNotNull(status.networkError, status.lightError?.let { "Light: $it" }).forEach {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConfigEditor(config: DmxConfig, onChange: (DmxConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ControlLabel("Protocol")
        Choice(DmxProtocol.entries, config.protocol, { it.label }) {
            // Each protocol counts universes differently; jump to its first universe when switching.
            onChange(config.copy(protocol = it, universe = it.firstUniverse))
        }
        ControlLabel("Personality")
        Choice(LightboksPersonality.entries, config.personality, { it.label }) { onChange(config.copy(personality = it)) }
        Spacer(Modifier.size(4.dp))
        Row {
            NumberField("Universe", config.universe, config.protocol.universes, Modifier.weight(1f)) {
                onChange(config.copy(universe = it))
            }
            Spacer(Modifier.width(12.dp))
            NumberField("Start address", config.startAddress, 1..(513 - config.personality.footprint), Modifier.weight(1f)) {
                onChange(config.copy(startAddress = it))
            }
        }
    }
}

/** Invalid input stays in the field (marked, with the allowed range) and is never sent to the bridge. */
@Composable
private fun NumberField(label: String, value: Int, range: IntRange, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val invalid = text.toIntOrNull()?.let { it !in range } ?: true
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter(Char::isDigit).take(5)
            text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text(label) },
        isError = invalid,
        supportingText = { Text("${range.first}–${range.last}") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ChannelMap(config: DmxConfig) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            config.personality.channels().forEachIndexed { i, name ->
                Text("${config.startAddress + i}".padStart(3) + "  $name", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.size(8.dp))
            HintText("Mode: 0–63 Chill · 64–127 Dance · 128–191 Rave · 192–255 Uplight (static colour)")
            if (config.personality == LightboksPersonality.FULL) {
                HintText("FX: 0–31 off · 32–87 Strobe · 88–143 Blackout · 144–199 Chase · 200–255 Sparkle")
            }
            HintText(
                "Colours are sent once they hold for 150 ms (max 4/s) to spare the light's flash memory, so fades jump to their end colour. Dimmer, mode and FX follow as fast as Bluetooth allows.",
            )
        }
    }
}

/** Panels in the section open and close downward, across their full width. */
private val OPEN = fadeIn() + expandVertically()
private val CLOSE = fadeOut() + shrinkVertically()
