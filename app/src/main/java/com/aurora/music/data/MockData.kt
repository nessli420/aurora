package com.aurora.music.data

import androidx.compose.ui.graphics.Color
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.LyricLine
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song

object MockData {

    private fun art(seed: String) = "https://picsum.photos/seed/$seed/500/500"

    val accents = listOf(
        Color(0xFFFF2E7E), Color(0xFFFF7A59), Color(0xFFC24CE0),
        Color(0xFFFB7185), Color(0xFFF7B733), Color(0xFFA855F7),
        Color(0xFFFF5C8A), Color(0xFFFF8E6E),
    )

    val songs: List<Song> = listOf(
        Song("s1", "Midnight Bloom", "Lunar Tide", "Nocturne", art("aurora1"), 214, liked = true, accent = accents[0]),
        Song("s2", "Velvet Skyline", "Mara Quinn", "Neon Hours", art("aurora2"), 187, accent = accents[1]),
        Song("s3", "Paper Planes", "The Foxgloves", "Wildflower", art("aurora3"), 241, liked = true, accent = accents[2]),
        Song("s4", "Gravity", "Aerial", "Lightyears", art("aurora4"), 199, explicit = true, accent = accents[3]),
        Song("s5", "Saltwater", "Coastlines", "Tidal", art("aurora5"), 263, accent = accents[4]),
        Song("s6", "Ember", "Wren Holloway", "Slow Burn", art("aurora6"), 176, liked = true, accent = accents[5]),
        Song("s7", "Cassette Dreams", "Polaroid Kids", "Analog", art("aurora7"), 224, accent = accents[6]),
        Song("s8", "Northern Lights", "Glacier", "Aurora", art("aurora8"), 252, accent = accents[7]),
        Song("s9", "Honeyglow", "Marigold", "Sundrop", art("aurora9"), 208, accent = accents[0]),
        Song("s10", "Static Heart", "Neon Vows", "Frequency", art("aurora10"), 231, explicit = true, accent = accents[1]),
        Song("s11", "Driftwood", "Coastlines", "Tidal", art("aurora11"), 195, accent = accents[2]),
        Song("s12", "After Hours", "Mara Quinn", "Neon Hours", art("aurora12"), 218, liked = true, accent = accents[3]),
    )

    fun songById(id: String) = songs.firstOrNull { it.id == id } ?: songs.first()

    val playlists: List<Playlist> = listOf(
        Playlist("p1", "Daily Mix 1", "Lunar Tide, Aerial and more", art("mix1"), 50, accents[0]),
        Playlist("p2", "Late Night Drive", "Synthwave after dark", art("mix2"), 42, accents[1]),
        Playlist("p3", "Focus Flow", "Instrumental concentration", art("mix3"), 80, accents[3]),
        Playlist("p4", "Morning Coffee", "Easy acoustic mornings", art("mix4"), 36, accents[4]),
        Playlist("p5", "Throwback Gold", "Hits you forgot you loved", art("mix5"), 64, accents[2]),
        Playlist("p6", "Rainy Day", "Mellow & moody", art("mix6"), 28, accents[6]),
    )

    val madeForYou: List<Playlist> = listOf(
        Playlist("m1", "Discover Weekly", "Your weekly mixtape", art("dw1"), 30, accents[1]),
        Playlist("m2", "Release Radar", "New from artists you follow", art("dw2"), 30, accents[0]),
        Playlist("m3", "On Repeat", "The songs you can't stop", art("dw3"), 40, accents[2]),
        Playlist("m4", "Time Capsule", "A trip down memory lane", art("dw4"), 50, accents[5]),
    )

    val albums: List<Album> = listOf(
        Album("a1", "Nocturne", "Lunar Tide", art("alb1"), 2024, 11),
        Album("a2", "Neon Hours", "Mara Quinn", art("alb2"), 2023, 12),
        Album("a3", "Wildflower", "The Foxgloves", art("alb3"), 2022, 10),
        Album("a4", "Lightyears", "Aerial", art("alb4"), 2024, 9),
        Album("a5", "Tidal", "Coastlines", art("alb5"), 2021, 13),
        Album("a6", "Analog", "Polaroid Kids", art("alb6"), 2023, 8),
    )

    val artists: List<Artist> = listOf(
        Artist("ar1", "Lunar Tide", art("art1"), 2_480_000),
        Artist("ar2", "Mara Quinn", art("art2"), 5_120_000),
        Artist("ar3", "The Foxgloves", art("art3"), 980_000),
        Artist("ar4", "Aerial", art("art4"), 3_340_000),
        Artist("ar5", "Coastlines", art("art5"), 1_760_000),
        Artist("ar6", "Glacier", art("art6"), 640_000),
    )

    val recentlyPlayed: List<Playlist> = listOf(
        Playlist("r1", "Liked Songs", "248 songs", art("liked"), 248, accents[1]),
        Playlist("r2", "Nocturne", "Lunar Tide", art("alb1"), 11, accents[0]),
        Playlist("r3", "Focus Flow", "Instrumental", art("mix3"), 80, accents[3]),
        Playlist("r4", "Neon Hours", "Mara Quinn", art("alb2"), 12, accents[2]),
        Playlist("r5", "Daily Mix 1", "For you", art("mix1"), 50, accents[4]),
        Playlist("r6", "Release Radar", "New music", art("dw2"), 30, accents[5]),
    )

    val genres: List<Pair<String, Color>> = listOf(
        "Pop" to accents[2], "Hip-Hop" to accents[1], "Rock" to accents[5],
        "Indie" to accents[0], "Electronic" to accents[3], "Jazz" to accents[4],
        "Lo-Fi" to accents[6], "R&B" to accents[7], "Classical" to Color(0xFF60A5FA),
        "Metal" to Color(0xFFEF4444), "Country" to Color(0xFFFBBF24), "Workout" to Color(0xFF22D3EE),
    )

    fun formatListeners(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.0fK".format(n / 1_000.0)
        else -> n.toString()
    }

    fun detailFor(kind: String, id: String): DetailInfo {
        (playlists + madeForYou + recentlyPlayed).firstOrNull { it.id == id }?.let {
            return DetailInfo(it.title, it.subtitle, it.coverUrl, it.accent, false, it.songCount, "Playlist")
        }
        albums.firstOrNull { it.id == id }?.let {
            return DetailInfo(it.title, "${it.artist} • ${it.year}", it.artworkUrl, accents[it.year % accents.size], false, it.songCount, "Album")
        }
        artists.firstOrNull { it.id == id }?.let {
            return DetailInfo(it.name, "${formatListeners(it.monthlyListeners)} monthly listeners", it.imageUrl, accents[0], true, 0, "Artist")
        }
        if (kind == "genre") {
            val idx = id.toIntOrNull() ?: 0
            val (name, color) = genres.getOrElse(idx) { genres.first() }
            return DetailInfo(name, "Top tracks in $name", "https://picsum.photos/seed/genre$idx/600", color, false, 50, "Genre")
        }
        return DetailInfo(
            id.replaceFirstChar { it.uppercase() },
            kind.replaceFirstChar { it.uppercase() },
            "https://picsum.photos/seed/$id/600",
            accents[0],
            kind == "artist",
            songs.size,
            kind.replaceFirstChar { it.uppercase() },
        )
    }

    fun tracksFor(id: String): List<Song> {
        val start = (id.hashCode().mod(songs.size))
        return (songs.drop(start) + songs.take(start))
    }

    val lyrics: List<LyricLine> = listOf(
        LyricLine(0, "♪"),
        LyricLine(6, "Pull the curtains, let the midnight in"),
        LyricLine(12, "Neon shadows dancing on your skin"),
        LyricLine(18, "We were chasing colors in the dark"),
        LyricLine(24, "Every heartbeat leaving its own spark"),
        LyricLine(31, "And we bloom, in the midnight"),
        LyricLine(37, "Like the city when it's half awake"),
        LyricLine(43, "And we bloom, out of plain sight"),
        LyricLine(49, "Every petal is a chance we take"),
        LyricLine(56, "Hold the moment 'til the morning comes"),
        LyricLine(62, "Quiet thunder of a million suns"),
        LyricLine(68, "Don't let go, don't let the feeling fade"),
        LyricLine(74, "This is everything we've ever made"),
        LyricLine(81, "And we bloom, in the midnight"),
        LyricLine(87, "Like the city when it's half awake"),
        LyricLine(93, "And we bloom, out of plain sight"),
        LyricLine(99, "Every petal is a chance we take"),
    )
}
