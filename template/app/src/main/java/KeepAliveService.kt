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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
    private val handler = Handler(Looper.getMainLooper())
    private var cpuLease: PowerManager.WakeLock? = null
    private var backgrounded = false
    private var stopping = false
    private fun shouldHoldCpu(): Boolean = backgrounded && !stopping

    private val renewCpuLease = object : Runnable {
        override fun run() {
            if (!shouldHoldCpu()) return
            try {
                val power = getSystemService(POWER_SERVICE) as PowerManager
                val lease = cpuLease ?: power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "$packageName:BackgroundSync"
                ).apply { setReferenceCounted(false) }.also { cpuLease = it }
                // Each lease expires even if the renewal handler stops running.
                // Android/OEM battery restrictions and dataSync limits still apply.
                lease.acquire(CPU_LEASE_MS)
                handler.postDelayed(this, CPU_RENEW_MS)
            } catch (error: Exception) {
                android.util.Log.w("AppductKeepAlive", "Background CPU lease unavailable", error)
                releaseCpuLease()
            }
        }
    }

    private fun updateBackgrounded(value: Boolean) {
        backgrounded = value
        releaseCpuLease()
        if (shouldHoldCpu()) handler.post(renewCpuLease)
    }

    private fun releaseCpuLease() {
        handler.removeCallbacks(renewCpuLease)
        try { cpuLease?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        cpuLease = null
    }

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
            stopping = false
            runningService = this
            stateListener?.invoke(true)
            updateBackgrounded(appBackgrounded)
        } catch (error: Throwable) {
            android.util.Log.e("AppductKeepAlive", "Foreground live sync could not start", error)
            stopping = true
            stateListener?.invoke(false)
            releaseCpuLease()
            try { stopSelf() } catch (_: Throwable) {}
            return START_NOT_STICKY
        }
        // No orphan restart: the booking website lives in the Activity, not here.
        // MainActivity starts us again when the user returns to the foreground.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ requires dataSync services to stop promptly at their time limit.
        android.util.Log.w("AppductKeepAlive", "Android background sync time limit reached; push alerts require FCM")
        stopping = true
        stateListener?.invoke(false)
        releaseCpuLease()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        releaseCpuLease()
        if (runningService === this) {
            runningService = null
            stateListener?.invoke(false)
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Running in background",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Supports live sync while minimized; Android battery and time limits apply."
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CPU_LEASE_MS = 120_000L
        private const val CPU_RENEW_MS = 60_000L
        private var runningService: KeepAliveService? = null
        private var appBackgrounded = false
        private var stateListener: ((Boolean) -> Unit)? = null

        fun observeState(listener: ((Boolean) -> Unit)?) {
            stateListener = listener
            listener?.invoke(runningService?.stopping == false)
        }

        // Called on the main thread by the Activity; never starts from background.
        fun setAppBackgrounded(value: Boolean) {
            appBackgrounded = value
            runningService?.updateBackgrounded(value)
        }

        private const val CHANNEL_ID = "appduct_keep_alive"
        private const val NOTIF_ID = 4711
    }
}
