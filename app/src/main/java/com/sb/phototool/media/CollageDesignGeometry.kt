package com.sb.phototool.media

import com.sb.phototool.model.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class DesignElementKind { PHOTO, TEXT, DECORATION }
data class DesignElement(val id: String, val kind: DesignElementKind, val index: Int, val frame: NormalizedCell, val rotation: Float, val layer: Int)
enum class DesignAlignment(val label: String) { LEFT("왼쪽"), CENTER_X("가로 중앙"), RIGHT("오른쪽"), TOP("위쪽"), CENTER_Y("세로 중앙"), BOTTOM("아래쪽"), SPACE_X("가로 간격"), SPACE_Y("세로 간격") }
data class SnapResult(val frame: NormalizedCell, val x: Float? = null, val y: Float? = null)

fun photoElementId(tile: TileTransform, index: Int) = tile.elementId.ifBlank { "photo-" + index }
fun collageDesignElements(job: CollageJob): List<DesignElement> {
    val cells = if (job.mode == CollageMode.LAYOUT) resolvedCollageCells(job.tiles.size, job.layoutId, job.customCells) else emptyList()
    return job.tiles.mapIndexed { index, tile -> DesignElement(photoElementId(tile, index), DesignElementKind.PHOTO, index,
        cells.getOrNull(index) ?: freeformFrame(job, index), if (job.mode == CollageMode.LAYOUT) 0f else tile.frameRotation,
        if (job.mode == CollageMode.LAYOUT) index else tile.layer) } +
        job.texts.mapIndexed { i, t -> DesignElement(t.id, DesignElementKind.TEXT, i, t.frame, t.rotation, t.layer) } +
        job.decorations.mapIndexed { i, d -> DesignElement(d.id, DesignElementKind.DECORATION, i, d.frame, d.rotation, d.layer) }
}

fun updateDesignElement(job: CollageJob, id: String, frame: NormalizedCell? = null, rotation: Float? = null, layer: Int? = null): CollageJob {
    val element = collageDesignElements(job).firstOrNull { it.id == id } ?: return job
    if (element.kind == DesignElementKind.PHOTO && job.tiles[element.index].locked) return job
    return when (element.kind) {
        DesignElementKind.PHOTO -> job.copy(tiles = job.tiles.mapIndexed { i, p -> if (i != element.index) p else p.copy(
            freeform = frame ?: p.freeform, frameRotation = rotation ?: p.frameRotation, layer = layer ?: p.layer) })
        DesignElementKind.TEXT -> job.copy(texts = job.texts.map { if (it.id != id) it else it.copy(frame = frame ?: it.frame, rotation = rotation ?: it.rotation, layer = layer ?: it.layer) })
        DesignElementKind.DECORATION -> job.copy(decorations = job.decorations.map { if (it.id != id) it else it.copy(frame = frame ?: it.frame, rotation = rotation ?: it.rotation, layer = layer ?: it.layer) })
    }
}

fun removeDesignElements(job: CollageJob, ids: Set<String>) = job.copy(
    tiles = job.tiles.filterIndexed { i, t -> t.locked || photoElementId(t, i) !in ids },
    texts = job.texts.filter { it.id !in ids }, decorations = job.decorations.filter { it.id !in ids },
    customCells = if (job.tiles.indices.any { !job.tiles[it].locked && photoElementId(job.tiles[it], it) in ids }) emptyList() else job.customCells,
)

fun moveDesignLayer(job: CollageJob, id: String, forward: Boolean): CollageJob {
    val ordered = collageDesignElements(job).sortedBy { it.layer }.toMutableList()
    val index = ordered.indexOfFirst { it.id == id }
    val target = index + if (forward) 1 else -1
    if (index < 0 || target !in ordered.indices) return job
    if (ordered[index].kind == DesignElementKind.PHOTO && job.tiles[ordered[index].index].locked) return job
    ordered.add(target, ordered.removeAt(index))
    return ordered.foldIndexed(job) { i, doc, e ->
        if (e.kind == DesignElementKind.PHOTO) doc.copy(tiles = doc.tiles.mapIndexed { n, p -> if (n == e.index) p.copy(layer = i) else p })
        else updateDesignElement(doc, e.id, layer = i)
    }
}

fun snapDesignFrame(frame: NormalizedCell, others: List<NormalizedCell>, thresholdX: Float, thresholdY: Float, clampToCanvas: Boolean = true): SnapResult {
    fun anchors(a: Float, b: Float) = listOf(a, (a + b) / 2, b)
    fun nearest(from: List<Float>, to: List<Float>, threshold: Float): Pair<Float, Float>? =
        from.flatMap { f -> to.map { t -> (t - f) to t } }.filter { abs(it.first) <= threshold }.minByOrNull { abs(it.first) }
    val dx = nearest(anchors(frame.left, frame.right), listOf(0f, .5f, 1f) + others.flatMap { anchors(it.left, it.right) }, thresholdX)
    val dy = nearest(anchors(frame.top, frame.bottom), listOf(0f, .5f, 1f) + others.flatMap { anchors(it.top, it.bottom) }, thresholdY)
    val x = (dx?.first ?: 0f).let { if (clampToCanvas) it.coerceIn(-frame.left, 1f - frame.right) else it }
    val y = (dy?.first ?: 0f).let { if (clampToCanvas) it.coerceIn(-frame.top, 1f - frame.bottom) else it }
    return SnapResult(NormalizedCell(frame.left + x, frame.top + y, frame.right + x, frame.bottom + y),
        dx?.takeIf { abs(x - it.first) < .00001f }?.second, dy?.takeIf { abs(y - it.first) < .00001f }?.second)
}

fun rotatedElementBounds(frame: NormalizedCell, degrees: Float, aspect: Float): NormalizedCell {
    val radians = Math.toRadians(degrees.toDouble())
    val w = (frame.right - frame.left) * aspect
    val h = frame.bottom - frame.top
    val rw = (abs(cos(radians)) * w + abs(sin(radians)) * h).toFloat() / aspect
    val rh = (abs(sin(radians)) * w + abs(cos(radians)) * h).toFloat()
    val cx = (frame.left + frame.right) / 2; val cy = (frame.top + frame.bottom) / 2
    return NormalizedCell(cx - rw / 2, cy - rh / 2, cx + rw / 2, cy + rh / 2)
}

fun alignDesignElements(job: CollageJob, ids: Set<String>, alignment: DesignAlignment, aspect: Float): CollageJob {
    val selected = collageDesignElements(job).filter { it.id in ids && (it.kind != DesignElementKind.PHOTO || !job.tiles[it.index].locked) }
    if (selected.isEmpty()) return job
    val bounds = selected.associate { it.id to rotatedElementBounds(it.frame, it.rotation, aspect) }
    val space = alignment == DesignAlignment.SPACE_X || alignment == DesignAlignment.SPACE_Y
    if (space && selected.size < 3) return job
    val left = if (selected.size == 1) 0f else bounds.values.minOf { it.left }
    val right = if (selected.size == 1) 1f else bounds.values.maxOf { it.right }
    val top = if (selected.size == 1) 0f else bounds.values.minOf { it.top }
    val bottom = if (selected.size == 1) 1f else bounds.values.maxOf { it.bottom }
    var result = job
    val horizontal = alignment == DesignAlignment.SPACE_X
    val ordered = if (space) selected.sortedBy { bounds.getValue(it.id).let { r -> if (horizontal) r.left else r.top } } else selected
    val extent = if (horizontal) right - left else bottom - top
    val sum = ordered.sumOf { bounds.getValue(it.id).let { r -> if (horizontal) r.right - r.left else r.bottom - r.top }.toDouble() }.toFloat()
    val gap = if (space) (extent - sum) / (ordered.size - 1) else 0f
    var cursor = if (horizontal) left else top
    for (e in ordered) {
        val r = bounds.getValue(e.id)
        val dx = when (alignment) {
            DesignAlignment.LEFT -> left - r.left; DesignAlignment.CENTER_X -> (left + right - r.left - r.right) / 2
            DesignAlignment.RIGHT -> right - r.right; DesignAlignment.SPACE_X -> cursor - r.left; else -> 0f
        }
        val dy = when (alignment) {
            DesignAlignment.TOP -> top - r.top; DesignAlignment.CENTER_Y -> (top + bottom - r.top - r.bottom) / 2
            DesignAlignment.BOTTOM -> bottom - r.bottom; DesignAlignment.SPACE_Y -> cursor - r.top; else -> 0f
        }
        val f = e.frame
        val mx = dx.coerceIn(-f.left, 1f - f.right); val my = dy.coerceIn(-f.top, 1f - f.bottom)
        result = updateDesignElement(result, e.id, frame = NormalizedCell(f.left + mx, f.top + my, f.right + mx, f.bottom + my))
        if (space) cursor += (if (horizontal) r.right - r.left else r.bottom - r.top) + gap
    }
    return result
}

/** Convert the current grid to independent frames without changing its visible crop. */
fun detachCollageLayout(job: CollageJob, aspect: Float): CollageJob {
    if (job.mode == CollageMode.FREEFORM) return job
    val rects = collageTileBounds(job, aspect, 1f)
    return job.copy(mode = CollageMode.FREEFORM, freeformCanvasAspect = aspect, tiles = job.tiles.mapIndexed { i, p ->
        val r = rects[i]
        p.copy(freeform = NormalizedCell(r.left / aspect, r.top, r.right / aspect, r.bottom), layer = i,
            layoutTransform = PhotoTransformState(p.zoom, p.panX, p.panY, p.quarterTurns, p.flipHorizontal, p.flipVertical))
    })
}

fun changeDesignCanvasAspect(job: CollageJob, oldAspect: Float, newAspect: Float): CollageJob {
    if (oldAspect == newAspect || newAspect <= 0f || !newAspect.isFinite()) return job
    val sx = minOf(1f, oldAspect / newAspect)
    val sy = minOf(1f, newAspect / oldAspect)
    fun fit(r: NormalizedCell) = NormalizedCell(.5f + (r.left - .5f) * sx, .5f + (r.top - .5f) * sy,
        .5f + (r.right - .5f) * sx, .5f + (r.bottom - .5f) * sy)
    val independent = detachCollageLayout(job, oldAspect)
    return independent.copy(freeformCanvasAspect = newAspect,
        tiles = independent.tiles.mapIndexed { i, p -> p.copy(freeform = fit(freeformFrame(independent, i))) },
        texts = independent.texts.map { it.copy(frame = fit(it.frame), size = it.size * sy * minOf(oldAspect, 1f) / minOf(newAspect, 1f)) },
        decorations = independent.decorations.map { it.copy(frame = fit(it.frame)) })
}
