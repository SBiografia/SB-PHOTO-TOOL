package com.sb.phototool.ui.gallery

import org.junit.Assert.*
import org.junit.Test

class GallerySelectionTest {
    private fun photo(id: Int) = GalleryPhoto(id.toLong(), "content://photos/$id", "$id.jpg", 0)

    @Test fun tenthPhotoIsRejectedBeforeConfirmation() {
        val selection = GallerySelection().apply { beginSession("collage", 9) }
        repeat(9) { assertTrue(selection.toggle(photo(it))) }
        assertFalse(selection.toggle(photo(9)))
        assertEquals((0..8).map(::photo), selection.photos.toList())
    }
    @Test fun deselectThenReselectUsesNewOrderAndNoDuplicates() {
        val selection = GallerySelection().apply { beginSession("test", 9) }
        repeat(3) { selection.toggle(photo(it)) }
        selection.toggle(photo(1))
        selection.toggle(photo(1))
        selection.setSelected(photo(2), true)
        assertEquals(listOf(photo(0), photo(2), photo(1)), selection.photos.toList())
    }
    @Test fun singlePhotoReplacesSelection() {
        val selection = GallerySelection().apply { beginSession("split", 1) }
        selection.toggle(photo(1)); selection.toggle(photo(2))
        assertEquals(listOf(photo(2)), selection.photos.toList())
        selection.toggle(photo(2))
        assertTrue(selection.photos.isEmpty())
    }
    @Test fun sameSessionSurvivesRecreationButNewSessionClears() {
        val selection = GallerySelection().apply { beginSession("one", 9); toggle(photo(1)) }
        selection.beginSession("one", 9)
        assertEquals(1, selection.photos.size)
        selection.beginSession("two", 9)
        assertTrue(selection.photos.isEmpty())
    }
    @Test fun dragSelectionDoesNotToggleAlreadySelectedPhotos() {
        val selection = GallerySelection().apply { beginSession("drag", 9) }
        repeat(20) { selection.setSelected(photo(it % 6), true) }
        assertEquals(6, selection.photos.size)
        repeat(20) { selection.setSelected(photo(it % 6), false) }
        assertTrue(selection.photos.isEmpty())
    }
    @Test fun bulkCompressionHasNoHundredPhotoPickerLimit() {
        val selection = GallerySelection().apply { beginSession("compression", Int.MAX_VALUE) }
        repeat(200) { assertTrue(selection.setSelected(photo(it), true)) }
        assertEquals(200, selection.photos.size)
    }
}
