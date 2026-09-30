package com.aurora.music.ui.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import javax.swing.SwingUtilities

internal fun <T> edt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}

// composes on the edt like the app so recomposition and snapshot apply callbacks never race
internal class EdtScene(width: Int, height: Int, content: @Composable () -> Unit) : AutoCloseable {
    private val scene = edt { ImageComposeScene(width, height, Density(1f), coroutineContext = Dispatchers.Main, content = content) }
    var millis = 0L

    fun frame(advanceMillis: Long, applyChanges: Boolean = true): Image = edt {
        if (applyChanges) Snapshot.sendApplyNotifications()
        millis += advanceMillis
        scene.render(millis * 1_000_000)
    }

    fun <T> input(block: ImageComposeScene.() -> T): T = edt { scene.block() }

    fun click(x: Float, y: Float, button: PointerButton = PointerButton.Primary, pressed: PointerButtons = PointerButtons(isPrimaryPressed = true)) = input {
        sendPointerEvent(PointerEventType.Move, Offset(x, y), timeMillis = millis)
        sendPointerEvent(PointerEventType.Press, Offset(x, y), timeMillis = millis, buttons = pressed, button = button)
        millis += 40
        sendPointerEvent(PointerEventType.Release, Offset(x, y), timeMillis = millis, buttons = PointerButtons(), button = button)
    }

    override fun close() {
        edt { scene.close() }
        edt { }
    }
}

internal fun Image.saveTo(dir: File?, name: String): Image {
    dir?.let { File(it.apply { mkdirs() }, "$name.png").writeBytes(encodeToData(EncodedImageFormat.PNG)!!.bytes) }
    return this
}

internal fun Image.distinctColors(step: Int = 5): Int {
    val bitmap = Bitmap.makeFromImage(this)
    val colors = HashSet<Int>()
    for (y in 0 until height step step) for (x in 0 until width step step) colors += bitmap.getColor(x, y)
    return colors.size
}

internal fun Image.differsFrom(other: Image): Boolean =
    !Bitmap.makeFromImage(this).readPixels()!!.contentEquals(Bitmap.makeFromImage(other).readPixels()!!)

internal fun Image.inkRows(x0: Int = 8, x1: Int = width - 16): Int {
    val bitmap = Bitmap.makeFromImage(this)
    val background = bitmap.getColor(width - 3, height - 3)
    return (0 until height).count { y -> (x0 until x1 step 3).any { x -> bitmap.getColor(x, y) != background } }
}

internal fun Image.pixel(x: Int, y: Int): Int = Bitmap.makeFromImage(this).getColor(x, y)

internal fun Image.region(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
    val bitmap = Bitmap.makeFromImage(this)
    return IntArray((x1 - x0) * (y1 - y0)) { i -> bitmap.getColor(x0 + i % (x1 - x0), y0 + i / (x1 - x0)) }
}
