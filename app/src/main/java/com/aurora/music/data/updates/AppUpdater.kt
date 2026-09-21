package com.aurora.music.data.updates

import com.aurora.music.localization.appString
import com.aurora.music.R

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
                        if (response.code == 403 || response.code == 429) throw IOException(appString(R.string.text_github_is_busy_try_again_later_83f29b))
                        if (response.code == 404) throw IOException(appString(R.string.text_no_public_release_is_available_yet_54a115))
                        if (!response.isSuccessful) throw IOException(appString(R.string.text_couldn_t_check_for_updates_try_again_later_e70d9d))
                        response.body?.string() ?: throw IOException(appString(R.string.text_github_returned_an_empty_response_06f1d4))
                    }
                }
                val release = GitHubRelease.parse(json)
                releaseJson = json
                mutableState.update { it.copy(release = release, checking = false, checked = true, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(checking = false, error = when (e) {
                    is java.net.UnknownHostException, is java.net.SocketTimeoutException -> appString(R.string.text_couldn_t_reach_github_check_your_connection_and_try_again_47aff9)
                    is IOException -> e.message ?: appString(R.string.text_couldn_t_check_for_updates_try_again_a15f1d)
                    else -> appString(R.string.text_couldn_t_read_the_github_release_try_again_later_309d84)
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
                        .setDescription(appString(R.string.text_app_update_45b5d1))
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
                mutableState.update { it.copy(download = UpdateDownload.IDLE, error = appString(R.string.text_couldn_t_start_the_download_check_available_storage_and_try_again_2afac7)) }
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
        mutableState.update { it.copy(error = appString(R.string.text_couldn_t_open_the_installer_you_can_also_download_the_update_from_c2a33f)) }
    }

    fun installerIntent(): Intent? {
        if (state.value.download != UpdateDownload.READY || !apkFile.isFile) {
            mutableState.update { it.copy(download = UpdateDownload.IDLE, error = appString(R.string.text_the_downloaded_update_is_missing_download_it_again_8a84fc)) }
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
                        check(cursor.moveToFirst()) { appString(R.string.text_the_download_was_removed_try_again_867dcc) }
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
                    DownloadManager.STATUS_FAILED -> error(appString(R.string.text_download_failed_check_your_connection_and_storage_then_try_again_3e9e00))
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
            mutableState.update { it.copy(download = UpdateDownload.IDLE, progress = null, error = e.message ?: appString(R.string.text_couldn_t_download_the_update_try_again_5d009e)) }
        }
    }

    @Suppress("DEPRECATION")
    internal fun validateApk(release: AppRelease, file: File = apkFile) {
        val asset = requireNotNull(release.apk)
        check(file.isFile && file.length() == asset.size) { appString(R.string.text_the_download_is_incomplete_please_try_again_96c9cd) }
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
            check(actual.equals(expected, ignoreCase = true)) { appString(R.string.text_the_download_could_not_be_verified_please_try_again_95c71d) }
        }
        val manager = context.packageManager
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: error(appString(R.string.text_the_download_isn_t_a_valid_app_update_d50721))
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        check(candidate.packageName == context.packageName && candidate.signatures?.isNotEmpty() == true &&
            candidate.signatures?.toSet() == installed.signatures?.toSet()) {
            appString(R.string.text_this_update_doesn_t_match_the_installed_app_s_signing_key_install_5af2e9)
        }
        check(AppVersion.parse(candidate.versionName.orEmpty()) == release.version && candidate.versionCode > installed.versionCode) {
            appString(R.string.text_this_release_s_apk_has_incompatible_version_information_please_ch_71cb07)
        }
    }

    private fun clearDownload() {
        val id = prefs.getLong("download_id", -1)
        if (id != -1L) runCatching { downloads.remove(id) }
        prefs.edit().clear().commit()
        apkFile.delete()
    }
}
