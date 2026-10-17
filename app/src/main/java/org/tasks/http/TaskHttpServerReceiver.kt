package org.tasks.http

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

/**
 * Starts and stops the local HTTP API on demand, so an automation app or an agent can turn it on
 * only while it needs it:
 *
 * ```
 * adb shell am broadcast -a org.tasks.intent.action.START_HTTP_SERVER
 * adb shell am broadcast -a org.tasks.intent.action.STOP_HTTP_SERVER
 * adb shell am broadcast -a org.tasks.intent.action.TOGGLE_HTTP_SERVER
 * ```
 *
 * The password is the one set in the settings screen; passing `--es password` replaces it. The
 * endpoint is returned as the result data of the start and toggle broadcasts.
 */
@AndroidEntryPoint
class TaskHttpServerReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: TaskHttpServerController

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> start(context, intent)
            ACTION_STOP -> stop(context)
            ACTION_TOGGLE -> toggle(context, intent)
            else -> Timber.w("Ignoring unknown action %s", intent.action)
        }
    }

    private fun start(context: Context, intent: Intent) {
        report(controller.start(port(intent), password(intent)), context)
    }

    private fun toggle(context: Context, intent: Intent) {
        report(controller.toggle(), context)
    }

    private fun stop(context: Context) {
        controller.stop()
        TaskHttpServerService.stop(context)
        resultCode = Activity.RESULT_OK
        resultData = STOPPED
    }

    private fun report(state: TaskHttpServerState, context: Context) {
        if (!state.running) {
            resultCode = Activity.RESULT_CANCELED
            resultData = state.error ?: "Failed to start the tasks HTTP API"
            return
        }
        keepAlive(context)
        resultCode = Activity.RESULT_OK
        resultData = state.endpoint
    }

    private fun port(intent: Intent): Int? =
        intent.getIntExtra(EXTRA_PORT, 0).takeIf { it in 1..65535 }

    private fun password(intent: Intent): String? =
        intent.getStringExtra(EXTRA_PASSWORD) ?: intent.getStringExtra(EXTRA_TOKEN)

    private fun keepAlive(context: Context) {
        try {
            TaskHttpServerService.start(context)
        } catch (e: Exception) {
            Timber.w(e, "Keeping the tasks HTTP API alive in the background was refused")
        }
    }

    companion object {
        const val ACTION_START = "org.tasks.intent.action.START_HTTP_SERVER"
        const val ACTION_STOP = "org.tasks.intent.action.STOP_HTTP_SERVER"
        const val ACTION_TOGGLE = "org.tasks.intent.action.TOGGLE_HTTP_SERVER"

        const val EXTRA_PORT = "port"
        const val EXTRA_PASSWORD = "password"

        /** The name this extra had before the password could be set in the app. */
        const val EXTRA_TOKEN = "token"

        private const val STOPPED = "stopped"
    }
}
