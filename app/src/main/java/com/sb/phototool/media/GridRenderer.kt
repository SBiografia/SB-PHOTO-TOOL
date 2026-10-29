package com.sb.phototool.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

fun drawGridCrop(canvas: Canvas, bitmap: Bitmap, crop: NormalizedCell, width: Float, height: Float) {
    require(validGridCrop(crop)) { "잘라낼 영역을 다시 설정해 주세요." }
    val source = Rect((crop.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1),
        (crop.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1),
        (crop.right * bitmap.width).toInt().coerceIn(1, bitmap.width),
        (crop.bottom * bitmap.height).toInt().coerceIn(1, bitmap.height))
    source.right = source.right.coerceAtLeast(source.left + 1)
    source.bottom = source.bottom.coerceAtLeast(source.top + 1)
    canvas.drawBitmap(bitmap, source, RectF(0f, 0f, width, height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
}
