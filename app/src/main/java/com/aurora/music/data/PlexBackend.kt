package com.aurora.music.data

import com.aurora.music.data.remote.PlexClient
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.model.inferReleaseType
import com.aurora.music.model.releaseTypeLabel
import com.aurora.music.util.accentFor

class PlexBackend(
    private val client: PlexClient,
    private val maxBitrateProvider: () -> Int,
    private val localize: (Song) -> Song,
) : MediaBackend {
    override val session: Session get() = client.session
    private val streams = ConcurrentHashMap<String, String>()
    private val artwork = ConcurrentHashMap<String, String>()


        return localize(Song(
            id = id,
            accent = accentFor(id),
        ))
    }

    }

    }

    }

            }
            if (result.size >= limit) break
        }
        return result
    }


    }



    override suspend fun search(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        return SearchResults(
        )
    }


    override suspend fun detail(kind: String, id: String): DetailData? {
        if (kind == "liked") {
            val tracks = starredSongs()
        }
        }
        }
    }
        }
        return client.allMetadata(path).ofType("track").map { it.toSong() }
    }


}
