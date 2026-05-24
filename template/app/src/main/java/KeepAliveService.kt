package %%PACKAGE_NAME%%

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Tiny foreground service whose only job is to keep the app process alive while
 * the user is using the app — so things like ongoing audio, websockets, or
 * mid-form state survive being backgrounded (recent apps / Home button) and
 * the OS does not reclaim the process under memory pressure.
 *
 * The notification is low-priority and silent so it never interrupts the user.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ensureChannel()
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
            // If foreground promotion fails (e.g. missing permission), stop quietly —
            // the activity will still run; we just lose the background-alive guarantee.
            try { stopSelf() } catch (_: Throwable) {}
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Running in background",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Keeps the app running so audio, uploads, and sessions don't drop."
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                    setSound(null, null)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = launch?.let {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
            PendingIntent.getActivity(this, 0, it, flags)
        }
        val appName = try { applicationInfo.loadLabel(packageManager).toString() } catch (_: Throwable) { "App" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(appName)
            .setContentText("Running")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "appduct_keep_alive"
        private const val NOTIF_ID = 4711
    }
}
