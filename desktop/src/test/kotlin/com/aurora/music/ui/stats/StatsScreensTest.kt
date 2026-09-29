package com.aurora.music.ui.stats

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.aurora.music.data.PlayEvent
import com.aurora.music.data.RecapPeriod
import com.aurora.music.data.RecapWindow
import com.aurora.music.ui.auth.AccountScene
import com.aurora.music.ui.screens.stats.ListeningHistoryScreen
import com.aurora.music.ui.screens.stats.ListeningStatsScreen
import com.aurora.music.ui.screens.stats.RecapInboxScreen
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsScreensTest {
    private val padding = PaddingValues(bottom = 24.dp)

    private fun events(): List<PlayEvent> {
        val songs = listOf(
            Triple("s1", "Midnight Bloom", "Lunar Tide"), Triple("s2", "Velvet Skyline", "Mara Quinn"),
            Triple("s3", "Paper Planes", "The Foxgloves"), Triple("s4", "Gravity", "Aerial"), Triple("s5", "Saltwater", "Coastlines"),
        )
        val today = LocalDate.now()
        return (0 until 60).map { i ->
            val (id, title, artist) = songs[(i * 7 + i / 5) % songs.size]
            val day = today.minusDays((i / 6).toLong())
            val at = day.atTime(8 + i % 12, (i * 13) % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            PlayEvent(id, title, artist, "$artist LP", "al-$artist", "ar-$artist", "", 200 + i, at, 180_000L)
        }.sortedByDescending { it.timestamp }
    }

    @Test fun probe() {
        AccountScene("history", seed = { playHistory.restore(events()) }) { ListeningHistoryScreen(padding, onBack = {}, onPlay = {}) }.use { it.shot() }
        AccountScene("history-empty") { ListeningHistoryScreen(padding, onBack = {}, onPlay = {}) }.use { it.shot() }
        AccountScene("stats", height = 2600, seed = { playHistory.restore(events()) }) {
            ListeningStatsScreen(padding, onBack = {}, onPlay = {}, onOpenDetail = { _, _ -> }, initialWindow = RecapWindow.containing(RecapPeriod.WEEK, LocalDate.now()))
        }.use { it.shot() }
        AccountScene("inbox", seed = { playHistory.restore(events()) }) { RecapInboxScreen(padding, onBack = {}, onOpen = {}) }.use { it.shot() }
    }
}
