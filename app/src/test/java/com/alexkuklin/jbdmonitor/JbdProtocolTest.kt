package com.alexkuklin.jbdmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JbdProtocolTest {

    private fun frame(cmd: Int, data: ByteArray): ByteArray {
        var sum = data.size
        data.forEach { sum += it.toInt() and 0xFF }
        val crc = (0x10000 - sum) and 0xFFFF
        return byteArrayOf(0xDD.toByte(), cmd.toByte(), 0, data.size.toByte()) + data +
            byteArrayOf((crc shr 8).toByte(), crc.toByte(), 0x77)
    }

    private fun u16(vararg v: Int) = v.flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray()

    private fun basicPayload(): ByteArray =
        u16(5320, -1250 and 0xFFFF, 8000, 10000, 42, 0, 0b101, 0, 0, 0) // v, i, rem, nom, cycles, date, bal lo, bal hi, prot, ver/soc placeholder
            .copyOfRange(0, 18) + byteArrayOf(0x20, 80, 3, 4, 2) + u16(2731 + 255, 2731 - 30)

    @Test
    fun parsesBasicInfo() {
        val info = JbdProtocol.parseBasic(basicPayload())!!
        assertEquals(53.20f, info.packVoltage, 0.001f)
        assertEquals(-12.5f, info.current, 0.001f)
        assertEquals(80f, info.remainingAh, 0.001f)
        assertEquals(100f, info.nominalAh, 0.001f)
        assertEquals(80, info.soc)
        assertEquals(4, info.cellCount)
        assertEquals(listOf(25.5f, -3.0f), info.temps)
        assertEquals(-665f, info.powerW, 0.5f)
        assertEquals(0b101L, info.balanceMask)
        assertTrue(info.chargeFetOn && info.dischargeFetOn)
    }

    @Test
    fun parsesCells() {
        val cells = JbdProtocol.parseCells(u16(3312, 3310, 3320, 3305))
        assertEquals(listOf(3.312f, 3.310f, 3.320f, 3.305f), cells)
    }

    @Test
    fun reassemblesChunkedFramesAndSkipsGarbage() {
        val a = frame(3, basicPayload())
        val b = frame(4, u16(3300, 3301))
        val stream = byteArrayOf(0x01, 0x02) + a + b
        val asm = FrameAssembler()
        val out = stream.toList().chunked(20).flatMap { asm.feed(it.toByteArray()) }
        assertEquals(listOf(3, 4), out.map { it.cmd })
        assertNotNull(JbdProtocol.parseBasic(out[0].data))
    }

    @Test
    fun rejectsBadChecksum() {
        val a = frame(4, u16(3300, 3301))
        a[5] = (a[5] + 1).toByte()
        assertTrue(FrameAssembler().feed(a).isEmpty())
    }

    @Test
    fun logScaleEndpoints() {
        assertEquals(0f, logFraction(0f), 1e-6f)
        assertEquals(1f, logFraction(200f), 1e-6f)
        assertEquals(1f, logFraction(-500f), 1e-6f)
        assertTrue(logFraction(10f) > 0.4f) // log scale: 10 A is nearly half way
    }

    @Test
    fun etaDirection() {
        val info = JbdProtocol.parseBasic(basicPayload())!!
        assertEquals("Time to empty", estimateEta(info, -10f)!!.label)
        assertEquals(8f, estimateEta(info, -10f)!!.hours, 0.01f)
        assertEquals("Time to full", estimateEta(info, 10f)!!.label)
        assertEquals(2f, estimateEta(info, 10f)!!.hours, 0.01f)
        assertEquals(null, estimateEta(info, 0.1f))
    }
}
