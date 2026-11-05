package com.sb.phototool.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.phototool.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32

@RunWith(AndroidJUnit4::class)
class CompressionMetadataTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val outputUris = mutableListOf<Uri>()
    private val sourceFiles = mutableListOf<File>()
    private fun fixture(name: String) = instrumentation.context.assets.open("metadata/$name").use { it.readBytes() }
    private fun bytes(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use(write) }.toByteArray()
    private fun source(extension: String = "JPG", zoned: Boolean = true, flat: Boolean = false, name: String? = null, wideGamut: Boolean = false): File {
        val dir = File(context.cacheDir, "metadata-test-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(dir, name ?: "촬영 원본.$extension")
        sourceFiles += file
        val bitmap = if (wideGamut) Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888, true, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3))
            else Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        if (!flat) for (y in 0 until 400) for (x in 0 until 240) bitmap.setPixel(x, y, if (y < 200) Color.RED else Color.BLUE)
        file.outputStream().use { bitmap.compress(when (extension.lowercase()) { "png" -> Bitmap.CompressFormat.PNG; "webp" -> Bitmap.CompressFormat.WEBP; else -> Bitmap.CompressFormat.JPEG }, 95, it) }
        bitmap.recycle()
        val tiff = fixture(if (zoned) "original-le.tiff" else "unzoneddatetime.tiff")
        val image = file.readBytes()
        file.writeBytes(when (extension.lowercase()) {
            "png" -> {
                val chunk = bytes { out ->
                    out.writeInt(tiff.size); out.writeBytes("eXIf"); out.write(tiff)
                    out.writeInt(CRC32().apply { update("eXIf".toByteArray()); update(tiff) }.value.toInt())
                }
                image.copyOfRange(0, 33) + chunk + image.copyOfRange(33, image.size)
            }
            "webp" -> {
                val payload = "Exif\u0000\u0000".toByteArray() + tiff
                var header = ByteArray(10).also {
                    it[0] = 8
                    for (i in 0..2) { it[4 + i] = (239 ushr (8 * i)).toByte(); it[7 + i] = (399 ushr (8 * i)).toByte() }
                }
                val pixelChunks = ByteArrayOutputStream()
                var pos = 12
                while (pos < image.size) {
                    val kind = image.copyOfRange(pos, pos + 4).toString(Charsets.US_ASCII)
                    val size = java.nio.ByteBuffer.wrap(image, pos + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    val end = pos + 8 + size + size % 2
                    if (kind == "VP8X") header = image.copyOfRange(pos + 8, pos + 8 + size).also { it[0] = (it[0].toInt() or 8).toByte() }
                    else pixelChunks.write(image, pos, end - pos)
                    pos = end
                }
                val body = bytes { out ->
                    out.writeBytes("WEBP"); out.writeBytes("VP8X"); out.writeInt(Integer.reverseBytes(10)); out.write(header)
                    out.write(pixelChunks.toByteArray())
                    out.writeBytes("EXIF"); out.writeInt(Integer.reverseBytes(payload.size)); out.write(payload)
                    if (payload.size % 2 != 0) out.writeByte(0)
                }
                bytes { it.writeBytes("RIFF"); it.writeInt(Integer.reverseBytes(body.size)); it.write(body) }
            }
            else -> {
                val payload = "Exif\u0000\u0000".toByteArray() + tiff
                image.copyOfRange(0, 2) + bytes { it.writeShort(0xffe1); it.writeShort(payload.size + 2); it.write(payload) } + image.copyOfRange(2, image.size)
            }
        })
        Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.parse("2026-11-01T04:05:06.123Z")))
        return file
    }

    private fun album(): Set<Uri> = buildSet {
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"), "relative_path LIKE ?", arrayOf("Pictures/SB PHOTO TOOL/Compress/%"), null)?.use {
            while (it.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0)))
        }
    }
    private fun job(file: File, resize: ResizeMode = ResizeMode.PERCENT, metadata: Boolean = true, maxBytes: Long = 1024 * 1024) =
        CompressJob(UUID.randomUUID().toString(), listOf(Uri.fromFile(file).toString()), ImageFormat.JPEG, resize, 50, 1920, maxBytes, ConflictPolicy.MAX_SIZE_FIRST, metadata)

    private suspend fun export(job: CompressJob): Pair<ProcessingSummary, Uri> {
        val before = album()
        val result = MediaProcessor(context).execute(job) { _, _, _ -> }
        val added = album() - before
        outputUris += added
        assertEquals(result.userMessage, 1, result.succeeded)
        return result to added.single()
    }
    private fun read(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun longValue(uri: Uri, column: String): Long? = resolver.query(uri, arrayOf(column), null, null, null)!!.use {
        assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getLong(0)
    }
    private fun stringValue(uri: Uri, column: String): String = resolver.query(uri, arrayOf(column), null, null, null)!!.use { assertTrue(it.moveToFirst()); it.getString(0) }
    private fun modified(uri: Uri): FileTime = resolver.openFileDescriptor(uri, "r")!!.use {
        val stat = Os.fstat(it.fileDescriptor)
        FileTime.from(Instant.ofEpochSecond(stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec))
    }
    private fun cleanup() {
        outputUris.forEach { resolver.delete(it, null, null) }
        sourceFiles.forEach { it.delete(); it.parentFile?.delete() }
    }

    @Test fun jpeg_keepsAllExifGpsMakerNoteFractionNameAndActualMtime() = runBlocking {
        try {
            val original = source()
            val before = original.readBytes()
            val sourceTime = Files.getLastModifiedTime(original.toPath())
            val (summary, uri) = export(job(original))
            assertTrue(summary.userMessage, summary.details.isEmpty())
            assertEquals(original.name, stringValue(uri, MediaStore.MediaColumns.DISPLAY_NAME))
            assertEquals(sourceTime, modified(uri))
            assertEquals(sourceTime.toMillis() / 1000, longValue(uri, MediaStore.MediaColumns.DATE_MODIFIED))
            assertEquals(Instant.parse("2026-10-31T18:04:05.123Z").toEpochMilli(), longValue(uri, MediaStore.MediaColumns.DATE_TAKEN))
            val temp = File(context.cacheDir, "metadata-export-check.jpg").apply { writeBytes(read(uri)) }
            try {
                val block = ImageMetadata.read(temp).blocks.first { it.kind == "225" }
                assertArrayEquals("Exif\u0000\u0000".toByteArray() + fixture("resized-le.tiff"), block.data)
                val exif = ExifInterface(temp)
                assertEquals(6, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
                assertEquals("123456", exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
                assertEquals("+09:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                val bitmap = BitmapFactory.decodeFile(temp.absolutePath)
                assertEquals(120, bitmap.width); assertEquals(200, bitmap.height)
                assertTrue(Color.red(bitmap.getPixel(20, 20)) > 200)
                assertTrue(Color.blue(bitmap.getPixel(20, 180)) > 200)
                bitmap.recycle()
            } finally { temp.delete() }
            assertArrayEquals(before, original.readBytes())
            assertEquals(sourceTime, Files.getLastModifiedTime(original.toPath()))
        } finally { cleanup() }
    }

    @Test fun pngAndWebp_keepOriginalFormatAndOpaqueExif() = runBlocking {
        try {
            for (extension in listOf("png", "webp")) {
                val original = source(extension)
                val (_, uri) = export(job(original))
                assertEquals(original.name, stringValue(uri, MediaStore.MediaColumns.DISPLAY_NAME))
                val temp = File(context.cacheDir, "metadata-output.$extension").apply { writeBytes(read(uri)) }
                try {
                    val metadata = ImageMetadata.read(temp)
                    assertEquals(extension, metadata.format)
                    val block = metadata.blocks.first { it.kind in setOf("eXIf", "EXIF") }
                    val expected = if (extension == "webp") "Exif\u0000\u0000".toByteArray() + fixture("resized-le.tiff") else fixture("resized-le.tiff")
                    assertArrayEquals(expected, block.data)
                    val bitmap = BitmapFactory.decodeFile(temp.absolutePath)
                    assertNotNull(bitmap); assertEquals(120, bitmap.width); assertEquals(200, bitmap.height); bitmap.recycle()
                } finally { temp.delete() }
            }
        } finally { cleanup() }
    }

    @Test fun displayP3_preservesProfileAndPixelColorSpace() = runBlocking {
        try {
            val original = source(wideGamut = true)
            val sourceProfile = ImageMetadata.read(original).blocks.filter { it.kind == "226" }
            assertTrue(sourceProfile.isNotEmpty())
            val (_, uri) = export(job(original))
            val temp = File(context.cacheDir, "metadata-p3-check.jpg").apply { writeBytes(read(uri)) }
            try {
                val outputProfile = ImageMetadata.read(temp).blocks.filter { it.kind == "226" }
                assertEquals(sourceProfile.size, outputProfile.size)
                sourceProfile.indices.forEach { assertArrayEquals(sourceProfile[it].data, outputProfile[it].data) }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temp.absolutePath, options)
                assertEquals(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3), options.outColorSpace)
            } finally { temp.delete() }
        } finally { cleanup() }
    }

    @Test fun duplicateNames_useDifferentFoldersWithoutRenamingOrOverwriting() = runBlocking {
        try {
            val first = source(name = "동일 이름.JPG")
            val second = source(name = "동일 이름.JPG")
            val (_, firstUri) = export(job(first))
            val firstBytes = read(firstUri)
            val (_, secondUri) = export(job(second))
            assertEquals(first.name, stringValue(firstUri, MediaStore.MediaColumns.DISPLAY_NAME))
            assertEquals(second.name, stringValue(secondUri, MediaStore.MediaColumns.DISPLAY_NAME))
            assertNotEquals(stringValue(firstUri, MediaStore.MediaColumns.RELATIVE_PATH), stringValue(secondUri, MediaStore.MediaColumns.RELATIVE_PATH))
            assertArrayEquals(firstBytes, read(firstUri))
        } finally { cleanup() }
    }

    @Test fun directCopy_isByteIdenticalAndKeepsFilesystemDate() = runBlocking {
        try {
            val original = source()
            val (_, uri) = export(job(original, ResizeMode.NONE))
            assertArrayEquals(original.readBytes(), read(uri))
            assertEquals(Files.getLastModifiedTime(original.toPath()), modified(uri))
        } finally { cleanup() }
    }

    @Test fun missingZone_isReportedWithoutAddingAnInventedOffset() = runBlocking {
        try {
            val original = source(zoned = false)
            val (summary, uri) = export(job(original))
            assertTrue(summary.userMessage, summary.userMessage.contains("시간대"))
            resolver.openInputStream(uri)!!.use { assertNull(ExifInterface(it).getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)) }
            assertTrue(summary.compressedOriginalUris.isEmpty())
        } finally { cleanup() }
    }

    @Test fun metadataFailure_isVisibleAndPublishesNothing() = runBlocking {
        try {
            val original = source()
            val bytes = original.readBytes()
            for (i in 16..19) bytes[i] = 0x7f
            original.writeBytes(bytes)
            val before = album()
            val result = MediaProcessor(context).execute(job(original)) { _, _, _ -> }
            assertEquals(0, result.succeeded); assertEquals(1, result.failed)
            assertTrue(result.userMessage, result.userMessage.contains("메타데이터"))
            assertEquals(before, album()); assertArrayEquals(bytes, original.readBytes())
        } finally { cleanup() }
    }

    @Test fun metadataIsNotDiscardedToMeetSizeLimit() = runBlocking {
        try {
            val (summary, _) = export(job(source(flat = true), maxBytes = 800))
            assertTrue(summary.userMessage, summary.userMessage.contains("목표 용량"))
            assertTrue(summary.outputBytes > 800)
        } finally { cleanup() }
    }

    @Test fun metadataOff_bakesOrientationIntoPixels() = runBlocking {
        try {
            val (_, uri) = export(job(source(), metadata = false))
            resolver.openInputStream(uri)!!.use { assertNull(ExifInterface(it).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)) }
            val bitmap = BitmapFactory.decodeByteArray(read(uri), 0, read(uri).size)
            assertEquals(200, bitmap.width); assertEquals(120, bitmap.height); bitmap.recycle()
        } finally { cleanup() }
    }

    @Test fun realMediaStoreSource_preservesDbDateAndRequiresUnredactedAccess() = runBlocking {
        var mediaSource: Uri? = null
        try {
            val original = source()
            mediaSource = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "metadata-source-${UUID.randomUUID()}.jpg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SBPhotoMetadataTest/")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.DATE_TAKEN, Instant.parse("2026-10-31T18:04:05.123Z").toEpochMilli())
            })!!
            resolver.openOutputStream(mediaSource)!!.use { it.write(original.readBytes()) }
            resolver.update(mediaSource, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val deniedContext = object : ContextWrapper(context) {
                override fun checkPermission(permission: String, pid: Int, uid: Int): Int = if (permission == android.Manifest.permission.ACCESS_MEDIA_LOCATION) PackageManager.PERMISSION_DENIED else super.checkPermission(permission, pid, uid)
                override fun checkSelfPermission(permission: String): Int = if (permission == android.Manifest.permission.ACCESS_MEDIA_LOCATION) PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
            }
            assertThrows(UserVisibleException::class.java) { CompressionStorage(deniedContext).stage(mediaSource, "test.jpg", true) }
            val expectedDate = longValue(mediaSource, MediaStore.MediaColumns.DATE_TAKEN)
            val before = read(MediaStore.setRequireOriginal(mediaSource))
            val (_, output) = export(job(original).copy(uris = listOf(mediaSource.toString())))
            assertEquals(expectedDate, longValue(output, MediaStore.MediaColumns.DATE_TAKEN))
            assertArrayEquals(before, read(MediaStore.setRequireOriginal(mediaSource)))
            resolver.openInputStream(output)!!.use { assertNotNull(ExifInterface(it).latLong) }
        } finally { mediaSource?.let { resolver.delete(it, null, null) }; cleanup() }
    }

    @Test fun saveLifecycleEvidence_forIndependentAppRestartCheck() = runBlocking {
        val original = source()
        val (summary, uri) = export(job(original))
        assertTrue(summary.userMessage, summary.details.isEmpty())
        val data = JSONObject().apply {
            put("uri", uri.toString()); put("dateTaken", longValue(uri, MediaStore.MediaColumns.DATE_TAKEN))
            put("dateModified", longValue(uri, MediaStore.MediaColumns.DATE_MODIFIED))
            put("modified", modified(uri).toString()); put("name", stringValue(uri, MediaStore.MediaColumns.DISPLAY_NAME))
            put("hash", MessageDigest.getInstance("SHA-256").digest(read(uri)).joinToString("") { "%02x".format(it) })
            put("source", original.absolutePath)
        }
        File(context.cacheDir, "metadata-lifecycle.json").writeText(data.toString())
    }
}

@RunWith(AndroidJUnit4::class)
class CompressionMetadataRestartTest {
    @Test fun fileAndDatabaseStillMatch_afterSeparateAppProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val evidence = File(context.cacheDir, "metadata-lifecycle.json")
        val expected = JSONObject(evidence.readText())
        val uri = Uri.parse(expected.getString("uri"))
        try {
            resolver.query(uri, arrayOf("datetaken", "date_modified", "_display_name"), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(expected.getLong("dateTaken"), it.getLong(0))
                assertEquals(expected.getLong("dateModified"), it.getLong(1)); assertEquals(expected.getString("name"), it.getString(2))
            }
            resolver.openFileDescriptor(uri, "r")!!.use {
                val stat = Os.fstat(it.fileDescriptor)
                assertEquals(expected.getString("modified"), FileTime.from(Instant.ofEpochSecond(stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec)).toString())
            }
            resolver.openInputStream(uri)!!.use {
                val bytes = it.readBytes()
                assertEquals(expected.getString("hash"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) })
                File(context.cacheDir, "metadata-export-compressed.jpg").writeBytes(bytes)
            }
            File(context.cacheDir, "metadata-export-original.jpg").writeBytes(File(expected.getString("source")).readBytes())
        } finally {
            resolver.delete(uri, null, null)
            val original = File(expected.getString("source")); original.delete(); original.parentFile?.delete(); evidence.delete()
        }
    }
}
