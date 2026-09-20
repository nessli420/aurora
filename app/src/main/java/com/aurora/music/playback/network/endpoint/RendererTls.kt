package com.aurora.music.playback.network.endpoint

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyInfo
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

internal object RendererTls {
    private const val ALIAS = "aurora_renderer_tls_v1"

    @Synchronized fun server(): Pair<SSLContext, String> {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) {
            val existing = store.getKey(ALIAS, null) as PrivateKey
            val info = KeyFactory.getInstance(existing.algorithm, "AndroidKeyStore").getKeySpec(existing, KeyInfo::class.java)
            if (KeyProperties.DIGEST_NONE !in info.digests) store.deleteEntry(ALIAS)
        }
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=Aurora Renderer"))
                    .setCertificateSerialNumber(BigInteger(128, SecureRandom()).abs().add(BigInteger.ONE))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 86_400_000))
                    .build())
                generateKeyPair()
            }
        }
        val chain = store.getCertificateChain(ALIAS).map { it as X509Certificate }.toTypedArray()
        val key = store.getKey(ALIAS, null) as PrivateKey
        val manager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (keyType?.startsWith("EC") == true) arrayOf(ALIAS) else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
                getServerAliases(keyType, issuers)?.firstOrNull()
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
                getServerAliases(keyType, issuers)?.firstOrNull()
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) chain else null
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null
        }
        val context = SSLContext.getInstance("TLSv1.2").apply { init(arrayOf(manager), null, SecureRandom()) }
        return context to fingerprint(chain.first())
    }

    fun fingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    fun pinnedTrust(fingerprint: String): X509TrustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw java.security.cert.CertificateException("Client certificate authentication is unsupported.")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull()
                ?: throw java.security.cert.CertificateException("Missing renderer certificate.")
            certificate.checkValidity()
            if (!MessageDigest.isEqual(fingerprint.toByteArray(), fingerprint(certificate).toByteArray())) {
                throw java.security.cert.CertificateException("Renderer certificate changed. Pair again.")
            }
        }
    }
}
