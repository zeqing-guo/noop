package com.noop.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * The minimal HTTP/1.1 the computer-query server ([CoachLanServer]) speaks: one request per
 * connection, `Content-Length` bodies only, `Connection: close` on every reply. Written by hand so
 * the feature adds no dependency; everything here is pure over streams and unit-tested.
 */
internal data class LanHttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names lower-cased. */
    val headers: Map<String, String>,
    val body: String,
)

internal data class LanHttpResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json; charset=utf-8",
)

internal class LanHttpException(val status: Int, message: String) : Exception(message)

internal object LanHttp {
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_BODY_BYTES = 64 * 1024

    /** Reads one request. Throws [LanHttpException] (400/413/431) on anything malformed or oversized. */
    fun parse(input: InputStream): LanHttpRequest {
        val head = readHead(input)
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty().split(" ")
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) {
            throw LanHttpException(400, "malformed request line")
        }
        val method = requestLine[0].uppercase()
        val target = requestLine[1]
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) throw LanHttpException(400, "malformed header")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw LanHttpException(400, "chunked bodies are not supported")
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: throw LanHttpException(400, "bad content-length") } ?: 0
        if (length < 0) throw LanHttpException(400, "bad content-length")
        if (length > MAX_BODY_BYTES) throw LanHttpException(413, "body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw LanHttpException(400, "truncated body")
            read += n
        }
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
        return LanHttpRequest(method, path, query, headers, String(body, Charsets.UTF_8))
    }

    private fun readHead(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw LanHttpException(400, "connection closed before headers ended")
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (buf.size() > MAX_HEADER_BYTES) throw LanHttpException(431, "headers too large")
        }
        val bytes = buf.toByteArray()
        return String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1)
    }

    fun parseQuery(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            val v = if (eq >= 0) pair.substring(eq + 1) else ""
            val key = runCatching { URLDecoder.decode(k, "UTF-8") }.getOrNull() ?: continue
            val value = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrNull() ?: continue
            out[key] = value
        }
        return out
    }

    fun write(out: OutputStream, response: LanHttpResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        410 -> "Gone"
        413 -> "Payload Too Large"
        421 -> "Misdirected Request"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        503 -> "Service Unavailable"
        else -> if (status >= 500) "Internal Server Error" else "Error"
    }
}
