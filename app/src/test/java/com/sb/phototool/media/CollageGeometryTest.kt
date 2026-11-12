package com.sb.phototool.media

import com.sb.phototool.model.*
import org.junit.Assert.*
import org.junit.Test

class CollageGeometryTest {
    private fun job(count: Int, layout: String) = CollageJob("test", List(count) { TileTransform("$it") },
        CanvasRatio.STORY, .08f, .12f, -1, ExportSize(), layoutId = layout, outerMarginPercent = .12f)

    @Test
    fun maximumSpacing_neverInvertsOrOverflowsAnyTile() {
        for (count in 1..MAX_COLLAGE_PHOTOS) for (layout in collageLayouts(count)) {
            for ((width, height) in listOf(300f to 900f, 900f to 300f, 1080f to 1080f)) {
                val bounds = collageTileBounds(job(count, layout.id), width, height)
                assertEquals(count, bounds.size)
                bounds.forEach {
                    assertTrue(it.width > 0f && it.height > 0f)
                    assertTrue(it.left >= 0f && it.top >= 0f && it.right <= width && it.bottom <= height)
                }
            }
        }
    }

    @Test
    fun draggingAtBaseZoom_movesNaturallyCroppedPhoto() {
        val moved = transformPhoto(TileTransform("one"), 1200, 800, 300f, 400f, 50f, 0f, 1f, 150f, 200f)
        assertTrue(moved.panX > 0f)
        assertEquals(1f, moved.zoom, 0f)
        assertEquals(0f, moved.panY, 0f)
    }

    @Test
    fun pinch_keepsTheImagePointUnderItsFocalPosition() {
        val original = TileTransform("one", zoom = 1.2f, panX = .2f, panY = -.1f)
        val before = photoPlacement(1000, 800, 400f, 300f, original)
        val updated = transformPhoto(original, 1000, 800, 400f, 300f, 0f, 0f, 1.3f, 220f, 140f)
        val after = photoPlacement(1000, 800, 400f, 300f, updated)
        assertEquals((220f - before.left) / before.width, (220f - after.left) / after.width, .00001f)
        assertEquals((140f - before.top) / before.height, (140f - after.top) / after.height, .00001f)
    }

    @Test
    fun rotationsAndExtremePan_keepTheTileCovered() {
        for (turns in 0..3) for (zoom in listOf(1f, 1.5f, 3f)) for (pan in listOf(-20f, 0f, 20f)) {
            val tile = TileTransform("one", zoom, pan, -pan, turns)
            val rect = photoPlacement(1200, 300, 400f, 600f, tile)
            assertTrue(rect.left <= .001f && rect.top <= .001f)
            assertTrue(rect.right >= 399.999f && rect.bottom >= 599.999f)
            val moved = transformPhoto(tile, 1200, 300, 400f, 600f, 50000f, -50000f, .001f, 200f, 300f)
            assertTrue(moved.zoom in 1f..3f && moved.panX in -1f..1f && moved.panY in -1f..1f)
        }
    }

    @Test
    fun previewAndExportBounds_areProportional() {
        val job = job(9, "hero-center")
        collageTileBounds(job, 360f, 640f).zip(collageTileBounds(job, 1080f, 1920f)).forEach { (preview, output) ->
            assertEquals(preview.left * 3, output.left, .001f)
            assertEquals(preview.top * 3, output.top, .001f)
            assertEquals(preview.width * 3, output.width, .001f)
            assertEquals(preview.height * 3, output.height, .001f)
        }
    }
}
