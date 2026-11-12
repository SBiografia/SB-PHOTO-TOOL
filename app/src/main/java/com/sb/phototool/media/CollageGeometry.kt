package com.sb.phototool.media

import com.sb.phototool.model.CollageJob
import com.sb.phototool.model.CollageMode
import com.sb.phototool.model.TileTransform
import kotlin.math.max
import kotlin.math.min

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

fun photoPlacement(sourceWidth: Int, sourceHeight: Int, width: Float, height: Float, tile: TileTransform): PhotoRect {
    val sideways = tile.quarterTurns.mod(2) == 1
    val rotatedWidth = (if (sideways) sourceHeight else sourceWidth).coerceAtLeast(1).toFloat()
    val rotatedHeight = (if (sideways) sourceWidth else sourceHeight).coerceAtLeast(1).toFloat()
    val base = if (tile.fitInside) min(width / rotatedWidth, height / rotatedHeight) else max(width / rotatedWidth, height / rotatedHeight)
    val scale = base * tile.zoom.coerceIn(1f, 3f)
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
): TileTransform {
    if (width <= 0f || height <= 0f) return tile
    val old = photoPlacement(sourceWidth, sourceHeight, width, height, tile)
    val next = tile.copy(zoom = (tile.zoom * zoomChange).coerceIn(1f, 3f))
    val rect = photoPlacement(sourceWidth, sourceHeight, width, height, next)
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
