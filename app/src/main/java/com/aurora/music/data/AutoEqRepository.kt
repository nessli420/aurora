package com.aurora.music.data

import android.content.Context
import com.aurora.music.playback.DspCoeffBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** A headphone/IEM correction profile from the bundled AutoEq index. */
data class EqProfile(val name: String, val source: String, val path: String)

/** A parsed AutoEq parametric correction: a preamp plus filter bands. */
data class ParsedEq(val preampDb: Float, val bands: List<ParamBand>)

/**
 * AutoEq headphone-correction database. The searchable index (5000+ models) ships as a bundled
 * asset for instant offline search; the chosen model's tiny `ParametricEQ.txt` is fetched live from
 * AutoEq's GitHub and parsed into Aurora's parametric EQ bands (PK / low-shelf / high-shelf).
 */
class AutoEqRepository(private val context: Context) {

    private val http = OkHttpClient()
    @Volatile private var index: List<EqProfile>? = null

    suspend fun ensureIndex(): List<EqProfile> {
        index?.let { return it }
        return withContext(Dispatchers.IO) {
            val list = runCatching {
                context.assets.open(INDEX_ASSET).bufferedReader().useLines { lines ->
                    lines.mapNotNull { ln ->
                        val p = ln.split('\t')
                        if (p.size >= 3 && p[0].isNotBlank()) EqProfile(p[0], p[1], p[2]) else null
                    }.toList()
                }
            }.getOrDefault(emptyList())
            index = list
            list
        }
    }

    /** Search the index by model name (ranked: prefix match first, then shortest). */
    suspend fun search(query: String, limit: Int = 60): List<EqProfile> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return ensureIndex().asSequence()
            .filter { it.name.lowercase().contains(q) }
            .sortedWith(compareByDescending<EqProfile> { it.name.lowercase().startsWith(q) }.thenBy { it.name.length })
            .distinctBy { it.name.lowercase() + "|" + it.source.lowercase() }
            .take(limit)
            .toList()
    }

    /** Fetch + parse a profile's AutoEq ParametricEQ.txt. Null on network/parse failure. */
    suspend fun fetch(profile: EqProfile): ParsedEq? = withContext(Dispatchers.IO) {
        runCatching {
            val enc = profile.path.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
            val req = Request.Builder().url("$RAW_BASE/$enc").build()
            val body = http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@runCatching null
            parse(body)
        }.getOrNull()
    }

    private fun parse(text: String): ParsedEq {
        var preamp = 0f
        val bands = ArrayList<ParamBand>()
        val num = Regex("(-?\\d+(?:\\.\\d+)?)")
        for (raw in text.lineSequence()) {
            val t = raw.trim()
            when {
                t.startsWith("Preamp", true) ->
                    preamp = num.find(t)?.value?.toFloatOrNull() ?: 0f
                t.startsWith("Filter", true) && t.contains(" ON ") -> {
                    val type = when {
                        Regex("\\bLSC\\b").containsMatchIn(t) -> BandType.LOW_SHELF
                        Regex("\\bHSC\\b").containsMatchIn(t) -> BandType.HIGH_SHELF
                        Regex("\\bPK\\b").containsMatchIn(t) -> BandType.PEAK
                        else -> continue
                    }
                    val fc = Regex("Fc\\s+(-?\\d+(?:\\.\\d+)?)").find(t)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
                    val gain = Regex("Gain\\s+(-?\\d+(?:\\.\\d+)?)").find(t)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
                    val q = Regex("\\bQ\\s+(-?\\d+(?:\\.\\d+)?)").find(t)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.7f
                    bands.add(ParamBand(fc, gain, q, type))
                }
            }
        }
        return ParsedEq(preamp, bands.take(DspCoeffBuilder.MAX_PARAMETRIC))
    }

    private companion object {
        const val INDEX_ASSET = "autoeq_index.tsv"
        const val RAW_BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master"
    }
}
