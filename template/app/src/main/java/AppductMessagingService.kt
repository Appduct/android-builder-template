package %%PACKAGE_NAME%%

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Native push alerts (Firebase Cloud Messaging). Google Play services wakes the
 * phone for high-priority messages even when the app is minimized, locked or
 * closed, which WebView connections cannot guarantee. This file is only
 * compiled into apps that have a Firebase Android config for their package.
 */
class AppductMessagingService : FirebaseMessagingService() {

    companion object {
        const val PREFS = "appduct_push"
        const val KEY_TOKEN = "token"
        const val CHANNEL_ID = "appduct_alerts"
        const val EXTRA_URL = "appduct_push_url"

        fun initialize(context: Context) {
            val app = context.applicationContext
            if (FirebaseApp.getApps(app).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey("%%FCM_API_KEY%%")
                    .setApplicationId("%%FCM_APP_ID%%")
                    .setProjectId("%%FCM_PROJECT_ID%%")
                    .setGcmSenderId("%%FCM_SENDER_ID%%")
                    .build()
                FirebaseApp.initializeApp(app, options)
            }
            ensureChannel(app)
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) saveToken(app, token)
            }.addOnFailureListener { e ->
                android.util.Log.e("AppductPush", "FCM token request failed", e)
            }
        }

        fun saveToken(context: Context, token: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply()
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Important alerts such as new bookings"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                )
            }
            mgr.createNotificationChannel(channel)
        }
    }

    override fun onNewToken(token: String) {
        saveToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = message.notification?.title ?: data["title"] ?: getString(applicationInfo.labelRes)
        val body = message.notification?.body ?: data["body"] ?: data["message"] ?: ""
        val url = data["url"] ?: data["link"] ?: message.notification?.link?.toString()
        ensureChannel(applicationContext)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!url.isNullOrBlank()) putExtra(EXTRA_URL, url)
        }
        val pending = PendingIntent.getActivity(
            this, (System.currentTimeMillis() and 0xFFFFFFF).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify((System.currentTimeMillis() and 0xFFFFFFF).toInt(), notification)
        } catch (e: SecurityException) {
            android.util.Log.w("AppductPush", "Notification permission not granted", e)
        }
    }
}
