package com.sb.phototool.media

import kotlin.math.min
import kotlin.math.sqrt

internal const val HIGH_QUALITY_PREVIEW_MAX_EDGE = 8192

internal fun editorPreviewPixelBudget(photoCount: Int, heapBytes: Long = Runtime.getRuntime().maxMemory()): Long {
    // Leave room for the editor, gallery, decoder scratch space and a concurrently saving job.
    val bytes = min(heapBytes / 4, 128L * 1024 * 1024)
    // A single bitmap must also stay below Android's hardware-canvas draw limit.
    return (min(bytes / photoCount.coerceAtLeast(1), 64L * 1024 * 1024) / 4).coerceAtLeast(1)
}

internal fun previewDimensions(width: Int, height: Int, maxEdge: Int, maxPixels: Long): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxEdge > 0 && maxPixels > 0)
    val scale = min(1.0, min(maxEdge.toDouble() / maxOf(width, height), sqrt(maxPixels.toDouble() / width / height)))
    var targetWidth = (width * scale).toInt().coerceAtLeast(1)
    var targetHeight = (height * scale).toInt().coerceAtLeast(1)
    if (targetWidth.toLong() * targetHeight > maxPixels) {
        if (targetWidth >= targetHeight) targetWidth = (maxPixels / targetHeight).toInt().coerceAtLeast(1)
        else targetHeight = (maxPixels / targetWidth).toInt().coerceAtLeast(1)
    }
    return targetWidth to targetHeight
}
