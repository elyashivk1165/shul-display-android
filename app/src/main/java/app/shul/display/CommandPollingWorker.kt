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
        // Safety net: ensure DisplayForegroundService is running.
        // getRunningServices() is deprecated and always returns empty on Android 11+,
        // so we always call start() directly — START_STICKY handles duplicate starts gracefully.
        Log.d(TAG, "Ensuring DisplayForegroundService is running...")
        DisplayForegroundService.start(applicationContext)
        return Result.success()
    }
}
