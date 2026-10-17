package org.tasks.http

import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tasks.api.ApiQueryEngine
import org.tasks.api.ApiWriter
import org.tasks.preferences.Preferences
import timber.log.Timber

data class TaskHttpServerState(
    val running: Boolean = false,
    val port: Int = 0,
    val error: String? = null,
) {
    val endpoint: String
        get() = if (running) "http://${TaskHttpServer.LOOPBACK}:$port/${TaskApiRouter.API_VERSION}" else ""
}

object TaskHttpServerSettings {
    const val PORT = "http_server_port"
    const val TOKEN = "http_server_token"
}

/**
 * Holds the one HTTP server the app can have running. Starting it binds a loopback socket only:
 * nothing is reachable from the network, and every request still needs the token.
 */
@Singleton
class TaskHttpServerController @Inject constructor(
    private val engine: ApiQueryEngine,
    private val writer: ApiWriter,
    private val preferences: Preferences,
) {

    private val _state = MutableStateFlow(TaskHttpServerState())

    private var server: TaskHttpServer? = null

    val state: StateFlow<TaskHttpServerState> = _state.asStateFlow()

    @Synchronized
    fun start(requestedPort: Int? = null, requestedToken: String? = null): TaskHttpServerState {
        if (server?.isRunning == true) return _state.value
        return try {
            val port = requestedPort ?: port()
            val token = requestedToken?.takeIf { it.isNotBlank() } ?: storedToken()
            val server = TaskHttpServer(
                router = TaskApiRouter(DatabaseTaskApi(engine, writer), token),
                requestedPort = port,
            )
            server.start()
            this.server = server
            store(server.port, token)
            TaskHttpServerState(running = true, port = server.port)
                .also { _state.value = it }
                .also { Timber.i("Tasks HTTP API listening on %s", it.endpoint) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start the tasks HTTP API")
            TaskHttpServerState(error = e.message ?: e.toString()).also { _state.value = it }
        }
    }

    @Synchronized
    fun stop(): TaskHttpServerState {
        server?.stop()
        server = null
        Timber.i { "Tasks HTTP API stopped" }
        return TaskHttpServerState().also { _state.value = it }
    }

    @Synchronized
    fun restart(): TaskHttpServerState {
        stop()
        return start()
    }

    fun port(): Int = preferences.getInt(TaskHttpServerSettings.PORT, TaskHttpServer.DEFAULT_PORT)
        .takeIf { it in 1..65535 }
        ?: TaskHttpServer.DEFAULT_PORT

    private fun storedToken(): String {
        val stored = preferences.getStringValue(TaskHttpServerSettings.TOKEN)
        if (!stored.isNullOrEmpty()) return stored
        val token = newToken()
        preferences.setString(TaskHttpServerSettings.TOKEN, token)
        Timber.i { "Generated a tasks HTTP API token" }
        return token
    }

    private fun store(port: Int, token: String) {
        preferences.setInt(TaskHttpServerSettings.PORT, port)
        preferences.setString(TaskHttpServerSettings.TOKEN, token)
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
