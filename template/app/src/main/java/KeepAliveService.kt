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
 * Supports user-visible live sync while the activity is backgrounded.
 * Does not guarantee process survival, bypass Doze, or deliver booking pushes.
 * Android 15+ limits dataSync background service time; native FCM delivery is
 * required for alerts after that limit or when the app process is unavailable.
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
        } catch (error: Throwable) {
            android.util.Log.e("AppductKeepAlive", "Foreground live sync could not start", error)
            try { stopSelf() } catch (_: Throwable) {}
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ requires dataSync services to stop promptly at their time limit.
        android.util.Log.w("AppductKeepAlive", "Android background sync time limit reached; push alerts require FCM")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
