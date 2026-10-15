package dev.boksremote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The app's look: the phone's own colours, and the tokens below that every screen draws with, so the
 * same kind of thing looks the same wherever it is.
 */
@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) {
        // A charcoal page rather than near-black, so page, cards and the tiles on them climb in even steps.
        dynamicDarkColorScheme(context).let { val page = lerp(it.surface, it.surfaceContainerLow, 0.5f); it.copy(background = page, surface = page) }
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** The gaps and paddings between and around things. A value off this scale is a deliberate exception. */
internal object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    /** Around a page's own content, such as an empty state. */
    val xxl = 32.dp
}

/** Corner radii, by the size of what they round. */
internal object Corners {
    /** Palette thumbnails, and labels and pills drawn over art. */
    val small = 8.dp
    /** Tiles, rows, and cards within a card or sheet. */
    val medium = 12.dp
    /** Large tiles and pads, and palette art shown big. */
    val large = 16.dp
}

private val isDark: Boolean
    @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * The cards on the device list and device screens, and on full-screen pages, a clear step above the
 * page. In the dark theme the page (see [AppTheme]), a card and the [tileContainer]s on it climb in
 * even steps of the same tinted grey.
 */
internal val cardContainer: Color
    @Composable get() = MaterialTheme.colorScheme.run { if (isDark) surfaceContainerHigh else surfaceContainer }

/** A tile, track or inner card on a card, the next step above [cardContainer]. */
internal val tileContainer: Color
    @Composable get() = MaterialTheme.colorScheme.run { if (isDark) surfaceBright else surfaceContainerHighest }

/** Something live and well, such as DMX arriving: a green that reads on either theme. */
internal val liveColor: Color
    @Composable get() = if (isDark) Color(0xFF81C995) else Color(0xFF2E7D32)

/** Dark backings for what is drawn over palette art, so it reads over any colours. */
internal object Scrim {
    /** Behind chips and pills. */
    val chip = Color.Black.copy(alpha = 0.55f)
    /** Behind small labels, which need more contrast than a chip. */
    val label = Color.Black.copy(alpha = 0.7f)
    /** Across a cover, darkest where its text starts. */
    val cover = listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.22f))
}

/** What is drawn on palette art, over a [Scrim]: text, and the quieter text and lines beside it. */
internal object OnArt {
    val text = Color.White
    val subtle = Color.White.copy(alpha = 0.8f)
    val hairline = Color.White.copy(alpha = 0.18f)
}
