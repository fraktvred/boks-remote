package dev.boksremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.width
import dev.boksremote.speaker.PaletteCycle
import dev.boksremote.speaker.PaletteStore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.boksremote.speaker.HsvColor
import dev.boksremote.speaker.LightFx
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.SpeakerController
import dev.boksremote.speaker.SpeakerState

@Composable
fun PaletteSection(s: SpeakerState, speaker: SpeakerController, store: PaletteStore, cycle: PaletteCycle) {
    val saved by store.palettes.collectAsState()
    val recent by store.recentColors.collectAsState()
    // Null while the light runs its default colours: it picks them itself, so a neutral stand-in is shown.
    val current = s.palette?.takeUnless { s.paletteIsDefault }
    val shown = current?.colors ?: DEFAULT_ART
    var browsing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<PaletteDraft?>(null) }
    // A palette the app doesn't know by name (sent by another app, or kept with "Use without saving").
    val unsaved = current != null && LightPalette.PRESETS.none { it.name == current.name } && saved.none { it.name == current.name }

    /** Opens the editor on the light's palette: in place if it is one of the user's, otherwise as a new copy. */
    fun editCurrent(swatch: Int) {
        val own = current?.let { c -> saved.firstOrNull { it.name == c.name } }
        draft = own?.let { PaletteDraft(it, it.name, it.colors, swatch) }
            ?: PaletteDraft(null, store.suggestName(current?.name?.takeIf { it != "Custom" } ?: "My palette"), shown, swatch)
    }

    ActivePaletteBanner(
        name = current?.name ?: "Lightboks default",
        colors = shown,
        onClick = { browsing = true },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        shown.forEachIndexed { i, c ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(c.compose())
                        .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable(onClickLabel = "Edit ${SWATCH_LABELS[i]} colour") { editCurrent(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    // Swatches look like plain dots otherwise; the pencil says they open an editor.
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = if (c.value > 0.6f && c.saturation < 0.5f) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(SWATCH_LABELS[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (current == null) HintText("The light is choosing its own colours. Tap a colour to make your own palette, or browse palettes.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { browsing = true }) {
            Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Palettes")
        }
        Spacer(Modifier.weight(1f))
        if (unsaved) {
            TextButton(onClick = { editCurrent(0) }) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Save")
            }
        }
    }

    if (browsing) {
        PalettePickerSheet(
            active = s.palette,
            activeIsDefault = s.paletteIsDefault,
            saved = saved,
            // A palette picked by hand wins over Cycle, which then waits for Resume.
            onApply = { cycle.pause(); speaker.setPalette(it) },
            onResetDefault = { cycle.pause(); speaker.resetPalette() },
            onNew = { draft = PaletteDraft(null, store.suggestName("My palette"), shown) },
            onEdit = { draft = PaletteDraft(it, it.name, it.colors) },
            onDuplicate = { draft = PaletteDraft(null, store.suggestName(it.name), it.colors) },
            onRename = { p, name ->
                val renamed = store.rename(p, name)
                cycle.rename(p.name, renamed.name)
                if (current?.name == p.name) speaker.setPalette(renamed)
            },
            onDelete = { p ->
                val index = saved.indexOf(p)
                val cycleIndex = cycle.config.value.names.indexOf(p.name)
                store.delete(p)
                cycle.remove(p.name)
                val undo = {
                    val back = store.restore(p, index)
                    if (cycleIndex >= 0) cycle.restore(back.name, cycleIndex)
                }
                undo
            },
            onDismiss = { browsing = false },
        )
    }
    draft?.let { d ->
        val session = remember(d) { PreviewSession(speaker, cycle, current) }
        PaletteEditorSheet(
            draft = d,
            onLight = current?.colors,
            recentColors = recent,
            onPreview = session::show,
            onPreviewOff = session::restore,
            onSave = { name, colors, preview ->
                val p = d.original?.let { o -> store.update(o, name, colors).also { cycle.rename(o.name, it.name) } }
                    ?: store.add(name, colors)
                // Sent under its saved name, so the light, and this screen after a reconnect, know it by that name.
                when {
                    preview -> session.keep(p)
                    d.original != null && current?.name == d.original.name -> session.refresh(p)
                }
                draft = null
            },
            onApplyOnce = { session.keep(LightPalette("Custom", it)); draft = null },
            onCancel = { session.restore(); draft = null },
        )
    }
}

/** The light's side of one editor session: a preview pauses Cycle, and cancelling puts back what was showing. */
private class PreviewSession(
    private val speaker: SpeakerController,
    private val cycle: PaletteCycle,
    /** Null when the light ran its default colours. */
    private val before: LightPalette?,
) {
    private val cycleWasRunning = cycle.status.value.running
    private var previewing = false

    fun show(colors: List<HsvColor>) {
        if (!previewing) {
            previewing = true
            cycle.pause()
        }
        speaker.setPalette(LightPalette("Custom", colors))
    }

    /** Undoes the preview; if Cycle was running it simply carries on instead. */
    fun restore() {
        if (!previewing) return
        previewing = false
        when {
            cycleWasRunning -> cycle.start(speaker)
            before != null -> speaker.setPalette(before)
            else -> speaker.resetPalette()
        }
    }

    /** Leaves [p] on the light: a palette picked by hand, so Cycle stays paused. */
    fun keep(p: LightPalette) {
        cycle.pause()
        speaker.setPalette(p)
    }

    /** New colours for the palette the light already shows; Cycle, if running, isn't interrupted. */
    fun refresh(p: LightPalette) = speaker.setPalette(p)
}

/** Press-and-hold pads, like the official app's FX panel: the effect runs only while a finger is down. */
@Composable
fun FxPad(speaker: SpeakerController) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LightFx.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { fx ->
                    HoldPad(fx.label, "${fx.label}, press and hold", Modifier.weight(1f).aspectRatio(2f)) { held -> speaker.holdFx(fx.takeIf { held }) }
                }
            }
        }
        HoldPad(
            "Drop",
            "Drop: hold for the build-up, let go for the drop",
            Modifier.fillMaxWidth().height(64.dp),
            hint = "Hold for the build-up, let go for the drop",
            onHold = speaker::holdDrop,
        )
    }
}

/** One pad: [onHold] gets true when a finger goes down and false when it lifts. */
@Composable
private fun HoldPad(label: String, description: String, modifier: Modifier, hint: String? = null, onHold: (Boolean) -> Unit) {
    var active by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .scale(if (active) 0.96f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    active = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHold(true)
                    tryAwaitRelease()
                    active = false
                    onHold(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.8f))
        }
    }
}
