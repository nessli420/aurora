package com.aurora.music.data.artwork

import com.aurora.music.data.ArtistSeparators
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ArtworkCacheTrimTest {
    @get:Rule val temp = TemporaryFolder()

    private object Images : ArtworkImages {
        override fun size(bytes: ByteArray): Pair<Int, Int> = 1 to 1
        override fun isPlaceholder(bytes: ByteArray) = false
    }

    private fun request(id: Int) = ArtworkRequest("Artist", "Album", original = "file:///cover$id")

    @Test fun goingOverBudgetTrimsBelowTheLimitAndKeepsTheNewestCover() = runBlocking {
        val dir = temp.newFolder("covers")
        val payload = ByteArray(8 * 1024 * 1024)
        val repo = ArtworkRepository(dir, { ByteArrayInputStream(payload) }, Images, offline = { true }, enabled = { false }, separators = { ArtistSeparators() })
        val totals = List(9) { id -> repo.open(request(id)) { }; repo.bytes.value }
        assertTrue(totals.all { it <= ArtworkRepository.LIMIT_BYTES })
        assertTrue(totals.zipWithNext().any { (before, after) -> after < before && after <= ArtworkRepository.LIMIT_BYTES / 8 * 7 })
        assertTrue(File(dir, "${request(8).key}.img").isFile)
    }

    @Test fun aNewCoverReplacesTheOneAlreadySaved() = runBlocking {
        val dir = temp.newFolder("replace")
        var payload = byteArrayOf(1)
        val repo = ArtworkRepository(dir, { ByteArrayInputStream(payload) }, Images, offline = { true }, enabled = { false }, separators = { ArtistSeparators() })
        repo.open(request(0)) { }
        payload = byteArrayOf(2, 2)
        assertArrayEquals(payload, repo.open(request(0)) { it.readBytes() })
    }
}
