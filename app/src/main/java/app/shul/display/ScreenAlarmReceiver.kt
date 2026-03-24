package app.shul.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ScreenAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestCode = intent.getIntExtra("request_code", -1)
        when (intent.action) {
            "app.shul.display.SCREEN_OFF" -> {
                Log.i("ScreenAlarm", "Schedule: turning screen OFF (rc=$requestCode)")
                ScreenScheduleManager.lockScreen(context)
                // Reschedule for next week same day
                rescheduleAlarm(context, requestCode)
            }
            "app.shul.display.SCREEN_ON" -> {
                Log.i("ScreenAlarm", "Schedule: turning screen ON (rc=$requestCode)")
                ScreenScheduleManager.wakeScreen(context)
                // Reschedule for next week same day
                rescheduleAlarm(context, requestCode)
            }
        }
    }

    private fun rescheduleAlarm(context: Context, requestCode: Int) {
        val (offTime, onTime, _) = ScreenScheduleManager.getSchedule(context)
        if (offTime == null || onTime == null) return

        val offParts = offTime.split(":").map { it.toIntOrNull() ?: 0 }
        val onParts = onTime.split(":").map { it.toIntOrNull() ?: 0 }
        val offHour = offParts.getOrElse(0) { 0 }.coerceIn(0, 23)
        val offMin = offParts.getOrElse(1) { 0 }.coerceIn(0, 59)
        val onHour = onParts.getOrElse(0) { 0 }.coerceIn(0, 23)
        val onMin = onParts.getOrElse(1) { 0 }.coerceIn(0, 59)

        when {
            requestCode in 1000..1006 -> {
                val day = requestCode - 1000
                ScreenScheduleManager.scheduleAlarmForDay(context, offHour, offMin, day, "SCREEN_OFF", requestCode)
            }
            requestCode in 2000..2006 -> {
                val day = requestCode - 2000
                ScreenScheduleManager.scheduleAlarmForDay(context, onHour, onMin, day, "SCREEN_ON", requestCode)
            }
            else -> {
                // Fallback: re-read prefs and reschedule all
                ScreenScheduleManager.rescheduleAfterBoot(context)
            }
        }
    }
}
