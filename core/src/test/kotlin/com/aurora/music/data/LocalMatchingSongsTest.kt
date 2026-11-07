package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class LocalMatchingSongsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun song(id: String, title: String) = Song(id, title, "Artist", "Album", "", 200)

    @Test fun titlesMatchThroughTheIndexAndARescanReplacesIt() = runBlocking {
        var songs = listOf(song("a", "Blue Hour (feat. Guest)"), song("b", "Red Sky"), song("c", "!!!"), song("d", "blue hour"))
        val catalog = Proxy.newProxyInstance(LocalCatalog::class.java.classLoader, arrayOf(LocalCatalog::class.java)) { _, method, _ ->
            when (method.name) {
                "getSongs" -> songs
                "ensureLoaded" -> Unit
                else -> error("Unexpected ${method.name}")
            }
        } as LocalCatalog
        val local = LocalBackend(catalog, LocalStore(temp.root), Session("local", "Me", "", "local", ServerType.LOCAL))
        suspend fun matches(title: String) = local.matchingSongs(song("q", title)).map { it.id }

        assertEquals(listOf("a", "d"), matches("Blue Hour"))
        assertEquals(emptyList<String>(), matches("   "))
        assertEquals(listOf("c"), matches("???"))

        songs = listOf(song("e", "Blue Hour"))
        assertEquals(listOf("e"), matches("Blue Hour"))
    }
}
