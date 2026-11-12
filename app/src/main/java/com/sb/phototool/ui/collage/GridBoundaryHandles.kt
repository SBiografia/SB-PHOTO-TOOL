package com.sb.phototool.ui.collage

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sb.phototool.media.*
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun GridBoundaryHandles(editor: CollageEditorState, index: Int, pixels: IntSize) {
    val job = editor.document
    val density = LocalDensity.current
    val cells = resolvedCollageCells(job.tiles.size, job.layoutId, job.customCells)
    val cell = cells.getOrNull(index) ?: return
    val margin = minOf(pixels.width, pixels.height) * job.outerMarginPercent
    val width = pixels.width - margin * 2; val height = pixels.height - margin * 2
    listOf("left", "right", "top", "bottom").forEach { edge ->
        val vertical = edge == "left" || edge == "right"
        fun coordinate(c: NormalizedCell) = when(edge) { "left" -> c.left; "right" -> c.right; "top" -> c.top; else -> c.bottom }
        val position = coordinate(cell)
        if (position <= .0001f || position >= .9999f) return@forEach
        if (cells.indices.any { i -> job.tiles.getOrNull(i)?.locked == true &&
            (if (vertical) abs(cells[i].left - position) < .0001f || abs(cells[i].right - position) < .0001f
            else abs(cells[i].top - position) < .0001f || abs(cells[i].bottom - position) < .0001f) }) return@forEach
        val x = margin + (if (vertical) position else (cell.left + cell.right) / 2) * width
        val y = margin + (if (vertical) (cell.top + cell.bottom) / 2 else position) * height
        val half = with(density) { 24.dp.toPx() }
        Box(Modifier.offset { IntOffset((x - half).roundToInt(), (y - half).roundToInt()) }.size(48.dp)
            .testTag("collage-boundary-$edge").semantics { contentDescription = "사진 칸 $edge 크기 조절" }
            .pointerInput(editor, index, edge, pixels) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Claim the first touch before the parent canvas starts a photo gesture.
                    down.consume()
                    val current = editor.document
                    if (current.placementLocked || current.tiles.getOrNull(index)?.locked != false) return@awaitEachGesture
                    val start = resolvedCollageCells(current.tiles.size, current.layoutId, current.customCells)
                    val original = start.getOrNull(index) ?: return@awaitEachGesture
                    val origin = coordinate(original)
                    val inset = minOf(pixels.width, pixels.height) * current.outerMarginPercent * 2
                    val available = ((if (vertical) pixels.width else pixels.height) - inset).coerceAtLeast(1f)
                    var distance = 0f
                    var dragging = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            distance += if (vertical) delta.x else delta.y
                            event.changes.forEach { it.consume() }
                            if (!dragging && abs(distance) > viewConfiguration.touchSlop) {
                                dragging = true; editor.beginGesture()
                            }
                            if (dragging) {
                                val next = moveCollageBoundary(start, vertical, origin, distance / available)
                                if (editor.document.tiles.indices.none { editor.document.tiles[it].locked && start[it] != next[it] })
                                    editor.drag(editor.document.copy(customCells = next))
                            }
                        }
                    } finally { if (dragging) editor.finishGesture() }
                }
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (vertical) 16.dp else 28.dp, if (vertical) 28.dp else 16.dp).clip(CircleShape).background(Color.White))
        }
    }
}
