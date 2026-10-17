package org.tasks.http

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.todoroo.astrid.activity.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.tasks.R
import timber.log.Timber

/**
 * Keeps the process that hosts the tasks HTTP API out of the cached and frozen states while the
 * API is running. If the platform refuses the foreground start, the API keeps serving from
 * whatever process is hosting it, which is enough for the short window an agent needs.
 */
@AndroidEntryPoint
class TaskHttpServerService : Service() {

    @Inject lateinit var controller: TaskHttpServerController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            return stop()
        }
        if (!controller.state.value.running) {
            Timber.w("The tasks HTTP API is not running, stopping the service")
            return stop()
        }
        if (!foreground()) {
            return stop()
        }
        return START_NOT_STICKY
    }

    private fun foreground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        true
    } catch (e: Exception) {
        Timber.w(e, "The tasks HTTP API will not be kept in the foreground")
        false
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_check_black_24dp)
        .setContentTitle(getString(R.string.http_server_notification_title))
        .setContentText(getString(R.string.http_server_notification_text))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun stop(): Int {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.http_server_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL = "http_server"
        private const val NOTIFICATION_ID = 101

        private const val ACTION_START = "org.tasks.intent.action.KEEP_HTTP_SERVER"
        private const val ACTION_STOP = "org.tasks.intent.action.RELEASE_HTTP_SERVER"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TaskHttpServerService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TaskHttpServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
