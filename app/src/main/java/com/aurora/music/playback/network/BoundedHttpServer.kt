package com.aurora.music.playback.network

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ServerSocketFactory

data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteAddress: InetAddress,
)

data class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
    val bodyLength: Long = body.size.toLong(),
    val writeBody: ((OutputStream) -> Unit)? = null,
)

class BoundedHttpServer(
    private val handler: (HttpRequest) -> HttpResponse,
    private val maxBodyBytes: Int = 65_536,
    private val maxConcurrentRequests: Int = 4,
    private val requestTimeoutMillis: Long = 30_000,
    private val serverSocketFactory: ServerSocketFactory = ServerSocketFactory.getDefault(),
) : Closeable {
    private var listener: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null
    private var deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "aurora-http-deadlines").apply { isDaemon = true }
    }
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile var port: Int = 0
        private set

    init {
        require(maxBodyBytes in 0..1_048_576)
        require(maxConcurrentRequests in 1..16)
        require(requestTimeoutMillis in 100..120_000)
    }

    @Synchronized fun start(bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"), port: Int = 0): Int {
        if (listener != null) return this.port
        val server = serverSocketFactory.createServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(bindAddress, port), maxConcurrentRequests)
        }
        if (deadlines.isShutdown) deadlines = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "aurora-http-deadlines").apply { isDaemon = true }
        }
        val executor = ThreadPoolExecutor(
            maxConcurrentRequests, maxConcurrentRequests, 30, TimeUnit.SECONDS,
            SynchronousQueue(), { task -> Thread(task, "aurora-http").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }
        workers = executor
        listener = server
        this.port = server.localPort
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                    catch (_: Exception) { break }
                clients.add(socket)
                try {
                    executor.execute { serve(socket) }
                } catch (_: RejectedExecutionException) {
                    // close immediately; a stalled peer must not block accept.
                    clients.remove(socket)
                    runCatching { socket.close() }
                }
            }
        }, "aurora-http-listen").apply { isDaemon = true }.start()
        return this.port
    }

    private fun serve(socket: Socket) {
        val deadline = try {
            deadlines.schedule({ runCatching { socket.close() } }, requestTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            clients.remove(socket)
            runCatching { socket.close() }
            return
        }
        socket.use {
            try {
                socket.soTimeout = minOf(requestTimeoutMillis, 5_000).toInt()
                val request = readRequest(socket)
                val response = try { handler(request) } catch (_: Exception) { HttpResponse(500) }
                writeResponse(socket.getOutputStream(), response, request.method == "HEAD")
            } catch (error: InvalidRequest) {
                runCatching { writeResponse(socket.getOutputStream(), HttpResponse(error.status), false) }
            } catch (_: Exception) {
                // disconnected or timed out.
            } finally {
                deadline.cancel(false)
                clients.remove(socket)
            }
        }
    }

    private fun readRequest(socket: Socket): HttpRequest {
        val input = BufferedInputStream(socket.getInputStream())
        var remaining = 16_384
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (remaining-- > 0) {
                val value = input.read()
                if (value < 0) throw InvalidRequest(400)
                if (value == 10) {
                    if (bytes.lastOrNull() != 13.toByte()) throw InvalidRequest(400)
                    bytes.removeAt(bytes.lastIndex)
                    return bytes.toByteArray().toString(Charsets.US_ASCII)
                }
                if (bytes.lastOrNull() == 13.toByte()) throw InvalidRequest(400)
                if (value != 13 && (value < 32 || value > 126)) throw InvalidRequest(400)
                bytes.add(value.toByte())
            }
            throw InvalidRequest(431)
        }
        val start = line().split(' ')
        if (start.size != 3 || !start[0].matches(Regex("[A-Z]{1,12}")) ||
            !start[1].startsWith('/') || start[1].startsWith("//") ||
            start[2] !in listOf("HTTP/1.0", "HTTP/1.1")) throw InvalidRequest(400)
        val headers = linkedMapOf<String, String>()
        while (true) {
            val next = line()
            if (next.isEmpty()) break
            val split = next.indexOf(':')
            if (split <= 0) throw InvalidRequest(400)
            val key = next.substring(0, split).lowercase(Locale.ROOT)
            if (!key.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]+")) || key in headers) throw InvalidRequest(400)
            headers[key] = next.substring(split + 1).trim()
            if (headers.size > 64) throw InvalidRequest(431)
        }
        if ("transfer-encoding" in headers) throw InvalidRequest(400)
        val length = headers["content-length"]?.let {
            if (!it.matches(Regex("[0-9]{1,10}"))) throw InvalidRequest(400)
            it.toLongOrNull() ?: throw InvalidRequest(400)
        } ?: 0L
        if (length > maxBodyBytes) throw InvalidRequest(413)
        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read < 0) throw InvalidRequest(400)
            offset += read
        }
        return HttpRequest(start[0], start[1], headers, body, socket.inetAddress)
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse, head: Boolean) {
        require(response.status in 100..599 && response.bodyLength >= 0)
        val reason = when (response.status) {
            200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            410 -> "Gone"; 413 -> "Content Too Large"; 416 -> "Range Not Satisfiable"
            429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"; else -> "Response"
        }
        val header = buildString {
            append("HTTP/1.1 ${response.status} $reason\r\n")
            append("Content-Length: ${response.bodyLength}\r\nConnection: close\r\nCache-Control: no-store\r\n")
            for ((key, value) in response.headers) {
                require(key.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) && value.none { it == '\r' || it == '\n' })
                if (key.lowercase(Locale.ROOT) !in setOf("content-length", "connection", "cache-control")) {
                    append("$key: $value\r\n")
                }
            }
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        if (!head && response.status != 204) {
            response.writeBody?.invoke(output) ?: output.write(response.body)
        }
        output.flush()
    }

    @Synchronized override fun close() {
        runCatching { listener?.close() }
        listener = null
        port = 0
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        workers?.shutdownNow()
        workers = null
        deadlines.shutdownNow()
    }

    private class InvalidRequest(val status: Int) : Exception()
}
