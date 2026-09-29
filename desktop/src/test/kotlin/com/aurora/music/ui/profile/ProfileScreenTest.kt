package com.aurora.music.ui.profile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.ui.auth.AccountScene
import com.aurora.music.ui.screens.profile.LocalProfileDialog
import com.aurora.music.ui.screens.profile.ProfileScreen
import org.junit.Test

class ProfileScreenTest {
    private val playlists = listOf(
        Playlist("p1", "Late Night Drive", "Synthwave after dark", "", 42, 0xFFFF7A59),
        Playlist("p2", "Focus Flow", "Instrumental concentration", "", 80, 0xFFFB7185),
        Playlist("p3", "Morning Coffee", "Easy acoustic mornings", "", 36, 0xFFF7B733),
    )
    private val artists = listOf(Artist("ar1", "Lunar Tide", "", 2_480_000), Artist("ar2", "Mara Quinn", "", 5_120_000))

    @Test fun probe() {
        AccountScene("profile") {
            ProfileScreen(PaddingValues(bottom = 24.dp), "Mara", "https://music.example.com", "Navidrome", "",
                onEditProfile = {}, playlists = playlists, artists = artists, onBack = {}, onOpenSettings = {}, onOpenDetail = { _, _ -> })
        }.use { it.shot() }
        AccountScene("profile-dialog") { LocalProfileDialog(onDismiss = {}) }.use { it.shot() }
    }
}
