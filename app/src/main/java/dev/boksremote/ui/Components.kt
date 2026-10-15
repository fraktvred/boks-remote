package dev.boksremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.boksremote.BuildConfig

/** Icon on a tinted circle; the app's one visual anchor for devices, sections and empty states. */
@Composable
internal fun IconBadge(
    icon: ImageVector,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Surface(shape = CircleShape, color = container, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * A titled card grouping related controls on the device screen. Given [expanded], its header toggles
 * it through [onExpandedChange], and [content] shows the full or the folded version to match.
 */
@Composable
internal fun SectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    expanded: Boolean? = null,
    onExpandedChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardContainer),
    ) {
        Column(Modifier.animateContentSize().padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            // The whole header toggles, but only the chevron shows the press.
            val press = remember { MutableInteractionSource() }
            val header = if (expanded == null) Modifier else Modifier.clickable(
                interactionSource = press,
                indication = null,
                onClickLabel = if (expanded) "Collapse" else "Expand",
            ) { onExpandedChange(!expanded) }
            Row(header, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing()
                if (expanded != null) {
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.indication(press, ripple(bounded = false, radius = 20.dp)),
                    )
                }
            }
            content()
        }
    }
}

/**
 * For a [androidx.compose.material3.ModalBottomSheet]: a tall sheet otherwise expands up under the
 * status bar, so this keeps it below it with a gap that shows the screen behind.
 */
internal fun Modifier.sheetTopMargin() = statusBarsPadding().padding(top = Spacing.l)

/**
 * A page of its own over the device screen, which stays as it was underneath: a full-screen window
 * with [top] as its top bar. [fromSide] slides it in from the right edge and back out to it, like a
 * page navigated to; otherwise it rises a little, like an editor opened on the spot. [top] gets
 * `close`, which takes the page away the way it came before calling [onDismiss], as Back does unless
 * [onBack] handles it, such as a step back through a wizard.
 */
@Composable
internal fun FullScreenPage(
    onDismiss: () -> Unit,
    fromSide: Boolean = false,
    onBack: (() -> Unit)? = null,
    top: @Composable RowScope.(close: () -> Unit) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    // A side page leaves the way it came, and is dismissed once it has gone; an editor just goes.
    val close = { if (fromSide) shown.targetState = false else onDismiss() }
    if (fromSide && shown.isIdle && !shown.currentState) LaunchedEffect(Unit) { onDismiss() }
    Dialog(
        onDismissRequest = onBack ?: close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        // The window's own dialog animation would play on top of the page's.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setWindowAnimations(0) }
        val enter = if (fromSide) slideInHorizontally(tween(PAGE_MS, easing = EaseOutCubic)) { it } else slideInVertically { it / 12 } + fadeIn()
        val exit = slideOutHorizontally(tween(PAGE_MS, easing = EaseInCubic)) { it }
        AnimatedVisibility(shown, enter = enter, exit = exit) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = Spacing.xs, end = Spacing.l), verticalAlignment = Alignment.CenterVertically) { top(close) }
                    content()
                }
            }
        }
    }
}

private const val PAGE_MS = 300

/** A small label above a control inside a [SectionCard]. */
@Composable
internal fun ControlLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Choice(options: List<T>, selected: T?, label: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    val haptics = LocalHapticFeedback.current
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                enabled = enabled,
                onClick = {
                    if (option != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(option)
                },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                // The checkmark icon eats width that labels like "Uplight" need on narrow phones.
                icon = {},
            ) { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Stand-ins from a screenshot build ([BuildConfig.SCREENSHOT_NAMES]), as (part of a name, name shown). */
private val screenshotNames = BuildConfig.SCREENSHOT_NAMES.split(';').mapNotNull { entry ->
    entry.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { (key, shown) -> key.trim() to shown.trim() }
}

/** A device's name as the app shows it: the advertised name, or its stand-in in a screenshot build. */
internal fun shownName(name: String?): String? =
    name?.let { n -> screenshotNames.firstOrNull { it.first in n }?.second ?: n }
