package com.sb.phototool.media

import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** A validated, two-JPEG SDR-base gain map. The auxiliary JPEG is never re-encoded. */
internal class HdrJpeg private constructor(val auxiliary: ByteArray) {
    fun relocate(blocks: List<ImageMetadata.Block>, primaryLength: Long): List<ImageMetadata.Block> {
        var markerOffset = 2L
        return blocks.map { block ->
            val result = if (isMpf(block)) {
                val index = Mpf(block.data)
                index.relocate(primaryLength, markerOffset + 8)
                ImageMetadata.Block(block.kind, index.bytes)
            } else block
            markerOffset += block.data.size + 4L
            result
        }
    }

    companion object {
        private const val HDR = "http://ns.adobe.com/hdr-gain-map/1.0/"
        private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
        private val XMP = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
        private val MPF = "MPF\u0000".toByteArray()

        fun isMpf(block: ImageMetadata.Block) = block.kind == "226" && block.data.starts(MPF)

        fun read(blocks: List<ImageMetadata.Block>, primaryLength: Long, auxiliary: ByteArray, mpfMarkerOffset: Long, sef: SamsungSef? = null): HdrJpeg {
            require(auxiliary.size >= 4 && auxiliary[0] == 0xff.toByte() && auxiliary[1] == 0xd8.toByte()) { "JPEG 보조 이미지가 아닌 후단 데이터는 지원하지 않아요." }
            val mpf = blocks.filter(::isMpf).singleOrNull() ?: error("HDR MPF 인덱스가 하나가 아니에요.")
            val index = Mpf(mpf.data)
            index.validate(primaryLength, auxiliary.size, mpfMarkerOffset + 8)
            val primaryXml = blocks.filter { it.kind == "225" && it.data.starts(XMP) }.map { xml(it.data.copyOfRange(XMP.size, it.data.size)) }
            require(primaryXml.any { elements -> elements.any { it.getAttributeNS(HDR, "Version") == "1.0" } }) { "일반 다중 이미지 JPEG는 HDR 게인맵으로 처리하지 않아요." }
            val items = primaryXml.flatMap { elements -> elements.filter { it.hasAttributeNS(ITEM, "Semantic") } }
            val motion = sef?.motion
            require(items.size == (if (motion == null) 2 else 3) && items[0].getAttributeNS(ITEM, "Semantic") == "Primary" && items[1].getAttributeNS(ITEM, "Semantic") == "GainMap") { "HDR 외의 다른 이미지·동영상 항목이 있어요." }
            require(items.take(2).all { it.getAttributeNS(ITEM, "Mime") == "image/jpeg" }) { "HDR 항목의 파일 형식이 JPEG가 아니에요." }
            require(items[1].getAttributeNS(ITEM, "Length").toLongOrNull() == auxiliary.size.toLong()) { "HDR 보조 이미지 길이가 맞지 않아요." }
            if (motion != null) {
                val video = items[2]
                require(video.getAttributeNS(ITEM, "Semantic") == "MotionPhoto" && video.getAttributeNS(ITEM, "Mime") == "video/mp4") { "모션 동영상의 XMP 항목이 맞지 않아요." }
                require(video.getAttributeNS(ITEM, "Length").toLongOrNull() == (sef.bytes.size - motion.payloadStart).toLong() && items[0].getAttributeNS(ITEM, "Padding").toLongOrNull() == motion.payloadStart.toLong()) { "모션 동영상의 XMP 위치·길이가 맞지 않아요." }
            }
            require(items.drop(if (motion == null) 0 else 1).all { !it.hasAttributeNS(ITEM, "Padding") || it.getAttributeNS(ITEM, "Padding").toLongOrNull() == 0L }) { "HDR 항목 사이의 별도 데이터는 지원하지 않아요." }
            require(!items[0].hasAttributeNS(ITEM, "Length") || items[0].getAttributeNS(ITEM, "Length").toLongOrNull() == 0L) { "별도 본체 길이를 사용하는 HDR 구조는 지원하지 않아요." }
            val auxiliaryBlocks = jpegBlocks(auxiliary)
            val gainXml = auxiliaryBlocks.filter { it.kind == "225" && it.data.starts(XMP) }.flatMap { xml(it.data.copyOfRange(XMP.size, it.data.size)) }
            require(gainXml.any { it.getAttributeNS(HDR, "Version") == "1.0" }) { "HDR 보조 이미지의 게인맵 정보를 확인하지 못했어요." }
            require(gainXml.none { it.getAttributeNS(HDR, "BaseRenditionIsHDR").equals("True", true) }) { "HDR 본체를 SDR로 변환하는 형식은 지원하지 않아요." }
            // Auxiliary MPF attribute IFDs may carry a version, but must not link more pictures.
            auxiliaryBlocks.filter(::isMpf).forEach { require(!Mpf(it.data, indexRequired = false).hasIndex) { "HDR 보조 이미지에 추가 이미지 참조가 있어요." } }
            return HdrJpeg(auxiliary)
        }

        private fun xml(bytes: ByteArray): List<Element> {
            require(bytes.size <= 4 * 1024 * 1024) { "HDR XMP가 너무 커요." }
            val text = bytes.toString(Charsets.UTF_8)
            require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "HDR XMP의 외부 문서 참조는 허용하지 않아요." }
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
            val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
            val document = builder.parse(ByteArrayInputStream(bytes))
            val nodes = document.getElementsByTagName("*")
            require(nodes.length < 10_000) { "HDR XMP 구조가 너무 커요." }
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        }

        private fun jpegBlocks(bytes: ByteArray): List<ImageMetadata.Block> {
            val input = java.io.DataInputStream(bytes.inputStream())
            require(input.readUnsignedShort() == 0xffd8)
            val result = mutableListOf<ImageMetadata.Block>()
            var marker: Int? = null
            while (true) {
                if (marker == null) {
                    require(input.readUnsignedByte() == 255)
                    do { marker = input.readUnsignedByte() } while (marker == 255)
                }
                if (marker == 217) { require(input.read() == -1) { "HDR 게인맵 뒤에 추가 데이터가 있어요." }; return result }
                val size = input.readUnsignedShort() - 2
                require(size >= 0)
                val payload = ByteArray(size).also(input::readFully)
                if (marker in 224..239 || marker == 254) result += ImageMetadata.Block(marker.toString(), payload)
                if (marker == 218) {
                    while (true) {
                        if (input.readUnsignedByte() == 255) {
                            var next: Int
                            do { next = input.readUnsignedByte() } while (next == 255)
                            if (next != 0 && next !in 208..215) { marker = next; break }
                        }
                    }
                } else marker = null
            }
        }

        private fun ByteArray.starts(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}

private class Mpf(data: ByteArray, indexRequired: Boolean = true) {
    val bytes = data.copyOf()
    private val buffer = ByteBuffer.wrap(bytes).apply {
        require(bytes.size >= 12)
        order(when (getShort(4).toInt() and 65535) { 0x4949 -> ByteOrder.LITTLE_ENDIAN; 0x4d4d -> ByteOrder.BIG_ENDIAN; else -> error("MPF 바이트 순서가 올바르지 않아요.") })
        require(getShort(6).toInt() == 42)
    }
    private var entries = -1
    private var imageCount = 0L
    val hasIndex get() = entries >= 0 || imageCount != 0L

    init {
        val directory = address(uint(8), 2)
        val count = ushort(directory)
        require(count <= 1024 && directory.toLong() + 2 + count * 12L + 4 <= bytes.size)
        for (i in 0 until count) {
            val pos = directory + 2 + i * 12
            when (ushort(pos)) {
                0xb001 -> { require(ushort(pos + 2) == 4 && uint(pos + 4) == 1L); imageCount = uint(pos + 8) }
                0xb002 -> { require(ushort(pos + 2) == 7 && uint(pos + 4) == 32L); entries = address(uint(pos + 8), 32) }
            }
        }
        require(uint(directory + 2 + count * 12) == 0L) { "연결된 MPF 디렉터리는 지원하지 않아요." }
        if (indexRequired) require(imageCount == 2L && entries >= 0) { "두 이미지로 구성된 HDR 게인맵만 지원해요." }
    }

    fun validate(primaryLength: Long, auxiliaryLength: Int, tiffOffset: Long) {
        require(imageCount == 2L && entries >= 0)
        require(uint(entries) and 0x07000000L == 0L && uint(entries + 16) and 0x07000000L == 0L) { "JPEG가 아닌 MPF 항목이에요." }
        require(uint(entries + 4) == primaryLength && uint(entries + 8) == 0L) { "HDR 본체의 MPF 위치·길이가 맞지 않아요." }
        require(uint(entries + 20) == auxiliaryLength.toLong() && uint(entries + 24) + tiffOffset == primaryLength) { "HDR 게인맵의 MPF 위치·길이가 맞지 않아요." }
    }

    fun relocate(primaryLength: Long, tiffOffset: Long) {
        require(primaryLength in 1..0xffffffffL && primaryLength - tiffOffset in 1..0xffffffffL)
        buffer.putInt(entries + 4, primaryLength.toInt())
        buffer.putInt(entries + 24, (primaryLength - tiffOffset).toInt())
    }

    private fun ushort(pos: Int) = buffer.getShort(pos).toInt() and 65535
    private fun uint(pos: Int) = Integer.toUnsignedLong(buffer.getInt(pos))
    private fun address(offset: Long, length: Int): Int {
        require(offset >= 0 && offset + 4 + length <= bytes.size) { "MPF 데이터 위치가 범위를 벗어났어요." }
        return offset.toInt() + 4
    }
}
