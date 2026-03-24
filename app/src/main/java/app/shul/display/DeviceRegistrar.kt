package app.shul.display

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DeviceRegistrar {

    private const val TAG = "DeviceRegistrar"
    private const val REGISTER_URL = "https://shul-display.vercel.app/api/device-register"

    fun register(context: Context, slug: String) {
        Thread {
            try {
                val deviceId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )

                val appVersion = try {
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    pInfo.versionName ?: "unknown"
                } catch (e: PackageManager.NameNotFoundException) {
                    "unknown"
                }

                val payload = JSONObject().apply {
                    put("device_id", deviceId)
                    put("slug", slug)
                    put("app_version", appVersion)
                    put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("android_version", Build.VERSION.RELEASE)
                }

                val url = URL(REGISTER_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                try {
                    connection.outputStream.bufferedWriter().use {
                        it.write(payload.toString())
                    }

                    val responseCode = connection.responseCode
                    Log.d(TAG, "Device registration response: $responseCode")
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Device registration failed", e)
            }
        }.start()
    }
}
