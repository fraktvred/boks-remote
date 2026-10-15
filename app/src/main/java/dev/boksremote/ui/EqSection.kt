package dev.boksremote.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.boksremote.speaker.PaletteCodec
import dev.boksremote.audio.BandCalibration
import dev.boksremote.audio.BandModels
import dev.boksremote.audio.InputProbe
import dev.boksremote.audio.RoomMeter
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight

internal fun profileIcon(title: String): ImageVector = when (title) {
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
 * The built-in profiles and Custom as one row of four modes, the user's saved curves and "New EQ"
 * as a grid below, then a panel for whatever is active. A saved curve is active when the speaker
 * plays exactly its bands; any other user curve is unsaved and selects "New EQ". The active saved
 * curve is renamed or deleted from the editor's menu, any saved curve by long-pressing its card.
 */
@Composable
internal fun EqSection(s: SpeakerState, speaker: SpeakerController, store: EqPresetStore) {
    val saved by store.presets.collectAsState()
    val profiles = SoundProfiles.forModel(s.model)
    val bands = s.eqBands ?: EqCurve.FLAT
    val custom = s.eq == EqMode.USER
    val activeSaved = activePreset(s, saved)
    // The saved curve being edited, so it can be updated in place once it no longer matches.
    var origin by remember { mutableStateOf<EqPreset?>(null) }
    if (activeSaved != null) origin = activeSaved
    if (!custom) origin = null
    var menuFor by remember { mutableStateOf<EqPreset?>(null) }
    var editorMenu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<EqPreset?>(null) }
    var deleting by remember { mutableStateOf<EqPreset?>(null) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        profiles.forEach { p ->
            ProfileTile(p.title, profileIcon(p.title), selected = s.eq == p.mode, onClick = { speaker.setEq(p.mode) }, Modifier.weight(1f))
        }
        // Back to whatever curve the speaker still holds, saved or not; a new one starts from Reset.
        ProfileTile("Custom", Icons.Default.Tune, selected = custom, onClick = { if (!custom) speaker.setCustomEq(bands) }, Modifier.weight(1f))
    }

    // A fixed two-column grid with "New EQ" last, so cards never reflow when the selection changes.
    ControlLabel("Your EQs")
    val newSelected = custom && activeSaved == null && origin == null
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        (saved + null).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                row.forEach { p ->
                    Box(Modifier.weight(1f)) {
                        if (p == null) {
                            NewEqCard(selected = newSelected, onClick = { origin = null; speaker.setCustomEq(EqCurve.FLAT) })
                        } else {
                            EqCard(p.name, selected = activeSaved == p, onClick = { origin = p; speaker.setCustomEq(p.bands) }, onLongClick = { menuFor = p }) {
                                EqSparkline(p.bands, it, Modifier.fillMaxSize())
                            }
                            PresetMenu(expanded = menuFor == p, onDismiss = { menuFor = null }, onRename = { renaming = p }, onDelete = { deleting = p })
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    if (custom) {
        val editing = origin?.takeIf { activeSaved == null }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(activeSaved?.name ?: editing?.let { "${it.name} · edited" } ?: "New EQ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (editing != null) {
                TextButton(onClick = { speaker.setCustomEq(editing.bands) }) { Text("Revert") }
            } else {
                TextButton(onClick = { speaker.setCustomEq(EqCurve.FLAT) }, enabled = bands != EqCurve.FLAT) { Text("Reset") }
            }
            activeSaved?.let { p ->
                Box {
                    IconButton(onClick = { editorMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More for ${p.name}") }
                    PresetMenu(expanded = editorMenu, onDismiss = { editorMenu = false }, onRename = { renaming = p }, onDelete = { deleting = p })
                }
            }
        }
        EqGraph(bands, onChange = speaker::setCustomEq)
        when {
            editing != null -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { origin = store.update(editing, bands) }, modifier = Modifier.weight(1f)) {
                    Text("Update ${editing.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { naming = true }, modifier = Modifier.weight(1f)) { Text("Save as new") }
            }
            activeSaved != null -> Unit
            bands == EqCurve.FLAT -> HintText("Drag a point up or down to shape the sound. The speaker follows as you drag.")
            else -> Button(onClick = { naming = true }, modifier = Modifier.fillMaxWidth()) { Text("Save to Your EQs") }
        }
    } else {
        profiles.firstOrNull { it.mode == s.eq }?.let { ProfileDetails(it) }
            ?: HintText("Pick a sound profile, or tap New EQ to shape your own.")
    }


    if (naming) {
        NameDialog("Name your custom EQ", initial = "", confirm = "Save", maxLength = PaletteCodec.MAX_NAME, placeholder = "My EQ", onDismiss = { naming = false }) {
            origin = store.add(it, bands)
            naming = false
        }
    }
    renaming?.let { p ->
        NameDialog("Rename custom EQ", p.name, "Rename", PaletteCodec.MAX_NAME, placeholder = "My EQ", onDismiss = { renaming = null }) {
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

@Composable
private fun PresetMenu(expanded: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { onRename(); onDismiss() })
        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { onDelete(); onDismiss() })
    }
}

@Composable
private fun ProfileTile(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(Corners.medium))
            .selectable(selected = selected, role = Role.RadioButton) {
                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(Corners.medium))
                .background(if (selected) colors.primary else tileContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.onPrimary else colors.onSurface, modifier = Modifier.size(26.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.s, bottom = 2.dp),
        )
    }
}

/**
 * One cell of the Your EQs grid: [art] (drawn in the content colour) beside a one-line name, at a
 * fixed height so the grid stays even. Selection only changes colours, never the size.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EqCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    outlined: Boolean = false,
    art: @Composable (Color) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Corners.medium)
    val content = when {
        selected -> colors.onSecondaryContainer
        outlined -> colors.primary
        else -> colors.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(
                when {
                    selected -> colors.secondaryContainer
                    outlined -> Color.Transparent
                    else -> tileContainer
                },
            )
            .border(
                if (selected) 2.dp else 1.dp,
                when {
                    selected -> colors.primary
                    outlined -> colors.outlineVariant
                    else -> Color.Transparent
                },
                shape,
            )
            .combinedClickable(
                onClick = {
                    if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                },
                onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
                onLongClickLabel = onLongClick?.let { "Rename or delete" },
            )
            .semantics { this.selected = selected }
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 28.dp, height = 20.dp), contentAlignment = Alignment.Center) { art(content) }
        Spacer(Modifier.width(Spacing.s))
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** Starts a new curve from flat; selected while an unsaved curve is playing. */
@Composable
private fun NewEqCard(selected: Boolean, onClick: () -> Unit) {
    EqCard("New EQ", selected, onClick, outlined = true) { Icon(Icons.Default.Add, contentDescription = null, tint = it, modifier = Modifier.size(20.dp)) }
}

@Composable
internal fun EqSparkline(bands: List<Int>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val span = (EqCurve.MAX_DB - EqCurve.MIN_DB).toFloat()
        val path = eqPath(bands, { i -> i * size.width / (EqCurve.BANDS - 1) }, { db -> (EqCurve.MAX_DB - db) / span * size.height })
        drawLine(color.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Each segment eases out of one point and into the next, so equal neighbours stay flat. */
private fun eqPath(bands: List<Int>, x: (Int) -> Float, y: (Int) -> Float) = Path().apply {
    moveTo(x(0), y(bands[0]))
    for (i in 1 until EqCurve.BANDS) {
        val mid = (x(i - 1) + x(i)) / 2
        cubicTo(mid, y(bands[i - 1]), mid, y(bands[i]), x(i), y(bands[i]))
    }
}

/** Description, the official app's bass/mid/treble character bars side by side, and its battery estimate. */
@Composable
private fun ProfileDetails(p: SoundProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        p.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val bars = listOf("Bass" to p.bass, "Mid" to p.mid, "Treble" to p.treble).filter { it.second != null }
        if (bars.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                bars.forEach { (label, value) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        ControlLabel(label)
                        LinearProgressIndicator(progress = { value!! / 100f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                    }
                }
            }
        }
        p.batteryHours?.let { hours ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Battery5Bar, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.s))
                HintText("About $hours hrs of battery at max volume")
            }
        }
    }
}

/**
 * Room tuning on its own card under the EQ, opening the wizard as a page, with the debug builds'
 * measurement tools underneath.
 */
@Composable
internal fun RoomTuning(s: SpeakerState, speaker: SpeakerController, store: EqPresetStore) {
    var tuning by remember { mutableStateOf(false) }
    val bandModel = BandModels.forModel(s.model)
    TuneRoomCard(approximate = !bandModel.measured, onClick = { tuning = true })
    if (tuning) RoomCorrectionWizard(s, speaker, store, bandModel, onDismiss = { tuning = false })
    val context = LocalContext.current
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    val calibrationCsv = File(context.getExternalFilesDir(null), "bandcal.csv")
    // Kept together, so they don't each take the page's spacing between cards.
    Column {
        DebugTool("Calibrate bands (debug)", speaker) { sp, meter, progress -> BandCalibration.run(sp, meter, calibrationCsv, progress) }
        DebugTool("Probe AUX input (debug)", speaker, InputProbe::run)
    }
}

@Composable
private fun TuneRoomCard(approximate: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cardContainer)) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Default.Mic, size = 40.dp, iconSize = 20.dp)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tune to this room", style = MaterialTheme.typography.titleSmall)
                    if (approximate) {
                        Spacer(Modifier.width(Spacing.s))
                        Text(
                            "Approx.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                HintText("Measure with your phone and get an EQ that evens out the room.")
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The six-band curve, bass on the left, with each band's gain underneath. Touch anywhere to grab
 * the nearest band and drag it up or down; every whole-dB change is sent straight to the speaker
 * so it can be heard while editing. Without [onChange] it only shows the curve.
 */
@Composable
internal fun EqGraph(bands: List<Int>, onChange: ((List<Int>) -> Unit)?) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(bands)
    val change by rememberUpdatedState(onChange ?: {})
    var active by remember { mutableStateOf<Int?>(null) }
    val colors = MaterialTheme.colorScheme
    val axisWidth = 44.dp
    val inset = 12.dp
    val span = (EqCurve.MAX_DB - EqCurve.MIN_DB).toFloat()

    Column {
        Row(Modifier.fillMaxWidth().height(220.dp)) {
            // Labels sit on the +10, 0 and -10 grid lines, which the canvas insets by 12 dp.
            Column(Modifier.width(axisWidth).fillMaxHeight().padding(vertical = Spacing.xs), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("+10 dB", "0", "−10 dB").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { contentDescription = "EQ curve: " + current.mapIndexed { i, db -> "band ${i + 1} ${dbLabel(db)}" }.joinToString(", ") }
                    .then(if (onChange == null) Modifier else Modifier.pointerInput(Unit) {
                        val pad = inset.toPx()
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
                val pad = inset.toPx()
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad
                fun x(i: Int) = pad + i * w / (EqCurve.BANDS - 1)
                fun y(db: Int) = pad + (EqCurve.MAX_DB - db) / span * h
                val zero = y(0)

                for (db in EqCurve.MIN_DB..EqCurve.MAX_DB step 5) {
                    drawLine(colors.outlineVariant.copy(alpha = if (db == 0) 0.9f else 0.35f), Offset(pad, y(db)), Offset(pad + w, y(db)), 1.dp.toPx())
                }
                for (i in 0 until EqCurve.BANDS) {
                    val color = if (active == i) colors.primary.copy(alpha = 0.6f) else colors.outlineVariant.copy(alpha = 0.35f)
                    drawLine(color, Offset(x(i), pad), Offset(x(i), pad + h), 1.dp.toPx())
                }

                val curve = eqPath(bands, ::x, ::y)
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
                    drawCircle(if (active == i) colors.primary else colors.onSurface, 9.dp.toPx(), c)
                }
            }
        }
        // Each band's gain, centred under its point.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = axisWidth).height(20.dp)) {
            val step = (maxWidth - inset * 2) / (EqCurve.BANDS - 1)
            val labelWidth = 40.dp
            bands.forEachIndexed { i, db ->
                Text(
                    if (db > 0) "+$db" else "$db",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active == i) FontWeight.Bold else null,
                    color = if (active == i) colors.primary else colors.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(labelWidth).offset(x = inset + step * i - labelWidth / 2),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = axisWidth)) {
            Text("Bass", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Treble", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Runs a measurement tool against the connected speaker, showing its progress; [RoomTuning] shows these in debug builds only. */
@Composable
private fun DebugTool(
    label: String,
    speaker: SpeakerController,
    tool: suspend (SpeakerController, RoomMeter, (String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
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
