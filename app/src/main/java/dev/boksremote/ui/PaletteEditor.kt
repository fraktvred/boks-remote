package dev.boksremote.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.boksremote.speaker.Harmony
import dev.boksremote.speaker.HsvColor
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.PaletteCodec
import dev.boksremote.speaker.PhotoPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val SWATCH_LABELS = listOf("Main 1", "Main 2", "Main 3", "Effect")

internal fun HsvColor.compose() = Color(toArgb())

/** What the editor opens on: [original] is the saved palette being edited, or null for a new one. */
data class PaletteDraft(
    val original: LightPalette?,
    val name: String,
    val colors: List<HsvColor>,
    val swatch: Int = 0,
)

/** Settled colours go to the light only after this long, so a slider drag doesn't send (and maybe store) every step. */
private const val PREVIEW_SETTLE_MS = 350L

/**
 * Makes or edits a palette: the four swatches share one colour picker, and with preview on the
 * light shows the draft as it changes. Closing without saving is [onCancel]; the caller puts back
 * whatever the light showed before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteEditorSheet(
    draft: PaletteDraft,
    /** What the light shows now, so preview doesn't re-send colours it already has. */
    onLight: List<HsvColor>?,
    recentColors: List<HsvColor>,
    onPreview: (List<HsvColor>) -> Unit,
    onPreviewOff: () -> Unit,
    onSave: (name: String, colors: List<HsvColor>, previewed: Boolean) -> Unit,
    onApplyOnce: (List<HsvColor>) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = remember { mutableStateListOf(*draft.colors.toTypedArray()) }
    var selected by remember { mutableIntStateOf(draft.swatch) }
    var name by remember { mutableStateOf(draft.name) }
    var preview by remember { mutableStateOf(true) }
    var lastSent by remember { mutableStateOf(onLight) }
    var editingHex by remember { mutableStateOf(false) }
    val color = colors[selected]
    fun set(c: HsvColor) { colors[selected] = c }
    // Recent colours grow while editing, not only on save. A swatch being tuned owns one entry that each
    // finished change replaces, so dragging a slider adds a colour rather than one per drag.
    var recents by remember { mutableStateOf(recentColors.take(PaletteCodec.MAX_RECENT)) }
    val tuning = remember { mutableStateMapOf<Int, HsvColor>() }
    fun used(c: HsvColor) {
        val rest = recents - setOfNotNull(tuning[selected])
        // A colour already among them only moves to the front, so it isn't this swatch's to replace later.
        val isNew = PaletteCodec.mergeRecent(listOf(c), rest, Int.MAX_VALUE).size > rest.size
        recents = PaletteCodec.mergeRecent(listOf(c), rest, PaletteCodec.MAX_RECENT)
        if (isNew) tuning[selected] = c else tuning.remove(selected)
    }

    val snapshot = colors.toList()
    LaunchedEffect(snapshot, preview) {
        if (!preview) return@LaunchedEffect
        delay(PREVIEW_SETTLE_MS)
        if (snapshot != lastSent) {
            onPreview(snapshot)
            lastSent = snapshot
        }
    }

    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.sheetTopMargin()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Discard changes") }
                Text(
                    if (draft.original == null) "New palette" else "Edit palette",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                SaveButton(
                    onSave = { onSave(name, snapshot, preview) },
                    // A one-off tweak only makes sense for a new palette; an edit always saves in place.
                    onApplyOnce = if (draft.original == null) ({ onApplyOnce(snapshot) }) else null,
                )
            }
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(PaletteCodec.MAX_NAME) },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Preview on the light", style = MaterialTheme.typography.bodyLarge)
                        HintText(if (preview) "The light shows your changes as you make them." else "The light keeps what it shows now.")
                    }
                    Switch(checked = preview, onCheckedChange = { on ->
                        preview = on
                        // The light goes back to what it showed, so turning preview on again re-sends the draft.
                        if (!on) { onPreviewOff(); lastSent = null }
                    })
                }
                PaletteArt(snapshot, Modifier.fillMaxWidth().aspectRatio(2.4f).clip(RoundedCornerShape(16.dp)))
                SwatchRow(snapshot, selected, onSelect = { tuning.clear(); selected = it })
                ColorPicker(color, onChange = ::set, onSettled = { used(colors[selected]) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ControlLabel("Colour code")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editingHex = true }) { Text(color.hex(), fontFamily = FontFamily.Monospace) }
                }
                if (recents.isNotEmpty()) {
                    ControlLabel("Recent colours")
                    // One row that fits a phone's width, newest first.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recents.forEach { c ->
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(c.compose())
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable(onClickLabel = "Use ${c.hex()}") { set(c) },
                            )
                        }
                    }
                }
                Starters(
                    base = color,
                    baseLabel = SWATCH_LABELS[selected],
                    onHarmony = { h ->
                        // Built around the selected colour, which stays in its slot (the Effect slot hands it to Main 1).
                        val slot = selected.coerceAtMost(LightPalette.SIZE - 2)
                        val built = h.from(color).toMutableList().also { it[0] = it[slot]; it[slot] = color }
                        built.forEachIndexed { i, c -> colors[i] = c }
                    },
                    onPhoto = { picked -> picked.forEachIndexed { i, c -> colors[i] = c } },
                )
            }
        }
    }

    if (editingHex) {
        HexDialog(color.hex(), onDismiss = { editingHex = false }) { set(it); used(it); editingHex = false }
    }
}

/** Save, split with a menu holding "Use without saving" when [onApplyOnce] is given. */
@Composable
private fun SaveButton(onSave: () -> Unit, onApplyOnce: (() -> Unit)?) {
    if (onApplyOnce == null) {
        Button(onClick = onSave) { Text("Save") }
        return
    }
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Button(onClick = onSave, shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50, topEndPercent = 15, bottomEndPercent = 15)) {
            Text("Save")
        }
        Box {
            Button(
                onClick = { menu = true },
                shape = RoundedCornerShape(topStartPercent = 15, bottomStartPercent = 15, topEndPercent = 50, bottomEndPercent = 50),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.widthIn(min = 40.dp),
            ) {
                Icon(Icons.Default.ArrowDropDown, contentDescription = "More save options")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Use without saving") },
                    leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { menu = false; onApplyOnce() },
                )
            }
        }
    }
}

/** Ways to fill all four swatches at once: a colour-wheel harmony of the selected colour, or a photo's colours. */
@Composable
private fun Starters(base: HsvColor, baseLabel: String, onHarmony: (Harmony) -> Unit, onPhoto: (List<HsvColor>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var noColour by remember { mutableStateOf(false) }
    // The system photo picker needs no storage permission.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val colors = withContext(Dispatchers.Default) { runCatching { photoColors(context, uri) }.getOrNull() }
            if (colors != null) onPhoto(colors) else noColour = true
        }
    }
    ControlLabel("Generate from $baseLabel")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Harmony.entries.chunked(HARMONY_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { h -> HarmonyTile(h.label, h.from(base), Modifier.weight(1f)) { onHarmony(h) } }
            }
        }
    }
    OutlinedButton(
        onClick = { noColour = false; pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text("From a photo")
    }
    if (noColour) HintText("That photo has hardly any colour in it. Try a more colourful one.")
}

private const val HARMONY_COLUMNS = 3

/** A harmony as a tile: the four colours it would set, in swatch order, over its name. */
@Composable
private fun HarmonyTile(label: String, colors: List<HsvColor>, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = "Use $label", onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(6.dp)).clearAndSetSemantics {}) {
            colors.forEach { c -> Box(Modifier.weight(1f).fillMaxHeight().background(c.compose())) }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Decodes the photo small, since only its colours matter, and picks a palette from it. */
private fun photoColors(context: Context, uri: Uri): List<HsvColor>? {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val scale = PHOTO_SIZE.toFloat() / maxOf(info.size.width, info.size.height)
        if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // hardware bitmaps can't be read pixel by pixel
    }
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    bitmap.recycle()
    return PhotoPalette.extract(pixels)
}

private const val PHOTO_SIZE = 96

/** The four swatches; the selected one is ringed and is what the picker below changes. */
@Composable
private fun SwatchRow(colors: List<HsvColor>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        colors.forEachIndexed { i, c ->
            val isSelected = i == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier
                        .size(56.dp)
                        .border(3.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(c.compose())
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable(onClickLabel = "Edit ${SWATCH_LABELS[i]}") { onSelect(i) }
                        .semantics { this.selected = isSelected },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    SWATCH_LABELS[i],
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else null,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val HUE_STOPS = (0..6).map { Color(HsvColor(it * 60f, 1f, 1f).toArgb()) }

@Composable
private fun ColorPicker(color: HsvColor, onChange: (HsvColor) -> Unit, onSettled: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GradientSlider("Hue", color.hue / 360f, Brush.horizontalGradient(HUE_STOPS), onSettled) { onChange(color.copy(hue = it * 360f)) }
        GradientSlider(
            "Saturation",
            color.saturation,
            Brush.horizontalGradient(listOf(color.copy(saturation = 0f).compose(), color.copy(saturation = 1f).compose())),
            onSettled,
        ) { onChange(color.copy(saturation = it)) }
        GradientSlider(
            "Brightness",
            color.value,
            Brush.horizontalGradient(listOf(Color.Black, color.copy(value = 1f).compose())),
            onSettled,
        ) { onChange(color.copy(value = it)) }
    }
}

/** A slider drawn over the colour range it picks from, so the hue and the other channels are visible while dragging. */
@Composable
private fun GradientSlider(label: String, value: Float, track: Brush, onSettled: () -> Unit, onChange: (Float) -> Unit) {
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
            onValueChangeFinished = onSettled,
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

@Composable
private fun HexDialog(initial: String, onDismiss: () -> Unit, onConfirm: (HsvColor) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val parsed = HsvColor.fromHex(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour code") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(parsed?.compose() ?: Color.Transparent).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(7) },
                    label = { Text("Hex, e.g. #FF2A6D") },
                    singleLine = true,
                    isError = parsed == null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
