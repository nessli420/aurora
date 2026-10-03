@file:Suppress("FunctionName")

package com.aurora.music.desktop.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Service")
interface SecretService : DBusInterface {
    fun OpenSession(algorithm: String, input: Variant<*>): OpenedSession
    fun SearchItems(attributes: Map<String, String>): FoundItems
    fun Unlock(objects: List<DBusPath>): Unlocked
    fun ReadAlias(name: String): DBusPath
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Collection")
interface SecretCollection : DBusInterface {
    fun CreateItem(properties: Map<String, Variant<*>>, secret: Secret, replace: Boolean): CreatedItem
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
interface SecretItem : DBusInterface {
    fun GetSecret(session: DBusPath): Secret
}

@DBusInterfaceName("org.freedesktop.Secret.Session")
interface SecretSession : DBusInterface {
    fun Close()
}

@DBusInterfaceName("org.freedesktop.Secret.Prompt")
interface SecretPrompt : DBusInterface {
    fun Prompt(windowId: String)

    class Completed(path: String, val dismissed: Boolean, val result: Variant<*>) : DBusSignal(path, dismissed, result)
}

class Secret(
    @field:Position(0) @JvmField val session: DBusPath,
    @field:Position(1) @JvmField val parameters: ByteArray,
    @field:Position(2) @JvmField val value: ByteArray,
    @field:Position(3) @JvmField val contentType: String,
) : Struct()

class OpenedSession(@field:Position(0) @JvmField val output: Variant<*>, @field:Position(1) @JvmField val result: DBusPath) : Tuple()

@JvmSuppressWildcards
class FoundItems(@field:Position(0) @JvmField val unlocked: List<DBusPath>, @field:Position(1) @JvmField val locked: List<DBusPath>) : Tuple()

@JvmSuppressWildcards
class Unlocked(@field:Position(0) @JvmField val unlocked: List<DBusPath>, @field:Position(1) @JvmField val prompt: DBusPath) : Tuple()

class CreatedItem(@field:Position(0) @JvmField val item: DBusPath, @field:Position(1) @JvmField val prompt: DBusPath) : Tuple()

@DBusInterfaceName("org.freedesktop.portal.Settings")
interface PortalSettings : DBusInterface {
    fun ReadOne(namespace: String, key: String): Variant<*>
    fun Read(namespace: String, key: String): Variant<*>

    class SettingChanged(path: String, val namespace: String, val key: String, val value: Variant<*>) : DBusSignal(path, namespace, key, value)
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.portal.Inhibit")
interface PortalInhibit : DBusInterface {
    fun Inhibit(window: String, flags: UInt32, options: Map<String, Variant<*>>): DBusPath
}

@DBusInterfaceName("org.freedesktop.portal.Request")
interface PortalRequest : DBusInterface {
    fun Close()
}

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)

    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}
