package dev.boksremote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import dev.boksremote.speaker.EnergyLevel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.width
import dev.boksremote.speaker.Stage
import dev.boksremote.speaker.PaletteKind
import dev.boksremote.speaker.PaletteLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import dev.boksremote.speaker.HsvColor
import dev.boksremote.speaker.LightFx
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.SpeakerController
import dev.boksremote.speaker.SpeakerState

@Composable
fun PaletteSection(s: SpeakerState, control: Stage.LightControl, store: PaletteLibrary) {
    val saved by store.palettes.collectAsState()
    // Null while the light runs its default colours: it picks them itself, so a neutral stand-in is shown.
    val current = s.palette?.takeUnless { s.paletteIsDefault }
    val shown = current?.colors ?: DEFAULT_ART
    val pages = remember { PalettePagesState() }
    var browsing by pages::browsing
    var draft by pages::draft
    val kind = remember(current, saved) { store.kind(current) }
    val unsaved = kind == PaletteKind.UNSAVED

    /** Opens the editor on the light's palette: in place if it is one of the user's, otherwise as a new copy. */
    fun editCurrent(swatch: Int) {
        val own = store.own(current)
        draft = own?.let { PaletteDraft(it, it.name, it.colors, swatch) }
            ?: PaletteDraft(null, store.suggestName(current?.name?.takeIf { it != "Custom" } ?: "My palette"), shown, swatch)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Browse palettes") { browsing = true }
            .padding(start = 16.dp, end = 12.dp, top = 18.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(current?.name ?: "Lightboks default", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The one action on the row, so it is the one thing on it that looks like a button.
        if (unsaved) {
            FilledTonalButton(onClick = { editCurrent(0) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Save")
            }
        }
        // The whole row opens the palettes; the chevron only says so.
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    SwatchStrip(shown, ::editCurrent, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 18.dp))
    if (current == null) HintText("Tap a colour to make your own palette.", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))

    PalettePages(pages, s, control, store)
}

/** Where the palettes page and its editor are: the page open, and the palette being edited. */
@Stable
internal class PalettePagesState {
    var browsing by mutableStateOf(false)
    var draft by mutableStateOf<PaletteDraft?>(null)
}

/** The palettes page and the editor it opens, over whichever screen opened them, acting on [control]'s light. */
@Composable
internal fun PalettePages(pages: PalettePagesState, s: SpeakerState, control: Stage.LightControl, store: PaletteLibrary) {
    val saved by store.palettes.collectAsState()
    val recent by store.recentColors.collectAsState()
    val current = s.palette?.takeUnless { s.paletteIsDefault }
    val shown = current?.colors ?: DEFAULT_ART
    var browsing by pages::browsing
    var draft by pages::draft

    if (browsing) {
        PalettesPage(
            active = s.palette,
            activeIsDefault = s.paletteIsDefault,
            saved = saved,
            // A palette picked by hand wins over Cycle, which then waits for Resume.
            onApply = { control.showPalette(it) },
            onResetDefault = { control.showPalette(null) },
            onNew = { draft = PaletteDraft(null, store.suggestName("My palette"), shown) },
            onEdit = { draft = PaletteDraft(it, it.name, it.colors) },
            onDuplicate = { draft = PaletteDraft(null, store.suggestName(it.name), it.colors) },
            onRename = { p, name ->
                val renamed = store.rename(p, name)
                if (current?.name == p.name) control.updateShown(renamed)
            },
            onDelete = store::delete,
            onDismiss = { browsing = false },
        )
    }
    draft?.let { d ->
        val session = remember(d) { control.preview() }
        PaletteEditorPage(
            draft = d,
            onLight = current?.colors,
            recentColors = recent,
            onPreview = session::show,
            onPreviewOff = session::cancel,
            onSave = { name, colors, preview ->
                val p = d.original?.let { o -> store.update(o, name, colors) }
                    ?: store.add(name, colors)
                // Sent under its saved name, so the light, and this screen after a reconnect, know it by that name.
                when {
                    preview -> session.keep(p)
                    d.original != null && current?.name == d.original.name -> session.refresh(p)
                }
                draft = null
            },
            onApplyOnce = { session.keep(LightPalette("Custom", it)); draft = null },
            onCancel = { session.cancel(); draft = null },
        )
    }
}

/**
 * The palette's colours as one strip, each tapped to edit it. Effect stands apart from the three
 * main colours, since the light uses it for effects rather than sharing it out with them.
 */
@Composable
private fun SwatchStrip(colors: List<HsvColor>, onEdit: (Int) -> Unit, modifier: Modifier = Modifier) {
    val outer = 12.dp
    val inner = 4.dp
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        colors.forEachIndexed { i, c ->
            if (i == 3) Spacer(Modifier.width(9.dp))
            val shape = when (i) {
                0 -> RoundedCornerShape(outer, inner, inner, outer)
                2 -> RoundedCornerShape(inner, outer, outer, inner)
                3 -> RoundedCornerShape(outer)
                else -> RoundedCornerShape(inner)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(shape)
                        .background(c.compose())
                        // Keeps a near-black colour from vanishing into the card.
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)
                        .clickable(onClickLabel = "Edit ${SWATCH_LABELS[i]} colour") { onEdit(i) },
                )
                Spacer(Modifier.height(4.dp))
                Text(SWATCH_LABELS[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Press-and-hold pads, like the official app's FX panel: the effect runs only while a finger is down.
 * Sliding up while holding locks one on until it is tapped again; another pad held meanwhile plays
 * over it and hands back to it on release.
 */
@Composable
fun FxPad(speaker: SpeakerController) {
    var locked by rememberSaveable { mutableStateOf<LightFx?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LightFx.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { fx ->
                    HoldPad(
                        fx.label,
                        "${fx.label}, press and hold, slide up to lock",
                        Modifier.weight(1f).aspectRatio(2f),
                        locked = locked == fx,
                        onLock = { locked = fx },
                        onUnlock = {
                            locked = null
                            speaker.holdFx(null)
                        },
                    ) { held -> speaker.holdFx(if (held) fx else locked) }
                }
            }
        }
        HoldPad(
            "Drop",
            "Drop: hold for the build-up, let go for the drop",
            Modifier.fillMaxWidth().height(64.dp),
            hint = "Hold for the build-up, let go for the drop",
        ) { held ->
            // The drop ends by stopping every effect, a locked one too.
            if (held) locked = null
            speaker.holdDrop(held)
        }
    }
}

private val LOCK_DRAG = 56.dp
/** How long a pad waits to tell a press from a swipe that scrolls the screen. */
private const val PRESS_WAIT_MS = 50L

/** Swallows the rest of a gesture, so the screen behind doesn't scroll with it. */
private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/**
 * One pad: [onHold] gets true when a finger goes down and false when it lifts. With [onLock], sliding
 * the finger up by [LOCK_DRAG] locks it instead: it stays on, without [onHold] false, until a tap
 * calls [onUnlock].
 */
@Composable
private fun HoldPad(
    label: String,
    description: String,
    modifier: Modifier,
    hint: String? = null,
    locked: Boolean = false,
    onLock: (() -> Unit)? = null,
    onUnlock: () -> Unit = {},
    onHold: (Boolean) -> Unit,
) {
    var active by remember { mutableStateOf(false) }
    // 0 to 1 as the finger slides up towards the lock.
    var lockProgress by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val isLocked by rememberUpdatedState(locked)
    val lock by rememberUpdatedState(onLock)
    val unlock by rememberUpdatedState(onUnlock)
    val hold by rememberUpdatedState(onHold)
    val lit = active || locked
    Box(
        modifier
            .scale(if (active) 0.96f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
            .semantics { contentDescription = if (locked) "$label, locked on, tap to unlock" else description }
            .pointerInput(Unit) {
                val lockPx = LOCK_DRAG.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (isLocked) {
                        down.consume()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        unlock()
                        consumeUntilUp()
                        return@awaitEachGesture
                    }
                    // A swipe that starts on a pad is the screen scrolling, not a press: the pad waits a moment
                    // for the finger, and lets the screen have it if it heads down or sideways first. Moving up
                    // is the slide to lock, so it presses at once.
                    var released = false
                    val press = withTimeoutOrNull(PRESS_WAIT_MS) {
                        var decided: Boolean? = null
                        while (decided == null) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            when {
                                change == null -> decided = false
                                // A quick tap still plays, if only for a moment.
                                !change.pressed -> { released = true; decided = true }
                                else -> {
                                    val moved = change.position - down.position
                                    if (moved.getDistance() > viewConfiguration.touchSlop) {
                                        decided = moved.y < 0 && abs(moved.y) > abs(moved.x)
                                        if (decided) change.consume()
                                    }
                                }
                            }
                        }
                        decided
                    } ?: true
                    if (!press) return@awaitEachGesture
                    active = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    hold(true)
                    var lockedNow = false
                    while (!released) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        // Taken here, so a slide doesn't scroll the screen and cut the hold short.
                        change.consume()
                        val onLockNow = lock ?: continue
                        lockProgress = ((down.position.y - change.position.y) / lockPx).coerceIn(0f, 1f)
                        if (lockProgress >= 1f) {
                            lockedNow = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLockNow()
                            // The finger is still sliding; the rest of it mustn't scroll the screen either.
                            consumeUntilUp()
                            break
                        }
                    }
                    active = false
                    lockProgress = 0f
                    if (!lockedNow) hold(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val color = if (lit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (onLock != null && (active || locked)) {
                Icon(
                    if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer {
                            translationY = -lockProgress * 8.dp.toPx()
                            alpha = if (locked) 1f else 0.5f + lockProgress / 2
                        },
                )
            }
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            val shown = when {
                locked -> "Locked · tap to unlock"
                active && onLock != null -> "Slide up to lock"
                else -> hint
            }
            if (shown != null) Text(shown, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.8f))
        }
    }
}

internal val EnergyLevel.icon: ImageVector
    get() = when (this) {
        EnergyLevel.LOW -> Icons.Default.Waves
        EnergyLevel.MEDIUM -> Icons.Default.MusicNote
        EnergyLevel.HIGH -> Icons.Default.Bolt
        EnergyLevel.CUSTOM -> Icons.Default.Highlight
    }

/** The light modes as tiles, each with an icon for its mood; the one on is filled. */
@Composable
fun LightModePicker(selected: EnergyLevel?, onSelect: (EnergyLevel) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnergyLevel.entries.forEach { level ->
            val on = level == selected
            val container by animateColorAsState(
                if (on) MaterialTheme.colorScheme.primaryContainer else tileContainer,
                label = "mode container",
            )
            val content by animateColorAsState(
                if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "mode content",
            )
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(container)
                    .selectable(selected = on, role = Role.RadioButton) {
                        if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(level)
                    }
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(level.icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    level.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
