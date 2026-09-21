package com.aurora.music.data.updates

import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    @Test fun comparesNumericVersionsInsteadOfText() {
        assertTrue(version("V2.10.0") > version("v2.9.9"))
        assertTrue(version("3.0.0") > version("2.99.99"))
        assertEquals(version("1.0"), version("V1.0.0"))
        assertEquals(version("2.5.0+build.4"), version("2.5.0"))
    }

    @Test fun stableReleaseFollowsReleaseCandidates() {
        assertTrue(version("2.5.0") > version("2.5.0-rc.2"))
        assertTrue(version("2.5.0-rc.10") > version("2.5.0-rc.2"))
        assertTrue(version("2.5.0-rc.1") > version("2.5.0-beta.9"))
        assertTrue(version("2.6.0-rc.1") > version("2.5.0"))
    }

    @Test fun invalidVersionsDoNotBecomeUpdates() {
        listOf("latest", "2", "v2.5.0junk", "-2.0.0", "999999999999999999999.0.0").forEach {
            assertNull(AppVersion.parse(it))
        }
    }

    @Test fun parsesPublishedAuroraReleaseAndChecksum() {
        val release = GitHubRelease.parse(releaseJson())
        assertEquals("V2.6.0", release.tag)
        assertEquals("https://github.com/nessli420/aurora/releases/tag/V2.6.0", release.pageUrl)
        assertEquals(123L, release.apk?.size)
        assertEquals("a".repeat(64), release.apk?.sha256)
    }

    @Test fun missingApkStillAllowsViewingRelease() {
        assertNull(GitHubRelease.parse("""{"tag_name":"V2.6.0","assets":[]}""").apk)
        assertNull(GitHubRelease.parse("""{"tag_name":"V2.6.0"}""").apk)
    }

    @Test fun allowsOlderGitHubAssetsWithoutDigest() {
        assertNull(GitHubRelease.parse(releaseJson().replace("\"sha256:${"a".repeat(64)}\"", "null")).apk?.sha256)
    }

    @Test fun rejectsDraftsPrereleasesAndMalformedMetadata() {
        listOf(
            releaseJson().replace("\"draft\":false", "\"draft\":true"),
            releaseJson().replace("\"prerelease\":false", "\"prerelease\":true"),
            releaseJson().replace("V2.6.0", "V2.6.0-beta.1"),
            releaseJson().replace("V2.6.0", "latest"),
            releaseJson().replace("\"size\":123", "\"size\":0"),
            releaseJson().replace("sha256:${"a".repeat(64)}", "sha256:broken"),
            "null", "{}",
        ).forEach { json -> assertTrue(json, runCatching { GitHubRelease.parse(json) }.isFailure) }
    }

    @Test fun rejectsApksOutsideTheOfficialRelease() {
        listOf(
            "http://github.com/nessli420/aurora/releases/download/V2.6.0/Aurora.apk",
            "https://example.com/nessli420/aurora/releases/download/V2.6.0/Aurora.apk",
            "https://github.com/someone/aurora/releases/download/V2.6.0/Aurora.apk",
            "https://github.com/nessli420/aurora/releases/download/V2.5.0/Aurora.apk",
        ).forEach { url ->
            assertTrue(runCatching { GitHubRelease.parse(releaseJson(url)) }.isFailure)
        }
    }

    @Test fun prefersUniversalAssetAndDoesNotGuessBetweenSplitApks() {
        val arm = asset("Aurora-arm64.apk")
        val x86 = asset("Aurora-x86_64.apk")
        val universal = asset("Aurora-universal.apk")
        assertNull(GitHubRelease.parse("""{"tag_name":"V2.6.0","assets":[$arm,$x86]}""").apk)
        assertTrue(GitHubRelease.parse("""{"tag_name":"V2.6.0","assets":[$arm,$universal,$x86]}""")
            .apk!!.url.endsWith("Aurora-universal.apk"))
    }

    private fun version(text: String) = requireNotNull(AppVersion.parse(text))
    private fun asset(name: String) = """{"name":"$name","size":123,"browser_download_url":"https://github.com/nessli420/aurora/releases/download/V2.6.0/$name"}"""
    private fun releaseJson(url: String = "https://github.com/nessli420/aurora/releases/download/V2.6.0/Aurora.apk") =
        """{"tag_name":"V2.6.0","draft":false,"prerelease":false,"assets":[{"name":"Aurora.apk","size":123,"browser_download_url":"$url","digest":"sha256:${"a".repeat(64)}"}]}"""
}
