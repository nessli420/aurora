package com.aurora.music.data.updates

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.aurora.music.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class UpdateDownload { IDLE, DOWNLOADING, VERIFYING, READY }

data class AppUpdateState(
    val release: AppRelease? = null,
    val checking: Boolean = false,
    val checked: Boolean = false,
    val download: UpdateDownload = UpdateDownload.IDLE,
    val progress: Float? = null,
    val waitingForNetwork: Boolean = false,
    val error: String? = null,
) {
    val updateAvailable: Boolean get() = release?.version?.let {
        val installed = AppVersion.parse(BuildConfig.VERSION_NAME)
        installed != null && it > installed
    } == true
}

class AppUpdater(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var lastCheck = 0L
    private var releaseJson: String? = null
    private val apkFile: File get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates/Aurora.apk")

    init {
        val saved = prefs.getString("release", null)
        val id = prefs.getLong("download_id", -1)
        if (saved != null && id != -1L) {
            val release = runCatching { GitHubRelease.parse(saved) }.getOrNull()
            val restored = AppUpdateState(release = release, checked = true)
            if (release?.apk != null && restored.updateAvailable) {
                releaseJson = saved
                mutableState.value = restored.copy(download = UpdateDownload.DOWNLOADING)
                downloadJob = scope.launch { monitorDownload(id, release) }
            } else {
                mutableState.update { it.copy(download = UpdateDownload.VERIFYING) }
                downloadJob = scope.launch {
                    withContext(Dispatchers.IO) { clearDownload() }
                    mutableState.update { it.copy(download = UpdateDownload.IDLE) }
                    checkForUpdate()
                }
            }
        }
    }

    fun checkForUpdate(force: Boolean = false) {
        if (checkJob?.isActive == true || state.value.download != UpdateDownload.IDLE) return
        if (!force && lastCheck != 0L && SystemClock.elapsedRealtime() - lastCheck < TimeUnit.HOURS.toMillis(1)) return
        lastCheck = SystemClock.elapsedRealtime()
        mutableState.update { it.copy(checking = true, error = null) }
        checkJob = scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(GitHubRelease.LATEST_API)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "Aurora/${BuildConfig.VERSION_NAME}").build()
                    client.newCall(request).execute().use { response ->
                        if (response.code == 403 || response.code == 429) throw IOException("GitHub is busy. Try again later.")
                        if (response.code == 404) throw IOException("No public release is available yet.")
                        if (!response.isSuccessful) throw IOException("Couldn't check for updates. Try again later.")
                        response.body?.string() ?: throw IOException("GitHub returned an empty response.")
                    }
                }
                val release = GitHubRelease.parse(json)
                releaseJson = json
                mutableState.update { it.copy(release = release, checking = false, checked = true, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(checking = false, error = when (e) {
                    is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "Couldn't reach GitHub. Check your connection and try again."
                    is IOException -> e.message ?: "Couldn't check for updates. Try again."
                    else -> "Couldn't read the GitHub release. Try again later."
                }) }
            }
        }
    }

    fun downloadUpdate() {
        val current = state.value
        val release = current.release ?: return
        val apk = release.apk ?: return
        if (!current.updateAvailable || current.checking || current.download != UpdateDownload.IDLE || downloadJob?.isActive == true) return
        mutableState.update { it.copy(download = UpdateDownload.DOWNLOADING, progress = null, error = null) }
        downloadJob = scope.launch {
            try {
                val id = withContext(Dispatchers.IO + NonCancellable) {
                    clearDownload()
                    check(apkFile.parentFile?.mkdirs() == true || apkFile.parentFile?.isDirectory == true)
                    val request = DownloadManager.Request(Uri.parse(apk.url))
                        .setTitle("Aurora ${release.tag}")
                        .setDescription("App update")
                        .setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/Aurora.apk")
                    downloads.enqueue(request).also { downloadId ->
                        prefs.edit().putLong("download_id", downloadId).putString("release", releaseJson).commit()
                    }
                }
                monitorDownload(id, release)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(download = UpdateDownload.IDLE, error = "Couldn't start the download. Check available storage and try again.") }
            }
        }
    }

    fun cancelDownload() {
        if (state.value.download == UpdateDownload.VERIFYING) return
        val previous = downloadJob
        previous?.cancel()
        // Keep actions disabled until the previous download and its file have been removed.
        mutableState.update { it.copy(download = UpdateDownload.VERIFYING) }
        downloadJob = scope.launch {
            previous?.join()
            withContext(Dispatchers.IO) { clearDownload() }
            mutableState.update { it.copy(download = UpdateDownload.IDLE, progress = null, waitingForNetwork = false, error = null) }
        }
    }

    fun reportInstallError() {
        mutableState.update { it.copy(error = "Couldn't open the installer. You can also download the update from GitHub.") }
    }

    fun installerIntent(): Intent? {
        if (state.value.download != UpdateDownload.READY || !apkFile.isFile) {
            mutableState.update { it.copy(download = UpdateDownload.IDLE, error = "The downloaded update is missing. Download it again.") }
            return null
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apkFile)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private suspend fun monitorDownload(id: Long, release: AppRelease) {
        try {
            while (true) {
                val status = withContext(Dispatchers.IO) {
                    downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        check(cursor.moveToFirst()) { "The download was removed. Try again." }
                        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        Triple(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), downloaded, total)
                    }
                }
                when (status.first) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        mutableState.update { it.copy(download = UpdateDownload.VERIFYING, progress = 1f, waitingForNetwork = false) }
                        withContext(Dispatchers.IO) { validateApk(release) }
                        mutableState.update { it.copy(download = UpdateDownload.READY) }
                        return
                    }
                    DownloadManager.STATUS_FAILED -> error("Download failed. Check your connection and storage, then try again.")
                    else -> mutableState.update { it.copy(
                        progress = if (status.third > 0) (status.second.toFloat() / status.third).coerceIn(0f, 1f) else null,
                        waitingForNetwork = status.first == DownloadManager.STATUS_PAUSED,
                    ) }
                }
                delay(750)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { clearDownload() }
            mutableState.update { it.copy(download = UpdateDownload.IDLE, progress = null, error = e.message ?: "Couldn't download the update. Try again.") }
        }
    }

    @Suppress("DEPRECATION")
    internal fun validateApk(release: AppRelease, file: File = apkFile) {
        val asset = requireNotNull(release.apk)
        check(file.isFile && file.length() == asset.size) { "The download is incomplete. Please try again." }
        asset.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expected, ignoreCase = true)) { "The download could not be verified. Please try again." }
        }
        val manager = context.packageManager
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: error("The download isn't a valid app update.")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        check(candidate.packageName == context.packageName && candidate.signatures?.isNotEmpty() == true &&
            candidate.signatures?.toSet() == installed.signatures?.toSet()) {
            "This update doesn't match the installed app's signing key. Install updates from the same source."
        }
        check(AppVersion.parse(candidate.versionName.orEmpty()) == release.version && candidate.versionCode > installed.versionCode) {
            "This release's APK has incompatible version information. Please check the GitHub release."
        }
    }

    private fun clearDownload() {
        val id = prefs.getLong("download_id", -1)
        if (id != -1L) runCatching { downloads.remove(id) }
        prefs.edit().clear().commit()
        apkFile.delete()
    }
}
