package com.aurora.music.data

import com.aurora.music.data.rules.RuleSource
import java.net.URI
import java.security.MessageDigest

data class PlaybackSourceIdentity(
    val providerId: String? = null,
    val providerLabel: String? = null,
    val source: RuleSource? = null,
    val albumId: String? = null,
) {
    companion object {
        fun fromSession(session: Session, albumId: String, source: RuleSource =
            if (session.type == ServerType.LOCAL) RuleSource.LOCAL_FILE else RuleSource.STREAM): PlaybackSourceIdentity {
            val provider = "provider:${digest(session.accountKey())}"
            val host = runCatching { URI(session.server).host }.getOrNull()?.takeIf { it.isNotBlank() }
            return PlaybackSourceIdentity(provider, session.typeLabel + (host?.let { " · $it" } ?: ""), source,
                scoped(provider, "album", albumId))
        }

        fun scoped(provider: String, kind: String, originalId: String): String? =
            originalId.takeIf { it.isNotBlank() }?.let { "$kind:${digest("$provider\u0000$kind\u0000$it")}" }

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

data class PlaybackCollectionIdentity(val id: String? = null, val name: String? = null)
