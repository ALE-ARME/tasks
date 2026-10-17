package org.tasks.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val CONTENT_LENGTH = "content-length"
private const val MAX_LINE_BYTES = 8 * 1024
private const val MAX_HEADER_BYTES = 64 * 1024
private const val MAX_BODY_BYTES = 4 * 1024 * 1024

/**
 * A parsed HTTP/1.1 request. The server only ever serves a loopback client, so this keeps to what
 * the tasks API needs: a method, a target, headers and a body.
 */
class HttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    private val targetParts = target.split('?', limit = 2)

    val path: String = decode(targetParts[0])

    val query: Map<String, List<String>> = targetParts
        .getOrNull(1)
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
            raw.split('&')
                .filter { it.isNotEmpty() }
                .groupBy({ decode(it.substringBefore('=')) }, { decode(it.substringAfter('=', "")) })
        }
        .orEmpty()

    fun header(name: String): String? = headers[name.lowercase()]

    val authorization: String? get() = header("authorization")

    val contentType: String? get() = header("content-type")

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Malformed encoding in '$value'", e)
    }
}

/**
 * Reads one HTTP/1.1 request off a connection. Returns null when the client closed the connection
 * before sending anything, and throws [IllegalArgumentException] on a request that cannot be
 * parsed, which the server answers with a 400.
 */
class HttpRequestReader(private val input: InputStream) {

    fun read(): HttpRequest? {
        val requestLine = readLine() ?: return null
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ')
        if (parts.size != 3) {
            throw IllegalArgumentException("Malformed request line: '$requestLine'")
        }
        val (method, target, protocol) = parts
        if (!protocol.startsWith("HTTP/1")) {
            throw IllegalArgumentException("Unsupported protocol: '$protocol'")
        }
        val headers = readHeaders()
        return HttpRequest(method.uppercase(), target, headers, readBody(headers))
    }

    private fun readHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var size = 0
        while (true) {
            val line = readLine() ?: throw IllegalArgumentException("Unexpected end of headers")
            if (line.isEmpty()) return headers
            size += line.length
            if (size > MAX_HEADER_BYTES) {
                throw IllegalArgumentException("Headers are too large")
            }
            val separator = line.indexOf(':')
            if (separator <= 0) {
                throw IllegalArgumentException("Malformed header: '$line'")
            }
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
    }

    private fun readBody(headers: Map<String, String>): String {
        val length = headers[CONTENT_LENGTH]?.toIntOrNull() ?: return ""
        if (length < 0) {
            throw IllegalArgumentException("Negative $CONTENT_LENGTH")
        }
        if (length > MAX_BODY_BYTES) {
            throw IllegalArgumentException("$CONTENT_LENGTH $length is over the $MAX_BODY_BYTES byte limit")
        }
        if (length == 0) return ""
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) {
                throw IllegalArgumentException("Unexpected end of body")
            }
            read += count
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                if (line.size() == 0) return null
                throw IllegalArgumentException("Unterminated line")
            }
            if (byte == '\n'.code) {
                val text = line.toString("UTF-8")
                return text.removeSuffix("\r")
            }
            line.write(byte)
            if (line.size() > MAX_LINE_BYTES) {
                throw IllegalArgumentException("Line is longer than $MAX_LINE_BYTES bytes")
            }
        }
    }
}

class HttpResponse(
    val status: Int,
    val body: String = "",
    val contentType: String = JSON,
    val headers: Map<String, String> = emptyMap(),
) {

    fun write(output: OutputStream) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) {
            output.write(bytes)
        }
        output.flush()
    }

    companion object {
        const val JSON = "application/json; charset=utf-8"

        fun error(status: Int, message: String) = HttpResponse(status, jsonError(message))

        fun jsonError(message: String) = buildJsonObject { put("error", message) }.toString()

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            422 -> "Unprocessable Content"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Unknown"
        }
    }
}
