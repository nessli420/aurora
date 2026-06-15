package com.aurora.music.data

import com.aurora.music.data.remote.SquigClient

const val DEFAULT_SQUIG_BASE = "https://squig.link"
const val DEFAULT_SQUIG_TARGET = "Harman IE 2019 Target"

/** Built-in CrinGraph instances offered in the EQ UI: display label → base URL. */
val SQUIG_INSTANCES = listOf(
    "squig.link" to "https://squig.link",
    "Precog" to "https://precog.squig.link",
)

/** Common IEM target curves present on most instances: display label → file stem. */
val SQUIG_TARGETS = listOf(
    "Harman IE 2019" to "Harman IE 2019 Target",
    "Diffuse Field" to "Diffuse Field Target",
)

/**
 * Live per-IEM AutoEQ from squig.link: searches a CrinGraph instance's catalog and turns the chosen
 * model's raw measured response into a parametric correction toward the active target, on-device.
 * Produces the same [EqProfile] / [ParsedEq] the bundled AutoEq path uses, so apply/bind/auto-switch
 * are unchanged. The instance [baseProvider] and [targetProvider] are user-configurable in settings.
 */
class SquigEqRepository(
    private val client: SquigClient,
    private val baseProvider: () -> String,
    private val targetProvider: () -> String,
) {
    private val targetCache = HashMap<String, FrCurve>()

    /** Search the active instance's model catalog. */
    suspend fun search(query: String): List<EqProfile> = client.search(baseProvider(), query)

    /** Generate a correction for [profile] = measured (L+R avg) vs. the active target. Null on failure. */
    suspend fun generate(profile: EqProfile): ParsedEq? {
        val base = baseProvider()
        val measured = client.measurement(base, profile.path) ?: return null
        val target = cachedTarget(base, targetProvider()) ?: return null
        return AutoEqGenerator.generate(measured, target)
    }

    private suspend fun cachedTarget(base: String, name: String): FrCurve? {
        val key = "$base|$name"
        synchronized(targetCache) { targetCache[key] }?.let { return it }
        val t = client.target(base, name) ?: return null
        synchronized(targetCache) { targetCache[key] = t }
        return t
    }
}
