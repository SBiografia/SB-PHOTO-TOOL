package com.sb.phototool.media

import org.junit.Assert.*
import org.junit.Test

class PreviewQualityTest {
    @Test fun smallOriginalIsNotUpscaled() {
        assertEquals(640 to 480, previewDimensions(640, 480, 8192, 8_000_000))
    }
    @Test fun highResolutionUsesPixelBudgetAndPreservesAspect() {
        val budget = editorPreviewPixelBudget(1, 256L * 1024 * 1024)
        val (width, height) = previewDimensions(16000, 12000, HIGH_QUALITY_PREVIEW_MAX_EDGE, budget)
        assertTrue(width > 4000)
        assertTrue(width.toLong() * height <= budget)
        assertEquals(4.0 / 3, width.toDouble() / height, .001)
    }
    @Test fun ninePhotosShareOneBudget() {
        val heap = 512L * 1024 * 1024
        val budget = editorPreviewPixelBudget(9, heap)
        assertTrue(budget * 4 * 9 <= 128L * 1024 * 1024)
        val (width, height) = previewDimensions(12000, 16000, HIGH_QUALITY_PREVIEW_MAX_EDGE, budget)
        assertTrue(height > 2000)
        assertTrue(width.toLong() * height <= budget)
    }
    @Test fun panoramaHasBoundedTextureSize() {
        assertEquals(8192 to 512, previewDimensions(32000, 2000, 8192, 32_000_000))
    }
    @Test fun hugeDimensionsDoNotOverflow() {
        val (width, height) = previewDimensions(Int.MAX_VALUE, Int.MAX_VALUE, 8192, 4_000_000)
        assertEquals(2000, width)
        assertEquals(2000, height)
    }
    @Test fun emptyPhotoSetAndSmallHeapRemainValid() {
        assertEquals(1L, editorPreviewPixelBudget(0, 8))
        assertEquals(1 to 1, previewDimensions(5000, 3000, 8192, 1))
    }
    @Test fun largeHeapStillCapsEachBitmapBelowCanvasLimit() {
        assertEquals(64L * 1024 * 1024, editorPreviewPixelBudget(1, 2L * 1024 * 1024 * 1024) * 4)
    }
    @Test fun onePixelPanoramaRespectsTinyBudget() {
        assertEquals(10 to 1, previewDimensions(100000, 1, 8192, 10))
    }
}
