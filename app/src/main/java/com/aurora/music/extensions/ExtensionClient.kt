package com.aurora.music.extensions

import android.content.*
import android.os.*
import com.aurora.extension.ExtensionContract
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ExtensionClient(private val context: Context) {
    private val requests = Semaphore(2)
    private val sequence = AtomicInteger()

    suspend fun call(descriptor: ExtensionDescriptor, request: JsonObject, timeoutMs: Long = 5_000): JsonObject = requests.withPermit {
        withContext(Dispatchers.IO) {
            withTimeout(timeoutMs) {
                val result = CompletableDeferred<JsonObject>()
                val id = sequence.incrementAndGet()
                val reply = Messenger(object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(message: Message) {
                        if (message.what != ExtensionContract.RESPONSE || message.arg1 != id || message.sendingUid != descriptor.uid) return
                        runCatching {
                            val json = message.data.getString("json") ?: error("Empty extension response.")
                            require(json.toByteArray(Charsets.UTF_8).size <= ExtensionCodec.MAX_BYTES) { "Extension response is too large." }
                            val response = ExtensionCodec.objectValue(json)
                            require(ExtensionCodec.number(response, "api", 1.0, 1.0) == 1.0)
                            require(!response.has("error")) { "Extension request failed." }
                            require(response.has("data")) { "Missing extension response." }
                            response
                        }.fold(result::complete) { result.completeExceptionally(IOException("Invalid extension response.", it)) }
                    }
                })
                request.addProperty("api", ExtensionContract.API)
                val json = request.toString()
                require(json.toByteArray(Charsets.UTF_8).size <= ExtensionCodec.MAX_BYTES)
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        runCatching { Messenger(service).send(Message.obtain(null, ExtensionContract.REQUEST, id, 0).apply {
                            replyTo = reply
                            data = Bundle().apply { putString("json", json) }
                        }) }.onFailure { result.completeExceptionally(IOException("Extension could not be reached.", it)) }
                    }
                    override fun onServiceDisconnected(name: ComponentName) { result.completeExceptionally(IOException("Extension disconnected.")) }
                    override fun onNullBinding(name: ComponentName) { result.completeExceptionally(IOException("Extension rejected the connection.")) }
                    override fun onBindingDied(name: ComponentName) { result.completeExceptionally(IOException("Extension stopped.")) }
                }
                val intent = Intent(ExtensionContract.ACTION).setComponent(ComponentName.unflattenFromString(descriptor.component))
                val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                try {
                    check(bound) { "Extension is unavailable." }
                    result.await()
                } finally {
                    if (bound) runCatching { context.unbindService(connection) }
                    result.cancel()
                }
            }
        }
    }
}
