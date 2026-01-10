package com.ustadmobile.meshrabiya.util

import java.security.PublicKey
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

fun PublicKey.toHash(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(this.encoded)
    return hashBytes.joinToString("") { "%02x".format(it) }
}

fun ByteArray.toPublicKey(): PublicKey {
    val keySpec = X509EncodedKeySpec(this)
    return KeyFactory.getInstance("RSA").generatePublic(keySpec)
}