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

/**
 * Minimal Discord Gateway (v10) client for setting the *user's own* Rich Presence — the same
 * approach Kizzy uses, since Android has no local Discord IPC. Connects with a user token, performs
 * the HELLO → heartbeat → IDENTIFY handshake, captures the username from READY, and sends presence
 * (op 3) updates. Reconnects on drop. Best-effort; never throws into the caller.
 *
 * Note: this drives a user account over the gateway, which is a Discord ToS gray area — kept fully
 * opt-in and local. Not a bot; no privileged intents.
 */
class DiscordGateway(
    private val onUsername: (String) -> Unit,
    private val onConnected: (Boolean) -> Unit,
) {
    private val http = OkHttpClient.Builder().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ws: WebSocket? = null
    private var token: String = ""
    private var seq: Int? = null
    private var heartbeatJob: Job? = null
    private var activity: JSONObject? = null
    @Volatile private var closedByUser = false

    fun connect(token: String, activity: JSONObject?) {
        if (token.isBlank()) return
        this.token = token
        this.activity = activity
        closedByUser = false
        open()
    }

    fun updateActivity(activity: JSONObject?) {
        this.activity = activity
        if (ws != null) sendPresence()
    }

    fun disconnect() {
        closedByUser = true
        heartbeatJob?.cancel()
        runCatching { ws?.close(1000, "bye") }
        ws = null
        onConnected(false)
    }

    private fun open() {
        val req = Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build()
        ws = http.newWebSocket(req, Listener())
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
        activity?.let { activities.put(it) }
        return JSONObject()
            .put("status", "online")
            .put("since", 0)
            .put("activities", activities)
            .put("afk", false)
    }

    private fun sendPresence() {
        runCatching { ws?.send(JSONObject().put("op", 3).put("d", presence()).toString()) }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay((intervalMs * 0.5).toLong())
            while (isActive) {
                runCatching { ws?.send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL).toString()) }
                delay(intervalMs)
            }
        }
    }

    private fun reconnect() {
        heartbeatJob?.cancel()
        onConnected(false)
        if (closedByUser) return
        scope.launch { delay(5000); if (!closedByUser) open() }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
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
                    sendPresence()
                }
                1 -> runCatching { ws?.send(JSONObject().put("op", 1).put("d", seq ?: JSONObject.NULL).toString()) }
                7, 9 -> { Log.d(TAG, "reconnect requested op=${json.optInt("op")}"); reconnect() }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "closed $code $reason")
            reconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "failure ${t.message}")
            reconnect()
        }
    }

    private companion object { const val TAG = "DiscordRpc" }
}
