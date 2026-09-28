package com.aurora.music.data

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale

val EqDeviceKind.label: String get() = appString(when (this) {
    EqDeviceKind.ALL -> R.string.text_all_devices_a8b16c
    EqDeviceKind.HEADPHONES -> R.string.text_headphones_studio_1f6f07
    EqDeviceKind.IN_EAR -> R.string.text_iems_wireless_buds_d69c4c
    EqDeviceKind.EARBUDS -> R.string.text_earbuds_open_ear_9a037e
    EqDeviceKind.SPEAKERS -> R.string.text_speakers_bluetooth_6dc246
})

val EqDeviceKind.description: String get() = appString(when (this) {
    EqDeviceKind.ALL -> R.string.text_measured_presets_for_wired_and_bluetooth_headphones_earbuds_and_s_dbd541
    EqDeviceKind.HEADPHONES -> R.string.text_over_ear_and_on_ear_headphones_including_studio_and_bluetooth_mod_d79653
    EqDeviceKind.IN_EAR -> R.string.text_in_ear_monitors_and_sealed_wireless_earbuds_match_the_model_and_a_a24f40
    EqDeviceKind.EARBUDS -> R.string.text_unsealed_earbuds_and_measured_open_ear_models_including_shokz_c45724
    EqDeviceKind.SPEAKERS -> R.string.text_measured_speaker_correction_from_spinorama_including_bluetooth_mo_beaf02
})

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
