package dev.boksremote.ui

import android.view.WindowManager

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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.boksremote.speaker.HsvColor
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.PaletteCodec
import kotlinx.coroutines.flow.first
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

/**
 * The dock's toolbar for the palette on the light: its colours lead, so it is plain which palette
 * the buttons act on, then the actions as icons, each named in a tooltip on long-press.
 */
@Composable
private fun QuickActions(palette: LightPalette, actions: @Composable RowScope.() -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp) {
        Row(Modifier.height(56.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PaletteArt(palette.colors, Modifier.size(28.dp).clip(CircleShape))
            Spacer(Modifier.width(4.dp))
            actions()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

/** Names a group of palettes on the [PalettesPage]. */
@Composable
private fun GroupHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(start = 4.dp))
}

/**
 * Every palette on a page of its own: the user's first, then the presets, one list rather than tabs
 * so both are a scroll away. Tapping a card applies it straight away and keeps the page open, so the
 * user can audition palettes on the light. A dock at the bottom, under the thumb, holds New palette
 * and quick actions for the palette on the light: edit, duplicate, rename and delete for one of the
 * user's, a copy to edit for a preset. Long-press any card to reach the same actions without
 * applying it first.
 */
@Composable
fun PalettesPage(
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
    var menuFor by remember { mutableStateOf<LightPalette?>(null) }
    var renaming by remember { mutableStateOf<LightPalette?>(null) }
    // The page is its own window above the screen's snackbar, so it has one of its own.
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
    fun isActive(p: LightPalette) = !activeIsDefault && active?.name == p.name
    val activeOwn = saved.find(::isActive)
    val activePreset = LightPalette.PRESETS.find(::isActive)

    FullScreenPage(
        onDismiss = onDismiss,
        fromSide = true,
        top = {
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Colour palettes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
        },
    ) {
        Box(Modifier.weight(1f).navigationBarsPadding()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                // Room at the end for the button, so it never covers the last row.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "yours", span = { GridItemSpan(maxLineSpan) }) { GroupHeading("Your palettes") }
                if (saved.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        HintText("Tap New palette to make one, or long-press a preset to start from a copy.")
                    }
                }
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
                                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
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
                item(key = "presets", span = { GridItemSpan(maxLineSpan) }) { GroupHeading("Presets", Modifier.padding(top = 12.dp)) }
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
            // Cards fade out under the dock instead of being cut by it.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(136.dp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to MaterialTheme.colorScheme.surface, 1f to MaterialTheme.colorScheme.surface)),
            )
            Row(
                Modifier.align(Alignment.BottomEnd).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val onLight = activeOwn ?: activePreset
                if (onLight != null) {
                    QuickActions(onLight) {
                        if (activeOwn != null) {
                            QuickAction(Icons.Default.Palette, "Edit colours") { onEdit(activeOwn) }
                            QuickAction(Icons.Default.ContentCopy, "Duplicate") { onDuplicate(activeOwn) }
                            QuickAction(Icons.Default.DriveFileRenameOutline, "Rename") { renaming = activeOwn }
                            Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            QuickAction(Icons.Default.Delete, "Delete") { delete(activeOwn) }
                        } else {
                            TextButton(onClick = { onDuplicate(onLight) }) { Text("Duplicate & edit") }
                        }
                    }
                    // Shaped and coloured like the toolbar beside it, so the two read as one dock; New palette alone matches too.
                    FloatingActionButton(
                        onClick = onNew,
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) { Icon(Icons.Default.Add, contentDescription = "New palette") }
                } else {
                    ExtendedFloatingActionButton(
                        onClick = onNew,
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("New palette") },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
        }

        renaming?.let { p ->
            PaletteNameDialog("Rename palette", p.name, "Rename", onDismiss = { renaming = null }) { onRename(p, it); renaming = null }
        }
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
    // Starts selected, so typing replaces the old name and a tap in the field keeps it.
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
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
                onValueChange = { name = it.copy(text = it.text.take(PaletteCodec.MAX_NAME)) },
                label = { Text("Name") },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
