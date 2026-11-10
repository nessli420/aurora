package com.aurora.music.desktop.mac

import com.aurora.music.desktop.platform.SecretStore
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.Base64

// keeps aurora's settings key in the login keychain, the mac counterpart of the linux secret service
class KeychainStore(
    private val service: String = "com.aurora.music",
    private val account: String = "settings-key",
) : SecretStore {
    override fun read(): ByteArray? {
        val length = IntByReference()
        val data = PointerByReference()
        val status = find(length, data, null)
        if (status == ERR_NOT_FOUND) return null
        check(status == 0) { "Keychain read failed ($status)" }
        val value = data.value ?: return null
        return try {
            Base64.getDecoder().decode(value.getByteArray(0, length.value))
        } finally {
            Security.INSTANCE.SecKeychainItemFreeContent(null, value)
        }
    }

    override fun write(secret: ByteArray): Boolean {
        val encoded = Base64.getEncoder().encode(secret)
        val item = PointerByReference()
        val found = find(null, null, item)
        val status = if (found == 0 && item.value != null) {
            try {
                Security.INSTANCE.SecKeychainItemModifyAttributesAndData(item.value, null, encoded.size, encoded)
            } finally {
                CoreFoundation.INSTANCE.CFRelease(item.value)
            }
        } else {
            val s = service.toByteArray()
            val a = account.toByteArray()
            Security.INSTANCE.SecKeychainAddGenericPassword(null, s.size, s, a.size, a, encoded.size, encoded, null)
        }
        return status == 0
    }

    private fun find(length: IntByReference?, data: PointerByReference?, item: PointerByReference?): Int {
        val s = service.toByteArray()
        val a = account.toByteArray()
        return Security.INSTANCE.SecKeychainFindGenericPassword(null, s.size, s, a.size, a, length, data, item)
    }

    @Suppress("FunctionName")
    private interface Security : Library {
        fun SecKeychainFindGenericPassword(
            keychain: Pointer?, serviceLength: Int, service: ByteArray, accountLength: Int, account: ByteArray,
            passwordLength: IntByReference?, passwordData: PointerByReference?, item: PointerByReference?,
        ): Int

        fun SecKeychainAddGenericPassword(
            keychain: Pointer?, serviceLength: Int, service: ByteArray, accountLength: Int, account: ByteArray,
            passwordLength: Int, password: ByteArray, item: PointerByReference?,
        ): Int

        fun SecKeychainItemModifyAttributesAndData(item: Pointer, attributes: Pointer?, length: Int, data: ByteArray): Int
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer): Int

        companion object {
            val INSTANCE: Security by lazy { Native.load("Security", Security::class.java) }
        }
    }

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFRelease(ref: Pointer)

        companion object {
            val INSTANCE: CoreFoundation by lazy { Native.load("CoreFoundation", CoreFoundation::class.java) }
        }
    }

    private companion object {
        const val ERR_NOT_FOUND = -25300
    }
}
