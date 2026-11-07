package com.aurora.music.data

import com.aurora.music.data.rules.RuleSource
import java.net.URI
import java.security.MessageDigest

data class PlaybackSourceIdentity(
    val providerId: String? = null,
    val providerLabel: String? = null,
    val source: RuleSource? = null,
    val albumId: String? = null,
    val songId: String? = null,
) {
    companion object {
        fun fromSession(session: Session, albumId: String, source: RuleSource =
            if (session.type == ServerType.LOCAL) RuleSource.LOCAL_FILE else RuleSource.STREAM): PlaybackSourceIdentity {
            val provider = providerFor(session)
            return PlaybackSourceIdentity(provider.id, provider.label, source, scoped(provider.id, "album", albumId))
        }

        fun scoped(provider: String, kind: String, originalId: String): String? =
            originalId.takeIf { it.isNotBlank() }?.let { "$kind:${sha256Hex("$provider\u0000$kind\u0000$it")}" }

        private class Provider(val session: Session, val id: String, val label: String)

        @Volatile private var recent: Provider? = null

        // keyed by text because gson can leave session fields null
        private val providers = object : LinkedHashMap<String, Provider>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Provider>?): Boolean = size > 16
        }

        private fun providerFor(session: Session): Provider {
            recent?.let { if (it.session === session) return it }
            val key = "${session.type}\u0000${session.server}\u0000${session.username}\u0000${session.userId}\u0000" +
                if (session.type == ServerType.PLEX) session.token else ""
            val cached = synchronized(providers) { providers[key] }
            val provider = if (cached != null) Provider(session, cached.id, cached.label) else {
                val id = "provider:${sha256Hex(session.accountKey())}"
                val host = runCatching { URI(session.server).host }.getOrNull()?.takeIf { it.isNotBlank() }
                Provider(session, id, session.typeLabel + (host?.let { " · $it" } ?: "")).also { synchronized(providers) { providers[key] = it } }
            }
            recent = provider
            return provider
        }
    }
}

data class PlaybackCollectionIdentity(val id: String? = null, val name: String? = null)

private val HEX_DIGITS = "0123456789abcdef".toCharArray()
private val sha256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

fun sha256Hex(value: String): String {
    val bytes = sha256.get().digest(value.toByteArray(Charsets.UTF_8))
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xff
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0f]
    }
    return String(out)
}
