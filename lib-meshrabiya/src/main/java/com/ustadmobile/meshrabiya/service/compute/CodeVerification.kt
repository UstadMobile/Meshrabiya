package com.ustadmobile.meshrabiya.service.compute

// Code bundle signature verification and hash calculation
import java.security.MessageDigest

object CodeVerification {
    fun verifyCodeBundle(code: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        // Production-ready cryptographic verification
        // Use standard signature verification (e.g., SHA256withRSA)
        return try {
            val keySpec = java.security.spec.X509EncodedKeySpec(publicKey)
            val kf = java.security.KeyFactory.getInstance("RSA")
            val pubKey = kf.generatePublic(keySpec)
            val sig = java.security.Signature.getInstance("SHA256withRSA")
            sig.initVerify(pubKey)
            sig.update(code)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }
    fun calculateHash(code: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(code)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
