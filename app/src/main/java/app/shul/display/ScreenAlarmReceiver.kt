package app.shul.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

class ScreenAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenAlarmReceiver"
        private const val WAKE_LOCK_TAG = "ShulDisplay:AlarmWake"

        // Static wake lock so it survives across async operations
        @Volatile private var wakeLock: PowerManager.WakeLock? = null

        fun acquireWakeLock(context: Context) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock?.let { if (it.isHeld) it.release() }
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or   // more reliable than FULL_WAKE_LOCK on Android TV
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG
            ).also { it.acquire(60_000L) } // Hold for 60 seconds max
        }

        fun releaseWakeLock() {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync() extends execution window beyond the default ~10 seconds
        val pendingResult = goAsync()

        // FIRST: acquire wake lock to keep CPU awake
        acquireWakeLock(context)

        val action = intent.action ?: run { pendingResult.finish(); return }
        val requestCode = intent.getIntExtra("request_code", -1)

        try {
            when (action) {
                "app.shul.display.SCREEN_ON" -> {
                    Log.i(TAG, "Schedule: turning screen ON (rc=$requestCode)")
                    try {
                        handleScreenOn(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling SCREEN_ON: ${e.message}")
                        releaseWakeLock()
                    }
                }
                "app.shul.display.SCREEN_OFF" -> {
                    Log.i(TAG, "Schedule: turning screen OFF (rc=$requestCode)")
                    handleScreenOff(context)
                    releaseWakeLock()
                }
            }

            // Reschedule for next week
            val manager = ScreenScheduleManager(context)
            manager.rescheduleAlarmNextWeek(requestCode, action)
        } finally {
            pendingResult.finish()
        }
    }

    private fun handleScreenOn(context: Context) {
        // Primary path: full-screen notification with CATEGORY_ALARM privilege.
        // This bypasses background activity launch restrictions on Android 10+.
        ScreenWakeHelper.wakeToApp(context)

        // Acquire WakeLock via ScreenScheduleManager (without redundant startActivity).
        // WakeLock stays acquired — released in MainActivity.applyWakeScreenFlags()
        ScreenScheduleManager.wakeScreen(context)
        Log.i(TAG, "SCREEN_ON: wakeToApp + wakeScreen called (single activity launch path)")
    }

    private fun handleScreenOff(context: Context) {
        Log.i(TAG, "Screen-off alarm fired — delegating to ScreenScheduleManager")
        ScreenScheduleManager.lockScreen(context)
    }
}
