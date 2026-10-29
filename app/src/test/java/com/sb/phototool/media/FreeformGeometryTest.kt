package com.sb.phototool.media

import com.sb.phototool.model.*
import com.sb.phototool.ui.collage.CollageEditorState
import org.junit.Assert.*
import org.junit.Test

class FreeformGeometryTest {
    private fun job(count: Int = 3) = CollageJob("freeform", List(count) { TileTransform("photo-$it") },
        CanvasRatio.STORY, .012f, .02f, -1, ExportSize(), outerMarginPercent = .03f)

    @Test fun enteringFreeform_showsEveryWholePhotoInsteadOfItsLayoutCrop() {
        val sourceRatios = listOf(.75f, 4f / 3, 9f / 16, 16f / 9, .01f, 100f, .1f, 1.5f, 1f)
        for (count in 1..9) for (ratio in CanvasRatio.entries) {
            val before = job(count).copy(ratio = ratio, tiles = job(count).tiles.map {
                it.copy(zoom = 2.6f, panX = .8f, panY = -.7f, quarterTurns = 1, flipHorizontal = true)
            })
            val canvas = ratio.ratio ?: 1.37f
            val sources = before.tiles.mapIndexed { i, tile -> tile.uri to sourceRatios[i] }.toMap()
            val after = changeCollageMode(before, CollageMode.FREEFORM, canvas, sources)
            val bounds = collageTileBounds(after, 1000 * canvas, 1000f)
            after.tiles.forEachIndexed { i, tile ->
                assertTrue("$count / $ratio / $i: ${tile.freeform}", validFreeformFrame(tile.freeform))
                assertEquals(sourceRatios[i], bounds[i].width / bounds[i].height, sourceRatios[i] * .0001f)
                assertEquals(1f, tile.zoom, 0f)
                assertEquals(0f, tile.panX, 0f); assertEquals(0f, tile.panY, 0f)
                assertEquals(0, tile.quarterTurns); assertFalse(tile.flipHorizontal)
                val placement = photoPlacement((sourceRatios[i] * 10000).toInt(), 10000, bounds[i].width, bounds[i].height, tile)
                assertEquals(bounds[i].width, placement.width, .05f)
                assertEquals(bounds[i].height, placement.height, .05f)
            }
        }
    }

    @Test fun modeRoundTrips_keepEachModesOwnCropAndOrientation() {
        for (ratio in CanvasRatio.entries) {
            val before = job().copy(ratio = ratio, tiles = job().tiles.map {
                it.copy(zoom = 2f, panX = .3f, quarterTurns = 1, flipVertical = true)
            })
            val aspect = ratio.ratio ?: 1.37f
            val after = changeCollageMode(before, CollageMode.FREEFORM, aspect)
            val edited = after.copy(tiles = after.tiles.map { it.copy(zoom = 1.5f, panY = -.4f, quarterTurns = 2) })
            val layout = changeCollageMode(edited, CollageMode.LAYOUT)
            assertEquals(2f, layout.tiles[0].zoom, 0f); assertEquals(1, layout.tiles[0].quarterTurns)
            val changedLayout = layout.copy(tiles = layout.tiles.map { it.copy(zoom = 3f, panY = .9f) })
            val restored = changeCollageMode(changedLayout, CollageMode.FREEFORM)
            assertEquals(edited.tiles.map { it.freeform }, restored.tiles.map { it.freeform })
            assertEquals(1.5f, restored.tiles[0].zoom, 0f); assertEquals(2, restored.tiles[0].quarterTurns)
            assertEquals(-.4f, restored.tiles[0].panY, 0f)
            assertEquals(restored, JobCodec.decode(JobCodec.encode(restored)))
        }
    }

    @Test fun restoreOriginalAndCanvasRatioChanges_preserveSourceAspectAndClearCrop() {
        for (source in listOf(.01f, .75f, 1f, 1.5f, 100f)) for (canvas in listOf(.5f, 1f, 2f)) for (turn in 0..3) {
            val tile = TileTransform("photo", zoom = 2.5f, panX = .7f, panY = -.8f, quarterTurns = turn, layer = 3)
            val restored = restoreFreeformPhoto(tile, NormalizedCell(.3f, .1f, .6f, .8f), source, canvas)
            val frame = requireNotNull(restored.freeform)
            val wanted = if (turn % 2 == 1) 1f / source else source
            assertEquals(wanted, (frame.right - frame.left) * canvas / (frame.bottom - frame.top), wanted * .0001f)
            assertEquals(1f, restored.zoom, 0f); assertEquals(0f, restored.panX, 0f); assertEquals(0f, restored.panY, 0f)
            assertEquals(3, restored.layer)
            val document = job(1).copy(mode = CollageMode.FREEFORM, tiles = listOf(restored))
            val newCanvas = 1.6f
            val changed = changeFreeformCanvasAspect(document, canvas, newCanvas)
            val rect = collageTileBounds(changed, newCanvas * 1000, 1000f).single()
            assertEquals(wanted, rect.width / rect.height, wanted * .0001f)
        }
    }

    @Test fun changingCanvasInLayout_doesNotRecropTheRememberedFreeformPhoto() {
        val freeform = changeCollageMode(job(1), CollageMode.FREEFORM, sourceAspects = mapOf("photo-0" to 1.5f))
        val layout = changeCollageMode(freeform, CollageMode.LAYOUT)
        val returned = changeCollageMode(layout.copy(ratio = CanvasRatio.DOUBLE), CollageMode.FREEFORM)
        val frame = collageTileBounds(returned, 2000f, 1000f).single()
        assertEquals(1.5f, frame.width / frame.height, .0001f)
    }

    @Test fun largeMovesAndScales_neverLosePhotosOrChangeTheirAspect() {
        val frame = NormalizedCell(.2f, .3f, .7f, .6f)
        for (pan in listOf(-100f, -.1f, 0f, .3f, 100f)) for (zoom in listOf(.0001f, .7f, 1f, 2f, 100f)) {
            val changed = transformFreeformFrame(frame, pan, pan, zoom, .35f, .4f)
            assertTrue("$pan / $zoom / $changed", validFreeformFrame(changed))
            assertEquals((frame.right - frame.left) / (frame.bottom - frame.top),
                (changed.right - changed.left) / (changed.bottom - changed.top), .0001f)
        }
        assertEquals(frame, transformFreeformFrame(frame, 0f, 0f, Float.NaN, .5f, .5f))
    }

    @Test fun zoom_keepsItsFocalPointStableAwayFromBoundaries() {
        val original = NormalizedCell(.2f, .3f, .6f, .7f)
        val next = transformFreeformFrame(original, 0f, 0f, 1.2f, .35f, .42f)
        assertEquals((.35f - original.left) / (original.right - original.left), (.35f - next.left) / (next.right - next.left), .0001f)
        assertEquals((.42f - original.top) / (original.bottom - original.top), (.42f - next.top) / (next.bottom - next.top), .0001f)
    }

    @Test fun arbitraryCroppingAndAspectPresets_alwaysHavePositiveVisibleBounds() {
        for (edge in listOf("left", "right", "top", "bottom")) for (delta in listOf(-100f, -.1f, .1f, 100f)) {
            val frame = cropFreeformEdge(NormalizedCell(0f, 0f, 1f, 1f), edge, delta)
            assertTrue(validFreeformFrame(frame))
            assertTrue(validFreeformFrame(cropFreeformEdge(frame, edge, delta)))
        }
        for (aspect in listOf(.01f, .5f, .8f, 1f, 1.5f, 100f)) for (canvas in listOf(.5f, 1f, 2f)) {
            assertTrue(validFreeformFrame(freeformAspect(NormalizedCell(.1f, .2f, .9f, .8f), aspect, canvas)))
        }
    }

    @Test fun layersControlTopmostHit_withoutReorderingSourcesOrChangingCrop() {
        val base = job().copy(mode = CollageMode.FREEFORM,
            tiles = job().tiles.map { it.copy(freeform = NormalizedCell(.1f, .1f, .9f, .9f), zoom = 1.7f) })
        assertEquals(2, hitFreeformPhoto(base, .5f, .5f))
        val lower = moveFreeformLayer(base, 2, false)
        assertEquals(listOf(0, 2, 1), collagePaintOrder(lower))
        assertEquals(1, hitFreeformPhoto(lower, .5f, .5f))
        assertEquals(-1, hitFreeformPhoto(lower, .95f, .95f))
        assertEquals(base.tiles.map { it.uri }, lower.tiles.map { it.uri })
        assertEquals(base.tiles.map { it.freeform }, lower.tiles.map { it.freeform })
        assertEquals(base.tiles.map { it.zoom }, lower.tiles.map { it.zoom })
        assertEquals(lower, moveFreeformLayer(lower, 0, false))
    }

    @Test fun invalidFrames_fallBackSafelyAndExportScalesExactly() {
        val base = job().copy(mode = CollageMode.FREEFORM, tiles = job().tiles.map {
            it.copy(freeform = NormalizedCell(Float.NaN, 0f, -1f, 2f))
        })
        base.tiles.indices.forEach { assertTrue(validFreeformFrame(freeformFrame(base, it))) }
        val small = collageTileBounds(base, 300f, 600f)
        val large = collageTileBounds(base, 1200f, 2400f)
        small.zip(large).forEach { (a, b) ->
            assertEquals(a.left * 4, b.left, .0001f); assertEquals(a.bottom * 4, b.bottom, .0001f)
        }
    }

    @Test fun modeMovementCropAndLayers_restoreWithUndoAndJson() {
        val editor = CollageEditorState(job())
        val original = editor.document
        editor.update(changeCollageMode(original, CollageMode.FREEFORM))
        val detached = editor.document
        repeat(20) { i -> editor.drag(editor.document.copy(tiles = editor.document.tiles.mapIndexed { index, tile ->
            if (index == 0) tile.copy(freeform = NormalizedCell(.1f, .1f, .4f + i * .01f, .7f), zoom = 1.3f) else tile
        })) }
        editor.finishGesture()
        val moved = editor.document
        editor.update(moveFreeformLayer(editor.document, 0, true))
        assertEquals(editor.document, JobCodec.decode(JobCodec.encode(editor.document)))
        editor.undo(); assertEquals(moved, editor.document)
        editor.undo(); assertEquals(detached, editor.document)
        editor.undo(); assertEquals(original, editor.document)
        repeat(3) { editor.redo() }
        assertEquals(listOf(1, 0, 2), collagePaintOrder(editor.document))
    }
}
