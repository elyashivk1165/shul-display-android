package app.shul.display

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service — Reliable boot auto-start.
 *
 * How it works:
 *  - Android automatically starts enabled Accessibility Services on boot.
 *  - Running in the system's accessibility service process, we CAN call startActivity()
 *    without any background-launch restrictions.
 *  - When onServiceConnected() fires (on boot or after re-enable), we bring MainActivity
 *    to the front.
 *
 * User must enable once: Settings → Accessibility → Shul Display → Enable.
 * SetupActivity guides the user through this flow on first launch.
 */
class ShulAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ShulA11y"

        // Singleton reference so other components can call lockScreen()
        @Volatile var instance: ShulAccessibilityService? = null

        /**
         * Locks the screen via GLOBAL_ACTION_LOCK_SCREEN.
         * On many Android TV boxes this triggers a proper standby that sends
         * HDMI-CEC standby to the connected TV — unlike dpm.lockNow().
         * Returns true if the action was performed, false if service not running.
         */
        fun lockScreen(): Boolean {
            val svc = instance ?: return false
            return try {
                svc.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } catch (e: Exception) {
                Log.e(TAG, "lockScreen via a11y failed: ${e.message}")
                false
            }
        }

        /**
         * Returns true if this service is currently enabled in accessibility settings.
         */
        fun isEnabled(context: Context): Boolean {
            val component = "${context.packageName}/${ShulAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(":").any { it.trim().equals(component, ignoreCase = true) }
        }

        /**
         * Opens the system Accessibility Settings screen.
         */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open accessibility settings: ${e.message}")
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Service connected — ready for lockScreen()")
        // Do NOT launch any activity here. Android TV/Android 13+ disables accessibility
        // services that immediately launch activities on connection (seen as malicious).
        // Boot-time launch is handled by BootReceiver + DisplayForegroundService.
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }
}
