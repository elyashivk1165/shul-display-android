package app.shul.display

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            // Try direct activity launch from foreground service
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(launchIntent)
            Log.i(TAG, "MainActivity launched from foreground service")
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service launch failed, using notification: ${e.message}")
            // Fallback: full-screen notification
            ScreenWakeHelper.wakeToApp(this)
        }

        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun buildNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("מפעיל תצוגה...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
