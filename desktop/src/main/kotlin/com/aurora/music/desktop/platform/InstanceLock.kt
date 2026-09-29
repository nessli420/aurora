package com.aurora.music.desktop.platform

import com.aurora.music.desktop.natives.SystemNative
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.concurrent.thread

class InstanceLock private constructor(
    private val channel: FileChannel,
    private val server: ServerSocket,
    private val token: String,
    private val portFile: File,
) : AutoCloseable {
    private val _activations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val activations: SharedFlow<Unit> = _activations.asSharedFlow()

    init {
        thread(isDaemon = true, name = "aurora-instance") {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        if (socket.getInputStream().bufferedReader().readLine() == token) _activations.tryEmit(Unit)
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
        portFile.delete()
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(dir: File, allowForeground: (Long) -> Unit = ::grantForeground): InstanceLock? {
            dir.mkdirs()
            val channel = FileChannel.open(File(dir, "instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) {
                channel.close()
                signal(dir, allowForeground)
                return null
            }
            val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            val token = UUID.randomUUID().toString()
            val portFile = File(dir, "instance.port").apply { writeText("${server.localPort} $token ${ProcessHandle.current().pid()}") }
            return InstanceLock(channel, server, token, portFile)
        }

        // windows only lets the running instance take the foreground if the launched one hands its right over
        fun signal(dir: File, allowForeground: (Long) -> Unit = ::grantForeground): Boolean = runCatching {
            val parts = File(dir, "instance.port").readText().trim().split(' ')
            val (port, token) = parts
            parts.getOrNull(2)?.toLongOrNull()?.let(allowForeground)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port.toInt()), 1000)
                socket.getOutputStream().write("$token\n".toByteArray())
            }
        }.isSuccess

        private fun grantForeground(pid: Long) {
            runCatching { SystemNative.allowForeground(pid) }
        }
    }
}
