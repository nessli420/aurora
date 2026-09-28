package com.aurora.music.data

import com.aurora.music.data.remote.SquigClient

const val DEFAULT_SQUIG_BASE = "https://squig.link"
const val DEFAULT_SQUIG_TARGET = "Harman IE 2019 Target"

val SQUIG_INSTANCES = listOf(
    "squig.link" to "https://squig.link",
    "Precog" to "https://precog.squig.link",
)

val SQUIG_TARGETS = listOf(
    "Harman IE 2019" to "Harman IE 2019 Target",
    "Diffuse Field" to "Diffuse Field Target",
)

class SquigEqRepository(
    private val client: SquigClient,
    private val baseProvider: () -> String,
    private val targetProvider: () -> String,
) {
    private val targetCache = HashMap<String, FrCurve>()

    suspend fun search(query: String): List<EqProfile> = client.search(baseProvider(), query)

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
