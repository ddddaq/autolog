package com.autolog.app.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * 폰으로 받기. 내보낸 파일을 같은 네트워크에 있는 폰이 받아 갈 수 있게, 사용자가 연 동안만 작은 웹 서버를 띄운다.
 *
 * 주소에는 열 때마다 새로 만든 값이 들어가고, 그 값을 모르면 아무것도 받을 수 없다. 화면을 닫거나 [OPEN_MS]가 지나면 닫힌다.
 * 차량 밖 서버를 거치지 않는다. 파일은 차량에서 폰으로 바로 간다.
 */
class PhoneShare(private val log: (String) -> Unit = {}, private val now: () -> Long = System::currentTimeMillis) {

    data class Item(val name: String, val mime: String, val file: File)

    class State(
        /** 폰에서 열 주소 */
        val url: String,
        /** 주소를 담은 QR 코드. 만들지 못했으면 null */
        val qr: Array<BooleanArray>?,
        /** 차량의 네트워크 주소를 찾았는가. 못 찾았으면 폰이 닿을 수 없다 */
        val reachable: Boolean,
        val files: List<String>,
        /** 폰이 받아 간 파일 이름 */
        val received: List<String>,
        val expiresAtMs: Long,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private var server: ServerSocket? = null
    private var token = ""
    private var title = ""
    private var items = emptyList<Item>()

    /**
     * 파일을 받아 갈 수 있게 연다. 이미 열려 있으면 닫고 새 주소로 다시 연다. 열지 못하면 false.
     * [port]가 쓰이고 있으면 그 뒤 번호를 몇 개 더 시도한다.
     */
    @Synchronized
    fun open(title: String, items: List<Item>, port: Int = DEFAULT_PORT): Boolean {
        close()
        if (items.isEmpty()) return false
        val socket = (0 until PORT_TRIES).firstNotNullOfOrNull { step ->
            runCatching { ServerSocket(if (port == 0) 0 else port + step) }.getOrNull()
        } ?: return false
        val random = SecureRandom()
        token = (1..TOKEN_LENGTH).map { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] }.joinToString("")
        this.title = title
        this.items = items
        server = socket
        val host = localAddress()
        val url = "http://${host ?: "127.0.0.1"}:${socket.localPort}/$token"
        val expiresAt = now() + OPEN_MS
        _state.value = State(url, QrCode.encode(url), reachable = host != null, files = items.map { it.name }, received = emptyList(), expiresAtMs = expiresAt)
        log("폰으로 받기를 엶: $url")
        thread(isDaemon = true, name = "phone-share") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(client, socket) } }
            }
        }
        thread(isDaemon = true, name = "phone-share-timeout") {
            runCatching { Thread.sleep(OPEN_MS) }
            closeIf(socket)
        }
        return true
    }

    /** 닫는다. 이후에는 같은 주소로도 받을 수 없다. */
    @Synchronized
    fun close() {
        val socket = server ?: return
        server = null
        runCatching { socket.close() }
        _state.value = null
        log("폰으로 받기를 닫음")
    }

    @Synchronized
    private fun closeIf(socket: ServerSocket) {
        if (server === socket) close()
    }

    private fun serve(client: Socket, owner: ServerSocket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val request = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val out = BufferedOutputStream(socket.getOutputStream())
            val parts = request.split(' ')
            val method = parts.getOrNull(0)
            val segments = parts.getOrNull(1).orEmpty().substringBefore('?').split('/').filter { it.isNotEmpty() }
            val current: Pair<String, List<Item>> = synchronized(this) { if (server === owner) token to items else "" to emptyList() }
            when {
                method != "GET" && method != "HEAD" -> respond(out, "405 Method Not Allowed", "text/plain; charset=utf-8", "GET만 받습니다".toByteArray(), method)
                current.first.isEmpty() || segments.firstOrNull() != current.first ->
                    respond(out, "404 Not Found", "text/plain; charset=utf-8", "찾을 수 없습니다".toByteArray(), method)
                segments.size == 1 -> respond(out, "200 OK", "text/html; charset=utf-8", indexPage(current.first, current.second).toByteArray(), method)
                else -> {
                    val name = runCatching { URLDecoder.decode(segments[1], "UTF-8") }.getOrDefault(segments[1])
                    val item = current.second.firstOrNull { it.name == name }
                    val bytes = item?.let { runCatching { it.file.readBytes() }.getOrNull() }
                    if (item == null || bytes == null) {
                        respond(out, "404 Not Found", "text/plain; charset=utf-8", "찾을 수 없습니다".toByteArray(), method)
                    } else {
                        respond(out, "200 OK", item.mime, bytes, method, "Content-Disposition: attachment; filename=\"${item.name}\"")
                        if (method == "GET") received(owner, item.name)
                    }
                }
            }
        }
    }

    @Synchronized
    private fun received(owner: ServerSocket, name: String) {
        val current = _state.value ?: return
        if (server !== owner || name in current.received) return
        _state.value = State(current.url, current.qr, current.reachable, current.files, current.received + name, current.expiresAtMs)
        log("폰이 받아 감: $name")
    }

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray, method: String?, vararg headers: String) {
        val head = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (method != "HEAD") out.write(body)
        out.flush()
    }

    /** 폰에서 여는 화면. 받을 파일을 큰 버튼으로 늘어놓는다. */
    private fun indexPage(token: String, items: List<Item>): String {
        val links = items.joinToString("\n") { item ->
            val kind = when (item.name.substringAfterLast('.').lowercase()) {
                "pdf" -> "PDF (서식 모양)"
                "xlsx" -> "엑셀"
                "csv" -> "CSV"
                else -> "파일"
            }
            "<a href=\"/$token/${URLEncoder.encode(item.name, "UTF-8")}\">$kind 받기<small>${escape(item.name)}</small></a>"
        }
        return """<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escape(title)}</title>
<style>
body{font-family:-apple-system,system-ui,sans-serif;margin:24px;color:#1a1c1e;background:#f6f7f9}
h1{font-size:22px;margin:0 0 8px}
p{color:#555;line-height:1.5;margin:0 0 20px}
a{display:block;margin:12px 0;padding:18px 20px;border-radius:14px;background:#0b57d0;color:#fff;text-decoration:none;font-size:18px;font-weight:600}
a small{display:block;margin-top:4px;font-size:13px;font-weight:400;opacity:.85}
footer{margin-top:24px;color:#777;font-size:13px;line-height:1.5}
</style></head>
<body>
<h1>${escape(title)}</h1>
<p>차량의 오토로그에서 내보낸 운행기록부입니다. 받을 파일을 누르세요.</p>
$links
<footer>차량 화면에서 닫으면 이 주소는 더 열리지 않습니다. 파일은 차량에서 이 기기로 바로 옵니다.</footer>
</body></html>
"""
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** 차량이 붙어 있는 네트워크에서의 주소. 폰이 이 주소로 찾아온다. 없으면 null. */
    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        const val DEFAULT_PORT = 8765
        /** 열어 두는 시간(ms). */
        const val OPEN_MS = 5 * 60_000L
        private const val PORT_TRIES = 5
        private const val READ_TIMEOUT_MS = 5_000
        private const val TOKEN_LENGTH = 8
        /** 주소에 넣는 값에 쓰는 글자. 헷갈리는 글자(0, o, 1, l)는 뺐다. */
        private const val TOKEN_CHARS = "abcdefghijkmnpqrstuvwxyz23456789"
    }
}
