package com.aurora.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import com.aurora.music.ui.testing.EdtScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelScrollTest {
    private fun EdtScene.wheel(x: Float, y: Float, dy: Float) {
        input {
            sendPointerEvent(PointerEventType.Move, Offset(x, y), timeMillis = millis)
            sendPointerEvent(PointerEventType.Scroll, Offset(x, y), scrollDelta = Offset(0f, dy), timeMillis = millis)
        }
        repeat(20) { Thread.sleep(10); frame(16) }
    }

    @Test fun verticalWheelScrollsTheStripThenHandsOverToThePage() {
        val page = LazyListState()
        val strip = LazyListState()
        EdtScene(400, 300) {
            LazyColumn(Modifier.fillMaxWidth().height(300.dp), state = page) {
                item {
                    LazyRow(Modifier.fillMaxWidth().height(80.dp).wheelScrollsHorizontally(strip), state = strip) {
                        items(12) { Box(Modifier.size(80.dp)) }
                    }
                }
                items(20) { Box(Modifier.size(80.dp)) }
            }
        }.use { scene ->
            scene.frame(16)
            scene.wheel(200f, 40f, 2f)
            assertTrue(strip.firstVisibleItemIndex > 0)
            assertEquals(0, page.firstVisibleItemIndex)
            assertEquals(0, page.firstVisibleItemScrollOffset)

            scene.wheel(200f, 40f, 40f)
            assertTrue(strip.canScrollForward.not())
            scene.wheel(200f, 40f, 3f)
            assertTrue(page.firstVisibleItemIndex > 0 || page.firstVisibleItemScrollOffset > 0)
        }
    }
}
