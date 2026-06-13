package com.decent.usbaudio.media3

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream

/**
 * Media3 [DataSource] for SFTP streaming with native offset seek.
 *
 * Uses JSch directly (not VFS) for data reads — this gives us
 * `ChannelSftp.get(path, null, offset)` which sends SSH_FXP_READ
 * at the exact byte offset. Seek is O(1), no bytes discarded.
 *
 * The SSH session is cached across seeks for the same file.
 * Only SFTP is supported; HTTP/HTTPS use ExoPlayer's default DataSource.
 *
 * Usage: register [Factory] via [DecentDataSourceFactory].
 */
@OptIn(UnstableApi::class)
class SftpDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var session: Session? = null
    private var channel: ChannelSftp? = null
    private var inputStream: InputStream? = null
    private var bytesRemaining: Long = 0
    private var opened: Boolean = false
    private var dataSpec: DataSpec? = null

    // Cache for reuse across seeks
    private var cachedHost: String? = null
    private var cachedUser: String? = null
    private var cachedPass: String? = null
    private var cachedPath: String? = null
    private var cachedFileSize: Long = -1

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        val rawUri = dataSpec.uri.toString()
        val decoded = java.net.URLDecoder.decode(rawUri, "UTF-8")

        try {
            // Parse SFTP URI manually (java.net.URI rejects [] in paths)
            val withoutScheme = decoded.removePrefix("sftp://")
            val atIdx = withoutScheme.indexOf("@")
            val userInfoStr = withoutScheme.substring(0, atIdx)
            val hostAndPath = withoutScheme.substring(atIdx + 1)
            val user = userInfoStr.substringBefore(":")
            val pass = userInfoStr.substringAfter(":", "")
            val firstSlash = hostAndPath.indexOf("/")
            val hostPort = if (firstSlash >= 0) hostAndPath.substring(0, firstSlash) else hostAndPath
            var path = if (firstSlash >= 0) hostAndPath.substring(firstSlash) else "/"
            val host = hostPort.substringBefore(":")
            val port = hostPort.substringAfter(":", "22").toIntOrNull() ?: 22

            // Try to reuse cached session
            val needNewSession = session == null || !session!!.isConnected
                    || cachedHost != host || cachedUser != user

            if (needNewSession) {
                closeSession()
                Log.i(TAG, "New SSH session to $host:$port as $user")
                val jsch = JSch()
                val sess = jsch.getSession(user, host, port)
                sess.setPassword(pass)
                sess.setConfig("StrictHostKeyChecking", "no")
                sess.setConfig("PreferredAuthentications", "password,keyboard-interactive")
                sess.connect(15000)

                val ch = sess.openChannel("sftp") as ChannelSftp
                ch.connect(10000)

                session = sess
                channel = ch
                cachedHost = host
                cachedUser = user
                cachedPass = pass
                cachedPath = null
                cachedFileSize = -1
            }

            var ch = channel!!

            // Resolve path (handle SFTP chroot)
            if (cachedPath == null || cachedPath != path) {
                // Try path as-is first
                var resolved = false
                try {
                    ch.stat(path)
                    resolved = true
                } catch (_: Exception) {}

                if (!resolved) {
                    // Strip /home/user/ for chroot
                    val relative = path.replace(Regex("^/home/[^/]+/"), "/")
                    Log.w(TAG, "Absolute path failed, trying: $relative")
                    try {
                        ch.stat(relative)
                        path = relative
                        resolved = true
                    } catch (_: Exception) {}
                }

                if (!resolved) {
                    throw java.io.FileNotFoundException("SFTP file not found: $path")
                }

                cachedPath = path
                cachedFileSize = ch.stat(path).size
            }

            val fileSize = cachedFileSize

            // Close previous stream
            try { inputStream?.close() } catch (_: Exception) {}
            inputStream = null

            // Try reusing channel first (fast path), reconnect if stale
            ch = channel!!
            try {
                inputStream = ch.get(cachedPath, null, dataSpec.position)
            } catch (_: Exception) {
                // Channel stale after interrupted read — reconnect
                Log.w(TAG, "Channel stale, reconnecting...")
                try { ch.disconnect() } catch (_: Exception) {}
                val fresh = session!!.openChannel("sftp") as ChannelSftp
                fresh.connect(10000)
                channel = fresh
                inputStream = fresh.get(cachedPath, null, dataSpec.position)
            }

            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                fileSize - dataSpec.position
            }

            opened = true
            transferStarted(dataSpec)
            Log.i(TAG, "open: pos=${dataSpec.position} size=$fileSize remaining=$bytesRemaining")
            return bytesRemaining

        } catch (e: Exception) {
            Log.e(TAG, "open failed: ${e.message}", e)
            throw java.io.IOException("SFTP open failed", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        val bytesRead = stream.read(buffer, offset, toRead)
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        try {
            inputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close stream error: ${e.message}")
        } finally {
            inputStream = null
            if (opened) {
                opened = false
                transferEnded()
            }
            // Keep session/channel alive for seek reuse
        }
    }

    private fun closeSession() {
        try {
            inputStream?.close()
            channel?.disconnect()
            session?.disconnect()
        } catch (_: Exception) {}
        inputStream = null
        channel = null
        session = null
        cachedHost = null
        cachedPath = null
        cachedFileSize = -1
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SftpDataSource()
    }

    companion object {
        private const val TAG = "SftpDataSource"
        val SUPPORTED_SCHEMES = setOf("sftp", "ftp", "ftps")
        fun supportsUri(uri: Uri): Boolean = uri.scheme?.lowercase() in SUPPORTED_SCHEMES
    }
}
