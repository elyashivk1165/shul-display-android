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

        // Fallback polling: fetch pending commands in case realtime is disconnected
        try {
            val deviceId = DeviceUtils.getDeviceId(applicationContext)
            val pending = SupabaseClient.getPendingCommands(deviceId)
            if (pending.isNotEmpty()) {
                Log.i(TAG, "Fallback poll found ${pending.size} pending commands")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback command poll failed: ${e.message}")
        }

        return Result.success()
    }
}
