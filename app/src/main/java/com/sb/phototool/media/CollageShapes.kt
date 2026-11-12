package com.sb.phototool.media

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import com.sb.phototool.model.PhotoShape
import kotlin.math.cos
import kotlin.math.sin

fun collageShapePath(shape: PhotoShape, bounds: RectF, cornerRadius: Float = 0f): Path {
    val p = Path()
    when (shape) {
        PhotoShape.RECTANGLE -> p.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        PhotoShape.ROUNDED -> p.addRoundRect(bounds, bounds.width().coerceAtMost(bounds.height()) * .12f,
            bounds.width().coerceAtMost(bounds.height()) * .12f, Path.Direction.CW)
        PhotoShape.CIRCLE -> p.addCircle(bounds.centerX(), bounds.centerY(), minOf(bounds.width(), bounds.height()) / 2, Path.Direction.CW)
        PhotoShape.OVAL -> p.addOval(bounds, Path.Direction.CW)
        else -> {
            when (shape) {
                PhotoShape.HEART -> {
                    p.moveTo(.5f, 1f); p.cubicTo(.4f, .84f, 0f, .58f, 0f, .28f)
                    p.cubicTo(0f, -.02f, .36f, -.1f, .5f, .19f); p.cubicTo(.64f, -.1f, 1f, -.02f, 1f, .28f)
                    p.cubicTo(1f, .58f, .6f, .84f, .5f, 1f)
                }
                PhotoShape.STAR -> for (i in 0 until 10) {
                    val a = Math.PI * i / 5 - Math.PI / 2
                    val r = if (i % 2 == 0) .5 else .235
                    val x = (.5 + cos(a) * r).toFloat(); val y = (.5 + sin(a) * r).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                PhotoShape.TRIANGLE -> { p.moveTo(.5f, 0f); p.lineTo(1f, 1f); p.lineTo(0f, 1f) }
                PhotoShape.DIAMOND -> { p.moveTo(.5f, 0f); p.lineTo(1f, .5f); p.lineTo(.5f, 1f); p.lineTo(0f, .5f) }
                PhotoShape.HEXAGON -> { p.moveTo(.25f, 0f); p.lineTo(.75f, 0f); p.lineTo(1f, .5f); p.lineTo(.75f, 1f); p.lineTo(.25f, 1f); p.lineTo(0f, .5f) }
                PhotoShape.ARCH -> { p.moveTo(0f, 1f); p.lineTo(0f, .42f); p.cubicTo(0f, -.14f, 1f, -.14f, 1f, .42f); p.lineTo(1f, 1f) }
                PhotoShape.SLANT -> { p.moveTo(.13f, 0f); p.lineTo(1f, 0f); p.lineTo(.87f, 1f); p.lineTo(0f, 1f) }
                PhotoShape.WAVE -> { p.moveTo(0f, .07f); p.cubicTo(.3f, -.1f, .65f, .2f, 1f, .04f); p.lineTo(1f, .94f); p.cubicTo(.65f, 1.1f, .35f, .8f, 0f, .97f) }
                PhotoShape.TORN -> {
                    p.moveTo(0f, .025f)
                    for (i in 1..24) p.lineTo(i / 24f, if (i % 2 == 0) .008f else .035f)
                    for (i in 1..24) p.lineTo(if (i % 2 == 0) .995f else .973f, i / 24f)
                    for (i in 23 downTo 0) p.lineTo(i / 24f, if (i % 2 == 0) .992f else .965f)
                    for (i in 23 downTo 1) p.lineTo(if (i % 2 == 0) .005f else .027f, i / 24f)
                }
                else -> Unit
            }
            p.close()
            p.transform(Matrix().apply { setScale(bounds.width(), bounds.height()); postTranslate(bounds.left, bounds.top) })
        }
    }
    return p
}

fun shapeContains(shape: PhotoShape, width: Float, height: Float, x: Float, y: Float): Boolean {
    if (x < 0f || y < 0f || x > width || y > height || width <= 0f || height <= 0f) return false
    val scale = 1000f / maxOf(width, height)
    val w = (width * scale).toInt().coerceAtLeast(1); val h = (height * scale).toInt().coerceAtLeast(1)
    val region = Region().apply { setPath(collageShapePath(shape, RectF(0f, 0f, w.toFloat(), h.toFloat())), Region(0, 0, w + 1, h + 1)) }
    return region.contains((x * scale).toInt(), (y * scale).toInt())
}
