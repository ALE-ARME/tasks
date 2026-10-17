package org.tasks.http

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * A loopback-only HTTP/1.1 server for [TaskApiRouter]. Connections are served one thread apiece and
 * closed after the response, which is all a local client needs.
 */
class TaskHttpServer(
    private val router: TaskApiRouter,
    private val requestedPort: Int,
    private val host: String = LOOPBACK,
    private val readTimeout: Int = READ_TIMEOUT,
) {

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var clients: ExecutorService? = null

    val port: Int get() = server?.localPort ?: requestedPort

    val isRunning: Boolean get() = running.get()

    fun start() {
        check(running.compareAndSet(false, true)) { "The server is already running" }
        try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(host), requestedPort))
            }
            server = socket
            clients = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "tasks-http-client").apply { isDaemon = true }
            }
            Thread({ accept(socket) }, "tasks-http-server").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            running.set(false)
            close()
            throw e
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        close()
    }

    private fun close() {
        runCatching { server?.close() }
        server = null
        clients?.shutdownNow()
        clients = null
    }

    private fun accept(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (running.get()) {
                    Timber.w(e, "Tasks HTTP API stopped accepting connections")
                }
                return
            }
            val executor = clients
            if (executor == null) {
                runCatching { client.close() }
                return
            }
            executor.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = readTimeout
                val output = socket.getOutputStream()
                val request = try {
                    HttpRequestReader(socket.getInputStream()).read()
                } catch (e: Exception) {
                    write(output, HttpResponse.error(400, e.message ?: "Malformed request"))
                    return
                } ?: return
                val response = try {
                    runBlocking { router.handle(request) }
                } catch (e: Exception) {
                    Timber.e(e, "HTTP %s %s failed", request.method, request.target)
                    HttpResponse.error(500, "Unexpected failure: ${e.message}")
                }
                write(output, response)
            }
        } catch (e: SocketException) {
            Timber.d(e, "Client went away")
        } catch (e: IOException) {
            Timber.d(e, "Client went away")
        }
    }

    private fun write(output: OutputStream, response: HttpResponse) {
        try {
            response.write(output)
        } catch (e: SocketException) {
            Timber.d(e, "Client went away")
        } catch (e: IOException) {
            Timber.d(e, "Client went away")
        }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"

        const val DEFAULT_PORT = 8275

        const val READ_TIMEOUT = 30_000
    }
}
