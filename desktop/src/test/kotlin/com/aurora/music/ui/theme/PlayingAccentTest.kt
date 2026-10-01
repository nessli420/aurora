package com.aurora.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import com.aurora.music.data.UiPrefs
import com.aurora.music.ui.testing.EdtScene
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayingAccentTest {
    private fun primary(playing: Color?): Color {
        var primary = Color.Unspecified
        EdtScene(40, 40) { AuroraTheme(UiPrefs(), playingAccent = playing) { primary = MaterialTheme.colorScheme.primary } }.use { it.frame(16) }
        return primary
    }

    @Test fun pickedAccentRulesUnlessACoverIsFollowed() {
        assertEquals(AccentPresets[0].seed, primary(null))
        assertEquals(Color(0xFFE8C21A), primary(Color(0xFFE8C21A)))
        assertEquals(readableAccent(Color(0xFF101830), darkBackground = true), primary(Color(0xFF101830)))
    }
}
