package com.aurora.music.desktop.linux

import com.aurora.music.desktop.platform.HostPlatform
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class PrivateBus : AutoCloseable {
    private val root: File = Files.createTempDirectory("aurora-bus").toFile()
    private val processes = mutableListOf<Process>()
    private val connections = mutableListOf<DBusConnection>()
    val address: String

    init {
        val config = File(root, "bus.conf").apply {
            writeText(
                """
                <busconfig>
                  <type>session</type>
                  <listen>unix:path=${File(root, "bus")}</listen>
                  <policy context="default">
                    <allow send_destination="*" eavesdrop="true"/>
                    <allow eavesdrop="true"/>
                    <allow own="*"/>
                  </policy>
                </busconfig>
                """.trimIndent(),
            )
        }
        val daemon = ProcessBuilder("dbus-daemon", "--config-file=$config", "--nofork", "--print-address=1")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        processes += daemon
        address = daemon.inputStream.bufferedReader().readLine() ?: error("dbus-daemon did not start")
    }

    fun connect(): DBusConnection = DBusConnectionBuilder.forAddress(address).withShared(false).build().also { connections += it }

    fun run(vararg command: String, input: String? = null, background: Boolean = false): Process {
        val process = ProcessBuilder(*command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment()["DBUS_SESSION_BUS_ADDRESS"] = address
                environment()["HOME"] = File(root, "home").path
                environment()["XDG_DATA_HOME"] = File(root, "data").path
                environment()["XDG_RUNTIME_DIR"] = File(root, "run").apply { mkdirs() }.path
            }
            .start()
        input?.let { process.outputStream.use { out -> out.write(it.toByteArray()) } }
        if (background) processes += process else process.waitFor(20, TimeUnit.SECONDS)
        return process
    }

    fun startKeyring() {
        run("gnome-keyring-daemon", "--foreground", "--components=secrets", "--unlock", input = "test-password", background = true)
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val owned = run("gdbus", "call", "--session", "-d", "org.freedesktop.DBus", "-o", "/org/freedesktop/DBus",
                "-m", "org.freedesktop.DBus.NameHasOwner", "org.freedesktop.secrets").inputStream.bufferedReader().readText()
            if ("true" in owned) return
            Thread.sleep(100)
        }
        error("gnome-keyring-daemon did not start")
    }

    override fun close() {
        connections.forEach { runCatching { it.close() } }
        processes.reversed().forEach { it.destroy(); it.waitFor(5, TimeUnit.SECONDS) }
        root.deleteRecursively()
    }

    companion object {
        fun assumeAvailable(vararg tools: String) {
            assumeTrue(HostPlatform.isLinux)
            (listOf("dbus-daemon") + tools).forEach { tool ->
                assumeTrue("$tool is not installed", System.getenv("PATH").orEmpty().split(':').any { File(it, tool).canExecute() })
            }
        }
    }
}
