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
        return try {
            val deviceId = DeviceUtils.getDeviceId(applicationContext)

            SupabaseClient.updateLastSeen(deviceId)

            val commands = SupabaseClient.getPendingCommands(deviceId)
            Log.d(TAG, "Got ${commands.size} pending commands")

            for (cmd in commands) {
                Log.d(TAG, "Executing command: ${cmd.command}")

                val activity = MainActivity.instance
                when (cmd.command) {
                    "RELOAD" -> {
                        if (activity != null && !activity.isDestroyed && !activity.isFinishing) {
                            activity.reloadWebView()
                        } else {
                            Log.w(TAG, "RELOAD skipped — activity not available")
                        }
                    }
                    "UPDATE_SLUG" -> {
                        val newSlug = cmd.payload["slug"] as? String
                        if (!newSlug.isNullOrBlank()) {
                            applicationContext.getSharedPreferences("shul_display_prefs", Context.MODE_PRIVATE)
                                .edit().putString("slug", newSlug).apply()
                            SupabaseClient.updateDeviceSlug(deviceId, newSlug)
                            if (activity != null && !activity.isDestroyed && !activity.isFinishing) {
                                activity.updateSlug(newSlug)
                            }
                        }
                    }
                }

                SupabaseClient.markCommandExecuted(cmd.id)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Command polling failed", e)
            Result.retry()
        }
    }
}
