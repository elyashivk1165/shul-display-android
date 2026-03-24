package app.shul.display

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ShulDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "DeviceAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin disabled")
    }
}
