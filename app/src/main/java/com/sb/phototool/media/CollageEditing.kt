package com.sb.phototool.media

import com.sb.phototool.model.*

internal fun selectedCollagePhotoIndices(job: CollageJob, ids: Set<String>, includeLocked: Boolean = false): List<Int> =
    job.tiles.indices.filter { photoElementId(job.tiles[it], it) in ids && (includeLocked || !job.tiles[it].locked) }

internal fun updateSelectedCollagePhotos(
    job: CollageJob,
    ids: Set<String>,
    includeLocked: Boolean = false,
    change: (Int, TileTransform) -> TileTransform,
): CollageJob {
    if (ids.isEmpty()) return job
    return job.copy(tiles = job.tiles.mapIndexed { index, photo ->
        if (photoElementId(photo, index) in ids && (includeLocked || !photo.locked)) change(index, photo) else photo
    })
}

internal fun moveSelectedCollagePhotos(job: CollageJob, ids: Set<String>, dx: Float, dy: Float, frame: Boolean): CollageJob =
    selectedCollagePhotoIndices(job, ids).fold(job) { next, index -> moveCollagePhoto(next, index, dx, dy, frame) }

/** Photo content travels between slots; each slot keeps its frame and identity. */
fun photoContent(slot: TileTransform, photo: TileTransform) = slot.copy(
    uri = photo.uri, zoom = photo.zoom, panX = photo.panX, panY = photo.panY,
    quarterTurns = photo.quarterTurns, flipHorizontal = photo.flipHorizontal, flipVertical = photo.flipVertical,
    layoutTransform = null, freeformTransform = null, fitInside = photo.fitInside, adjustments = photo.adjustments,
)

fun swapCollagePhotos(job: CollageJob, from: Int, to: Int): CollageJob {
    if (from == to || from !in job.tiles.indices || to !in job.tiles.indices || job.tiles[from].locked || job.tiles[to].locked) return job
    return job.copy(tiles = job.tiles.mapIndexed { i, slot -> when (i) {
        from -> photoContent(slot, job.tiles[to]); to -> photoContent(slot, job.tiles[from]); else -> slot
    } })
}

fun applyCollageTemplate(current: CollageJob, template: CollageJob): CollageJob {
    val photos = current.tiles.filter { it.uri.isNotBlank() }
    val slots = template.tiles.mapIndexed { i, slot -> photos.getOrNull(i)?.let { photoContent(slot, it).copy(locked = it.locked) } ?: slot }
    val usedIds = collageDesignElements(template).map { it.id }.toMutableSet()
    var extraIndex = 0
    val extra = photos.drop(slots.size).mapIndexed { i, photo ->
        while ("extra-photo-$extraIndex" in usedIds) extraIndex++
        val id = "extra-photo-${extraIndex++}"; usedIds.add(id)
        photo.copy(
        elementId = id, freeform = NormalizedCell(.15f + i * .015f, .2f + i * .015f, .7f + i * .015f, .75f + i * .015f),
        layer = 200 + i, frameRotation = 0f,
    ) }
    return template.copy(id = current.id, exportSize = current.exportSize, tiles = (slots + extra).take(MAX_COLLAGE_PHOTOS),
        mode = if (extra.isNotEmpty()) CollageMode.FREEFORM else template.mode,
        placementLocked = template.placementLocked && extra.isEmpty())
}

fun fillEmptyCollageSlots(job: CollageJob, uris: List<String>): CollageJob {
    val remaining = uris.filter { it.isNotBlank() }.iterator()
    return job.copy(tiles = job.tiles.map { if (it.uri.isBlank() && !it.locked && remaining.hasNext())
        photoContent(it, TileTransform(remaining.next())) else it })
}

fun nextEmptyCollageSlot(job: CollageJob, after: Int): Int? =
    ((after + 1 until job.tiles.size) + (0..after.coerceAtMost(job.tiles.lastIndex))).firstOrNull { job.tiles[it].uri.isBlank() && !job.tiles[it].locked }

fun moveCollagePhoto(job: CollageJob, index: Int, dx: Float, dy: Float, frame: Boolean): CollageJob {
    val tile = job.tiles.getOrNull(index) ?: return job
    if (tile.locked) return job
    if (!frame) return job.copy(tiles = job.tiles.mapIndexed { i, p -> if (i == index) p.copy(panX = (p.panX + dx).coerceIn(-1f, 1f), panY = (p.panY + dy).coerceIn(-1f, 1f)) else p })
    if (job.placementLocked || job.mode != CollageMode.FREEFORM) return job
    val f = freeformFrame(job, index)
    return updateDesignElement(job, photoElementId(tile, index), frame = transformFreeformFrame(f, dx, dy, 1f, .5f, .5f))
}

fun collageTemplateWithoutPhotos(job: CollageJob) = job.copy(id = "draft", templateId = "user-template", placementLocked = true,
    tiles = job.tiles.map { photoContent(it, TileTransform("")).copy(locked = false) },
    background = if (job.background.kind in listOf(BackgroundKind.PHOTO, BackgroundKind.BLUR)) CollageBackground() else job.background)
