package com.sb.phototool.media

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ToolExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun source(): File {
        val image = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { band, color ->
            canvas.drawRect(0f, band * 100f, 240f, (band + 1) * 100f, Paint().apply { this.color = color })
        }
        val file = File.createTempFile("tools-export-", ".jpg", context.cacheDir)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }; image.recycle()
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:30 01:00:00"); saveAttributes() }
        return file
    }
    private fun album(name: String): Set<Uri> = buildSet {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), "relative_path = ?", arrayOf("Pictures/SB PHOTO TOOL/$name/"), null)?.use { cursor ->
            while (cursor.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
        }
    }
    private fun read(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    @Test fun compressionSample_matchesRealOutput_preservesOriginalAndMetadata() = runBlocking {
        val file = source(); val original = file.readBytes(); val before = album("Compress")
        val processor = MediaProcessor(context)
        val job = CompressJob("compress-test", listOf(Uri.fromFile(file).toString()), ImageFormat.JPEG, ResizeMode.PERCENT, 50, 1920, 100 * 1024, ConflictPolicy.MAX_SIZE_FIRST, true)
        try {
            val preview = processor.previewCompression(job)
            assertEquals(before, album("Compress"))
            assertEquals(120, preview.width); assertEquals(200, preview.height)
            assertTrue(preview.targetMet); assertFalse(preview.copied)
            val summary = processor.execute(job) { _, _, _ -> }
            assertEquals(1, summary.succeeded); assertEquals(1, summary.measuredFiles)
            assertEquals(original.size.toLong(), summary.inputBytes)
            assertEquals(preview.outputBytes, summary.outputBytes)
            val exported = (album("Compress") - before).single()
            assertEquals(preview.outputBytes, read(exported).size.toLong())
            context.contentResolver.openInputStream(exported)!!.use { assertEquals("2026:09:30 01:00:00", ExifInterface(it).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)) }
            assertArrayEquals(original, file.readBytes())
        } finally { (album("Compress") - before).forEach { context.contentResolver.delete(it, null, null) }; file.delete() }
    }

    @Test fun alreadySmallCompression_copiesWithoutReencoding() = runBlocking {
        val file = source(); val before = album("Compress")
        val job = CompressJob("copy-test", listOf(Uri.fromFile(file).toString()), ImageFormat.JPEG, ResizeMode.NONE, 100, 0, 1024 * 1024, ConflictPolicy.MAX_SIZE_FIRST, true)
        try {
            assertTrue(MediaProcessor(context).previewCompression(job).copied)
            assertEquals(1, MediaProcessor(context).execute(job) { _, _, _ -> }.succeeded)
            assertArrayEquals(file.readBytes(), read((album("Compress") - before).single()))
        } finally { (album("Compress") - before).forEach { context.contentResolver.delete(it, null, null) }; file.delete() }
    }

    @Test fun customFourColumnGrid_exportsOnlyTheSelectedBand() = runBlocking {
        val file = source(); val before = album("Split")
        try {
            val crop = NormalizedCell(0f, .26f, 1f, .41f)
            val job = SplitJob("crop-test", Uri.fromFile(file).toString(), 4, 1, SplitFitMode.FILL, Color.WHITE, false, ExportSize(ExportSizeMode.CUSTOM, 960), crop)
            assertEquals(4, MediaProcessor(context).execute(job) { _, _, _ -> }.succeeded)
            val results = album("Split") - before
            assertEquals(4, results.size)
            results.forEach { uri ->
                val bitmap = BitmapFactory.decodeByteArray(read(uri), 0, read(uri).size)
                assertEquals(240, bitmap.width); assertEquals(240, bitmap.height)
                val pixel = bitmap.getPixel(120, 120)
                assertTrue(Color.green(pixel) > 245 && Color.red(pixel) < 10 && Color.blue(pixel) < 10)
                bitmap.recycle()
            }
        } finally { (album("Split") - before).forEach { context.contentResolver.delete(it, null, null) }; file.delete() }
    }

    @Test fun photoGif_exportsEveryFrameWithRequestedTiming() = runBlocking {
        val files = List(3) { source() }; val before = album("GIF")
        try {
            val job = GifJob("gif-test", files.map { Uri.fromFile(it).toString() }, GifOutputFormat.GIF, 12, 160, 0, 500, Color.WHITE)
            assertEquals(1, MediaProcessor(context).execute(job) { _, _, _ -> }.succeeded)
            val gif = inspectGif(read((album("GIF") - before).single()))
            assertEquals(listOf(3, 1500, 96, 160), gif)
        } finally { (album("GIF") - before).forEach { context.contentResolver.delete(it, null, null) }; files.forEach { it.delete() } }
    }

    private fun inspectGif(data: ByteArray): List<Int> {
        assertEquals("GIF89a", String(data, 0, 6, Charsets.US_ASCII))
        fun u(i: Int) = data[i].toInt() and 255
        fun word(i: Int) = u(i) + (u(i + 1) shl 8)
        var cursor = 13 + if (u(10) and 128 != 0) 3 * (1 shl ((u(10) and 7) + 1)) else 0
        var frames = 0; var duration = 0
        fun skipBlocks() { while (true) { val length = u(cursor++); if (length == 0) break; cursor += length } }
        while (cursor < data.size) {
            when (u(cursor++)) {
                0x3B -> break
                0x21 -> { val label = u(cursor++); if (label == 0xF9) duration += word(cursor + 2) * 10; skipBlocks() }
                0x2C -> { frames++; val packed = u(cursor + 8); cursor += 9; if (packed and 128 != 0) cursor += 3 * (1 shl ((packed and 7) + 1)); cursor++; skipBlocks() }
                else -> error("Invalid GIF block at $cursor")
            }
        }
        return listOf(frames, duration, word(6), word(8))
    }
}
