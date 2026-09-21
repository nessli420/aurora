package com.aurora.music.data.remote

import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject

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
    private val presenceThrottle = PresenceThrottle<JSONObject>()
    @Volatile private var heartbeatAcknowledged = true
    private var ws: WebSocket? = null
    private var token: String = ""
    private var seq: Int? = null
    private var heartbeatJob: Job? = null
    @Volatile private var closedByUser = false

    @Synchronized fun connect(token: String, activity: JSONObject?) {
        if (token.isBlank()) return
        disconnect()
        this.token = token
        presenceThrottle.offer(activity)
        closedByUser = false
        open()
    }

    @Synchronized fun updateActivity(activity: JSONObject?) {
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
        val props = JSONObject()
            .put("os", "Android")
            .put("browser", "Discord Android")
            .put("device", "Aurora")
        val d = JSONObject()
            .put("token", token)
            .put("capabilities", 16381)
            .put("properties", props)
            .put("compress", false)
            .put("presence", presence())
        ws?.send(JSONObject().put("op", 2).put("d", d).toString())
    }

    private fun presence(): JSONObject {
        val activities = JSONArray()
        presenceThrottle.latest?.let { activities.put(it) }
        return JSONObject()
            .put("status", "online")
            .put("since", 0)
            .put("activities", activities)
            .put("afk", false)
    }

    @Synchronized private fun schedulePresence() {
        if (!ready || presenceJob?.isActive == true) return
        presenceJob = scope.launch {
            delay(synchronized(this@DiscordGateway) { presenceThrottle.delayMs(android.os.SystemClock.elapsedRealtime()) })
            synchronized(this@DiscordGateway) {
                if (ready && !closedByUser) {
                    if (ws?.send(JSONObject().put("op", 3).put("d", presence()).toString()) == true) {
                        presenceThrottle.sent(android.os.SystemClock.elapsedRealtime())
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
                runCatching { ws?.send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL).toString()) }
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
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return@synchronized
            if (!json.isNull("s")) seq = json.optInt("s")
            when (json.optInt("op", -1)) {
                10 -> {
                    val interval = json.getJSONObject("d").getLong("heartbeat_interval")
                    startHeartbeat(interval)
                    identify()
                    Log.d(TAG, "HELLO interval=$interval, identifying")
                }
                0 -> if (json.optString("t") == "READY") {
                    val user = json.getJSONObject("d").optJSONObject("user")
                    val name = user?.optString("global_name").orEmpty().ifBlank { user?.optString("username").orEmpty() }
                    Log.d(TAG, "READY as $name")
                    onUsername(name)
                    onConnected(true)
                    ready = true
                    schedulePresence()
                }
                1 -> runCatching { ws?.send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL).toString()) }
                11 -> heartbeatAcknowledged = true
                7, 9 -> { Log.d(TAG, "reconnect requested op=${json.optInt("op")}"); reconnect() }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@DiscordGateway) {
            if (epoch != generation || closedByUser) return@synchronized
            Log.d(TAG, "closed $code $reason")
            reconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(this@DiscordGateway) {
            if (epoch != generation || closedByUser) return@synchronized
            Log.d(TAG, "failure ${t.message}")
            reconnect()
        }
    }

    private companion object { const val TAG = "DiscordRpc" }
}
