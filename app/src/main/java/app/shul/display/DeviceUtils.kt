package app.shul.display

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
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
}
