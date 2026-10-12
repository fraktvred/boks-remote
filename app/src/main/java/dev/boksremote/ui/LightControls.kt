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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.width
import dev.boksremote.speaker.PaletteStore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
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

private val SWATCH_LABELS = listOf("Main 1", "Main 2", "Main 3", "Effect")

private fun HsvColor.compose() = Color(toArgb())

@Composable
fun PaletteSection(s: SpeakerState, speaker: SpeakerController, store: PaletteStore) {
    val saved by store.palettes.collectAsState()
    // Edits start from what the light reports, falling back to the first preset when it runs its default colours.
    val current = s.palette ?: LightPalette.PRESETS.first()
    var editing by remember { mutableIntStateOf(-1) }
    var browsing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    // Swatch edits produce an unnamed palette; offer to keep it until it has a name the app knows.
    val unsaved = !s.paletteIsDefault && s.palette != null &&
        LightPalette.PRESETS.none { it.name == current.name } && saved.none { it.name == current.name }

    ActivePaletteBanner(
        name = if (s.paletteIsDefault) "Lightboks default" else current.name,
        colors = current.colors,
        onClick = { browsing = true },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        current.colors.forEachIndexed { i, c ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(c.compose())
                        .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable(onClickLabel = "Edit ${SWATCH_LABELS[i]} colour") { editing = i },
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
    if (s.paletteIsDefault) HintText("The light is choosing its own colours. Tap a colour or browse palettes to change them.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { browsing = true }) {
            Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Palettes")
        }
        Spacer(Modifier.width(8.dp))
        OutlinedIconButton(onClick = { LightPalette.PRESETS.filterNot { it.name == current.name && !s.paletteIsDefault }.random().let(speaker::setPalette) }) {
            Icon(Icons.Default.Shuffle, contentDescription = "Random preset")
        }
        Spacer(Modifier.weight(1f))
        if (unsaved) {
            TextButton(onClick = { naming = true }) {
                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Save")
            }
        }
    }

    // Saving re-sends the palette under its new name so the light, and this screen after a reconnect, know it by that name.
    fun saveCurrent(name: String) = speaker.setPalette(store.add(name, current.colors))

    if (browsing) {
        PalettePickerSheet(
            active = s.palette,
            activeIsDefault = s.paletteIsDefault,
            saved = saved,
            onApply = speaker::setPalette,
            onResetDefault = speaker::resetPalette,
            onSaveCurrent = ::saveCurrent,
            onRename = { p, name ->
                val renamed = store.rename(p, name)
                if (!s.paletteIsDefault && s.palette?.name == p.name) speaker.setPalette(renamed)
            },
            onDelete = store::delete,
            onDismiss = { browsing = false },
        )
    }
    if (naming) {
        PaletteNameDialog("Save palette", "", "Save", onDismiss = { naming = false }) { saveCurrent(it); naming = false }
    }
    if (editing >= 0) {
        ColorDialog(
            title = SWATCH_LABELS[editing],
            initial = current.colors[editing],
            onDismiss = { editing = -1 },
            onApply = { color ->
                val colors = current.colors.toMutableList().also { it[editing] = color }
                speaker.setPalette(LightPalette("Custom", colors))
                editing = -1
            },
        )
    }
}

private val HUE_STOPS = (0..6).map { Color(HsvColor(it * 60f, 1f, 1f).toArgb()) }

@Composable
private fun ColorDialog(title: String, initial: HsvColor, onDismiss: () -> Unit, onApply: (HsvColor) -> Unit) {
    var hue by remember { mutableStateOf(initial.hue) }
    var sat by remember { mutableStateOf(initial.saturation) }
    var value by remember { mutableStateOf(initial.value) }
    val color = HsvColor(hue, sat, value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))) {
                    Box(Modifier.weight(1f).height(56.dp).background(initial.compose()))
                    Box(Modifier.weight(2f).height(56.dp).background(color.compose()))
                }
                Spacer(Modifier.height(8.dp))
                GradientSlider("Hue", hue / 360f, Brush.horizontalGradient(HUE_STOPS)) { hue = it * 360f }
                GradientSlider("Saturation", sat, Brush.horizontalGradient(listOf(HsvColor(hue, 0f, value).compose(), HsvColor(hue, 1f, value).compose()))) { sat = it }
                GradientSlider("Brightness", value, Brush.horizontalGradient(listOf(Color.Black, HsvColor(hue, sat, 1f).compose()))) { value = it }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(color) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A slider drawn over the colour range it picks from, so the hue and the other channels are visible while dragging. */
@Composable
private fun GradientSlider(label: String, value: Float, track: Brush, onChange: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(12.dp)
                .clip(CircleShape)
                .background(track)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            colors = SliderDefaults.colors(
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/** Press-and-hold pads, like the official app's FX panel: the effect runs only while a finger is down. */
@Composable
fun FxPad(speaker: SpeakerController) {
    var held by remember { mutableStateOf<LightFx?>(null) }
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LightFx.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { fx ->
                    val active = held == fx
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(2f)
                            .scale(if (active) 0.96f else 1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                            .semantics { contentDescription = "${fx.label}, press and hold" }
                            .pointerInput(fx) {
                                detectTapGestures(onPress = {
                                    held = fx
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    speaker.holdFx(fx)
                                    tryAwaitRelease()
                                    held = null
                                    speaker.holdFx(null)
                                })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            fx.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}
