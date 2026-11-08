package com.aurora.music.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.aurora.music.data.*
import com.aurora.music.data.SettingsKeys as Keys
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

interface LibrarySettings {
    val artworkLookupEnabled: Flow<Boolean>
    val trackCovers: Flow<Boolean>
    val animatedCoversEnabled: Flow<Boolean>
    val animatedCoversFolder: Flow<String>
    val lrclibEnabled: Flow<Boolean>
    val sonicAutoAnalyze: Flow<Boolean>
    suspend fun setSonicAutoAnalyze(v: Boolean): Preferences
    val artistEnrichment: Flow<Boolean>
    suspend fun setArtistEnrichment(v: Boolean): Preferences
    val preferLocalSources: Flow<Boolean>
    suspend fun setPreferLocalSources(v: Boolean): Preferences
    val sourcePriority: Flow<List<String>>
    suspend fun setSourcePriority(order: List<String>): Preferences
    val catalogueMatch: Flow<CatalogueMatchPrefs>
    suspend fun setCatalogueLibraryCopies(v: Boolean): Preferences
    suspend fun setCatalogueMatchRelaxed(v: Boolean): Preferences
    suspend fun setCatalogueLosslessOnly(v: Boolean): Preferences
    val unifiedLibrary: Flow<Boolean>
    suspend fun setUnifiedLibrary(v: Boolean): Preferences
    val localFolders: Flow<Set<String>>
    suspend fun setLocalFolders(paths: Set<String>): Preferences
    val localExcludedFolders: Flow<Set<String>>
    suspend fun setLocalExcludedFolders(paths: Set<String>): Preferences
    val mergeSources: Flow<Set<String>>
    suspend fun setMergeSources(keys: Set<String>): Preferences
    val mergeFeatures: Flow<MergeFeatures>
    suspend fun setMergeFeatures(f: MergeFeatures): Preferences
    val lyricsSources: Flow<List<String>>
    suspend fun setLyricsSources(order: List<String>): Preferences
    suspend fun setArtworkLookupEnabled(v: Boolean): Preferences
    suspend fun setTrackCovers(v: Boolean): Preferences
    suspend fun setAnimatedCoversEnabled(v: Boolean): Preferences
    suspend fun setAnimatedCoversFolder(uri: String): Preferences
    suspend fun setLrclibEnabled(v: Boolean): Preferences
    val artistSeparators: Flow<ArtistSeparators>
    suspend fun setArtistSeparators(separators: ArtistSeparators)
}

internal class LibrarySettingsStore(private val dataStore: DataStore<Preferences>) : LibrarySettings {
    private val gson = Gson()

    override val artworkLookupEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.ARTWORK_LOOKUP] ?: true }.distinctUntilChanged()
    // off keeps one cover per album; on shows each local file's own embedded cover
    override val trackCovers: Flow<Boolean> = dataStore.data.map { it[Keys.TRACK_COVERS] ?: false }.distinctUntilChanged()
    override val animatedCoversEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.ANIMATED_COVERS] ?: true }.distinctUntilChanged()
    override val animatedCoversFolder: Flow<String> = dataStore.data.map { it[Keys.ANIMATED_COVERS_FOLDER].orEmpty() }.distinctUntilChanged()
    override val lrclibEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.LRCLIB] ?: true }.distinctUntilChanged()

    override val sonicAutoAnalyze: Flow<Boolean> = dataStore.data.map { it[Keys.SONIC_AUTO_ANALYZE] ?: false }.distinctUntilChanged()
    override suspend fun setSonicAutoAnalyze(v: Boolean) = dataStore.edit { it[Keys.SONIC_AUTO_ANALYZE] = v }

    // off keeps artist names from ever leaving the device
    override val artistEnrichment: Flow<Boolean> = dataStore.data.map { it[Keys.ARTIST_ENRICHMENT] ?: true }.distinctUntilChanged()
    override suspend fun setArtistEnrichment(v: Boolean) = dataStore.edit { it[Keys.ARTIST_ENRICHMENT] = v }

    override val preferLocalSources: Flow<Boolean> = dataStore.data.map { it[Keys.PREFER_LOCAL] ?: true }.distinctUntilChanged()
    override suspend fun setPreferLocalSources(v: Boolean) = dataStore.edit { it[Keys.PREFER_LOCAL] = v }

    override val sourcePriority: Flow<List<String>> = dataStore.data.map { it[Keys.SOURCE_PRIORITY] }.distinctUntilChanged().map { raw ->
        gson.parseList<String>(raw).filter { it in DEFAULT_SOURCE_PRIORITY }
            .ifEmpty { DEFAULT_SOURCE_PRIORITY }
    }
    override suspend fun setSourcePriority(order: List<String>) = dataStore.edit {
        it[Keys.SOURCE_PRIORITY] = gson.toJson(order.filter { t -> t in DEFAULT_SOURCE_PRIORITY }.distinct())
    }

    override val catalogueMatch: Flow<CatalogueMatchPrefs> = dataStore.data.map {
        CatalogueMatchPrefs(
            useLibrary = it[Keys.CATALOGUE_LIBRARY_COPIES] ?: true,
            relaxed = it[Keys.CATALOGUE_MATCH_RELAXED] ?: false,
            losslessOnly = it[Keys.CATALOGUE_LOSSLESS_ONLY] ?: false,
        )
    }.distinctUntilChanged()
    override suspend fun setCatalogueLibraryCopies(v: Boolean) = dataStore.edit { it[Keys.CATALOGUE_LIBRARY_COPIES] = v }
    override suspend fun setCatalogueMatchRelaxed(v: Boolean) = dataStore.edit { it[Keys.CATALOGUE_MATCH_RELAXED] = v }
    override suspend fun setCatalogueLosslessOnly(v: Boolean) = dataStore.edit { it[Keys.CATALOGUE_LOSSLESS_ONLY] = v }

    override val unifiedLibrary: Flow<Boolean> = dataStore.data.map { it[Keys.UNIFIED_LIBRARY] ?: false }.distinctUntilChanged()
    override suspend fun setUnifiedLibrary(v: Boolean) = dataStore.edit { it[Keys.UNIFIED_LIBRARY] = v }

    // empty = the whole device
    override val localFolders: Flow<Set<String>> = dataStore.data.map { it[Keys.LOCAL_FOLDERS] ?: emptySet() }.distinctUntilChanged()
    override suspend fun setLocalFolders(paths: Set<String>) = dataStore.edit { it[Keys.LOCAL_FOLDERS] = paths }
    override val localExcludedFolders: Flow<Set<String>> = dataStore.data.map { it[Keys.LOCAL_EXCLUDED_FOLDERS] ?: emptySet() }.distinctUntilChanged()
    override suspend fun setLocalExcludedFolders(paths: Set<String>) = dataStore.edit { it[Keys.LOCAL_EXCLUDED_FOLDERS] = paths }

    // empty = every eligible saved source
    override val mergeSources: Flow<Set<String>> = dataStore.data.map { it[Keys.MERGE_SOURCES] ?: emptySet() }.distinctUntilChanged()
    override suspend fun setMergeSources(keys: Set<String>) = dataStore.edit { it[Keys.MERGE_SOURCES] = keys }

    override val mergeFeatures: Flow<MergeFeatures> = dataStore.data.map {
        MergeFeatures(
            canvas = it[Keys.MERGE_CANVAS] ?: true, canvasOff = it[Keys.MERGE_CANVAS_OFF] ?: emptySet(),
            videos = it[Keys.MERGE_VIDEOS] ?: true, videosOff = it[Keys.MERGE_VIDEOS_OFF] ?: emptySet(),
            comments = it[Keys.MERGE_COMMENTS] ?: true, commentsOff = it[Keys.MERGE_COMMENTS_OFF] ?: emptySet(),
            lyrics = it[Keys.MERGE_LYRICS] ?: true, lyricsOff = it[Keys.MERGE_LYRICS_OFF] ?: emptySet(),
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
    override suspend fun setMergeFeatures(f: MergeFeatures) = dataStore.edit {
        it[Keys.MERGE_CANVAS] = f.canvas; it[Keys.MERGE_CANVAS_OFF] = f.canvasOff
        it[Keys.MERGE_VIDEOS] = f.videos; it[Keys.MERGE_VIDEOS_OFF] = f.videosOff
        it[Keys.MERGE_COMMENTS] = f.comments; it[Keys.MERGE_COMMENTS_OFF] = f.commentsOff
        it[Keys.MERGE_LYRICS] = f.lyrics; it[Keys.MERGE_LYRICS_OFF] = f.lyricsOff
    }

    // a saved order keeps any source added later, at the end
    override val lyricsSources: Flow<List<String>> = dataStore.data.map { it[Keys.LYRICS_SOURCES] }.distinctUntilChanged().map { raw ->
        val saved = gson.parseList<String>(raw).filter { it in DEFAULT_LYRICS_SOURCES }.distinct()
        saved + DEFAULT_LYRICS_SOURCES.filterNot { it in saved }
    }
    override suspend fun setLyricsSources(order: List<String>) = dataStore.edit {
        it[Keys.LYRICS_SOURCES] = gson.toJson(order.filter { s -> s in DEFAULT_LYRICS_SOURCES }.distinct())
    }

    override suspend fun setArtworkLookupEnabled(v: Boolean) = dataStore.edit { it[Keys.ARTWORK_LOOKUP] = v }
    override suspend fun setTrackCovers(v: Boolean) = dataStore.edit { it[Keys.TRACK_COVERS] = v }
    override suspend fun setAnimatedCoversEnabled(v: Boolean) = dataStore.edit { it[Keys.ANIMATED_COVERS] = v }
    override suspend fun setAnimatedCoversFolder(uri: String) = dataStore.edit { it[Keys.ANIMATED_COVERS_FOLDER] = uri }
    override suspend fun setLrclibEnabled(v: Boolean) = dataStore.edit { it[Keys.LRCLIB] = v }

    private val separatorsMemo = RawMemo { raw -> runCatching { ArtistSeparatorsCodec.decode(raw) }.getOrDefault(ArtistSeparators()) }

    override val artistSeparators: Flow<ArtistSeparators> = dataStore.data.map { it[Keys.ARTIST_SEPARATORS] }.distinctUntilChanged()
        .map { separatorsMemo(it) }.distinctUntilChanged()

    override suspend fun setArtistSeparators(separators: ArtistSeparators) {
        val encoded = ArtistSeparatorsCodec.encode(separators)
        dataStore.edit { it[Keys.ARTIST_SEPARATORS] = encoded }
    }
}
