package com.autolog.app.share

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class PhoneShareTest {

    private val share = PhoneShare()
    private val file = File.createTempFile("autolog", ".pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)); deleteOnExit() }
    private val items = listOf(PhoneShare.Item("autolog_2026-11.pdf", "application/pdf", file))

    @After
    fun tearDown() = share.close()

    /** 테스트에서는 차량의 네트워크 주소 대신 이 컴퓨터 자신으로 접속한다. */
    private fun local(url: String): String = "http://127.0.0.1:" + URL(url).port + URL(url).path

    private fun get(url: String): Pair<Int, ByteArray> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        val code = connection.responseCode
        val body = (if (code < 400) connection.inputStream else connection.errorStream)?.readBytes() ?: ByteArray(0)
        connection.disconnect()
        return code to body
    }

    @Test
    fun `연 주소로 들어가면 받을 파일 목록이 보인다`() {
        assertTrue(share.open("2026년 11월 운행기록부", items, port = 0))
        val (code, body) = get(local(share.state.value!!.url))

        assertEquals(200, code)
        val page = String(body, Charsets.UTF_8)
        assertTrue(page.contains("2026년 11월 운행기록부"))
        assertTrue(page.contains("autolog_2026-11.pdf"))
    }

    @Test
    fun `파일을 받으면 내용이 그대로 오고 받아 간 파일로 남는다`() {
        share.open("운행기록부", items, port = 0)
        val base = local(share.state.value!!.url)

        val (code, body) = get("$base/autolog_2026-11.pdf")
        Thread.sleep(200)

        assertEquals(200, code)
        assertTrue(body.contentEquals(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(listOf("autolog_2026-11.pdf"), share.state.value!!.received)
    }

    @Test
    fun `주소의 값이 틀리거나 없는 파일이면 받을 수 없다`() {
        share.open("운행기록부", items, port = 0)
        val base = local(share.state.value!!.url)
        val port = URL(base).port

        assertEquals(404, get("http://127.0.0.1:$port/").first)
        assertEquals(404, get("http://127.0.0.1:$port/wrongtoken").first)
        assertEquals(404, get("$base/other.pdf").first)
        assertEquals(404, get("$base/..%2F..%2Fetc%2Fpasswd").first)
        assertTrue(share.state.value!!.received.isEmpty())
    }

    @Test
    fun `닫으면 같은 주소로 받을 수 없고 다시 열면 주소가 바뀐다`() {
        share.open("운행기록부", items, port = 0)
        val first = share.state.value!!.url
        share.close()

        assertNull(share.state.value)
        assertFalse(runCatching { get(local(first)).first == 200 }.getOrDefault(false))

        share.open("운행기록부", items, port = 0)
        assertNotEquals(first.substringAfterLast('/'), share.state.value!!.url.substringAfterLast('/'))
    }

    @Test
    fun `보낼 파일이 없으면 열지 않는다`() {
        assertFalse(share.open("운행기록부", emptyList(), port = 0))
        assertNull(share.state.value)
    }
}
