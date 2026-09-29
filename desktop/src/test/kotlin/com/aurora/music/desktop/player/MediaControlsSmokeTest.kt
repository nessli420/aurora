package com.aurora.music.desktop.player

import androidx.compose.ui.awt.ComposeWindow
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.util.AppLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

class MediaControlsSmokeTest {
    @Test fun publishesTheCurrentTrackToWindowsMediaControls() {
        assumeTrue("set AURORA_LIVE_SMTC=1 to talk to the Windows media controls", System.getenv("AURORA_LIVE_SMTC") == "1")
        val root = Files.createTempDirectory("aurora-smtc").toFile()
        val cover = File(root, "cover.png").also { ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", it) }
        val errors = CopyOnWriteArrayList<Throwable>()
        val warnings = CopyOnWriteArrayList<String>()
        val sink = AppLog.sink
        AppLog.sink = { level, tag, message, error -> if (tag == "AuroraMediaControls") warnings += message else sink(level, tag, message, error) }
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> errors += error })
        val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow().apply {
                setBounds(-3000, -3000, 320, 200)
                isVisible = true
            }
        }
        var handle = 0L
        SwingUtilities.invokeAndWait { handle = window.windowHandle }
        val engine = FakeEngine()
        val player = runBlocking(dispatcher) { DesktopPlayer(engine, container.playerDependencies().copy(scope = scope)) }
        try {
            runBlocking(dispatcher) {
                player.mediaControls.attach(handle)
                player.playAll(listOf(song("a").copy(artworkUrl = cover.toURI().toString()), song("b")))
            }
            Thread.sleep(1_500)
            runBlocking(dispatcher) {
                engine.at(60_000)
                player.next()
                player.cycleRepeat()
                player.toggleShuffle()
                player.pause()
            }
            Thread.sleep(1_500)
            assertEquals(emptyList<String>(), warnings.toList())
            assertEquals(emptyList<Throwable>(), errors.toList())
        } finally {
            runBlocking(dispatcher) { player.close() }
            AppLog.sink = sink
            SwingUtilities.invokeAndWait { window.dispose() }
            scope.cancel()
            container.close()
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }
}
