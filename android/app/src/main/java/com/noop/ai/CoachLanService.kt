package com.noop.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.noop.R
import com.noop.ui.appLaunchIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while a [CoachLanServer] window is open, so it keeps answering
 * after the phone locks. Holds no state of its own: it renders [CoachLanServer.state] (the countdown is
 * a chronometer on the window's single deadline) and stops itself when the window closes. Its Stop
 * action closes the window.
 */
class CoachLanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        // startForeground first, always: a service started with startForegroundService must promote
        // itself even when it is about to stop, or the platform kills the app.
        val promoted = runCatching {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIF_ID, build(CoachLanServer.state.value), type)
        }.isSuccess
        if (intent?.action == ACTION_STOP) CoachLanServer.stop(applicationContext)
        if (!promoted || !CoachLanServer.state.value.running) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch {
                CoachLanServer.state.collect { s ->
                    if (!s.running) {
                        ServiceCompat.stopForeground(this@CoachLanService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(s)) }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(s: CoachLanServer.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0, appLaunchIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CoachLanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (s.pending.isNotEmpty()) {
            resources.getQuantityString(R.plurals.coach_lan_notif_pending, s.pending.size, s.pending.size)
        } else {
            s.url.orEmpty()
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(getString(R.string.coach_lan_notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.coach_lan_stop), stop)
            .setOngoing(true)
            .setSilent(s.pending.isEmpty())
            .setOnlyAlertOnce(true)
            .setUsesChronometer(s.running)
            .setChronometerCountDown(true)
            .setWhen(s.expiresAtMillis)
            .setShowWhen(s.running)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(if (s.pending.isEmpty()) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.coach_lan_channel_name), NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "noop_coach_lan"
        private const val NOTIF_ID = 4417
        private const val ACTION_START = "com.noop.ai.COACH_LAN_START"
        private const val ACTION_STOP = "com.noop.ai.COACH_LAN_STOP"

        fun startIntent(context: Context): Intent = Intent(context, CoachLanService::class.java).setAction(ACTION_START)
    }
}
