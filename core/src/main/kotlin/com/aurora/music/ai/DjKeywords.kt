package com.aurora.music.ai

data class DjVocabulary(val genres: List<String> = emptyList(), val artists: List<String> = emptyList())

data class DjParse(val intent: DjIntent, val found: Set<String>)

object DjKeywords {
    private class Mood(val energy: Int, val tempo: DjTempo = DjTempo.ANY, val genres: List<String> = emptyList(), val vocals: DjVocals = DjVocals.ANY)

    private val moods = listOf(
        listOf("late night", "midnight") to Mood(3, DjTempo.SLOW, listOf("chill", "lo fi", "ambient")),
        listOf("feel good", "good vibes") to Mood(7, genres = listOf("pop", "funk", "soul")),
        listOf("road trip", "drive", "driving") to Mood(6, genres = listOf("rock", "pop", "indie")),
        listOf("wake up", "morning") to Mood(5, genres = listOf("acoustic", "pop", "indie")),
        listOf("sleep", "sleeping", "bedtime") to Mood(1, DjTempo.SLOW, listOf("ambient", "classical", "piano"), DjVocals.LESS),
        listOf("study", "studying", "focus", "concentrate", "concentration", "reading") to Mood(3, DjTempo.SLOW, listOf("lo fi", "ambient", "classical"), DjVocals.LESS),
        listOf("work", "working", "coding") to Mood(4, genres = listOf("lo fi", "electronic", "indie")),
        listOf("chill", "chilled", "chillout", "laid back") to Mood(3, DjTempo.SLOW, listOf("chill", "lo fi", "ambient", "acoustic")),
        listOf("relax", "relaxing", "relaxed", "unwind") to Mood(2, DjTempo.SLOW, listOf("ambient", "acoustic", "jazz")),
        listOf("calm", "peaceful", "quiet", "soft") to Mood(2, DjTempo.SLOW, listOf("ambient", "acoustic", "classical")),
        listOf("mellow", "cozy", "cosy", "lazy") to Mood(3, DjTempo.SLOW, listOf("acoustic", "folk", "jazz")),
        listOf("night", "evening") to Mood(3, genres = listOf("chill", "electronic")),
        listOf("dreamy", "ethereal") to Mood(3, DjTempo.SLOW, listOf("dream pop", "shoegaze", "ambient")),
        listOf("sad", "melancholic", "melancholy", "heartbreak", "rainy", "gloomy") to Mood(3, DjTempo.SLOW, listOf("indie", "acoustic", "singer songwriter")),
        listOf("romantic", "love songs", "date night") to Mood(4, genres = listOf("r and b", "soul", "jazz")),
        listOf("dinner", "cooking") to Mood(3, genres = listOf("jazz", "soul", "bossa nova")),
        listOf("happy", "joyful", "cheerful", "sunny") to Mood(7, genres = listOf("pop", "funk", "disco")),
        listOf("summer", "beach") to Mood(7, genres = listOf("pop", "dance", "reggaeton")),
        listOf("upbeat", "uplifting", "groovy") to Mood(7, DjTempo.FAST, listOf("pop", "dance", "funk")),
        listOf("energetic", "high energy", "pumped") to Mood(8, DjTempo.FAST, listOf("dance", "rock", "electronic")),
        listOf("hype", "banger", "bangers") to Mood(9, DjTempo.FAST, listOf("hip hop", "trap", "edm")),
        listOf("party", "dance", "dancing", "club") to Mood(8, DjTempo.FAST, listOf("dance", "house", "pop")),
        listOf("workout", "gym", "running", "run", "training", "cardio") to Mood(9, DjTempo.FAST, listOf("edm", "hip hop", "rock")),
        listOf("angry", "aggressive", "rage") to Mood(9, DjTempo.FAST, listOf("metal", "punk", "rock")),
        listOf("epic", "cinematic") to Mood(7, genres = listOf("soundtrack", "orchestral")),
    )

    internal val genres = listOf(
        "drum and bass", "hip hop", "trip hop", "lo fi", "dream pop", "k pop", "j pop", "bossa nova", "post rock", "singer songwriter",
        "r and b", "new wave", "synthpop", "synthwave", "shoegaze", "rock", "pop", "jazz", "blues", "classical", "metal", "rap",
        "soul", "funk", "disco", "house", "techno", "trance", "dubstep", "edm", "electronic", "ambient", "indie", "folk", "country",
        "reggae", "punk", "grunge", "latin", "reggaeton", "afrobeats", "gospel", "soundtrack", "downtempo", "emo", "ska", "opera",
        "piano", "acoustic", "alternative", "garage", "grime", "drill", "trap", "dancehall", "orchestral", "hardcore", "chillwave",
    )

    private val words = mapOf("fifties" to 1950, "sixties" to 1960, "seventies" to 1970, "eighties" to 1980, "nineties" to 1990, "noughties" to 2000)
    private val shortDecade = Regex("""\b(?:19)?([5-9])0 ?'?s\b""")
    private val longDecade = Regex("""\b20([0-2])0 ?'?s\b""")
    private val songsCount = Regex("""\b(\d{1,3}) (?:songs?|tracks?|tunes?)\b""")
    private val hoursCount = Regex("""\b(\d{1,2}(?:\.\d)?) ?(?:hours?|hrs?|h)\b""")
    private val minutesCount = Regex("""\b(\d{1,3}) ?(?:minutes?|mins?)\b""")
    private val like = Regex("""\b(?:like|similar to|sounds like|in the style of|by) ([a-z0-9][a-z0-9 ']{1,40})""")
    private val negators = setOf("no", "without", "not", "except", "skip", "avoid", "less", "minus")
    private val clause = Regex(" (?:but|with|without|no|for|except|from|in|at|that|to|please) ")
    private val stop = setOf("a", "an", "the", "some", "me", "my", "for", "to", "and", "with", "of", "in", "on", "music", "songs", "song", "tracks",
        "playlist", "mix", "set", "please", "play", "give", "make", "something", "that", "is", "at", "end", "start", "more", "less", "i", "want", "it")

    private val separators = Regex("[^\\p{L}\\p{N}']+")

    fun norm(value: String): String = value.lowercase().replace("&", " and ").replace(separators, " ").trim()

    private fun has(text: String, term: String) = term.isNotEmpty() && " $text ".contains(" $term ")

    fun parse(request: String, vocabulary: DjVocabulary = DjVocabulary(), base: DjIntent? = null): DjParse {
        val text = norm(request).replace("lofi", "lo fi").replace("rnb", "r and b")
        val found = HashSet<String>()
        var intent = base ?: DjIntent(title = title(request))
        val start = base?.energyEnd ?: 5

        val mood = moods.mapNotNull { (keys, m) -> keys.firstOrNull { has(text, it) }?.let { it to m } }
        if (mood.isNotEmpty()) {
            found += "moods"
            val energy = mood.map { it.second.energy }.average().toInt()
            intent = intent.copy(
                moods = (mood.map { it.first } + intent.moods).distinct().take(6),
                energyStart = energy, energyEnd = energy,
                tempo = mood.firstNotNullOfOrNull { it.second.tempo.takeIf { t -> t != DjTempo.ANY } } ?: intent.tempo,
                vocals = if (mood.any { it.second.vocals == DjVocals.LESS } && intent.vocals == DjVocals.ANY) DjVocals.LESS else intent.vocals,
            )
        }
        val level = if (mood.isNotEmpty()) intent.energyEnd else start
        when {
            listOf("more upbeat", "more energy", "faster", "pick it up", "turn it up", "more intense", "harder", "livelier").any { has(text, it) } -> {
                found += "energy"; val e = (start + 2).coerceAtMost(10); intent = intent.copy(energyStart = e, energyEnd = e, tempo = if (e >= 7) DjTempo.FAST else intent.tempo)
            }
            listOf("calmer", "slower", "tone it down", "less energy", "more chill", "softer", "quieter", "more relaxed").any { has(text, it) } -> {
                found += "energy"; val e = (start - 2).coerceAtLeast(0); intent = intent.copy(energyStart = e, energyEnd = e, tempo = if (e <= 3) DjTempo.SLOW else intent.tempo)
            }
        }
        if (listOf("build", "build up", "builds", "building", "ramp up", "crescendo", "end high", "end strong", "warm up", "rising", "get more intense").any { has(text, it) }) {
            found += "energy"; intent = intent.copy(energyStart = (level - 3).coerceAtLeast(1), energyEnd = (level + 3).coerceAtMost(10))
        } else if (listOf("wind down", "cool down", "come down", "end calm", "fade out", "winding down", "slow down at the end").any { has(text, it) }) {
            found += "energy"; intent = intent.copy(energyStart = (level + 2).coerceAtMost(10), energyEnd = (level - 3).coerceAtLeast(0))
        }

        val decades = (shortDecade.findAll(text).map { 1900 + it.groupValues[1].toInt() * 10 } +
            longDecade.findAll(text).map { 2000 + it.groupValues[1].toInt() * 10 } +
            words.filterKeys { has(text, it) }.values).distinct().toList()
        if (decades.isNotEmpty()) { found += "decades"; intent = intent.copy(decades = decades) }

        when {
            listOf("no vocals", "without vocals", "instrumental", "no singing", "no lyrics", "vocal free", "no voice", "no words").any { has(text, it) } &&
                !listOf("mostly instrumental", "some vocals").any { has(text, it) } -> { found += "vocals"; intent = intent.copy(vocals = DjVocals.NONE) }
            listOf("less vocals", "fewer vocals", "mostly instrumental", "light vocals", "few vocals").any { has(text, it) } -> { found += "vocals"; intent = intent.copy(vocals = DjVocals.LESS) }
            listOf("with vocals", "vocals ok", "singing", "sing along", "singalong").any { has(text, it) } -> { found += "vocals"; intent = intent.copy(vocals = DjVocals.ANY) }
        }

        when {
            listOf("favorites", "favourites", "favorite", "favourite", "liked", "most played", "my top", "familiar", "classics", "i love", "my best").any { has(text, it) } ->
                { found += "familiar"; intent = intent.copy(familiarity = DjFamiliarity.FAVOURITES) }
            listOf("new to me", "discover", "something new", "fresh", "haven't heard", "havent heard", "never heard", "unknown", "deep cuts", "never played", "new music").any { has(text, it) } ->
                { found += "familiar"; intent = intent.copy(familiarity = DjFamiliarity.DISCOVER) }
        }

        when {
            listOf("slow", "slower tempo", "low bpm").any { has(text, it) } -> { found += "tempo"; intent = intent.copy(tempo = DjTempo.SLOW) }
            listOf("fast", "uptempo", "up tempo", "high bpm", "faster").any { has(text, it) } -> { found += "tempo"; intent = intent.copy(tempo = DjTempo.FAST) }
            listOf("mid tempo", "midtempo", "medium tempo").any { has(text, it) } -> { found += "tempo"; intent = intent.copy(tempo = DjTempo.MID) }
        }

        val count = songsCount.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: hoursCount.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 15).toInt() }
            ?: minutesCount.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { it / 4 }
            ?: when { has(text, "half an hour") -> 8; has(text, "an hour") || has(text, "one hour") -> 15; else -> null }
        if (count != null) { found += "count"; intent = intent.copy(count = count.coerceIn(5, DjIntent.MAX_COUNT)) }

        val tokens = text.split(' ')
        val avoided = tokens.indices.filter { tokens[it] in negators }.mapNotNull { i ->
            match(tokens.drop(if (tokens.getOrNull(i + 1) == "more") i + 2 else i + 1).take(4).joinToString(" "), vocabulary)
        }.filter { it != "vocals" }.distinctBy(::norm)
        val genreHits = (vocabulary.genres.filter { has(text, norm(it)) } + genres.filter { has(text, it) })
            .distinctBy(::norm).filter { g -> avoided.none { norm(it) == norm(g) } }
        val artistHits = vocabulary.artists.filter { norm(it).length >= 4 && has(text, norm(it)) }.filter { a -> avoided.none { norm(it) == norm(a) } }
        val likeTerms = like.findAll(text).flatMap { " ${it.groupValues[1]} ".split(clause).first().split(" and ", " or ").asSequence() }
            .map { part -> part.split(' ').filter { w -> w.isNotEmpty() && w !in stop }.take(3).joinToString(" ") }
            .filter { it.isNotBlank() && match(it, vocabulary) == null }.distinct().toList()

        if (avoided.isNotEmpty()) {
            found += "avoid"
            intent = intent.copy(avoid = (intent.avoid + avoided).distinctBy(::norm),
                genres = intent.genres.filter { g -> avoided.none { norm(it) == norm(g) } },
                artists = intent.artists.filter { a -> avoided.none { norm(it) == norm(a) } })
        }
        // mood genres stay a hint scored at pick time, genres here are only the ones the request names
        if (genreHits.isNotEmpty()) {
            found += "genres"
            val replace = base != null && listOf("switch to", "change to", "only", "now", "instead").any { has(text, it) }
            val kept = if (replace) emptyList() else intent.genres
            intent = intent.copy(genres = (genreHits + kept).distinctBy(::norm).filter { g -> intent.avoid.none { norm(it) == norm(g) } }.take(6))
        }
        if (artistHits.isNotEmpty()) { found += "artists"; intent = intent.copy(artists = (artistHits + intent.artists).distinctBy(::norm).take(6)) }
        if (likeTerms.isNotEmpty()) { found += "search"; intent = intent.copy(search = (likeTerms + intent.search).distinct().take(4)) }
        if (base == null && genreHits.isEmpty() && artistHits.isEmpty() && likeTerms.isEmpty()) {
            val free = if (mood.isNotEmpty()) mood.flatMap { it.first.split(' ') }.distinct().joinToString(" ")
                else text.split(' ').filter { it !in stop && it.length > 1 && it.toIntOrNull() == null }.take(5).joinToString(" ")
            if (free.isNotBlank()) intent = intent.copy(search = listOf(free))
        }
        return DjParse(intent, found)
    }

    private fun match(phrase: String, vocabulary: DjVocabulary): String? {
        val words = phrase.split(' ')
        for (n in minOf(4, words.size) downTo 1) {
            val head = words.take(n).joinToString(" ")
            if (head == "vocals" || head == "singing") return "vocals"
            (vocabulary.genres + vocabulary.artists).firstOrNull { norm(it) == head }?.let { return it }
            genres.firstOrNull { it == head || "${it}s" == head }?.let { return it }
        }
        return null
    }

    // hard constraints come only from the request words, the small model adds soft expansions
    fun merge(model: DjIntent, keywords: DjParse, request: String, steering: Boolean = false): DjIntent {
        val k = keywords.intent
        val f = keywords.found
        val text = norm(request)
        val avoid = (k.avoid + model.avoid.filter { has(text, norm(it)) }).distinctBy(::norm)
        fun allowed(values: List<String>) = values.filter { v -> avoid.none { norm(it) == norm(v) } }
        val keywordEnergy = steering || "energy" in f || "moods" in f
        // a steer the rules understand keeps the set's genres, the model only reshapes ones they can't read
        val understood = steering && f.isNotEmpty()
        return k.copy(
            title = model.title.ifBlank { k.title },
            energyStart = if (keywordEnergy) k.energyStart else model.energyStart,
            energyEnd = if (keywordEnergy) k.energyEnd else model.energyEnd,
            moods = (k.moods + if (understood) emptyList() else model.moods).distinctBy(::norm).take(6),
            genres = allowed(if ("genres" in f || understood) k.genres else model.genres + k.genres).distinctBy(::norm).take(6),
            artists = allowed(k.artists + model.artists.filter { has(text, norm(it)) }).distinctBy(::norm).take(6),
            avoid = avoid,
            tempo = if (steering || "tempo" in f || "moods" in f) k.tempo else model.tempo,
            search = allowed(k.search + if (understood) emptyList() else model.search).distinct().take(4),
        )
    }

    private fun title(request: String): String {
        val clean = request.trim().replace(Regex("\\s+"), " ").take(40).trimEnd(',', '.', ' ')
        return clean.replaceFirstChar { it.uppercase() }
    }
}
