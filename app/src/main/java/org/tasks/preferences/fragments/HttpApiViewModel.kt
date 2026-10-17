package org.tasks.preferences.fragments

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.tasks.http.TaskHttpServerController
import org.tasks.http.TaskHttpServerService

@HiltViewModel
class HttpApiViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: TaskHttpServerController,
) : ViewModel() {

    var running by mutableStateOf(controller.state.value.running)
        private set
    var port by mutableStateOf(controller.port)
        private set
    var password by mutableStateOf(controller.password)
        private set
    var error by mutableStateOf(controller.state.value.error)
        private set

    init {
        viewModelScope.launch {
            controller.state.collect { state ->
                running = state.running
                if (state.running) {
                    port = state.port
                }
                error = state.error
            }
        }
    }

    fun start() {
        val state = controller.start()
        error = state.error
        if (state.running) {
            TaskHttpServerService.start(context)
        }
    }

    fun stop() {
        controller.stop()
        TaskHttpServerService.stop(context)
        error = null
    }

    fun changePassword(value: String) {
        controller.setPassword(value)
        password = controller.password
    }

    fun changePort(value: Int) {
        controller.setPort(value)
        port = controller.port
    }
}
