package app.shul.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val prefs = context.getSharedPreferences("shul_display_prefs", Context.MODE_PRIVATE)
                val slug = prefs.getString("slug", null)
                if (!slug.isNullOrBlank()) {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(launchIntent)

                    val workRequest = PeriodicWorkRequestBuilder<CommandPollingWorker>(
                        15, TimeUnit.MINUTES
                    ).build()
                    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                        "command_poller",
                        ExistingPeriodicWorkPolicy.KEEP,
                        workRequest
                    )
                }
            }
        }
    }
}
