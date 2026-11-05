package com.sb.phototool.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

internal fun sefFixture(vararg fields: Pair<String, ByteArray>): ByteArray {
    val records = fields.map { (name, payload) -> ByteBuffer.allocate(8 + name.length + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x0a010000); putInt(name.length); put(name.toByteArray()); put(payload)
    }.array() }
    val dataSize = records.sumOf { it.size }
    val directorySize = 12 + 12 * fields.size
    val out = ByteBuffer.allocate(dataSize + directorySize + 8).order(ByteOrder.LITTLE_ENDIAN)
    records.forEach(out::put)
    out.put("SEFH".toByteArray()); out.putInt(107); out.putInt(fields.size)
    var offset = 0
    records.forEach { out.putInt(0x0a010000); out.putInt(dataSize - offset); out.putInt(it.size); offset += it.size }
    out.putInt(directorySize); out.put("SEFT".toByteArray())
    return out.array()
}

class SamsungSefTest {
    @Test fun unknownFieldsRemainOpaqueAndRelativeToMovedDirectory() {
        val suffix = sefFixture("Unknown_Future_Field" to byteArrayOf(0, 1, -1), "Image_UTC_Data" to "1234567890123".toByteArray())
        val parsed = SamsungSef.read(byteArrayOf(1, 2, 3) + suffix)!!
        assertArrayEquals(suffix, parsed.bytes)
        assertEquals(listOf("Unknown_Future_Field", "Image_UTC_Data"), parsed.fields.map { it.name })
        assertFalse(parsed.hasEditingData)
    }
    @Test fun invalidDirectoryOffsetFails() {
        val suffix = sefFixture("Image_UTC_Data" to byteArrayOf(1))
        ByteBuffer.wrap(suffix).order(ByteOrder.LITTLE_ENDIAN).putInt(8 + "Image_UTC_Data".length + 1 + 16, Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { SamsungSef.read(suffix) }
    }
    @Test fun portraitEditingDataIsIdentified() {
        assertTrue(SamsungSef.read(sefFixture("Portrait_Bokeh_Edit_Info" to byteArrayOf(1)))!!.hasEditingData)
    }
    @Test fun truncatedMp4Fails() {
        val video = ByteBuffer.allocate(16).putInt(100).put("ftypmp42".toByteArray()).putInt(0).array()
        assertThrows(IllegalArgumentException::class.java) { SamsungSef.read(sefFixture("MotionPhoto_Data" to video)) }
    }
}
