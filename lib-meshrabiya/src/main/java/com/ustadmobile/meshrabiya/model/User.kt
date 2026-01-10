package com.ustadmobile.meshrabiya.model

import java.security.KeyPair
import java.security.PublicKey
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.util.toHash
/**
 * User identity for Meshrabiya mesh network.
 * - userId: SHA-256 hash of publicKey
 * - publicKey: user's public key
 * - nickname: user-chosen display name
 * - keypair: user's keypair (private key stored securely)
 */
data class User(
    val userId: String,           // Hash of publicKey
    val publicKey: PublicKey,     // Public key
    var nickname: String,
    val keypair: KeyPair,          // Stored in Android Keystore
    val entry: RecipientEntry
)

// fun PublicKey.toHash(): String {
//     val digest = java.security.MessageDigest.getInstance("SHA-256")
//     val hashBytes = digest.digest(this.encoded)
//     return hashBytes.joinToString("") { "%02x".format(it) }
// }
