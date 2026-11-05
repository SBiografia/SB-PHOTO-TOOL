package com.sb.phototool.media

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.model.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SamsungOriginalCompressionTest {
    @Test fun originalZipPhotos_keepExifGpsHdrSefVideoDatesAndNames() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val names = listOf("20261031_153707.jpg", "20261030_204122.jpg", "20261104_191702.jpg", "20261105_054743.jpg")
        val directory = File(context.cacheDir, "sef-input-${UUID.randomUUID()}").apply { mkdirs() }
        val evidence = JSONArray()
        fun album(): Set<Uri> = buildSet {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), "relative_path LIKE ?", arrayOf("Pictures/SB PHOTO TOOL/Compress/%"), null)?.use {
                while (it.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0)))
            }
        }
        for (name in names) {
            val data = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/sb-sef-$name")).use { it.readBytes() }
            assertTrue("Private original must be available", data.size > 1_000_000 && data[0] == 0xff.toByte())
            val source = File(directory, name).apply { writeBytes(data) }
            val time = FileTime.from(Instant.parse("2026-11-01T04:05:06.123Z"))
            Files.setLastModifiedTime(source.toPath(), time)
            val meta = ImageMetadata.read(source)
            meta.checkRecompressionSupport()
            assertNotNull(meta.samsungBytes)
            val originalExif = ExifInterface(source)
            assertNotNull(originalExif.latLong)
            val bitmap = BitmapFactory.decodeFile(source.absolutePath)
            val width = bitmap.width / 2; val height = bitmap.height / 2
            val wasNativeHdr = bitmap.hasGainmap()
            bitmap.recycle()
            val before = album()
            try {
                val job = CompressJob(UUID.randomUUID().toString(), listOf(Uri.fromFile(source).toString()), ImageFormat.JPEG,
                    ResizeMode.PERCENT, 50, 1920, 2 * 1024 * 1024, ConflictPolicy.PRESERVE_RESOLUTION, true)
                val preview = MediaProcessor(context).previewCompression(job)
                val swapsAxes = originalExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 5..8
                assertEquals(if (swapsAxes) height else width, preview.width)
                assertEquals(if (swapsAxes) width else height, preview.height)
                assertEquals(before, album())
                val result = MediaProcessor(context).execute(job) { _, _, _ -> }
                assertEquals(result.userMessage, 1, result.succeeded); assertEquals(result.userMessage, 0, result.failed)
                assertTrue(result.outputBytes < data.size)
                assertTrue(result.userMessage, result.details.all { it.contains("목표 용량") || it.contains("재편집") })
                if (name == "20261104_191702.jpg") {
                    assertTrue(result.userMessage.contains("재편집")); assertTrue(result.compressedOriginalUris.isEmpty())
                }
                val uri = (album() - before).single()
                val output = File(context.cacheDir, "sef-verified-$name")
                resolver.openInputStream(uri)!!.use { input -> output.outputStream().use { input.copyTo(it) } }
                val restored = ImageMetadata.read(output)
                restored.checkRecompressionSupport()
                assertArrayEquals(meta.samsungBytes, restored.samsungBytes)
                assertArrayEquals(meta.gainMapBytes, restored.gainMapBytes)
                val exif = ExifInterface(output)
                for (tag in listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_ORIENTATION)) assertEquals(originalExif.getAttribute(tag), exif.getAttribute(tag))
                assertArrayEquals(originalExif.latLong, exif.latLong, 0.0)
                val decoded = BitmapFactory.decodeFile(output.absolutePath)
                assertEquals(width, decoded.width); assertEquals(height, decoded.height)
                if (wasNativeHdr) assertTrue("Native HDR must remain recognized", decoded.hasGainmap())
                decoded.recycle()
                resolver.query(uri, arrayOf("_display_name", "datetaken", "date_modified", "_data"), null, null, null)!!.use {
                    assertTrue(it.moveToFirst()); assertEquals(name, it.getString(0)); assertEquals(meta.captureTimeMillis()!!, it.getLong(1))
                    assertEquals(time.toMillis() / 1000, it.getLong(2))
                    assertEquals(time, Files.getLastModifiedTime(File(it.getString(3)).toPath()))
                    evidence.put(JSONObject().put("uri", uri.toString()).put("name", name).put("dateTaken", it.getLong(1))
                        .put("dateModified", it.getLong(2)).put("modified", time.toString()))
                }
                assertArrayEquals(data, source.readBytes()); assertEquals(time, Files.getLastModifiedTime(source.toPath()))
            } catch (failure: Throwable) {
                (album() - before).forEach { resolver.delete(it, null, null) }
                throw failure
            } finally { source.delete() }
        }
        File(context.cacheDir, "sef-lifecycle.json").writeText(evidence.toString())
        directory.delete()
        Unit
    }
}

@RunWith(AndroidJUnit4::class)
class SamsungOriginalRestartTest {
    @Test fun finalFilesAndDatesSurviveAnotherAppProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val file = File(context.cacheDir, "sef-lifecycle.json")
        val evidence = JSONArray(file.readText())
        assertEquals(4, evidence.length())
        for (i in 0 until evidence.length()) {
            val expected = evidence.getJSONObject(i)
            val uri = Uri.parse(expected.getString("uri"))
            try {
                resolver.query(uri, arrayOf("_display_name", "datetaken", "date_modified", "_data"), null, null, null)!!.use {
                    assertTrue(it.moveToFirst()); assertEquals(expected.getString("name"), it.getString(0))
                    assertEquals(expected.getLong("dateTaken"), it.getLong(1)); assertEquals(expected.getLong("dateModified"), it.getLong(2))
                    assertEquals(expected.getString("modified"), Files.getLastModifiedTime(File(it.getString(3)).toPath()).toString())
                }
                val cached = File(context.cacheDir, "sef-verified-${expected.getString("name")}")
                assertArrayEquals(cached.readBytes(), resolver.openInputStream(uri)!!.use { it.readBytes() })
            } finally { resolver.delete(uri, null, null) }
        }
        file.delete()
    }
}
