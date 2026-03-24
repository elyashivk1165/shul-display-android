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
        val slug = prefs.getString("slug", "") ?: ""

        val targetClass = if (slug.isNotBlank()) MainActivity::class.java else SetupActivity::class.java
        Log.d(TAG, "Launching ${targetClass.simpleName} (slug=${slug.ifBlank { "<empty>" }})")

        try {
            val launchIntent = Intent(this, targetClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(launchIntent)
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
