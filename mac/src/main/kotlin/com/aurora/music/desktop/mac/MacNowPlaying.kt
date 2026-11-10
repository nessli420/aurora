package com.aurora.music.desktop.mac

import com.aurora.music.desktop.natives.MediaSession
import com.aurora.music.desktop.natives.SmtcButton
import com.aurora.music.desktop.natives.SmtcRepeat
import com.aurora.music.desktop.natives.SmtcStatus
import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Media keys, the Now Playing widget in Control Center and headphone buttons, through the media player framework:
 * MPNowPlayingInfoCenter shows what plays and MPRemoteCommandCenter hands the buttons back.
 */
class MacNowPlaying private constructor(private val callbacks: MediaSession.Callbacks) : MediaSession {
    private val lib = "MediaPlayer"
    private val center: Pointer
    private val commands: Pointer
    private val target: Pointer
    private val registered = mutableListOf<Pair<Pointer, String>>()
    private val artwork = Artwork()
    @Volatile private var closed = false

    // the last full set of values, so a timeline or status change can republish everything without the caller
    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var albumArtist: String? = null
    private var positionMs = 0L
    private var durationMs = 0L
    private var playing = false

    init {
        NativeLibrary.getInstance(lib) // loads the framework, so its classes and constants exist
        center = checkNotNull(ObjC.msg(ObjC.cls("MPNowPlayingInfoCenter"), "defaultCenter"))
        commands = checkNotNull(ObjC.msg(ObjC.cls("MPRemoteCommandCenter"), "sharedCommandCenter"))
        target = checkNotNull(ObjC.msg(ObjC.msg(targetClass, "alloc"), "init"))
        current = this
    }

    // every remote command calls one method of the target, which looks at which command sent it
    private fun wire() {
        listOf("playCommand", "pauseCommand", "togglePlayPauseCommand", "stopCommand", "nextTrackCommand",
            "previousTrackCommand", "changePlaybackPositionCommand", "changeShuffleModeCommand", "changeRepeatModeCommand",
        ).forEach { name ->
            val command = ObjC.msg(commands, name) ?: return@forEach
            ObjC.msgVoid(command, "setEnabled:", true)
            ObjC.msg(command, "addTarget:action:", target, ObjC.sel("handle:"))
            registered += command to name
        }
    }

    private fun handle(event: Pointer): Long {
        val command = ObjC.msg(event, "command") ?: return FAILED
        val name = registered.firstOrNull { it.first == command }?.second ?: return FAILED
        when (name) {
            "playCommand" -> callbacks.onButton(SmtcButton.PLAY)
            "pauseCommand", "stopCommand" -> callbacks.onButton(SmtcButton.PAUSE)
            "togglePlayPauseCommand" -> callbacks.onButton(if (playing) SmtcButton.PAUSE else SmtcButton.PLAY)
            "nextTrackCommand" -> callbacks.onButton(SmtcButton.NEXT)
            "previousTrackCommand" -> callbacks.onButton(SmtcButton.PREVIOUS)
            "changePlaybackPositionCommand" -> callbacks.onSeek((ObjC.msgDouble(event, "positionTime") * 1000).toLong())
            "changeShuffleModeCommand" -> callbacks.onShuffle(ObjC.msgLong(event, "shuffleType") != 0L)
            "changeRepeatModeCommand" -> callbacks.onRepeat(when (ObjC.msgLong(event, "repeatType")) {
                1L -> SmtcRepeat.TRACK
                2L -> SmtcRepeat.LIST
                else -> SmtcRepeat.NONE
            })
        }
        return SUCCESS
    }

    override fun metadata(title: String?, artist: String?, album: String?, albumArtist: String?, thumbnail: ByteArray?): Boolean = post {
        this.title = title
        this.artist = artist
        this.album = album
        this.albumArtist = albumArtist
        artwork.set(thumbnail)
        publish()
    }

    override fun status(status: SmtcStatus): Boolean = post {
        playing = status == SmtcStatus.PLAYING
        ObjC.msgVoid(center, "setPlaybackState:", when (status) {
            SmtcStatus.PLAYING -> 1L
            SmtcStatus.PAUSED -> 2L
            else -> 3L
        })
        publish()
    }

    override fun timeline(positionMs: Long, durationMs: Long): Boolean = post {
        this.positionMs = positionMs
        this.durationMs = durationMs
        publish()
    }

    override fun buttons(): Boolean = post { wire() }

    override fun shuffle(enabled: Boolean): Boolean = post {
        ObjC.msg(commands, "changeShuffleModeCommand")?.let { ObjC.msgVoid(it, "setCurrentShuffleType:", if (enabled) 1L else 0L) }
    }

    override fun repeat(mode: SmtcRepeat): Boolean = post {
        ObjC.msg(commands, "changeRepeatModeCommand")?.let {
            ObjC.msgVoid(it, "setCurrentRepeatType:", when (mode) {
                SmtcRepeat.NONE -> 0L
                SmtcRepeat.TRACK -> 1L
                SmtcRepeat.LIST -> 2L
            })
        }
    }

    private fun publish() {
        if (title == null) {
            ObjC.msg(center, "setNowPlayingInfo:", null)
            return
        }
        val info = checkNotNull(ObjC.msg(ObjC.cls("NSMutableDictionary"), "dictionary"))
        fun put(key: String, value: Pointer?) { if (value != null) ObjC.msg(info, "setObject:forKey:", value, ObjC.constant(lib, key)) }
        put("MPMediaItemPropertyTitle", title?.let(ObjC::string))
        put("MPMediaItemPropertyArtist", artist?.takeIf(String::isNotBlank)?.let(ObjC::string))
        put("MPMediaItemPropertyAlbumTitle", album?.takeIf(String::isNotBlank)?.let(ObjC::string))
        put("MPMediaItemPropertyAlbumArtist", albumArtist?.takeIf(String::isNotBlank)?.let(ObjC::string))
        put("MPMediaItemPropertyPlaybackDuration", ObjC.number(durationMs / 1000.0))
        put("MPNowPlayingInfoPropertyElapsedPlaybackTime", ObjC.number(positionMs / 1000.0))
        put("MPNowPlayingInfoPropertyPlaybackRate", ObjC.number(if (playing) 1.0 else 0.0))
        put("MPNowPlayingInfoPropertyMediaType", ObjC.number(1L)) // audio
        put("MPMediaItemPropertyArtwork", artwork.item)
        ObjC.msg(center, "setNowPlayingInfo:", info)
    }

    private fun post(block: () -> Unit): Boolean {
        if (closed) return false
        ObjC.onMain { if (!closed) block() }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        ObjC.onMain {
            registered.forEach { (command, _) -> ObjC.msg(command, "removeTarget:", target) }
            registered.clear()
            ObjC.msg(center, "setNowPlayingInfo:", null)
            ObjC.msgVoid(center, "setPlaybackState:", 3L)
            artwork.set(null)
            ObjC.release(target)
            if (current === this) current = null
        }
    }

    /** The cover as an MPMediaItemArtwork, whose image comes from a block the framework calls when it draws. */
    private class Artwork {
        private var image: Pointer? = null
        var item: Pointer? = null
            private set

        fun set(bytes: ByteArray?) {
            ObjC.release(item)
            ObjC.release(image)
            item = null
            image = bytes?.takeIf { it.isNotEmpty() }?.let { ObjC.msg(ObjC.msg(ObjC.cls("NSImage"), "alloc"), "initWithData:", ObjC.data(it)) }
            val drawn = image ?: return
            shown = drawn
            val size = sizeOf(drawn)
            item = ObjC.msg(ObjC.msg(ObjC.cls("MPMediaItemArtwork"), "alloc"), "initWithBoundsSize:requestHandler:", size, block)
        }

        private fun sizeOf(image: Pointer): CGSize.ByValue {
            // [image size] returns a CGSize, two doubles, which arm64 hands back in registers like any small struct
            val size = sizeFunction.invoke(CGSize.ByValue::class.java, arrayOf(image, ObjC.sel("size"))) as CGSize.ByValue
            return CGSize.ByValue().also {
                it.width = size.width.takeIf { w -> w > 0 } ?: 512.0
                it.height = size.height.takeIf { h -> h > 0 } ?: 512.0
            }
        }
    }

    @Structure.FieldOrder("width", "height")
    open class CGSize : Structure() {
        @JvmField var width = 0.0
        @JvmField var height = 0.0

        class ByValue : CGSize(), Structure.ByValue
    }

    fun interface ImageRequest : Callback {
        fun invoke(block: Pointer, size: CGSize.ByValue): Pointer?
    }

    fun interface Handler : Callback {
        fun invoke(self: Pointer, cmd: Pointer, event: Pointer): Long
    }

    companion object {
        private const val SUCCESS = 0L
        private const val FAILED = 200L // MPRemoteCommandHandlerStatusCommandFailed

        @Volatile private var current: MacNowPlaying? = null
        @Volatile private var shown: Pointer? = null

        private val sizeFunction = NativeLibrary.getInstance("objc").getFunction("objc_msgSend")

        // one objective-c class for the whole app; its single method routes to whichever session is current
        private val handler = Handler { _, _, event -> current?.handle(event) ?: FAILED }
        private val targetClass: Pointer by lazy { ObjC.subclass("AuroraRemoteCommandTarget", mapOf("handle:" to (handler to "q@:@"))) }

        // a global block, the kind with nothing captured: the framework may copy it, which leaves it as it is
        private val request = ImageRequest { _, _ -> shown }
        private val signature = Memory(32).apply { setString(0, "@32@?0{CGSize=dd}8") }
        private val descriptor = Memory(24).apply {
            setLong(0, 0)
            setLong(8, 32)
            setPointer(16, signature)
        }
        private val block = Memory(32).apply {
            setPointer(0, NativeLibrary.getInstance("System").getGlobalVariableAddress("_NSConcreteGlobalBlock"))
            setInt(8, (1 shl 28) or (1 shl 30)) // BLOCK_IS_GLOBAL | BLOCK_HAS_SIGNATURE
            setInt(12, 0)
            setPointer(16, com.sun.jna.CallbackReference.getFunctionPointer(request))
            setPointer(24, descriptor)
        }

        fun create(callbacks: MediaSession.Callbacks): MacNowPlaying {
            var created: MacNowPlaying? = null
            var failure: Throwable? = null
            val done = java.util.concurrent.CountDownLatch(1)
            ObjC.onMain {
                try {
                    created = MacNowPlaying(callbacks)
                } catch (e: Throwable) {
                    failure = e
                } finally {
                    done.countDown()
                }
            }
            check(done.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "The main thread did not answer" }
            failure?.let { throw it }
            return checkNotNull(created)
        }
    }

}
