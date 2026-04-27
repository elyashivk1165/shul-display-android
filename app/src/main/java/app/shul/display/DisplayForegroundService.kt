package app.shul.display

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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

    // Held for the lifetime of the service. PARTIAL keeps the CPU running so
    // network sockets and coroutines aren't paused during deep doze; the
    // WiFi lock keeps the WiFi radio from going into power-save (which is
    // what kills the Supabase realtime WebSocket overnight on TV boxes).
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

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
        acquireWakeLocks()

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

                    // Liveness breadcrumb: persist the latest heartbeat
                    // timestamp + device_info snapshot. ShulDisplayApp reads
                    // this on the next process startup to detect abnormal
                    // termination (no pending_crash file, but a recent
                    // last_alive_ms => process was killed by OOM / native /
                    // ANR rather than throwing).
                    try {
                        applicationContext
                            .getSharedPreferences("liveness", Context.MODE_PRIVATE)
                            .edit()
                            .putLong("last_alive_ms", System.currentTimeMillis())
                            .putString("last_device_info", info.toString())
                            .apply()
                    } catch (_: Exception) {}

                    // Opportunistic flush of any logs that were buffered to
                    // disk while the network was down — the heartbeat
                    // succeeding means we're back online.
                    try {
                        val flushed = LogBuffer.flush(applicationContext)
                        if (flushed > 0) Log.i(TAG, "Heartbeat flushed $flushed buffered log lines")
                    } catch (_: Exception) {}

                    // Health check every 5th heartbeat (~25 min) - log warnings for critical issues only
                    healthCheckCounter++
                    if (healthCheckCounter % 5 == 0) {
                        val issues = mutableListOf<String>()
                        // Only warn about issues that actually affect functionality
                        if (realtimeListener?.isConnected() != true) issues.add("realtime disconnected")
                        if (!MainActivity.isForeground && !isInScheduledOffPeriod()) issues.add("app not foreground")
                        if (issues.isNotEmpty()) {
                            SupabaseClient.sendLog(deviceId, "WARN",
                                "Health check: ${issues.joinToString(", ")}",
                                appVersion = appVersion,
                                appContext = applicationContext)
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
     *  IMPORTANT: Does NOT recover during scheduled screen-off periods.
     *
     *  Strategy escalates by how long the activity has been backgrounded:
     *    • Every 8s: try a direct startActivity (cheap, often works on TVs
     *      or when SYSTEM_ALERT_WINDOW is granted).
     *    • After 60s of continuous background: also fire a fullScreenIntent
     *      notification (ScreenWakeHelper.wakeToApp). On Android 10+ this is
     *      the only reliable way to bring the activity to the foreground
     *      from a background context, and it works regardless of the app's
     *      launcher / overlay permissions. */
    private fun startBackgroundRecovery(): Job {
        return serviceScope.launch(exceptionHandler) {
            delay(10_000) // wait for startup to settle
            var backgroundedSince = 0L // ms timestamp when we first noticed it backgrounded
            var lastFullScreenIntentAt = 0L
            while (isActive) {
                delay(8_000)
                val nowMs = System.currentTimeMillis()
                if (MainActivity.isAlive && !MainActivity.isForeground) {
                    if (isInScheduledOffPeriod()) { backgroundedSince = 0; continue }
                    if (SetupActivity.isVisible)  { backgroundedSince = 0; continue }
                    if (nowMs - MainActivity.lastSettingsOpenTime < 120_000) { backgroundedSince = 0; continue }

                    if (backgroundedSince == 0L) backgroundedSince = nowMs
                    val backgroundedFor = nowMs - backgroundedSince

                    Log.w(TAG, "BackgroundRecovery: MainActivity backgrounded ${backgroundedFor / 1000}s — bringing to front")
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
                        Log.w(TAG, "BackgroundRecovery: startActivity failed: ${e.message}")
                    }

                    // Escalate to fullScreenIntent if startActivity hasn't worked
                    // for ≥60s. Throttle to once per 60s so we don't spam the
                    // notification shade.
                    if (backgroundedFor >= 60_000 && nowMs - lastFullScreenIntentAt >= 60_000) {
                        Log.w(TAG, "BackgroundRecovery: escalating to fullScreenIntent")
                        try {
                            ScreenWakeHelper.wakeToApp(applicationContext)
                            lastFullScreenIntentAt = nowMs
                        } catch (e: Exception) {
                            Log.w(TAG, "BackgroundRecovery: fullScreenIntent failed: ${e.message}")
                        }
                    }
                } else {
                    backgroundedSince = 0
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
                appVersion = appVersion,
                appContext = applicationContext)
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
        releaseWakeLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Acquire CPU + WiFi wake locks for the lifetime of the service. Both
     *  are idempotent — repeated onStartCommand calls won't double-acquire. */
    private fun acquireWakeLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ShulDisplay::DisplayService"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                // WIFI_MODE_FULL_HIGH_PERF keeps WiFi awake even when the
                // screen is off, which is what we need for the Supabase
                // realtime WebSocket to stay alive overnight.
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "ShulDisplay::DisplayService"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wifi lock: ${e.message}")
        }
    }

    private fun releaseWakeLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
