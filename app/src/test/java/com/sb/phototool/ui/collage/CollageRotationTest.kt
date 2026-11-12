package com.sb.phototool.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Test

class CollageRotationTest {
    @Test fun quarterTurnsSnapExactlyAndOtherAnglesRemainFineAdjustable() {
        for (anchor in listOf(0f, 90f, 180f, 270f, 360f)) {
            for (offset in listOf(-2.5f, 0f, 2.5f)) {
                assertEquals(anchor, snapCollageRotation(anchor + offset), 0f)
            }
        }
        for (angle in listOf(15.25f, 85f, 94f, 174.5f, 284.25f)) {
            assertEquals(angle, snapCollageRotation(angle), 0f)
        }
    }

    @Test fun detentHoldsPastItsEntryZoneAndReleasesInBothDirections() {
        assertEquals(90f, snapCollageRotation(94.5f, 90f), 0f)
        assertEquals(90f, snapCollageRotation(85.5f, 90f), 0f)
        assertEquals(95.5f, snapCollageRotation(95.5f, 90f), 0f)
        assertEquals(84.5f, snapCollageRotation(84.5f, 90f), 0f)
        assertEquals(94f, snapCollageRotation(94f), 0f)
        assertEquals(90f, snapCollageRotation(92.5f), 0f)
    }

    @Test fun oldSignedAnglesKeepTheirOrientationAndFullTurnKeepsTheRightEndpoint() {
        assertEquals(270f, collageRotationSliderValue(-90f), 0f)
        assertEquals(180f, collageRotationSliderValue(-180f), 0f)
        assertEquals(90f, collageRotationSliderValue(450f), 0f)
        assertEquals(270f, collageRotationSliderValue(-450f), 0f)
        assertEquals(360f, collageRotationSliderValue(360f), 0f)
    }

    @Test fun photoSliderIncludesQuarterTurnsAndKeepsThemWhenFineRotationChanges() {
        assertEquals(90f, collagePhotoRotationSliderValue(0f, 1), 0f)
        assertEquals(135f, collagePhotoRotationSliderValue(45f, 1), 0f)
        assertEquals(315f, collagePhotoRotationSliderValue(45f, 3), 0f)
        assertEquals(180f, collagePhotoFrameRotation(270f, 1), 0f)
        assertEquals(270f, collagePhotoRotationSliderValue(180f, 1), 0f)
        assertEquals(90f, collagePhotoFrameRotation(360f, 3), 0f)
        assertEquals(360f, collagePhotoRotationSliderValue(90f, 3), 0f)
    }
}
