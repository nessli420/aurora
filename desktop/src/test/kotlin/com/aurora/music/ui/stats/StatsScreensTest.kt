package com.aurora.music.ui.stats

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ListeningRecaps
import com.aurora.music.data.PlayEvent
import com.aurora.music.data.RecapPeriod
import com.aurora.music.data.RecapWindow
import com.aurora.music.ui.auth.AccountScene
import com.aurora.music.ui.auth.accountShots
import com.aurora.music.ui.auth.differsFrom
import com.aurora.music.ui.auth.distinctColors
import com.aurora.music.ui.auth.inkRows
import com.aurora.music.ui.screens.stats.ListeningHistoryScreen
import com.aurora.music.ui.screens.stats.ListeningStatsScreen
import com.aurora.music.ui.screens.stats.RecapInboxScreen
import com.aurora.music.ui.screens.stats.RecapStyle
import com.aurora.music.ui.screens.stats.renderRecap
import kotlinx.coroutines.flow.first
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class StatsScreensTest {
    private val padding = PaddingValues(bottom = 24.dp)
    private val songs = listOf(
        Triple("s1", "Midnight Bloom", "Lunar Tide"), Triple("s2", "Velvet Skyline", "Mara Quinn"),
        Triple("s3", "Paper Planes", "The Foxgloves"), Triple("s4", "Gravity", "Aerial"), Triple("s5", "Saltwater", "Coastlines"),
    )
    private val anchor = LocalDate.of(2025, 6, 15)
    private val week = RecapWindow.containing(RecapPeriod.WEEK, anchor)

    private fun events(last: LocalDate): List<PlayEvent> = (0 until 60).map { i ->
        val (id, title, artist) = songs[(i * 7 + i / 5) % songs.size]
        val at = last.minusDays((i / 6).toLong()).atTime(8 + i % 12, (i * 13) % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        PlayEvent(id, title, artist, "$artist LP", "al-$artist", "ar-$artist", "", 200 + i, at, 180_000L)
    }.sortedByDescending { it.timestamp }

    @Test fun historyGroupsByDayAndReplaysTracks() {
        val history = events(LocalDate.now())
        val played = mutableListOf<String>()
        var back = 0
        val full = AccountScene("history", seed = { playHistory.restore(history) }) {
            ListeningHistoryScreen(padding, onBack = { back++ }, onPlay = { played += it })
        }.use { scene ->
            val image = scene.shot()
            scene.click(480f, 126f)
            scene.click(480f, 550f)
            scene.click(28f, 26f)
            image
        }
        assertEquals(listOf(history[0].songId, history[6].songId), played)
        assertEquals(1, back)
        val empty = AccountScene("history-empty") { ListeningHistoryScreen(padding, onBack = {}, onPlay = {}) }.use { it.shot() }
        assertTrue(full.distinctColors() > 20)
        assertTrue(empty.inkRows() < full.inkRows())
    }

    @Test fun statsNavigatesPeriodsDatesAndRankings() {
        val played = mutableListOf<String>()
        val details = mutableListOf<String>()
        AccountScene("stats", height = 2600, seed = { playHistory.restore(events(anchor)) }) {
            ListeningStatsScreen(padding, onBack = {}, onPlay = { played += it }, onOpenDetail = { kind, id -> details += "$kind:$id" }, initialWindow = week)
        }.use { scene ->
            val weekly = scene.shot()
            assertTrue(weekly.distinctColors() > 40)
            scene.click(300f, 1230f)
            scene.click(300f, 1607f)
            assertEquals(listOf("artist:ar-Aerial"), details)
            assertEquals(listOf("s4"), played)

            scene.click(40f, 143f)
            val previous = scene.shot("-previous")
            assertTrue(previous.differsFrom(weekly))
            scene.click(300f, 1074f)
            assertEquals("artist:ar-Coastlines", details.last())

            scene.click(480f, 143f)
            assertTrue(scene.shot("-picker").differsFrom(previous))
            scene.click(432f, 1310f)
            scene.click(623f, 1551f)
            scene.click(300f, 1230f)
            assertEquals("artist:ar-Aerial", details.last())

            scene.click(44f, 82f)
            assertTrue(scene.shot("-daily").differsFrom(weekly))
        }
    }

    @Test fun recapPictureRendersEveryStyle() {
        val recap = ListeningRecaps.build(events(anchor), week)
        val pictures = RecapStyle.entries.map { style ->
            renderRecap(recap, style).also { image ->
                assertEquals(1080, image.width)
                assertEquals(1600, image.height)
                assertTrue(image.distinctColors() > 20)
                accountShots?.let { File(it.apply { mkdirs() }, "recap-${style.name.lowercase()}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            }
        }
        assertTrue(pictures[0].differsFrom(pictures[1]) && pictures[1].differsFrom(pictures[2]) && pictures[0].differsFrom(pictures[2]))
    }

    @Test fun inboxOpensAndMarksRecapsRead() {
        val opened = mutableListOf<RecapWindow>()
        AccountScene("inbox", seed = { playHistory.restore(events(LocalDate.now())) }) {
            RecapInboxScreen(padding, onBack = {}, onOpen = { opened += it })
        }.use { scene ->
            val unread = scene.shot()
            scene.click(480f, 380f)
            assertEquals(1, opened.size)
            scene.await(read = { desktopSettings.recapSeen.first() }) { opened.single().key in it }
            scene.click(893f, 32f)
            val all = ListeningRecaps.available(scene.container.playHistory.history.value, LocalDate.now()).map { it.key }.toSet()
            scene.await(read = { desktopSettings.recapSeen.first() }) { it.containsAll(all) }
            assertTrue(scene.shot("-read").differsFrom(unread))
        }
    }
}
