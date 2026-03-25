package app.shul.display

import android.app.admin.DevicePolicyManager
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
                    handleScreenOn(context)
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
        // Method 1: Direct startActivity — BroadcastReceivers triggered by setAlarmClock()
        // are granted a special background-activity-launch exception on Android 10+.
        try {
            val activityIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra("wake_screen", true)
            }
            context.startActivity(activityIntent)
            Log.i(TAG, "SCREEN_ON: started MainActivity directly")
        } catch (e: Exception) {
            Log.w(TAG, "SCREEN_ON: direct startActivity failed: ${e.message}")
        }

        // Method 2: Full-screen notification (backup — also wakes screen via CATEGORY_ALARM)
        ScreenWakeHelper.wakeToApp(context)
        // WakeLock stays acquired — released in MainActivity.applyWakeScreenFlags()
    }

    private fun handleScreenOff(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try {
            dpm.lockNow()
        } catch (e: Exception) {
            Log.e(TAG, "lockNow failed: ${e.message}")
        }
    }
}
