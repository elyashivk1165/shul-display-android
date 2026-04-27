package app.shul.display

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ShulDisplayApp : Application() {

    companion object {
        var appStartTime: Long = 0L
    }

    // Application-scoped coroutine scope — lives for the lifetime of the process
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appStartTime = SystemClock.elapsedRealtime()

        // Send any crash from previous session (using application scope, not bare CoroutineScope)
        sendPendingCrash()

        // Detect & report abnormal termination from previous session (process
        // killed by OOM, native crash, or anything else that bypassed our
        // uncaught-exception handler — leaving a recent "alive" timestamp
        // but no pending_crash file).
        detectAbnormalTermination()

        // Try to flush any logs buffered to disk while the network was
        // down. Cheap; no-op when nothing pending.
        applicationScope.launch {
            try { LogBuffer.flush(this@ShulDisplayApp) } catch (_: Exception) {}
        }

        // Force light mode globally — must happen before any Activity is created
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        setupCrashHandler()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.w("ShulDisplayApp", "Low memory (level=$level), clearing WebView cache")
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post {
                try {
                    MainActivity.instance?.clearWebViewCache()
                } catch (e: Exception) {
                    Log.w("ShulDisplayApp", "Failed to clear WebView cache on low memory: ${e.message}")
                }
            }
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // 1. Save to disk immediately (never fails)
                getSharedPreferences("crash_data", MODE_PRIVATE).edit()
                    .putString("pending_crash_message", throwable.message ?: "Unknown")
                    .putString("pending_crash_stack", throwable.stackTraceToString().take(4000))
                    .putLong("pending_crash_time", System.currentTimeMillis())
                    .putString("pending_crash_version", DeviceUtils.getAppVersion(this))
                    .commit()

                // 2. Attempt network send on separate thread with hard 3s deadline
                val deviceId = DeviceUtils.getDeviceId(this)
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("level", "CRASH")
                    put("message", throwable.message ?: "Unknown crash")
                    put("stacktrace", throwable.stackTraceToString().take(4000))
                    put("app_version", DeviceUtils.getAppVersion(this@ShulDisplayApp))
                    put("extra", JSONObject().put("thread", thread.name))
                }
                val networkThread = Thread {
                    try {
                        val url = URL("${SupabaseClient.SUPABASE_URL}/rest/v1/device_logs")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            doOutput = true
                            connectTimeout = 3_000
                            readTimeout = 3_000
                            setRequestProperty("Content-Type", "application/json")
                            setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                            setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                        }
                        conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
                        conn.responseCode
                        conn.disconnect()
                    } catch (t: Throwable) {
                        Log.w("ShulDisplayApp", "Failed to send crash report: ${t.message}")
                    }
                }
                networkThread.isDaemon = true
                networkThread.start()
                networkThread.join(3_000)  // hard 3s deadline
            } catch (_: Exception) {
                Log.e("ShulDisplayApp", "Failed to log crash to Supabase")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * If the previous session was alive recently and didn't leave a
     * pending_crash file behind, the process was terminated abnormally —
     * almost always an OOM kill or native crash that our uncaught-exception
     * handler can't see. Log a synthetic ABNORMAL_TERMINATION entry with
     * the last-known device state so we can diagnose remotely.
     */
    private fun detectAbnormalTermination() {
        try {
            val livePrefs = getSharedPreferences("liveness", MODE_PRIVATE)
            val crashPrefs = getSharedPreferences("crash_data", MODE_PRIVATE)

            val lastAlive = livePrefs.getLong("last_alive_ms", 0L)
            val hasPendingCrash = crashPrefs.contains("pending_crash_stack")
            val lastInfo = livePrefs.getString("last_device_info", null)

            // Reset for the new session — we'll start writing fresh in the
            // heartbeat loop. Done first so a failure later in this method
            // doesn't leave a stale heartbeat lurking.
            livePrefs.edit().remove("last_alive_ms").remove("last_device_info").apply()

            if (lastAlive == 0L) return // first run, nothing to report
            if (hasPendingCrash) return  // we already have the real crash, sendPendingCrash handles it

            val ageMs = System.currentTimeMillis() - lastAlive
            // Threshold: if the heartbeat was alive within the last 30 minutes,
            // the gap until now is suspicious. Anything older than that is
            // probably just a graceful shutdown that we don't need to report.
            if (ageMs > 30 * 60 * 1000) return

            applicationScope.launch {
                try {
                    val deviceId = DeviceUtils.getDeviceId(this@ShulDisplayApp)
                    val extra = JSONObject().apply {
                        put("event", "abnormal_termination")
                        put("last_alive_ms", lastAlive)
                        put("gap_ms", ageMs)
                        if (lastInfo != null) put("last_device_info", JSONObject(lastInfo))
                    }
                    SupabaseClient.sendLog(
                        deviceId = deviceId,
                        level = "ERROR",
                        message = "Suspected OOM/abnormal termination — last alive ${ageMs / 1000}s ago, no crash file",
                        extra = extra,
                        appVersion = DeviceUtils.getAppVersion(this@ShulDisplayApp),
                        appContext = this@ShulDisplayApp,
                    )
                } catch (e: Exception) {
                    Log.w("ShulDisplayApp", "Failed to send abnormal-termination log: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("ShulDisplayApp", "detectAbnormalTermination failed: ${e.message}")
        }
    }

    private fun sendPendingCrash() {
        val prefs = getSharedPreferences("crash_data", MODE_PRIVATE)
        val stack = prefs.getString("pending_crash_stack", null) ?: return
        // Send in background using the application-scoped coroutine scope
        applicationScope.launch {
            try {
                val deviceId = DeviceUtils.getDeviceId(this@ShulDisplayApp)
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("level", "CRASH")
                    put("message", prefs.getString("pending_crash_message", "Unknown"))
                    put("stacktrace", stack)
                    put("app_version", prefs.getString("pending_crash_version", "unknown"))
                    put("extra", JSONObject().put("source", "disk_recovery"))
                }
                val url = URL("${SupabaseClient.SUPABASE_URL}/rest/v1/device_logs")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                }
                conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
                conn.responseCode
                conn.disconnect()
                // Clear after successful send
                prefs.edit().remove("pending_crash_stack").remove("pending_crash_message")
                    .remove("pending_crash_time").remove("pending_crash_version").apply()
            } catch (e: Exception) {
                Log.w("ShulDisplayApp", "Failed to send pending crash: ${e.message}")
            }
        }
    }
}
