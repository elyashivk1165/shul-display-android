package app.shul.display

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class CommandPollerWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CommandPoller"
        private const val COMMANDS_URL = "https://shul-display.vercel.app/api/device-commands"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val deviceId = Settings.Secure.getString(
                applicationContext.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            val url = URL("$COMMANDS_URL?device_id=$deviceId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            try {
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val commands = JSONArray(response)

                    for (i in 0 until commands.length()) {
                        val command = commands.getJSONObject(i)
                        val type = command.optString("type", "")
                        Log.d(TAG, "Received command: $type")

                        when (type) {
                            "RELOAD" -> {
                                MainActivity.instance?.reloadWebView()
                            }
                            "UPDATE_SLUG" -> {
                                val newSlug = command.optString("slug", "")
                                if (newSlug.isNotBlank()) {
                                    applicationContext.getSharedPreferences("shul_display", Context.MODE_PRIVATE)
                                        .edit().putString("slug", newSlug).apply()
                                    MainActivity.instance?.updateSlug(newSlug)
                                }
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Command poll returned HTTP $responseCode")
                }
            } finally {
                connection.disconnect()
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Command poll failed", e)
            Result.retry()
        }
    }
}
