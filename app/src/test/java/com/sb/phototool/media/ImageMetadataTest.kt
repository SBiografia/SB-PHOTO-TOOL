package com.sb.phototool.media

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.CRC32

class ImageMetadataTest {
    @get:Rule val folder = TemporaryFolder()
    private fun fixture(name: String) = javaClass.getResourceAsStream("/metadata/$name")!!.use { it.readBytes() }
    private fun file(bytes: ByteArray): File = folder.newFile().apply { writeBytes(bytes) }
    private fun bytes(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use(write) }.toByteArray()
    private fun jpeg(vararg blocks: Pair<Int, ByteArray>, afterScan: Pair<Int, ByteArray>? = null, tail: ByteArray = byteArrayOf()): ByteArray = bytes { out ->
        fun segment(kind: Int, data: ByteArray) { out.writeByte(255); out.writeByte(kind); out.writeShort(data.size + 2); out.write(data) }
        out.writeShort(0xffd8)
        blocks.forEach { segment(it.first, it.second) }
        segment(0xc0, byteArrayOf(8, 1, 0x90.toByte(), 0, 0xf0.toByte(), 3))
        segment(0xda, byteArrayOf(0))
        out.write(byteArrayOf(1, 2, 0xff.toByte(), 0, 3, 0xff.toByte(), 0xd0.toByte(), 4))
        afterScan?.let { segment(it.first, it.second) }
        out.writeShort(0xffd9); out.write(tail)
    }
    private fun exif(name: String = "original-le.tiff") = "Exif\u0000\u0000".toByteArray() + fixture(name)
    private fun png(vararg chunks: Pair<String, ByteArray>): ByteArray = bytes { out ->
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(kind: String, data: ByteArray) {
            out.writeInt(data.size); out.writeBytes(kind); out.write(data)
            out.writeInt(CRC32().apply { update(kind.toByteArray()); update(data) }.value.toInt())
        }
        chunk("IHDR", bytes { it.writeInt(240); it.writeInt(400); it.write(byteArrayOf(8, 2, 0, 0, 0)) })
        chunks.forEach { chunk(it.first, it.second) }
        chunk("IDAT", byteArrayOf(1, 2, 3)); chunk("IEND", byteArrayOf())
    }
    private fun webp(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = bytes { out ->
            out.writeBytes("WEBP")
            chunks.forEach { (kind, data) ->
                out.writeBytes(kind); out.writeInt(Integer.reverseBytes(data.size)); out.write(data)
                if (data.size % 2 != 0) out.writeByte(0)
            }
        }
        return bytes { it.writeBytes("RIFF"); it.writeInt(Integer.reverseBytes(body.size)); it.write(body) }
    }

    @Test fun tiffLittleEndian_preservesEveryByteExceptFourDimensionValues() {
        assertArrayEquals(fixture("resized-le.tiff"), Tiff(fixture("original-le.tiff")).dimensions(120, 200))
    }

    @Test fun tiffBigEndian_preservesOpaqueMakerNoteUnknownTagAndAllGpsValues() {
        assertArrayEquals(fixture("resized-be.tiff"), Tiff(fixture("original-be.tiff")).dimensions(120, 200))
    }

    @Test fun dates_haveExplicitZoneAndUnroundedOriginalFraction() {
        val meta = ImageMetadata.read(file(jpeg(225 to exif())))
        assertEquals(Instant.parse("2026-10-31T18:04:05.123Z").toEpochMilli(), meta.captureTimeMillis())
        assertEquals("2026:11:01 03:04:05", meta.originalDate)
        assertArrayEquals(exif(), meta.blocks.single().data)
    }

    @Test fun unzonedDate_doesNotInventAnOffsetFromSystemTimeZone() {
        val meta = ImageMetadata.read(file(jpeg(225 to exif("unzoneddatetime.tiff"))))
        assertNull(meta.captureTimeMillis())
        assertEquals("2026:11:01 03:04:05", meta.originalDate)
    }

    @Test fun jpeg_preservesExifXmpIptcIccCommentAndUnknownAppSegments() {
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x tiff:ImageWidth='240' exif:PixelYDimension='400'>설명</x>".toByteArray()
        val source = ImageMetadata.read(file(jpeg(225 to exif(), 225 to xmp, 237 to byteArrayOf(0, 1, -1), 226 to "ICC_PROFILE\u0000profile".toByteArray(), 239 to byteArrayOf(7, 0, -128), 254 to "comment".toByteArray())))
        val output = file(jpeg(224 to "encoder header".toByteArray()))
        source.restoreTo(output, 120, 200)
        val actual = ImageMetadata.read(output).blocks
        assertEquals(6, actual.size)
        assertArrayEquals(exif("resized-le.tiff"), actual[0].data)
        assertEquals(xmp.toString(Charsets.UTF_8).replace("'240'", "'120'").replace("'400'", "'200'"), actual[1].data.toString(Charsets.UTF_8))
        for (i in 2..5) assertArrayEquals(source.blocks[i].data, actual[i].data)
    }

    @Test fun jpeg_findsMetadataBetweenProgressiveScans() {
        val source = ImageMetadata.read(file(jpeg(225 to exif(), afterScan = 237 to byteArrayOf(9, 8, 7))))
        val output = file(jpeg())
        source.restoreTo(output, 240, 400)
        source.verify(ImageMetadata.read(output))
    }

    @Test fun jpeg_refusesToSilentlyDropMotionPhotoTrailer() {
        val source = ImageMetadata.read(file(jpeg(225 to exif(), tail = "MotionPhotoData".toByteArray())))
        val output = file(jpeg())
        val before = output.readBytes()
        assertThrows(IllegalArgumentException::class.java) { source.restoreTo(output, 120, 200) }
        assertArrayEquals(before, output.readBytes())
    }

    @Test fun jpeg_refusesMultiPictureOffsetsInsteadOfPublishingStalePointers() {
        val source = ImageMetadata.read(file(jpeg(225 to exif(), 226 to "MPF\u0000unsupported".toByteArray())))
        assertThrows(IllegalArgumentException::class.java) { source.restoreTo(file(jpeg()), 120, 200) }
    }

    @Test fun png_preservesExifTextColorChunksAndUnknownSafeChunksWithValidCrc() {
        val source = ImageMetadata.read(file(png("eXIf" to fixture("original-be.tiff"), "tEXt" to "Author\u0000Test".toByteArray(), "gAMA" to byteArrayOf(0, 0, -79, -113), "vpAg" to byteArrayOf(0, 9))))
        val output = file(png())
        source.restoreTo(output, 120, 200)
        val actual = ImageMetadata.read(output).blocks
        assertArrayEquals(fixture("resized-be.tiff"), actual[0].data)
        for (i in 1..3) assertArrayEquals(source.blocks[i].data, actual[i].data)
    }

    @Test fun png_detectsCorruptMetadataBeforeAnyRewrite() {
        val data = png("eXIf" to fixture("original-le.tiff"))
        data[60] = (data[60].toInt() xor 1).toByte()
        assertThrows(IllegalStateException::class.java) { ImageMetadata.read(file(data)) }
    }

    @Test fun png_unknownUnsafeChunkFailsExplicitly() {
        val source = ImageMetadata.read(file(png("vpAG" to byteArrayOf(1, 2))))
        assertThrows(IllegalArgumentException::class.java) { source.restoreTo(file(png()), 120, 200) }
    }

    @Test fun webp_restoresExifXmpIccUnknownChunksAndUpdatesRiffAndFlags() {
        val source = ImageMetadata.read(file(webp("VP8 " to ByteArray(10), "EXIF" to exif(), "XMP " to "<x>권리</x>".toByteArray(), "ICCP" to byteArrayOf(9, 8, 7), "TEST" to byteArrayOf(4))))
        val output = file(webp("VP8 " to ByteArray(10)))
        source.restoreTo(output, 120, 200)
        val actual = ImageMetadata.read(output)
        val byType = actual.blocks.associateBy { it.kind }
        assertArrayEquals(exif("resized-le.tiff"), byType.getValue("EXIF").data)
        assertArrayEquals(byteArrayOf(9, 8, 7), byType.getValue("ICCP").data)
        assertArrayEquals(byteArrayOf(4), byType.getValue("TEST").data)
        assertEquals(44, output.readBytes()[20].toInt())
    }

    @Test fun malformedTiffPointerFailsInsteadOfReturningMissingMetadata() {
        val data = fixture("original-le.tiff")
        for (i in 4..7) data[i] = 0x7f
        assertThrows(IllegalStateException::class.java) { Tiff(data).strings() }
    }

    @Test fun png_compressedXmpKeepsOtherFieldsAndUpdatesAliasedDimensions() {
        val original = "<rdf xmlns:t='http://ns.adobe.com/tiff/1.0/' t:ImageWidth='240'><t:ImageLength>400</t:ImageLength><rights>원본 권리</rights></rdf>".toByteArray()
        val compressed = ByteArrayOutputStream().also { stream -> java.util.zip.DeflaterOutputStream(stream).use { it.write(original) } }.toByteArray()
        val header = "XML:com.adobe.xmp\u0000".toByteArray() + byteArrayOf(1, 0, 0, 0)
        val source = ImageMetadata.read(file(png("iTXt" to (header + compressed))))
        val output = file(png())
        source.restoreTo(output, 120, 200)
        val actual = ImageMetadata.read(output).blocks.single().data
        val xml = java.util.zip.InflaterInputStream(actual.copyOfRange(header.size, actual.size).inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        assertEquals(original.toString(Charsets.UTF_8).replace("'240'", "'120'").replace(">400<", ">200<"), xml)
    }

    @Test fun verifyReportsMetadataLoss() {
        val source = ImageMetadata.read(file(jpeg(225 to exif(), 237 to byteArrayOf(1, 2))))
        assertThrows(IllegalStateException::class.java) { source.verify(ImageMetadata.read(file(jpeg(225 to exif())))) }
    }
}
