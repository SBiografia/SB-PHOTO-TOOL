package com.sb.phototool.media

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class CollageExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(index: Int): Pair<Uri, Bitmap> {
        val bitmap = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { band, color ->
            paint.color = color
            canvas.drawRect(0f, band * 100f, 240f, (band + 1) * 100f, paint)
        }
        paint.color = Color.CYAN
        canvas.drawRect(0f, 0f, 40f, 400f, paint)
        val file = File(context.cacheDir, "collage-export-$index.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file) to bitmap
    }

    private fun albumPhotos(album: String): Set<Uri> = buildSet {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf("Pictures/SB PHOTO TOOL/$album/"), null)?.use { cursor ->
            while (cursor.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
        }
    }

    @Test
    fun exportedJpeg_matchesPreviewGeometryIncludingCustomBoundariesAndRotation() = runBlocking {
        val sources = List(5) { fixture(it) }
        val tiles = sources.mapIndexed { i, source -> TileTransform(source.first.toString(), 1.3f, .3f, -.2f, i % 4, i % 2 == 0) }
        val base = collageLayouts(5).first { it.id == "hero-center" }.cells
        val job = CollageJob("render-check", tiles, CanvasRatio.LANDSCAPE, .014f, .04f, Color.LTGRAY,
            ExportSize(ExportSizeMode.CUSTOM, 640), "hero-center", moveCollageBoundary(base, true, 1f / 3, .05f),
            outerMarginPercent = .04f, borderWidthPercent = .006f, borderColor = Color.WHITE)
        val before = albumPhotos("Collage")
        var produced = emptySet<Uri>()
        try {
            val summary = MediaProcessor(context).execute(JobCodec.decode(JobCodec.encode(job))) { _, _, _ -> }
            assertEquals(1, summary.succeeded)
            assertEquals(0, summary.failed)
            produced = albumPhotos("Collage") - before
            assertEquals(1, produced.size)
            val result = context.contentResolver.openInputStream(produced.single())!!.use { BitmapFactory.decodeStream(it) }
            assertEquals(640, result.width)
            assertEquals(360, result.height)
            val expected = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(expected)
            canvas.drawColor(job.backgroundColor)
            collageTileBounds(job, 640f, 360f).forEachIndexed { i, rect ->
                drawCollagePhoto(canvas, sources[i].second, rect, tiles[i], 360f * job.cornerPercent,
                    360f * job.borderWidthPercent, job.borderColor)
            }
            var channelError = 0L
            var samples = 0
            for (y in 0 until 360 step 4) for (x in 0 until 640 step 4) {
                val actual = result.getPixel(x, y)
                val wanted = expected.getPixel(x, y)
                channelError += abs(Color.red(actual) - Color.red(wanted)) + abs(Color.green(actual) - Color.green(wanted)) + abs(Color.blue(actual) - Color.blue(wanted))
                samples += 3
            }
            assertTrue("Preview/export mean color error = ${channelError.toFloat() / samples}", channelError.toFloat() / samples < 7f)
            val output = File(context.getExternalFilesDir(null), "collage-qa").apply { mkdirs() }
            File(output, "07-export.jpg").outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            result.recycle(); expected.recycle()
        } finally {
            produced.forEach { context.contentResolver.delete(it, null, null) }
            sources.forEach { it.second.recycle() }
        }
    }

    @Test
    fun freeformExport_matchesPreviewWithOverlapCroppingAndLayerOrder() = runBlocking {
        val sources = List(3) { fixture(20 + it) }
        val job = CollageJob("freeform-export", sources.mapIndexed { i, source ->
            TileTransform(source.first.toString(), zoom = 1.2f + i * .2f, panX = .4f, panY = -.3f,
                quarterTurns = i, freeform = NormalizedCell(.05f + i * .17f, .08f + i * .12f, .65f + i * .17f, .72f + i * .12f), layer = 2 - i)
        }, CanvasRatio.STORY, 0f, .025f, Color.WHITE, ExportSize(ExportSizeMode.CUSTOM, 640),
            borderWidthPercent = .005f, borderColor = Color.BLACK, mode = CollageMode.FREEFORM)
        val before = albumPhotos("Collage")
        var produced = emptySet<Uri>()
        try {
            val summary = MediaProcessor(context).execute(JobCodec.decode(JobCodec.encode(job))) { _, _, _ -> }
            assertEquals(1, summary.succeeded)
            produced = albumPhotos("Collage") - before
            assertEquals(1, produced.size)
            val result = context.contentResolver.openInputStream(produced.single())!!.use { BitmapFactory.decodeStream(it) }
            assertEquals(360, result.width); assertEquals(640, result.height)
            val expected = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(expected).apply { drawColor(job.backgroundColor) }
            val bounds = collageTileBounds(job, 360f, 640f)
            collagePaintOrder(job).forEach { i ->
                drawCollagePhoto(canvas, sources[i].second, bounds[i], job.tiles[i], 360 * job.cornerPercent,
                    360 * job.borderWidthPercent, job.borderColor)
            }
            var total = 0L
            var samples = 0
            for (y in 0 until 640 step 4) for (x in 0 until 360 step 4) {
                val actual = result.getPixel(x, y)
                val wanted = expected.getPixel(x, y)
                total += abs(Color.red(actual) - Color.red(wanted)) + abs(Color.green(actual) - Color.green(wanted)) + abs(Color.blue(actual) - Color.blue(wanted))
                samples += 3
            }
            assertTrue("Freeform preview/export error: ${total.toFloat() / samples}", total.toFloat() / samples < 7)
            assertEquals(Color.WHITE, result.getPixel(2, 2))
            result.recycle(); expected.recycle()
        } finally {
            produced.forEach { context.contentResolver.delete(it, null, null) }
            sources.forEach { it.second.recycle() }
        }
    }

    @Test
    fun sharedRenderer_rotatesAndReflectsInCanvasCoordinates() {
        val bitmap = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.RED)
            drawRect(50f, 0f, 100f, 50f, Paint().apply { color = Color.BLUE })
        }
        val result = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val rect = PhotoRect(0f, 0f, 100f, 200f)
        drawCollagePhoto(Canvas(result), bitmap, rect, TileTransform("", quarterTurns = 1), 0f)
        assertEquals(Color.RED, result.getPixel(50, 25))
        assertEquals(Color.BLUE, result.getPixel(50, 175))
        drawCollagePhoto(Canvas(result), bitmap, rect, TileTransform("", quarterTurns = 1, flipVertical = true), 0f)
        assertEquals(Color.BLUE, result.getPixel(50, 25))
        assertEquals(Color.RED, result.getPixel(50, 175))
        bitmap.recycle(); result.recycle()
    }

    @Test
    fun neighboringSplitExport_stillProducesAllThreeTiles() = runBlocking {
        val source = fixture(8)
        val before = albumPhotos("Split")
        var produced = emptySet<Uri>()
        try {
            val job = SplitJob("split-regression", source.first.toString(), 3, 1, SplitFitMode.FILL, Color.WHITE, false,
                ExportSize(ExportSizeMode.CUSTOM, 960))
            val summary = MediaProcessor(context).execute(job) { _, _, _ -> }
            produced = albumPhotos("Split") - before
            assertEquals(3, summary.succeeded)
            assertEquals(3, produced.size)
        } finally {
            produced.forEach { context.contentResolver.delete(it, null, null) }
            source.second.recycle()
        }
    }
}
