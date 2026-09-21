package com.aurora.music.data.updates

import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppUpdaterDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val updater = AppUpdater(context)

    @Test fun rejectsTruncatedTamperedAndMislabeledApks() {
        val installedApk = File(context.applicationInfo.sourceDir)
        val version = requireNotNull(AppVersion.parse(BuildConfig.VERSION_NAME))
        val release = AppRelease("V${BuildConfig.VERSION_NAME}", version, GitHubRelease.RELEASES_URL,
            ReleaseApk("", installedApk.length(), null))
        val truncated = release.copy(apk = release.apk!!.copy(size = installedApk.length() + 1))
        assertTrue(runCatching { updater.validateApk(truncated, installedApk) }.exceptionOrNull()?.message.orEmpty().contains("incomplete"))
        val tampered = release.copy(apk = release.apk.copy(sha256 = "0".repeat(64)))
        assertTrue(runCatching { updater.validateApk(tampered, installedApk) }.exceptionOrNull()?.message.orEmpty().contains("verified"))
        // A correctly signed APK must still have a higher version code.
        assertTrue(runCatching { updater.validateApk(release, installedApk) }.exceptionOrNull()?.message.orEmpty().contains("version information"))
        val foreignApk = File(instrumentation.context.applicationInfo.sourceDir)
        val foreign = release.copy(apk = release.apk.copy(size = foreignApk.length()))
        assertTrue(runCatching { updater.validateApk(foreign, foreignApk) }.exceptionOrNull()?.message.orEmpty().contains("signing key"))
    }

    @Test fun providerOnlySharesTheUpdateDirectory() {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        val allowed = File(root, "updates/provider-test.apk").apply { parentFile!!.mkdirs(); writeText("test") }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", allowed)
            assertEquals("content", uri.scheme)
            assertEquals("application/vnd.android.package-archive", context.contentResolver.getType(uri))
            assertEquals("test", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertTrue(runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.updates", File(root, "private.txt"))
            }.isFailure)
        } finally {
            allowed.delete()
        }
    }
}
