package dev.boksremote.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.HsvColor

/** How the light looks: its brightness, filled in its own colours, over the light mode. */
@Composable
internal fun LightLookCard(
    brightness: Int,
    colors: List<HsvColor>,
    mode: EnergyLevel?,
    onBrightness: (Int) -> Unit,
    onMode: (EnergyLevel) -> Unit,
) {
    val level = rememberLevelDrag(brightness, onBrightness)
    SectionCard(
        "Brightness",
        Icons.Default.BrightnessMedium,
        trailing = { Text(level.label(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) },
    ) {
        PaletteSlider(level.fraction, colors.map { it.compose() }, level::drag, level::settle)
        if (mode != null) LightModePicker(mode, onMode)
    }
}

/**
 * A slider whose filled part is drawn in the light's [colors], left to right, with a thumb filled
 * with the colour under it. The empty part is the plain track.
 */
@Composable
internal fun PaletteSlider(value: Float, colors: List<Color>, onChange: (Float) -> Unit, onSettled: () -> Unit, modifier: Modifier = Modifier) {
    val empty = tileContainer
    // A gradient needs two stops; a single colour is its own both ends.
    val stops = if (colors.size > 1) colors else List(2) { colors.firstOrNull() ?: Color.White }
    ColorTrackSlider(value, onChange, onSettled, colorAt(stops, value), "Brightness", modifier) { inset ->
        drawRect(empty)
        clipRect(right = inset + (size.width - 2 * inset) * value) {
            drawRect(Brush.horizontalGradient(stops, startX = inset, endX = size.width - inset))
        }
    }
}

/** The colour the gradient through [colors] has [fraction] of the way along. */
private fun colorAt(colors: List<Color>, fraction: Float): Color {
    val at = fraction.coerceIn(0f, 1f) * (colors.size - 1)
    val i = at.toInt().coerceAtMost(colors.size - 2)
    return lerp(colors[i], colors[i + 1], at - i)
}
