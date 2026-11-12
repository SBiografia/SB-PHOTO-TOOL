package com.sb.phototool.media

import com.sb.phototool.model.*
import com.sb.phototool.ui.collage.CollageEditorState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CollageEditingTest {
    private fun job() = CollageEditorState.create(listOf("photo-a", "photo-b")).document

    @Test fun swapKeepsFramesAndMovesPhotoAdjustmentsAndFit() {
        val a = TileTransform("a", elementId = "slot-a", freeform = NormalizedCell(0f, 0f, .4f, .4f), zoom = 1.8f, fitInside = true, adjustments = PhotoAdjustments(brightness = .2f))
        val b = TileTransform("b", elementId = "slot-b", shape = PhotoShape.HEART, freeform = NormalizedCell(.5f, .5f, 1f, 1f))
        val before = job().copy(tiles = listOf(a, b))
        val after = swapCollagePhotos(before, 0, 1)
        assertEquals("b", after.tiles[0].uri); assertEquals(a.freeform, after.tiles[0].freeform)
        assertEquals("slot-b", after.tiles[1].elementId); assertEquals(PhotoShape.HEART, after.tiles[1].shape)
        assertEquals(a.adjustments, after.tiles[1].adjustments); assertTrue(after.tiles[1].fitInside)
        val locked = before.copy(tiles = listOf(a.copy(locked = true), b))
        assertEquals(locked, swapCollagePhotos(locked, 0, 1))
    }

    @Test fun templatesNeverLosePhotosWhenSlotCountsDecreaseOrIncrease() {
        val current = CollageEditorState.create((1..15).map { "photo-$it" }).document
        for (template in collageTemplates) {
            val after = applyCollageTemplate(current, template.document)
            assertEquals(current.tiles.map { it.uri }, after.tiles.filter { it.uri.isNotBlank() }.map { it.uri })
            assertEquals(after.tiles.size, after.tiles.mapIndexed { i,p -> photoElementId(p,i) }.toSet().size)
            assertTrue(after.tiles.size <= 15)
            if (after.mode == CollageMode.FREEFORM) assertTrue(after.tiles.indices.all { validFreeformFrame(freeformFrame(after,it)) })
            assertEquals(after, JobCodec.decode(JobCodec.encode(after)))
        }
        val single = job().copy(tiles = job().tiles.take(1))
        val many = applyCollageTemplate(single, collageTemplates.last().document)
        assertEquals(1, many.tiles.count { it.uri.isNotBlank() })
        assertTrue(many.tiles.any { it.uri.isBlank() })
    }

    @Test fun applyingUserTemplateAvoidsExistingExtraPhotoIds() {
        val current = CollageEditorState.create((1..15).map { "p-$it" }).document
        val template = current.copy(tiles = listOf(TileTransform("",elementId="extra-photo-0"),TileTransform("",elementId="extra-photo-1")))
        val applied = applyCollageTemplate(current,template)
        assertEquals(15,collageDesignElements(applied).map { it.id }.toSet().size)
        assertEquals(current.tiles.map { it.uri },applied.tiles.map { it.uri })
    }

    @Test fun fillOnlyUnlockedEmptySlotsAndAdvanceWraps() {
        val base = collageTemplates.first().document
        val before = base.copy(tiles = listOf(TileTransform("a"), TileTransform("", locked = true), TileTransform(""), TileTransform("")))
        val filled = fillEmptyCollageSlots(before, listOf("b", "c", "d"))
        assertEquals(listOf("a", "", "b", "c"), filled.tiles.map { it.uri })
        assertEquals(2, nextEmptyCollageSlot(before, 3)); assertNull(nextEmptyCollageSlot(filled, 0))
    }

    @Test fun editsRoundTripAndOldDocumentsUseDefaults() {
        val before = job().copy(tiles = job().tiles.map { it.copy(locked = true, fitInside = true, adjustments = PhotoAdjustments(.3f,1.2f,.6f,PhotoFilter.WARM,.7f)) },
            texts = listOf(CollageText("text", bold = true, italic = true, underline = true, strikeThrough = true, letterSpacing = .12f, lineSpacing = 1.4f)))
        assertEquals(before, JobCodec.decode(JobCodec.encode(before)))
        val json = JSONObject(JobCodec.encode(before)); json.getJSONObject("design").remove("photoEdits")
        val old = JobCodec.decode(json.toString()) as CollageJob
        assertFalse(old.tiles.first().locked); assertFalse(old.tiles.first().fitInside); assertEquals(PhotoAdjustments(),old.tiles.first().adjustments)
    }

    @Test fun fitShowsFullImageWithCenteredLetterboxAndFillCrops() {
        val tile = TileTransform("photo",fitInside = true)
        val fit = photoPlacement(400,200,100f,100f,tile)
        assertEquals(PhotoRect(0f,25f,100f,75f),fit)
        val fill = photoPlacement(400,200,100f,100f,tile.copy(fitInside = false))
        assertEquals(PhotoRect(-50f,0f,150f,100f),fill)
    }

    @Test fun locksPreventMoveShapeGeometryAndRemoval() {
        val before = job().copy(mode = CollageMode.FREEFORM, tiles = job().tiles.map { it.copy(locked = true) })
        val id = photoElementId(before.tiles.first(),0)
        assertEquals(before,moveCollagePhoto(before,0,.1f,.1f,true))
        assertEquals(before,updateDesignElement(before,id,rotation = 90f))
        assertEquals(before,removeDesignElements(before,setOf(id)))
    }

    @Test fun adjustmentCancelDoesNotConsumeUndoAndApplyIsOneStep() {
        val editor = CollageEditorState(job()); val before = editor.document
        editor.beginGesture(); editor.drag(before.copy(backgroundColor = 5)); editor.cancelGesture()
        assertEquals(before,editor.document); assertFalse(editor.canUndo)
        editor.beginGesture(); editor.drag(before.copy(backgroundColor = 5)); editor.drag(before.copy(backgroundColor = 8)); editor.finishGesture()
        editor.undo(); assertEquals(before,editor.document); editor.redo(); assertEquals(8,editor.document.backgroundColor)
    }

    @Test fun savedTemplateContainsNoPhotoUris() {
        val template = collageTemplateWithoutPhotos(job().copy(background = CollageBackground(BackgroundKind.PHOTO,uri="background")))
        assertTrue(template.tiles.all { it.uri.isBlank() }); assertTrue(template.background.uri.isBlank()); assertTrue(template.placementLocked)
    }
}
