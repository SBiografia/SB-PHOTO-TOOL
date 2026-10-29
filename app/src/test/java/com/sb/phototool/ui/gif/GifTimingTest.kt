package com.sb.phototool.ui.gif

import org.junit.Assert.*
import org.junit.Test

class GifTimingTest {
    @Test fun reorder_keepsEveryPhotoExactlyOnce() {
        val photos = listOf("one", "two", "three")
        assertEquals(listOf("two", "three", "one"), movedFrame(photos, 0, 2))
        assertEquals(listOf("three", "one", "two"), movedFrame(photos, 2, 0))
        assertEquals(photos, movedFrame(photos, 0, -1))
        assertEquals("1.25초", gifTime(1250))
    }
}
