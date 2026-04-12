package app.shul.display

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
        // Attempt to restart critical services after failure
        try {
            if (heartbeatJob?.isActive != true) {
                heartbeatJob = startHeartbeat()
            }
        } catch (restart: Exception) {
            Log.e(TAG, "Failed to restart after exception: ${restart.message}")
        }
    }

    // Deduplication set to prevent the same command executing twice (catch-up + realtime race)
    private val executedCommandIds = LinkedHashSet<String>()
    private val commandIdsLock = Any()
    private val MAX_EXECUTED_IDS = 100

    // Track heartbeat job so we never launch duplicates on START_STICKY restarts
    private var heartbeatJob: Job? = null
    private var recoveryJob: Job? = null
    private var realtimeListener: RealtimeCommandListener? = null
    private var screenReceiverRegistered = false

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                // Screen turned on by any means — bring our app to front
                serviceScope.launch {
                    delay(500) // Brief delay for screen to stabilize
                    ScreenWakeHelper.wakeToApp(context)
                    ScreenScheduleManager.wakeScreen(context)
                }
                // Also start MainActivity to ensure FLAG_KEEP_SCREEN_ON is active
                try {
                    val activityIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    startActivity(activityIntent)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start MainActivity from screenOnReceiver: ${e.message}")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Guard: don't launch duplicate coroutines on START_STICKY restarts
        synchronized(this) {
            if (heartbeatJob?.isActive != true) {
                heartbeatJob = startHeartbeat()
            }
            if (realtimeListener == null || realtimeListener?.isConnected() != true) {
                realtimeListener?.stop()
                realtimeListener = null
                startRealtimeListener()
            }
            if (recoveryJob?.isActive != true) {
                recoveryJob = startBackgroundRecovery()
            }
        }

        try {
            if (!screenReceiverRegistered) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON),
                        Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
                }
                screenReceiverRegistered = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register screen on receiver: ${e.message}")
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

    private fun startHeartbeat(): Job {
        return serviceScope.launch(exceptionHandler) {
            val deviceId = DeviceUtils.getDeviceId(applicationContext)
            val appVersion = DeviceUtils.getAppVersion(applicationContext)
            val slug = SecurePrefs.get(applicationContext).getString("slug", "") ?: ""
            val backoff = ExponentialBackoff()
            var healthCheckCounter = 0
            var hasRegistered = false
            while (isActive) {
                try {
                    // Ensure device is registered (covers: first boot, deleted from admin, DB wipe)
                    if (!hasRegistered && slug.isNotBlank()) {
                        SupabaseClient.registerDevice(deviceId, slug, appVersion)
                        hasRegistered = true
                    }
                    val info = DeviceUtils.getFullDeviceInfo(applicationContext)
                    info.put("realtime_connected", realtimeListener?.isConnected() == true)
                    info.put("is_foreground", MainActivity.isForeground)
                    info.put("scheduled_off", isInScheduledOffPeriod())
                    info.put("schedule_enabled", ScreenScheduleManager.isEnabled(applicationContext))
                    info.put("a11y_enabled", ShulAccessibilityService.isEnabled(applicationContext))
                    info.put("admin_active", ScreenScheduleManager.isDeviceAdminActive(applicationContext))
                    SupabaseClient.updateLastSeen(deviceId, info)
                    backoff.reset()

                    // Health check every 5th heartbeat (~25 min) - log warnings for issues
                    healthCheckCounter++
                    if (healthCheckCounter % 5 == 0) {
                        val issues = mutableListOf<String>()
                        if (!ShulAccessibilityService.isEnabled(applicationContext)) issues.add("a11y disabled")
                        if (!ScreenScheduleManager.isDeviceAdminActive(applicationContext)) issues.add("admin not active")
                        if (realtimeListener?.isConnected() != true) issues.add("realtime disconnected")
                        if (!MainActivity.isForeground && !isInScheduledOffPeriod()) issues.add("app not foreground")
                        if (issues.isNotEmpty()) {
                            SupabaseClient.sendLog(deviceId, "WARN",
                                "Health check: ${issues.joinToString(", ")}",
                                appVersion = appVersion)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error", e)
                    delay(backoff.nextDelay())
                    continue
                }
                delay(300_000) // 5 minutes
            }
        }
    }

    /** Background recovery: if MainActivity is alive but not in foreground, bring it back.
     *  IMPORTANT: Does NOT recover during scheduled screen-off periods. */
    private fun startBackgroundRecovery(): Job {
        return serviceScope.launch(exceptionHandler) {
            delay(10_000) // wait for startup to settle
            while (isActive) {
                delay(8_000)
                if (MainActivity.isAlive && !MainActivity.isForeground) {
                    // Don't fight the screen-off schedule!
                    if (isInScheduledOffPeriod()) {
                        continue
                    }
                    // Don't fight SetupActivity (user is in settings)
                    if (SetupActivity.isVisible) {
                        continue
                    }
                    // Don't fight system settings screens — user may have opened
                    // Android settings from SetupActivity (2 minute cooldown)
                    if (System.currentTimeMillis() - MainActivity.lastSettingsOpenTime < 120_000) {
                        continue
                    }
                    Log.w(TAG, "BackgroundRecovery: MainActivity went to background — bringing to front")
                    try {
                        val intent = Intent(applicationContext, MainActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "BackgroundRecovery: could not bring to front: ${e.message}")
                    }
                }
            }
        }
    }

    /** Check if current time falls within the scheduled screen-off period. */
    private fun isInScheduledOffPeriod(): Boolean {
        if (!ScreenScheduleManager.isEnabled(applicationContext)) return false
        val (offTime, onTime, days) = ScreenScheduleManager.getSchedule(applicationContext)
        if (offTime == null || onTime == null) return false

        val cal = java.util.Calendar.getInstance()
        // Check if today is a scheduled day (0=Sun, 1=Mon, ... 6=Sat)
        val todayDay = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1 // Convert 1-7 to 0-6
        if (!days.contains(todayDay)) return false

        val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val offParts = offTime.split(":").map { it.toIntOrNull() ?: 0 }
        val onParts = onTime.split(":").map { it.toIntOrNull() ?: 0 }
        val offMinutes = offParts.getOrElse(0) { 0 } * 60 + offParts.getOrElse(1) { 0 }
        val onMinutes = onParts.getOrElse(0) { 0 } * 60 + onParts.getOrElse(1) { 0 }

        return if (offMinutes <= onMinutes) {
            // Same-day: e.g., OFF at 13:00, ON at 14:00
            currentMinutes in offMinutes until onMinutes
        } else {
            // Overnight: e.g., OFF at 23:00, ON at 07:00
            currentMinutes >= offMinutes || currentMinutes < onMinutes
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
                    safeExecuteCommand(cmd, "realtime")
                }
            },
            onConnected = {
                // Catch-up: fetch any commands missed while disconnected
                val pending = SupabaseClient.getPendingCommands(deviceId)
                pending.forEach { cmd -> safeExecuteCommand(cmd, "catch-up") }
                Log.i(TAG, "Catch-up fetch on reconnect: ${pending.size} pending commands")
            }
        )
        realtimeListener?.start()
        Log.d(TAG, "Realtime listener started")

        // Log service start for remote monitoring
        serviceScope.launch {
            val appVersion = DeviceUtils.getAppVersion(applicationContext)
            SupabaseClient.sendLog(deviceId, "INFO",
                "Service started, realtime connecting...",
                appVersion = appVersion)
        }
    }

    /**
     * Safely execute a command with deduplication, acknowledgment, and result reporting.
     * Flow: dedup check → acknowledge (mark delivered) → execute → report result → heartbeat
     */
    private suspend fun safeExecuteCommand(cmd: DeviceCommand, source: String) {
        // 1. Deduplication check
        synchronized(commandIdsLock) {
            if (!executedCommandIds.add(cmd.id)) {
                Log.w(TAG, "Skipping duplicate command ${cmd.id} from $source (already executed)")
                return
            }
            if (executedCommandIds.size > MAX_EXECUTED_IDS) {
                executedCommandIds.remove(executedCommandIds.first())
            }
        }

        // 2. Acknowledge receipt (marks executed_at so it won't be fetched again)
        SupabaseClient.acknowledgeCommand(cmd.id)

        // 3. Execute and report result
        val deviceId = DeviceUtils.getDeviceId(applicationContext)
        val appVersion = DeviceUtils.getAppVersion(applicationContext)
        try {
            executeCommand(cmd)
            // Only report success if executeCommand didn't already report a specific result
            if (cmd.command !in listOf("SCREEN_OFF", "SCREEN_ON", "RESTART_APP")) {
                SupabaseClient.reportCommandResult(cmd.id, "success")
            }
            // Log successful command execution
            SupabaseClient.sendLog(deviceId, "INFO",
                "Command executed: ${cmd.command} (source=$source)",
                appVersion = appVersion)
        } catch (e: Exception) {
            Log.e(TAG, "$source: failed to execute command ${cmd.command}", e)
            SupabaseClient.reportCommandResult(
                cmd.id, "error",
                e.message?.take(500) ?: "Unknown error"
            )
            // Log failed command
            SupabaseClient.sendLog(deviceId, "ERROR",
                "Command failed: ${cmd.command} — ${e.message}",
                stacktrace = e.stackTraceToString().take(2000),
                appVersion = appVersion)
        }

        // 4. Send immediate heartbeat so admin sees updated device state
        try {
            val deviceId = DeviceUtils.getDeviceId(applicationContext)
            val info = DeviceUtils.getFullDeviceInfo(applicationContext)
            info.put("last_command", cmd.command)
            info.put("realtime_connected", realtimeListener?.isConnected() == true)
            info.put("is_foreground", MainActivity.isForeground)
            SupabaseClient.updateLastSeen(deviceId, info)
        } catch (_: Exception) { }
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
                    SecurePrefs.get(applicationContext)
                        .edit().putString("slug", newSlug).apply()
                    SupabaseClient.updateDeviceSlug(deviceId, newSlug)
                    if (activity == null) throw IllegalStateException("אפליקציה לא פעילה")
                    // updateSlug uses runOnUiThread internally — safe to call from any thread
                    activity.updateSlug(newSlug)
                }
            }
            "RESTART_APP" -> {
                // Sync write before exit
                SecurePrefs.get(applicationContext).edit().commit()
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
                // Fix 2: lockScreen now returns Boolean — report real result
                val success = ScreenScheduleManager.lockScreen(applicationContext)
                SupabaseClient.reportCommandResult(
                    cmd.id,
                    if (success) "success" else "error:no_lock_method_available"
                )
            }
            "SCREEN_ON" -> {
                // Fix 3: Check permissions before attempting wake
                val notificationsEnabled = NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
                val hasOverlay = Settings.canDrawOverlays(applicationContext)

                if (!notificationsEnabled && !hasOverlay) {
                    Log.w(TAG, "SCREEN_ON: no notification permission and no overlay permission")
                    SupabaseClient.reportCommandResult(cmd.id, "error:no_wake_permission")
                    return
                }

                ScreenScheduleManager.wakeScreen(applicationContext)
                ScreenWakeHelper.wakeToApp(applicationContext)
                SupabaseClient.reportCommandResult(cmd.id, "success")
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
            "OPEN_SETTINGS" -> {
                withContext(Dispatchers.Main) {
                    val intent = Intent(this@DisplayForegroundService, SetupActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("from_settings", true)
                    }
                    startActivity(intent)
                }
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
        val triggerAt = System.currentTimeMillis() + 5_000
        // Fix 5: Use setExactAndAllowWhileIdle so the alarm fires reliably even in Doze mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        recoveryJob?.cancel()
        realtimeListener?.stop()
        realtimeListener = null
        try { unregisterReceiver(screenOnReceiver) } catch (e: Exception) { }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
