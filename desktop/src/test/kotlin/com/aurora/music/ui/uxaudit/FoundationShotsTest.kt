package com.aurora.music.ui.uxaudit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ThemeStyle
import com.aurora.music.desktop.platform.desktopFileUri
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.ui.components.AdaptiveShelf
import com.aurora.music.ui.components.AlbumCard
import com.aurora.music.ui.components.AmbientBackground
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.components.PageSection
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.components.SongListHeader
import com.aurora.music.ui.components.SongRow
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.TabletMetrics
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

private class ShowcaseData(val albums: List<Album>, val artists: List<Artist>, val playlists: List<Playlist>, val album: List<Song>, val songs: List<Song>)

class FoundationShotsTest {
    @Before fun gate() = assumeTrue(System.getenv("AURORA_SHOTS") != null)

    @Test fun components() {
        listOf(1280 to 800, 1440 to 900, 1920 to 1080, 960 to 600).forEach { (w, h) -> showcase(w, h, ThemeStyle.AURORA, "aurora") }
        showcase(1440, 900, ThemeStyle.RETRO, "retro")
        showcase(1440, 900, ThemeStyle.GLASS, "glass")
    }

    private fun showcase(w: Int, h: Int, style: Int, label: String) {
        var data by mutableStateOf<ShowcaseData?>(null)
        AuditScene("foundation-$label", w, h, setup = { settingsStore.setThemeStyle(style) }, content = { data?.let { Showcase(it) } }).use { scene ->
            val library = scene.container.folderLibrary
            val playlists = runBlocking { scene.container.repository.allPlaylists() }
            val artists = library.artists.map { a -> a.copy(imageUrl = AuditLibrary.portraitFor(a.name)?.let { desktopFileUri(it.path) }.orEmpty()) }
            val nocturne = library.albums.first { it.title == "Nocturne" }
            data = ShowcaseData(library.albums, artists, playlists, library.songsByAlbumId(nocturne.id), library.songs.take(8))
            scene.shot("components", 2_500)
            val left = scene.contentLeft
            scene.hover(left + 400f, 120f + PageMetrics.HeaderTop.value + PageMetrics.HeaderHeight.value)
            scene.shot("components-hover-shelf")
            scene.scroll(left + 400f, h * 0.5f, 30f)
            scene.hover(left + 300f, h * 0.5f)
            scene.shot("components-hover-rows")
        }
    }
}

@Composable
private fun Showcase(d: ShowcaseData) {
    val gutter = LocalPageGutter.current
    val window = LocalWindowLayout.current
    val nav = if (window.canExpandSidebar) TabletMetrics.SidebarWidth else TabletMetrics.RailWidth
    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        LazyColumn(
            Modifier.fillMaxSize().padding(start = nav + TabletMetrics.NavGap),
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(PageMetrics.SectionGap),
        ) {
            item {
                PageHeader("Good evening", subtitle = "${d.albums.size} albums · ${d.artists.size} artists") {
                    IconButton(onClick = {}) { Icon(Icons.Filled.Search, null) }
                    IconButton(onClick = {}) { Icon(Icons.Filled.Add, null) }
                }
            }
            item {
                PageSection("Recently played", action = "See all", onAction = {}) {
                    AdaptiveShelf(d.albums, bleed = gutter, key = { it.id }) { album, width -> AlbumCard(album, {}, width = width) }
                }
            }
            item {
                PageSection("Artists", count = d.artists.size) {
                    AdaptiveShelf(d.artists, bleed = gutter) { artist, width -> ArtistCircle(artist, {}, width = width) }
                }
            }
            item {
                PageSection("Your playlists", action = "See all", onAction = {}) {
                    AdaptiveShelf(d.playlists) { playlist, width -> PlaylistCard(playlist, {}, width = width) }
                }
            }
            item {
                PageSection("Nocturne") {
                    SongListHeader(showIndex = true, showArt = false, showAlbum = false)
                    d.album.forEachIndexed { i, s ->
                        SongRow(s, isPlaying = i == 2, isLiked = i % 3 == 0, onClick = {}, onToggleLike = {}, index = i + 1,
                            showArt = false, showAlbum = false)
                    }
                }
            }
            item {
                PageHeader("Pushed page", subtitle = "Back arrow hangs in the gutter", onBack = {})
            }
            item {
                PageSection("Songs", count = d.songs.size) {
                    SongListHeader(showIndex = true, showDateAdded = true)
                    d.songs.forEachIndexed { i, s ->
                        SongRow(s, isPlaying = false, isLiked = i % 4 == 1, onClick = {}, onToggleLike = {}, index = i + 1,
                            showDateAdded = true, isDownloaded = i == 3)
                    }
                }
            }
        }
    }
}
