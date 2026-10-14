package dev.boksremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.boksremote.protocol.Family
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The device's drawing on a round backdrop; the app's visual anchor for a speaker or the Lightboks. */
@Composable
internal fun DeviceBadge(family: Family?, size: Dp = 48.dp) {
    // Partway from the container colour to the surface: darker in the dark theme, lighter in the light one.
    val backdrop = lerp(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface, 0.35f)
    Surface(shape = CircleShape, color = backdrop, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (family == Family.LIGHT) DeviceArt.Lightboks else DeviceArt.Speaker,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(size * 0.14f).fillMaxSize(),
            )
        }
    }
}

/**
 * Flat drawings of the hardware, on a 24-unit grid like Material icons. They are one colour, so they
 * tint like icons: shapes nest inside each other and are filled even-odd, so each ring, hole and dot
 * is one subpath, and gaps are cut out rather than painted over.
 */
internal object DeviceArt {
    private val CENTER = Offset(12f, 12f)

    /**
     * A SOUNDBOKS from the front, drawn like the Lightboks: a rim around a honeycomb standing in for
     * the grille, with a bumper on each corner. Rows of three cells alternate with rows of two plus a
     * half cell at each side, and every gap (between cells, to the rim, along the cut) is the same.
     */
    val Speaker: ImageVector by lazy {
        val dx = 3.2f
        val dy = dx * sqrt(3f) / 2
        val r = 1.36f
        val gap = dx - r * sqrt(3f)
        val cut = dx + r * sqrt(3f) / 2 // where the half cells are cut off
        val inner = Size(cut + gap, 2 * dy + r + gap - 0.1f) // half size; rounded tips stop short of r
        val rim = 1.3f
        val cells = (-2..2).flatMap { row ->
            val shift = if (row % 2 == 0) 0f else 0.5f
            (if (row % 2 == 0) -1..1 else -2..1).map { col -> CENTER + Offset((col + shift) * dx, row * dy) }
        }
        val bumpers = listOf(-1f, 1f).flatMap { sx ->
            listOf(-1f, 1f).map { sy -> CENTER + Offset(sx * (inner.width + 1f), sy * (inner.height + 1f)) }
        }
        ImageVector.Builder("Speaker", 24.dp, 24.dp, 24f, 24f).apply {
            // The rim, with a gap cut around each bumper like the one around the Lightboks' cells.
            group(clipPathData = PathBuilder().apply { canvasWithout(bumpers, 2.0f) }.nodes) {
                shape {
                    roundRect(Size(inner.width + rim, inner.height + rim), 1.6f)
                    roundRect(inner, 0.6f)
                }
            }
            group(clipPathData = PathBuilder().apply { roundRect(Size(cut, inner.height), 0f) }.nodes) {
                shape { cells.forEach { polygon(hexagon(it, r, flatTop = false), corner = 0.3f) } }
            }
            shape { bumpers.forEach { circle(it, 1.5f) } }
        }.build()
    }

    /** The Lightboks from the front: a hexagonal housing around seven hexagonal lens cells. */
    val Lightboks: ImageVector by lazy {
        // Pointy-top cells: one in the middle, six around it in rows of two, three and two.
        val cells = listOf(CENTER) + (0 until 6).map { k ->
            val a = k * PI / 3
            CENTER + Offset(cos(a).toFloat(), sin(a).toFloat()) * 5.7f
        }
        ImageVector.Builder("Lightboks", 24.dp, 24.dp, 24f, 24f).apply {
            shape {
                polygon(hexagon(CENTER, 11.2f, flatTop = true), corner = 1.6f)
                polygon(hexagon(CENTER, 9.9f, flatTop = true), corner = 1.2f)
                cells.forEach { c ->
                    polygon(hexagon(c, 2.85f, flatTop = false), corner = 0.5f)
                    polygon(hexagon(c, 1.85f, flatTop = false), corner = 0.3f)
                    circle(c, 0.9f)
                }
            }
        }.build()
    }
}

private fun ImageVector.Builder.shape(block: PathBuilder.() -> Unit) =
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd, pathBuilder = block)

/** A circle, drawn clockwise unless [clockwise] is false. */
private fun PathBuilder.circle(c: Offset, r: Float, clockwise: Boolean = true) {
    moveTo(c.x - r, c.y)
    arcToRelative(r, r, 0f, true, clockwise, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, clockwise, -2 * r, 0f)
    close()
}

/**
 * The whole canvas minus circles around [holes], for clipping. Clip paths fill non-zero, so the
 * circles run against the canvas's direction to cancel it out.
 */
private fun PathBuilder.canvasWithout(holes: List<Offset>, r: Float) {
    moveTo(0f, 0f)
    horizontalLineTo(24f)
    verticalLineTo(24f)
    horizontalLineTo(0f)
    close()
    holes.forEach { circle(it, r, clockwise = false) }
}

/** A rectangle centred on the canvas, [half] its size in each direction, with corner radius [rad]. */
private fun PathBuilder.roundRect(half: Size, rad: Float) {
    val l = 12f - half.width
    val t = 12f - half.height
    val r = 12f + half.width
    val b = 12f + half.height
    moveTo(l + rad, t)
    horizontalLineTo(r - rad)
    arcTo(rad, rad, 0f, false, true, r, t + rad)
    verticalLineTo(b - rad)
    arcTo(rad, rad, 0f, false, true, r - rad, b)
    horizontalLineTo(l + rad)
    arcTo(rad, rad, 0f, false, true, l, b - rad)
    verticalLineTo(t + rad)
    arcTo(rad, rad, 0f, false, true, l + rad, t)
    close()
}

/** Corners of a regular hexagon with circumradius [r]; flat-top has corners left and right. */
private fun hexagon(c: Offset, r: Float, flatTop: Boolean): List<Offset> = (0 until 6).map { k ->
    val a = k * PI / 3 + if (flatTop) 0.0 else PI / 6
    c + Offset(cos(a).toFloat(), sin(a).toFloat()) * r
}

/** A closed polygon whose corners are rounded off, starting and ending [corner] units along each edge. */
private fun PathBuilder.polygon(points: List<Offset>, corner: Float) {
    fun toward(from: Offset, to: Offset) = lerp(from, to, corner / (to - from).getDistance())
    val start = toward(points[0], points[1])
    moveTo(start.x, start.y)
    points.indices.forEach { i ->
        val v = points[(i + 1) % points.size]
        val before = toward(v, points[i])
        val after = toward(v, points[(i + 2) % points.size])
        lineTo(before.x, before.y)
        quadTo(v.x, v.y, after.x, after.y)
    }
    close()
}
