package com.sb.phototool.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.sb.phototool.model.TileTransform

/**
 * Preview and export share the crop, rotation, reflection and clipping math.
 *
 * [clipToDestination] is a layout cell: the slot shape is fixed by the layout, so a turned photo
 * turns *inside* it and keeps the whole slot covered. Without it (freeform) the turned shape is the
 * photo's own frame, so the frame turns together with the photo.
 */
fun drawCollagePhoto(
    canvas: Canvas, bitmap: Bitmap, destination: PhotoRect, tile: TileTransform,
    cornerRadius: Float, borderWidth: Float = 0f, borderColor: Int = -1,
    clipToDestination: Boolean = false,
) {
    val bounds = RectF(destination.left, destination.top, destination.right, destination.bottom)
    // A freeform frame turns with its photo; a layout cell never turns, only its content does.
    val frameRotates = !clipToDestination
    val outerSave = canvas.save()
    if (clipToDestination) canvas.clipRect(bounds)
    if (frameRotates) canvas.rotate(tile.frameRotation, bounds.centerX(), bounds.centerY())
    val matte = minOf(bounds.width(), bounds.height()) * tile.matte.coerceIn(0f, .2f)
    val bottom = bounds.height() * tile.bottomMatte.coerceIn(0f, .3f)
    if (matte > 0 || bottom > 0) {
        canvas.drawPath(collageShapePath(tile.shape, bounds, cornerRadius), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tile.frameColor })
        bounds.inset(matte, matte)
        bounds.bottom -= bottom
    }
    val radius = cornerRadius.coerceIn(0f, minOf(bounds.width(), bounds.height()) / 2f)
    val clip = collageShapePath(tile.shape, bounds, radius)
    // Clipping happens in the frame's own space: for a layout cell that is the still-unrotated slot,
    // so the clip never intersects the slot with a rotated copy of itself (the white corner wedges).
    canvas.clipPath(clip)
    val placement = photoPlacement(bitmap.width, bitmap.height, bounds.width(), bounds.height(), tile,
        contentRotation = clipToDestination)
    val rotatedWidth = if (tile.quarterTurns.mod(2) == 1) bitmap.height else bitmap.width
    val scale = placement.width / rotatedWidth
    val photoSave = canvas.save()
    // The layout cell keeps its own angle; only the photo content turns inside it.
    if (clipToDestination) canvas.rotate(tile.frameRotation, bounds.centerX(), bounds.centerY())
    canvas.translate(bounds.left + placement.left + placement.width / 2, bounds.top + placement.top + placement.height / 2)
    canvas.scale(scale * if (tile.flipHorizontal) -1f else 1f, scale * if (tile.flipVertical) -1f else 1f)
    canvas.rotate(tile.quarterTurns.mod(4) * 90f)
    canvas.drawBitmap(bitmap, -bitmap.width / 2f, -bitmap.height / 2f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = photoColorFilter(tile.adjustments)
    })
    canvas.restoreToCount(photoSave)
    if (borderWidth > 0f) {
        canvas.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = borderColor
            strokeWidth = borderWidth * 2f
        })
    }
    canvas.restoreToCount(outerSave)
}
