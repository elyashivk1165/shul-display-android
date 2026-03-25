package app.shul.display

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class BootForegroundService : Service() {

    companion object {
        private const val TAG = "BootForegroundService"
        private const val CHANNEL_ID = "boot_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("מפעיל תצוגה...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        val prefs = getSharedPreferences("shul_display_prefs", Context.MODE_PRIVATE)
        val slug = prefs.getString("slug", null)

        Log.d(TAG, "Boot: slug=${if (!slug.isNullOrBlank()) slug else "<empty>"}")

        try {
            val intent = if (!slug.isNullOrBlank()) {
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            } else {
                Intent(this, SetupActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch activity from foreground service", e)
        }

        stopSelf()
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Boot Auto-Start",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used briefly when auto-starting after boot"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
