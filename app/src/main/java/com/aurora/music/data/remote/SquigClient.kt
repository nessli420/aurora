package com.aurora.music.data.remote

import com.aurora.music.data.EqProfile
import com.aurora.music.data.FrCurve
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads headphone/IEM frequency-response data from squig.link / CrinGraph instances. The layout is
 * fixed by the CrinGraph engine: `{base}/data/phone_book.json` indexes every model, and each model's
 * measurement lives in `{base}/data/<file> L.txt` + ` R.txt` (averaged here). Target curves are
 * `{base}/data/<target>.txt`. Cloudflare-fronted instances 403 default bot agents, so every request
 * carries a browser User-Agent. CORS-open, keyless, HTTPS. Failures degrade to null/empty.
 */
class SquigClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // One parsed index per instance base URL (the catalog rarely changes within a session).
    private val indexCache = HashMap<String, List<EqProfile>>()

    private data class SquigBrand(val name: String? = "", val phones: List<SquigPhone>? = emptyList())
    private data class SquigPhone(val name: String? = "", val file: JsonElement? = null, val suffix: JsonElement? = null)

    /** Full model catalog for [base], flattened (one entry per measurement variant). */
    suspend fun index(base: String): List<EqProfile> = withContext(Dispatchers.IO) {
        synchronized(indexCache) { indexCache[base] }?.let { return@withContext it }
        val body = fetchText("$base/data/phone_book.json") ?: return@withContext emptyList()
        val brands = runCatching { gson.fromJson(body, Array<SquigBrand>::class.java) }.getOrNull()
            ?: return@withContext emptyList()
        val host = hostLabel(base)
        val out = ArrayList<EqProfile>()
        for (brand in brands) {
            val bName = brand.name?.trim().orEmpty()
            if (bName.isBlank() || bName.startsWith("_")) continue   // skip "_EQ" pseudo-brand
            for (phone in brand.phones.orEmpty()) {
                val mName = phone.name?.trim().orEmpty()
                val files = stringsOf(phone.file)
                val suffixes = stringsOf(phone.suffix)
                files.forEachIndexed { idx, stem ->
                    val s = stem.trim()
                    if (s.isBlank()) return@forEachIndexed
                    val suff = suffixes.getOrNull(idx)?.trim().orEmpty()
                    val base2 = listOf(bName, mName).filter { it.isNotBlank() }.joinToString(" ")
                    val display = (if (suff.isNotBlank()) "$base2 $suff" else base2).ifBlank { s }
                    out.add(EqProfile(name = display, source = host, path = s))
                }
            }
        }
        synchronized(indexCache) { indexCache[base] = out }
        out
    }

    /** Search a model by name (prefix match ranked first, then shortest). */
    suspend fun search(base: String, query: String, limit: Int = 60): List<EqProfile> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.length < 2) return@withContext emptyList()
        index(base).asSequence()
            .filter { it.name.lowercase().contains(q) }
            .sortedWith(compareByDescending<EqProfile> { it.name.lowercase().startsWith(q) }.thenBy { it.name.length })
            .take(limit)
            .toList()
    }

    /** Averaged L+R measured response for [stem] (e.g. "64 Audio Aspire 1"), or null. */
    suspend fun measurement(base: String, stem: String): FrCurve? = withContext(Dispatchers.IO) {
        val l = fetchCurve("$base/data", "$stem L.txt")
        val r = fetchCurve("$base/data", "$stem R.txt")
        when {
            !l.isNullOrEmpty() && !r.isNullOrEmpty() -> averageCurves(l, r)
            !l.isNullOrEmpty() -> l
            !r.isNullOrEmpty() -> r
            else -> fetchCurve("$base/data", "$stem.txt")   // some instances use a no-suffix file
        }
    }

    /** A target curve named e.g. "Harman IE 2019 Target" (the ".txt" is appended), or null. */
    suspend fun target(base: String, targetName: String): FrCurve? = withContext(Dispatchers.IO) {
        fetchCurve("$base/data", "$targetName.txt")
    }

    // ---- internals -------------------------------------------------------------------------------

    private fun fetchCurve(dirUrl: String, fileName: String): FrCurve? {
        val url = dirUrl.toHttpUrlOrNull()?.newBuilder()?.addPathSegment(fileName)?.build() ?: return null
        val text = fetchText(url.toString()) ?: return null
        return parseCurve(text).takeIf { it.size >= 8 }
    }

    private fun fetchText(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            .execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    /** Tolerant FR parse: skip any line not starting with a number; take cols [0]=Hz, [1]=dB. */
    private fun parseCurve(text: String): FrCurve {
        val out = ArrayList<Pair<Float, Float>>()
        for (line in text.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            val c = s[0]
            if (!(c.isDigit() || c == '-' || c == '.')) continue
            val parts = s.split(SEP)
            val f = parts.getOrNull(0)?.toFloatOrNull() ?: continue
            val db = parts.getOrNull(1)?.toFloatOrNull() ?: continue
            if (f > 0f) out.add(f to db)
        }
        // The interpolator assumes ascending, de-duped frequencies; enforce it (don't trust the file).
        return out.sortedBy { it.first }.distinctBy { it.first }
    }

    private fun averageCurves(a: FrCurve, b: FrCurve): FrCurve {
        if (a.size != b.size) return a   // different grids — L/R from one instance share a grid, so this is the safe fallback
        return a.indices.map { i -> a[i].first to ((a[i].second + b[i].second) / 2f) }
    }

    private fun stringsOf(el: JsonElement?): List<String> = when {
        el == null || el.isJsonNull -> emptyList()
        el.isJsonArray -> el.asJsonArray.mapNotNull { runCatching { it.asString }.getOrNull() }
        el.isJsonPrimitive -> listOf(el.asString)
        else -> emptyList()
    }

    private fun hostLabel(base: String): String =
        runCatching { android.net.Uri.parse(base).host ?: base }.getOrDefault(base)

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 Aurora/1.0"
        val SEP = Regex("[\\s,]+")
    }
}
