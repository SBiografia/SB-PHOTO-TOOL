package com.sb.phototool.media

import com.sb.phototool.model.CollageJob
import com.sb.phototool.model.CollageMode
import com.sb.phototool.model.TileTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class PhotoRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

fun collageTileBounds(job: CollageJob, width: Float, height: Float): List<PhotoRect> {
    if (job.mode == CollageMode.FREEFORM) return job.tiles.indices.map { index ->
        freeformFrame(job, index).let { PhotoRect(it.left * width, it.top * height, it.right * width, it.bottom * height) }
    }
    val margin = min(width, height) * job.outerMarginPercent.coerceIn(0f, .12f)
    val innerWidth = width - margin * 2
    val innerHeight = height - margin * 2
    val gap = min(width, height) * job.gapPercent.coerceIn(0f, .08f)
    return resolvedCollageCells(job.tiles.size, job.layoutId, job.customCells).map { cell ->
        // Very small cells keep a positive content area even at the largest gap.
        val inset = min(gap / 2f, min((cell.right - cell.left) * innerWidth, (cell.bottom - cell.top) * innerHeight) * .4f)
        PhotoRect(margin + cell.left * innerWidth + inset, margin + cell.top * innerHeight + inset,
            margin + cell.right * innerWidth - inset, margin + cell.bottom * innerHeight - inset)
    }
}

/** |cos| and |sin| of a turn, or null when the photo is not turned at all (nothing to compensate). */
private fun rotationAxes(degrees: Float): Pair<Float, Float>? {
    if (!degrees.isFinite()) return null
    val turn = ((degrees % 360f) + 360f) % 360f
    if (turn == 0f) return null
    val radians = Math.toRadians(turn.toDouble())
    return abs(cos(radians)).toFloat() to abs(sin(radians)).toFloat()
}

/**
 * How much a turned photo has to grow to keep a fixed cell covered.
 * A photo that only spans the cell leaves the four corner wedges empty as soon as it turns,
 * because the turned rectangle cuts the corners of its own slot.
 */
fun rotationCoverScale(rotation: Float, width: Float, height: Float, imageWidth: Float, imageHeight: Float): Float {
    if (imageWidth <= 0f || imageHeight <= 0f) return 1f
    val (cosine, sine) = rotationAxes(rotation) ?: return 1f
    return max((width * cosine + height * sine) / imageWidth, (width * sine + height * cosine) / imageHeight).coerceAtLeast(1f)
}

/** The matching reduction so a photo shown whole (fitInside) still fits in its cell once it turns. */
fun rotationFitScale(rotation: Float, width: Float, height: Float, imageWidth: Float, imageHeight: Float): Float {
    if (imageWidth <= 0f || imageHeight <= 0f) return 1f
    val (cosine, sine) = rotationAxes(rotation) ?: return 1f
    return min(width / (imageWidth * cosine + imageHeight * sine), height / (imageWidth * sine + imageHeight * cosine)).coerceAtMost(1f)
}

/**
 * [contentRotation] marks a photo whose frame is fixed by the layout cell and only whose content
 * turns: it then has to keep the cell covered (or, with [TileTransform.fitInside], stay inside it).
 * A freeform photo turns together with its own frame, so it needs no compensation.
 */
fun photoPlacement(sourceWidth: Int, sourceHeight: Int, width: Float, height: Float, tile: TileTransform,
    contentRotation: Boolean = false): PhotoRect {
    val sideways = tile.quarterTurns.mod(2) == 1
    val rotatedWidth = (if (sideways) sourceHeight else sourceWidth).coerceAtLeast(1).toFloat()
    val rotatedHeight = (if (sideways) sourceWidth else sourceHeight).coerceAtLeast(1).toFloat()
    val base = if (tile.fitInside) min(width / rotatedWidth, height / rotatedHeight) else max(width / rotatedWidth, height / rotatedHeight)
    val compensation = when {
        !contentRotation -> 1f
        tile.fitInside -> rotationFitScale(tile.frameRotation, width, height, rotatedWidth * base, rotatedHeight * base)
        else -> rotationCoverScale(tile.frameRotation, width, height, rotatedWidth * base, rotatedHeight * base)
    }
    val scale = base * compensation * tile.zoom.coerceIn(1f, 3f)
    val drawWidth = rotatedWidth * scale
    val drawHeight = rotatedHeight * scale
    val extraX = max(0f, (drawWidth - width) / 2f)
    val extraY = max(0f, (drawHeight - height) / 2f)
    val x = (width - drawWidth) / 2 + tile.panX.coerceIn(-1f, 1f) * extraX
    val y = (height - drawHeight) / 2 + tile.panY.coerceIn(-1f, 1f) * extraY
    return PhotoRect(x, y, x + drawWidth, y + drawHeight)
}

fun transformPhoto(
    tile: TileTransform, sourceWidth: Int, sourceHeight: Int, width: Float, height: Float,
    panX: Float, panY: Float, zoomChange: Float, focalX: Float, focalY: Float,
    contentRotation: Boolean = false,
): TileTransform {
    if (width <= 0f || height <= 0f) return tile
    val old = photoPlacement(sourceWidth, sourceHeight, width, height, tile, contentRotation)
    val next = tile.copy(zoom = (tile.zoom * zoomChange).coerceIn(1f, 3f))
    val rect = photoPlacement(sourceWidth, sourceHeight, width, height, next, contentRotation)
    val factor = next.zoom / tile.zoom
    val x = focalX - (focalX - old.left) * factor + panX
    val y = focalY - (focalY - old.top) * factor + panY
    val extraX = max(0f, (rect.width - width) / 2f)
    val extraY = max(0f, (rect.height - height) / 2f)
    return next.copy(
        panX = if (extraX < .001f) 0f else ((x + extraX) / extraX).coerceIn(-1f, 1f),
        panY = if (extraY < .001f) 0f else ((y + extraY) / extraY).coerceIn(-1f, 1f),
    )
}
