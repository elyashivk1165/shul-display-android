package app.shul.display

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object ScreenWakeHelper {
    private const val CHANNEL_ID = "screen_wake"
    private const val NOTIF_ID = 8888

    fun wakeToApp(context: Context) {
        createChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("wake_screen", true)
        }
        val pi = PendingIntent.getActivity(
            context, NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Shul Display")
            .setContentText("מפעיל תצוגה...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)   // ← KEY: bypasses background restrictions
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "הפעלת מסך",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setBypassDnd(true)
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
