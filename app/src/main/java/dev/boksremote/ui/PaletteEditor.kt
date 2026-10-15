package dev.boksremote.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Makes or edits a palette on a page of its own: the four swatches share one colour picker, and with
 * preview on the light shows the draft as it changes. Closing without saving is [onCancel], asked
 * first when something changed; the caller puts back whatever the light showed before. Closing a new
 * palette also offers [onApplyOnce], keeping its colours on the light without saving it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteEditorPage(
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

    // Leaving with changes asks first, so a stray back gesture doesn't throw an edit away.
    var confirmDiscard by remember { mutableStateOf(false) }
    val changed = name != draft.name || snapshot != draft.colors
    fun close() = if (changed) confirmDiscard = true else onCancel()

    FullScreenPage(
        onDismiss = ::close,
        top = {
            IconButton(onClick = ::close) { Icon(Icons.Default.Close, contentDescription = "Close") }
            Text(if (draft.original == null) "New palette" else "Edit palette", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
        },
    ) {
        // The page's own window's, not the screen's beneath it, or clearing focus misses the name field.
        val focusManager = LocalFocusManager.current
        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .imePadding()
                    // Touching the controls ends typing the name, so the field doesn't stay focused under them.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            focusManager.clearFocus()
                        }
                    }
                    // Room at the end for the dock, so it never covers the last controls.
                    .padding(start = 24.dp, end = 24.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                Stage(
                    colors = snapshot,
                    selected = selected,
                    onSelect = { tuning.clear(); selected = it },
                    preview = preview,
                    onPreviewChange = { on ->
                        preview = on
                        // The light goes back to what it showed, so turning preview on again re-sends the draft.
                        if (!on) { onPreviewOff(); lastSent = null }
                    },
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(SWATCH_LABELS[selected], style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editingHex = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text(color.hex(), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Edit, contentDescription = "Edit colour code", modifier = Modifier.size(16.dp))
                    }
                }
                ColorPicker(color, onChange = ::set, onSettled = { used(colors[selected]) })
                if (recents.isNotEmpty()) {
                    ControlLabel("Recent colours")
                    // Newest first; the one the swatch has now is ringed.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        recents.forEach { c ->
                            val current = c.hex() == color.hex()
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .border(2.dp, if (current) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                    .padding(4.dp)
                                    .clip(CircleShape)
                                    .background(c.compose())
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable(onClickLabel = "Use ${c.hex()}") { set(c) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
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
            // The name and Save sit under the thumb, and ride up with the keyboard while the name is typed.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                // Controls fade out above the dock instead of being cut by it.
                Box(Modifier.fillMaxWidth().height(24.dp).background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface))))
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Dock(
                        name = name,
                        onNameChange = { name = it.take(PaletteCodec.MAX_NAME) },
                        onSave = { onSave(name, snapshot, preview) },
                    )
                }
            }
        }

        if (editingHex) {
            HexDialog(color.hex(), onDismiss = { editingHex = false }) { set(it); used(it); editingHex = false }
        }
        if (confirmDiscard && draft.original == null) {
            // Leaving a new palette is where its colours' fate is decided: saved, kept on the light as a
            // one-off, or dropped for what the light showed before. Tapping outside keeps editing.
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("Save this palette?") },
                text = { Text("Or keep its colours on the light without saving it.") },
                dismissButton = { TextButton(onClick = { confirmDiscard = false; onCancel() }) { Text("Discard") } },
                confirmButton = {
                    Row {
                        TextButton(onClick = { confirmDiscard = false; onApplyOnce(snapshot) }) { Text("Keep on light") }
                        TextButton(onClick = { confirmDiscard = false; onSave(name, snapshot, preview) }) { Text("Save") }
                    }
                },
            )
        } else if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("Discard changes?") },
                text = { Text("Your changes to ${draft.original?.name} won't be saved.") },
                confirmButton = { TextButton(onClick = { confirmDiscard = false; onCancel() }) { Text("Discard") } },
                dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
            )
        }
    }
}

private val DOCK_HEIGHT = 64.dp
private val DOCK_BUTTON_HEIGHT = 48.dp

/**
 * The bar along the bottom: the palette's name, edited in place behind a pencil, and Save inside the
 * same pill. Using a new palette without saving it is offered on leaving instead.
 */
@Composable
private fun Dock(name: String, onNameChange: (String) -> Unit, onSave: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = scheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, if (focused) scheme.primary else Color.Transparent),
    ) {
        Row(Modifier.fillMaxWidth().height(DOCK_HEIGHT).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                interactionSource = interaction,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Palette name" },
                decorationBox = { field ->
                    Box {
                        if (name.isEmpty()) Text("Name this palette", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                        field()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSave, contentPadding = PaddingValues(horizontal = 24.dp), modifier = Modifier.height(DOCK_BUTTON_HEIGHT)) { Text("Save") }
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

private val SWATCH_SIZE = 52.dp
private val SWATCH_SELECTED_SIZE = 64.dp
/** The ring that cuts each swatch out of the art, in the page's own colour. */
private val SWATCH_CUTOUT = 4.dp

/**
 * The draft as the light would show it, with the four swatches sitting on its lower edge: tapping
 * one picks what the controls below change. The pill on top turns the preview on the light on and off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Stage(colors: List<HsvColor>, selected: Int, onSelect: (Int) -> Unit, preview: Boolean, onPreviewChange: (Boolean) -> Unit) {
    val sheet = MaterialTheme.colorScheme.surface
    Column {
        Box {
            Box(Modifier.padding(bottom = SWATCH_SELECTED_SIZE / 2)) {
                PaletteArt(colors, Modifier.fillMaxWidth().aspectRatio(2.1f).clip(RoundedCornerShape(20.dp)))
                LivePill(preview, onPreviewChange, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(SWATCH_SELECTED_SIZE), verticalAlignment = Alignment.CenterVertically) {
                colors.forEachIndexed { i, c ->
                    val isSelected = i == selected
                    val size by animateDpAsState(if (isSelected) SWATCH_SELECTED_SIZE else SWATCH_SIZE, label = "swatch")
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(size)
                                .background(sheet, CircleShape)
                                .padding(SWATCH_CUTOUT)
                                .border(3.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                .padding(if (isSelected) 5.dp else 0.dp)
                                .clip(CircleShape)
                                .background(c.compose())
                                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                                .clickable(onClickLabel = "Edit ${SWATCH_LABELS[i]}") { onSelect(i) }
                                .semantics { this.selected = isSelected; contentDescription = SWATCH_LABELS[i] },
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            SWATCH_LABELS.forEachIndexed { i, label ->
                val isSelected = i == selected
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else null,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clearAndSetSemantics {},
                )
            }
        }
    }
}

/** Whether the light follows the draft, on a dark chip so it reads over any palette. */
@Composable
private fun LivePill(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (on) Color.White else Color.Transparent, CircleShape)
                .border(1.5.dp, Color.White.copy(alpha = if (on) 1f else 0.7f), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (on) "Live on the light" else "Preview off",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = if (on) 1f else 0.8f),
        )
    }
}

private val HUE_STOPS = (0..6).map { Color(HsvColor(it * 60f, 1f, 1f).toArgb()) }

@Composable
private fun ColorPicker(color: HsvColor, onChange: (HsvColor) -> Unit, onSettled: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        GradientSlider("Hue", "${color.hue.roundToInt()}°", color.hue / 360f, HUE_STOPS, color, onSettled) { onChange(color.copy(hue = it * 360f)) }
        GradientSlider(
            "Saturation",
            "${(color.saturation * 100).roundToInt()}%",
            color.saturation,
            listOf(color.copy(saturation = 0f).compose(), color.copy(saturation = 1f).compose()),
            color,
            onSettled,
        ) { onChange(color.copy(saturation = it)) }
        GradientSlider(
            "Brightness",
            "${(color.value * 100).roundToInt()}%",
            color.value,
            listOf(Color.Black, color.copy(value = 1f).compose()),
            color,
            onSettled,
        ) { onChange(color.copy(value = it)) }
    }
}

private val TRACK_HEIGHT = 24.dp
private val THUMB_SIZE = 30.dp

/**
 * A slider drawn over the colour range it picks from, with a thumb filled with the colour being made.
 * The gradient runs between the thumb's two end positions, so the colour under the thumb is the one it picks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradientSlider(label: String, reading: String, value: Float, stops: List<Color>, color: HsvColor, onSettled: () -> Unit, onChange: (Float) -> Unit) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val drag = remember { MutableInteractionSource() }
    val dragged by drag.collectIsDraggedAsState()
    val pressed by drag.collectIsPressedAsState()
    val grow by animateFloatAsState(if (dragged || pressed) 1.15f else 1f, label = "thumb")
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(reading, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface)
    }
    Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onSettled,
        interactionSource = drag,
        modifier = Modifier.semantics { contentDescription = label },
        thumb = {
            Box(
                Modifier
                    .size(THUMB_SIZE)
                    .graphicsLayer { scaleX = grow; scaleY = grow }
                    .shadow(3.dp, CircleShape)
                    .background(Color.White, CircleShape)
                    .padding(3.dp)
                    .background(color.compose(), CircleShape),
            )
        },
        track = {
            Box(
                Modifier
                    // The slider spans its track between the thumb's centres; this one reaches out to the thumb's edges.
                    .layout { measurable, constraints ->
                        val reach = (THUMB_SIZE / 2).roundToPx()
                        val p = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * reach, maxWidth = constraints.maxWidth + 2 * reach))
                        layout(constraints.maxWidth, p.height) { p.place(-reach, 0) }
                    }
                    .height(TRACK_HEIGHT)
                    .clip(CircleShape)
                    .drawBehind {
                        val inset = THUMB_SIZE.toPx() / 2
                        drawRect(Brush.horizontalGradient(stops, startX = inset, endX = size.width - inset))
                    }
                    .border(1.dp, outline, CircleShape),
            )
        },
    )
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
