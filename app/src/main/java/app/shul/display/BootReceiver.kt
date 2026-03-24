package app.shul.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
                val prefs = context.getSharedPreferences("shul_display_prefs", Context.MODE_PRIVATE)
                val slug = prefs.getString("slug", "") ?: ""

                val targetActivity = if (slug.isNotBlank()) {
                    Log.d(TAG, "Boot/update detected, launching MainActivity for slug: $slug")
                    MainActivity::class.java
                } else {
                    Log.d(TAG, "Boot/update detected, no slug set — launching SetupActivity")
                    SetupActivity::class.java
                }

                try {
                    val launchIntent = Intent(context, targetActivity).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch activity on boot", e)
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
