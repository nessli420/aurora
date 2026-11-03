package com.aurora.music.ai

import com.google.gson.JsonParser

object DjPrompt {
    const val MAX_TOKENS = 140

    // eras, vocals, familiarity, length and exclusions come from the request words, so the model only writes the rest
    val GRAMMAR = """
root ::= "{\"title\":\"" text "\",\"energy\":[" lvl "," lvl "],\"moods\":" list ",\"genres\":" list ",\"artists\":" list ",\"tempo\":\"" ("any" | "slow" | "mid" | "fast") "\",\"search\":" list "}"
lvl ::= [0-9] | "10"
list ::= "[]" | "[" str ("," str){0,4} "]"
str ::= "\"" text "\""
text ::= [^"\\\x00-\x1f]{1,40}
""".trim()

    private val SYSTEM = """
You turn a music request into JSON settings for a playlist picker. The picker chooses real songs from the user's library, so never invent song titles.
Fields:
title: short playlist name, at most 5 words
energy: [start, end] from 0 (silent, calm) to 10 (maximum intensity); use different numbers when the request wants the energy to change over the set
moods: mood words from the request
genres: genres that fit the request, preferring genres from the library list
artists: artists named in the request, else []
tempo: "slow", "mid", "fast" or "any"
search: up to 3 specific search queries that combine genre, era and mood
When a current set is given, return the full set with only the requested change applied.

Request: upbeat 80s workout that builds to the end
{"title":"80s Workout Build","energy":[6,10],"moods":["upbeat","energetic"],"genres":["synthpop","rock"],"artists":[],"tempo":"fast","search":["80s workout rock"]}

Request: something calm to study to, no vocals, about an hour
{"title":"Quiet Study","energy":[3,2],"moods":["calm","focused"],"genres":["ambient","lo-fi","classical"],"artists":[],"tempo":"slow","search":["instrumental study"]}

Current set: {"title":"Quiet Study","energy":[3,2],"moods":["calm","focused"],"genres":["ambient","lo-fi","classical"],"artists":[],"tempo":"slow","search":["instrumental study"]}
Change: a bit more upbeat, no classical
{"title":"Brighter Study","energy":[5,5],"moods":["focused","upbeat"],"genres":["lo-fi","electronic"],"artists":[],"tempo":"mid","search":["upbeat instrumental"]}
""".trim()

    private val exampleTitles = setOf("80s workout build", "quiet study", "brighter study")
    private val exampleSearches = setOf("80s workout rock", "80s workout", "instrumental study", "upbeat instrumental")

    // a small model sometimes copies a worked example instead of answering
    fun scrub(intent: DjIntent): DjIntent? = if (intent.title.trim().lowercase() in exampleTitles) null
        else intent.copy(search = intent.search.filterNot { it.trim().lowercase() in exampleSearches })

    private fun clean(value: String) = value.replace("<|", "").replace("|>", "").replace('\n', ' ').trim().take(300)

    fun prefix(vocabulary: DjVocabulary): String = buildString {
        append("<|im_start|>system\n").append(SYSTEM)
        if (vocabulary.genres.isNotEmpty()) append("\n\nLibrary genres: ").append(vocabulary.genres.take(30).joinToString(", ") { clean(it) })
        if (vocabulary.artists.isNotEmpty()) append("\nLibrary artists: ").append(vocabulary.artists.take(30).joinToString(", ") { clean(it) })
        append("<|im_end|>\n<|im_start|>user\n")
    }

    fun request(text: String) = "Request: ${clean(text)}$ASSISTANT"

    fun steer(current: DjIntent, text: String) = "Current set: ${brief(current)}\nChange: ${clean(text)}$ASSISTANT"

    private fun brief(intent: DjIntent) = JsonParser.parseString(intent.toJson()).asJsonObject
        .apply { listOf("avoid", "decades", "vocals", "familiar", "count").forEach { remove(it) } }.toString()

    private const val ASSISTANT = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
}
