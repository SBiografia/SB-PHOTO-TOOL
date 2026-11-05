package com.sb.phototool.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** SEF directory offsets are relative to the directory, so the entire suffix can move intact. */
internal class SamsungSef private constructor(val bytes: ByteArray, val fields: List<Field>) {
    data class Field(val name: String, val payloadStart: Int, val payloadLength: Int)
    val motion: Field? get() = fields.singleOrNull { it.name == "MotionPhoto_Data" }
    val hasEditingData: Boolean get() = fields.any { it.name == "PhotoEditor_Re_Edit_Data" || it.name == "Portrait_Bokeh_Edit_Info" }

    companion object {
        fun read(tail: ByteArray): SamsungSef? {
            if (tail.size < 8 || tail.copyOfRange(tail.size - 4, tail.size).toString(Charsets.US_ASCII) != "SEFT") return null
            val buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            fun uint(at: Int) = Integer.toUnsignedLong(buffer.getInt(at))
            val size = uint(tail.size - 8)
            require(size in 12..(tail.size - 8).toLong()) { "삼성 SEF 디렉터리 길이가 맞지 않아요." }
            val directory = tail.size - 8 - size.toInt()
            require(tail.copyOfRange(directory, directory + 4).toString(Charsets.US_ASCII) == "SEFH") { "삼성 SEF 헤더가 맞지 않아요." }
            val count = uint(directory + 8)
            require(count in 1..4096 && 12 + count * 12 == size) { "삼성 SEF 항목 수가 맞지 않아요." }
            data class Record(val start: Int, val end: Int, val field: Field)
            val records = (0 until count.toInt()).map { i ->
                val entry = directory + 12 + i * 12
                val backwards = uint(entry + 4)
                val length = uint(entry + 8)
                require(backwards <= directory && length in 8..backwards) { "삼성 SEF 항목의 위치·길이가 맞지 않아요." }
                val start = directory - backwards.toInt()
                require(buffer.getInt(start) == buffer.getInt(entry)) { "삼성 SEF 항목 종류가 맞지 않아요." }
                val nameLength = uint(start + 4)
                require(nameLength in 1..1024 && nameLength + 8 <= length) { "삼성 SEF 항목 이름이 올바르지 않아요." }
                val nameBytes = tail.copyOfRange(start + 8, start + 8 + nameLength.toInt())
                require(nameBytes.all { it.toInt() in 32..126 }) { "삼성 SEF 항목 이름을 해석하지 못했어요." }
                val name = nameBytes.toString(Charsets.US_ASCII)
                Record(start, start + length.toInt(), Field(name, start + 8 + nameLength.toInt(), length.toInt() - 8 - nameLength.toInt()))
            }.sortedBy { it.start }
            require(records.zipWithNext().all { (a, b) -> a.end == b.start } && records.last().end == directory) { "삼성 SEF 항목 사이에 확인되지 않은 데이터가 있어요." }
            val first = records.first().start
            val fields = records.map { it.field.copy(payloadStart = it.field.payloadStart - first) }
            require(fields.count { it.name == "MotionPhoto_Data" } <= 1) { "삼성 모션 동영상이 여러 개 있어요." }
            return SamsungSef(tail.copyOfRange(first, tail.size), fields).also { sef ->
                sef.motion?.let { video ->
                    val end = video.payloadStart + video.payloadLength
                    require(video.payloadLength >= 16 && sef.bytes.copyOfRange(video.payloadStart + 4, video.payloadStart + 8).toString(Charsets.US_ASCII) == "ftyp") { "삼성 모션 동영상 형식을 확인하지 못했어요." }
                    var position = video.payloadStart
                    val mp4 = ByteBuffer.wrap(sef.bytes).order(ByteOrder.BIG_ENDIAN)
                    while (position < end) {
                        require(end - position >= 8) { "삼성 모션 동영상 박스가 잘렸어요." }
                        val length = Integer.toUnsignedLong(mp4.getInt(position))
                        val boxLength = when (length) { 0L -> (end - position).toLong(); 1L -> { require(end - position >= 16); mp4.getLong(position + 8) }; else -> length }
                        require(boxLength in (if (length == 1L) 16L else 8L)..(end - position).toLong()) { "삼성 모션 동영상 박스 길이가 맞지 않아요." }
                        position += boxLength.toInt()
                    }
                }
            }
        }
    }
}
