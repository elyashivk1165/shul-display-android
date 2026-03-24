package app.shul.display

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.webkit.WebView
import android.view.WindowManager
import org.json.JSONObject
import java.util.UUID

object DeviceUtils {

    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        // ANDROID_ID can be null or the emulator default value — fall back to a stored UUID
        return if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            androidId
        } else {
            val prefs = context.getSharedPreferences("shul_display_prefs", Context.MODE_PRIVATE)
            prefs.getString("fallback_device_id", null) ?: run {
                val newId = UUID.randomUUID().toString()
                prefs.edit().putString("fallback_device_id", newId).apply()
                newId
            }
        }
    }

    fun getAppVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    fun getFullDeviceInfo(context: Context): JSONObject {
        val info = JSONObject()

        // Basic device info
        info.put("device_manufacturer", Build.MANUFACTURER)
        info.put("device_model", Build.MODEL)
        info.put("android_version", Build.VERSION.RELEASE)
        info.put("api_level", Build.VERSION.SDK_INT)

        // Screen resolution
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            info.put("screen_resolution", "${metrics.widthPixels}x${metrics.heightPixels}")
        } catch (_: Exception) {}

        // WebView version
        try {
            val webViewPackage = WebView.getCurrentWebViewPackage()
            info.put("webview_version", webViewPackage?.versionName ?: "unknown")
        } catch (_: Exception) {}

        // Network info
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = if (network != null) cm.getNetworkCapabilities(network) else null
            val networkType = when {
                caps == null -> "None"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                else -> "Other"
            }
            info.put("network_type", networkType)
        } catch (_: Exception) {}

        // WiFi info
        try {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            if (wifiInfo != null) {
                val ssid = wifiInfo.ssid?.replace("\"", "")
                if (ssid != null && ssid != "<unknown ssid>") {
                    info.put("wifi_ssid", ssid)
                }
                info.put("wifi_signal_strength", wifiInfo.rssi)
            }
        } catch (_: Exception) {}

        // Storage
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val availableMb = stat.availableBytes / (1024 * 1024)
            val totalMb = stat.totalBytes / (1024 * 1024)
            info.put("storage_available_mb", availableMb)
            info.put("storage_total_mb", totalMb)
        } catch (_: Exception) {}

        // RAM
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            info.put("ram_available_mb", memInfo.availMem / (1024 * 1024))
            info.put("ram_total_mb", memInfo.totalMem / (1024 * 1024))
        } catch (_: Exception) {}

        // Battery
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            // level is -1 or Integer.MIN_VALUE on devices with no battery (e.g. Android TV plugged in)
            if (level in 0..100) {
                info.put("battery_level", level)
                info.put("battery_charging", charging)
            } else {
                info.put("battery_level", null)
                info.put("battery_charging", charging)
            }
        } catch (_: Exception) {}

        // Uptime
        try {
            val appUptime = (SystemClock.elapsedRealtime() - ShulDisplayApp.appStartTime) / 1000
            info.put("app_uptime_seconds", appUptime)
            info.put("device_uptime_seconds", SystemClock.elapsedRealtime() / 1000)
        } catch (_: Exception) {}

        return info
    }
}
