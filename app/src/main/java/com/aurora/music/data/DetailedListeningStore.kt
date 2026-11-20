package com.aurora.music.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

// lives in no-backup storage so it never leaves the device through cloud backup; created on first use only
class DetailedListeningStore(context: Context) : DetailedListeningRepository {
    private val file = File(context.noBackupFilesDir, "listening_details.db")
    private val helper = object : SQLiteOpenHelper(context.applicationContext, file.path, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE)
            db.execSQL("CREATE INDEX plays_started ON plays(started_at)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                LOCATION_COLUMNS.forEach { db.execSQL("ALTER TABLE plays ADD COLUMN $it REAL") }
                // version 1 marked every crossfaded track as skipped
                db.execSQL("UPDATE plays SET ending = 'COMPLETED' WHERE ending = 'SKIPPED' AND duration_sec > 0 AND listened_ms >= duration_sec * 900")
            }
        }
    }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "aurora-insights") }
    private val dispatcher = worker.asCoroutineDispatcher()

    override fun save(play: DetailedPlay) {
        worker.execute { runCatching { helper.writableDatabase.insertWithOnConflict("plays", null, values(play), SQLiteDatabase.CONFLICT_REPLACE) } }
    }

    override suspend fun summary(): InsightsSummary = withContext(dispatcher) {
        if (!file.exists()) return@withContext InsightsSummary()
        runCatching {
            helper.readableDatabase.rawQuery("SELECT COUNT(*), MIN(started_at) FROM plays", null).use { c ->
                if (c.moveToFirst()) InsightsSummary(c.getInt(0), if (c.isNull(1)) null else c.getLong(1)) else InsightsSummary()
            }
        }.getOrDefault(InsightsSummary())
    }

    override suspend fun importHistory(events: List<PlayEvent>): Int = withContext(dispatcher) {
        val plays = events.mapNotNull { it.toImportedPlay("android") }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            plays.forEach { db.insertWithOnConflict("plays", null, values(it), SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        plays.size
    }

    override suspend fun removeHistory(events: List<PlayEvent>) = withContext(dispatcher) {
        if (!file.exists()) return@withContext
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            events.forEach { db.delete("plays", "id = ?", arrayOf(it.importedPlayId)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun clear() = withContext(dispatcher) {
        helper.close()
        SQLiteDatabase.deleteDatabase(file)
        Unit
    }

    override suspend fun plays(from: Long, to: Long): List<DetailedPlay> = withContext(dispatcher) {
        if (!file.exists()) return@withContext emptyList()
        helper.readableDatabase.rawQuery("SELECT * FROM plays WHERE started_at >= ? AND started_at < ? ORDER BY started_at",
            arrayOf(from.toString(), to.toString())).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
    }

    suspend fun located(from: Long = 0, to: Long = Long.MAX_VALUE, songId: String? = null): List<DetailedPlay> = withContext(dispatcher) {
        if (!file.exists()) return@withContext emptyList()
        val song = if (songId == null) "" else " AND song_id = ?"
        val args = listOfNotNull(from.toString(), to.toString(), songId).toTypedArray()
        helper.readableDatabase.rawQuery("SELECT * FROM plays WHERE latitude IS NOT NULL AND started_at >= ? AND started_at < ?$song ORDER BY started_at", args)
            .use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
    }

    suspend fun locationCount(): Int = withContext(dispatcher) {
        if (!file.exists()) return@withContext 0
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM plays WHERE latitude IS NOT NULL", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    // the plays and their statistics stay, only the coordinates go
    suspend fun clearLocations() = withContext(dispatcher) {
        if (!file.exists()) return@withContext
        helper.writableDatabase.execSQL("UPDATE plays SET latitude = NULL, longitude = NULL, location_accuracy = NULL")
    }

    override suspend fun firstArtistPlays(): Map<String, Long> = withContext(dispatcher) {
        if (!file.exists()) return@withContext emptyMap()
        val first = HashMap<String, Long>()
        helper.readableDatabase.rawQuery("SELECT artist, MIN(started_at) FROM plays GROUP BY artist", null).use { c ->
            while (c.moveToNext()) first.merge(Insights.artistKey(c.getString(0)), c.getLong(1), ::minOf)
        }
        first
    }

    private fun row(c: Cursor): DetailedPlay {
        fun text(name: String) = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getString(it) }
        fun int(name: String) = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getInt(it) }
        fun flag(name: String) = int(name)?.let { it != 0 }
        fun long(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
        fun double(name: String) = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getDouble(it) }
        return DetailedPlay(
            id = text("id").orEmpty(), startedAt = long("started_at"), endedAt = long("ended_at"), listenedMs = long("listened_ms"),
            track = PlayedTrack(text("song_id").orEmpty(), text("title").orEmpty(), text("artist").orEmpty(), text("album").orEmpty(),
                text("album_id").orEmpty(), text("artist_id").orEmpty(), int("duration_sec") ?: 0),
            context = PlayContext(text("genre"), text("source"), text("provider"), text("playlist"), flag("shuffle"), int("repeat_mode"),
                text("output"), text("codec"), int("sample_rate"), int("bit_depth"), flag("lossless"), text("dsp_engine"), flag("autoeq"),
                flag("convolution"), int("replaygain"), flag("crossfeed"), flag("rack"), flag("bit_perfect"), text("zone"),
                double("latitude"), double("longitude"), double("location_accuracy")?.toFloat()),
            ending = text("ending")?.let { runCatching { PlayEnding.valueOf(it) }.getOrNull() },
            platform = text("platform") ?: "android", imported = flag("imported") == true,
        )
    }

    private fun values(play: DetailedPlay) = ContentValues().apply {
        val c = play.context
        fun flag(value: Boolean?) = value?.let { if (it) 1 else 0 }
        put("id", play.id)
        put("started_at", play.startedAt)
        put("ended_at", play.endedAt)
        put("listened_ms", play.listenedMs)
        put("song_id", play.track.songId)
        put("title", play.track.title)
        put("artist", play.track.artist)
        put("album", play.track.album)
        put("album_id", play.track.albumId)
        put("artist_id", play.track.artistId)
        put("duration_sec", play.track.durationSec)
        put("genre", c.genre)
        put("source", c.source)
        put("provider", c.provider)
        put("playlist", c.playlist)
        put("shuffle", flag(c.shuffle))
        put("repeat_mode", c.repeat)
        put("ending", play.ending?.name)
        put("output", c.output)
        put("codec", c.codec)
        put("sample_rate", c.sampleRateHz)
        put("bit_depth", c.bitDepth)
        put("lossless", flag(c.lossless))
        put("dsp_engine", c.dspEngine)
        put("autoeq", flag(c.autoEq))
        put("convolution", flag(c.convolution))
        put("replaygain", c.replayGain)
        put("crossfeed", flag(c.crossfeed))
        put("rack", flag(c.rack))
        put("bit_perfect", flag(c.bitPerfect))
        put("zone", c.zone)
        put("latitude", c.latitude)
        put("longitude", c.longitude)
        put("location_accuracy", c.locationAccuracyM?.toDouble())
        put("platform", play.platform)
        put("imported", if (play.imported) 1 else 0)
    }

    private companion object {
        const val VERSION = 2
        val LOCATION_COLUMNS = listOf("latitude", "longitude", "location_accuracy")
        const val CREATE = """CREATE TABLE plays (
            id TEXT PRIMARY KEY, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, listened_ms INTEGER NOT NULL,
            song_id TEXT NOT NULL, title TEXT, artist TEXT, album TEXT, album_id TEXT, artist_id TEXT, duration_sec INTEGER,
            genre TEXT, source TEXT, provider TEXT, playlist TEXT, shuffle INTEGER, repeat_mode INTEGER, ending TEXT,
            output TEXT, codec TEXT, sample_rate INTEGER, bit_depth INTEGER, lossless INTEGER,
            dsp_engine TEXT, autoeq INTEGER, convolution INTEGER, replaygain INTEGER, crossfeed INTEGER, rack INTEGER, bit_perfect INTEGER,
            zone TEXT, platform TEXT NOT NULL, imported INTEGER NOT NULL DEFAULT 0,
            latitude REAL, longitude REAL, location_accuracy REAL)"""
    }
}
