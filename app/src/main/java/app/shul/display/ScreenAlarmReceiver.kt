package app.shul.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ScreenAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "app.shul.display.SCREEN_OFF" -> {
                Log.i("ScreenAlarm", "Schedule: turning screen OFF")
                ScreenScheduleManager.lockScreen(context)
                val (offTime, onTime) = ScreenScheduleManager.getSchedule(context)
                if (offTime != null && onTime != null) {
                    ScreenScheduleManager.setSchedule(context, offTime, onTime)
                }
            }
            "app.shul.display.SCREEN_ON" -> {
                Log.i("ScreenAlarm", "Schedule: turning screen ON")
                ScreenScheduleManager.wakeScreen(context)
                val (offTime, onTime) = ScreenScheduleManager.getSchedule(context)
                if (offTime != null && onTime != null) {
                    ScreenScheduleManager.setSchedule(context, offTime, onTime)
                }
            }
        }
    }
}
