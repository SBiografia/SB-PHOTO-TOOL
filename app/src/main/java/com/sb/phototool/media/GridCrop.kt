package com.sb.phototool.media

import kotlin.math.min

const val MAX_GRID_AXIS = 10

fun gridCrop(sourceRatio: Float, columns: Int, rows: Int, scale: Float = 1f, centerX: Float = .5f, centerY: Float = .5f): NormalizedCell {
    val relativeRatio = (columns.toFloat() / rows) / sourceRatio
    val width = min(1f, relativeRatio) * scale.coerceIn(.1f, 1f)
    val height = min(1f, 1f / relativeRatio) * scale.coerceIn(.1f, 1f)
    val left = (centerX - width / 2).coerceIn(0f, (1f - width).coerceAtLeast(0f))
    val top = (centerY - height / 2).coerceIn(0f, (1f - height).coerceAtLeast(0f))
    return NormalizedCell(left, top, left + width, top + height)
}

fun moveGridCrop(crop: NormalizedCell, x: Float, y: Float): NormalizedCell {
    val dx = x.coerceIn(-crop.left, 1f - crop.right)
    val dy = y.coerceIn(-crop.top, 1f - crop.bottom)
    return crop.copy(left = crop.left + dx, right = crop.right + dx, top = crop.top + dy, bottom = crop.bottom + dy)
}

fun resizeGridCrop(crop: NormalizedCell, edge: String, delta: Float): NormalizedCell {
    val width = crop.right - crop.left
    val height = crop.bottom - crop.top
    val ratio = width / height
    val cx = (crop.left + crop.right) / 2
    val cy = (crop.top + crop.bottom) / 2
    val horizontal = edge == "left" || edge == "right"
    if (horizontal) {
        val maxWidth = min(if (edge == "left") crop.right else 1f - crop.left, min(cy, 1f - cy) * 2 * ratio)
        val next = (width + if (edge == "left") -delta else delta).coerceIn(min(.025f, maxWidth), maxWidth)
        val left = if (edge == "left") crop.right - next else crop.left
        return NormalizedCell(left, cy - next / ratio / 2, left + next, cy + next / ratio / 2)
    }
    val maxHeight = min(if (edge == "top") crop.bottom else 1f - crop.top, min(cx, 1f - cx) * 2 / ratio)
    val next = (height + if (edge == "top") -delta else delta).coerceIn(min(.025f, maxHeight), maxHeight)
    val top = if (edge == "top") crop.bottom - next else crop.top
    return NormalizedCell(cx - next * ratio / 2, top, cx + next * ratio / 2, top + next)
}

fun validGridCrop(crop: NormalizedCell): Boolean =
    listOf(crop.left, crop.top, crop.right, crop.bottom).all(Float::isFinite) &&
        crop.left >= 0f && crop.top >= 0f && crop.right <= 1.00001f && crop.bottom <= 1.00001f &&
        crop.right - crop.left > .001f && crop.bottom - crop.top > .001f
