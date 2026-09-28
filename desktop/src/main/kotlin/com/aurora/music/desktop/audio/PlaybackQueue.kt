package com.aurora.music.desktop.audio

import com.aurora.music.model.Song
import kotlin.random.Random

class PlaybackQueue(private val random: Random = Random.Default) {
    private val items = ArrayList<QueueEntry>()
    private var nextUid = 1L
    private var original: List<Long>? = null
    private var restoreCache: Pair<Long, List<String>?>? = null

    var entries: List<QueueEntry> = emptyList(); private set
    var index = -1; private set
    var shuffle = false; private set
    var version = 0L; private set
    var removedPastEnd = false; private set
    var repeat = RepeatMode.OFF
        set(value) { if (field != value) { field = value; changed() } }

    val size: Int get() = items.size
    val current: QueueEntry? get() = items.getOrNull(index)
    val restoreIds: List<String>?
        get() {
            restoreCache?.takeIf { it.first == version }?.let { return it.second }
            val byUid = items.associate { it.uid to it.song.id }
            return original?.mapNotNull { byUid[it] }.also { restoreCache = version to it }
        }

    operator fun get(index: Int): QueueEntry = items[index]

    fun indexOf(uid: Long): Int = items.indexOfFirst { it.uid == uid }

    fun select(index: Int) {
        require(index in items.indices) { "Queue index out of range" }
        if (this.index != index) { this.index = index; changed() }
    }

    fun set(songs: List<Song>, startIndex: Int) {
        items.clear()
        songs.mapTo(items) { entry(it) }
        index = if (items.isEmpty()) -1 else startIndex.coerceIn(items.indices)
        original = if (shuffle) items.map { it.uid } else null
        changed()
    }

    fun insert(at: Int, songs: List<Song>) {
        if (songs.isEmpty()) return
        val position = at.coerceIn(0, items.size)
        items.addAll(position, songs.map { entry(it) })
        if (index < 0) index = 0 else if (position <= index) index += songs.size
        changed()
    }

    fun append(songs: List<Song>) = insert(items.size, songs)

    fun move(from: Int, to: Int) {
        require(from in items.indices && to in items.indices) { "Queue index out of range" }
        if (from == to) return
        items.add(to, items.removeAt(from))
        index = when {
            index == from -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        changed()
    }

    fun remove(from: Int, to: Int = from + 1) {
        val start = from.coerceIn(0, items.size)
        val end = to.coerceIn(start, items.size)
        removedPastEnd = false
        if (start == end) return
        items.subList(start, end).clear()
        index = when {
            items.isEmpty() -> -1
            index >= end -> index - (end - start)
            index < start -> index
            start < items.size -> start
            repeat == RepeatMode.ALL -> 0
            else -> (items.size - 1).also { removedPastEnd = true }
        }
        changed()
    }

    fun replace(at: Int, song: Song) {
        require(at in items.indices) { "Queue index out of range" }
        val old = items[at]
        items[at] = if (old.song.streamUrl == song.streamUrl) old.copy(song = song) else entry(song)
        changed()
    }

    fun clear() {
        items.clear()
        index = -1
        original = if (shuffle) emptyList() else null
        changed()
    }

    fun next(from: Int = index, auto: Boolean = false): Int = when {
        from !in items.indices -> -1
        auto && repeat == RepeatMode.ONE -> from
        from + 1 < items.size -> from + 1
        repeat == RepeatMode.ALL -> 0
        else -> -1
    }

    fun previous(from: Int = index): Int = when {
        from !in items.indices -> -1
        from > 0 -> from - 1
        repeat == RepeatMode.ALL -> items.size - 1
        else -> -1
    }

    fun setShuffle(target: ShuffleTarget, providedOrder: List<String>? = null) {
        val enable = when (target) {
            ShuffleTarget.ON -> true
            ShuffleTarget.OFF -> false
            ShuffleTarget.TOGGLE -> !shuffle
        }
        if (providedOrder == null && enable == shuffle && enable == (original != null)) return
        when {
            enable && providedOrder != null -> original = uidsFor(providedOrder)
            enable -> {
                original = items.map { it.uid }
                val current = current
                if (items.size > 1) reorder(listOfNotNull(current) + items.filter { it !== current }.shuffled(random))
            }
            else -> {
                original?.let { order ->
                    val rank = order.withIndex().associate { (position, uid) -> uid to position }
                    reorder(items.filter { it.uid in rank }.sortedBy { rank.getValue(it.uid) } + items.filter { it.uid !in rank })
                }
                original = null
            }
        }
        shuffle = enable
        changed()
    }

    private fun uidsFor(ids: List<String>): List<Long> {
        val unused = items.groupByTo(HashMap(), { it.song.id }, { it.uid })
        return ids.mapNotNull { id -> unused[id]?.takeIf { it.isNotEmpty() }?.removeAt(0) }
    }

    private fun reorder(order: List<QueueEntry>) {
        val currentUid = current?.uid
        items.clear()
        items.addAll(order)
        index = if (currentUid == null) index else indexOf(currentUid)
    }

    private fun entry(song: Song) = QueueEntry(nextUid++, song)

    private fun changed() {
        version++
        entries = items.toList()
    }
}
