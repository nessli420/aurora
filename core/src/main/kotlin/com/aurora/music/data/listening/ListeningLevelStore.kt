package com.aurora.music.data.listening

import com.aurora.music.data.persistBackupFileAtomically
import com.aurora.music.data.routes.OutputDeviceCategory
import com.aurora.music.data.routes.ProcessingRouteKind
import com.aurora.music.data.routes.ProcessingRouteMonitor
import com.aurora.music.data.routes.RouteObservation
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ListeningLevelStore(private val file: File, private val routes: ProcessingRouteMonitor) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    private val measured = MutableStateFlow<ListeningEstimate>(ListeningEstimate.Unavailable("Start playback to measure listening levels."))
    val estimate = measured.asStateFlow()
    private val volume = MutableStateFlow<Int?>(null)
    val volumeStep = volume.asStateFlow()
    private val maximumVolume = MutableStateFlow<Int?>(null)
    val volumeMaximum = maximumVolume.asStateFlow()
    private var confirmation: Pair<String, Long>? = null
    private var lastObservation: ListeningObservation? = null
    private var lastHistoryNanos: Long? = null
    private var revision = 0L
    internal class BackupRollback internal constructor(internal val previous: ListeningState, internal val revision: Long)

    init { scope.launch { for (ignored in writes) saveCurrent() } }

    fun observe(observation: ListeningObservation) = synchronized(lock) {
        lastObservation = observation
        volume.value = observation.volumeIndex
        maximumVolume.value = observation.volumeMaximum
        if (confirmation?.second != observation.route.generation || observation.route != routes.current) confirmation = null
        refresh(observation)
        val result = measured.value as? ListeningEstimate.Available ?: return@synchronized
        if (!mutable.value.historyEnabled || !observation.allowHistory || result.maximumDb == null) return@synchronized
        val previous = lastHistoryNanos
        if (previous != null && observation.nowNanos - previous in 0 until 30_000_000_000L) return@synchronized
        lastHistoryNanos = observation.nowNanos
        val entry = ListeningHistoryEntry(result.timestampMillis, result.profileName, result.leftDb, result.rightDb, result.uncertaintyDb)
        revision++
        mutable.value = mutable.value.copy(history = (listOf(entry) + mutable.value.history).take(ListeningLevelCodec.MAX_HISTORY))
        writes.trySend(Unit)
    }

    suspend fun saveProfile(profile: ListeningProfile, expected: RouteObservation): Result<Unit> = mutate {
        requireExpectedRoute(expected)
        require(lastObservation?.route == expected && lastObservation?.volumeMaximum == profile.volumeMaximum) {
            "The Android volume range changed. Reopen the calibration."
        }
        val validated = ListeningMath.validate(profile.copy(routeKey = expected.route.key, routeLabel = expected.route.label))
        val profiles = mutable.value.profiles.filterNot { it.id == validated.id } + validated
        require(profiles.size <= ListeningLevelCodec.MAX_PROFILES) { "Remove a calibration before adding another." }
        confirmation = null
        mutable.value.copy(profiles = profiles)
    }

    fun confirm(profileId: String, expected: RouteObservation): Result<Unit> = runCatching {
        synchronized(lock) {
            requireExpectedRoute(expected)
            val profile = mutable.value.profiles.singleOrNull { it.id == profileId } ?: error("Calibration no longer exists.")
            require(profile.routeKey == expected.route.key) { "Bind this calibration to the current output first." }
            confirmation = profile.id to expected.generation
            lastObservation?.let(::refresh)
            Unit
        }
    }

    fun invalidateConfirmation() = synchronized(lock) {
        confirmation = null
        measured.value = ListeningEstimate.Unavailable("Confirm the headphones, gain and hardware volume.")
    }

    suspend fun removeProfile(id: String): Result<Unit> = mutate {
        if (confirmation?.first == id) confirmation = null
        mutable.value.copy(profiles = mutable.value.profiles.filterNot { it.id == id })
    }

    suspend fun setHistoryEnabled(enabled: Boolean): Result<Unit> = mutate {
        lastHistoryNanos = null
        mutable.value.copy(historyEnabled = enabled)
    }

    suspend fun clearHistory(): Result<Unit> = mutate {
        lastHistoryNanos = null
        mutable.value.copy(history = emptyList())
    }

    fun exportProfiles(): String = synchronized(lock) { ListeningLevelCodec.encode(mutable.value, portable = true) }

    suspend fun importProfiles(json: String): Result<Unit> = runCatching { ListeningLevelCodec.decode(json, portable = true) }
        .fold(onSuccess = { imported -> mutate {
            val combined = (mutable.value.profiles + imported.profiles).associateBy { it.id }.values.toList()
            require(combined.size <= ListeningLevelCodec.MAX_PROFILES) { "Too many calibrations." }
            confirmation = null
            mutable.value.copy(profiles = combined)
        } }, onFailure = { Result.failure(it) })

    internal suspend fun replaceBackupProfiles(json: String): BackupRollback = withContext(Dispatchers.IO) {
        val imported = ListeningLevelCodec.decode(json, portable = true)
        synchronized(lock) {
            val previous = mutable.value
            val next = previous.copy(profiles = imported.profiles, error = null)
            persistBackupFileAtomically(file, ListeningLevelCodec.encode(next).toByteArray(Charsets.UTF_8))
            mutable.value = next
            confirmation = null
            lastObservation?.let(::refresh)
            BackupRollback(previous, ++revision)
        }
    }

    internal suspend fun rollbackBackup(token: BackupRollback): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (revision != token.revision) false else {
                persistBackupFileAtomically(file, ListeningLevelCodec.encode(token.previous).toByteArray(Charsets.UTF_8))
                mutable.value = token.previous
                revision++
                confirmation = null
                lastObservation?.let(::refresh)
                true
            }
        }
    }

    private fun refresh(observation: ListeningObservation) {
        val profile = mutable.value.profiles.firstOrNull { it.id == confirmation?.first }
        measured.value = if (profile == null) ListeningEstimate.Unavailable("Confirm a calibration for this output.")
            else ListeningMath.estimate(profile, observation, confirmation?.second)
    }

    private fun requireExpectedRoute(expected: RouteObservation) {
        require(expected == routes.current && expected.route.kind == ProcessingRouteKind.ANDROID && expected.route.key != null &&
            expected.route.category in setOf(OutputDeviceCategory.HEADPHONES, OutputDeviceCategory.USB, OutputDeviceCategory.BLUETOOTH)) {
            "A stable headphone output must be playing."
        }
    }

    private suspend fun mutate(change: () -> ListeningState): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { synchronized(lock) {
            val next = change().copy(error = null)
            persistBackupFileAtomically(file, ListeningLevelCodec.encode(next).toByteArray(Charsets.UTF_8))
            revision++
            mutable.value = next
            lastObservation?.let(::refresh)
            Unit
        } }
    }

    private fun load(): ListeningState = runCatching {
        if (!file.exists()) ListeningState() else {
            require(file.length() <= ListeningLevelCodec.MAX_BYTES) { "Listening data is too large." }
            ListeningLevelCodec.decode(file.readText())
        }
    }.getOrElse { ListeningState(error = "Listening calibrations could not be loaded.") }

    private fun saveCurrent() = synchronized(lock) {
        runCatching { persistBackupFileAtomically(file, ListeningLevelCodec.encode(mutable.value).toByteArray(Charsets.UTF_8)) }
            .onFailure { mutable.value = mutable.value.copy(error = "Listening history could not be saved.") }
    }
}
