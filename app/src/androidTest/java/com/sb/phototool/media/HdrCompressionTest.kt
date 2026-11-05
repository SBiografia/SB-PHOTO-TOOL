package com.sb.phototool.media

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.data.DiagnosticLog
import com.sb.phototool.model.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HdrCompressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private fun input(which: String): File {
        require(which in listOf("a", "b"))
        val data = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/sb-photo-hdr-$which.jpg")).use { it.readBytes() }
        assertTrue("Private local HDR sample is not available", data.size > 2 && data[0] == 0xff.toByte() && data[1] == 0xd8.toByte())
        return File(context.cacheDir, "hdr-validation-$which.jpg").apply {
            writeBytes(data)
            Files.setLastModifiedTime(toPath(), FileTime.from(Instant.parse("2026-11-01T04:05:06.123Z")))
        }
    }
    private fun album(): Set<Uri> = buildSet {
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), "relative_path LIKE ?", arrayOf("Pictures/SB PHOTO TOOL/Compress/%"), null)?.use {
            while (it.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0)))
        }
    }
    private fun job(file: File) = CompressJob(UUID.randomUUID().toString(), listOf(Uri.fromFile(file).toString()), ImageFormat.JPEG,
        ResizeMode.PERCENT, 70, 1920, 1024 * 1024, ConflictPolicy.MAX_SIZE_FIRST, true)

    @Test fun providedHdrPhotos_recompressWithOriginalAuxiliaryAndNativeHdrRecognition() = runBlocking {
        for (which in listOf("a", "b")) {
            val source = input(which)
            val sourceBytes = source.readBytes()
            val sourceTime = Files.getLastModifiedTime(source.toPath())
            val sourceMetadata = ImageMetadata.read(source)
            assertTrue(sourceMetadata.hasGainMap)
            val decodedSource = BitmapFactory.decodeFile(source.absolutePath)
            assertTrue("Input must be recognized as Ultra HDR by Android", decodedSource.hasGainmap())
            val originalGain = decodedSource.gainmap!!
            val before = album()
            try {
                val settings = job(source).copy(maxBytes = sourceBytes.size.toLong() / 2)
                val preview = MediaProcessor(context).previewCompression(settings)
                assertEquals(896, preview.width); assertEquals(672, preview.height)
                assertEquals(before, album())
                val summary = MediaProcessor(context).execute(settings) { _, _, _ -> }
                assertEquals(summary.userMessage, 1, summary.succeeded)
                assertEquals(summary.userMessage, 0, summary.failed)
                assertTrue(summary.userMessage, summary.details.all { it.contains("목표 용량") })
                assertTrue("A smaller target must reduce file size while retaining HDR", summary.outputBytes < sourceBytes.size)
                val outputUri = (album() - before).single()
                val output = File(context.cacheDir, "hdr-verified-$which.jpg")
                resolver.openInputStream(outputUri)!!.use { input -> output.outputStream().use { input.copyTo(it) } }
                val result = ImageMetadata.read(output)
                assertTrue(result.hasGainMap)
                assertArrayEquals(sourceMetadata.gainMapBytes, result.gainMapBytes)
                for ((original, restored) in sourceMetadata.blocks.zip(result.blocks)) {
                    assertEquals(original.kind, restored.kind)
                    if (!HdrJpeg.isMpf(original)) assertArrayEquals(original.data, restored.data)
                }
                val decodedOutput = BitmapFactory.decodeFile(output.absolutePath)
                assertEquals(896, decodedOutput.width); assertEquals(672, decodedOutput.height)
                assertTrue("Output must remain an Ultra HDR image", decodedOutput.hasGainmap())
                assertEquals(decodedSource.colorSpace, decodedOutput.colorSpace)
                val gain = decodedOutput.gainmap!!
                assertArrayEquals(originalGain.ratioMin, gain.ratioMin, 0.00001f)
                assertArrayEquals(originalGain.ratioMax, gain.ratioMax, 0.00001f)
                assertArrayEquals(originalGain.gamma, gain.gamma, 0.00001f)
                assertArrayEquals(originalGain.epsilonSdr, gain.epsilonSdr, 0.00001f)
                assertArrayEquals(originalGain.epsilonHdr, gain.epsilonHdr, 0.00001f)
                assertEquals(originalGain.displayRatioForFullHdr, gain.displayRatioForFullHdr, 0.00001f)
                assertEquals(originalGain.gainmapContents.width, gain.gainmapContents.width)
                assertEquals(originalGain.gainmapContents.height, gain.gainmapContents.height)
                resolver.query(outputUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(sourceTime, Files.getLastModifiedTime(File(it.getString(0)).toPath()))
                }
                decodedOutput.recycle()
                assertArrayEquals(sourceBytes, source.readBytes())
                assertEquals(sourceTime, Files.getLastModifiedTime(source.toPath()))
            } finally {
                (album() - before).forEach { resolver.delete(it, null, null) }
                decodedSource.recycle(); source.delete()
            }
        }
    }

    @Test fun unsupportedTrailer_isLoggedWithActualReasonAndNoMotionMisclassification() = runBlocking {
        val source = input("a")
        source.appendBytes(byteArrayOf(0))
        val before = album()
        try {
            val result = MediaProcessor(context).execute(job(source)) { _, _, _ -> }
            assertEquals(0, result.succeeded); assertEquals(1, result.failed)
            assertTrue(result.userMessage.contains("HDR"))
            assertFalse(result.userMessage.contains("모션사진"))
            assertEquals(before, album())
            val log = DiagnosticLog.snapshot(context)
            assertTrue(log.contains("compress/file_failed")); assertTrue(log.contains("HDR"))
        } finally { source.delete() }
    }
}
