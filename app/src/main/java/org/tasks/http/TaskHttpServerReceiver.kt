package org.tasks.http

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

/**
 * Starts and stops the local HTTP API on demand:
 *
 * ```
 * adb shell am broadcast -a org.tasks.intent.action.START_HTTP_SERVER --ei port 8275 --es token SECRET
 * adb shell am broadcast -a org.tasks.intent.action.STOP_HTTP_SERVER
 * ```
 *
 * The token is stored on the first start and reused afterwards; pass `--es token` to replace it.
 * The endpoint is returned as the result data of the start broadcast.
 */
@AndroidEntryPoint
class TaskHttpServerReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: TaskHttpServerController

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> start(context, intent)
            ACTION_STOP -> stop(context)
            else -> Timber.w("Ignoring unknown action %s", intent.action)
        }
    }

    private fun start(context: Context, intent: Intent) {
        val port = intent.getIntExtra(EXTRA_PORT, 0).takeIf { it in 1..65535 }
        val state = controller.start(port, intent.getStringExtra(EXTRA_TOKEN))
        if (!state.running) {
            resultCode = Activity.RESULT_CANCELED
            resultData = state.error ?: "Failed to start the tasks HTTP API"
            return
        }
        keepAlive(context)
        resultCode = Activity.RESULT_OK
        resultData = state.endpoint
        setResultExtras(Bundle().apply { putInt(EXTRA_PORT, state.port) })
    }

    private fun stop(context: Context) {
        controller.stop()
        TaskHttpServerService.stop(context)
        resultCode = Activity.RESULT_OK
        resultData = STOPPED
    }

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

        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"

        private const val STOPPED = "stopped"
    }
}
