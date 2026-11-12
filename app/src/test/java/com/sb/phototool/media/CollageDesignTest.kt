package com.sb.phototool.media

import com.sb.phototool.model.*
import com.sb.phototool.ui.collage.CollageEditorState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CollageDesignTest {
    @Test fun sixtyTemplates_haveUniqueDesignsValidSlotsAndAllCategories() {
        assertEquals(60, collageTemplates.size)
        assertEquals(60, collageTemplates.map { it.id }.toSet().size)
        assertEquals(10, collageTemplates.groupBy { it.category }.size)
        assertTrue(collageTemplates.groupBy { it.category }.values.all { it.size == 6 })
        assertEquals((10..15).toList(), collageTemplates.filter { it.category == TemplateCategory.MANY }.map { it.photoCount })
        for (t in collageTemplates) {
            val j = t.document
            assertTrue(t.name, j.tiles.size in 1..15)
            assertTrue(t.name, j.tiles.all { it.uri.isBlank() && validFreeformFrame(it.freeform) })
            assertEquals(t.name, j.tiles.size, j.tiles.map { it.elementId }.toSet().size)
            assertTrue(j.placementLocked)
            assertEquals(t.name, j, JobCodec.decode(JobCodec.encode(j)))
            assertTrue(t.name, collageDesignElements(j).all { validFreeformFrame(it.frame) })
        }
        // Distinctness ignores IDs, colors, words and font choices so recolors cannot pass as new layouts.
        val signatures = collageTemplates.map { t -> t.document.tiles.map {
            listOf(it.freeform, it.frameRotation, it.shape, it.matte, it.bottomMatte)
        }.toString() }
        assertEquals("Every catalog entry needs a different photo composition", 60, signatures.toSet().size)
    }

    @Test fun legacyAndNewDocuments_roundTripAllDesignProperties() {
        val base = collageTemplates.first { it.id == "scrapbook-2" }.document
        val job = base.copy(background = CollageBackground(BackgroundKind.BLUR, uri = "content://background", blur = .8f),
            texts = base.texts.map { it.copy(outline = .06f, shadow = true, alignment = TextAlignment.RIGHT) },
            tiles = base.tiles.mapIndexed { i, p -> if (i == 1) p.copy(uri = "content://photo") else p }, placementLocked = false, snapping = false)
        assertEquals(job, JobCodec.decode(JobCodec.encode(job)))
        val old = CollageEditorState.create(listOf("content://old")).document
        val raw = JSONObject(JobCodec.encode(old)); raw.remove("design")
        assertEquals(old, JobCodec.decode(raw.toString()))
    }

    @Test fun templateReplacementAndPhotoFill_areUndoableWithoutLosingEmptySlots() {
        val state = CollageEditorState.create(listOf("original"))
        val initial = state.document
        val template = collageTemplates.first { it.photoCount >= 3 }.document
        state.update(template)
        state.update(template.copy(tiles = template.tiles.mapIndexed { i, p -> if (i == 1) p.copy(uri = "filled") else p }))
        assertEquals(template.tiles.size, state.document.tiles.size)
        assertEquals(1, state.document.tiles.count { it.uri.isNotBlank() })
        state.undo(); assertEquals(template, state.document)
        state.undo(); assertEquals(initial, state.document)
        state.redo(); assertEquals(template, state.document)
    }

    @Test fun snapping_preservesSizeAndFindsCanvasAndNeighborEdges() {
        val f = NormalizedCell(.303f, .303f, .703f, .703f)
        val snap = snapDesignFrame(f, emptyList(), .008f, .008f)
        assertEquals(.3f, snap.frame.left, .0001f)
        assertEquals(.5f, snap.x!!, .0001f)
        assertEquals(.4f, snap.frame.right - snap.frame.left, .0001f)
        val neighbor = NormalizedCell(.1f, .1f, .34f, .2f)
        val edge = snapDesignFrame(NormalizedCell(.345f, .4f, .6f, .8f), listOf(neighbor), .01f, .01f)
        assertEquals(.34f, edge.frame.left, .0001f)
    }

    @Test fun alignmentAndSpacing_keepElementSizesAndEndpoints() {
        val job = collageTemplates.first { it.photoCount >= 3 }.document.copy(placementLocked = false)
        val photos = collageDesignElements(job).filter { it.kind == DesignElementKind.PHOTO }.take(3)
        val ids = photos.map { it.id }.toSet()
        val aligned = alignDesignElements(job, ids, DesignAlignment.TOP, 1f)
        val bounds = collageDesignElements(aligned).filter { it.id in ids }.map { rotatedElementBounds(it.frame, it.rotation, 1f) }
        assertTrue(bounds.maxOf { it.top } - bounds.minOf { it.top } < .0001f)
        val clean = job.copy(tiles = job.tiles.take(3).mapIndexed { i, p -> p.copy(freeform = NormalizedCell(.1f + .3f * i, .2f, .25f + .3f * i, .5f), frameRotation = 0f) })
        val result = alignDesignElements(clean, ids, DesignAlignment.SPACE_X, 1f)
        val frames = result.tiles.map { it.freeform!! }.sortedBy { it.left }
        assertEquals(.1f, frames.first().left, .0001f); assertEquals(.85f, frames.last().right, .0001f)
        assertEquals(frames[1].left - frames[0].right, frames[2].left - frames[1].right, .0001f)
    }

    @Test fun rotatedMagnetBounds_canExtendOutsideCanvasWithoutClampingOrDistortingShape() {
        val frame = NormalizedCell(.05f, .05f, .95f, .95f)
        val visual = rotatedElementBounds(frame, 45f, 1f)
        assertTrue(visual.left < 0f && visual.right > 1f)
        val snap = snapDesignFrame(visual, emptyList(), .01f, .01f, clampToCanvas = false)
        assertEquals(visual.right - visual.left, snap.frame.right - snap.frame.left, .0001f)
        assertEquals(.5f, (snap.frame.left + snap.frame.right) / 2, .0001f)
    }

    @Test fun ratioChanges_preserveScreenShapeOfPhotoTextAndDecoration() {
        val job = collageTemplates.first { it.id == "scrapbook-3" }.document
        val old = job.ratio.ratio!!
        for (ratio in listOf(.5f, 1f, 2f)) {
            val next = changeDesignCanvasAspect(job, old, ratio)
            val before = collageDesignElements(job); val after = collageDesignElements(next)
            before.zip(after).forEach { (a, b) ->
                val ar = (a.frame.right - a.frame.left) * old / (a.frame.bottom - a.frame.top)
                val br = (b.frame.right - b.frame.left) * ratio / (b.frame.bottom - b.frame.top)
                assertEquals(ar, br, .0001f)
            }
        }
    }

    @Test fun detachingLayout_keepsVisibleFramesAndCropForFifteenPhotos() {
        val state = CollageEditorState.create(List(15) { "source-" + it })
        val before = state.document.copy(gapPercent = .02f, outerMarginPercent = .04f)
        val after = detachCollageLayout(before, .8f)
        collageTileBounds(before, 800f, 1000f).zip(collageTileBounds(after, 800f, 1000f)).forEach { (a, b) ->
            assertEquals(a.left, b.left, .0001f); assertEquals(a.right, b.right, .0001f)
            assertEquals(a.top, b.top, .0001f); assertEquals(a.bottom, b.bottom, .0001f)
        }
        assertEquals(before.tiles.map { it.zoom }, after.tiles.map { it.zoom })
        assertEquals(15, after.tiles.size)
    }
}
