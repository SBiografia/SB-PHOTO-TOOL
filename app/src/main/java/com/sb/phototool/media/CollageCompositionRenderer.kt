package com.sb.phototool.media

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.sb.phototool.model.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

object CollageFonts {
    private val fonts = ConcurrentHashMap<CollageFont, Typeface>()
    fun get(context: Context, font: CollageFont): Typeface = fonts.getOrPut(font) { Typeface.createFromAsset(context.assets, font.asset) }
}

fun drawCollageBackground(canvas: Canvas, job: CollageJob, width: Float, height: Float, image: Bitmap? = null) {
    canvas.drawColor(job.backgroundColor)
    val bg = job.background
    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    when (bg.kind) {
        BackgroundKind.SOLID -> Unit
        BackgroundKind.GRADIENT -> {
            val a = Math.toRadians(bg.angle.toDouble()); val dx = cos(a).toFloat() * width / 2; val dy = sin(a).toFloat() * height / 2
            p.shader = LinearGradient(width / 2 - dx, height / 2 - dy, width / 2 + dx, height / 2 + dy,
                job.backgroundColor, bg.secondaryColor, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width, height, p)
        }
        BackgroundKind.PHOTO, BackgroundKind.BLUR -> if (image != null) {
            val scale = max(width / image.width, height / image.height)
            val w = image.width * scale; val h = image.height * scale
            canvas.drawBitmap(image, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), p)
        }
        BackgroundKind.PATTERN -> {
            val unit = min(width, height) / 24f
            p.color = bg.secondaryColor; p.alpha = if (bg.pattern == BackgroundPattern.PAPER || bg.pattern == BackgroundPattern.LINEN) 38 else 85
            p.strokeWidth = min(width, height) / 900f
            when (bg.pattern) {
                BackgroundPattern.PAPER -> {
                    val random = java.util.Random(23891)
                    repeat(2600) { canvas.drawCircle(random.nextFloat() * width, random.nextFloat() * height, min(width, height) / 1400f, p) }
                }
                BackgroundPattern.LINEN -> {
                    var x = 0f; while (x < width) { canvas.drawLine(x, 0f, x, height, p); x += unit / 7 }
                    var y = 0f; while (y < height) { canvas.drawLine(0f, y, width, y, p); y += unit / 7 }
                }
                BackgroundPattern.DOTS -> { var y = unit / 2; while (y < height) { var x = unit / 2; while (x < width) { canvas.drawCircle(x, y, unit * .13f, p); x += unit }; y += unit } }
                BackgroundPattern.GRID -> {
                    var x = 0f; while (x < width) { canvas.drawLine(x, 0f, x, height, p); x += unit }
                    var y = 0f; while (y < height) { canvas.drawLine(0f, y, width, y, p); y += unit }
                }
                BackgroundPattern.CHECK -> { var row = 0; var y = 0f; while (y < height) { var column = 0; var x = 0f; while (x < width) { if ((row + column) % 2 == 0) canvas.drawRect(x, y, x + unit, y + unit, p); column++; x += unit }; row++; y += unit } }
                BackgroundPattern.STRIPES -> { var x = -height; p.strokeWidth = unit * .32f; while (x < width) { canvas.drawLine(x, 0f, x + height, height, p); x += unit * 1.5f } }
            }
        }
    }
}

fun drawCollageText(canvas: Canvas, text: CollageText, width: Float, height: Float, typeface: Typeface) {
    if (text.text.isBlank()) return
    val f = text.frame; val rect = RectF(f.left * width, f.top * height, f.right * width, f.bottom * height)
    val size = min(width, height) * text.size.coerceIn(.012f, .3f)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = Typeface.create(typeface, when { text.bold && text.italic -> Typeface.BOLD_ITALIC; text.bold -> Typeface.BOLD; text.italic -> Typeface.ITALIC; else -> Typeface.NORMAL })
        textSize = size; color = text.color; isUnderlineText = text.underline; isStrikeThruText = text.strikeThrough
        letterSpacing = text.letterSpacing.coerceIn(-.05f, .3f)
    }
    val alignment = when (text.alignment) { TextAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL; TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER; TextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE }
    val layout = StaticLayout.Builder.obtain(text.text, 0, text.text.length, paint, rect.width().toInt().coerceAtLeast(1))
        .setAlignment(alignment).setIncludePad(false).setLineSpacing(0f, text.lineSpacing.coerceIn(.7f, 2f))
        .setMaxLines(16).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE).build()
    val saved = canvas.save()
    canvas.rotate(text.rotation, rect.centerX(), rect.centerY())
    canvas.clipRect(rect)
    canvas.translate(rect.left, rect.top + max(0f, (rect.height() - layout.height) / 2))
    if (text.outline > 0f) {
        paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = size * text.outline.coerceIn(0f, .15f) * 2; paint.color = text.outlineColor
        layout.draw(canvas)
    }
    paint.style = Paint.Style.FILL; paint.color = text.color
    if (text.shadow) paint.setShadowLayer(size * .08f, size * .035f, size * .05f, 0x99000000.toInt())
    layout.draw(canvas)
    canvas.restoreToCount(saved)
}

fun drawCollageDecoration(canvas: Canvas, d: CollageDecoration, width: Float, height: Float) {
    val r = RectF(d.frame.left * width, d.frame.top * height, d.frame.right * width, d.frame.bottom * height)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = d.color; alpha = (Color.alpha(d.color) * d.opacity.coerceIn(0f, 1f)).toInt() }
    val saved = canvas.save()
    canvas.rotate(d.rotation, r.centerX(), r.centerY())
    when (d.kind) {
        DecorationKind.PAPER -> canvas.drawPath(collageShapePath(PhotoShape.TORN, r), p)
        DecorationKind.TAPE -> { canvas.drawPath(collageShapePath(PhotoShape.TORN, r), p); p.alpha = p.alpha / 5; p.strokeWidth = r.height() / 15; for (i in 1..5) canvas.drawLine(r.left, r.top + r.height() * i / 6, r.right, r.top + r.height() * i / 6, p) }
        DecorationKind.LINE -> canvas.drawRect(r, p)
        DecorationKind.HEART -> canvas.drawPath(collageShapePath(PhotoShape.HEART, r), p)
        DecorationKind.STAR -> canvas.drawPath(collageShapePath(PhotoShape.STAR, r), p)
        DecorationKind.LEAF -> {
            val path = Path().apply { moveTo(r.left, r.bottom); cubicTo(r.left, r.top, r.right, r.top, r.right, r.top); cubicTo(r.right, r.bottom, r.left, r.bottom, r.left, r.bottom) }
            canvas.drawPath(path, p); p.color = 0x55FFFFFF; p.strokeWidth = r.width() * .025f; canvas.drawLine(r.left, r.bottom, r.right, r.top, p)
        }
        DecorationKind.FLOWER -> { val radius = min(r.width(), r.height()) / 4; repeat(6) { val a = it * Math.PI / 3; canvas.drawCircle(r.centerX() + cos(a).toFloat() * radius, r.centerY() + sin(a).toFloat() * radius, radius * .8f, p) }; p.color = 0xFFE0B567.toInt(); canvas.drawCircle(r.centerX(), r.centerY(), radius * .6f, p) }
        DecorationKind.BUTTON -> { val radius = min(r.width(), r.height()) / 2; canvas.drawCircle(r.centerX(), r.centerY(), radius, p); p.color = 0x99FFFFFF.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = radius * .08f; canvas.drawCircle(r.centerX(), r.centerY(), radius * .8f, p); p.style = Paint.Style.FILL; repeat(4) { canvas.drawCircle(r.centerX() + (if (it % 2 == 0) -1 else 1) * radius * .24f, r.centerY() + (if (it < 2) -1 else 1) * radius * .24f, radius * .09f, p) } }
        DecorationKind.FILM -> { val steps = 24; repeat(steps) { val y = r.top + r.height() * it / steps; canvas.drawRoundRect(r.left, y, r.right, y + r.height() / steps * .5f, r.width() * .15f, r.width() * .15f, p) } }
        DecorationKind.CLIP -> { p.style = Paint.Style.STROKE; p.strokeWidth = min(r.width(), r.height()) * .12f; canvas.drawRoundRect(r, r.width() / 2, r.width() / 2, p); val inner = RectF(r).apply { inset(r.width() * .25f, r.height() * .17f) }; canvas.drawRoundRect(inner, inner.width() / 2, inner.width() / 2, p) }
    }
    canvas.restoreToCount(saved)
}

/** A shared scene order is used for both the interactive canvas and saved JPEG. */
fun drawCollageComposition(canvas: Canvas, job: CollageJob, width: Float, height: Float,
    photo: (String) -> Bitmap?, background: Bitmap? = null, font: (CollageFont) -> Typeface = { Typeface.DEFAULT },
    release: (Bitmap) -> Unit = {}, emptySlots: Boolean = false) {
    drawCollageBackground(canvas, job, width, height, background)
    val bounds = collageTileBounds(job, width, height)
    for (e in collageDesignElements(job).sortedBy { it.layer }) {
        when (e.kind) {
            DesignElementKind.PHOTO -> {
                val tile = job.tiles[e.index]
                val bitmap = if (tile.uri.isBlank()) null else photo(tile.uri)
                val r = bounds[e.index]
                if (bitmap != null) {
                    try { drawCollagePhoto(canvas, bitmap, r, tile, min(width, height) * job.cornerPercent, min(width, height) * job.borderWidthPercent, job.borderColor,
                        clipToDestination = job.mode == CollageMode.LAYOUT) }
                    finally { release(bitmap) }
                } else if (emptySlots) {
                    val saved = canvas.save(); canvas.rotate(tile.frameRotation, (r.left + r.right) / 2, (r.top + r.bottom) / 2)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE1E2E3.toInt() }
                    canvas.drawPath(collageShapePath(tile.shape, RectF(r.left, r.top, r.right, r.bottom)), p)
                    p.color = if (tile.uri.isBlank()) 0xFF9C9FA4.toInt() else 0xFFC26868.toInt()
                    p.strokeWidth = min(r.width, r.height) * .025f; p.strokeCap = Paint.Cap.ROUND
                    val arm = min(r.width, r.height) * .07f; val x = (r.left + r.right) / 2; val y = (r.top + r.bottom) / 2
                    canvas.drawLine(x - arm, y, x + arm, y, p); canvas.drawLine(x, y - arm, x, y + arm, p)
                    canvas.restoreToCount(saved)
                }
            }
            DesignElementKind.TEXT -> drawCollageText(canvas, job.texts[e.index], width, height, font(job.texts[e.index].font))
            DesignElementKind.DECORATION -> drawCollageDecoration(canvas, job.decorations[e.index], width, height)
        }
    }
}

/** Fixed-size blur keeps background working memory independent of photo resolution. */
fun blurredCollageBackground(source: Bitmap, strength: Float): Bitmap {
    val scale = 320f / max(source.width, source.height)
    val w = (source.width * scale.coerceAtMost(1f)).toInt().coerceAtLeast(1)
    val h = (source.height * scale.coerceAtMost(1f)).toInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(source, w, h, true)
    val pixels = IntArray(w * h); small.getPixels(pixels, 0, w, 0, 0, w, h)
    if (small !== source) small.recycle()
    val temp = IntArray(pixels.size); val radius = (strength.coerceIn(0f, 1f) * 18 + 2).toInt()
    repeat(2) {
        for (y in 0 until h) {
            var r = 0; var g = 0; var b = 0
            for (k in -radius..radius) { val c = pixels[y * w + k.coerceIn(0, w - 1)]; r += Color.red(c); g += Color.green(c); b += Color.blue(c) }
            val n = radius * 2 + 1
            for (x in 0 until w) {
                temp[y * w + x] = Color.rgb(r / n, g / n, b / n)
                val a = pixels[y * w + (x - radius).coerceIn(0, w - 1)]; val z = pixels[y * w + (x + radius + 1).coerceIn(0, w - 1)]
                r += Color.red(z) - Color.red(a); g += Color.green(z) - Color.green(a); b += Color.blue(z) - Color.blue(a)
            }
        }
        for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (k in -radius..radius) { val c = temp[k.coerceIn(0, h - 1) * w + x]; r += Color.red(c); g += Color.green(c); b += Color.blue(c) }
            val n = radius * 2 + 1
            for (y in 0 until h) {
                pixels[y * w + x] = Color.rgb(r / n, g / n, b / n)
                val a = temp[(y - radius).coerceIn(0, h - 1) * w + x]; val z = temp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                r += Color.red(z) - Color.red(a); g += Color.green(z) - Color.green(a); b += Color.blue(z) - Color.blue(a)
            }
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}
