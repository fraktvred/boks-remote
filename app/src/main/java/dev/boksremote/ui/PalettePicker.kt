package dev.boksremote.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.boksremote.speaker.HsvColor
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.PaletteCodec
import kotlinx.coroutines.launch

/** Stand-in colours for the "Lightboks default" card: the light picks its own, so show a neutral spectrum. */
internal val DEFAULT_ART = listOf(0f, 120f, 240f, 60f).map { HsvColor(it, 0.75f, 1f) }

/**
 * Blends the four palette colours into a soft mesh: Main 1 is the base, Main 2 and Main 3 bloom
 * from the right-hand and lower corners, and the Effect colour is a smaller accent bottom-right,
 * roughly how they share the light while it runs.
 */
@Composable
internal fun PaletteArt(colors: List<HsvColor>, modifier: Modifier = Modifier) {
    val c = colors.map { Color(it.toArgb()) }
    Box(
        modifier.drawBehind {
            drawRect(c[0])
            fun bloom(color: Color, center: Offset, radius: Float) =
                drawRect(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = center, radius = radius))
            val r = size.maxDimension
            bloom(c[1], Offset(size.width, 0f), r * 0.95f)
            bloom(c[2], Offset(0f, size.height), r * 0.9f)
            bloom(c[3], Offset(size.width, size.height), r * 0.55f)
            bloom(c[0], Offset(0f, 0f), r * 0.45f)
        },
    )
}

/** The four colours as dots on a dark chip, so they stay readable on top of the blended art. */
@Composable
private fun PaletteDots(colors: List<HsvColor>) {
    Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.7f)) {
        Column(Modifier.padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            colors.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { Box(Modifier.size(8.dp).background(Color(it.toArgb()), CircleShape)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteCard(
    name: String,
    colors: List<HsvColor>,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    badge: @Composable () -> Unit = { PaletteDots(colors) },
) {
    val haptics = LocalHapticFeedback.current
    val outline = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(2.dp, outline, RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
                onLongClickLabel = onLongClickLabel,
            )
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(12.dp))) {
            PaletteArt(colors, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { badge() }
            if (selected) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp)) {
                    Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(3.dp))
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        )
    }
}

/** Outlined tile that opens the editor on a new palette. */
@Composable
private fun AddPaletteCard(onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.3f), contentAlignment = Alignment.Center) {
            IconBadge(Icons.Default.Add, size = 44.dp)
        }
        Text("New palette", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp))
    }
}

private enum class PaletteTab(val label: String) { PRESETS("Presets"), YOURS("Your palettes") }

/**
 * Full palette browser. Tapping a card applies it straight away and keeps the sheet open, so the
 * user can audition palettes on the light. Long-press a preset to copy it into the editor, or one
 * of their own to edit, duplicate, rename or delete it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PalettePickerSheet(
    active: LightPalette?,
    activeIsDefault: Boolean,
    saved: List<LightPalette>,
    onApply: (LightPalette) -> Unit,
    onResetDefault: () -> Unit,
    onNew: () -> Unit,
    onEdit: (LightPalette) -> Unit,
    onDuplicate: (LightPalette) -> Unit,
    onRename: (LightPalette, String) -> Unit,
    /** Deletes straight away and returns how to undo it. */
    onDelete: (LightPalette) -> (() -> Unit),
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<LightPalette?>(null) }
    var renaming by remember { mutableStateOf<LightPalette?>(null) }
    // The sheet is its own window above the screen's snackbar, so it has one of its own.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun delete(p: LightPalette) {
        val undo = onDelete(p)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Deleted \"${p.name}\"", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }
    val currentTab = PaletteTab.entries[tab]
    fun isActive(p: LightPalette) = !activeIsDefault && active?.name == p.name

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.sheetTopMargin()) {
        Text("Colour palettes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        PrimaryTabRow(selectedTabIndex = tab) {
            PaletteTab.entries.forEachIndexed { i, t ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(if (t == PaletteTab.YOURS && saved.isNotEmpty()) "${t.label} (${saved.size})" else t.label) },
                )
            }
        }
        Box {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            ) {
                when (currentTab) {
                    PaletteTab.PRESETS -> {
                        item(key = "default") {
                            PaletteCard(
                                name = "Lightboks default",
                                colors = DEFAULT_ART,
                                selected = activeIsDefault,
                                onClick = onResetDefault,
                                badge = {
                                    Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.7f)) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.padding(5.dp).size(16.dp))
                                    }
                                },
                            )
                        }
                        items(LightPalette.PRESETS, key = { "preset:" + it.name }) { p ->
                            Box {
                                PaletteCard(p.name, p.colors, isActive(p), onClick = { onApply(p) }, onLongClick = { menuFor = p }, onLongClickLabel = "Duplicate and edit")
                                DropdownMenu(expanded = menuFor == p, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(
                                        text = { Text("Duplicate & edit") },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                        onClick = { onDuplicate(p); menuFor = null },
                                    )
                                }
                            }
                        }
                    }
                    PaletteTab.YOURS -> {
                        item(key = "add") { AddPaletteCard(onClick = onNew) }
                        items(saved, key = { "saved:" + it.name }) { p ->
                            Box {
                                PaletteCard(p.name, p.colors, isActive(p), onClick = { onApply(p) }, onLongClick = { menuFor = p }, onLongClickLabel = "Edit, duplicate, rename or delete")
                                DropdownMenu(expanded = menuFor == p, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(
                                        text = { Text("Edit") },
                                        leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                        onClick = { onEdit(p); menuFor = null },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Duplicate") },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                        onClick = { onDuplicate(p); menuFor = null },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        onClick = { renaming = p; menuFor = null },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                        onClick = { delete(p); menuFor = null },
                                    )
                                }
                            }
                        }
                        if (saved.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                HintText("Tap New palette to make one, or long-press a preset to start from a copy. Long-press your palettes to edit them.")
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
        Spacer(Modifier.height(8.dp))
    }

    renaming?.let { p ->
        PaletteNameDialog("Rename palette", p.name, "Rename", onDismiss = { renaming = null }) { onRename(p, it); renaming = null }
    }
}

@Composable
internal fun PaletteNameDialog(
    title: String,
    initial: String,
    confirm: String,
    placeholder: String = "My palette",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(PaletteCodec.MAX_NAME) },
                label = { Text("Name") },
                placeholder = { Text(placeholder) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The active palette on the Colours card; tapping it opens [PalettePickerSheet]. */
@Composable
internal fun ActivePaletteBanner(name: String, colors: List<HsvColor>, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaletteArt(colors, Modifier.width(72.dp).height(52.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Browse palettes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
