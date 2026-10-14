package dev.boksremote.ui

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import dev.boksremote.BoksApp
import dev.boksremote.speaker.CycleStatus
import dev.boksremote.speaker.Scene
import dev.boksremote.speaker.SpeakerState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val SCENE_ROW_HEIGHT = 64.dp
private val SCENE_ROW_GAP = 4.dp

/**
 * The scenes in their order, each [row] given the modifier for its drag handle: dragging lifts the
 * row and moves it a place each time it passes half a row. A lone scene has nothing to move past, so
 * it gets no handle (null).
 */
@Composable
private fun ReorderableScenes(scenes: List<Scene>, onMove: (from: Int, to: Int) -> Unit, row: @Composable (Scene, Modifier?) -> Unit) {
    val stepPx = with(LocalDensity.current) { (SCENE_ROW_HEIGHT + SCENE_ROW_GAP).toPx() }
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(scenes)
    val move by rememberUpdatedState(onMove)
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }

    scenes.forEachIndexed { i, scene ->
        // Keyed by name so the row, and the drag in progress on it, follows the scene to its new place.
        key(scene.name) {
            val lifted = dragging == scene.name
            Box(
                Modifier
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (lifted) offset else 0f
                        shadowElevation = if (lifted) 6.dp.toPx() else 0f
                        shape = RoundedCornerShape(12.dp)
                    }
                    .semantics {
                        customActions = listOfNotNull(
                            if (i > 0) CustomAccessibilityAction("Move up") { onMove(i, i - 1); true } else null,
                            if (i < scenes.lastIndex) CustomAccessibilityAction("Move down") { onMove(i, i + 1); true } else null,
                        )
                    },
            ) {
                val handle = Modifier.pointerInput(scene.name) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = scene.name
                            offset = 0f
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragEnd = { dragging = null; offset = 0f },
                        onDragCancel = { dragging = null; offset = 0f },
                    ) { change, amount ->
                        change.consume()
                        offset += amount.y
                        val at = latest.indexOfFirst { it.name == scene.name }
                        if (offset > stepPx / 2 && at < latest.lastIndex) {
                            move(at, at + 1)
                            offset -= stepPx
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        } else if (offset < -stepPx / 2 && at > 0) {
                            move(at, at - 1)
                            offset += stepPx
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    }
                }
                row(scene, handle.takeIf { scenes.size > 1 })
            }
        }
    }
}

/**
 * The scene and the light's colours as one card: the scene applied last on top, tapped to open every
 * scene in a sheet, where they are switched to, renamed or deleted, and [colours] beneath it, since the
 * light's colours are part of the scene in use. Keeps the device screen short however many scenes there are.
 */
@Composable
fun ScenesSection(s: SpeakerState, app: BoksApp, colours: @Composable () -> Unit) {
    val scenes by app.scenes.scenes.collectAsState()
    val activeName by app.activeScene.collectAsState()
    val live by app.activeSceneLive.collectAsState()
    val cycle by app.cycle.status.collectAsState()
    var browsing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Scene?>(null) }
    val canSave = s.brightness != null && s.energyLevel != null
    // The scene in use follows Cycle, so its Cycle is the one running.
    fun playing(scene: Scene) = if (scene.name == activeName && cycle.running) cycle else null
    fun position(scene: Scene) = if (playing(scene) != null) app.cycle.palettes().indexOfFirst { it.name == cycle.current } + 1 else 0
    val active = scenes.firstOrNull { it.name == activeName }
    // The light as it is now, rather than as last saved, so a change or a Cycle step shows at once.
    val shown = active?.let { live?.takeIf { it.name == active.name } ?: active }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        if (scenes.isEmpty()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Default.Bookmarks, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Scenes", style = MaterialTheme.typography.titleMedium)
                }
                HintText("Save this look, colours and Cycle included, to bring it back with one tap. A scene keeps your changes by itself.")
                SaveSceneButton(canSave) { naming = true }
            }
        } else {
            SceneCover(
                shown,
                playing = active?.let(::playing),
                position = active?.let(::position) ?: 0,
                count = scenes.size,
                onClick = { browsing = true },
                // With a scene in use the light already is that scene, so a new one is started from the sheet.
                save = { if (active == null) SaveSceneButton(canSave) { naming = true } },
            )
        }
        // The cover's colours end the scene by themselves; a plain scene row needs the line.
        if (shown == null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        colours()
    }

    if (browsing) {
        ScenesSheet(
            scenes = scenes,
            playing = ::playing,
            position = ::position,
            canSave = canSave,
            onApply = { app.applyScene(it); browsing = false },
            onRename = { renaming = it },
            onDelete = app::deleteScene,
            onMove = app.scenes::move,
            onSaveNew = { naming = true },
            onDismiss = { browsing = false },
        )
    }
    if (naming) {
        PaletteNameDialog(if (active == null) "Save as scene" else "New scene", app.scenes.suggestName(), "Save", placeholder = "Scene", onDismiss = { naming = false }) { name ->
            app.saveScene(name)
            naming = false
        }
    }
    renaming?.let { scene ->
        PaletteNameDialog("Rename scene", scene.name, "Rename", placeholder = "Scene", onDismiss = { renaming = null }) { name ->
            app.renameScene(scene, name)
            renaming = null
        }
    }
}

@Composable
private fun SaveSceneButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text("Save as scene")
    }
}

/**
 * The active scene, or a way into the list when none has been applied; tapping it opens [ScenesSheet].
 * An active scene is shown as a cover: its palette fills the row, dimmed behind the text.
 */
@Composable
private fun SceneCover(active: Scene?, playing: CycleStatus?, position: Int, count: Int, onClick: () -> Unit, save: @Composable () -> Unit) {
    val cover = active != null
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClickLabel = "Browse scenes", onClick = onClick)) {
        if (active != null) {
            PaletteArt(active.palette?.colors ?: DEFAULT_ART, Modifier.matchParentSize())
            // Darkest under the text, so the name reads on a yellow or white palette too.
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.22f)))))
        }
        val text = if (cover) Color.White else MaterialTheme.colorScheme.onSurface
        val subtle = if (cover) Color.White.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (active == null) {
                IconBadge(Icons.Default.Bookmarks, size = 40.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(active?.name ?: "No scene applied", style = MaterialTheme.typography.titleMedium, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    active?.let { sceneStatus(it, playing, position) } ?: "Browse $count ${if (count == 1) "scene" else "scenes"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active != null) subtle else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            save()
            if (playing != null) Countdown(playing.nextAt, tint = if (cover) Color.White else MaterialTheme.colorScheme.primary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = subtle)
        }
    }
}

/** Every scene; applying one closes the sheet so the light's new look is in view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScenesSheet(
    scenes: List<Scene>,
    playing: (Scene) -> CycleStatus?,
    position: (Scene) -> Int,
    canSave: Boolean,
    onApply: (Scene) -> Unit,
    onRename: (Scene) -> Unit,
    onDelete: (Scene) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onSaveNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.sheetTopMargin()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Scenes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onSaveNew, enabled = canSave) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("New scene")
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(SCENE_ROW_GAP)) {
                ReorderableScenes(scenes, onMove) { scene, handle ->
                    SceneRow(
                        scene,
                        playing = playing(scene),
                        position = position(scene),
                        handle = handle,
                        onApply = { onApply(scene) },
                        onRename = { onRename(scene) },
                        onDelete = { onDelete(scene) },
                    )
                }
            }
            HintText("Tap to switch. The scene in use saves your changes by itself.", Modifier.padding(start = 8.dp, top = 12.dp))
        }
    }
}

/** A scene's look, or while its Cycle plays, its light mode, the palette on and its place in the cycle. */
private fun sceneStatus(scene: Scene, playing: CycleStatus?, position: Int): String {
    val count = scene.cycle?.config?.names?.size ?: 0
    return when {
        // The countdown beside it has the Cycle icon, so the word itself makes way for the light mode.
        playing != null -> listOfNotNull(scene.level.label, playing.current, "$position of $count".takeIf { position > 0 }).joinToString(" · ")
        scene.cycle?.running == true -> "${scene.level.label} · ${(scene.brightness * 100 / 255f).roundToInt()}% · Cycle, $count palettes"
        else -> "${scene.level.label} · ${(scene.brightness * 100 / 255f).roundToInt()}%"
    }
}

/** Time to Cycle's next palette, beside its icon. */
@Composable
private fun Countdown(nextAt: Long, tint: Color = MaterialTheme.colorScheme.primary) {
    val left by produceState(0L, nextAt) {
        while (true) {
            value = (nextAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000
            delay(250)
        }
    }
    Row(Modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Autorenew, contentDescription = "Next palette in", tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("${left / 60}:${(left % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/**
 * One scene. [playing] is Cycle's live status while this scene's Cycle is the one running, shown with
 * the palette on, its [position] in the cycle (1-based) and the time to the next change.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SceneRow(
    scene: Scene,
    playing: CycleStatus?,
    position: Int,
    handle: Modifier?,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(SCENE_ROW_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(if (playing != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(
                onClickLabel = "Apply",
                onClick = onApply,
                onLongClickLabel = "Rename or delete",
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (handle != null) {
            Box(handle.size(40.dp, SCENE_ROW_HEIGHT), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DragHandle, contentDescription = "Drag to reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Spacer(Modifier.width(12.dp))
        PaletteArt(
            scene.palette?.colors ?: DEFAULT_ART,
            Modifier.width(52.dp).height(40.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(scene.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = sceneStatus(scene, playing, position)
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = if (playing != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (playing != null) Countdown(playing.nextAt)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More for ${scene.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { onRename(); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { onDelete(); menu = false },
                )
            }
        }
    }
}
