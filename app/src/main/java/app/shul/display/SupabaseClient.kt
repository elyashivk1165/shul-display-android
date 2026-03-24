package app.shul.display

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class DeviceCommand(
    val id: String,
    val command: String,
    val payload: Map<String, Any>
)

object SupabaseClient {

    private const val TAG = "SupabaseClient"
    const val SUPABASE_URL = "https://wifpjexmcbkgfnjmmpst.supabase.co"
    private val ANON_KEY: String get() = BuildConfig.SUPABASE_ANON_KEY

    private fun headers(): Map<String, String> = mapOf(
        "apikey" to ANON_KEY,
        "Authorization" to "Bearer $ANON_KEY",
        "Content-Type" to "application/json"
    )

    suspend fun registerDevice(deviceId: String, slug: String, appVersion: String) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("slug", slug)
                    put("app_version", appVersion)
                }

                val url = URL("$SUPABASE_URL/rest/v1/devices")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    headers().forEach { (k, v) -> setRequestProperty(k, v) }
                    setRequestProperty("Prefer", "resolution=merge-duplicates")
                }

                conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
                val code = conn.responseCode
                Log.d(TAG, "registerDevice response: $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "registerDevice failed", e)
            }
        }
    }

    suspend fun updateLastSeen(deviceId: String, deviceInfo: JSONObject? = null) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("last_seen", nowIso())
                    if (deviceInfo != null) {
                        put("device_info", deviceInfo)
                    }
                }

                val url = URL("$SUPABASE_URL/rest/v1/devices?device_id=eq.$deviceId")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PATCH"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    headers().forEach { (k, v) -> setRequestProperty(k, v) }
                }

                conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
                val code = conn.responseCode
                Log.d(TAG, "updateLastSeen response: $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "updateLastSeen failed", e)
            }
        }
    }

    suspend fun getPendingCommands(deviceId: String): List<DeviceCommand> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "$SUPABASE_URL/rest/v1/device_commands" +
                        "?device_id=eq.$deviceId&executed_at=is.null&order=created_at.asc"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    headers().forEach { (k, v) -> setRequestProperty(k, v) }
                }

                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    conn.disconnect()
                    parseCommands(response)
                } else {
                    Log.w(TAG, "getPendingCommands returned $code")
                    conn.disconnect()
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "getPendingCommands failed", e)
                emptyList()
            }
        }
    }

    suspend fun markCommandExecuted(commandId: String) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("executed_at", nowIso())
                }

                val url = URL("$SUPABASE_URL/rest/v1/device_commands?id=eq.$commandId")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PATCH"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    headers().forEach { (k, v) -> setRequestProperty(k, v) }
                }

                conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
                val code = conn.responseCode
                Log.d(TAG, "markCommandExecuted response: $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "markCommandExecuted failed", e)
            }
        }
    }

    suspend fun updateDeviceSlug(deviceId: String, newSlug: String) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("slug", newSlug)
                }

                val url = URL("$SUPABASE_URL/rest/v1/devices?device_id=eq.$deviceId")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PATCH"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    headers().forEach { (k, v) -> setRequestProperty(k, v) }
                }

                conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
                val code = conn.responseCode
                Log.d(TAG, "updateDeviceSlug response: $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "updateDeviceSlug failed", e)
            }
        }
    }

    private fun parseCommands(json: String): List<DeviceCommand> {
        val arr = JSONArray(json)
        val result = mutableListOf<DeviceCommand>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val payload = mutableMapOf<String, Any>()
            val payloadJson = obj.optJSONObject("payload")
            if (payloadJson != null) {
                for (key in payloadJson.keys()) {
                    payload[key] = payloadJson.get(key)
                }
            }
            result.add(
                DeviceCommand(
                    id = obj.getString("id"),
                    command = obj.getString("command"),
                    payload = payload
                )
            )
        }
        return result
    }

    private fun nowIso(): String {
        return Instant.now()
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
