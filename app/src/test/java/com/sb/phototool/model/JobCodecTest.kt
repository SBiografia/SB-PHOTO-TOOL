package com.sb.phototool.model

import org.junit.Assert.assertEquals
import org.junit.Test
import com.sb.phototool.media.collageLayouts
import org.json.JSONObject

class JobCodecTest {
    @Test
    fun collageJob_roundTripsDirectManipulationAndStyling() {
        val original = CollageJob("new-editor", List(5) {
            TileTransform("content://$it", 1.6f, .3f, -.4f, 3, true, true)
        }, CanvasRatio.TWO_THREE, .012f, .04f, -100, ExportSize(ExportSizeMode.CUSTOM, 2500),
            layoutId = "hero-left", customCells = collageLayouts(5).first { it.id == "hero-left" }.cells,
            outerMarginPercent = .03f, borderWidthPercent = .005f, borderColor = -500)
        assertEquals(original, JobCodec.decode(JobCodec.encode(original)))
    }

    @Test
    fun legacyCollageJob_restoresWithUnchangedDefaults() {
        val legacy = CollageJob("old-editor", listOf(TileTransform("content://one")), CanvasRatio.SQUARE,
            .02f, .04f, -1, ExportSize())
        val json = JSONObject(JobCodec.encode(legacy))
        listOf("layoutId", "customCells", "outerMarginPercent", "borderWidthPercent", "borderColor", "mode").forEach(json::remove)
        val tile = json.getJSONArray("tiles").getJSONObject(0)
        listOf("quarterTurns", "flipHorizontal", "flipVertical", "freeform", "layer", "layoutTransform", "freeformTransform").forEach(tile::remove)
        assertEquals(legacy, JobCodec.decode(json.toString()))
    }

    @Test
    fun compressJob_roundTripsEveryUserSetting() {
        val original = CompressJob(
            id = "job-1",
            uris = listOf("content://one", "content://two"),
            outputFormat = ImageFormat.WEBP,
            resizeMode = ResizeMode.PERCENT,
            percent = 70,
            targetLongEdge = 0,
            maxBytes = 512_000,
            conflictPolicy = ConflictPolicy.MAX_SIZE_FIRST,
            keepMetadata = false,
        )

        assertEquals(original, JobCodec.decode(JobCodec.encode(original)))
    }

    @Test
    fun collageJob_roundTripsTileTransforms() {
        val original = CollageJob(
            id = "collage",
            tiles = listOf(TileTransform("content://one", 1.5f, -.2f, .3f)),
            ratio = CanvasRatio.STORY,
            gapPercent = .02f,
            cornerPercent = .04f,
            backgroundColor = -1,
            exportSize = ExportSize(ExportSizeMode.CUSTOM, 1800),
        )

        val decoded = JobCodec.decode(JobCodec.encode(original)) as CollageJob

        assertEquals(original.id, decoded.id)
        assertEquals(original.tiles.single(), decoded.tiles.single())
        assertEquals(ExportSizeMode.CUSTOM, decoded.exportSize.mode)
        assertEquals(1800, decoded.exportSize.customLongEdge)
    }
}
