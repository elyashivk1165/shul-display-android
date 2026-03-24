package app.shul.display

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ShulDisplayApp : Application() {

    companion object {
        var appStartTime: Long = 0L
    }

    override fun onCreate() {
        super.onCreate()
        appStartTime = SystemClock.elapsedRealtime()

        // Send any crash from previous session
        sendPendingCrash()

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
                } catch (_: Exception) {}
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
                    } catch (_: Throwable) {}
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

    private fun sendPendingCrash() {
        val prefs = getSharedPreferences("crash_data", MODE_PRIVATE)
        val stack = prefs.getString("pending_crash_stack", null) ?: return
        // Send in background
        CoroutineScope(Dispatchers.IO).launch {
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
            } catch (_: Exception) {}
        }
    }
}
