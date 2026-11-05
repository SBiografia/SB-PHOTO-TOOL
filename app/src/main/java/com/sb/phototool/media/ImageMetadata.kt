package com.sb.phototool.media

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Carries opaque metadata, including unknown TIFF tags, without a string round trip. */
internal class ImageMetadata private constructor(
    val format: String,
    internal val blocks: List<Block>,
    private val rewriteProblem: String? = null,
    private val hdr: HdrJpeg? = null,
    private val jpegPrimaryLength: Long = 0,
    private val sef: SamsungSef? = null,
) {
    internal data class Block(val kind: String, val data: ByteArray)

    val originalDate: String? get() = exifValues()[0x9003]
    val hasGainMap: Boolean get() = hdr != null
    internal val gainMapBytes: ByteArray? get() = hdr?.auxiliary
    internal val samsungBytes: ByteArray? get() = sef?.bytes
    val preservationWarnings: List<String> get() = if (sef?.hasEditingData == true) listOf("삼성 편집 데이터는 보존했지만 갤러리 재편집 기능은 실기기 확인이 필요해요.") else emptyList()

    fun captureTimeMillis(): Long? {
        val values = exifValues()
        val date = values[0x9003] ?: return null
        val offset = values[0x9011] ?: return null
        return try {
            val local = LocalDateTime.parse(date, DATE_FORMAT)
            val fraction = values[0x9291]?.takeWhile(Char::isDigit).orEmpty().take(3).padEnd(3, '0').toInt()
            local.toInstant(ZoneOffset.of(offset)).toEpochMilli() + fraction
        } catch (_: RuntimeException) { null }
    }

    private fun exifValues(): Map<Int, String> = blocks.firstOrNull { isExif(it) }?.let {
        Tiff(it.data).strings()
    }.orEmpty()

    fun checkRecompressionSupport() {
        require(rewriteProblem == null) { rewriteProblem.orEmpty() }
    }

    fun restoreTo(destination: File, width: Int, height: Int): ImageMetadata {
        checkRecompressionSupport()
        val iccModel = blocks.firstNotNullOfOrNull {
            when {
                format == "webp" && it.kind == "ICCP" && it.data.size >= 20 -> it.data.copyOfRange(16, 20).toString(Charsets.US_ASCII)
                format == "jpeg" && it.kind == "226" && it.data.size >= 34 && it.data.startsWith("ICC_PROFILE\u0000".toByteArray()) && it.data[12] == 1.toByte() -> it.data.copyOfRange(30, 34).toString(Charsets.US_ASCII)
                else -> null
            }
        }
        require(iccModel == null || iccModel == "RGB ") { "RGB가 아닌 원본 ICC 프로파일은 색상 변환 없이 재압축할 수 없어요." }
        val pngChannels = if (format == "png") destination.inputStream().use {
            skipExactly(it, 25)
            when (it.read()) { 2 -> 3; 6 -> 4; else -> error("PNG 출력의 색상 채널을 확인하지 못했어요.") }
        } else 0
        var restoredBlocks = blocks.map { block ->
            when {
                isExif(block) -> Block(block.kind, Tiff(block.data).dimensions(width, height))
                isXmp(block) -> Block(block.kind, updateXmp(block.data, width, height))
                format == "png" && block.kind == "iTXt" -> Block(block.kind, updatePngXmp(block.data, width, height))
                format == "png" && block.kind == "sBIT" -> Block(block.kind, ByteArray(pngChannels) { 8 })
                else -> block
            }
        }
        if (hdr != null) {
            val encoded = read(destination)
            check(!encoded.hasGainMap && encoded.jpegPrimaryLength == destination.length()) { "HDR 본체 압축 중 예상하지 못한 보조 데이터가 생겼어요." }
            val primaryLength = encoded.jpegPrimaryLength - encoded.blocks.sumOf { it.data.size + 4L } + restoredBlocks.sumOf { it.data.size + 4L }
            restoredBlocks = hdr.relocate(restoredBlocks, primaryLength)
        }
        val expected = ImageMetadata(format, restoredBlocks, hdr = hdr, sef = sef)
        val scratch = File.createTempFile("metadata_", ".tmp", destination.parentFile)
        try {
            when (format) {
                "jpeg" -> expected.writeJpeg(destination, scratch)
                "png" -> expected.writePng(destination, scratch)
                "webp" -> expected.writeWebp(destination, scratch, width, height)
                else -> error("원본 형식을 유지할 수 없는 사진이에요.")
            }
            expected.verify(read(scratch))
            Files.move(scratch.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return expected
        } finally { scratch.delete() }
    }

    fun verify(actual: ImageMetadata) {
        val expectedBlocks = if (format == "webp") blocks.sortedBy { it.kind } else blocks
        val actualBlocks = if (format == "webp") actual.blocks.sortedBy { it.kind } else actual.blocks
        check(format == actual.format && expectedBlocks.size == actualBlocks.size && expectedBlocks.indices.all {
            expectedBlocks[it].kind == actualBlocks[it].kind && expectedBlocks[it].data.contentEquals(actualBlocks[it].data)
        } && hasGainMap == actual.hasGainMap && (hdr == null || actual.hdr?.auxiliary?.contentEquals(hdr.auxiliary) == true)
            && ((sef == null && actual.sef == null) || (sef != null && actual.sef?.bytes?.contentEquals(sef.bytes) == true))
        ) { "메타데이터 보존 검증에 실패했어요. 원본은 변경하지 않았어요." }
    }

    private fun isExif(block: Block) = when (format) {
        "jpeg" -> block.kind == "225" && block.data.startsWith(EXIF)
        "png" -> block.kind == "eXIf"
        else -> block.kind == "EXIF"
    }

    private fun isXmp(block: Block) = block.kind == "XMP " ||
        (format == "jpeg" && block.kind == "225" && block.data.startsWith(XMP))

    private fun writeJpeg(encoded: File, output: File) {
        DataInputStream(encoded.inputStream().buffered()).use { input ->
            DataOutputStream(output.outputStream().buffered()).use { out ->
                check(input.readUnsignedShort() == 0xffd8)
                out.writeShort(0xffd8)
                blocks.forEach { out.writeByte(0xff); out.writeByte(it.kind.toInt()); out.writeShort(it.data.size + 2); out.write(it.data) }
                while (true) {
                    val marker = jpegMarker(input)
                    if (marker == 0xda) {
                        out.writeShort(0xffda)
                        input.copyTo(out)
                        break
                    }
                    check(marker != 0xd9) { "JPEG 이미지 데이터가 없어요." }
                    val length = input.readUnsignedShort()
                    check(length >= 2)
                    val data = ByteArray(length - 2).also(input::readFully)
                    if (marker !in 0xe0..0xef && marker != 0xfe) {
                        out.writeByte(0xff); out.writeByte(marker); out.writeShort(length); out.write(data)
                    }
                }
                hdr?.let { out.write(it.auxiliary) }
                sef?.let { out.write(it.bytes) }
            }
        }
    }

    private fun writePng(encoded: File, output: File) {
        DataInputStream(encoded.inputStream().buffered()).use { input ->
            DataOutputStream(output.outputStream().buffered()).use { out ->
                out.write(ByteArray(8).also(input::readFully))
                var inserted = false
                while (true) {
                    val size = input.readInt().also { check(it >= 0) }
                    val type = ByteArray(4).also(input::readFully)
                    val kind = type.toString(Charsets.US_ASCII)
                    if (!inserted && kind == "IDAT") {
                        blocks.forEach { writePngChunk(out, it) }; inserted = true
                    }
                    if (kind in setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS")) {
                        out.writeInt(size); out.write(type); copyExactly(input, out, size.toLong()); out.writeInt(input.readInt())
                    } else { skipExactly(input, size.toLong() + 4) }
                    if (kind == "IEND") break
                }
            }
        }
    }

    private fun writeWebp(encoded: File, output: File, width: Int, height: Int) {
        val chunks = readWebpChunks(encoded, includePixels = true)
        val pixels = chunks.filter { it.kind in setOf("VP8 ", "VP8L", "ALPH") }
        val alpha = chunks.any { it.kind == "ALPH" || (it.kind == "VP8X" && it.data[0].toInt() and 16 != 0) ||
            (it.kind == "VP8L" && it.data.size >= 5 && it.data[4].toInt() and 16 != 0) }
        var flags = if (alpha) 16 else 0
        if (blocks.any { it.kind == "ICCP" }) flags = flags or 32
        if (blocks.any { it.kind == "EXIF" }) flags = flags or 8
        if (blocks.any { it.kind == "XMP " }) flags = flags or 4
        val header = ByteArray(10).also {
            it[0] = flags.toByte()
            for (i in 0..2) { it[4 + i] = ((width - 1) ushr (8 * i)).toByte(); it[7 + i] = ((height - 1) ushr (8 * i)).toByte() }
        }
        val ordered = listOf(Block("VP8X", header)) + blocks.filter { it.kind == "ICCP" } + pixels + blocks.filter { it.kind != "ICCP" }
        val size = 4L + ordered.sumOf { 8L + it.data.size + it.data.size % 2 }
        check(size <= 0xffffffffL)
        DataOutputStream(output.outputStream().buffered()).use { out ->
            out.writeBytes("RIFF"); out.writeInt(Integer.reverseBytes(size.toInt())); out.writeBytes("WEBP")
            ordered.forEach {
                out.writeBytes(it.kind); out.writeInt(Integer.reverseBytes(it.data.size)); out.write(it.data)
                if (it.data.size % 2 != 0) out.writeByte(0)
            }
        }
    }

    companion object {
        private const val MAX_METADATA = 32 * 1024 * 1024
        private val EXIF = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)
        private val XMP = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

        fun read(file: File): ImageMetadata {
            val header = file.inputStream().use { ByteArray(12).also { b -> check(it.read(b) == b.size) { "사진 파일이 너무 짧아요." } } }
            return when {
                header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> readJpeg(file)
                header.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> readPng(file)
                header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> {
                    val chunks = readWebpChunks(file)
                    val problem = if (chunks.any { it.kind in setOf("ANIM", "ANMF") }) "움직이는 WebP는 원본 형식과 부가 정보를 유지하며 압축할 수 없어요." else null
                    ImageMetadata("webp", chunks.filter { it.kind !in setOf("VP8X", "VP8 ", "VP8L", "ALPH", "ANIM", "ANMF") }, problem).also { it.validateExif() }
                }
                else -> error("원본 이름·확장자 유지 압축은 JPEG·PNG·WebP만 지원해요. 원본은 변경하지 않았어요.")
            }
        }

        private fun ImageMetadata.validateExif() { blocks.filter(::isExif).forEach { Tiff(it.data).strings() } }

        private fun readJpeg(file: File): ImageMetadata {
            val blocks = mutableListOf<Block>()
            var problem: String? = null
            var total = 0L
            var primaryLength = 0L
            var mpfMarkerOffset = -1L
            var hdr: HdrJpeg? = null
            var sef: SamsungSef? = null
            val counted = CountingInputStream(BufferedInputStream(file.inputStream()))
            DataInputStream(counted).use { input ->
                check(input.readUnsignedShort() == 0xffd8)
                var marker = jpegMarker(input)
                while (marker != 0xd9) {
                    check(marker !in setOf(0xd8, 0x00) && marker !in 0xd0..0xd7) { "JPEG 구조가 올바르지 않아요." }
                    val size = input.readUnsignedShort() - 2
                    check(size >= 0)
                    if (marker in 0xe0..0xef || marker == 0xfe) {
                        total += size; check(total <= MAX_METADATA) { "메타데이터가 너무 커서 안전하게 보존할 수 없어요." }
                        val data = ByteArray(size).also(input::readFully)
                        blocks += Block(marker.toString(), data)
                        if (marker == 0xe2 && data.startsWith("MPF\u0000".toByteArray())) mpfMarkerOffset = counted.count - size - 4
                        if (marker == 0xe1 && data.startsWith("http://ns.adobe.com/xmp/extension/\u0000".toByteArray())) problem = "확장 XMP가 있는 JPEG는 원본 부가 정보를 위해 재압축하지 않아요. 크기 유지 복사는 가능해요."
                    } else {
                        if (marker in setOf(0xc0, 0xc1, 0xc2)) {
                            val data = ByteArray(size).also(input::readFully)
                            if (data.size >= 6 && data[5].toInt() !in setOf(1, 3)) problem = "이 사진의 색상 형식은 원본 메타데이터를 유지하며 압축할 수 없어요."
                        } else skipExactly(input, size.toLong())
                    }
                    marker = if (marker == 0xda) entropyMarker(input) else jpegMarker(input)
                }
                primaryLength = counted.count
                val remaining = file.length() - primaryLength
                if (remaining > 0 && problem == null) {
                    if (remaining <= MAX_METADATA) {
                        val tail = ByteArray(remaining.toInt()).also(input::readFully)
                        try {
                            sef = SamsungSef.read(tail)
                            val auxiliary = tail.copyOfRange(0, tail.size - (sef?.bytes?.size ?: 0))
                            if (mpfMarkerOffset >= 0) hdr = HdrJpeg.read(blocks, primaryLength, auxiliary, mpfMarkerOffset, sef)
                            else require(auxiliary.isEmpty() && sef != null) { "JPEG 뒤의 부가 데이터 형식을 지원하지 않아요. 원본 그대로 복사는 가능해요." }
                        } catch (failure: Exception) { problem = "HDR·삼성 부가 정보를 확인하지 못했어요: ${failure.message?.take(180) ?: "구조를 해석하지 못했어요."}" }
                    } else problem = "JPEG 뒤의 부가 데이터 형식을 지원하지 않아요. 원본 그대로 복사는 가능해요."
                } else if (mpfMarkerOffset >= 0 && problem == null) problem = "MPF가 가리키는 보조 이미지가 없어요. 원본 그대로 복사는 가능해요."
            }
            return ImageMetadata("jpeg", blocks, problem, hdr, primaryLength, sef).also { it.validateExif() }
        }

        private fun readPng(file: File): ImageMetadata {
            val blocks = mutableListOf<Block>()
            var total = 0L
            var problem: String? = null
            DataInputStream(file.inputStream().buffered()).use { input ->
                skipExactly(input, 8)
                while (true) {
                    val size = input.readInt().also { check(it >= 0 && it.toLong() <= file.length()) }
                    val type = ByteArray(4).also(input::readFully)
                    val kind = type.toString(Charsets.US_ASCII)
                    if (kind !in setOf("IDAT", "PLTE", "IEND", "tRNS")) {
                        total += size; check(total <= MAX_METADATA) { "메타데이터가 너무 커요." }
                        val data = ByteArray(size).also(input::readFully)
                        val crc = input.readInt()
                        check(CRC32().apply { update(type); update(data) }.value.toInt() == crc) { "PNG 메타데이터 검사값이 올바르지 않아요." }
                        when {
                            kind == "IHDR" -> if (data.size != 13 || data[8].toInt() != 8 || data[9].toInt() !in setOf(2, 6)) problem = "이 PNG의 색상·비트 깊이를 유지하는 재압축은 지원하지 않아요."
                            kind in setOf("acTL", "fcTL", "fdAT") -> problem = "움직이는 PNG는 재압축을 지원하지 않아요."
                            kind in setOf("hIST", "cICP", "mDCV", "cLLI") -> problem = "이 PNG의 색상 부가 정보를 안전하게 옮길 수 없어요."
                            kind in setOf("eXIf", "iCCP", "sRGB", "gAMA", "cHRM", "pHYs", "tIME", "tEXt", "zTXt", "iTXt", "sBIT", "bKGD") || kind[3].isLowerCase() -> blocks += Block(kind, data)
                            else -> problem = "알 수 없는 PNG 부가 정보를 안전하게 옮길 수 없어요."
                        }
                    } else { skipExactly(input, size.toLong() + 4) }
                    if (kind == "IEND") { if (input.read() != -1) problem = "PNG 뒤의 부가 데이터는 재압축을 지원하지 않아요."; break }
                }
            }
            return ImageMetadata("png", blocks, problem).also { it.validateExif() }
        }

        private fun readWebpChunks(file: File, includePixels: Boolean = false): List<Block> = DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == 0x52494646)
            val length = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt())) + 8
            check(length == file.length()) { "WebP 크기나 파일 뒤의 부가 데이터를 확인할 수 없어요." }
            check(input.readInt() == 0x57454250)
            val blocks = mutableListOf<Block>()
            var consumed = 12L
            var metadataBytes = 0L
            while (consumed < length) {
                val kind = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                val size = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
                check(size <= Int.MAX_VALUE && consumed + 8 + size + size % 2 <= length)
                if (kind !in setOf("VP8 ", "VP8L", "ALPH", "ANMF")) {
                    metadataBytes += size; check(metadataBytes <= MAX_METADATA) { "메타데이터가 너무 커요." }
                }
                if (includePixels || kind !in setOf("VP8 ", "VP8L", "ALPH", "ANMF")) {
                    check(size <= 128L * 1024 * 1024) { "WebP 데이터가 너무 커요." }
                    blocks += Block(kind, ByteArray(size.toInt()).also(input::readFully))
                } else {
                    if (kind == "ANMF") blocks += Block(kind, byteArrayOf())
                    skipExactly(input, size)
                }
                if (size % 2 != 0L) input.readUnsignedByte()
                consumed += 8 + size + size % 2
            }
            blocks
        }

        private fun updateXmp(data: ByteArray, width: Int, height: Int): ByteArray {
            var text = data.toString(Charsets.UTF_8)
            check(text.toByteArray(Charsets.UTF_8).contentEquals(data)) { "이 XMP의 문자 인코딩은 출력 치수를 안전하게 갱신할 수 없어요." }
            val namespaces = mutableMapOf("tiff" to "tiff", "exif" to "exif")
            Regex("xmlns:([A-Za-z_][A-Za-z0-9_.-]*)\\s*=\\s*[\"']http://ns.adobe.com/(tiff|exif)/1.0/[\"']").findAll(text).forEach {
                namespaces[it.groupValues[1]] = it.groupValues[2]
            }
            val values = namespaces.flatMap { (prefix, kind) ->
                if (kind == "tiff") listOf("$prefix:ImageWidth" to width, "$prefix:ImageLength" to height)
                else listOf("$prefix:PixelXDimension" to width, "$prefix:PixelYDimension" to height)
            }
            for ((tag, value) in values) {
                val escaped = Regex.escape(tag)
                text = text.replace(Regex("($escaped\\s*=\\s*[\"'])\\d+([\"'])")) { "${it.groupValues[1]}$value${it.groupValues[2]}" }
                text = text.replace(Regex("(<$escaped(?:\\s[^>]*)?>)\\d+(</$escaped>)")) { "${it.groupValues[1]}$value${it.groupValues[2]}" }
            }
            return text.toByteArray(Charsets.UTF_8)
        }

        private fun updatePngXmp(data: ByteArray, width: Int, height: Int): ByteArray {
            val keywordEnd = data.indexOf(0)
            if (keywordEnd < 0 || data.copyOfRange(0, keywordEnd).toString(Charsets.US_ASCII) != "XML:com.adobe.xmp") return data
            check(data.size > keywordEnd + 3) { "PNG XMP 헤더가 잘렸어요." }
            val compressed = data[keywordEnd + 1].toInt()
            check(compressed in 0..1 && data[keywordEnd + 2] == 0.toByte()) { "PNG XMP 압축 방식이 올바르지 않아요." }
            var start = keywordEnd + 3
            repeat(2) {
                while (start < data.size && data[start] != 0.toByte()) start++
                check(start < data.size) { "PNG XMP 헤더가 잘렸어요." }; start++
            }
            val payload = data.copyOfRange(start, data.size)
            val xml = if (compressed == 1) InflaterInputStream(payload.inputStream()).use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_METADATA) { "압축된 XMP가 너무 커요." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } else payload
            val updated = updateXmp(xml, width, height)
            if (updated.contentEquals(xml)) return data
            val encoded = if (compressed == 1) java.io.ByteArrayOutputStream().also { stream -> DeflaterOutputStream(stream).use { it.write(updated) } }.toByteArray() else updated
            return data.copyOfRange(0, start) + encoded
        }

        private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
        private fun jpegMarker(input: DataInputStream): Int {
            check(input.readUnsignedByte() == 0xff) { "JPEG 마커가 올바르지 않아요." }
            var value: Int
            do { value = input.readUnsignedByte() } while (value == 0xff)
            return value
        }
        private fun entropyMarker(input: DataInputStream): Int {
            while (true) {
                if (input.readUnsignedByte() == 0xff) {
                    var value: Int
                    do { value = input.readUnsignedByte() } while (value == 0xff)
                    if (value != 0 && value !in 0xd0..0xd7) return value
                }
            }
        }
        private fun skipExactly(input: InputStream, bytes: Long) {
            var remaining = bytes
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) remaining -= skipped else { check(input.read() != -1) { "사진 메타데이터가 잘렸어요." }; remaining-- }
            }
        }
        private fun copyExactly(input: InputStream, out: java.io.OutputStream, bytes: Long) {
            val buffer = ByteArray(64 * 1024)
            var remaining = bytes
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                check(read > 0); out.write(buffer, 0, read); remaining -= read
            }
        }
        private fun writePngChunk(out: DataOutputStream, block: Block) {
            val type = block.kind.toByteArray(Charsets.US_ASCII)
            out.writeInt(block.data.size); out.write(type); out.write(block.data)
            out.writeInt(CRC32().apply { update(type); update(block.data) }.value.toInt())
        }
    }
}

private class CountingInputStream(input: InputStream) : java.io.FilterInputStream(input) {
    var count = 0L
        private set
    override fun read(): Int = `in`.read().also { if (it >= 0) count++ }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also { if (it > 0) count += it }
    override fun skip(length: Long): Long = `in`.skip(length).also { count += it }
}

/** Patches fixed-size structural values in place, retaining byte order, offsets and opaque data. */
internal class Tiff(private val source: ByteArray) {
    private val base = if (source.size >= 6 && source.copyOfRange(0, 6).contentEquals("Exif\u0000\u0000".toByteArray())) 6 else 0
    private val buffer = ByteBuffer.wrap(source.copyOf()).apply {
        check(capacity() >= base + 8) { "EXIF 헤더가 잘렸어요." }
        order(when (getShort(base).toInt() and 0xffff) { 0x4949 -> ByteOrder.LITTLE_ENDIAN; 0x4d4d -> ByteOrder.BIG_ENDIAN; else -> error("EXIF 바이트 순서가 올바르지 않아요.") })
        check(getShort(base + 2).toInt() == 42) { "지원하지 않는 EXIF 구조예요." }
    }
    private data class Entry(val tag: Int, val type: Int, val count: Long, val value: Int, val primary: Boolean)

    private fun entries(): List<Entry> {
        val result = mutableListOf<Entry>()
        val visited = mutableSetOf<Long>()
        fun walk(offset: Long, primary: Boolean, next: Boolean) {
            if (offset == 0L) return
            check(visited.size < 64 && visited.add(offset)) { "EXIF 디렉터리 참조가 올바르지 않아요." }
            val start = address(offset, 2)
            val count = buffer.getShort(start).toInt() and 0xffff
            address(offset, 2L + count * 12L + 4)
            for (i in 0 until count) {
                val pos = start + 2 + i * 12
                val tag = buffer.getShort(pos).toInt() and 0xffff
                val type = buffer.getShort(pos + 2).toInt() and 0xffff
                val components = uint(pos + 4)
                val unit = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11, 13 -> 4; 5, 10, 12 -> 8; else -> 0 }
                val bytes = components * unit
                val value = if (bytes > 4) address(uint(pos + 8), bytes) else pos + 8
                result += Entry(tag, type, components, value, primary)
                if (tag in setOf(0x8769, 0x8825, 0xa005)) {
                    check(type == 4 && components == 1L)
                    walk(uint(value), false, false)
                }
            }
            if (next) walk(uint(start + 2 + count * 12), false, true)
        }
        walk(uint(base + 4), true, true)
        return result
    }

    fun strings(): Map<Int, String> = entries().filter { it.type == 2 && it.tag in setOf(0x9003, 0x9011, 0x9291) }.associate {
        it.tag to buffer.array().copyOfRange(it.value, it.value + it.count.toInt()).toString(Charsets.US_ASCII).trimEnd('\u0000', ' ')
    }

    fun dimensions(width: Int, height: Int): ByteArray {
        entries().forEach {
            val value = when {
                it.primary && it.tag == 256 || it.tag == 40962 -> width
                it.primary && it.tag == 257 || it.tag == 40963 -> height
                else -> null
            }
            if (value != null) {
                check(it.count == 1L && it.type in setOf(3, 4)) { "EXIF 이미지 크기를 안전하게 갱신할 수 없어요." }
                if (it.type == 3) { check(value <= 65535); buffer.putShort(it.value, value.toShort()) } else buffer.putInt(it.value, value)
            }
        }
        return buffer.array()
    }

    private fun uint(pos: Int) = Integer.toUnsignedLong(buffer.getInt(pos))
    private fun address(offset: Long, length: Long): Int {
        check(offset >= 0 && length >= 0 && offset + base + length <= buffer.capacity()) { "EXIF 데이터 위치가 파일 범위를 벗어났어요." }
        return (offset + base).toInt()
    }
}
