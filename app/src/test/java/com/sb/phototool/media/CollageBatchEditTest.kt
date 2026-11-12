package com.sb.phototool.media

import com.sb.phototool.model.*
import com.sb.phototool.ui.collage.CollageEditorState
import com.sb.phototool.ui.collage.collagePhotoFrameRotation
import com.sb.phototool.ui.collage.collagePhotoRotationSliderValue
import org.junit.Assert.*
import org.junit.Test

class CollageBatchEditTest {
    private fun job(): CollageJob {
        val base = CollageEditorState.create(listOf("one", "two", "three")).document
        return base.copy(tiles = base.tiles.mapIndexed { index, photo -> photo.copy(
            adjustments = PhotoAdjustments(brightness = index * .1f, contrast = 1f + index * .2f,
                filter = listOf(PhotoFilter.WARM, PhotoFilter.MONO, PhotoFilter.COOL)[index]),
            quarterTurns = index, frameRotation = index * 15f,
        ) })
    }

    @Test fun changingBrightnessKeepsEachPhotosEarlierEditsAndLeavesUnselectedPhotosAlone() {
        val before = job()
        val after = updateSelectedCollagePhotos(before, setOf("photo-0", "photo-1")) { _, photo ->
            photo.copy(adjustments = photo.adjustments.copy(brightness = .35f))
        }
        assertEquals(listOf(.35f, .35f), after.tiles.take(2).map { it.adjustments.brightness })
        assertEquals(listOf(PhotoFilter.WARM, PhotoFilter.MONO), after.tiles.take(2).map { it.adjustments.filter })
        assertEquals(listOf(1f, 1.2f), after.tiles.take(2).map { it.adjustments.contrast })
        assertEquals(before.tiles.map { it.uri }, after.tiles.map { it.uri })
        assertEquals(before.tiles.map { it.quarterTurns }, after.tiles.map { it.quarterTurns })
        assertEquals(before.tiles[2], after.tiles[2])
    }

    @Test fun lockedPhotosAreSkippedButCanBeExplicitlyUnlockedTogether() {
        val source = job()
        val before = source.copy(tiles = source.tiles.mapIndexed { i, photo -> photo.copy(locked = i == 1) })
        val ids = setOf("photo-0", "photo-1")
        val shaped = updateSelectedCollagePhotos(before, ids) { _, photo -> photo.copy(shape = PhotoShape.HEART) }
        assertEquals(PhotoShape.HEART, shaped.tiles[0].shape)
        assertEquals(before.tiles[1], shaped.tiles[1])
        assertEquals(before.tiles[2], shaped.tiles[2])
        val unlocked = updateSelectedCollagePhotos(shaped, ids, includeLocked = true) { _, photo -> photo.copy(locked = false) }
        assertTrue(unlocked.tiles.none { it.locked })
        assertEquals(before.tiles[1].adjustments, unlocked.tiles[1].adjustments)
    }

    @Test fun aBatchAdjustmentCancelsCompletelyAndAppliesAsOneUndoStep() {
        val before = job()
        val editor = CollageEditorState(before)
        val ids = setOf("photo-0", "photo-1")
        fun adjust(value: Float) = updateSelectedCollagePhotos(editor.document, ids) { _, photo ->
            photo.copy(adjustments = photo.adjustments.copy(saturation = value))
        }
        editor.drag(adjust(.4f)); editor.cancelGesture()
        assertEquals(before, editor.document)
        assertFalse(editor.canUndo)
        editor.drag(adjust(.6f)); editor.drag(adjust(1.4f)); editor.finishGesture()
        val applied = editor.document
        assertEquals(1.4f, applied.tiles[0].adjustments.saturation, 0f)
        assertEquals(1.4f, applied.tiles[1].adjustments.saturation, 0f)
        editor.undo()
        assertEquals(before, editor.document)
        assertFalse(editor.canUndo)
        editor.redo()
        assertEquals(applied, editor.document)
    }

    @Test fun quarterTurnsAndFineRotationWorkForPhotosWithDifferentEarlierRotations() {
        val before = job()
        val ids = setOf("photo-0", "photo-1")
        val turned = updateSelectedCollagePhotos(before, ids) { _, photo -> photo.copy(quarterTurns = (photo.quarterTurns + 1).mod(4)) }
        assertEquals(listOf(1, 2), turned.tiles.take(2).map { it.quarterTurns })
        val aligned = updateSelectedCollagePhotos(turned, ids) { _, photo ->
            photo.copy(frameRotation = collagePhotoFrameRotation(270f, photo.quarterTurns))
        }
        assertTrue(aligned.tiles.take(2).all { collagePhotoRotationSliderValue(it.frameRotation, it.quarterTurns) == 270f })
        assertEquals(before.tiles.take(2).map { it.adjustments }, aligned.tiles.take(2).map { it.adjustments })
        assertEquals(before.tiles[2], aligned.tiles[2])
    }

    @Test fun movingPhotosUsesEachOwnFrameAndRespectsPlacementLock() {
        val source = job()
        val frames = listOf(NormalizedCell(.1f, .1f, .3f, .3f), NormalizedCell(.55f, .45f, .75f, .65f), NormalizedCell(.3f, .7f, .5f, .9f))
        val before = source.copy(mode = CollageMode.FREEFORM, placementLocked = false,
            tiles = source.tiles.mapIndexed { i, photo -> photo.copy(freeform = frames[i]) })
        val ids = setOf("photo-0", "photo-1")
        val moved = moveSelectedCollagePhotos(before, ids, .05f, .04f, frame = true)
        assertEquals(.15f, moved.tiles[0].freeform!!.left, .00001f)
        assertEquals(.6f, moved.tiles[1].freeform!!.left, .00001f)
        assertEquals(.45f, moved.tiles[1].freeform!!.left - moved.tiles[0].freeform!!.left, .00001f)
        assertEquals(before.tiles[2], moved.tiles[2])
        val locked = before.copy(placementLocked = true)
        assertEquals(locked, moveSelectedCollagePhotos(locked, ids, .05f, .04f, frame = true))
    }

    @Test fun emptyOrStaleSelectionDoesNotChangeTheDocumentOrCreateUndo() {
        val before = job()
        assertSame(before, updateSelectedCollagePhotos(before, emptySet()) { _, photo -> photo.copy(flipHorizontal = true) })
        val editor = CollageEditorState(before)
        editor.update(updateSelectedCollagePhotos(before, setOf("missing-photo")) { _, photo -> photo.copy(flipHorizontal = true) })
        assertEquals(before, editor.document)
        assertFalse(editor.canUndo)
    }
}
