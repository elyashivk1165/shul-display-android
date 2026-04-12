package app.shul.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.i(TAG, "Boot/update received: $action")

        // Reschedule screen alarms FIRST (before launching anything)
        try {
            ScreenScheduleManager.rescheduleAfterBoot(context)
        } catch (e: Exception) {
            Log.e(TAG, "Schedule reschedule failed: ${e.message}")
        }

        // Start DisplayForegroundService (handles heartbeat, realtime, recovery)
        DisplayForegroundService.start(context)

        // Schedule WorkManager command poller
        try {
            val workRequest = PeriodicWorkRequestBuilder<CommandPollingWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "command_poller",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule WorkManager on boot", e)
        }

        // Try to show MainActivity
        launchMainActivity(context)
    }

    private fun launchMainActivity(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        // Strategy 1: Direct launch (works if SYSTEM_ALERT_WINDOW granted OR if app is HOME launcher)
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(intent)
                Log.i(TAG, "Direct activity launch succeeded (SYSTEM_ALERT_WINDOW)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct launch failed: ${e.message}")
            }
        }

        // Strategy 2: Via BootForegroundService (foreground service can attempt launch)
        try {
            context.startForegroundService(
                Intent(context, BootForegroundService::class.java)
            )
            Log.i(TAG, "BootForegroundService started")
        } catch (e: Exception) {
            Log.e(TAG, "BootForegroundService failed: ${e.message}")
        }
    }
}
