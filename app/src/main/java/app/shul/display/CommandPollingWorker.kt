package app.shul.display

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CommandPollingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CommandPollingWorker"
    }

    override suspend fun doWork(): Result {
        // Safety net: ensure DisplayForegroundService is running
        if (!isServiceRunning(applicationContext, DisplayForegroundService::class.java)) {
            Log.w(TAG, "DisplayForegroundService not running, restarting...")
            DisplayForegroundService.start(applicationContext)
        }
        return Result.success()
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }
}
