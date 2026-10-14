package com.gng.app.crypto

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
fun String.unb64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

private val random = SecureRandom()
fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

/**
 * This device's X25519 identity key (HPKE). The private half never leaves the device: Tink keeps it
 * in shared preferences, encrypted under a master key in the Android Keystore.
 */
class IdentityKeys(context: Context) {
    private val handle: KeysetHandle

    init {
        HybridConfig.register()
        handle = AndroidKeysetManager.Builder()
            .withSharedPref(context, "identity_keyset", "gng_crypto")
            .withKeyTemplate(KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"))
            .withMasterKeyUri("android-keystore://gng_identity_master")
            .build()
            .keysetHandle
    }

    private val publicBytes: ByteArray = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(handle.publicKeysetHandle)
    val publicKey: String = publicBytes.b64()
    val keyId: String = keyIdOf(publicKey)

    private val decrypter = handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)

    fun unwrap(wrapped: String, context: String): ByteArray = decrypter.decrypt(wrapped.unb64(), context.toByteArray())

    companion object {
        fun keyIdOf(publicKey: String): String = publicKey.unb64().sha256().hex().take(16)

        fun wrap(recipientPublicKey: String, secret: ByteArray, context: String): String {
            val pub = TinkProtoKeysetFormat.parseKeysetWithoutSecret(recipientPublicKey.unb64())
            return pub.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
                .encrypt(secret, context.toByteArray())
                .b64()
        }

        /**
         * A 60-digit code two people compare to make sure the server didn't swap anyone's key.
         * Same on both phones because the keys are sorted before hashing.
         */
        fun safetyCode(publicKeyA: String, publicKeyB: String): String {
            val (first, second) = listOf(publicKeyA, publicKeyB).sorted()
            val digest = (first + "|" + second).toByteArray().sha256()
            return (0 until 12).joinToString(" ") { i ->
                val chunk = ((digest[i * 2].toInt() and 0xff) shl 8) or (digest[i * 2 + 1].toInt() and 0xff)
                "%05d".format(chunk % 100000)
            }
        }
    }
}

/** AES-256-GCM. Output is nonce (12 bytes) followed by ciphertext and tag. */
object Aead {
    private const val NONCE = 12

    fun seal(key: ByteArray, plaintext: ByteArray, aad: String): ByteArray {
        val nonce = randomBytes(NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray())
        return nonce + cipher.doFinal(plaintext)
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, NONCE))
        cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(sealed, NONCE, sealed.size - NONCE)
    }
}
