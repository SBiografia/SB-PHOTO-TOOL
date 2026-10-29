package com.sb.phototool.ui.collage

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sb.phototool.EditorPreviewState
import com.sb.phototool.media.*
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun FreeformCollageCanvas(
    editor: CollageEditorState, previews: Map<String, EditorPreviewState>, selectedIndex: Int,
    cropping: Boolean, onSelect: (Int) -> Unit, onDeselect: () -> Unit, modifier: Modifier = Modifier,
) {
    val document = editor.document
    val density = LocalDensity.current
    val select by rememberUpdatedState(onSelect)
    val deselect by rememberUpdatedState(onDeselect)
    val selectedSlot by rememberUpdatedState(selectedIndex)
    val crop by rememberUpdatedState(cropping)
    val latestPreviews by rememberUpdatedState(previews)
    val first = (document.tiles.firstOrNull()?.let { previews[it.uri] } as? EditorPreviewState.Ready)?.bitmap
    val ratio = document.ratio.ratio ?: first?.let { it.width.toFloat() / it.height } ?: document.freeformCanvasAspect ?: 1f
    var pixels by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(modifier.padding(12.dp)) {
        val width = minOf(maxWidth, maxHeight * ratio)
        Box(Modifier.size(width, width / ratio).align(Alignment.Center).onSizeChanged { pixels = it }
            .testTag("freeform-canvas")
            .pointerInput(editor, pixels) {
                if (pixels == IntSize.Zero) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val job = editor.document
                    val nx = down.position.x / pixels.width
                    val ny = down.position.y / pixels.height
                    val selectedFrame = selectedSlot.takeIf { it in job.tiles.indices }?.let { freeformFrame(job, it) }
                    val index = if (crop && selectedFrame != null && nx in selectedFrame.left..selectedFrame.right &&
                        ny in selectedFrame.top..selectedFrame.bottom) selectedSlot else hitFreeformPhoto(job, nx, ny)
                    if (index < 0) {
                        deselect()
                        return@awaitEachGesture
                    }
                    val cropThisGesture = crop && index == selectedSlot
                    select(index)
                    down.consume()
                    var moved = false
                    var totalPan = Offset.Zero
                    var totalZoom = 1f
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            totalPan += pan
                            totalZoom *= zoom
                            val firstMove = !moved && (totalPan.getDistance() > viewConfiguration.touchSlop || abs(totalZoom - 1) > .02f)
                            if (firstMove) {
                                moved = true
                                editor.beginGesture()
                            }
                            if (moved) {
                                val current = editor.document
                                val tile = current.tiles.getOrNull(index) ?: break
                                val focal = event.calculateCentroid(useCurrent = false)
                                if (focal.x.isFinite() && focal.y.isFinite()) {
                                    val delta = if (firstMove) totalPan else pan
                                    val factor = if (firstMove) totalZoom else zoom
                                    val next = if (cropThisGesture) {
                                        val bitmap = (latestPreviews[tile.uri] as? EditorPreviewState.Ready)?.bitmap
                                        val rect = collageTileBounds(current, pixels.width.toFloat(), pixels.height.toFloat())[index]
                                        if (bitmap == null) tile else transformPhoto(tile, bitmap.width, bitmap.height, rect.width, rect.height,
                                            delta.x, delta.y, factor, focal.x - rect.left, focal.y - rect.top)
                                    } else tile.copy(freeform = transformFreeformFrame(freeformFrame(current, index),
                                        delta.x / pixels.width, delta.y / pixels.height, factor, focal.x / pixels.width, focal.y / pixels.height))
                                    editor.drag(current.copy(tiles = current.tiles.mapIndexed { i, item -> if (i == index) next else item }))
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        if (moved) editor.finishGesture()
                    }
                }
            }) {
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                drawRect(Color(document.backgroundColor))
                val bounds = collageTileBounds(document, size.width, size.height)
                collagePaintOrder(document).forEach { index ->
                    val tile = document.tiles[index]
                    val rect = bounds[index]
                    val bitmap = (previews[tile.uri] as? EditorPreviewState.Ready)?.bitmap
                    if (bitmap != null) drawIntoCanvas {
                        drawCollagePhoto(it.nativeCanvas, bitmap, rect, tile, size.minDimension * document.cornerPercent,
                            size.minDimension * document.borderWidthPercent, document.borderColor)
                    } else drawRect(Color(0xFF30333B), Offset(rect.left, rect.top), Size(rect.width, rect.height))
                }
                // Selection is drawn last, including when another photo overlaps it.
                bounds.getOrNull(selectedIndex)?.let { rect ->
                    drawRect(EditorAccent, Offset(rect.left, rect.top), Size(rect.width, rect.height), style = Stroke(2.dp.toPx()))
                    if (cropping) for (fraction in listOf(1f / 3, 2f / 3)) {
                        drawLine(Color.White.copy(alpha = .65f), Offset(rect.left + rect.width * fraction, rect.top),
                            Offset(rect.left + rect.width * fraction, rect.bottom), 1.dp.toPx())
                        drawLine(Color.White.copy(alpha = .65f), Offset(rect.left, rect.top + rect.height * fraction),
                            Offset(rect.right, rect.top + rect.height * fraction), 1.dp.toPx())
                    }
                }
            }
            if (pixels != IntSize.Zero) {
                collageTileBounds(document, pixels.width.toFloat(), pixels.height.toFloat()).forEachIndexed { index, rect ->
                    Box(Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                        .testTag("freeform-photo-$index").semantics {
                            contentDescription = "자유형 사진 ${index + 1}"
                            selected = index == selectedIndex
                            role = Role.Button
                            onClick { select(index); true }
                        })
                }
                if (selectedIndex in document.tiles.indices) {
                    val frame = freeformFrame(document, selectedIndex)
                    val handles = if (cropping) listOf("left", "right", "top", "bottom") else listOf("scale")
                    handles.forEach { edge ->
                        val x = when (edge) { "left" -> frame.left; "right", "scale" -> frame.right; else -> (frame.left + frame.right) / 2 }
                        val y = when (edge) { "top" -> frame.top; "bottom", "scale" -> frame.bottom; else -> (frame.top + frame.bottom) / 2 }
                        Box(Modifier.offset { IntOffset((x * pixels.width - with(density) { 24.dp.toPx() }).roundToInt(),
                            (y * pixels.height - with(density) { 24.dp.toPx() }).roundToInt()) }
                            .size(48.dp).testTag("freeform-handle-$edge")
                            .semantics { contentDescription = if (edge == "scale") "사진 크기 조절" else "자르기 $edge 가장자리" }
                            .pointerInput(editor, selectedIndex, edge, pixels) {
                                awaitEachGesture {
                                    // Claim the handle before the canvas starts moving the underlying photo.
                                    val down = awaitFirstDown()
                                    down.consume()
                                    val start = freeformFrame(editor.document, selectedIndex)
                                    var distance = Offset.Zero
                                    editor.beginGesture()
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            distance += change.positionChange()
                                            event.changes.forEach { it.consume() }
                                            val next = if (edge == "scale") {
                                                val w = (start.right - start.left) * pixels.width
                                                val h = (start.bottom - start.top) * pixels.height
                                                val factor = (1 + (distance.x * w + distance.y * h) / (w * w + h * h)).coerceAtLeast(.01f)
                                                transformFreeformFrame(start, 0f, 0f, factor, start.left, start.top)
                                            } else cropFreeformEdge(start, edge,
                                                if (edge == "left" || edge == "right") distance.x / pixels.width else distance.y / pixels.height)
                                            val current = editor.document
                                            editor.drag(current.copy(tiles = current.tiles.mapIndexed { i, tile -> if (i == selectedIndex) tile.copy(freeform = next) else tile }))
                                        }
                                    } finally { editor.finishGesture() }
                                }
                            }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(if (edge == "scale") 20.dp else 16.dp).clip(CircleShape).background(EditorAccent))
                        }
                    }
                }
            }
        }
    }
}
