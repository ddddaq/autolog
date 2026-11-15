package com.autolog.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QrCodeTest {

    @Test
    fun `글 길이에 맞는 가장 작은 버전을 고른다`() {
        assertEquals(21, QrCode.encode("abc")!!.size)                                  // 버전 1
        assertEquals(29, QrCode.encode("http://192.168.0.10:8765/abcd2345")!!.size)     // 33바이트, 버전 3
        assertEquals(41, QrCode.encode("a".repeat(QrCode.MAX_BYTES))!!.size)           // 버전 6
        assertNull(QrCode.encode("a".repeat(QrCode.MAX_BYTES + 1)))
    }

    @Test
    fun `세 귀퉁이에 위치 찾기 무늬가 있다`() {
        val qr = QrCode.encode("http://192.168.0.10:8765/abcd2345")!!
        val size = qr.size
        for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
            assertTrue(qr[cy][cx])              // 가운데
            assertFalse(qr[cy][cx + 2])         // 밝은 띠
            assertTrue(qr[cy][cx + 3])          // 바깥 테두리
            assertTrue(qr[cy - 3][cx - 3])
        }
        assertTrue(qr[size - 8][8])             // 항상 어두운 칸
    }

    @Test
    fun `같은 글은 같은 무늬가 된다`() {
        val a = QrCode.encode("http://10.0.2.16:8765/k3f9x2ab")!!
        val b = QrCode.encode("http://10.0.2.16:8765/k3f9x2ab")!!
        assertTrue(a.indices.all { a[it].contentEquals(b[it]) })
    }

    /** 만든 무늬를 0과 1의 글 파일로 남긴다. 그림으로 바꿔 다른 QR 해독기로 읽어 보는 데 쓴다(build/qr). */
    @Test
    fun `무늬를 파일로 남긴다`() {
        val samples = mapOf(
            "v1" to "abc",
            "v3" to "http://192.168.0.10:8765/abcd2345",
            "v4" to "http://192.168.100.123:8765/abcd2345/autolog_2026-11.pdf",
            "v6" to "https://example.com/" + "오토로그".repeat(6) + "/0123456789",
        )
        val dir = File("build/qr").apply { mkdirs() }
        samples.forEach { (name, text) ->
            val qr = QrCode.encode(text)
            assertNotNull(qr)
            File(dir, "$name.modules").writeText(qr!!.joinToString("\n") { row -> row.joinToString("") { if (it) "1" else "0" } })
            File(dir, "$name.txt").writeText(text)
        }
    }
}
