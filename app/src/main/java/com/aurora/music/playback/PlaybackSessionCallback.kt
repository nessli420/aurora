package com.aurora.music.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.aurora.music.playback.PlaybackService.Companion.CMD_EXIT_MIX
import com.aurora.music.playback.PlaybackService.Companion.CMD_QUEUE_APPEND
import com.aurora.music.playback.PlaybackService.Companion.CMD_REPEAT
import com.aurora.music.playback.PlaybackService.Companion.CMD_SHUFFLE
import com.aurora.music.playback.PlaybackService.Companion.CMD_SLEEP_FADE
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@UnstableApi
internal class PlaybackSessionCallback(
    private val browser: () -> PlaybackLibraryBrowser,
    private val scope: CoroutineScope,
    private val queueReplaced: (Player) -> Unit,
    private val disconnected: () -> Unit,
    private val command: (MediaSession, SessionCommand, Bundle) -> ListenableFuture<SessionResult>,
) : MediaLibrarySession.Callback {
    private val libraryBrowser get() = browser()
    private var platformQueueShown = true
    private val androidAutoControllers = mutableSetOf<MediaSession.ControllerInfo>()
    val androidAuto: Boolean get() = androidAutoControllers.isNotEmpty()

    // must keep library commands or android auto browser connection is refused
    private val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
        .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
        .add(SessionCommand(CMD_SLEEP_FADE, Bundle.EMPTY))
        .add(SessionCommand(CMD_EXIT_MIX, Bundle.EMPTY))
        .add(SessionCommand(CMD_QUEUE_APPEND, Bundle.EMPTY))
        .build()

    private fun platformPlayerCommands(): Player.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
        .apply { if (!platformQueueShown) remove(Player.COMMAND_GET_TIMELINE) }.build()

    // shortcut: every system listener copies each platform queue change (6k items ran bluetooth oom), so big queues are hidden; window them if media3 allows
    fun gatePlatformQueue(session: MediaSession?, count: Int) {
        session ?: return
        val shown = count <= PLATFORM_QUEUE_MAX
        if (shown == platformQueueShown) return
        val notification = session.mediaNotificationControllerInfo ?: return
        platformQueueShown = shown
        session.setAvailableCommands(notification, sessionCommands, platformPlayerCommands())
    }

    fun clearControllers() = androidAutoControllers.clear()

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        if (controller.packageName == "com.google.android.projection.gearhead") androidAutoControllers += controller
        val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(sessionCommands)
        if (session.isMediaNotificationController(controller)) {
            platformQueueShown = session.player.mediaItemCount <= PLATFORM_QUEUE_MAX
            result.setAvailablePlayerCommands(platformPlayerCommands())
        }
        return result.build()
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        androidAutoControllers -= controller
        disconnected()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> = command(session, customCommand, args)

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return Futures.immediateFuture(LibraryResult.ofItem(libraryBrowser.root(), params))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceFuture {
        LibraryResult.ofItemList(libraryBrowser.children(parentId, page, pageSize).also { libraryBrowser.grantArtwork(browser, it) }, params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = serviceFuture {
        val item = libraryBrowser.resolvePlayable(mediaId)
        if (item != null) LibraryResult.ofItem(item.also { libraryBrowser.grantArtwork(browser, listOf(it)) }, null)
        else LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        if (mediaItems.all { it.localConfiguration != null }) return Futures.immediateFuture(mediaItems)
        return serviceFuture {
            mediaItems.map { item ->
                if (item.localConfiguration != null) item else libraryBrowser.resolvePlayable(item.mediaId) ?: item
            }.toMutableList()
        }
    }

    // a tapped song queues the list it came from, a voice request queues its search
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val request = mediaItems.singleOrNull()?.takeIf { it.localConfiguration == null }
            ?: return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        val query = request.requestMetadata.searchQuery
        val listing = if (query != null) null else libraryBrowser.listingWith(request.mediaId)
            ?: return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        // a failed future keeps the queue, an empty list would wipe it
        if (query?.isBlank() == true) {
            if (mediaSession.player.currentMediaItem != null) mediaSession.player.play()
            return Futures.immediateFailedFuture(UnsupportedOperationException("blank search"))
        }
        return serviceFuture {
            val queue = listing?.let { libraryBrowser.queue(it, request.mediaId) } ?: MediaSession.MediaItemsWithStartPosition(
                libraryBrowser.searchQueue(query.orEmpty()).ifEmpty { throw NoSuchElementException("no songs match the search") },
                0, C.TIME_UNSET)
            queueReplaced(mediaSession.player)
            queue
        }
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = serviceFuture {
        val items = libraryBrowser.search(query)
        session.notifySearchResultChanged(browser, query, items.size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceFuture {
        val items = libraryBrowser.searchResult(query, page, pageSize)
        LibraryResult.ofItemList(items.also { libraryBrowser.grantArtwork(browser, it) }, params)
    }

    private fun <T> serviceFuture(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch { runCatching { f.set(block()) }.onFailure { f.setException(it) } }
        return f
    }

    companion object {
        private const val PLATFORM_QUEUE_MAX = 500

        fun customLayout(active: Player): List<CommandButton> {
            val shuffleBtn = CommandButton.Builder(
                if (active.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
            )
                .setDisplayName("Shuffle")
                .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .build()
            val repeatIcon = when (active.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            }
            val repeatBtn = CommandButton.Builder(repeatIcon)
                .setDisplayName("Repeat")
                .setSessionCommand(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .build()
            return listOf(shuffleBtn, repeatBtn)
        }

        fun nextRepeatMode(mode: Int): Int = when (mode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}
