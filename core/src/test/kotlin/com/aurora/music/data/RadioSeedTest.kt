package com.aurora.music.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext

private fun unsupported(): MediaBackend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
    throw UnsupportedOperationException(method.name)
} as MediaBackend

class RadioSeedTest {
    private class Source : MediaBackend by unsupported() {
        override val session = Session("https://music.example", "alice", "salt", "token")
        val seeds = ArrayList<String>()
        override suspend fun radio(seedId: String): List<com.aurora.music.model.Song> { seeds += seedId; return emptyList() }
        override fun prefersServerRadio(seedId: String) = false
    }

    private val source = Source()
    private val dir = Files.createTempDirectory("aurora-radio-seed").toFile()
    private val repository = MusicRepository({ source }, DownloadManager(dir, { "file://$it" }, { null }), mapping = EmptyCoroutineContext)

    @Test fun aSongFiledUnderTheSourcesMergeNamespaceAsksItsRadioByItsOwnId() = runBlocking {
        repository.radio(mergeNamespace(source.session) + MERGE_NAMESPACE_SEP + "vid42")
        repository.radio("plain")
        // another source's namespace isn't this one's to strip
        val other = "s" + "0".repeat(64) + MERGE_NAMESPACE_SEP + "vid7"
        repository.radio(other)
        assertEquals(listOf("vid42", "plain", other), source.seeds)
        dir.deleteRecursively()
        Unit
    }
}
