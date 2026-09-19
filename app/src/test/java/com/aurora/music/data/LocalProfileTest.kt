package com.aurora.music.data

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class LocalProfileTest {
    @Test fun olderAndNullFieldsDefaultSafely() {
        assertEquals(LocalProfile("", "", ""), LocalProfileCodec.decode("{}"))
        assertEquals(LocalProfile("", "", ""), LocalProfileCodec.decode("{\"name\":null,\"avatar\":null,\"banner\":null}"))
    }

    @Test fun profileDoesNotChangeAccountIdentityOrServerAppearance() {
        val local = Session("On this device", "Local Library", "", "local", ServerType.LOCAL)
        val key = local.accountKey()
        val appearance = ProfileAppearance("My music", "file:///avatar", "file:///banner")
        assertEquals(appearance, profileAppearance(local, appearance))
        assertEquals(key, local.accountKey())
        val server = local.copy(type = ServerType.SUBSONIC, username = "Server user", imageUrl = "https://example.com/avatar")
        assertEquals(ProfileAppearance("Server user", server.imageUrl), profileAppearance(server, appearance))
    }

    @Test fun profileAndSeparatorRulesSurvivePortableBackupMetadata() {
        val profile = LocalProfile("Listener", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), "")
        val settings = ArtistSeparators(listOf(ArtistSeparator("&", SeparatorMatch.OFF)))
        val backup = AuroraBackup(prefs = PrefsBackup(strings = mapOf(LocalProfileCodec.KEY to LocalProfileCodec.encode(profile),
            ArtistSeparatorsCodec.KEY to ArtistSeparatorsCodec.encode(settings))))
        val restored = BackupArchive.decodeJson(Gson().toJson(backup))
        assertEquals(profile, LocalProfileCodec.decode(restored.prefs.strings[LocalProfileCodec.KEY]))
        assertEquals(settings, ArtistSeparatorsCodec.decode(restored.prefs.strings[ArtistSeparatorsCodec.KEY]))
    }

    @Test fun invalidOrOversizedProfileDataIsRejected() {
        assertTrue(runCatching { LocalProfileCodec.encode(LocalProfile("x".repeat(81))) }.isFailure)
        assertTrue(runCatching { LocalProfileCodec.encode(LocalProfile("Line\nbreak")) }.isFailure)
        assertTrue(runCatching { LocalProfileCodec.encode(LocalProfile(avatar = "not base64")) }.isFailure)
        val image = Base64.getEncoder().encodeToString(ByteArray(LocalProfileCodec.MAX_IMAGE_BYTES + 1))
        assertTrue(runCatching { LocalProfileCodec.encode(LocalProfile(banner = image)) }.isFailure)
    }
}
