package dev.boksremote.ui

import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

// The controls the screens share: each one here instead of a copy per screen.

/** A line of explanation under a control. */
@Composable
internal fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Mutes, or unmutes back to the level before, for a speaker at [volume]. */
@Composable
internal fun MuteToggle(volume: Int, onToggle: () -> Unit) {
    val muted = volume == 0
    IconToggleButton(checked = muted, onCheckedChange = { onToggle() }) {
        Icon(
            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeMute,
            contentDescription = if (muted) "Unmute" else "Mute",
            tint = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A 0..255 level on a slider: [fraction] moves with the thumb while dragging and follows the device
 * otherwise. Writes stream while dragging; the controller drops stale ones. See [rememberLevelDrag].
 */
@Stable
internal class LevelDrag(private val onLevel: (Int) -> Unit) {
    var fraction by mutableFloatStateOf(0f)
        private set
    private var dragging by mutableStateOf(false)

    /** The level shown, on the wire's 0..255. */
    val level: Int get() = (fraction * 255).roundToInt()

    /** The level as a percentage, or [zeroLabel] (such as "Muted") in place of "0%". */
    fun label(zeroLabel: String? = null): String {
        val percent = (fraction * 100).roundToInt()
        return if (percent == 0 && zeroLabel != null) zeroLabel else "$percent%"
    }

    fun drag(to: Float) {
        dragging = true
        fraction = to
        onLevel(level)
    }

    fun settle() {
        dragging = false
    }

    internal fun follow(value: Int) {
        if (!dragging) fraction = value / 255f
    }
}

@Composable
internal fun rememberLevelDrag(value: Int, onChange: (Int) -> Unit): LevelDrag {
    val latest by rememberUpdatedState(onChange)
    val drag = remember { LevelDrag { latest(it) } }
    drag.follow(value)
    return drag
}

/**
 * A row of tiles, icon over label, the selected one filled. [compact] is the size down the quick
 * settings use.
 */
@Composable
internal fun <T> TileChoice(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    onSelect: (T) -> Unit,
    compact: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        options.forEach { option ->
            val on = option == selected
            val container by animateColorAsState(if (on) MaterialTheme.colorScheme.primaryContainer else tileContainer, label = "tile container")
            val content by animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "tile content")
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(if (compact) Corners.medium else Corners.large))
                    .background(container)
                    .selectable(selected = on, role = Role.RadioButton) {
                        if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(option)
                    }
                    .then(if (compact) Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.s) else Modifier.padding(vertical = Spacing.m)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon(option), contentDescription = null, tint = content, modifier = Modifier.size(if (compact) 20.dp else 24.dp))
                Spacer(Modifier.height(if (compact) Spacing.xs else 6.dp))
                Text(
                    label(option),
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A slider over a track drawn in colour, with a round thumb filled with [thumbColor]. The track
 * reaches out to the thumb's edges, and [fill] draws it given the inset of the thumb's centre at
 * either end, where its range starts and ends. [outline] rings the track; [growWhenHeld] swells the
 * thumb under the finger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorTrackSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onSettled: () -> Unit,
    thumbColor: Color,
    description: String,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 16.dp,
    thumbSize: Dp = 28.dp,
    outline: Color? = null,
    growWhenHeld: Boolean = false,
    fill: DrawScope.(inset: Float) -> Unit,
) {
    val held = remember { MutableInteractionSource() }
    val dragged by held.collectIsDraggedAsState()
    val pressed by held.collectIsPressedAsState()
    val grow by animateFloatAsState(if (growWhenHeld && (dragged || pressed)) 1.15f else 1f, label = "thumb")
    Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onSettled,
        interactionSource = held,
        modifier = modifier.semantics { contentDescription = description },
        thumb = {
            Box(
                Modifier
                    .size(thumbSize)
                    .graphicsLayer { scaleX = grow; scaleY = grow }
                    .shadow(3.dp, CircleShape)
                    .background(Color.White, CircleShape)
                    .padding(3.dp)
                    .background(thumbColor, CircleShape),
            )
        },
        track = {
            Box(
                Modifier
                    // The slider spans its track between the thumb's centres; this one reaches out to the thumb's edges.
                    .layout { measurable, constraints ->
                        val reach = (thumbSize / 2).roundToPx()
                        val width = constraints.maxWidth + 2 * reach
                        val p = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(constraints.maxWidth, p.height) { p.place(-reach, 0) }
                    }
                    .height(trackHeight)
                    .clip(CircleShape)
                    .drawBehind { fill(thumbSize.toPx() / 2) }
                    .then(if (outline != null) Modifier.border(1.dp, outline, CircleShape) else Modifier),
            )
        },
    )
}

/**
 * [items] in their order, reordered by dragging a handle: [row] gets the modifier for its handle and
 * whether it is lifted. A dragged row swaps with its neighbour once it has moved half a [step], the
 * distance from one row to the next; TalkBack users get Move up / Move down actions instead.
 */
@Composable
internal fun <T> ReorderableColumn(
    items: List<T>,
    idOf: (T) -> String,
    step: Dp,
    onMove: (from: Int, to: Int) -> Unit,
    shape: Shape = RectangleShape,
    row: @Composable (item: T, handle: Modifier, lifted: Boolean) -> Unit,
) {
    val stepPx = with(LocalDensity.current) { step.toPx() }
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(items)
    val move by rememberUpdatedState(onMove)
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }

    items.forEachIndexed { i, item ->
        val id = idOf(item)
        // Keyed so the row, and the drag in progress on it, follows the item to its new place.
        key(id) {
            val lifted = dragging == id
            Box(
                Modifier
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (lifted) offset else 0f
                        shadowElevation = if (lifted) 6.dp.toPx() else 0f
                        this.shape = shape
                    }
                    .semantics {
                        customActions = listOfNotNull(
                            if (i > 0) CustomAccessibilityAction("Move up") { onMove(i, i - 1); true } else null,
                            if (i < items.lastIndex) CustomAccessibilityAction("Move down") { onMove(i, i + 1); true } else null,
                        )
                    },
            ) {
                val handle = Modifier.pointerInput(id) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = id
                            offset = 0f
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragEnd = { dragging = null; offset = 0f },
                        onDragCancel = { dragging = null; offset = 0f },
                    ) { change, amount ->
                        change.consume()
                        offset += amount.y
                        val at = latest.indexOfFirst { idOf(it) == id }
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
                row(item, handle, lifted)
            }
        }
    }
}

/**
 * Asks for a name, up to [maxLength] characters. A blank one is taken as [placeholder]; without one, a
 * name is required. With [requireChange], Save waits for a name other than [initial].
 */
@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    maxLength: Int,
    placeholder: String? = null,
    requireChange: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Starts selected, so typing replaces the old name and a tap in the field keeps it.
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val chosen = name.text.trim().ifBlank { placeholder.orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Focus alone leaves the keyboard closed in a dialog, above all one opened over a full-screen
            // page, so its window is told to show it once the window has focus.
            val view = LocalView.current
            val windowInfo = LocalWindowInfo.current
            LaunchedEffect(Unit) {
                snapshotFlow { windowInfo.isWindowFocused }.first { it }
                focus.requestFocus()
                val window = (view.parent as? DialogWindowProvider)?.window
                    ?: (view.parent?.parent as? DialogWindowProvider)?.window
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.ime()) }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.copy(text = it.text.take(maxLength)) },
                label = { Text("Name") },
                placeholder = placeholder?.let { { Text(it) } },
                supportingText = { Text("${name.text.length}/$maxLength") },
                singleLine = true,
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }, enabled = chosen.isNotEmpty() && !(requireChange && chosen == initial)) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
