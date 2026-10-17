package org.tasks.http

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
    const val PORT = "http_api_port"
    const val PASSWORD = "http_api_password"
}

/**
 * Holds the one HTTP server the app can have running. It is off until something asks for it, from
 * the settings screen or from a broadcast, and while it runs it binds a loopback socket only:
 * nothing is reachable from the network, and every request still needs the password.
 */
@Singleton
class TaskHttpServerController @Inject constructor(
    private val engine: ApiQueryEngine,
    private val writer: ApiWriter,
    private val preferences: Preferences,
) {

    private val _state = MutableStateFlow(TaskHttpServerState())

    private var server: TaskHttpServer? = null
    private var servingPort = 0
    private var servingPassword = ""

    val state: StateFlow<TaskHttpServerState> = _state.asStateFlow()

    val password: String
        get() = preferences.getStringValue(TaskHttpServerSettings.PASSWORD).orEmpty()

    val port: Int
        get() = preferences.getInt(TaskHttpServerSettings.PORT, TaskHttpServer.DEFAULT_PORT)
            .takeIf { it in 1..65535 }
            ?: TaskHttpServer.DEFAULT_PORT

    @Synchronized
    fun start(requestedPort: Int? = null, requestedPassword: String? = null): TaskHttpServerState {
        val newPort = requestedPort?.takeIf { it in 1..65535 } ?: port
        val newPassword = requestedPassword?.trim()?.takeIf { it.isNotEmpty() } ?: password
        if (newPassword.isEmpty()) {
            return fail("Set a password before starting the HTTP API")
        }
        if (server?.isRunning == true) {
            if (newPort == servingPort && newPassword == servingPassword) return _state.value
            stop()
        }
        return try {
            val server = TaskHttpServer(
                router = TaskApiRouter(DatabaseTaskApi(engine, writer), newPassword),
                requestedPort = newPort,
            )
            server.start()
            this.server = server
            servingPort = server.port
            servingPassword = newPassword
            store(server.port, newPassword)
            TaskHttpServerState(running = true, port = server.port)
                .also { _state.value = it }
                .also { Timber.i("Tasks HTTP API listening on %s", it.endpoint) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start the tasks HTTP API")
            fail(e.message ?: e.toString())
        }
    }

    @Synchronized
    fun stop(): TaskHttpServerState {
        server?.stop()
        server = null
        servingPort = 0
        servingPassword = ""
        Timber.i { "Tasks HTTP API stopped" }
        return TaskHttpServerState().also { _state.value = it }
    }

    @Synchronized
    fun toggle(): TaskHttpServerState =
        if (server?.isRunning == true) stop() else start()

    /**
     * Changing the password or the port while the API runs restarts it, so the change takes effect
     * at once and a client is never left talking to a server whose password it no longer knows.
     */
    fun setPassword(newPassword: String) {
        preferences.setString(TaskHttpServerSettings.PASSWORD, newPassword.trim())
        restartIfRunning()
    }

    fun setPort(newPort: Int) {
        if (newPort !in 1..65535) return
        preferences.setInt(TaskHttpServerSettings.PORT, newPort)
        restartIfRunning()
    }

    private fun restartIfRunning() {
        if (server?.isRunning == true) {
            start()
        }
    }

    private fun fail(message: String) =
        TaskHttpServerState(error = message).also { _state.value = it }

    private fun store(port: Int, password: String) {
        preferences.setInt(TaskHttpServerSettings.PORT, port)
        preferences.setString(TaskHttpServerSettings.PASSWORD, password)
    }
}
