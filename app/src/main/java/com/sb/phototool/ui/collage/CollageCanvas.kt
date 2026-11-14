package com.sb.phototool.ui.collage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.phototool.EditorPreviewState
import com.sb.phototool.media.*
import com.sb.phototool.model.CollageMode
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun CollageCanvas(
    editor: CollageEditorState,
    previews: Map<String, EditorPreviewState>,
    selectedIndex: Int,
    swapMode: Boolean,
    onSelect: (Int) -> Unit,
    onDeselect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val document = editor.document
    val density = LocalDensity.current
    val select by rememberUpdatedState(onSelect)
    val deselect by rememberUpdatedState(onDeselect)
    val swapping by rememberUpdatedState(swapMode)
    val latestPreviews by rememberUpdatedState(previews)
    val firstBitmap = (document.tiles.firstOrNull()?.let { previews[it.uri] } as? EditorPreviewState.Ready)?.bitmap
    val ratio = document.ratio.ratio ?: firstBitmap?.let { it.width.toFloat() / it.height } ?: 1f
    var pixels by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(modifier.padding(12.dp).pointerInput(Unit) { detectTapGestures { deselect() } }) {
        val width = minOf(maxWidth, maxHeight * ratio)
        val height = width / ratio
        Box(Modifier.size(width, height).align(Alignment.Center).onSizeChanged { pixels = it }.testTag("collage-canvas")) {
            val bounds = collageTileBounds(document, pixels.width.toFloat(), pixels.height.toFloat())
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(document.backgroundColor))
                val minSide = minOf(size.width, size.height)
                val destinations = collageTileBounds(document, size.width, size.height)
                document.tiles.forEachIndexed { index, tile ->
                    val rect = destinations[index]
                    val bitmap = (previews[tile.uri] as? EditorPreviewState.Ready)?.bitmap
                    if (bitmap != null) {
                        drawIntoCanvas { canvas ->
                            drawCollagePhoto(canvas.nativeCanvas, bitmap, rect, tile, minSide * document.cornerPercent,
                                minSide * document.borderWidthPercent, document.borderColor,
                                clipToDestination = document.mode == CollageMode.LAYOUT)
                        }
                    } else {
                        drawRect(Color(0xFF30333B), Offset(rect.left, rect.top), Size(rect.width, rect.height))
                    }
                    if (index == selectedIndex) {
                        drawRoundRect(EditorAccent, Offset(rect.left, rect.top), Size(rect.width, rect.height),
                            CornerRadius((minSide * document.cornerPercent).coerceAtMost(minOf(rect.width, rect.height) / 2)), style = Stroke(2.dp.toPx()))
                    }
                }
            }
            document.tiles.forEachIndexed { index, tile ->
                if (index >= bounds.size || pixels == IntSize.Zero) return@forEachIndexed
                val rect = bounds[index]
                val preview = previews[tile.uri]
                Box(
                    Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                        .semantics {
                            contentDescription = "콜라주 사진 ${index + 1}"
                            selected = index == selectedIndex
                            role = Role.Button
                            onClick { select(index); true }
                        }
                        .testTag("collage-photo-$index")
                        .pointerInput(index, tile.uri, pixels) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var transformed = false
                                var zoomTotal = 1f
                                var panTotal = Offset.Zero
                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        val pan = event.calculatePan()
                                        val zoom = event.calculateZoom()
                                        panTotal += pan
                                        zoomTotal *= zoom
                                        if (!transformed && !swapping && (panTotal.getDistance() > viewConfiguration.touchSlop || abs(zoomTotal - 1f) > .02f)) {
                                            select(index)
                                            editor.beginGesture()
                                            transformed = true
                                        }
                                        if (transformed) {
                                            val current = editor.document
                                            val selectedTile = current.tiles.getOrNull(index)
                                            val bitmap = (selectedTile?.let { latestPreviews[it.uri] } as? EditorPreviewState.Ready)?.bitmap
                                            if (selectedTile != null && bitmap != null) {
                                                val destination = collageTileBounds(current, pixels.width.toFloat(), pixels.height.toFloat())[index]
                                                val focal = event.calculateCentroid(useCurrent = false)
                                                val next = transformPhoto(selectedTile, bitmap.width, bitmap.height, destination.width, destination.height,
                                                    pan.x, pan.y, zoom, if (focal.x.isFinite()) focal.x else destination.width / 2,
                                                    if (focal.y.isFinite()) focal.y else destination.height / 2,
                                                    contentRotation = current.mode == CollageMode.LAYOUT)
                                                editor.drag(current.copy(tiles = current.tiles.mapIndexed { i, item -> if (i == index) next else item }))
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    if (!transformed) select(index)
                                } finally {
                                    if (transformed) editor.finishGesture()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (preview !is EditorPreviewState.Ready) Text(
                        if (preview == EditorPreviewState.Failed) "다시 불러오기" else "불러오는 중",
                        Modifier.padding(4.dp), fontSize = 10.sp, textAlign = TextAlign.Center, color = Color(0xFFBAC0CC),
                    )
                }
            }
            if (selectedIndex in document.tiles.indices && !swapMode && pixels != IntSize.Zero) {
                val cells = resolvedCollageCells(document.tiles.size, document.layoutId, document.customCells)
                val cell = cells[selectedIndex]
                val margin = minOf(pixels.width, pixels.height) * document.outerMarginPercent
                val innerWidth = pixels.width - margin * 2
                val innerHeight = pixels.height - margin * 2
                listOf("left", "right", "top", "bottom").forEach { edge ->
                    val vertical = edge == "left" || edge == "right"
                    fun coordinate(cell: NormalizedCell) = when (edge) {
                        "left" -> cell.left; "right" -> cell.right; "top" -> cell.top; else -> cell.bottom
                    }
                    val position = coordinate(cell)
                    if (position <= .0001f || position >= .9999f) return@forEach
                    val centerX = margin + (if (vertical) position else (cell.left + cell.right) / 2) * innerWidth
                    val centerY = margin + (if (vertical) (cell.top + cell.bottom) / 2 else position) * innerHeight
                    val halfTouch = with(density) { 22.dp.toPx() }
                    Box(
                        Modifier.offset { IntOffset((centerX - halfTouch).roundToInt(), (centerY - halfTouch).roundToInt()) }
                            .size(44.dp).testTag("collage-boundary-$edge")
                            .semantics {
                                val name = when (edge) { "left" -> "왼쪽"; "right" -> "오른쪽"; "top" -> "위쪽"; else -> "아래쪽" }
                                contentDescription = "사진 칸 $name 크기 조절"
                            }
                            .pointerInput(selectedIndex, edge, pixels) {
                                var original = cells
                                var startPosition = position
                                var distance = 0f
                                var available = if (vertical) innerWidth else innerHeight
                                detectDragGestures(
                                    onDragStart = {
                                        val current = editor.document
                                        original = resolvedCollageCells(current.tiles.size, current.layoutId, current.customCells)
                                        startPosition = coordinate(original[selectedIndex])
                                        distance = 0f
                                        val inset = minOf(pixels.width, pixels.height) * current.outerMarginPercent * 2
                                        available = (if (vertical) pixels.width else pixels.height) - inset
                                        editor.beginGesture()
                                    },
                                    onDragEnd = editor::finishGesture,
                                    onDragCancel = editor::finishGesture,
                                    onDrag = { change, drag ->
                                        change.consume()
                                        distance += if (vertical) drag.x else drag.y
                                        editor.drag(editor.document.copy(customCells = moveCollageBoundary(original, vertical, startPosition, distance / available)))
                                    },
                                )
                            }, contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(if (vertical) 16.dp else 28.dp, if (vertical) 28.dp else 16.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(if (vertical) 3.dp else 12.dp, if (vertical) 12.dp else 3.dp).clip(CircleShape).background(EditorAccent))
                        }
                    }
                }
            }
        }
    }
}
