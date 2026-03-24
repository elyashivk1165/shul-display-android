package app.shul.display

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
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

        // Force light mode globally — must happen before any Activity is created
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        // Global crash handler — log to Supabase before dying
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val deviceId = DeviceUtils.getDeviceId(this)
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("level", "CRASH")
                    put("message", throwable.message ?: "Unknown crash")
                    put("stacktrace", throwable.stackTraceToString().take(4000))
                    put("app_version", DeviceUtils.getAppVersion(this@ShulDisplayApp))
                    put("extra", JSONObject().put("thread", thread.name))
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
            } catch (_: Exception) {
                Log.e("ShulDisplayApp", "Failed to log crash to Supabase")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
