package app.shul.display

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

object ScreenScheduleManager {
    private const val TAG = "ScreenSchedule"
    private const val ACTION_SCREEN_OFF = "app.shul.display.SCREEN_OFF"
    private const val ACTION_SCREEN_ON = "app.shul.display.SCREEN_ON"
    private const val PREFS_NAME = "screen_schedule_prefs"

    fun setSchedule(context: Context, offTime: String?, onTime: String?) {
        // Guard: off_time and on_time must differ; identical times would fire simultaneously
        if (offTime != null && onTime != null && offTime == onTime) {
            Log.w(TAG, "setSchedule ignored: off_time == on_time ($offTime)")
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("off_time", offTime)
            .putString("on_time", onTime)
            .putBoolean("enabled", offTime != null && onTime != null)
            .apply()
        cancelAlarms(context)
        if (offTime != null && onTime != null) {
            scheduleAlarm(context, offTime, ACTION_SCREEN_OFF)
            scheduleAlarm(context, onTime, ACTION_SCREEN_ON)
            Log.i(TAG, "Schedule set: OFF=$offTime, ON=$onTime")
        }
    }

    fun getSchedule(context: Context): Pair<String?, String?> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(prefs.getString("off_time", null), prefs.getString("on_time", null))
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, ShulDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    fun lockScreen(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, ShulDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                Log.i(TAG, "Locking screen via DevicePolicyManager")
                dpm.lockNow()
            } else {
                Log.w(TAG, "Device admin not active, cannot lock screen")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock screen", e)
        }
    }

    fun wakeScreen(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
                "shul-display:wake-screen"
            )
            wakeLock.acquire(3_000)
            wakeLock.release()
            Log.i(TAG, "Screen woken up")

            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake screen", e)
        }
    }

    private fun scheduleAlarm(context: Context, time: String, action: String) {
        val parts = time.split(":").map { it.toIntOrNull() ?: -1 }
        val hour = parts.getOrElse(0) { -1 }
        val minute = parts.getOrElse(1) { -1 }
        if (hour !in 0..23 || minute !in 0..59) {
            Log.e(TAG, "Invalid time: $time")
            return
        }

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val intent = Intent(context, ScreenAlarmReceiver::class.java).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        }
        Log.i(TAG, "Alarm scheduled: $action at $time (${calendar.time})")
    }

    private fun cancelAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (action in listOf(ACTION_SCREEN_OFF, ACTION_SCREEN_ON)) {
            val intent = Intent(context, ScreenAlarmReceiver::class.java).apply { this.action = action }
            val pi = PendingIntent.getBroadcast(
                context, action.hashCode(), intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pi?.let { alarmManager.cancel(it) }
        }
    }

    fun rescheduleAfterBoot(context: Context) {
        if (!isEnabled(context)) return
        val (offTime, onTime) = getSchedule(context)
        if (offTime != null && onTime != null) {
            setSchedule(context, offTime, onTime)
            Log.i(TAG, "Alarms rescheduled after boot")
        }
    }
}
