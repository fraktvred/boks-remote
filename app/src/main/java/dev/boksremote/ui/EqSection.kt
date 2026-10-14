package dev.boksremote.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.boksremote.audio.BandCalibration
import dev.boksremote.audio.BandModels
import dev.boksremote.audio.InputProbe
import dev.boksremote.audio.RoomMeter
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Nightlife
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.boksremote.speaker.EqCurve
import dev.boksremote.speaker.EqMode
import dev.boksremote.speaker.EqPreset
import dev.boksremote.speaker.EqPresetStore
import dev.boksremote.speaker.SoundProfile
import dev.boksremote.speaker.SoundProfiles
import dev.boksremote.speaker.SpeakerController
import dev.boksremote.speaker.SpeakerState
import kotlin.math.abs
import kotlin.math.roundToInt

private fun profileIcon(title: String): ImageVector = when (title) {
    "Bass+" -> Icons.Default.GraphicEq
    "Outdoor" -> Icons.Default.Park
    "Indoor" -> Icons.Default.Home
    "Power" -> Icons.Default.Bolt
    "Stage" -> Icons.Default.Mic
    "Dancefloor" -> Icons.Default.Nightlife
    "Lounge" -> Icons.Default.Weekend
    else -> Icons.Default.Equalizer
}

private fun dbLabel(db: Int) = if (db > 0) "+$db dB" else "$db dB"

/**
 * Built-in profiles, the user's saved curves and "+" for a new one, over a panel for whatever is
 * active. A saved curve is active when the speaker plays exactly its bands; any other user curve
 * is unsaved "Custom". Long-press a saved curve to rename or delete it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EqSection(s: SpeakerState, speaker: SpeakerController, store: EqPresetStore) {
    val saved by store.presets.collectAsState()
    val profiles = SoundProfiles.forModel(s.model)
    val bands = s.eqBands ?: EqCurve.FLAT
    val custom = s.eq == EqMode.USER
    val activeSaved = if (custom) saved.firstOrNull { it.bands == bands } else null
    // The saved curve being edited, so it can be updated in place once it no longer matches.
    var origin by remember { mutableStateOf<EqPreset?>(null) }
    if (activeSaved != null) origin = activeSaved
    if (!custom) origin = null
    var menuFor by remember { mutableStateOf<EqPreset?>(null) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<EqPreset?>(null) }
    var deleting by remember { mutableStateOf<EqPreset?>(null) }
    var tuning by remember { mutableStateOf(false) }

    // Wraps rather than scrolls, so "+" and every saved curve stay in view.
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { p ->
            ProfileTile(p.title, profileIcon(p.title), selected = s.eq == p.mode, onClick = { speaker.setEq(p.mode) })
        }
        saved.forEach { p ->
            Box {
                ProfileTile(
                    p.name,
                    Icons.Default.Tune,
                    selected = activeSaved == p,
                    onClick = { origin = p; speaker.setCustomEq(p.bands) },
                    onLongClick = { menuFor = p },
                )
                DropdownMenu(expanded = menuFor == p, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { renaming = p; menuFor = null })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { deleting = p; menuFor = null })
                }
            }
        }
        ProfileTile(
            "Custom",
            Icons.Default.Add,
            selected = custom && activeSaved == null,
            onClick = {
                if (custom && activeSaved == null) return@ProfileTile
                origin = null
                // Pick up an unsaved curve the speaker still holds; otherwise start flat.
                speaker.setCustomEq(s.eqBands?.takeIf { b -> saved.none { it.bands == b } } ?: EqCurve.FLAT)
            },
        )
    }

    if (custom) {
        val editing = origin?.takeIf { activeSaved == null }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(activeSaved?.name ?: editing?.let { "${it.name} · edited" } ?: "Custom EQ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { speaker.setCustomEq(EqCurve.FLAT) }, enabled = bands != EqCurve.FLAT) { Text("Reset") }
        }
        EqGraph(bands, onChange = speaker::setCustomEq)
        when {
            editing != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { origin = store.update(editing, bands) }, modifier = Modifier.weight(1f)) {
                    Text("Update ${editing.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { naming = true }, modifier = Modifier.weight(1f)) { Text("Save as new") }
            }
            activeSaved == null -> Button(onClick = { naming = true }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            else -> HintText("Drag the points to change it. Long-press its tile to rename or delete it.")
        }
    } else {
        profiles.firstOrNull { it.mode == s.eq }?.let { ProfileDetails(it) }
            ?: HintText("Pick a sound profile, or tap Custom to shape your own.")
    }

    BandModels.forModel(s.model)?.let { shapes ->
        TextButton(onClick = { tuning = true }) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Tune to this room")
        }
        if (tuning) RoomCorrectionSheet(s, speaker, store, shapes, onDismiss = { tuning = false })
    }
    DebugTool("Calibrate bands (debug)", speaker, BandCalibration::run)
    DebugTool("Probe AUX input (debug)", speaker, InputProbe::run)

    if (naming) {
        PaletteNameDialog("Name your custom EQ", initial = "", confirm = "Save", placeholder = "My EQ", onDismiss = { naming = false }) {
            origin = store.add(it, bands)
            naming = false
        }
    }
    renaming?.let { p ->
        PaletteNameDialog("Rename custom EQ", p.name, "Rename", placeholder = "My EQ", onDismiss = { renaming = null }) {
            val renamed = store.rename(p, it)
            if (origin == p) origin = renamed
            renaming = null
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("It is removed from this phone. The speaker keeps playing it until you pick another profile.") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(p)
                    if (origin == p) origin = null
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileTile(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {
                    if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                },
                onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
                onLongClickLabel = onLongClick?.let { "Rename or delete" },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) colors.primary else colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.onPrimary else colors.onSurface, modifier = Modifier.size(28.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
    }
}

/** Description, the official app's bass/mid/treble character bars and its battery estimate. */
@Composable
private fun ProfileDetails(p: SoundProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        p.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        listOf("Bass" to p.bass, "Mid" to p.mid, "Treble" to p.treble).forEach { (label, value) ->
            if (value != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                }
            }
        }
        p.batteryHours?.let { hours ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Battery5Bar, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                HintText("Battery at max volume: ~$hours hrs")
            }
        }
    }
}

/**
 * The six-band curve, bass on the left. Touch anywhere to grab the nearest band and drag it up or
 * down; every whole-dB change is sent straight to the speaker so it can be heard while editing.
 * Without [onChange] it only shows the curve.
 */
@Composable
internal fun EqGraph(bands: List<Int>, onChange: ((List<Int>) -> Unit)?) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(bands)
    val change by rememberUpdatedState(onChange ?: {})
    var active by remember { mutableStateOf<Int?>(null) }
    val colors = MaterialTheme.colorScheme
    val axisWidth = 44.dp
    val span = (EqCurve.MAX_DB - EqCurve.MIN_DB).toFloat()

    Column {
        Row(Modifier.fillMaxWidth().height(240.dp)) {
            // Labels sit on the +10, 0 and -10 grid lines, which the canvas insets by 12 dp.
            Column(Modifier.width(axisWidth).fillMaxHeight().padding(vertical = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("+10 dB", "0", "−10 dB").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(240.dp)
                    .semantics { contentDescription = "EQ curve: " + current.mapIndexed { i, db -> "band ${i + 1} ${dbLabel(db)}" }.joinToString(", ") }
                    .then(if (onChange == null) Modifier else Modifier.pointerInput(Unit) {
                        val pad = 12.dp.toPx()
                        fun xOf(i: Int) = pad + i * (size.width - 2 * pad) / (EqCurve.BANDS - 1)
                        fun dbAt(y: Float) = (EqCurve.MAX_DB - (y - pad) / (size.height - 2 * pad) * span).roundToInt().coerceIn(EqCurve.MIN_DB, EqCurve.MAX_DB)
                        awaitEachGesture {
                            val down = awaitFirstDown(pass = PointerEventPass.Initial)
                            val band = (0 until EqCurve.BANDS).minBy { abs(xOf(it) - down.position.x) }
                            active = band
                            fun set(y: Float) {
                                val db = dbAt(y)
                                if (current[band] != db) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    change(current.toMutableList().also { it[band] = db })
                                }
                            }
                            down.consume()
                            set(down.position.y)
                            // Claim moves in the Initial pass, before the screen's vertical scroll can take the drag.
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                c.consume()
                                set(c.position.y)
                            }
                            active = null
                        }
                    }),
            ) {
                val pad = 12.dp.toPx()
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad
                fun x(i: Int) = pad + i * w / (EqCurve.BANDS - 1)
                fun y(db: Int) = pad + (EqCurve.MAX_DB - db) / span * h
                val zero = y(0)

                for (db in EqCurve.MIN_DB..EqCurve.MAX_DB step 5) {
                    drawLine(colors.outlineVariant.copy(alpha = if (db == 0) 0.9f else 0.35f), Offset(pad, y(db)), Offset(pad + w, y(db)), 1.dp.toPx())
                }
                for (i in 0 until EqCurve.BANDS) drawLine(colors.outlineVariant.copy(alpha = 0.35f), Offset(x(i), pad), Offset(x(i), pad + h), 1.dp.toPx())

                // Each segment eases out of one point and into the next, so equal neighbours stay flat.
                val curve = Path().apply {
                    moveTo(x(0), y(bands[0]))
                    for (i in 1 until EqCurve.BANDS) {
                        val mid = (x(i - 1) + x(i)) / 2
                        cubicTo(mid, y(bands[i - 1]), mid, y(bands[i]), x(i), y(bands[i]))
                    }
                }
                val fill = Path().apply {
                    addPath(curve)
                    lineTo(x(EqCurve.BANDS - 1), zero)
                    lineTo(x(0), zero)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.25f), colors.primary.copy(alpha = 0.05f), colors.primary.copy(alpha = 0.25f))))
                drawPath(curve, colors.primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                bands.forEachIndexed { i, db ->
                    val c = Offset(x(i), y(db))
                    if (active == i) drawCircle(colors.primary.copy(alpha = 0.25f), 20.dp.toPx(), c)
                    drawCircle(colors.onSurface, 9.dp.toPx(), c)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = axisWidth)) {
            Text("Bass", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(active?.let { dbLabel(bands[it]) } ?: "Mid", style = MaterialTheme.typography.labelMedium, color = if (active != null) colors.primary else colors.onSurfaceVariant)
            Text("Treble", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

/** Debug builds only: runs a measurement tool against the connected speaker, showing its progress. */
@Composable
private fun DebugTool(
    label: String,
    speaker: SpeakerController,
    tool: suspend (SpeakerController, RoomMeter, (String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    TextButton(
        enabled = !running,
        onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                progress = "Failed: grant microphone permission"
                return@TextButton
            }
            running = true
            scope.launch {
                progress = try {
                    tool(speaker, RoomMeter(context)) { progress = it }
                    "Done"
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    "Failed: ${e.message}"
                } finally {
                    running = false
                }
            }
        },
    ) { Text(progress?.let { "${label.removeSuffix(" (debug)")}: $it" } ?: label) }
}
