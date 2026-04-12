package app.shul.display

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
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

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 10_000
    private const val MAX_RETRIES = 2
    private const val STALE_COMMAND_SECONDS = 3600L // 1 hour — allows catch-up after scheduled off periods

    // ── HTTP helper ─────────────────────────────────────────────────────────

    private fun baseHeaders(): Map<String, String> = mapOf(
        "apikey" to ANON_KEY,
        "Authorization" to "Bearer $ANON_KEY",
        "Content-Type" to "application/json"
    )

    /**
     * Execute an HTTP request with retry logic and proper connection cleanup.
     * Returns the response code and body (if successful).
     */
    private fun executeRequest(
        url: String,
        method: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        retries: Int = MAX_RETRIES
    ): Pair<Int, String?> {
        var lastException: Exception? = null
        repeat(retries + 1) { attempt ->
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    doOutput = body != null
                    baseHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }

                if (body != null) {
                    conn.outputStream.bufferedWriter().use { it.write(body) }
                }

                val code = conn.responseCode
                val responseBody = if (code == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use(BufferedReader::readText)
                } else null

                // Don't retry on client errors (4xx) except 429
                if (code in 400..499 && code != 429) {
                    return Pair(code, responseBody)
                }
                // Retry on 429 (rate limited) or 5xx (server error)
                if (code == 429 || code >= 500) {
                    Log.w(TAG, "Request $method $url returned $code, attempt ${attempt + 1}")
                    if (attempt < retries) {
                        Thread.sleep((attempt + 1) * 1000L) // simple backoff
                        return@repeat
                    }
                }
                return Pair(code, responseBody)
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Request $method $url failed, attempt ${attempt + 1}: ${e.message}")
                if (attempt < retries) {
                    Thread.sleep((attempt + 1) * 1000L)
                }
            } finally {
                conn?.disconnect()
            }
        }
        throw lastException ?: IOException("Request failed after ${retries + 1} attempts")
    }

    // ── Device operations ────────────────────────────────────────────────────

    suspend fun registerDevice(deviceId: String, slug: String, appVersion: String) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("slug", slug)
                    put("app_version", appVersion)
                }
                val (code, _) = executeRequest(
                    url = "$SUPABASE_URL/rest/v1/devices",
                    method = "POST",
                    body = body.toString(),
                    extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates")
                )
                Log.d(TAG, "registerDevice response: $code")
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
                val (code, _) = executeRequest(
                    url = "$SUPABASE_URL/rest/v1/devices?device_id=eq.$deviceId",
                    method = "PATCH",
                    body = body.toString()
                )
                Log.d(TAG, "updateLastSeen response: $code")
            } catch (e: Exception) {
                Log.e(TAG, "updateLastSeen failed", e)
            }
        }
    }

    suspend fun updateDeviceSlug(deviceId: String, newSlug: String) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply { put("slug", newSlug) }
                val (code, _) = executeRequest(
                    url = "$SUPABASE_URL/rest/v1/devices?device_id=eq.$deviceId",
                    method = "PATCH",
                    body = body.toString()
                )
                Log.d(TAG, "updateDeviceSlug response: $code")
            } catch (e: Exception) {
                Log.e(TAG, "updateDeviceSlug failed", e)
            }
        }
    }

    // ── Command operations ───────────────────────────────────────────────────

    suspend fun getPendingCommands(deviceId: String): List<DeviceCommand> {
        return withContext(Dispatchers.IO) {
            try {
                val cutoff = Instant.now().minusSeconds(STALE_COMMAND_SECONDS).toString()
                val (code, responseBody) = executeRequest(
                    url = "$SUPABASE_URL/rest/v1/device_commands" +
                        "?device_id=eq.$deviceId&executed_at=is.null&order=created_at.asc" +
                        "&created_at=gte.$cutoff",
                    method = "GET"
                )
                when (code) {
                    HttpURLConnection.HTTP_OK -> parseCommands(responseBody ?: "[]")
                    429 -> throw IOException("Rate limited (429)")
                    HttpURLConnection.HTTP_UNAUTHORIZED -> throw IOException("Unauthorized (401)")
                    else -> throw IOException("HTTP $code")
                }
            } catch (e: Exception) {
                Log.e(TAG, "getPendingCommands failed", e)
                throw e
            }
        }
    }

    /**
     * Mark command as delivered (acknowledged by device, about to execute).
     * Prevents re-execution if reportCommandResult fails later.
     */
    suspend fun acknowledgeCommand(commandId: String) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("executed_at", nowIso())
                    put("result", "delivered")
                }
                val (code, _) = executeRequest(
                    url = "$SUPABASE_URL/rest/v1/device_commands?id=eq.$commandId",
                    method = "PATCH",
                    body = body.toString()
                )
                Log.d(TAG, "acknowledgeCommand response: $code")
            } catch (e: Exception) {
                Log.e(TAG, "acknowledgeCommand failed", e)
            }
        }
    }

    /**
     * Write final result and result_message back to the device_commands row
     * so the admin panel can see the outcome.
     */
    suspend fun reportCommandResult(commandId: String, result: String, message: String = "") {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("executed_at", nowIso())
                    put("result", result)
                    if (message.isNotBlank()) put("result_message", message)
                }
                val (code, _) = executeRequest(
                    url = "$SUPABASE_URL/rest/v1/device_commands?id=eq.$commandId",
                    method = "PATCH",
                    body = body.toString()
                )
                Log.d(TAG, "reportCommandResult response: $code")
            } catch (e: Exception) {
                Log.e(TAG, "reportCommandResult failed", e)
            }
        }
    }

    // ── Device logging ─────────────────────────────────────────────────────

    /**
     * Send a log entry to the device_logs table for remote monitoring.
     * Levels: INFO, WARN, ERROR, CRASH, PING
     */
    suspend fun sendLog(
        deviceId: String,
        level: String,
        message: String,
        stacktrace: String? = null,
        extra: JSONObject? = null,
        appVersion: String? = null
    ) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("level", level)
                    put("message", message)
                    if (stacktrace != null) put("stacktrace", stacktrace)
                    if (extra != null) put("extra", extra)
                    if (appVersion != null) put("app_version", appVersion)
                }
                executeRequest(
                    url = "$SUPABASE_URL/rest/v1/device_logs",
                    method = "POST",
                    body = body.toString(),
                    retries = 1 // Don't retry too aggressively for logs
                )
            } catch (e: Exception) {
                Log.w(TAG, "sendLog failed: ${e.message}")
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
