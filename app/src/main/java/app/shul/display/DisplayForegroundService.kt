package app.shul.display

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class DisplayForegroundService : Service() {

    companion object {
        private const val TAG = "DisplayFgService"
        private const val CHANNEL_ID = "display_service_channel"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, DisplayForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Coroutine exception", e)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startCommandPolling()
        startHeartbeat()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "תצוגת בית הכנסת",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "שירות תצוגה רץ ברקע"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("תצוגת בית הכנסת פעילה")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun startCommandPolling() {
        serviceScope.launch(exceptionHandler) {
            val deviceId = DeviceUtils.getDeviceId(applicationContext)
            val backoff = ExponentialBackoff()
            while (isActive) {
                try {
                    val commands = SupabaseClient.getPendingCommands(deviceId)
                    for (cmd in commands) {
                        try {
                            executeCommand(cmd)
                            SupabaseClient.markCommandExecuted(cmd.id)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to execute command ${cmd.command}", e)
                            SupabaseClient.markCommandExecuted(cmd.id)
                        }
                    }
                    backoff.reset()
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error (attempt #${backoff.failureCount + 1})", e)
                    delay(backoff.nextDelay())
                    continue
                }
                delay(30_000)
            }
        }
    }

    private fun startHeartbeat() {
        serviceScope.launch(exceptionHandler) {
            val deviceId = DeviceUtils.getDeviceId(applicationContext)
            val backoff = ExponentialBackoff()
            while (isActive) {
                try {
                    val info = DeviceUtils.getFullDeviceInfo(applicationContext)
                    SupabaseClient.updateLastSeen(deviceId, info)
                    backoff.reset()
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error", e)
                    delay(backoff.nextDelay())
                    continue
                }
                delay(60_000)
            }
        }
    }

    private suspend fun executeCommand(cmd: DeviceCommand) {
        val activity = MainActivity.instance
        when (cmd.command) {
            "RELOAD" -> {
                withContext(Dispatchers.Main) {
                    activity?.reloadWebViewWithIndicator()
                }
            }
            "UPDATE_SLUG" -> {
                val newSlug = cmd.payload["slug"] as? String
                if (!newSlug.isNullOrBlank()) {
                    val deviceId = DeviceUtils.getDeviceId(applicationContext)
                    getSharedPreferences("shul_display_prefs", MODE_PRIVATE)
                        .edit().putString("slug", newSlug).apply()
                    SupabaseClient.updateDeviceSlug(deviceId, newSlug)
                    withContext(Dispatchers.Main) {
                        activity?.updateSlug(newSlug)
                    }
                }
            }
            "RESTART_APP" -> {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                Runtime.getRuntime().exit(0)
            }
            "CLEAR_CACHE" -> {
                withContext(Dispatchers.Main) {
                    activity?.clearWebViewCache()
                }
            }
            "PING" -> {
                val deviceId = DeviceUtils.getDeviceId(applicationContext)
                val info = DeviceUtils.getFullDeviceInfo(applicationContext)
                SupabaseClient.updateLastSeen(deviceId, info)
            }
            "UPDATE_APP" -> {
                val currentVersion = DeviceUtils.getAppVersion(applicationContext)
                val release = UpdateChecker.checkForUpdate(currentVersion)
                if (release != null) {
                    UpdateChecker.downloadAndInstall(applicationContext, release) { status ->
                        Log.i(TAG, "UPDATE_APP: $status")
                    }
                }
            }
            else -> Log.w(TAG, "Unknown command: ${cmd.command}")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
