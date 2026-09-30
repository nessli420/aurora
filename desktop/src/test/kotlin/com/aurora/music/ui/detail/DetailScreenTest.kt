package com.aurora.music.ui.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aurora.music.data.DetailData
import com.aurora.music.data.remote.ArtistInfo
import com.aurora.music.model.accent
import com.aurora.music.ui.library.Harness
import com.aurora.music.ui.library.Samples
import com.aurora.music.ui.library.pixels
import com.aurora.music.ui.library.save
import com.aurora.music.ui.screens.detail.DetailScreen
import com.aurora.music.ui.testing.distinctColors
import com.aurora.music.ui.theme.ContextAccentState
import com.aurora.music.ui.theme.LocalContextAccent
import com.aurora.music.ui.theme.readableAccent
import com.aurora.music.viewmodel.DetailUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailScreenTest {
    private val label = "browse-b"

    @Composable
    private fun Detail(state: DetailUiState, kind: String, artistInfo: ArtistInfo? = null) = DetailScreen(
        contentPadding = PaddingValues(bottom = 96.dp), state = state, likedIds = setOf("s1", "s3"), currentSongId = "s2", isPlaying = true,
        onBack = {}, onPlayAll = { _, _ -> }, onShufflePlay = {}, onAddToQueue = {}, onPlayNext = {}, onToggleLike = {}, onOpenDetail = { _, _ -> },
        itemKind = kind, isItemLiked = kind == "album", onToggleItemLike = {}, downloadedIds = setOf("s0"), onDownload = {}, onRemoveDownload = {},
        onDownloadAll = {}, onRemoveDownloads = {}, onEditPlaylist = { _, _ -> true }, onDeletePlaylist = {}, isPinned = true, artistInfo = artistInfo,
    )

    private fun shot(name: String, width: Int = 1180, height: Int = 860, accent: ContextAccentState = ContextAccentState(), inspect: () -> Unit = {}, content: @Composable () -> Unit) =
        Harness(width, height) { CompositionLocalProvider(LocalContextAccent provides accent) { content() } }.use { it.settle(1200).save(label, name).also { inspect() } }

    @Test fun albumClaimsItsArtworkAccent() {
        val accent = ContextAccentState()
        var claimed: Color? = null
        val image = shot("detail-album", accent = accent, inspect = { claimed = accent.color }) { Detail(DetailUiState(loading = false, data = Samples.albumDetail), "album") }
        assertNotNull(claimed)
        assertNull(accent.color)
        assertTrue(image.distinctColors(6) > 200)
    }

    @Test fun blankArtworkFallsBackToInfoAccent() {
        val accent = ContextAccentState()
        var claimed: Color? = null
        val data = DetailData(Samples.albumDetail.info.copy(artUrl = ""), Samples.albumDetail.tracks)
        shot("detail-album-noart", accent = accent, inspect = { claimed = accent.color }) { Detail(DetailUiState(loading = false, data = data), "album") }
        assertEquals(readableAccent(data.info.accent, darkBackground = true), claimed)
    }

    @Test fun artistShowsAboutAndReleaseShelves() {
        val plain = shot("detail-artist-plain") { Detail(DetailUiState(loading = false, data = Samples.artistDetail), "artist") }
        val enriched = shot("detail-artist") { Detail(DetailUiState(loading = false, data = Samples.artistDetail, artistInfo = Samples.artistInfo), "artist", Samples.artistInfo) }
        assertFalse(plain.pixels().contentEquals(enriched.pixels()))
    }

    @Test fun playlistOffersGenreChips() {
        val image = shot("detail-playlist") { Detail(DetailUiState(loading = false, data = Samples.playlistDetail), "playlist") }
        assertTrue(image.distinctColors(6) > 200)
    }

    @Test fun narrowPaneUsesFullBleedHeader() {
        val narrow = shot("detail-narrow", width = 460) { Detail(DetailUiState(loading = false, data = Samples.albumDetail), "album") }
        val wide = shot("detail-album-wide") { Detail(DetailUiState(loading = false, data = Samples.albumDetail), "album") }
        fun artAt(px: IntArray, w: Int, x: Int, y: Int) = Color(px[y * w + x]).let { it.red - it.green > 0.25f }
        assertTrue(artAt(narrow.pixels(), 460, 400, 200))
        assertFalse(artAt(wide.pixels(), 1180, 400, 200))
    }

    @Test fun loadingAndFailedStates() {
        val accent = ContextAccentState()
        var claimed: Color? = Color.Unspecified
        val loading = shot("detail-loading", accent = accent, inspect = { claimed = accent.color }) { Detail(DetailUiState(loading = true), "album") }
        val failed = shot("detail-failed") { Detail(DetailUiState(loading = false), "album") }
        assertNull(claimed)
        assertFalse(loading.pixels().contentEquals(failed.pixels()))
    }
}
