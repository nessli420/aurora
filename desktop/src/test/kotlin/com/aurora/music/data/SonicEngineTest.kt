package com.aurora.music.data

import com.aurora.music.mix.MixAnalyzer
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SonicEngineTest {
    @get:Rule val temp = TemporaryFolder()

    private fun song(id: String) = Song(id, "Song $id", "Artist", "Album", "", 40, streamUrl = "https://example.invalid/$id")
    private fun analyzer() = MixAnalyzer(temp.root, { error("radio must not decode here") })

    @Test fun radioWithoutOtherVectorsSkipsTheWalkAndTheDecode() = runBlocking {
        val store = SonicStore(temp.root).apply { selectAccount("acct") }
        var walks = 0
        val engine = SonicEngine(store, { walks++; listOf(song("a"), song("b")) }, { "acct" }, analyzer())
        assertTrue(engine.buildRadio(song("a")).isEmpty())
        assertTrue(engine.buildAutoDj(song("a")).isEmpty())
        assertEquals(0, walks)
    }

    @Test fun radioSharesOneWalkUntilTheLibraryChanges() = runBlocking {
        val store = SonicStore(temp.root).apply { selectAccount("acct") }
        val library = listOf("a", "b", "c").map(::song)
        val random = java.util.Random(7)
        library.forEach { s -> store.put(s.id, FloatArray(SonicFeatures.DIMS) { random.nextFloat() }, "acct") }
        var walks = 0
        var generation = 0
        val engine = SonicEngine(store, { walks++; library }, { "acct" }, analyzer(), libraryKey = { generation })
        assertEquals("a", engine.buildRadio(library[0]).first().id)
        assertEquals("b", engine.buildAutoDj(library[1]).first().id)
        assertEquals(1, walks)
        generation++
        assertEquals(3, engine.buildRadio(library[0]).size)
        assertEquals(2, walks)
    }

    @Test fun radioFromAGivenPoolSkipsTheWalk() = runBlocking {
        val store = SonicStore(temp.root).apply { selectAccount("acct") }
        val library = listOf("a", "b", "c").map(::song)
        val random = java.util.Random(7)
        library.forEach { s -> store.put(s.id, FloatArray(SonicFeatures.DIMS) { random.nextFloat() }, "acct") }
        var walks = 0
        val engine = SonicEngine(store, { walks++; library }, { "acct" }, analyzer())
        assertEquals(listOf("a", "b"), engine.buildRadio(library[0], 40, library.take(2) + song("b")).map { it.id })
        assertEquals(0, walks)
    }
}
