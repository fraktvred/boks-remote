package dev.boksremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.HsvColor
import kotlin.math.roundToInt

/** How the light looks: its brightness, filled in its own colours, over the light mode. */
@Composable
internal fun LightLookCard(
    brightness: Int,
    colors: List<HsvColor>,
    mode: EnergyLevel?,
    onBrightness: (Int) -> Unit,
    onMode: (EnergyLevel) -> Unit,
) {
    // Moves with the thumb while dragging and follows the light otherwise; writes stream while
    // dragging and the controller drops stale ones.
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(brightness / 255f) }
    if (!dragging) local = brightness / 255f
    SectionCard(
        "Brightness",
        Icons.Default.BrightnessMedium,
        trailing = { Text("${(local * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) },
    ) {
        PaletteSlider(
            local,
            colors.map { it.compose() },
            onChange = { dragging = true; local = it; onBrightness((it * 255).roundToInt()) },
            onSettled = { dragging = false },
        )
        if (mode != null) LightModePicker(mode, onMode)
    }
}

private val TRACK_HEIGHT = 16.dp
private val THUMB_SIZE = 28.dp

/**
 * A slider whose filled part is drawn in the light's [colors], left to right, with a thumb filled
 * with the colour under it. The empty part is the plain track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaletteSlider(value: Float, colors: List<Color>, onChange: (Float) -> Unit, onSettled: () -> Unit, modifier: Modifier = Modifier) {
    val empty = tileContainer
    // A gradient needs two stops; a single colour is its own both ends.
    val stops = if (colors.size > 1) colors else List(2) { colors.firstOrNull() ?: Color.White }
    Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onSettled,
        modifier = modifier.semantics { contentDescription = "Brightness" },
        thumb = {
            Box(
                Modifier
                    .size(THUMB_SIZE)
                    .shadow(3.dp, CircleShape)
                    .background(Color.White, CircleShape)
                    .padding(3.dp)
                    .background(colorAt(stops, value), CircleShape),
            )
        },
        track = {
            Box(
                Modifier
                    // The slider spans its track between the thumb's centres; this one reaches out to the thumb's edges.
                    .layout { measurable, constraints ->
                        val reach = (THUMB_SIZE / 2).roundToPx()
                        val width = constraints.maxWidth + 2 * reach
                        val p = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(constraints.maxWidth, p.height) { p.place(-reach, 0) }
                    }
                    .height(TRACK_HEIGHT)
                    .clip(CircleShape)
                    .drawBehind {
                        val inset = THUMB_SIZE.toPx() / 2
                        drawRect(empty)
                        clipRect(right = inset + (size.width - 2 * inset) * value) {
                            drawRect(Brush.horizontalGradient(stops, startX = inset, endX = size.width - inset))
                        }
                    },
            )
        },
    )
}

/** The colour the gradient through [colors] has [fraction] of the way along. */
private fun colorAt(colors: List<Color>, fraction: Float): Color {
    val at = fraction.coerceIn(0f, 1f) * (colors.size - 1)
    val i = at.toInt().coerceAtMost(colors.size - 2)
    return lerp(colors[i], colors[i + 1], at - i)
}
