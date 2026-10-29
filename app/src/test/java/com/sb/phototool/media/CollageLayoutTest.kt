package com.sb.phototool.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CollageLayoutTest {
    @Test
    fun expandedCatalog_offersDistinctFamiliesAndHeroSizesForEveryCount() {
        for (count in 2..9) {
            val options = collageLayouts(count)
            assertTrue("$count photos: ${options.size}", options.size >= if (count == 2) 8 else if (count < 5) 20 else 30)
            assertTrue(options.any { it.family == CollageLayoutFamily.FEATURE })
            if (count >= 3) assertTrue(options.any { it.family == CollageLayoutFamily.MOSAIC })
            if (count >= 5) {
                assertTrue(options.any { it.id.startsWith("pinwheel-") })
                assertTrue(options.any { it.id.startsWith("surround-") })
            }
            println("Collage catalog: $count photos = ${options.size} layouts")
        }
    }

    @Test
    fun allTemplates_areCompleteNonoverlappingPartitionsWithUniqueIds() {
        for (count in 1..MAX_COLLAGE_PHOTOS) {
            val templates = collageLayouts(count)
            assertEquals("auto", templates.first().id)
            assertEquals(templates.size, templates.map { it.id }.toSet().size)
            assertEquals(templates.size, templates.map { it.cells }.toSet().size)
            assertTrue("Too few options for $count photos", count == 1 || templates.size >= 3)
            templates.forEach { assertTrue("$count / ${it.id}", validCollageCells(it.cells, count)) }
        }
    }

    @Test
    fun draggingEveryGridLine_toBothExtremes_preservesAllPhotos() {
        for (count in 2..MAX_COLLAGE_PHOTOS) {
            for (layout in collageLayouts(count)) {
                for (vertical in listOf(false, true)) {
                    val boundaries = layout.cells.flatMap { if (vertical) listOf(it.left, it.right) else listOf(it.top, it.bottom) }.toSet()
                    for (coordinate in boundaries) {
                        for (delta in listOf(-5f, -.03f, .03f, 5f)) {
                            val moved = moveCollageBoundary(layout.cells, vertical, coordinate, delta)
                            assertTrue("${layout.id} at $coordinate + $delta", validCollageCells(moved, count))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun invalidSavedGeometry_fallsBackToTemplate() {
        val invalid = List(5) { NormalizedCell(0f, 0f, 1f, 1f) }
        assertFalse(validCollageCells(invalid, 5))
        assertEquals(collageLayouts(5).first { it.id == "hero-left" }.cells, resolvedCollageCells(5, "hero-left", invalid))
        assertEquals(collageCells(5), resolvedCollageCells(5, "missing", invalid))
        assertTrue(collageLayouts(0).isEmpty())
        assertTrue(collageLayouts(10).isEmpty())
    }

    @Test
    fun maxPhotoCount_usesNineCellsInTheSharedPreviewAndExportLayout() {
        val cells = collageCells(MAX_COLLAGE_PHOTOS)

        assertEquals(MAX_COLLAGE_PHOTOS, cells.size)
        assertEquals(NormalizedCell(0f, 0f, 1f / 3f, 1f / 3f), cells.first())
        assertEquals(NormalizedCell(2f / 3f, 2f / 3f, 1f, 1f), cells.last())
        assertTrue(cells.all { it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f })
    }
}
