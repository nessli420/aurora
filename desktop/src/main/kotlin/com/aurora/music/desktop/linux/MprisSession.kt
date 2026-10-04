package com.aurora.music.desktop.linux

import com.aurora.music.desktop.natives.MediaSession
import com.aurora.music.desktop.natives.SmtcButton
import com.aurora.music.desktop.natives.SmtcRepeat
import com.aurora.music.desktop.natives.SmtcStatus
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.abs

class MprisSession private constructor(
    private val bus: DBusConnection,
    private val callbacks: MediaSession.Callbacks,
    private val artDir: File,
) : MediaSession {
    private val player = Player()
    private var busName: String? = null
    private var closed = false
    private var status = SmtcStatus.STOPPED
    private var repeat = SmtcRepeat.NONE
    private var shuffle = false
    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var albumArtist: String? = null
    private var artUrl: String? = null
    private var track = 0
    private var durationMs = 0L
    private var positionMs = 0L
    private var positionAt = System.nanoTime()

    @Synchronized
    override fun metadata(title: String?, artist: String?, album: String?, albumArtist: String?, thumbnail: ByteArray?): Boolean = update {
        if (title != this.title || artist != this.artist || album != this.album) track++
        this.title = title
        this.artist = artist
        this.album = album
        this.albumArtist = albumArtist
        artUrl = thumbnail?.let(::storeArt)
        changed(PLAYER, "Metadata" to Variant(metadataMap(), "a{sv}"))
    }

    @Synchronized
    override fun status(status: SmtcStatus): Boolean = update {
        positionMs = position()
        positionAt = System.nanoTime()
        this.status = status
        changed(PLAYER, "PlaybackStatus" to Variant(playbackStatus()), "CanPlay" to Variant(title != null), "CanPause" to Variant(title != null))
    }

    @Synchronized
    override fun timeline(positionMs: Long, durationMs: Long): Boolean = update {
        val jumped = abs(positionMs - position()) > SEEK_TOLERANCE_MS
        this.positionMs = positionMs
        positionAt = System.nanoTime()
        if (durationMs != this.durationMs) {
            this.durationMs = durationMs
            changed(PLAYER, "Metadata" to Variant(metadataMap(), "a{sv}"), "CanSeek" to Variant(durationMs > 0))
        }
        if (jumped) bus.sendMessage(MediaPlayer2Player.Seeked(PATH, positionMs * 1000))
    }

    override fun buttons(): Boolean = true

    @Synchronized
    override fun shuffle(enabled: Boolean): Boolean = update {
        shuffle = enabled
        changed(PLAYER, "Shuffle" to Variant(enabled))
    }

    @Synchronized
    override fun repeat(mode: SmtcRepeat): Boolean = update {
        repeat = mode
        changed(PLAYER, "LoopStatus" to Variant(loopStatus()))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { bus.unExportObject(PATH) }
        busName?.let { runCatching { bus.releaseBusName(it) } }
        artDir.deleteRecursively()
    }

    private inline fun update(block: () -> Unit): Boolean = !closed && runCatching(block).isSuccess

    private fun changed(iface: String, vararg values: Pair<String, Variant<*>>) {
        bus.sendMessage(Properties.PropertiesChanged(PATH, iface, mapOf(*values), emptyList()))
    }

    private fun position(): Long {
        if (status != SmtcStatus.PLAYING) return positionMs
        val elapsed = (System.nanoTime() - positionAt) / 1_000_000
        return (positionMs + elapsed).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
    }

    private fun playbackStatus() = when (status) {
        SmtcStatus.PLAYING -> "Playing"
        SmtcStatus.PAUSED, SmtcStatus.CHANGING -> "Paused"
        else -> "Stopped"
    }

    private fun loopStatus() = when (repeat) {
        SmtcRepeat.NONE -> "None"
        SmtcRepeat.TRACK -> "Track"
        SmtcRepeat.LIST -> "Playlist"
    }

    private fun trackId(): DBusPath = DBusPath(if (title == null) NO_TRACK else "/com/aurora/music/track/$track")

    private fun metadataMap(): Map<String, Variant<*>> = buildMap {
        put("mpris:trackid", Variant(trackId()))
        if (title == null) return@buildMap
        put("xesam:title", Variant(title))
        artist?.takeIf(String::isNotBlank)?.let { put("xesam:artist", Variant(listOf(it), "as")) }
        album?.takeIf(String::isNotBlank)?.let { put("xesam:album", Variant(it)) }
        albumArtist?.takeIf(String::isNotBlank)?.let { put("xesam:albumArtist", Variant(listOf(it), "as")) }
        if (durationMs > 0) put("mpris:length", Variant(durationMs * 1000))
        artUrl?.let { put("mpris:artUrl", Variant(it)) }
    }

    private fun storeArt(bytes: ByteArray): String {
        val name = MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
        val file = File(artDir, "$name.img")
        if (!file.isFile) {
            artDir.listFiles()?.forEach(File::delete)
            file.writeBytes(bytes)
        }
        return file.toURI().toString()
    }

    private fun properties(iface: String): Map<String, Variant<*>> = synchronized(this) {
        when (iface) {
            ROOT -> mapOf(
                "CanQuit" to Variant(false),
                "CanRaise" to Variant(true),
                "HasTrackList" to Variant(false),
                "Identity" to Variant("Aurora"),
                "DesktopEntry" to Variant(LinuxDesktop.ID),
                "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
                "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
            )
            PLAYER -> mapOf(
                "PlaybackStatus" to Variant(playbackStatus()),
                "LoopStatus" to Variant(loopStatus()),
                "Rate" to Variant(1.0),
                "Shuffle" to Variant(shuffle),
                "Metadata" to Variant(metadataMap(), "a{sv}"),
                "Volume" to Variant(1.0),
                "Position" to Variant(position() * 1000),
                "MinimumRate" to Variant(1.0),
                "MaximumRate" to Variant(1.0),
                "CanGoNext" to Variant(true),
                "CanGoPrevious" to Variant(true),
                "CanPlay" to Variant(title != null),
                "CanPause" to Variant(title != null),
                "CanSeek" to Variant(durationMs > 0),
                "CanControl" to Variant(true),
            )
            else -> emptyMap()
        }
    }

    private inner class Player : MediaPlayer2, MediaPlayer2Player, Properties {
        override fun getObjectPath() = PATH

        override fun Raise() = callbacks.onRaise()
        override fun Quit() {}

        override fun Next() = callbacks.onButton(SmtcButton.NEXT)
        override fun Previous() = callbacks.onButton(SmtcButton.PREVIOUS)
        override fun Pause() = callbacks.onButton(SmtcButton.PAUSE)
        override fun Play() = callbacks.onButton(SmtcButton.PLAY)
        override fun Stop() = callbacks.onButton(SmtcButton.STOP)
        override fun PlayPause() = callbacks.onButton(if (synchronized(this@MprisSession) { status } == SmtcStatus.PLAYING) SmtcButton.PAUSE else SmtcButton.PLAY)
        override fun OpenUri(uri: String) {}

        override fun Seek(offset: Long) {
            val (target, duration) = synchronized(this@MprisSession) { position() + offset / 1000 to durationMs }
            if (duration > 0 && target > duration) callbacks.onButton(SmtcButton.NEXT) else callbacks.onSeek(target.coerceAtLeast(0))
        }

        override fun SetPosition(trackId: DBusPath, position: Long) {
            val valid = synchronized(this@MprisSession) { trackId.path == trackId().path && position in 0..durationMs * 1000 }
            if (valid) callbacks.onSeek(position / 1000)
        }

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            properties(interfaceName)[propertyName] as A

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = properties(interfaceName)

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
            val raw = (value as? Variant<*>)?.value ?: value
            when (propertyName) {
                "Shuffle" -> (raw as? Boolean)?.let(callbacks::onShuffle)
                "LoopStatus" -> when (raw) {
                    "None" -> callbacks.onRepeat(SmtcRepeat.NONE)
                    "Track" -> callbacks.onRepeat(SmtcRepeat.TRACK)
                    "Playlist" -> callbacks.onRepeat(SmtcRepeat.LIST)
                }
            }
        }
    }

    companion object {
        private const val PATH = "/org/mpris/MediaPlayer2"
        private const val ROOT = "org.mpris.MediaPlayer2"
        private const val PLAYER = "org.mpris.MediaPlayer2.Player"
        private const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"
        private const val SEEK_TOLERANCE_MS = 1_500

        fun create(callbacks: MediaSession.Callbacks, bus: DBusConnection? = SessionBus.connection()): MprisSession {
            checkNotNull(bus) { "The session bus is unavailable" }
            val session = MprisSession(bus, callbacks, Files.createTempDirectory("aurora-mpris").toFile())
            bus.exportObject(session.player)
            val name = "$ROOT.aurora"
            session.busName = runCatching { bus.requestBusName(name) }.map { name }
                .getOrElse { "$name.instance${ProcessHandle.current().pid()}".also(bus::requestBusName) }
            return session
        }
    }
}
