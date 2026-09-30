package com.aurora.music.ui.uxaudit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.aurora.music.data.LocalProfile
import com.aurora.music.data.PlaybackCollectionIdentity
import com.aurora.music.data.Pin
import com.aurora.music.data.remote.ArtistInfo
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.platform.desktopFileUri
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.ui.AuroraRoot
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.model.Song
import com.aurora.music.ui.AuroraApp
import com.aurora.music.ui.layout.TabletMetrics
import com.aurora.music.ui.layout.WindowLayout
import com.aurora.music.ui.testing.EdtScene
import com.aurora.music.ui.testing.edt
import com.aurora.music.ui.testing.saveTo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.Assert.fail
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.reflect.KClass

internal class AuditPlayer : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    override val outputs = MutableStateFlow(listOf(
        AudioDevice("speakers", "Speakers (Realtek(R) Audio)", DeviceKind.SPEAKERS, true),
        AudioDevice("dac", "FiiO K7 USB DAC", DeviceKind.HEADPHONES, false),
    ))
    override val preferredOutput = MutableStateFlow<String?>(null)
    override val exclusiveOutput = MutableStateFlow(false)
    override val volume = MutableStateFlow(0.72f)

    override fun setExpanded(value: Boolean) = state.update { it.copy(expanded = value) }
    override fun togglePlay() = state.update { it.copy(isPlaying = !it.isPlaying) }
    override fun next() = Unit
    override fun previous() = Unit
    override fun toggleLikeCurrent() = Unit
    override fun refreshLikes() = Unit
    override fun playAll(songs: List<Song>, startIndex: Int, collection: PlaybackCollectionIdentity?) = Unit
    override fun play(song: Song) = Unit
    override fun playCollection(kind: String, id: String, loaded: List<Song>, startIndex: Int, total: Int) = Unit
    override fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int) = Unit
    override fun shufflePlay(songs: List<Song>, collection: PlaybackCollectionIdentity?) = Unit
    override fun startSonicRadio(seed: Song, onResult: (String) -> Unit) = Unit
    override fun startAutoDj(seed: Song, onResult: (String) -> Unit) = Unit
    override fun addToQueue(song: Song) = Unit
    override fun playNext(song: Song) = Unit
    override fun jumpTo(index: Int) = Unit
    override fun removeFromQueue(index: Int) = Unit
    override fun clearQueue() = Unit
    override fun moveQueueItem(from: Int, to: Int) = Unit
    override fun saveQueueAsPlaylist(name: String, onResult: (String) -> Unit) = Unit
    override fun seekTo(fraction: Float) = Unit
    override fun toggleShuffle() = Unit
    override fun cycleRepeat() = Unit
    override fun checkLiked(ids: List<String>) = Unit
    override fun toggleLike(id: String, kind: String) = Unit
    override fun setSpeed(value: Float) = Unit
    override fun resetSpeedPitch() = Unit
    override fun setSleepTimer(minutes: Int) = Unit
    override fun setSleepEndOfTrack() = Unit
    override fun setPreferredDevice(deviceId: String?) = Unit
    override fun setExclusiveOutput(enabled: Boolean) = Unit
    override fun setVolume(value: Float) { volume.value = value }
    override fun stopPlayback() = Unit
}

@OptIn(DelicateCoilApi::class)
internal class AuditScene(
    private val label: String,
    val width: Int,
    val height: Int,
    signedIn: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) : AutoCloseable {
    private val root = Files.createTempDirectory("aurora-ux").toFile()
    val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
    val player = AuditPlayer()
    val shortcuts = MutableSharedFlow<Shortcut>(extraBufferCapacity = 8)
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private var fullscreen by mutableStateOf(false)
    lateinit var nav: NavHostController
        private set
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private val ui: EdtScene
    val songs: List<Song> get() = container.folderLibrary.songs

    val route: String? get() = edt { nav.currentDestination?.route }

    init {
        SingletonImageLoader.setUnsafe(container.imageLoader)
        runBlocking {
            with(container) {
                settingsStore.setLrclibEnabled(false)
                settingsStore.setArtworkLookupEnabled(false)
                settingsStore.setLocalProfile(LocalProfile(name = "Alex Rivera"))
                desktopSettings.setMusicFolders(listOf(AuditLibrary.music.path))
                if (signedIn) seed()
            }
        }
        ui = EdtScene(width, height) {
            val controller = rememberNavController()
            nav = controller
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalViewModelStoreOwner provides owner) {
                AuroraRoot(container, player) {
                    if (content != null) content()
                    else AuroraApp(navController = controller, shortcuts = shortcuts, fullscreen = fullscreen, onFullscreenChange = { fullscreen = it })
                }
            }
        }
    }

    private suspend fun DesktopContainer.seed() {
        applySession(authenticator.local())
        folderLibrary.refresh()
        val library = folderLibrary
        val backend = backend!!
        val random = Random(11)
        val all = library.songs
        AuditLibrary.playlists.forEach { (name, count) ->
            val id = backend.createPlaylistWithId(name)!!
            backend.addToPlaylist(id, all.shuffled(random).take(count).map { it.id })
        }
        val liked = all.shuffled(random).take(36)
        liked.forEach { backend.setStarred(it.id, true, "song") }
        val now = System.currentTimeMillis()
        all.shuffled(random).take(140).forEachIndexed { i, song -> playHistory.record(song, now - i * 2_700_000L) }
        library.artists.forEach { artist ->
            artistInfoStore.put(artist.name, ArtistInfo(
                name = artist.name, bio = AuditLibrary.bio(artist.name),
                imageUrl = AuditLibrary.portraitFor(artist.name)?.let { desktopFileUri(it.path) }.orEmpty(),
                tags = listOf("indie", "electronic", "dream pop"), country = "United Kingdom", yearsActive = "2014 – present",
                found = true,
            ))
        }
        library.albums.take(2).forEach { album ->
            settingsStore.togglePin(Pin(album.id, "album", album.title, album.artist, album.artworkUrl, backend.session.server))
        }
        val nocturne = library.albums.first { it.title == "Nocturne" }
        val queue = library.songsByAlbumId(nocturne.id) + library.songs.filter { it.album == "Neon Hours" }.take(5)
        player.state.value = PlayerUiState(
            current = queue[2], queue = queue, currentIndex = 2, isPlaying = true, positionSec = 74f,
            likedIds = liked.map { it.id }.toSet() + queue[2].id,
        )
    }

    fun settle(realMillis: Long = 900): Image {
        val until = System.currentTimeMillis() + realMillis
        var image = ui.frame(32)
        while (System.currentTimeMillis() < until) {
            Thread.sleep(15)
            image = ui.frame(32)
        }
        return image
    }

    fun awaitRoute(expected: String, timeoutMillis: Long = 15_000) {
        val until = System.currentTimeMillis() + timeoutMillis
        while (route != expected) {
            if (System.currentTimeMillis() > until) fail("route never became $expected, last $route")
            settle(50)
        }
        settle(500)
    }

    fun click(x: Float, y: Float) {
        ui.click(x, y)
        settle(500)
    }

    fun scroll(x: Float, y: Float, amount: Float) {
        ui.input {
            sendPointerEvent(PointerEventType.Move, Offset(x, y), timeMillis = ui.millis)
            sendPointerEvent(PointerEventType.Scroll, Offset(x, y), scrollDelta = Offset(0f, amount), timeMillis = ui.millis)
        }
        settle(600)
    }

    val navWidth: Float get() = (if (WindowLayout(width, height).canExpandSidebar) TabletMetrics.SidebarWidth else TabletMetrics.RailWidth).value

    val gutter: Float get() = WindowLayout(width, height).pageGutter.value

    val contentLeft: Float get() = navWidth + TabletMetrics.NavGap.value + gutter

    fun hover(x: Float, y: Float) {
        ui.input { sendPointerEvent(PointerEventType.Move, Offset(x, y), timeMillis = ui.millis) }
        settle(400)
    }

    fun navigate(route: String, pattern: String = route) {
        edt { nav.navigate(route) }
        awaitRoute(pattern)
    }

    fun press(shortcut: Shortcut) {
        check(shortcuts.tryEmit(shortcut))
        settle(500)
    }

    fun <T : ViewModel> entryViewModel(type: KClass<T>): T = edt {
        ViewModelProvider.create(nav.currentBackStackEntry!!.viewModelStore, NoFactory)[type]
    }

    fun <T : ViewModel> rootViewModel(type: KClass<T>): T = edt { ViewModelProvider.create(owner.viewModelStore, NoFactory)[type] }

    fun shot(name: String, realMillis: Long = 1_400): Image =
        settle(realMillis).saveTo(System.getenv("AURORA_SHOTS")?.let { File(it, "ux-audit") }, "${width}x$height-$label-$name")

    override fun close() {
        ui.close()
        container.close()
        runBlocking { container.scope.coroutineContext.job.join() }
        root.deleteRecursively()
    }

    private object NoFactory : ViewModelProvider.Factory
}
