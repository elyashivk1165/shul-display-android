package app.shul.display

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-disk fallback for `device_logs` deliveries. When the network is down
 * or Supabase is unreachable, logs are appended to a JSONL file in the
 * app's private storage. The buffer is flushed:
 *   • On every app startup, before any new logging happens
 *   • From the heartbeat loop, opportunistically when network is back
 *
 * Each file is one JSON object per line (`{ device_id, level, message,
 * stacktrace?, extra?, app_version?, ts }`) with `ts` added so we can
 * preserve the time when the original event happened — Supabase's own
 * `created_at` is when the row finally arrives, which can be hours later
 * after a recovery.
 *
 * File is capped at 256 KB. When the cap is reached, oldest lines are
 * dropped on next append (FIFO ring) — better to keep recent diagnostics
 * than to lose all logs to disk pressure.
 */
object LogBuffer {
    private const val TAG = "LogBuffer"
    private const val FILE_NAME = "pending_logs.jsonl"
    private const val MAX_BYTES = 256 * 1024

    private fun file(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /**
     * Append a log entry to the disk buffer. Cheap; no network, no parsing
     * other than JSON serialization. Safe to call from a uncaught-exception
     * handler.
     */
    fun append(
        context: Context,
        deviceId: String,
        level: String,
        message: String,
        stacktrace: String? = null,
        extra: JSONObject? = null,
        appVersion: String? = null,
    ) {
        try {
            val obj = JSONObject().apply {
                put("device_id", deviceId)
                put("level", level)
                put("message", message)
                if (stacktrace != null) put("stacktrace", stacktrace)
                if (extra != null) put("extra", extra)
                if (appVersion != null) put("app_version", appVersion)
                put("ts", System.currentTimeMillis())
            }
            val f = file(context)

            // Trim oldest if we'd exceed the cap.
            if (f.exists() && f.length() > MAX_BYTES) {
                trimOldestHalf(f)
            }

            synchronized(this) {
                f.appendText(obj.toString() + "\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append to log buffer: ${e.message}")
        }
    }

    /**
     * Read the buffer and try to send each line to Supabase. Lines that
     * succeed are removed; lines that fail stay (we'll retry next flush).
     * Stops on the first send failure to avoid hammering an unreachable
     * endpoint — the next periodic flush will pick up where we left off.
     *
     * Returns the count of lines successfully sent.
     */
    suspend fun flush(context: Context): Int {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) return 0

        val lines = synchronized(this) {
            try { f.readLines().filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }
        }
        if (lines.isEmpty()) {
            try { f.delete() } catch (_: Exception) {}
            return 0
        }

        val supabaseUrl = SupabaseClient.SUPABASE_URL
        val anonKey = BuildConfig.SUPABASE_ANON_KEY

        val unsent = mutableListOf<String>()
        var sent = 0
        var stopOnError = false
        for (line in lines) {
            if (stopOnError) {
                unsent.add(line)
                continue
            }
            try {
                // Strip our `ts` field if present — the server doesn't have a
                // matching column for it. We use it locally only.
                val sendBody = try {
                    val parsed = JSONObject(line)
                    parsed.remove("ts")
                    parsed.toString()
                } catch (_: Exception) { line }

                val ok = postOnce("$supabaseUrl/rest/v1/device_logs", sendBody, anonKey)
                if (ok) sent++ else { unsent.add(line); stopOnError = true }
            } catch (_: Exception) {
                unsent.add(line)
                stopOnError = true
            }
        }

        synchronized(this) {
            try {
                if (unsent.isEmpty()) f.delete()
                else f.writeText(unsent.joinToString("\n", postfix = "\n"))
            } catch (_: Exception) {}
        }
        if (sent > 0) Log.i(TAG, "Flushed $sent buffered log line(s); ${unsent.size} remain")
        return sent
    }

    private fun postOnce(url: String, body: String, anonKey: String): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer $anonKey")
            }
            conn.outputStream.bufferedWriter().use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /** Drop the oldest half of the file. Cheap, keeps recent entries. */
    private fun trimOldestHalf(f: File) {
        try {
            synchronized(this) {
                val all = f.readLines()
                val keep = all.takeLast(all.size / 2)
                f.writeText(keep.joinToString("\n", postfix = if (keep.isEmpty()) "" else "\n"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trim log buffer: ${e.message}")
        }
    }
}
