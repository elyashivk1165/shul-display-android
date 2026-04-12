package app.shul.display

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.util.Calendar

class ScreenScheduleManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenSchedule"
        private const val PREFS_NAME = "screen_schedule_prefs"

        fun setSchedule(context: Context, offTime: String?, onTime: String?, days: List<Int> = (0..6).toList()) {
            if (offTime != null && onTime != null && offTime == onTime) {
                Log.w(TAG, "setSchedule ignored: off_time == on_time ($offTime)")
                return
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("off_time", offTime)
                .putString("on_time", onTime)
                .putBoolean("enabled", offTime != null && onTime != null)
                .putString("schedule_days", if (offTime != null && onTime != null) days.joinToString(",") else null)
                .apply()
            cancelAlarms(context)
            if (offTime != null && onTime != null) {
                val offParts = offTime.split(":").map { it.toIntOrNull() ?: 0 }
                val onParts = onTime.split(":").map { it.toIntOrNull() ?: 0 }
                val offHour = offParts.getOrElse(0) { 0 }.coerceIn(0, 23)
                val offMin = offParts.getOrElse(1) { 0 }.coerceIn(0, 59)
                val onHour = onParts.getOrElse(0) { 0 }.coerceIn(0, 23)
                val onMin = onParts.getOrElse(1) { 0 }.coerceIn(0, 59)

                for (day in days) {
                    scheduleAlarmForDay(context, offHour, offMin, day, "SCREEN_OFF", 1000 + day)
                    scheduleAlarmForDay(context, onHour, onMin, day, "SCREEN_ON", 2000 + day)
                }
                Log.i(TAG, "Schedule set: OFF=$offTime, ON=$onTime, days=$days")
            }
        }

        fun scheduleAlarmForDay(context: Context, hour: Int, minute: Int, day: Int, action: String, requestCode: Int) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, day + 1) // 0→Sunday(1), 1→Monday(2), etc.
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.WEEK_OF_YEAR, 1)
                }
            }

            val intent = Intent(context, ScreenAlarmReceiver::class.java).apply {
                this.action = "app.shul.display.$action"
                putExtra("request_code", requestCode)
            }
            val pi = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // On Android 12+ setAlarmClock requires SCHEDULE_EXACT_ALARM permission.
            // Check at runtime; fall back to setAndAllowWhileIdle if not granted.
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { alarmManager.canScheduleExactAlarms() } catch (_: Exception) { false }
            } else {
                true
            }

            if (canExact) {
                // setAlarmClock fires even in deep Doze — guaranteed delivery
                val showPi = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, showPi), pi)
                Log.i(TAG, "Scheduled via setAlarmClock: $action rc=$requestCode")
            } else {
                // Permission not yet granted — use inexact but Doze-aware fallback
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                Log.w(TAG, "SCHEDULE_EXACT_ALARM not granted — using setAndAllowWhileIdle fallback for $action rc=$requestCode")
            }
            Log.i(TAG, "Alarm scheduled: $action day=$day (calDay=${day+1}) at $hour:$minute rc=$requestCode (${cal.time})")
        }

        private fun cancelAlarms(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            for (i in 0..6) {
                for (base in listOf(1000, 2000)) {
                    val rc = base + i
                    val intent = Intent(context, ScreenAlarmReceiver::class.java).apply {
                        this.action = if (base == 1000) "app.shul.display.SCREEN_OFF" else "app.shul.display.SCREEN_ON"
                        putExtra("request_code", rc)
                    }
                    val pi = PendingIntent.getBroadcast(
                        context, rc, intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    pi?.let { alarmManager.cancel(it) }
                }
            }
        }

        fun getSchedule(context: Context): Triple<String?, String?, List<Int>> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val off = prefs.getString("off_time", null)
            val on = prefs.getString("on_time", null)
            val daysStr = prefs.getString("schedule_days", null)
            val days = daysStr?.split(",")?.mapNotNull { it.toIntOrNull() } ?: (0..6).toList()
            return Triple(off, on, days)
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

        fun lockScreen(context: Context): Boolean {
            // Method 1: Accessibility GLOBAL_ACTION_LOCK_SCREEN
            // On Android TV boxes with HDMI-CEC enabled this triggers proper standby
            // which sends a CEC standby signal to the connected TV.
            val lockedViaA11y = ShulAccessibilityService.lockScreen()
            if (lockedViaA11y) {
                Log.i(TAG, "Locking screen via AccessibilityService (HDMI-CEC standby may follow)")
                return true
            }
            // Method 2: DevicePolicyManager fallback
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, ShulDeviceAdminReceiver::class.java)
                if (dpm.isAdminActive(adminComponent)) {
                    dpm.lockNow()
                    Log.i(TAG, "Locking screen via DevicePolicyManager (fallback)")
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "DPM lockNow failed: ${e.message}")
            }
            // Method 3: No further no-permission methods exist.
            // KEYCODE_SLEEP via dispatchKeyEvent does NOT trigger system screen-off.
            // Screen-off requires either Accessibility Service or Device Admin.
            Log.w(TAG, "lockScreen: all methods exhausted — A11y disabled, DPM not admin. Screen remains on.")
            return false
        }

        fun wakeScreen(context: Context) {
            try {
                // Acquire PowerManager wake lock with ACQUIRE_CAUSES_WAKEUP to turn screen on.
                // Activity launch is handled by ScreenWakeHelper.wakeToApp() via full-screen notification.
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                val wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                    "ShulDisplay:WakeScreen"
                )
                // Acquire with auto-release timeout as safety net
                wakeLock.acquire(5_000L)

                Log.i(TAG, "Screen woken up (WakeLock acquired, auto-releases in 5s)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to wake screen: ${e.message}")
            }
        }

        fun rescheduleAfterBoot(context: Context) {
            if (!isEnabled(context)) return
            val (offTime, onTime, days) = getSchedule(context)
            if (offTime != null && onTime != null) {
                setSchedule(context, offTime, onTime, days)
                Log.i(TAG, "Alarms rescheduled after boot")
            }
        }
    }

    fun rescheduleAlarmNextWeek(requestCode: Int, action: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val offTime = prefs.getString("off_time", null) ?: return
        val onTime = prefs.getString("on_time", null) ?: return

        // Read the scheduled days list — only reschedule if this day is still active
        val daysStr = prefs.getString("schedule_days", null)
        val scheduledDays = daysStr?.split(",")?.mapNotNull { it.toIntOrNull() } ?: (0..6).toList()

        when {
            requestCode in 1000..1006 -> {
                val day = requestCode - 1000
                if (!scheduledDays.contains(day)) {
                    Log.i(TAG, "Day $day no longer in schedule, not rescheduling SCREEN_OFF")
                    return
                }
                val parts = offTime.split(":").map { it.toIntOrNull() ?: 0 }
                scheduleAlarmForDay(context, parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, day, "SCREEN_OFF", requestCode)
            }
            requestCode in 2000..2006 -> {
                val day = requestCode - 2000
                if (!scheduledDays.contains(day)) {
                    Log.i(TAG, "Day $day no longer in schedule, not rescheduling SCREEN_ON")
                    return
                }
                val parts = onTime.split(":").map { it.toIntOrNull() ?: 0 }
                scheduleAlarmForDay(context, parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, day, "SCREEN_ON", requestCode)
            }
        }
    }
}
