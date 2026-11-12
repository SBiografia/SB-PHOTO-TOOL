package com.sb.phototool.ui.collage

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sb.phototool.EditorPreviewState
import com.sb.phototool.media.*
import com.sb.phototool.model.*
import kotlin.math.*

private fun rotatePoint(point: Offset, center: Offset, degrees: Float): Offset {
    val r = Math.toRadians(degrees.toDouble()); val dx = point.x - center.x; val dy = point.y - center.y
    return Offset(center.x + (cos(r) * dx - sin(r) * dy).toFloat(), center.y + (sin(r) * dx + cos(r) * dy).toFloat())
}

@Composable
internal fun CollageDesignCanvas(editor: CollageEditorState, previews: Map<String, EditorPreviewState>,
    background: Bitmap?, selectedIds: Set<String>, multiSelect: Boolean, cropping: Boolean, galleryActive: Boolean,
    onSelect: (String?) -> Unit, onPhotoSlot: (Int) -> Unit, modifier: Modifier = Modifier) {
    val job = editor.document
    val context = LocalContext.current
    val density = LocalDensity.current
    val aspect = job.ratio.ratio ?: job.freeformCanvasAspect ?: (job.tiles.firstOrNull { it.uri.isNotBlank() }?.let {
        (previews[it.uri] as? EditorPreviewState.Ready)?.bitmap?.let { b -> b.width.toFloat() / b.height }
    } ?: 1f)
    var pixels by remember { mutableStateOf(IntSize.Zero) }
    val currentPixels by rememberUpdatedState(pixels)
    var guideX by remember { mutableStateOf<Float?>(null) }; var guideY by remember { mutableStateOf<Float?>(null) }
    val select by rememberUpdatedState(onSelect); val chooseSlot by rememberUpdatedState(onPhotoSlot)
    val selection by rememberUpdatedState(selectedIds); val multiple by rememberUpdatedState(multiSelect)
    val crop by rememberUpdatedState(cropping); val choosing by rememberUpdatedState(galleryActive)
    val currentPreviews by rememberUpdatedState(previews)
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapId by remember { mutableStateOf<String?>(null) }
    fun find(job: CollageJob, point: Offset, onlyPhotos: Boolean, pixels: IntSize): DesignElement? {
        val bounds = collageTileBounds(job, pixels.width.toFloat(), pixels.height.toFloat())
        return collageDesignElements(job).sortedByDescending { it.layer }.firstOrNull { e ->
            if ((onlyPhotos && e.kind != DesignElementKind.PHOTO) || (job.placementLocked && e.kind == DesignElementKind.DECORATION)) false else {
                val r = if (e.kind == DesignElementKind.PHOTO) bounds[e.index] else e.frame.let { PhotoRect(it.left * pixels.width, it.top * pixels.height, it.right * pixels.width, it.bottom * pixels.height) }
                val p = rotatePoint(point, Offset((r.left + r.right) / 2, (r.top + r.bottom) / 2), -e.rotation)
                if (e.kind == DesignElementKind.PHOTO) shapeContains(job.tiles[e.index].shape, r.width, r.height, p.x - r.left, p.y - r.top)
                else p.x in r.left..r.right && p.y in r.top..r.bottom
            }
        }
    }
    BoxWithConstraints(modifier.padding(12.dp).pointerInput(editor) { detectTapGestures { select(null) } }) {
        val w = minOf(maxWidth, maxHeight * aspect)
        Box(Modifier.size(w, w / aspect).align(Alignment.Center).onSizeChanged { pixels = it }
            .testTag("design-canvas").clipToBounds()
            // Opening the selection toolbar must not cancel the active gesture when the canvas resizes.
            .pointerInput(editor, pixels.width > 0 && pixels.height > 0) {
                if (currentPixels.width == 0 || currentPixels.height == 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pixels = currentPixels
                    val start = editor.document
                    val element = find(start, down.position, choosing, pixels)
                    if (element == null) { select(null); return@awaitEachGesture }
                    if (multiple && (element.kind != DesignElementKind.PHOTO || start.tiles[element.index].uri.isBlank())) {
                        down.consume(); return@awaitEachGesture
                    }
                    if (choosing || (element.kind == DesignElementKind.PHOTO && start.tiles[element.index].uri.isBlank())) {
                        if (element.kind == DesignElementKind.PHOTO) chooseSlot(element.index)
                        down.consume(); return@awaitEachGesture
                    }
                    val alreadySelected = element.id in selection
                    if (!alreadySelected || multiple) select(element.id)
                    if (element.kind == DesignElementKind.PHOTO && start.tiles[element.index].locked) {
                        if (alreadySelected && !multiple) select(element.id)
                        lastTapId = null; down.consume(); return@awaitEachGesture
                    }
                    if (multiple) { down.consume(); return@awaitEachGesture }
                    val ids = if (alreadySelected && selection.size > 1) selection else setOf(element.id)
                    val movable = (start.mode == CollageMode.FREEFORM || element.kind != DesignElementKind.PHOTO) && !start.placementLocked && !crop
                    if (!movable && element.kind != DesignElementKind.PHOTO) return@awaitEachGesture
                    var pan = Offset.Zero; var zoom = 1f; var rotation = 0f; var moved = false
                    var swapping = false
                    var multiplePointers = false
                    var focal = down.position
                    down.consume()
                    try {
                        do {
                            val event = awaitPointerEvent()
                            multiplePointers = multiplePointers || event.changes.size > 1
                            pan += event.calculatePan(); zoom *= event.calculateZoom(); rotation += event.calculateRotation()
                            val firstMove = !moved && (pan.getDistance() > viewConfiguration.touchSlop || abs(zoom - 1f) > .02f || abs(rotation) > 3f)
                            if (firstMove) {
                                lastTapId = null
                                swapping = element.kind == DesignElementKind.PHOTO && start.mode == CollageMode.LAYOUT &&
                                    event.changes.first().uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis && event.changes.size == 1
                                moved = true; editor.beginGesture(); focal = event.calculateCentroid(false).takeIf { it.x.isFinite() } ?: down.position
                            }
                            if (moved) {
                                if (swapping) {
                                    event.changes.forEach { it.consume() }
                                } else if (movable) {
                                    var next = start
                                    val others = collageDesignElements(start).filter { it.id !in ids }.map { rotatedElementBounds(it.frame, it.rotation, aspect) }
                                    collageDesignElements(start).filter { it.id in ids && (it.kind != DesignElementKind.PHOTO || !start.tiles[it.index].locked) }.forEach { e ->
                                        var f = transformFreeformFrame(e.frame, pan.x / pixels.width, pan.y / pixels.height,
                                            if (ids.size > 1) 1f else zoom, focal.x / pixels.width, focal.y / pixels.height)
                                        var a = e.rotation + if (ids.size > 1) 0f else rotation
                                        if (abs(a - round(a / 15f) * 15f) < 2f) a = round(a / 15f) * 15f
                                        if (ids.size == 1) {
                                            val visual = rotatedElementBounds(f, a, aspect)
                                            val snap = snapDesignFrame(visual, others, 8.dp.toPx() / pixels.width, 8.dp.toPx() / pixels.height, clampToCanvas = false)
                                            val dx = (snap.frame.left - visual.left).coerceIn(-f.left, 1f - f.right)
                                            val dy = (snap.frame.top - visual.top).coerceIn(-f.top, 1f - f.bottom)
                                            f = NormalizedCell(f.left + dx, f.top + dy, f.right + dx, f.bottom + dy)
                                            guideX = snap.x?.takeIf { abs(dx - (snap.frame.left - visual.left)) < .00001f }
                                            guideY = snap.y?.takeIf { abs(dy - (snap.frame.top - visual.top)) < .00001f }
                                        }
                                        next = updateDesignElement(next, e.id, frame = f, rotation = a)
                                    }
                                    editor.drag(next)
                                } else {
                                    val tile = start.tiles[element.index]
                                    val bitmap = (currentPreviews[tile.uri] as? EditorPreviewState.Ready)?.bitmap
                                    if (bitmap != null) {
                                        val r = collageTileBounds(start, pixels.width.toFloat(), pixels.height.toFloat())[element.index]
                                        val center = Offset((r.left + r.right) / 2, (r.top + r.bottom) / 2)
                                        val localFocal = rotatePoint(focal, center, -tile.frameRotation)
                                        val localPan = rotatePoint(pan, Offset.Zero, -tile.frameRotation)
                                        val changed = transformPhoto(tile, bitmap.width, bitmap.height, r.width, r.height,
                                            localPan.x, localPan.y, zoom, localFocal.x - r.left, localFocal.y - r.top)
                                        editor.drag(start.copy(tiles = start.tiles.mapIndexed { i, t -> if (i == element.index) changed else t }))
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (swapping) {
                            val target = find(start, down.position + pan, true, pixels)
                            if (target != null) editor.drag(swapCollagePhotos(start, element.index, target.index))
                        } else if (!moved && !multiplePointers && element.kind == DesignElementKind.PHOTO) {
                            if (lastTapId == element.id && down.uptimeMillis - lastTapTime in 1..viewConfiguration.doubleTapTimeoutMillis) {
                                val tile = editor.document.tiles[element.index]
                                editor.update(editor.document.copy(tiles = editor.document.tiles.mapIndexed { i, t -> if (i == element.index) t.copy(fitInside = !tile.fitInside, zoom = 1f, panX = 0f, panY = 0f) else t }))
                                lastTapId = null
                            } else {
                                if (alreadySelected) select(element.id)
                                lastTapId = element.id; lastTapTime = down.uptimeMillis
                            }
                        } else lastTapId = null
                    } finally { if (moved) editor.finishGesture(); guideX = null; guideY = null }
                }
            }) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c ->
                    drawCollageComposition(c.nativeCanvas, job, size.width, size.height,
                        { (previews[it] as? EditorPreviewState.Ready)?.bitmap }, background,
                        font = { CollageFonts.get(context, it) }, emptySlots = true)
                    val bounds = collageTileBounds(job, size.width, size.height)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF80A7FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.dp.toPx() }
                    collageDesignElements(job).filter { it.id in selectedIds }.forEach { e ->
                        val r = if (e.kind == DesignElementKind.PHOTO) bounds[e.index].let { RectF(it.left, it.top, it.right, it.bottom) }
                            else e.frame.let { RectF(it.left * size.width, it.top * size.height, it.right * size.width, it.bottom * size.height) }
                        val saved = c.nativeCanvas.save(); c.nativeCanvas.rotate(e.rotation, r.centerX(), r.centerY()); c.nativeCanvas.drawRect(r, p)
                        if (cropping && e.kind == DesignElementKind.PHOTO) {
                            for (i in 1..2) { c.nativeCanvas.drawLine(r.left + r.width() * i / 3, r.top, r.left + r.width() * i / 3, r.bottom, p); c.nativeCanvas.drawLine(r.left, r.top + r.height() * i / 3, r.right, r.top + r.height() * i / 3, p) }
                        }
                        c.nativeCanvas.restoreToCount(saved)
                    }
                }
                guideX?.let { drawLine(EditorAccent, Offset(it * size.width, 0f), Offset(it * size.width, size.height), 1.dp.toPx()) }
                guideY?.let { drawLine(EditorAccent, Offset(0f, it * size.height), Offset(size.width, it * size.height), 1.dp.toPx()) }
            }
            if (pixels.width > 0) {
                val elements = collageDesignElements(job)
                val photoBounds = collageTileBounds(job, pixels.width.toFloat(), pixels.height.toFloat())
                elements.forEach { e ->
                    val f = if (e.kind == DesignElementKind.PHOTO) photoBounds[e.index].let { NormalizedCell(it.left / pixels.width, it.top / pixels.height, it.right / pixels.width, it.bottom / pixels.height) } else e.frame
                    val a = rotatedElementBounds(f, e.rotation, aspect)
                    Box(Modifier.offset { IntOffset((a.left * pixels.width).roundToInt(), (a.top * pixels.height).roundToInt()) }
                        .size(with(density) { ((a.right - a.left) * pixels.width).toDp() }, with(density) { ((a.bottom - a.top) * pixels.height).toDp() })
                        .testTag("design-element-" + e.id).semantics {
                            contentDescription = when (e.kind) { DesignElementKind.PHOTO -> "사진 " + (e.index + 1) + if (job.tiles[e.index].uri.isBlank()) " 추가" else ""; DesignElementKind.TEXT -> job.texts[e.index].text; DesignElementKind.DECORATION -> job.decorations[e.index].kind.label }
                            selected = e.id in selectedIds; role = Role.Button
                            onClick { if (e.kind == DesignElementKind.PHOTO && (galleryActive || job.tiles[e.index].uri.isBlank())) chooseSlot(e.index) else select(e.id); true }
                        })
                }
                val selected = elements.singleOrNull { it.id in selectedIds }
                if (selected?.kind == DesignElementKind.PHOTO && !multiSelect && job.mode == CollageMode.LAYOUT && !job.placementLocked && !job.tiles[selected.index].locked && !galleryActive)
                    GridBoundaryHandles(editor, selected.index, pixels)
                if (selected != null && !multiSelect && !job.placementLocked && (selected.kind != DesignElementKind.PHOTO || !job.tiles[selected.index].locked) && (job.mode == CollageMode.FREEFORM || selected.kind != DesignElementKind.PHOTO) && !galleryActive) {
                    val f = selected.frame
                    val handles = if (cropping && selected.kind == DesignElementKind.PHOTO) listOf("left", "right", "top", "bottom") else listOf("scale", "rotate")
                    handles.forEach { edge ->
                        val cx = (f.left + f.right) * pixels.width / 2; val cy = (f.top + f.bottom) * pixels.height / 2
                        val point = rotatePoint(Offset(when(edge) { "left" -> f.left * pixels.width; "right", "scale" -> f.right * pixels.width; else -> cx },
                            when(edge) { "top", "rotate" -> f.top * pixels.height; "bottom", "scale" -> f.bottom * pixels.height; else -> cy }), Offset(cx, cy), selected.rotation)
                        Box(Modifier.offset { IntOffset((point.x - with(density) { 24.dp.toPx() }).roundToInt(), (point.y - with(density) { 24.dp.toPx() }).roundToInt()) }
                            .size(48.dp).testTag("design-handle-" + edge).semantics { contentDescription = when(edge) { "scale" -> "요소 크기 조절"; "rotate" -> "요소 회전"; else -> "사진틀 자르기 " + edge } }
                            .pointerInput(editor, selected.id, edge, pixels) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(); down.consume(); val start = editor.document
                                    val element = collageDesignElements(start).first { it.id == selected.id }
                                    var delta = Offset.Zero; editor.beginGesture()
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent(); val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            delta += rotatePoint(change.positionChange(), Offset.Zero, -element.rotation)
                                            val frame = element.frame
                                            if (edge == "rotate") {
                                                val a = (element.rotation + delta.x / pixels.width * 360f).coerceIn(-360f, 360f)
                                                val angle = if (abs(a - round(a / 15) * 15) < 2) round(a / 15) * 15 else a
                                                editor.drag(updateDesignElement(start, element.id, rotation = angle))
                                            } else {
                                                val next = if (edge == "scale") {
                                                    val wpx = (frame.right - frame.left) * pixels.width; val hpx = (frame.bottom - frame.top) * pixels.height
                                                    transformFreeformFrame(frame, 0f, 0f, (1 + (delta.x * wpx + delta.y * hpx) / (wpx * wpx + hpx * hpx)).coerceAtLeast(.01f), frame.left, frame.top)
                                                } else cropFreeformEdge(frame, edge, if (edge == "left" || edge == "right") delta.x / pixels.width else delta.y / pixels.height)
                                                editor.drag(updateDesignElement(start, element.id, frame = next))
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } finally { editor.finishGesture() }
                                }
                            }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(if (edge == "rotate") 12.dp else 18.dp).clip(CircleShape).background(if (edge == "rotate") Color.White else EditorAccent))
                        }
                    }
                }
            }
        }
    }
}
