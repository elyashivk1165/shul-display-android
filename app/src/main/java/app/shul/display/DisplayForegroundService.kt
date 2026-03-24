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

    // Track polling/heartbeat jobs so we never launch duplicates on START_STICKY restarts
    private var pollingJob: Job? = null
    private var heartbeatJob: Job? = null
    private var realtimeListener: RealtimeCommandListener? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Guard: don't launch duplicate coroutines on START_STICKY restarts
        if (pollingJob?.isActive != true) {
            pollingJob = startCommandPolling()
        }
        if (heartbeatJob?.isActive != true) {
            heartbeatJob = startHeartbeat()
        }
        if (realtimeListener == null) {
            startRealtimeListener()
        }

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

    private fun startCommandPolling(): Job {
        return serviceScope.launch(exceptionHandler) {
            val deviceId = DeviceUtils.getDeviceId(applicationContext)
            val backoff = ExponentialBackoff()
            while (isActive) {
                try {
                    val commands = SupabaseClient.getPendingCommands(deviceId)
                    for (cmd in commands) {
                        try {
                            executeCommand(cmd)
                            SupabaseClient.reportCommandResult(cmd.id, "success")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to execute command ${cmd.command}", e)
                            SupabaseClient.reportCommandResult(
                                cmd.id, "error",
                                e.message?.take(500) ?: "Unknown error"
                            )
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

    private fun startHeartbeat(): Job {
        return serviceScope.launch(exceptionHandler) {
            val deviceId = DeviceUtils.getDeviceId(applicationContext)
            val backoff = ExponentialBackoff()
            while (isActive) {
                try {
                    val info = DeviceUtils.getFullDeviceInfo(applicationContext)
                    info.put("realtime_connected", realtimeListener?.isConnected() == true)
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

    private fun startRealtimeListener() {
        val deviceId = DeviceUtils.getDeviceId(applicationContext)
        val supabaseUrl = SupabaseClient.SUPABASE_URL
        val supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        if (supabaseUrl.isBlank() || supabaseKey.isBlank()) return

        realtimeListener = RealtimeCommandListener(
            deviceId = deviceId,
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseKey,
            onCommand = { cmd ->
                serviceScope.launch(exceptionHandler) {
                    try {
                        executeCommand(cmd)
                        SupabaseClient.reportCommandResult(cmd.id, "success")
                    } catch (e: Exception) {
                        Log.e(TAG, "Realtime: failed to execute command ${cmd.command}", e)
                        SupabaseClient.reportCommandResult(
                            cmd.id, "error",
                            e.message?.take(500) ?: "Unknown error"
                        )
                    }
                }
            }
        )
        realtimeListener?.start()
        Log.d(TAG, "Realtime listener started")
    }

    private suspend fun executeCommand(cmd: DeviceCommand) {
        val activity = MainActivity.instance
        when (cmd.command) {
            "RELOAD" -> {
                if (activity != null) {
                    // reloadWebViewWithIndicator uses runOnUiThread internally — safe to call from any thread
                    activity.reloadWebViewWithIndicator()
                } else {
                    // MainActivity not running — launch it (SetupActivity will redirect to MainActivity if slug exists)
                    val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    if (intent != null) startActivity(intent)
                }
            }
            "UPDATE_SLUG" -> {
                val newSlug = cmd.payload["slug"] as? String
                if (!newSlug.isNullOrBlank()) {
                    val deviceId = DeviceUtils.getDeviceId(applicationContext)
                    getSharedPreferences("shul_display_prefs", MODE_PRIVATE)
                        .edit().putString("slug", newSlug).apply()
                    SupabaseClient.updateDeviceSlug(deviceId, newSlug)
                    if (activity == null) throw IllegalStateException("אפליקציה לא פעילה")
                    // updateSlug uses runOnUiThread internally — safe to call from any thread
                    activity.updateSlug(newSlug)
                }
            }
            "RESTART_APP" -> {
                // Sync write before exit
                getSharedPreferences("shul_display_prefs", MODE_PRIVATE).edit().commit()
                // Report result BEFORE restarting — exit(0) kills the process immediately
                SupabaseClient.reportCommandResult(cmd.id, "success")
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(launchIntent)
                    delay(300) // coroutine-safe delay instead of Thread.sleep
                }
                Runtime.getRuntime().exit(0)
            }
            "CLEAR_CACHE" -> {
                if (activity == null) throw IllegalStateException("אפליקציה לא פעילה")
                // clearWebViewCache uses runOnUiThread internally — safe to call from any thread
                activity.clearWebViewCache()
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
            "SCREEN_OFF" -> {
                ScreenScheduleManager.lockScreen(applicationContext)
            }
            "SCREEN_ON" -> {
                ScreenScheduleManager.wakeScreen(applicationContext)
            }
            "SET_SCHEDULE" -> {
                val offTime = cmd.payload["off_time"] as? String
                val onTime = cmd.payload["on_time"] as? String
                val daysRaw = cmd.payload["days"]
                val days: List<Int> = when (daysRaw) {
                    is org.json.JSONArray -> (0 until daysRaw.length()).map { daysRaw.getInt(it) }
                    is List<*> -> daysRaw.mapNotNull { (it as? Number)?.toInt() }
                    else -> (0..6).toList()
                }
                if (offTime != null && onTime != null && offTime != onTime) {
                    ScreenScheduleManager.setSchedule(applicationContext, offTime, onTime, days)
                } else if (offTime != null && onTime != null) {
                    Log.w(TAG, "SET_SCHEDULE ignored: off_time == on_time ($offTime)")
                }
            }
            "CLEAR_SCHEDULE" -> {
                ScreenScheduleManager.setSchedule(applicationContext, null, null)
            }
            else -> Log.w(TAG, "Unknown command: ${cmd.command}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart the service if it gets removed from recents
        val restartIntent = Intent(applicationContext, DisplayForegroundService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 5_000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        heartbeatJob?.cancel()
        realtimeListener?.stop()
        realtimeListener = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
