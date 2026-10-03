package com.aurora.music.desktop.linux

import com.aurora.music.desktop.platform.SecretStore
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SecretServiceStore(
    private val connection: () -> DBusConnection? = SessionBus::connection,
    private val label: String = "Aurora settings key",
    private val attributes: Map<String, String> = mapOf("application" to "com.aurora.music", "purpose" to "settings-key"),
    private val promptTimeoutMs: Long = 60_000,
) : SecretStore {
    override fun read(): ByteArray? {
        val bus = connection() ?: return null
        val service = service(bus)
        val found = service.SearchItems(attributes)
        val item = found.unlocked.firstOrNull()
            ?: found.locked.firstOrNull()?.also { check(unlock(bus, service, it)) { "The keyring stayed locked" } }
            ?: return null
        return withSession(bus, service) { session -> remote<SecretItem>(bus, item.path).GetSecret(session).value }
    }

    override fun write(secret: ByteArray): Boolean {
        val bus = connection() ?: return false
        val service = service(bus)
        val collection = service.ReadAlias("default").takeIf { it.path != NONE } ?: return false
        if (!unlock(bus, service, collection)) return false
        val properties = mapOf<String, Variant<*>>(
            "org.freedesktop.Secret.Item.Label" to Variant(label),
            "org.freedesktop.Secret.Item.Attributes" to Variant(attributes, "a{ss}"),
        )
        return withSession(bus, service) { session ->
            val created = remote<SecretCollection>(bus, collection.path)
                .CreateItem(properties, Secret(session, ByteArray(0), secret, "application/octet-stream"), true)
            created.item.path != NONE || prompt(bus, created.prompt) != null
        }
    }

    private fun unlock(bus: DBusConnection, service: SecretService, path: DBusPath): Boolean {
        val result = service.Unlock(listOf(path))
        if (result.unlocked.any { it.path == path.path }) return true
        return prompt(bus, result.prompt) != null
    }

    private fun prompt(bus: DBusConnection, path: DBusPath): Variant<*>? {
        if (path.path == NONE) return null
        val completed = CompletableFuture<SecretPrompt.Completed>()
        bus.addSigHandler(SecretPrompt.Completed::class.java) { if (it.path == path.path) completed.complete(it) }.use {
            remote<SecretPrompt>(bus, path.path).Prompt("")
            val done = runCatching { completed.get(promptTimeoutMs, TimeUnit.MILLISECONDS) }.getOrNull() ?: return null
            return done.result.takeUnless { done.dismissed }
        }
    }

    private fun <T> withSession(bus: DBusConnection, service: SecretService, block: (DBusPath) -> T): T {
        val session = service.OpenSession("plain", Variant("")).result
        try {
            return block(session)
        } finally {
            runCatching { remote<SecretSession>(bus, session.path).Close() }
        }
    }

    private fun service(bus: DBusConnection) = remote<SecretService>(bus, PATH)

    private inline fun <reified T : org.freedesktop.dbus.interfaces.DBusInterface> remote(bus: DBusConnection, path: String): T =
        bus.getRemoteObject(BUS, path, T::class.java)

    private companion object {
        const val BUS = "org.freedesktop.secrets"
        const val PATH = "/org/freedesktop/secrets"
        const val NONE = "/"
    }
}
