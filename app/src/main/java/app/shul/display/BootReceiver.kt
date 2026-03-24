package app.shul.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "Received ${intent.action}, starting BootForegroundService")

                try {
                    val serviceIntent = Intent(context, BootForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start BootForegroundService", e)
                }

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
            }
        }
    }
}
