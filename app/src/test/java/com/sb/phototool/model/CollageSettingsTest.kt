package com.sb.phototool.model

import com.sb.phototool.media.NormalizedCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollageSettingsTest {
    private fun job(
        photos: Int,
        ratio: CanvasRatio = CanvasRatio.SQUARE,
        layoutId: String = COLLAGE_AUTO_LAYOUT_ID,
        customCells: List<NormalizedCell> = emptyList(),
        gapPercent: Float = COLLAGE_DEFAULT_GAP_PERCENT,
        templateId: String? = null,
        mode: CollageMode = CollageMode.LAYOUT,
        background: CollageBackground = CollageBackground(),
    ) = CollageJob(
        id = "draft",
        tiles = List(photos) { index -> TileTransform("photo://$index", elementId = "photo-" + index) },
        ratio = ratio, gapPercent = gapPercent, cornerPercent = 0f, backgroundColor = -1, exportSize = ExportSize(),
        layoutId = layoutId, customCells = customCells, mode = mode, background = background, templateId = templateId,
    )

    @Test
    fun layoutIsRememberedForItsPhotoCountOnly() {
        val settings = CollageSettings().rememberFrom(job(4, layoutId = "hero-left"))
        assertEquals("hero-left", settings.layoutChoice(4).layoutId)
        assertEquals(COLLAGE_AUTO_LAYOUT_ID, settings.layoutChoice(3).layoutId)
        assertEquals(COLLAGE_AUTO_LAYOUT_ID, settings.layoutChoice(15).layoutId)
    }

    @Test
    fun unknownLayoutIdFallsBackToTheAutomaticLayout() {
        val settings = CollageSettings(layouts = mapOf(4 to CollageLayoutChoice("layout-that-left-the-app")))
        assertEquals(COLLAGE_AUTO_LAYOUT_ID, settings.layoutChoice(4).layoutId)
    }

    @Test
    fun handMovedGridLinesSurviveOnlyForTheCountTheyFit() {
        val cells = listOf(NormalizedCell(0f, 0f, .4f, 1f), NormalizedCell(.4f, 0f, 1f, 1f))
        val settings = CollageSettings().rememberFrom(job(2, layoutId = "cols-0", customCells = cells))
        assertEquals(cells, settings.layoutChoice(2).customCells)
        assertTrue(settings.layoutChoice(3).customCells.isEmpty())
    }

    @Test
    fun ratioAndStylingCarryIntoTheNextDraft() {
        val settings = CollageSettings().rememberFrom(job(2, ratio = CanvasRatio.STORY, gapPercent = .02f))
        assertEquals(CanvasRatio.STORY, settings.ratio)
        assertEquals(.02f, settings.gapPercent, .0001f)
    }

    @Test
    fun templateJobDoesNotReplaceRememberedSettings() {
        val plain = CollageSettings(ratio = CanvasRatio.PORTRAIT, gapPercent = .02f, backgroundColor = -2)
            .rememberFrom(job(2, layoutId = "hero-left"))
        val afterTemplate = plain.rememberFrom(job(6, ratio = CanvasRatio.STORY, gapPercent = 0f,
            templateId = "film-1", mode = CollageMode.FREEFORM))
        assertEquals(plain, afterTemplate)
    }

    @Test
    fun freeformJobKeepsTheLastGridLayout() {
        val plain = CollageSettings().rememberFrom(job(3, layoutId = "hero-top"))
        val freeform = plain.rememberFrom(job(3, layoutId = COLLAGE_AUTO_LAYOUT_ID, mode = CollageMode.FREEFORM))
        assertEquals("hero-top", freeform.layoutChoice(3).layoutId)
    }

    @Test
    fun emptyDraftKeepsEverything() {
        val settings = CollageSettings(ratio = CanvasRatio.LANDSCAPE)
        assertEquals(settings, settings.rememberFrom(job(0)))
    }

    @Test
    fun patternBackgroundIsRememberedButPhotoBackgroundIsNot() {
        val pattern = CollageBackground(BackgroundKind.PATTERN, secondaryColor = 0xFF112233.toInt(),
            pattern = BackgroundPattern.DOTS)
        val remembered = CollageSettings().rememberFrom(job(2, background = pattern)).rememberFrom(
            job(2, background = CollageBackground(BackgroundKind.PHOTO, uri = "content://photo/1")))
        assertEquals(pattern, remembered.background)
    }
}
