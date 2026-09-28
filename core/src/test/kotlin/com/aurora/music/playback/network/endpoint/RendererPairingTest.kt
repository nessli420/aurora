package com.aurora.music.playback.network.endpoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RendererPairingTest {
    @Test fun pairingIsSingleUseAndPersistsOnlyHashedTokens() {
        var saved = emptyList<ControllerTrust>()
        val pairing = RendererPairing(now = { 1_000L }, persist = { saved = it })
        val window = pairing.begin()
        val (controller, token) = pairing.pair("Phone", window.code)
        assertEquals(43, token.length)
        assertEquals(controller, pairing.authenticate(token))
        assertFalse(saved.single().tokenHash.contains(token))
        assertEquals(controller, RendererPairing(saved).authenticate(token))
        assertEquals("pairing_closed", assertThrows(RendererException::class.java) {
            pairing.pair("Other phone", window.code)
        }.code)
    }

    @Test fun fiveWrongCodesClosePairingUntilUserStartsAgain() {
        val pairing = RendererPairing()
        val code = pairing.begin().code
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(5) { assertThrows(RendererException::class.java) { pairing.pair("Phone", wrong) } }
        assertEquals("pairing_closed", assertThrows(RendererException::class.java) { pairing.pair("Phone", code) }.code)
        assertNotNull(pairing.pair("Phone", pairing.begin().code))
    }

    @Test fun expirationAndRevocationRejectPreviouslyValidSecrets() {
        var time = 5_000L
        var saved = emptyList<ControllerTrust>()
        val pairing = RendererPairing(now = { time }, persist = { saved = it })
        val code = pairing.begin().code
        time += 120_000
        assertThrows(RendererException::class.java) { pairing.pair("Phone", code) }
        val (controller, token) = pairing.pair("Phone", pairing.begin().code)
        pairing.revoke(controller.id)
        assertNull(pairing.authenticate(token))
        assertNull(RendererPairing(saved).authenticate(token))
    }

    @Test fun failedPersistenceDoesNotInstallTrustOrConsumeCode() {
        var fail = true
        val pairing = RendererPairing(persist = { if (fail) error("disk full") })
        val code = pairing.begin().code
        assertThrows(IllegalStateException::class.java) { pairing.pair("Phone", code) }
        assertEquals(emptyList<PairedController>(), pairing.clients())
        fail = false
        assertNotNull(pairing.pair("Phone", code))
    }
}
