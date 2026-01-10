package com.ustadmobile.meshrabiya.model

import android.content.Context
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore

/**
 * Manages user keypair in Android Keystore for Meshrabiya user identity.
 */
object UserKeyManager {
    private const val KEY_ALIAS = "meshrabiya_user_key"

    /**
     * Generate a new RSA keypair in Android Keystore.
     */
    @Suppress("UNUSED_PARAMETER")
    fun generateKeypair(context: Context, provider: String = "AndroidKeyStore"): KeyPair {
        println("[DEBUG] UserKeyManager.generateKeypair: provider='$provider'")
        val kpg = KeyPairGenerator.getInstance("RSA", provider)
        if (provider == "AndroidKeyStore") {
            println("[DEBUG] UserKeyManager.generateKeypair: Initializing AndroidKeyStore KeyGenParameterSpec")
            kpg.initialize(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or android.security.keystore.KeyProperties.PURPOSE_VERIFY
                ).setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                 .setUserAuthenticationRequired(false)
                 .build()
            )
        } else {
            println("[DEBUG] UserKeyManager.generateKeypair: Initializing generic 2048-bit RSA")
            kpg.initialize(2048)
        }
        val keypair = kpg.generateKeyPair()
        println("[DEBUG] UserKeyManager.generateKeypair: Generated keypair: public=${keypair.public}, private=${keypair.private}")
        return keypair
    }

    /**
     * Retrieve the keypair from Android Keystore, or null if not present.
     */
    fun getKeypair(provider: String = "AndroidKeyStore"): KeyPair? {
        println("[DEBUG] UserKeyManager.getKeypair: provider='$provider'")
        if (provider == "AndroidKeyStore") {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            println("[DEBUG] UserKeyManager.getKeypair: entry=$entry")
            return if (entry != null) KeyPair(entry.certificate.publicKey, entry.privateKey) else null
        } else {
            println("[DEBUG] UserKeyManager.getKeypair: JVM provider, returning null (no persistence)")
            return null
        }
    }

    /**
     * Rotate the keypair: delete old and generate new.
     */
    fun rotateKeypair(context: Context, provider: String = "AndroidKeyStore"): KeyPair {
        println("[DEBUG] UserKeyManager.rotateKeypair: provider='$provider'")
        if (provider == "AndroidKeyStore") {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(KEY_ALIAS)) {
                println("[DEBUG] UserKeyManager.rotateKeypair: Deleting old key entry for alias '$KEY_ALIAS'")
                ks.deleteEntry(KEY_ALIAS)
            }
        }
        val keypair = generateKeypair(context, provider)
        println("[DEBUG] UserKeyManager.rotateKeypair: Rotated keypair: public=${keypair.public}, private=${keypair.private}")
        return keypair
    }
}
