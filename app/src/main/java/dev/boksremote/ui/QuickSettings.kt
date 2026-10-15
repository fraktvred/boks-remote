package dev.boksremote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.boksremote.BridgeControl
import dev.boksremote.DevicePanel
import dev.boksremote.protocol.Family
import dev.boksremote.speaker.EqCurve
import dev.boksremote.speaker.EqMode
import dev.boksremote.speaker.EqPreset
import dev.boksremote.speaker.HsvColor
import dev.boksremote.speaker.SoundProfiles

/**
 * What the device list shows under a device: the few things worth changing without opening it, or
 * how its connection is going. [onOpen] opens the device, for what only its screen does (the pincode).
 */
@Composable
internal fun QuickSettings(panel: DevicePanel, onOpen: () -> Unit) {
    val device = panel.device
    val view = rememberDeviceView(panel)
    val s = view.state
    // Before the connection: the bridge reconnects its light by itself and says so.
    if (view.leased) {
        BridgeQuickStatus(panel.bridge)
        return
    }
    when (view.standIn) {
        StandIn.CONNECTING -> QuickStatus("Connecting…", leading = {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        })
        StandIn.FAILED, StandIn.DISCONNECTED -> QuickStatus(if (view.standIn == StandIn.FAILED) "Couldn't connect" else "Disconnected") {
            TextButton(onClick = device::connect) { Text("Try again") }
        }
        StandIn.PINCODE -> QuickStatus("Locked with a pincode", leading = { StatusIcon(Icons.Default.Lock) }) {
            TextButton(onClick = onOpen) { Text("Unlock") }
        }
        StandIn.LOCKED -> QuickStatus("Anti-theft locked. Unlock it in the official SOUNDBOKS app.", leading = { StatusIcon(Icons.Default.Lock) })
        null -> if (s.family == Family.LIGHT) LightQuickSettings(view, panel) else SpeakerQuickSettings(view, panel, onOpen)
    }
}

/** In place of a bridged light's controls: what the bridge is doing, and a way to stop it. */
@Composable
private fun BridgeQuickStatus(bridge: BridgeControl) {
    val status by bridge.status.collectAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveBadge(receiving = status.packetsPerSecond > 0)
        Spacer(Modifier.width(Spacing.s))
        Column(Modifier.weight(1f)) {
            Text("DMX bridge running", style = MaterialTheme.typography.titleSmall)
            DmxSummary(bridge)
        }
        TextButton(onClick = bridge::stop) { Text("Stop") }
    }
}

/** One line in place of the controls, with what can be done about it at the end. */
@Composable
private fun QuickStatus(text: String, leading: (@Composable () -> Unit)? = null, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Spacing.s))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun StatusIcon(icon: ImageVector) = Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))

@Composable
private fun LightQuickSettings(view: DeviceView, panel: DevicePanel) {
    val s = view.state
    // The scene light goes through its Stage, which also ends a Sleep fade; another light is written to directly.
    val control = panel.control
    val pages = remember { PalettePagesState() }
    val colors = s.shownPalette?.colors ?: DEFAULT_ART
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        s.brightness?.let { brightness ->
            LevelRow(
                brightness,
                onChange = control::setBrightness,
                leading = { Icon(Icons.Default.BrightnessMedium, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.m)) },
            ) { value, onChange, onSettled -> PaletteSlider(value, colors.map { it.compose() }, onChange, onSettled, Modifier.weight(1f)) }
        }
        s.energyLevel?.let { level ->
            LightModePicker(level, control::setMode, compact = true)
        }
        // Cycle plays on the scene light; its countdown rides on the chip.
        if (view.lightContent) PaletteChip(s.paletteName, colors, view.cycleNextAt) { pages.browsing = true }
    }
    PalettePages(pages, s, control, panel.workspace.palettes)
}

/**
 * The palette on the light as a strip of its own colours with its name on it, which opens the
 * palettes. The one place on the list in the light's colours besides the brightness it fills.
 */
@Composable
private fun PaletteChip(name: String, colors: List<HsvColor>, nextAt: Long?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Corners.large)
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .clickable(onClickLabel = "Change palette", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Palette: $name" },
    ) {
        PaletteArt(colors, Modifier.fillMaxSize())
        // Dark chips keep the name and chevron readable over a light palette.
        val scrim = Scrim.chip
        Row(Modifier.fillMaxSize().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Surface(shape = RoundedCornerShape(Corners.small), color = scrim) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    )
                }
            }
            Spacer(Modifier.width(Spacing.s))
            if (nextAt != null) {
                Surface(shape = RoundedCornerShape(50), color = scrim) {
                    Box(Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs)) { Countdown(nextAt, tint = Color.White) }
                }
                Spacer(Modifier.width(Spacing.s))
            }
            Surface(shape = CircleShape, color = scrim, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White, modifier = Modifier.padding(Spacing.xs))
            }
        }
    }
}

@Composable
private fun SpeakerQuickSettings(view: DeviceView, panel: DevicePanel, onOpen: () -> Unit) {
    val s = view.state
    val speaker = panel.device
    var choosing by remember { mutableStateOf(false) }
    val presets by panel.workspace.eqPresets.presets.collectAsState()
    val bands = s.eqBands ?: EqCurve.FLAT
    val custom = s.eq == EqMode.USER
    val activePreset = activePreset(s, presets)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        s.volume?.let { volume ->
            LevelRow(
                volume,
                onChange = speaker::setVolume,
                zeroLabel = "Muted",
                leading = { MuteToggle(volume, speaker::toggleMute) },
            ) { value, onChange, onSettled ->
                Slider(value, onChange, Modifier.weight(1f).semantics { contentDescription = "Volume" }, onValueChangeFinished = onSettled)
            }
        }
        if (view.eq) {
            val profiles = SoundProfiles.forModel(s.model)
            // The built-in profiles, then one tile for the custom curves: named after the one playing, it opens them all.
            val options = profiles.map { it.mode } + EqMode.USER
            TileChoice(
                options,
                s.eq,
                label = { mode -> if (mode == EqMode.USER) activePreset?.name ?: "Custom" else profiles.first { it.mode == mode }.title },
                icon = { mode -> if (mode == EqMode.USER) Icons.Default.Tune else profileIcon(profiles.first { it.mode == mode }.title) },
                onSelect = { mode -> if (mode == EqMode.USER) choosing = true else speaker.setEq(mode) },
                compact = true,
            )
        }
    }
    if (choosing) {
        CustomEqSheet(
            presets = presets,
            playing = if (custom) bands else null,
            active = activePreset,
            onSelect = { speaker.setCustomEq(it.bands); choosing = false },
            onOpen = { choosing = false; onOpen() },
            onDismiss = { choosing = false },
        )
    }
}

/**
 * The user's saved curves to pick from. While the speaker plays a curve that isn't saved, it heads
 * the list as the one playing, so the sheet always shows what is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomEqSheet(
    presets: List<EqPreset>,
    playing: List<Int>?,
    active: EqPreset?,
    onSelect: (EqPreset) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.sheetTopMargin()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.l)) {
            Text("Your EQs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.xl))
            Spacer(Modifier.height(Spacing.s))
            Column(Modifier.selectableGroup()) {
                if (playing != null && active == null) EqRow("Unsaved curve", playing, selected = true, onClick = onDismiss)
                presets.forEach { p -> EqRow(p.name, p.bands, selected = p == active, onClick = { onSelect(p) }) }
            }
            if (presets.isEmpty()) {
                Text(
                    "You haven't saved a custom EQ yet. Shape one on the speaker's page and save it to Your EQs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
                )
            }
            TextButton(onClick = onOpen, modifier = Modifier.padding(horizontal = Spacing.m)) { Text("Open the EQ editor") }
        }
    }
}

@Composable
private fun EqRow(name: String, bands: List<Int>, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EqSparkline(bands, tint, Modifier.size(width = 40.dp, height = 24.dp))
        Spacer(Modifier.width(Spacing.l))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Default.Check, contentDescription = "Playing", tint = colors.primary)
    }
}

/**
 * A 0..255 level as [slider] between [leading] and its percentage. The slider follows the thumb while
 * dragging and the device otherwise; writes stream while dragging and the controller drops stale ones.
 */
@Composable
private fun LevelRow(
    value: Int,
    onChange: (Int) -> Unit,
    zeroLabel: String? = null,
    leading: @Composable () -> Unit,
    slider: @Composable RowScope.(Float, (Float) -> Unit, () -> Unit) -> Unit,
) {
    val level = rememberLevelDrag(value, onChange)
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading()
        slider(level.fraction, level::drag, level::settle)
        Text(
            level.label(zeroLabel),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            // Wide enough for "100%" and "Muted", so the slider doesn't jump as the number changes.
            modifier = Modifier.width(52.dp),
        )
    }
}
