package com.sb.phototool.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HdrJpegTest {
    @get:Rule val folder = TemporaryFolder()
    private val prefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
    private fun xml(text: String) = prefix + text.toByteArray()
    private fun file(bytes: ByteArray) = folder.newFile().apply { writeBytes(bytes) }
    private fun jpeg(width: Int, height: Int, blocks: List<Pair<Int, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            fun segment(kind: Int, data: ByteArray) { out.writeShort(0xff00 or kind); out.writeShort(data.size + 2); out.write(data) }
            out.writeShort(0xffd8)
            blocks.forEach { segment(it.first, it.second) }
            segment(0xc0, byteArrayOf(8, (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 3))
            segment(0xda, byteArrayOf(0))
            out.write(byteArrayOf(1, 2, -1, 0, 3)); out.writeShort(0xffd9)
        }
        return output.toByteArray()
    }
    private data class Example(val source: ByteArray, val auxiliary: ByteArray, val mpfOffset: Int)
    private fun example(bigEndian: Boolean, corruptOffset: Boolean = false, semantic: String = "GainMap"): Example {
        val auxiliary = jpeg(60, 100, listOf(225 to xml("<x xmlns:h='http://ns.adobe.com/hdr-gain-map/1.0/' h:Version='1.0' h:GainMapMax='2.3' h:BaseRenditionIsHDR='False'/>")))
        val primaryXmp = xml("""<x xmlns:h="http://ns.adobe.com/hdr-gain-map/1.0/" xmlns:i="http://ns.google.com/photos/1.0/container/item/" h:Version="1.0"><a i:Semantic="Primary" i:Mime="image/jpeg"/><a i:Semantic="$semantic" i:Mime="image/jpeg" i:Length="${auxiliary.size}"/></x>""")
        val tiff = javaClass.getResourceAsStream("/metadata/original-${if (bigEndian) "be" else "le"}.tiff")!!.use { it.readBytes() }
        val exif = "Exif\u0000\u0000".toByteArray() + tiff
        val mpf = ByteArray(86)
        "MPF\u0000".toByteArray().copyInto(mpf)
        val buffer = ByteBuffer.wrap(mpf).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN).apply { position(4) }
        buffer.put(if (bigEndian) "MM".toByteArray() else "II".toByteArray()); buffer.putShort(42); buffer.putInt(8); buffer.putShort(3)
        fun entry(tag: Int, type: Int, count: Int, value: Int) { buffer.putShort(tag.toShort()); buffer.putShort(type.toShort()); buffer.putInt(count); buffer.putInt(value) }
        entry(0xb000, 7, 4, if (bigEndian) 0x30313030 else 0x30303130)
        entry(0xb001, 4, 1, 2); entry(0xb002, 7, 32, 50); buffer.putInt(0)
        buffer.putInt(0x30000); buffer.putInt(0); buffer.putInt(0); buffer.putInt(0)
        buffer.putInt(0); buffer.putInt(auxiliary.size); buffer.putInt(0); buffer.putInt(0)
        val blocks = listOf(225 to exif, 225 to primaryXmp, 226 to mpf)
        val primaryLength = jpeg(240, 400, blocks).size
        val mpfOffset = 2 + exif.size + 4 + primaryXmp.size + 4
        buffer.putInt(58, primaryLength)
        buffer.putInt(78, primaryLength - mpfOffset - 8 + if (corruptOffset) 1 else 0)
        return Example(jpeg(240, 400, blocks) + auxiliary, auxiliary, mpfOffset)
    }

    private fun checkRoundTrip(bigEndian: Boolean) {
        val example = example(bigEndian)
        val source = ImageMetadata.read(file(example.source))
        assertTrue(source.hasGainMap)
        val output = file(jpeg(120, 200, emptyList()))
        source.restoreTo(output, 120, 200)
        val restored = ImageMetadata.read(output)
        assertTrue(restored.hasGainMap)
        assertArrayEquals(example.auxiliary, restored.gainMapBytes)
        val exif = restored.blocks.first { it.kind == "225" }.data
        val expected = javaClass.getResourceAsStream("/metadata/resized-${if (bigEndian) "be" else "le"}.tiff")!!.use { it.readBytes() }
        assertArrayEquals("Exif\u0000\u0000".toByteArray() + expected, exif)
        val mpf = restored.blocks.first { HdrJpeg.isMpf(it) }.data
        val index = ByteBuffer.wrap(mpf).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val primaryLength = output.length() - example.auxiliary.size
        assertEquals(primaryLength, Integer.toUnsignedLong(index.getInt(58)))
        val markerOffset = 2L + restored.blocks.takeWhile { !HdrJpeg.isMpf(it) }.sumOf { it.data.size + 4L }
        assertEquals(primaryLength, Integer.toUnsignedLong(index.getInt(78)) + markerOffset + 8)
        assertFalse(example.source.contentEquals(output.readBytes()))
    }

    @Test fun hdrBigEndian_keepsGainMapAndAllExifExceptDimensionsAndRelocatesMpf() = checkRoundTrip(true)
    @Test fun hdrLittleEndian_keepsGainMapAndAllExifExceptDimensionsAndRelocatesMpf() = checkRoundTrip(false)

    @Test fun hdrWithSamsungSuffixKeepsBothSeparateAndByteIdentical() {
        val example = example(true)
        val suffix = sefFixture("Image_UTC_Data" to "1234567890123".toByteArray(), "Photo_HDR_Info" to byteArrayOf(1))
        val source = ImageMetadata.read(file(example.source + suffix))
        source.checkRecompressionSupport()
        val output = file(jpeg(120, 200, emptyList()))
        source.restoreTo(output, 120, 200)
        val result = ImageMetadata.read(output)
        result.checkRecompressionSupport()
        assertArrayEquals(example.auxiliary, result.gainMapBytes)
        assertArrayEquals(suffix, result.samsungBytes)
    }

    @Test fun plainJpegWithPortraitSuffixKeepsEditingBytesAndReportsLimitation() {
        val suffix = sefFixture("Portrait_Bokeh_Edit_Info" to byteArrayOf(1), "DualShot_5" to byteArrayOf(4, 5, 6))
        val source = ImageMetadata.read(file(jpeg(240, 400, emptyList()) + suffix))
        source.checkRecompressionSupport()
        val output = file(jpeg(120, 200, emptyList()))
        source.restoreTo(output, 120, 200)
        val result = ImageMetadata.read(output)
        assertFalse(result.hasGainMap)
        assertArrayEquals(suffix, result.samsungBytes)
        assertEquals(1, result.preservationWarnings.size)
    }

    @Test fun wrongGainMapPointer_isReportedBeforeRecompression() {
        val meta = ImageMetadata.read(file(example(true, corruptOffset = true).source))
        assertFalse(meta.hasGainMap)
        assertThrows(IllegalArgumentException::class.java) { meta.checkRecompressionSupport() }
    }

    @Test fun motionPhotoDirectory_isNotAcceptedAsStillHdr() {
        val meta = ImageMetadata.read(file(example(true, semantic = "MotionPhoto").source))
        assertFalse(meta.hasGainMap)
        assertThrows(IllegalArgumentException::class.java) { meta.checkRecompressionSupport() }
    }

    @Test fun arbitraryTrailer_isNotReportedAsMotionPhoto() {
        val meta = ImageMetadata.read(file(jpeg(240, 400, emptyList()) + byteArrayOf(0)))
        val error = assertThrows(IllegalArgumentException::class.java) { meta.checkRecompressionSupport() }
        assertFalse(error.message.orEmpty().contains("모션사진"))
    }
}
