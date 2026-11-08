package com.aurora.music.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSessionCallbackTest {
    @Test fun repeatCyclesOffAllOneAndResetsUnknownModes() {
        assertEquals(Player.REPEAT_MODE_ALL, PlaybackSessionCallback.nextRepeatMode(Player.REPEAT_MODE_OFF))
        assertEquals(Player.REPEAT_MODE_ONE, PlaybackSessionCallback.nextRepeatMode(Player.REPEAT_MODE_ALL))
        assertEquals(Player.REPEAT_MODE_OFF, PlaybackSessionCallback.nextRepeatMode(Player.REPEAT_MODE_ONE))
        assertEquals(Player.REPEAT_MODE_OFF, PlaybackSessionCallback.nextRepeatMode(42))
    }
}
