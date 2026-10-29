package com.sb.phototool.media

import com.sb.phototool.model.CollageJob
import com.sb.phototool.model.CollageMode
import com.sb.phototool.model.PhotoTransformState
import com.sb.phototool.model.TileTransform
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.sqrt

private const val MIN_FRAME_SIDE = .00001f
private const val MIN_RESIZE_LONG_SIDE = .08f

fun validFreeformFrame(frame: NormalizedCell?): Boolean = frame != null &&
    listOf(frame.left, frame.top, frame.right, frame.bottom).all(Float::isFinite) &&
    frame.left >= 0f && frame.top >= 0f && frame.right <= 1f && frame.bottom <= 1f &&
    frame.right - frame.left >= MIN_FRAME_SIDE * .99f &&
    frame.bottom - frame.top >= MIN_FRAME_SIDE * .99f

/** Freeform frames remain inside the canvas so a photo can always be retrieved. */
fun freeformFrame(job: CollageJob, index: Int): NormalizedCell =
    job.tiles[index].freeform?.takeIf(::validFreeformFrame)
        ?: collageCells(job.tiles.size).getOrElse(index) { NormalizedCell(.2f, .2f, .8f, .8f) }

private fun TileTransform.transformState() = PhotoTransformState(zoom, panX, panY, quarterTurns, flipHorizontal, flipVertical)

private fun TileTransform.withTransform(state: PhotoTransformState) = copy(
    zoom = state.zoom, panX = state.panX, panY = state.panY, quarterTurns = state.quarterTurns,
    flipHorizontal = state.flipHorizontal, flipVertical = state.flipVertical,
)

fun initialFreeformFrame(index: Int, count: Int, sourceAspect: Float, canvasAspect: Float): NormalizedCell {
    val columns = ceil(sqrt(count.coerceAtLeast(1).toDouble())).toInt()
    val rows = (count.coerceAtLeast(1) + columns - 1) / columns
    val ratio = safeAspect(sourceAspect) / safeAspect(canvasAspect)
    val maxWidth = .88f / columns
    val maxHeight = .88f / rows
    val width = min(maxWidth, maxHeight * ratio)
    return fitFrame((index % columns + .5f) / columns, (index / columns + .5f) / rows, width, width / ratio)
}

private fun safeAspect(aspect: Float) = aspect.takeIf { it.isFinite() && it > 0 } ?: 1f

fun changeCollageMode(job: CollageJob, mode: CollageMode, canvasAspect: Float = job.ratio.ratio ?: 1f,
    sourceAspects: Map<String, Float> = emptyMap()): CollageJob {
    if (mode == job.mode) return job
    return job.copy(mode = mode, freeformCanvasAspect = safeAspect(canvasAspect), tiles = job.tiles.mapIndexed { index, tile ->
        if (mode == CollageMode.LAYOUT) {
            tile.copy(freeformTransform = tile.transformState()).withTransform(tile.layoutTransform ?: PhotoTransformState())
        } else {
            val existing = tile.freeform?.takeIf(::validFreeformFrame)
            val saved = tile.copy(layoutTransform = tile.transformState())
            if (existing != null) {
                val previousCanvas = job.freeformCanvasAspect ?: canvasAspect
                val restored = saved.withTransform(tile.freeformTransform ?: tile.transformState())
                if (previousCanvas == canvasAspect) restored else restored.copy(freeform = freeformAspect(existing,
                    (existing.right - existing.left) * safeAspect(previousCanvas) / (existing.bottom - existing.top), safeAspect(canvasAspect)))
            }
            else saved.withTransform(PhotoTransformState()).copy(
                freeform = initialFreeformFrame(index, job.tiles.size, sourceAspects[tile.uri] ?: 1f, canvasAspect))
        }
    })
}

private fun fitFrame(centerX: Float, centerY: Float, width: Float, height: Float): NormalizedCell {
    val w = width.coerceIn(MIN_FRAME_SIDE, 1f)
    val h = height.coerceIn(MIN_FRAME_SIDE, 1f)
    val left = (centerX - w / 2).coerceIn(0f, 1f - w)
    val top = (centerY - h / 2).coerceIn(0f, 1f - h)
    return NormalizedCell(left, top, left + w, top + h)
}

/** Coordinates and pan are canvas-normalized; scale is uniform in canvas pixels. */
fun transformFreeformFrame(frame: NormalizedCell, panX: Float, panY: Float,
    zoom: Float, focalX: Float, focalY: Float): NormalizedCell {
    if (!listOf(panX, panY, zoom, focalX, focalY).all(Float::isFinite) || zoom <= 0f) return frame
    val width = frame.right - frame.left
    val height = frame.bottom - frame.top
    val minimum = max(MIN_RESIZE_LONG_SIDE / max(width, height), max(MIN_FRAME_SIDE / width, MIN_FRAME_SIDE / height))
    val factor = zoom.coerceIn(minimum, min(1f / width, 1f / height))
    val cx = focalX + ((frame.left + frame.right) / 2 - focalX) * factor + panX
    val cy = focalY + ((frame.top + frame.bottom) / 2 - focalY) * factor + panY
    return fitFrame(cx, cy, width * factor, height * factor)
}

fun cropFreeformEdge(frame: NormalizedCell, edge: String, delta: Float): NormalizedCell {
    if (!delta.isFinite()) return frame
    return when (edge) {
        "left" -> frame.copy(left = (frame.left + delta).coerceIn(0f, frame.right - MIN_FRAME_SIDE))
        "right" -> frame.copy(right = (frame.right + delta).coerceIn(frame.left + MIN_FRAME_SIDE, 1f))
        "top" -> frame.copy(top = (frame.top + delta).coerceIn(0f, frame.bottom - MIN_FRAME_SIDE))
        "bottom" -> frame.copy(bottom = (frame.bottom + delta).coerceIn(frame.top + MIN_FRAME_SIDE, 1f))
        else -> frame
    }
}

fun freeformAspect(frame: NormalizedCell, photoAspect: Float, canvasAspect: Float): NormalizedCell {
    if (!photoAspect.isFinite() || !canvasAspect.isFinite() || photoAspect <= 0 || canvasAspect <= 0) return frame
    val ratio = photoAspect / canvasAspect
    val longEdge = max((frame.right - frame.left) * canvasAspect, frame.bottom - frame.top)
    var w = if (photoAspect >= 1) longEdge / canvasAspect else longEdge * photoAspect / canvasAspect
    var h = w / ratio
    val factor = max(MIN_FRAME_SIDE / w, MIN_FRAME_SIDE / h).coerceAtLeast(1f)
    w *= factor; h *= factor
    val fit = min(1f / w, 1f / h).coerceAtMost(1f)
    return fitFrame((frame.left + frame.right) / 2, (frame.top + frame.bottom) / 2, w * fit, h * fit)
}

fun restoreFreeformPhoto(tile: TileTransform, frame: NormalizedCell, sourceAspect: Float, canvasAspect: Float): TileTransform {
    val rotatedAspect = if (tile.quarterTurns.mod(2) == 1) 1f / safeAspect(sourceAspect) else safeAspect(sourceAspect)
    return tile.copy(freeform = freeformAspect(frame, rotatedAspect, safeAspect(canvasAspect)), zoom = 1f, panX = 0f, panY = 0f)
}

fun changeFreeformCanvasAspect(job: CollageJob, oldAspect: Float, newAspect: Float): CollageJob =
    if (job.mode != CollageMode.FREEFORM || oldAspect == newAspect) job else job.copy(freeformCanvasAspect = safeAspect(newAspect), tiles = job.tiles.mapIndexed { index, tile ->
        val frame = freeformFrame(job, index)
        val photoAspect = (frame.right - frame.left) * safeAspect(oldAspect) / (frame.bottom - frame.top)
        tile.copy(freeform = freeformAspect(frame, photoAspect, safeAspect(newAspect)))
    })

fun collagePaintOrder(job: CollageJob): List<Int> = if (job.mode == CollageMode.FREEFORM)
    job.tiles.indices.sortedBy { job.tiles[it].layer } else job.tiles.indices.toList()

/** Layer order never changes the source order used by the original canvas aspect ratio. */
fun hitFreeformPhoto(job: CollageJob, x: Float, y: Float): Int = collagePaintOrder(job).asReversed().firstOrNull { i ->
    val rect = freeformFrame(job, i)
    x in rect.left..rect.right && y in rect.top..rect.bottom
} ?: -1

fun moveFreeformLayer(job: CollageJob, index: Int, forward: Boolean): CollageJob {
    val order = collagePaintOrder(job).toMutableList()
    val from = order.indexOf(index)
    val to = from + if (forward) 1 else -1
    if (from < 0 || to !in order.indices) return job
    order.add(to, order.removeAt(from))
    return job.copy(tiles = job.tiles.mapIndexed { i, tile -> tile.copy(layer = order.indexOf(i)) })
}
