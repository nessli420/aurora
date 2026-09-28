package com.aurora.music.desktop.platform

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
        fun acquire(dir: File): InstanceLock? {
            dir.mkdirs()
            val channel = FileChannel.open(File(dir, "instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) {
                channel.close()
                signal(dir)
                return null
            }
            val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            val token = UUID.randomUUID().toString()
            val portFile = File(dir, "instance.port").apply { writeText("${server.localPort} $token") }
            return InstanceLock(channel, server, token, portFile)
        }

        fun signal(dir: File): Boolean = runCatching {
            val (port, token) = File(dir, "instance.port").readText().trim().split(' ')
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port.toInt()), 1000)
                socket.getOutputStream().write("$token\n".toByteArray())
            }
        }.isSuccess
    }
}
