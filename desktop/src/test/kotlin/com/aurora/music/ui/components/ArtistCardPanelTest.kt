package com.aurora.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.ui.testing.EdtScene
import com.aurora.music.ui.testing.pixel
import com.aurora.music.ui.testing.saveTo
import com.aurora.music.ui.theme.AuroraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArtistCardPanelTest {
    private val shots = System.getenv("AURORA_SHOTS")?.let(::File)

    // album card at x 20..180, artist card at 200..360, both 160 wide; x 380 is bare page
    private fun render(style: Int, name: String) = EdtScene(400, 280) {
        AuroraTheme(UiPrefs(themeMode = ThemeMode.LIGHT, themeStyle = style)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    AlbumCard(Album("1", "The Album", "Artist", "", 2024, 10), {}, width = 160.dp)
                    ArtistCircle(Artist("2", "916frosty", "", 0), {}, width = 160.dp)
                }
            }
        }
    }.use { scene -> scene.frame(0); scene.frame(16); scene.frame(500).saveTo(shots, "artist-card-$name") }

    @Test fun artistsSitOnTheSamePanelAsAlbumsInBoxedStyles() {
        listOf(ThemeStyle.PAPER to "paper", ThemeStyle.RETRO to "retro", ThemeStyle.GLASS to "glass").forEach { (style, name) ->
            val image = render(style, name)
            val page = image.pixel(380, 120)
            // a panel shows as a fill or an outline at the card's left edge, for albums and artists alike
            fun edge(x0: Int) = (x0..x0 + 3).map { image.pixel(it, 120) }
            assertTrue("$name album panel", edge(20).any { it != page })
            assertTrue("$name artist panel", edge(200).any { it != page })
            assertEquals("$name panels match", edge(20), edge(200))
        }
    }
}
