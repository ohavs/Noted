package com.ohav.noted.design

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Continuous-curvature ("squircle") rounded rectangle. Each corner starts 1.5× the nominal radius
 * away and bends with tight control points, so the curve eases into the straight edge instead of
 * the visible kink a circular arc has. Optical size matches a circular corner of `radius`.
 */
class Squircle(private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val l = min(r * 1.5f, min(size.width, size.height) / 2f)
        val c = l * 0.2f
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(l, 0f)
            lineTo(w - l, 0f)
            cubicTo(w - c, 0f, w, c, w, l)
            lineTo(w, h - l)
            cubicTo(w, h - c, w - c, h, w - l, h)
            lineTo(l, h)
            cubicTo(c, h, 0f, h - c, 0f, h - l)
            lineTo(0f, l)
            cubicTo(0f, c, c, 0f, l, 0f)
            close()
        }
        return Outline.Generic(p)
    }

    override fun equals(other: Any?) = other is Squircle && other.radius == radius
    override fun hashCode() = radius.hashCode()
}

object Shapes {
    val card = Squircle(30.dp)
    val lg = Squircle(26.dp)
    val md = Squircle(18.dp)
    val sm = Squircle(13.dp)
}
