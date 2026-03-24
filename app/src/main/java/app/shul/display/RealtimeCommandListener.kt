package app.shul.display

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RealtimeCommandListener(
    private val deviceId: String,
    private val supabaseUrl: String,
    private val supabaseKey: String,
    private val onCommand: (DeviceCommand) -> Unit
) {
    companion object {
        private const val TAG = "RealtimeListener"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for WebSocket
        .build()
    private val isConnected = AtomicBoolean(false)
    private var reconnectDelayMs = 2_000L
    private val maxReconnectDelay = 60_000L
    private var heartbeatJob: Job? = null
    private val ref = AtomicInteger(1)

    fun start() {
        scope.launch { connect() }
    }

    fun stop() {
        heartbeatJob?.cancel()
        webSocket?.close(1000, "Service stopped")
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    fun isConnected(): Boolean = isConnected.get()

    private suspend fun connect() {
        val projectRef = supabaseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore(".")

        val wsUrl = "wss://$projectRef.supabase.co/realtime/v1/websocket?apikey=$supabaseKey&vsn=1.0.0"

        Log.d(TAG, "Connecting to Supabase Realtime...")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected.set(true)
                reconnectDelayMs = 2_000L
                joinChannel(ws)
                startHeartbeat(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: $reason")
                isConnected.set(false)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected.set(false)
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $reason")
                isConnected.set(false)
                if (code != 1000) scheduleReconnect()
            }
        })
    }

    private fun joinChannel(ws: WebSocket) {
        val payload = JSONObject().apply {
            put("config", JSONObject().apply {
                put("broadcast", JSONObject().apply { put("self", false) })
                put("presence", JSONObject().apply { put("key", "") })
                put("postgres_changes", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("event", "INSERT")
                        put("schema", "public")
                        put("table", "device_commands")
                        put("filter", "device_id=eq.$deviceId")
                    })
                })
            })
        }

        val msg = JSONObject().apply {
            put("topic", "realtime:device_commands_$deviceId")
            put("event", "phx_join")
            put("payload", payload)
            put("ref", ref.getAndIncrement().toString())
        }
        ws.send(msg.toString())
        Log.d(TAG, "Joined Realtime channel for device: $deviceId")
    }

    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isConnected.get()) {
                delay(HEARTBEAT_INTERVAL_MS)
                val heartbeat = JSONObject().apply {
                    put("topic", "phoenix")
                    put("event", "heartbeat")
                    put("payload", JSONObject())
                    put("ref", ref.getAndIncrement().toString())
                }
                try {
                    ws.send(heartbeat.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat send failed: ${e.message}")
                    isConnected.set(false)
                }
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            val event = msg.optString("event")
            val payload = msg.optJSONObject("payload") ?: return

            when (event) {
                "postgres_changes" -> {
                    val record = payload.optJSONObject("record") ?: return
                    val commandId = record.optString("id")
                    val command = record.optString("command")
                    if (command.isBlank()) return

                    // Parse payload map from the record's payload field
                    val payloadMap = mutableMapOf<String, Any>()
                    val payloadJson = record.optJSONObject("payload")
                    if (payloadJson != null) {
                        for (key in payloadJson.keys()) {
                            payloadMap[key] = payloadJson.get(key)
                        }
                    }

                    Log.d(TAG, "Realtime command received: $command (id=$commandId)")
                    onCommand(DeviceCommand(id = commandId, command = command, payload = payloadMap))
                }
                "phx_reply" -> {
                    val status = payload.optString("status")
                    Log.d(TAG, "Channel reply: $status")
                }
                "system" -> {
                    Log.d(TAG, "System message: ${payload.optString("message")}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Realtime message: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        scope.launch {
            Log.d(TAG, "Reconnecting in ${reconnectDelayMs}ms...")
            delay(reconnectDelayMs)
            reconnectDelayMs = minOf(reconnectDelayMs * 2, maxReconnectDelay)
            connect()
        }
    }
}
