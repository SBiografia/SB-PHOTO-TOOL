package com.sb.phototool.media

import android.content.ContentUris
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.model.*
import com.sb.phototool.ui.collage.templateDemoPhotos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class CollageTemplateRenderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder get() = File(context.getExternalFilesDir(null), "template-qa").apply { mkdirs() }
    private fun allOutputs(): Set<Uri> = buildSet {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            MediaStore.Images.Media.RELATIVE_PATH + " = ?", arrayOf("Pictures/SB PHOTO TOOL/Collage/"), null)?.use { c ->
            while (c.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)))
        }
    }
    private fun save(bitmap: Bitmap, path: String) { File(folder, path).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    @Test fun everyTemplateAndMask_rendersWithBundledFontsAndProducesContactSheets() {
        val sources = templateDemoPhotos()
        try {
            for (category in TemplateCategory.entries) {
                val sheet = Bitmap.createBitmap(1140, 1500, Bitmap.Config.ARGB_8888)
                val board = Canvas(sheet); board.drawColor(Color.rgb(240, 240, 240))
                collageTemplates.filter { it.category == category }.forEachIndexed { i, template ->
                    val job = template.document.copy(tiles = template.document.tiles.mapIndexed { n, p -> p.copy(uri = "demo-" + n % sources.size) })
                    val ratio = job.ratio.ratio!!
                    val width = 480; val height = (width / ratio).toInt()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    drawCollageComposition(Canvas(bitmap), job, width.toFloat(), height.toFloat(),
                        { sources[it.removePrefix("demo-").toInt()] }, font = { CollageFonts.get(context, it) })
                    save(bitmap, "catalog/" + template.id + ".png")
                    val scale = min(348f / width, 650f / height)
                    val x = 16 + i % 3 * 380f + (348 - width * scale) / 2
                    val y = 18 + i / 3 * 750f
                    board.drawBitmap(bitmap, null, RectF(x, y, x + width * scale, y + height * scale), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    board.drawText(template.name, 16 + i % 3 * 380f, y + 687f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 22f; typeface = CollageFonts.get(context, CollageFont.SANS) })
                    bitmap.recycle()
                }
                save(sheet, "contact-" + category.name.lowercase() + ".png"); sheet.recycle()
            }
            for (shape in PhotoShape.entries) {
                assertTrue(shape.name, shapeContains(shape, 200f, 200f, 100f, 100f))
            }
            assertFalse(shapeContains(PhotoShape.CIRCLE, 200f, 200f, 2f, 2f))
            assertFalse(shapeContains(PhotoShape.TRIANGLE, 200f, 200f, 5f, 5f))
        } finally { sources.forEach(Bitmap::recycle) }
    }

    @Test fun partialTemplate_firstEmptyAndRepeatedSources_exportsOnlyFilledFramesAndKeepsTextAndDecorations() = runBlocking {
        val source = templateDemoPhotos().first()
        val input = File(context.cacheDir, "partial-template-fixture.png")
        input.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val sourceBytes = input.readBytes()
        val uri = Uri.fromFile(input).toString()
        val job = CollageJob("partial-template", listOf(
            TileTransform("", freeform = NormalizedCell(.04f, .04f, .46f, .46f), elementId = "empty", matte = .05f, bottomMatte = .1f),
            TileTransform(uri, freeform = NormalizedCell(.54f, .04f, .96f, .46f), elementId = "filled", shape = PhotoShape.HEART, frameRotation = 9f),
            TileTransform(uri, freeform = NormalizedCell(.52f, .53f, .96f, .94f), elementId = "repeat", shape = PhotoShape.ARCH, frameRotation = -7f, layer = 1)),
            CanvasRatio.SQUARE, 0f, 0f, Color.rgb(239, 233, 220), ExportSize(ExportSizeMode.CUSTOM, 640), mode = CollageMode.FREEFORM,
            texts = listOf(CollageText("title", "우리의 순간\nMEMORIES", NormalizedCell(.04f, .57f, .48f, .86f), font = CollageFont.HAND, size = .06f, outline = .03f, shadow = true)),
            decorations = listOf(CollageDecoration("heart", DecorationKind.HEART, NormalizedCell(.09f, .86f, .2f, .96f), color = Color.RED)),
            templateId = "qa-partial", placementLocked = true)
        val before = allOutputs(); var produced = emptySet<Uri>()
        try {
            val result = MediaProcessor(context).execute(JobCodec.decode(JobCodec.encode(job))) { _, _, _ -> }
            assertEquals(1, result.succeeded); assertEquals(0, result.failed)
            produced = allOutputs() - before; assertEquals(1, produced.size)
            val actual = context.contentResolver.openInputStream(produced.single())!!.use { BitmapFactory.decodeStream(it) }
            assertEquals(640, actual.width); assertEquals(640, actual.height)
            val expected = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            drawCollageComposition(Canvas(expected), job, 640f, 640f, { source }, font = { CollageFonts.get(context, it) })
            var error = 0L; var n = 0
            for (y in 0 until 640 step 4) for (x in 0 until 640 step 4) {
                val a = actual.getPixel(x, y); val b = expected.getPixel(x, y)
                error += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)); n += 3
            }
            assertTrue("JPEG mean channel error: " + error.toFloat() / n, error.toFloat() / n < 7f)
            for ((x, y) in listOf(150 to 150, 30 to 30, 280 to 280)) {
                val color = actual.getPixel(x, y)
                assertTrue(abs(Color.red(color) - 239) < 5 && abs(Color.green(color) - 233) < 5 && abs(Color.blue(color) - 220) < 5)
            }
            assertArrayEquals(sourceBytes, input.readBytes())
            save(actual, "partial-export.png"); actual.recycle(); expected.recycle()
        } finally { produced.forEach { context.contentResolver.delete(it, null, null) }; source.recycle(); input.delete() }
    }

    @Test fun fifteenPhotosAndGradientBackground_exportAtRequestedDimensions() = runBlocking {
        val sources = templateDemoPhotos()
        val input = File(context.cacheDir, "fifteen-template-fixture.png")
        input.outputStream().use { sources[0].compress(Bitmap.CompressFormat.PNG, 100, it) }
        val template = collageTemplates.first { it.photoCount == 15 }.document
        val job = template.copy(id = "qa-fifteen", tiles = template.tiles.map { it.copy(uri = Uri.fromFile(input).toString()) },
            background = CollageBackground(BackgroundKind.GRADIENT, Color.rgb(88, 117, 125), angle = 45f), exportSize = ExportSize(ExportSizeMode.CUSTOM, 960))
        val before = allOutputs(); var produced = emptySet<Uri>()
        try {
            val result = MediaProcessor(context).execute(job) { _, _, _ -> }
            assertEquals(1, result.succeeded); produced = allOutputs() - before; assertEquals(1, produced.size)
            val actual = context.contentResolver.openInputStream(produced.single())!!.use { BitmapFactory.decodeStream(it) }
            assertEquals(960, maxOf(actual.width, actual.height)); save(actual, "fifteen-export.png"); actual.recycle()
        } finally { produced.forEach { context.contentResolver.delete(it, null, null) }; sources.forEach(Bitmap::recycle); input.delete() }
    }

    @Test fun blurBackgroundAndMissingPhoto_haveBoundedMemoryAndFailWithoutPartialPublication() = runBlocking {
        val sources = templateDemoPhotos()
        val blurred = blurredCollageBackground(sources[0], .8f)
        assertTrue(maxOf(blurred.width, blurred.height) <= 320)
        assertTrue(blurred.allocationByteCount <= 320 * 320 * 4)
        blurred.recycle()
        val input = File(context.cacheDir, "background-template-fixture.png")
        input.outputStream().use { sources[0].compress(Bitmap.CompressFormat.PNG, 100, it) }
        val template = collageTemplates.first().document
        val job = template.copy(id = "qa-bad", tiles = template.tiles.map { it.copy(uri = "file:///missing-template-photo.jpg") },
            background = CollageBackground(BackgroundKind.BLUR, uri = Uri.fromFile(input).toString()))
        val before = allOutputs()
        try {
            val failure = runCatching { MediaProcessor(context).execute(job) { _, _, _ -> } }.exceptionOrNull()
            assertTrue("The worker must receive the read failure", failure is java.io.FileNotFoundException)
            assertEquals(before, allOutputs())
        } finally { input.delete(); sources.forEach(Bitmap::recycle) }
    }
}
