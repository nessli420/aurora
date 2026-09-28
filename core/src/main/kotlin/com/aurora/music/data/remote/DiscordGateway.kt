package com.aurora.music.data.remote

import com.aurora.music.util.AppLog
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// user-token gateway presence since android has no local discord ipc
class DiscordGateway(
    private val onUsername: (String) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val gatewayUrl: String = "wss://gateway.discord.gg/?v=10&encoding=json",
) {
    private val http = OkHttpClient.Builder().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ready = false
    private var generation = 0L
    private var presenceJob: Job? = null
    private var reconnectJob: Job? = null
    private val presenceThrottle = PresenceThrottle<JsonObject>()
    @Volatile private var heartbeatAcknowledged = true
    private var ws: WebSocket? = null
    private var token: String = ""
    private var seq: Int? = null
    private var heartbeatJob: Job? = null
    @Volatile private var closedByUser = false

    @Synchronized fun connect(token: String, activity: JsonObject?) {
        if (token.isBlank()) return
        disconnect()
        this.token = token
        presenceThrottle.offer(activity)
        closedByUser = false
        open()
    }

    @Synchronized fun updateActivity(activity: JsonObject?) {
        presenceThrottle.offer(activity)
        if (ready) schedulePresence()
    }

    @Synchronized fun disconnect() {
        generation++; ready = false
        presenceJob?.cancel(); reconnectJob?.cancel()
        closedByUser = true
        heartbeatJob?.cancel()
        runCatching { ws?.close(1000, "bye") }
        ws = null
        onConnected(false)
    }

    @Synchronized private fun open() {
        val epoch = ++generation
        ready = false; seq = null
        ws?.cancel()
        val req = Request.Builder().url(gatewayUrl).build()
        ws = http.newWebSocket(req, Listener(epoch))
    }

    private fun identify() {
        val props = JsonObject().apply {
            addProperty("os", "Android")
            addProperty("browser", "Discord Android")
            addProperty("device", "Aurora")
        }
        val d = JsonObject().apply {
            addProperty("token", token)
            addProperty("capabilities", 16381)
            add("properties", props)
            addProperty("compress", false)
            add("presence", presence())
        }
        ws?.send(op(2, d))
    }

    private fun presence(): JsonObject {
        val activities = JsonArray()
        presenceThrottle.latest?.let { activities.add(it) }
        return JsonObject().apply {
            addProperty("status", "online")
            addProperty("since", 0)
            add("activities", activities)
            addProperty("afk", false)
        }
    }

    private fun op(code: Int, d: JsonElement): String = JsonObject().apply {
        addProperty("op", code)
        add("d", d)
    }.toString()

    private fun heartbeat(): String = op(1, seq?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)

    @Synchronized private fun schedulePresence() {
        if (!ready || presenceJob?.isActive == true) return
        presenceJob = scope.launch {
            delay(synchronized(this@DiscordGateway) { presenceThrottle.delayMs(System.nanoTime() / 1_000_000) })
            synchronized(this@DiscordGateway) {
                if (ready && !closedByUser) {
                    if (ws?.send(op(3, presence())) == true) {
                        presenceThrottle.sent(System.nanoTime() / 1_000_000)
                    }
                }
                presenceJob = null
            }
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatAcknowledged = true
        heartbeatJob = scope.launch {
            delay((intervalMs * 0.5).toLong())
            while (isActive) {
                if (!heartbeatAcknowledged) { reconnect(); return@launch }
                heartbeatAcknowledged = false
                runCatching { ws?.send(heartbeat()) }
                delay(intervalMs)
            }
        }
    }

    @Synchronized private fun reconnect() {
        if (closedByUser || reconnectJob?.isActive == true) return
        ready = false; generation++
        heartbeatJob?.cancel(); presenceJob?.cancel(); presenceJob = null
        ws?.cancel(); ws = null
        onConnected(false)
        reconnectJob = scope.launch {
            delay(5000)
            synchronized(this@DiscordGateway) { reconnectJob = null; if (!closedByUser) open() }
        }
    }

    private inner class Listener(private val epoch: Long) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@DiscordGateway) {
            if (epoch != generation || closedByUser) return@synchronized
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return@synchronized
            json.number("s")?.let { seq = it.toInt() }
            when (json.number("op")?.toInt() ?: -1) {
                10 -> {
                    val interval = json.getAsJsonObject("d").get("heartbeat_interval").asLong
                    startHeartbeat(interval)
                    identify()
                    AppLog.d(TAG, "HELLO interval=$interval, identifying")
                }
                0 -> if (json.text("t") == "READY") {
                    val user = json.getAsJsonObject("d").get("user")?.takeIf { it.isJsonObject }?.asJsonObject
                    val name = user?.text("global_name").orEmpty().ifBlank { user?.text("username").orEmpty() }
                    AppLog.d(TAG, "READY as $name")
                    onUsername(name)
                    onConnected(true)
                    ready = true
                    schedulePresence()
                }
                1 -> runCatching { ws?.send(heartbeat()) }
                11 -> heartbeatAcknowledged = true
                7, 9 -> { AppLog.d(TAG, "reconnect requested op=${json.number("op")}"); reconnect() }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@DiscordGateway) {
            if (epoch != generation || closedByUser) return@synchronized
            AppLog.d(TAG, "closed $code $reason")
            reconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(this@DiscordGateway) {
            if (epoch != generation || closedByUser) return@synchronized
            AppLog.d(TAG, "failure ${t.message}")
            reconnect()
        }
    }

    private companion object { const val TAG = "DiscordRpc" }
}

private fun JsonObject.number(key: String): Number? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asNumber

private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
