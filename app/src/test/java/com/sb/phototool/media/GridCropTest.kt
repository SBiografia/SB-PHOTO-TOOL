package com.sb.phototool.media

import com.sb.phototool.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GridCropTest {
    @Test fun allGridRatios_fitInsideSourceAndPreserveSquareTiles() {
        for (source in listOf(.25f, .6667f, 1f, 1.5f, 4f)) for (cols in 1..10) for (rows in 1..10) {
            val crop = gridCrop(source, cols, rows)
            assertTrue(validGridCrop(crop))
            assertEquals(cols.toFloat() / rows, (crop.right - crop.left) / (crop.bottom - crop.top) * source, .001f)
        }
    }

    @Test fun dragAndEveryHandle_stayBoundedWithoutAspectDrift() {
        val random = Random(72)
        var crop = gridCrop(.75f, 3, 2, .6f)
        repeat(500) {
            crop = moveGridCrop(crop, random.nextFloat() - .5f, random.nextFloat() - .5f)
            crop = resizeGridCrop(crop, listOf("left", "right", "top", "bottom")[it % 4], (random.nextFloat() - .5f) * .3f)
            assertTrue("Invalid $crop", validGridCrop(crop))
            assertEquals(2f, (crop.right - crop.left) / (crop.bottom - crop.top), .002f)
        }
    }

    @Test fun splitCodec_preservesCropAndAcceptsLegacyJobs() {
        val old = SplitJob("legacy", "content://source", 3, 1, SplitFitMode.FILL, -1, false, ExportSize())
        assertEquals(old, JobCodec.decode(JobCodec.encode(old)))
        val modern = old.copy(columns = 4, crop = gridCrop(1.5f, 4, 1, .6f, .4f, .7f))
        assertEquals(modern, JobCodec.decode(JobCodec.encode(modern)))
    }
}
