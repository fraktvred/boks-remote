package dev.boksremote.ui

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.boksremote.DevicePanel
import dev.boksremote.speaker.CycleConfig
import dev.boksremote.speaker.CycleStatus
import dev.boksremote.speaker.CycleTransition
import dev.boksremote.speaker.LightPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CycleSection(panel: DevicePanel) {
    val stage = panel.stage
    val cycle = stage.cycle
    val status by cycle.status.collectAsState()
    val config by cycle.config.collectAsState()
    val saved by panel.workspace.palettes.palettes.collectAsState()
    val stageStatus by stage.status.collectAsState()
    val lent = stageStatus.leased
    val large by cycle.largePreviews.collectAsState()
    val palettes = remember(config, saved) { cycle.palettes() }
    var editing by remember { mutableStateOf(false) }
    // Ask once so the "cycling" notice is visible; start either way.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { stage.startCycle() }
    val canStart = stageStatus.cycle == null

    if (palettes.isNotEmpty()) {
        val current = status.current.takeIf { status.showing }
        if (large) CycleGrid(palettes, current, paused = !status.running, enabled = !lent) { stage.jumpTo(it.name) }
        else CycleStrip(palettes, current, paused = !status.running, enabled = !lent) { stage.jumpTo(it.name) }
    }
    val every = CycleConfig.intervalLabel(config.intervalSeconds)
    val s = status
    when {
        lent -> HintText("Paused while the DMX bridge runs.")
        s is CycleStatus.Running -> {
            val nextAt = s.nextAt
            if (nextAt == null) Text("Starting…", style = MaterialTheme.typography.bodyMedium)
            else {
                val remaining by produceState(0L, nextAt) {
                    while (true) {
                        value = (nextAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000
                        delay(250)
                    }
                }
                Text("Next: ${s.next} in ${clock(remaining)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        s is CycleStatus.Paused && s.onLight -> {
            val left = s.remainingMs?.let { " with ${clock(it / 1000)} left" }.orEmpty()
            HintText("Paused on ${s.current}$left. Resume plays on from it.")
        }
        // Paused by a palette picked by hand or by the bridge; either way it carries on where it was.
        s is CycleStatus.Paused -> HintText("Paused. Resume carries on with ${s.next}.")
        palettes.size < CycleConfig.MIN_PALETTES -> HintText("Add at least two palettes to cycle through them.")
        else -> HintText("${palettes.size} palettes, every $every, ${if (config.shuffle) "shuffled" else "in order"}. Keeps going with the screen off.")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status.running) {
            FilledTonalButton(onClick = stage::holdCycle) {
                Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Pause")
            }
        } else {
            Button(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }, enabled = canStart) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(if (status.paused) "Resume" else "Start")
            }
        }
        // Stop starts the next run from the top; Pause keeps the place.
        if (status.running || status.paused) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = stage::stopCycle) { Icon(Icons.Default.Stop, contentDescription = "Stop, next start from the beginning") }
        }
        if (status.running) {
            IconButton(onClick = stage::skipCycle) { Icon(Icons.Default.SkipNext, contentDescription = "Next palette now") }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { editing = true }) {
            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Edit")
        }
    }

    if (editing) {
        CycleEditorSheet(
            config = config,
            largePreviews = large,
            onLargePreviews = cycle::setLargePreviews,
            inCycle = palettes,
            available = panel.workspace.palettes.all().filter { p -> palettes.none { it.name == p.name } },
            savedNames = saved.map { it.name }.toSet(),
            onChange = cycle::update,
            onDismiss = { editing = false },
        )
    }
}

/**
 * The cycle's palettes as small tiles, in play order, with the one on the light outlined: solid while
 * Cycle plays, dashed while it waits paused on it. Tapping one shows it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CycleStrip(palettes: List<LightPalette>, current: String?, paused: Boolean, enabled: Boolean, onPick: (LightPalette) -> Unit) {
    // Wraps rather than scrolls, so the whole cycle is visible at a glance.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        palettes.forEach { p ->
            PaletteArt(
                p.colors,
                Modifier
                    .width(44.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, onClickLabel = "Show ${p.name}") { onPick(p) }
                    .then(if (p.name == current) Modifier.tileOutline(MaterialTheme.colorScheme.primary, dashed = paused) else Modifier)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(5.dp)),
            )
        }
    }
}

private const val GRID_COLUMNS = 3

/**
 * The cycle's palettes as large tiles with their names, three to a row in play order; the one on the
 * light is outlined as in [CycleStrip]. Tapping one shows it.
 */
@Composable
private fun CycleGrid(palettes: List<LightPalette>, current: String?, paused: Boolean, enabled: Boolean, onPick: (LightPalette) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        palettes.chunked(GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p ->
                    val on = p.name == current
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = enabled, onClickLabel = "Show ${p.name}") { onPick(p) },
                    ) {
                        PaletteArt(
                            p.colors,
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.4f)
                                .clip(RoundedCornerShape(12.dp))
                                .then(if (on) Modifier.tileOutline(MaterialTheme.colorScheme.primary, dashed = paused, corner = 12.dp) else Modifier)
                                .padding(if (on) 4.dp else 0.dp)
                                .clip(RoundedCornerShape(if (on) 8.dp else 12.dp)),
                        )
                        Text(
                            p.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 2.dp),
                        )
                    }
                }
                // Keeps a short last row's tiles the size of the others.
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun Modifier.tileOutline(color: Color, dashed: Boolean, corner: Dp = 8.dp): Modifier =
    if (!dashed) border(2.dp, color, RoundedCornerShape(corner)) else drawBehind {
        val width = 2.dp.toPx()
        // The stroke is centred on its path, so the path sits half a stroke inside the tile.
        drawRoundRect(
            color,
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(corner.toPx() - width / 2),
            style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))),
        )
    }

private fun clock(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private val ROW_HEIGHT = 60.dp

/**
 * Picks the cycle's palettes and their order, applied as they change. The palettes in the cycle come
 * first and can be dragged by their handle; the rest are listed below to add.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CycleEditorSheet(
    config: CycleConfig,
    largePreviews: Boolean,
    onLargePreviews: (Boolean) -> Unit,
    inCycle: List<LightPalette>,
    available: List<LightPalette>,
    savedNames: Set<String>,
    onChange: (CycleConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    // Names of palettes that no longer exist are dropped on the first edit.
    fun setOrder(list: List<LightPalette>) = onChange(config.copy(names = list.map { it.name }))
    // The sheet is its own window above the screen's snackbar, so it has one of its own.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(config)
    fun removeAll() {
        val before = inCycle
        setOrder(emptyList())
        scope.launch {
            val result = snackbar.showSnackbar("Removed ${before.size} palettes", actionLabel = "Undo", duration = SnackbarDuration.Long)
            // Keeps anything added since, after the restored ones.
            if (result == SnackbarResult.ActionPerformed) onChange(latest.copy(names = before.map { it.name } + (latest.names - before.map { it.name }.toSet())))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.sheetTopMargin()) {
        Box {
            Column(Modifier.nestedScroll(KeepListFling).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
                Text("Cycle", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(16.dp))
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControlLabel("Change every")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CycleConfig.INTERVALS.forEach { s ->
                            FilterChip(
                                selected = config.intervalSeconds == s,
                                onClick = { onChange(config.copy(intervalSeconds = s)) },
                                label = { Text(CycleConfig.intervalLabel(s)) },
                            )
                        }
                    }
                    ControlLabel("Transition")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CycleTransition.entries.forEach { t ->
                            FilterChip(
                                selected = config.transition == t,
                                onClick = { onChange(config.copy(transition = t)) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                    ControlLabel("Previews in the card")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to "Small", true to "Large").forEach { (on, label) ->
                            FilterChip(selected = largePreviews == on, onClick = { onLargePreviews(on) }, label = { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Shuffle", style = MaterialTheme.typography.bodyLarge)
                            HintText("Every palette once in random order, then a new round.")
                        }
                        Switch(checked = config.shuffle, onCheckedChange = { onChange(config.copy(shuffle = it)) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                SheetHeading(
                    if (config.shuffle) "In the cycle (${inCycle.size})" else "In the cycle (${inCycle.size}), in play order",
                    action = if (inCycle.isNotEmpty()) "Remove all" to ::removeAll else null,
                )
                if (inCycle.isEmpty()) {
                    HintText("Add palettes from the list below.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                ReorderableRows(
                    items = inCycle,
                    onMove = { from, to -> setOrder(inCycle.toMutableList().apply { add(to, removeAt(from)) }) },
                    onRemove = { p -> setOrder(inCycle - p) },
                )
                val (yours, presets) = available.partition { it.name in savedNames }
                if (presets.isNotEmpty()) {
                    SheetHeading("Add presets", action = "Add all" to { setOrder(inCycle + presets) })
                    presets.forEach { p -> key("add:" + p.name) { AddRow(p) { setOrder(inCycle + p) } } }
                }
                if (yours.isNotEmpty()) {
                    SheetHeading("Add your palettes", action = "Add all" to { setOrder(inCycle + yours) })
                    yours.forEach { p -> key("add:" + p.name) { AddRow(p) { setOrder(inCycle + p) } } }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/**
 * Keeps the speed a fling of the list has left at its end from the sheet. Given any, the sheet settles
 * with an animation even when already fully open, and while that runs it takes the next touch as a drag
 * of its own, so a tap on a palette was lost. A flick down of the sheet itself, where the list didn't
 * scroll, still reaches it and closes it.
 */
private val KeepListFling = object : NestedScrollConnection {
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (consumed.y != 0f || available.y < 0f) available else Velocity.Zero
}

/** A list heading, with an optional text button at its end such as "Add all". */
@Composable
private fun SheetHeading(text: String, action: Pair<String, () -> Unit>? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
    }
}

@Composable
private fun PaletteRow(p: LightPalette, modifier: Modifier = Modifier, leading: @Composable () -> Unit = {}, trailing: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().height(ROW_HEIGHT).padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        PaletteArt(p.colors, Modifier.width(52.dp).height(38.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun AddRow(p: LightPalette, onAdd: () -> Unit) {
    // Indented like the handle on the rows above, so the thumbnails line up.
    PaletteRow(p, Modifier.clickable(onClickLabel = "Add to cycle", onClick = onAdd), leading = { Spacer(Modifier.width(48.dp)) }) {
        Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
    }
}

/**
 * Rows reordered by dragging their handle. Rows have one fixed height, so the dragged row swaps with
 * its neighbour once it has moved half a row; TalkBack users get Move up / Move down actions instead.
 */
@Composable
private fun ReorderableRows(items: List<LightPalette>, onMove: (from: Int, to: Int) -> Unit, onRemove: (LightPalette) -> Unit) {
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(items)
    val move by rememberUpdatedState(onMove)
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }

    items.forEachIndexed { i, p ->
        // Keyed by name so the row, and the drag in progress on it, follows the palette to its new place.
        key(p.name) {
            val lifted = dragging == p.name
            Surface(
                color = if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                shadowElevation = if (lifted) 6.dp else 0.dp,
                modifier = Modifier
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer { translationY = if (lifted) offset else 0f }
                    .semantics {
                        customActions = listOfNotNull(
                            if (i > 0) CustomAccessibilityAction("Move up") { onMove(i, i - 1); true } else null,
                            if (i < items.lastIndex) CustomAccessibilityAction("Move down") { onMove(i, i + 1); true } else null,
                        )
                    },
            ) {
                PaletteRow(
                    p,
                    leading = {
                        Box(
                            Modifier
                                .padding(end = 4.dp)
                                .size(44.dp)
                                .pointerInput(p.name) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragging = p.name
                                            offset = 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragEnd = { dragging = null; offset = 0f },
                                        onDragCancel = { dragging = null; offset = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        offset += amount.y
                                        val at = latest.indexOfFirst { it.name == p.name }
                                        if (offset > rowPx / 2 && at < latest.lastIndex) {
                                            move(at, at + 1)
                                            offset -= rowPx
                                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        } else if (offset < -rowPx / 2 && at > 0) {
                                            move(at, at - 1)
                                            offset += rowPx
                                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.DragHandle, contentDescription = "Drag to reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                ) {
                    IconButton(onClick = { onRemove(p) }) {
                        Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Remove ${p.name} from cycle", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
