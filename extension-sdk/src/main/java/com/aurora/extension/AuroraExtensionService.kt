package com.aurora.extension

import android.app.Service
import android.content.Intent
import android.os.*
import org.json.JSONObject

abstract class AuroraExtensionService : Service(), AuroraExtension {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    protected open fun acceptsCaller(uid: Int): Boolean =
        packageManager.getPackagesForUid(uid)?.contains("com.aurora.music") == true

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("aurora-extension").apply { start() }
        messenger = Messenger(object : Handler(worker.looper) {
            override fun handleMessage(message: Message) {
                if (message.what != ExtensionContract.REQUEST || !acceptsCaller(message.sendingUid)) return
                val reply = message.replyTo ?: return
                val result = runCatching {
                    val text = message.data.getString("json") ?: error("Missing request.")
                    require(text.toByteArray(Charsets.UTF_8).size <= ExtensionContract.MAX_MESSAGE_BYTES)
                    val request = JSONObject(text)
                    require(request.integer("api", ExtensionContract.API..ExtensionContract.API) == ExtensionContract.API)
                    val settings = request.optJSONObject("settings") ?: JSONObject()
                    val data: Any = when (request.getString("method")) {
                        ExtensionContract.DESCRIBE -> describe()
                        ExtensionContract.AUDIO -> (this@AuroraExtensionService as? AudioPlugin
                            ?: error("Audio presets are unavailable.")).audio(settings)
                        ExtensionContract.MEDIA -> (this@AuroraExtensionService as? MediaProviderPlugin
                            ?: error("Library browsing is unavailable.")).media(
                            request.integer("offset", 0..10_000),
                            request.integer("count", 1..100), settings)
                        ExtensionContract.METADATA -> (this@AuroraExtensionService as? MetadataPlugin
                            ?: error("Metadata lookup is unavailable.")).metadata(request.getJSONObject("query"), settings)
                        else -> error("Unsupported extension method.")
                    }
                    JSONObject().put("api", ExtensionContract.API).put("data", data).toString().also {
                        require(it.toByteArray(Charsets.UTF_8).size <= ExtensionContract.MAX_MESSAGE_BYTES) { "Extension response is too large." }
                    }
                }.getOrElse { JSONObject().put("api", ExtensionContract.API).put("error", "Extension request failed.").toString() }
                runCatching { reply.send(Message.obtain(null, ExtensionContract.RESPONSE, message.arg1, 0).apply {
                    data = Bundle().apply { putString("json", result) }
                }) }
            }
        })
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == ExtensionContract.ACTION) messenger.binder else null

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun JSONObject.integer(key: String, range: IntRange): Int {
        val raw = get(key)
        require(raw is Number) { "Invalid $key." }
        val value = raw.toDouble()
        require(value.isFinite() && value == value.toInt().toDouble() && value.toInt() in range) { "Invalid $key." }
        return value.toInt()
    }
}
