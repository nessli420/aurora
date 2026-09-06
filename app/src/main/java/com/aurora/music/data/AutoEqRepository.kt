package com.aurora.music.data

import android.content.Context
import com.aurora.music.playback.DspCoeffBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale

enum class EqDeviceKind(val label: String, val description: String, val examples: List<String>) {
    ALL("All devices", "Measured presets for wired and Bluetooth headphones, earbuds and speakers.", listOf("Sony WH-1000XM5", "AirPods", "HD 600", "SoundLink")),
    HEADPHONES("Headphones / studio", "Over-ear and on-ear headphones, including studio and Bluetooth models.", listOf("HD 600", "ATH-M50x", "DT 770", "WH-1000XM5")),
    IN_EAR("IEMs / wireless buds", "In-ear monitors and sealed wireless earbuds. Match the model and ANC mode.", listOf("AirPods Pro", "Galaxy Buds", "Moondrop", "WF-1000XM5")),
    EARBUDS("Earbuds / open-ear", "Unsealed earbuds and measured open-ear models, including Shokz.", listOf("OpenFit", "OpenRun", "AirPods 4", "VE Monk")),
    SPEAKERS("Speakers / Bluetooth", "Measured speaker correction from Spinorama, including Bluetooth models. Placement and room acoustics still affect the result.", listOf("SoundLink", "Sonos Roam", "JBL 305", "Genelec")),
}

enum class EqProvider { AUTOEQ, SQUIG, SPINORAMA }

data class EqProfile(
    val name: String,
    val source: String,
    val path: String,
    val provider: EqProvider = EqProvider.AUTOEQ,
) {
    // AutoEQ records the actual measurement form factor in the directory, including the rig name.
    val kind: EqDeviceKind get() = when {
        provider == EqProvider.SPINORAMA -> EqDeviceKind.SPEAKERS
        provider == EqProvider.SQUIG -> EqDeviceKind.IN_EAR
        path.split('/').getOrNull(2)?.contains("in-ear") == true -> EqDeviceKind.IN_EAR
        path.split('/').getOrNull(2)?.contains("earbud") == true -> EqDeviceKind.EARBUDS
        else -> EqDeviceKind.HEADPHONES
    }
}

data class ParsedEq(val preampDb: Float, val bands: List<ParamBand>)

class AutoEqRepository(private val context: Context) {

    private val http = OkHttpClient()
    @Volatile private var index: List<EqProfile>? = null

    suspend fun ensureIndex(): List<EqProfile> {
        index?.let { return it }
        return withContext(Dispatchers.IO) {
            val list = listOf(INDEX_ASSET to EqProvider.AUTOEQ, SPEAKER_INDEX_ASSET to EqProvider.SPINORAMA).flatMap { (asset, provider) ->
                runCatching { context.assets.open(asset).bufferedReader().useLines { lines ->
                    lines.mapNotNull { ln ->
                        val p = ln.split('\t')
                        if (!ln.startsWith('#') && p.size >= 3 && p[0].isNotBlank()) EqProfile(p[0], p[1], p[2], provider) else null
                    }.toList()
                } }.getOrDefault(emptyList())
            }
            index = list
            list
        }
    }

    suspend fun search(query: String, kind: EqDeviceKind = EqDeviceKind.ALL, limit: Int = Int.MAX_VALUE): List<EqProfile> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase(Locale.ROOT)
        val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        val matches = ensureIndex().asSequence()
            .filter { kind == EqDeviceKind.ALL || it.kind == kind }
            .filter { p -> terms.all { term -> p.name.lowercase(Locale.ROOT).contains(term) } }
            .sortedWith(compareByDescending<EqProfile> { p ->
                if (q.isBlank()) kind.examples.any { p.name.contains(it, ignoreCase = true) }
                else p.name.startsWith(q, ignoreCase = true)
            }.thenBy { if (q.isBlank()) it.name.lowercase(Locale.ROOT) else "" }.thenBy { it.name.length })
            .distinctBy { it.name.lowercase(Locale.ROOT) + "|" + it.source.lowercase(Locale.ROOT) }
            .toList()
        // Give each example a visible starting point instead of filling the first page with one model's rigs.
        val featured = if (q.isBlank()) kind.examples.mapNotNull { example ->
            matches.filter { it.name.contains(example, ignoreCase = true) }.minByOrNull { it.name.length }
        } else emptyList()
        (featured + matches).distinctBy { it.path }.take(limit)
    }

    suspend fun fetch(profile: EqProfile): ParsedEq? = withContext(Dispatchers.IO) {
        runCatching {
            val enc = profile.path.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
            val base = if (profile.provider == EqProvider.SPINORAMA) SPEAKER_RAW_BASE else RAW_BASE
            val req = Request.Builder().url("$base/$enc").build()
            val body = http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@runCatching null
            EqTextParser.parse(body)
        }.getOrNull()
    }

    private companion object {
        const val INDEX_ASSET = "autoeq_index.tsv"
        const val SPEAKER_INDEX_ASSET = "spinorama_index.tsv"
        const val RAW_BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master"
        const val SPEAKER_RAW_BASE = "https://raw.githubusercontent.com/pierreaubert/spinorama/b38d2715441f134a63b737b2fd913d14058a2643"
    }
}

/** Equalizer APO parametric text used by both AutoEQ and Spinorama. */
internal object EqTextParser {
    fun parse(text: String): ParsedEq? {
        var preamp = 0f
        val bands = ArrayList<ParamBand>()
        val number = "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))"
        val filter = Regex("^Filter\\s+\\d+:\\s+ON\\s+(\\S+)\\s+Fc\\s+$number\\s+Hz\\s+Gain\\s+$number\\s+dB\\s+Q\\s+$number(?:\\s.*)?$", RegexOption.IGNORE_CASE)
        for (raw in text.lineSequence()) {
            val t = raw.trim()
            when {
                t.startsWith("Preamp", true) -> {
                    preamp = Regex("^Preamp:\\s*$number\\s+dB", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
                    if (!preamp.isFinite()) return null
                }
                t.startsWith("Filter", true) && Regex("\\bON\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) -> {
                    // Never silently drop unsupported filters or positive gains from a correction.
                    val match = filter.matchEntire(t) ?: return null
                    val type = when (match.groupValues[1].uppercase(Locale.ROOT)) {
                        "LSC", "LS" -> BandType.LOW_SHELF
                        "HSC", "HS" -> BandType.HIGH_SHELF
                        "PK" -> BandType.PEAK
                        else -> return null
                    }
                    val fc = match.groupValues[2].toFloatOrNull() ?: return null
                    val gain = match.groupValues[3].toFloatOrNull() ?: return null
                    val q = match.groupValues[4].toFloatOrNull() ?: return null
                    if (!fc.isFinite() || !gain.isFinite() || !q.isFinite() || fc <= 0f || q < 0.1f) return null
                    bands.add(ParamBand(fc, gain, q, type))
                }
            }
        }
        return if (bands.isEmpty() || bands.size > DspCoeffBuilder.MAX_PARAMETRIC) null else ParsedEq(preamp, bands)
    }
}
