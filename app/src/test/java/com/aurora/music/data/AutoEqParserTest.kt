package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoEqParserTest {
    private fun filter(index: Int, frequency: String = "1000", gain: String = "-3", q: String = "1") =
        "Filter $index: ON PK Fc $frequency Hz Gain $gain dB Q $q"

    @Test fun acceptsCorrectionWithinStandardEngineBudget() {
        val parsed = EqTextParser.parse("Preamp: -6 dB\n" + (1..12).joinToString("\n") { filter(it) })
        assertEquals(-6f, parsed?.preampDb)
        assertEquals(12, parsed?.bands?.size)
    }

    @Test fun rejectsCorrectionsThatStandardEngineWouldTruncate() {
        assertNull(EqTextParser.parse((1..13).joinToString("\n") { filter(it) }))
    }

    @Test fun rejectsValuesThatCannotBeStoredOrPlayed() {
        assertNull(EqTextParser.parse(filter(1, frequency = "24001")))
        assertNull(EqTextParser.parse(filter(1, gain = "31")))
        assertNull(EqTextParser.parse(filter(1, q = "101")))
        assertNull(EqTextParser.parse("Preamp: -61 dB\n" + filter(1)))
    }
}
