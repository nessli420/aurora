package com.aurora.music.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aurora.music.data.DetailData
import com.aurora.music.data.ThemeStyle
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.components.AmbientBackground
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.TabletMetrics
import com.aurora.music.ui.screens.detail.DetailScreen
import com.aurora.music.ui.uxaudit.AuditScene
import com.aurora.music.viewmodel.DetailUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class DetailShotsTest {
    @Before fun gate() = assumeTrue(System.getenv("AURORA_SHOTS") != null)

    @Test fun pages() {
        listOf(1280 to 800, 1440 to 900, 1920 to 1080, 960 to 600).forEach { (w, h) -> tour(w, h, ThemeStyle.AURORA, "detail") }
    }

    @Test fun themes() {
        tour(1440, 900, ThemeStyle.RETRO, "detail-retro")
        tour(1440, 900, ThemeStyle.GLASS, "detail-glass")
    }

    @Test fun panes() {
        listOf(1280 to 800, 1440 to 900, 1920 to 1080, 960 to 600).forEach { (w, h) -> pane(w, h) }
    }

    private fun tour(w: Int, h: Int, style: Int, label: String) {
        AuditScene(label, w, h, setup = { settingsStore.setThemeStyle(style) }).use { scene ->
            scene.awaitRoute(Routes.HOME)
            val album = scene.songs.first { it.album == "Nocturne" }
            scene.navigate(Routes.detail("album", album.albumId), Routes.DETAIL)
            scene.shot("album", 2_000)
            scene.hover(scene.contentLeft + 300f, h - 160f)
            scene.shot("album-hover")
            scene.navigate(Routes.detail("artist", album.artistId), Routes.DETAIL)
            scene.shot("artist", 2_000)
            scene.scroll(scene.width * 0.6f, scene.height * 0.5f, 12f)
            scene.shot("artist-scrolled")
            val playlist = runBlocking { scene.container.repository.allPlaylists() }.first()
            scene.navigate(Routes.detail("playlist", playlist.id), Routes.DETAIL)
            scene.shot("playlist", 2_000)
            scene.navigate(Routes.detail("liked", "liked"), Routes.DETAIL)
            scene.shot("liked", 2_000)
        }
    }

    private fun pane(w: Int, h: Int) {
        var detail by mutableStateOf<DetailData?>(null)
        AuditScene("detail-pane", w, h, content = {
            val nav = if (w >= 1200) TabletMetrics.SidebarWidth else TabletMetrics.RailWidth
            Box(Modifier.fillMaxSize()) {
                AmbientBackground()
                Row(Modifier.fillMaxSize().padding(bottom = 100.dp)) {
                    Spacer(Modifier.width(nav + TabletMetrics.NavGap + if (w >= 1500) 560.dp else 440.dp))
                    Box(
                        Modifier.weight(1f).fillMaxHeight().padding(end = TabletMetrics.WindowInset)
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                    ) {
                        CompositionLocalProvider(LocalPageGutter provides PageMetrics.PaneGutter) {
                            detail?.let { d ->
                                DetailScreen(
                                    contentPadding = PaddingValues(), state = DetailUiState(loading = false, data = d), likedIds = emptySet(),
                                    currentSongId = d.tracks[2].id, isPlaying = true, onBack = {}, onPlayAll = { _, _ -> }, onShufflePlay = {},
                                    onAddToQueue = {}, onPlayNext = {}, onToggleLike = {}, onOpenDetail = { _, _ -> }, itemKind = "album",
                                    isItemLiked = false, onToggleItemLike = {}, downloadedIds = emptySet(), onDownload = {}, onRemoveDownload = {},
                                    onDownloadAll = {}, onRemoveDownloads = {}, onEditPlaylist = { _, _ -> true }, onDeletePlaylist = {},
                                )
                            }
                        }
                    }
                }
            }
        }).use { scene ->
            val album = scene.container.folderLibrary.albums.first { it.title == "Nocturne" }
            detail = runBlocking { scene.container.repository.detail("album", album.id) }
            scene.shot("album", 2_000)
        }
    }
}
