
package com.ustadmobile.meshrabiya.storage

import android.content.Context
import android.os.StatFs
import java.io.File
import java.security.KeyPairGenerator
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.security.PublicKey
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Storage quota management with Android-aware storage calculations
 */
class StorageQuotaManager(
    private val context: Context,
    private var configuration: DistributedStorageManager.StorageConfiguration
) {
    private val directoryQuotas = ConcurrentHashMap<String, Long>()

    fun updateConfiguration(config: DistributedStorageManager.StorageParticipationConfig) {
        for (directory in config.allowedDirectories) {
            directoryQuotas[directory] = calculateDirectoryQuota( config.totalQuota)
        }
    }

    fun canStoreFile(fileSize: Long): Boolean {
        val totalUsed = getCurrentUsage()
        val totalQuota = directoryQuotas.values.sum()
        return (totalUsed + fileSize) <= totalQuota
    }

    fun getQuotaInfo(): QuotaInfo {
        val totalQuota = directoryQuotas.values.sum()
        val usedQuota = getCurrentUsage()
        return QuotaInfo(totalQuota, usedQuota)
    }

    private fun calculateDirectoryQuota( totalQuota: Long): Long {
        return totalQuota / directoryQuotas.size.coerceAtLeast(1)
    }

    private fun getCurrentUsage(): Long {
        var totalUsed = 0L
        for (directory in directoryQuotas.keys) {
            val dirFile = File(directory)
            totalUsed += getDirectorySize(dirFile)
        }
        return totalUsed
    }

    private fun getDirectorySize(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        var size = 0L
        dir.walkTopDown().forEach {
            if (it.isFile) size += it.length()
        }
        return size
    }
}

data class QuotaInfo(
    val totalQuota: Long,
    val usedQuota: Long
) {
    val availableQuota: Long get() = totalQuota - usedQuota
    val utilizationPercent: Float get() = if (totalQuota > 0) usedQuota.toFloat() / totalQuota else 0f
}

/**
 * Encryption manager for stored files and session keys
 */
class StorageEncryptionManager {

    private val AES_KEY_SIZE = 32 // 256 bits
    private val AES_TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private val IV_SIZE = 16
    private val RSA_KEY_SIZE = 2048

    // In production, store keys securely (e.g., Android Keystore)
    private val secretKey: SecretKey = generateSecretKey()
    
    /**
     * Generate service keypair for storage node encryption.
     * 
     * Canonical workflow Step 0: Each storage node has a service keypair.
     * The public key is included in StorageNodeResponse and used by requesters
     * to encrypt chunks so the storage node can decrypt them.
     * 
     * @return Pair of (publicKey, privateKey) as ByteArray
     */
    fun generateServiceKeypair(): Pair<ByteArray, ByteArray> {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(RSA_KEY_SIZE, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        
        return Pair(
            keyPair.public.encoded,
            keyPair.private.encoded
        )
    }

    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(data)
        return iv + encrypted
    }

    fun decrypt(encryptedData: ByteArray): ByteArray {
        if (encryptedData.size < IV_SIZE) throw IllegalArgumentException("Invalid encrypted data")
        val iv = encryptedData.copyOfRange(0, IV_SIZE)
        val cipherText = encryptedData.copyOfRange(IV_SIZE, encryptedData.size)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        return cipher.doFinal(cipherText)
    }

    /**
     * Re-encrypt session keys for a chunk for new recipients.
     * @param sessionKey The raw session key to be encrypted.
     * @param recipientKeyIds List of recipient key IDs.
     * @return Map of recipient key ID to encrypted session key.
     */
    fun reencryptSessionKeysForRecipients(sessionKey: ByteArray, recipientKeyIds: List<Long>): Map<Long, ByteArray> {
        // In production, use each recipient's public key to encrypt the session key.
        // Here, we simulate encryption by using AES with a derived key per recipient.
        return recipientKeyIds.associateWith { keyId ->
            val recipientKey = deriveKeyForRecipient(keyId)
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            val iv = ByteArray(IV_SIZE)
            SecureRandom().nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, recipientKey, IvParameterSpec(iv))
            val encrypted = cipher.doFinal(sessionKey)
            iv + encrypted
        }
    }

    // (Obsolete docstring removed)
    /**
     * Hybrid encryption with per-recipient key encryption (returns encrypted data and sessionKeys map).
     *
     * @param data Raw file data to encrypt
     * @param recipientPublicKeys Map of recipientKeyId to recipient public key (java.security.PublicKey)
     * @return Pair<encryptedData: ByteArray, sessionKeys: Map<Long, ByteArray>>
     */
    fun encryptWithRecipients(
        data: ByteArray,
        recipientPublicKeys: Map<String, String>
    ): Pair<ByteArray, Map<String, ByteArray>> {
        // 1. Generate random symmetric key for this chunk
        val chunkKey = ByteArray(AES_KEY_SIZE)
        SecureRandom().nextBytes(chunkKey)

        // 2. Encrypt data with chunk key
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(chunkKey, "AES"), IvParameterSpec(iv))
        val encryptedData = cipher.doFinal(data)
        val encryptedDataWithIv = iv + encryptedData

        // 3. Encrypt chunk key for each recipient using their public key (RSA)
        val sessionKeys = mutableMapOf<String, ByteArray>()
        for ((keyId, publicKey) in recipientPublicKeys) {
            val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsaCipher.init(Cipher.ENCRYPT_MODE, decodePublicKey(publicKey))
            val encryptedChunkKey = rsaCipher.doFinal(chunkKey)
            sessionKeys[keyId] = encryptedChunkKey
        }

        // 4. Return encrypted data and sessionKeys map
        return Pair(encryptedDataWithIv, sessionKeys)
    }

    private fun generateSecretKey(): SecretKey {
        val keyBytes = ByteArray(AES_KEY_SIZE)
        SecureRandom().nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun deriveKeyForRecipient(keyId: Long): SecretKey {
        // In production, use recipient's public key. Here, derive a key from keyId for demonstration.
        val keyBytes = ByteArray(AES_KEY_SIZE)
        SecureRandom().setSeed(keyId)
        SecureRandom().nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }
    
    fun decodePublicKey(base64PublicKey: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64PublicKey)
        val keySpec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(keySpec)
    }
    // ========== Phase 4.7: Multi-Recipient PGP Encryption ==========
    // Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART2.md Section 8
    
    /**
     * Re-encrypt session key for additional recipients without re-encrypting file data.
     * 
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART2.md Section 8.2
     * 
     * This is used when adding task recipients to already-encrypted files.
     * Only the session key is re-encrypted, not the entire file.
     * 
     * @param encryptedBundle Existing encrypted bundle
     * @param newRecipients New recipients to add (public keys)
     * @return Updated encrypted bundle with new recipient keys
     */
    fun addRecipientsToBundle(
        encryptedBundle: ByteArray,
        newRecipients: List<String>
    ): ByteArray {
        // Parse existing bundle
        val buffer = java.nio.ByteBuffer.wrap(encryptedBundle)
        
        // Read encrypted data (preserved as-is)
        val encryptedDataSize = buffer.getInt()
        val encryptedData = ByteArray(encryptedDataSize)
        buffer.get(encryptedData)
        
        // Read existing recipients
        val existingRecipientCount = buffer.getInt()
        val existingRecipients = mutableListOf<Pair<String, ByteArray>>()
        
        repeat(existingRecipientCount) {
            val recipientIdSize = buffer.getInt()
            val recipientIdBytes = ByteArray(recipientIdSize)
            buffer.get(recipientIdBytes)
            val recipientId = String(recipientIdBytes)
            
            // Read encrypted key (IV + encrypted chunk key)
            val encryptedKeySize = IV_SIZE + AES_KEY_SIZE + 16 // IV + key + padding
            val encryptedKey = ByteArray(encryptedKeySize)
            buffer.get(encryptedKey)
            
            existingRecipients.add(Pair(recipientId, encryptedKey))
        }
        
        // Decrypt chunk key using owner's key (first recipient)
        // TODO: In production, use actual owner private key
        val chunkKey = ByteArray(AES_KEY_SIZE)
        SecureRandom().nextBytes(chunkKey) // Placeholder
        
        // Encrypt chunk key for new recipients
        val newEncryptedKeys = newRecipients.map { recipientId ->
            val recipientKeyId = recipientId.hashCode().toLong()
            val recipientKey = deriveKeyForRecipient(recipientKeyId)
            val keyIv = ByteArray(IV_SIZE)
            SecureRandom().nextBytes(keyIv)
            val keyCipher = Cipher.getInstance(AES_TRANSFORMATION)
            keyCipher.init(Cipher.ENCRYPT_MODE, recipientKey, IvParameterSpec(keyIv))
            val encryptedKey = keyCipher.doFinal(chunkKey)
            Pair(recipientId, keyIv + encryptedKey)
        }
        
        // Build updated bundle
        val allRecipients = existingRecipients + newEncryptedKeys
        val newBuffer = java.nio.ByteBuffer.allocate(
            4 + encryptedData.size +
            4 + allRecipients.sumOf { (id, key) -> 4 + id.toByteArray().size + key.size }
        )
        
        // Write encrypted data
        newBuffer.putInt(encryptedData.size)
        newBuffer.put(encryptedData)
        
        // Write recipient count
        newBuffer.putInt(allRecipients.size)
        
        // Write all recipients (existing + new)
        allRecipients.forEach { (recipientId, encryptedKey) ->
            val recipientIdBytes = recipientId.toByteArray()
            newBuffer.putInt(recipientIdBytes.size)
            newBuffer.put(recipientIdBytes)
            newBuffer.put(encryptedKey)
        }
        
        return newBuffer.array()
    }
    
    /**
     * Remove recipients from encrypted bundle.
     * 
     * @param encryptedBundle Existing encrypted bundle
     * @param recipientsToRemove Recipient IDs to remove
     * @return Updated encrypted bundle without specified recipients
     */
    fun removeRecipientsFromBundle(
        encryptedBundle: ByteArray,
        recipientsToRemove: List<String>
    ): ByteArray {
        // Parse existing bundle
        val buffer = java.nio.ByteBuffer.wrap(encryptedBundle)
        
        // Read encrypted data (preserved as-is)
        val encryptedDataSize = buffer.getInt()
        val encryptedData = ByteArray(encryptedDataSize)
        buffer.get(encryptedData)
        
        // Read existing recipients
        val existingRecipientCount = buffer.getInt()
        val remainingRecipients = mutableListOf<Pair<String, ByteArray>>()
        
        repeat(existingRecipientCount) {
            val recipientIdSize = buffer.getInt()
            val recipientIdBytes = ByteArray(recipientIdSize)
            buffer.get(recipientIdBytes)
            val recipientId = String(recipientIdBytes)
            
            val encryptedKeySize = IV_SIZE + AES_KEY_SIZE + 16
            val encryptedKey = ByteArray(encryptedKeySize)
            buffer.get(encryptedKey)
            
            // Keep only if not in removal list
            if (recipientId !in recipientsToRemove) {
                remainingRecipients.add(Pair(recipientId, encryptedKey))
            }
        }
        
        // Build updated bundle
        val newBuffer = java.nio.ByteBuffer.allocate(
            4 + encryptedData.size +
            4 + remainingRecipients.sumOf { (id, key) -> 4 + id.toByteArray().size + key.size }
        )
        
        // Write encrypted data
        newBuffer.putInt(encryptedData.size)
        newBuffer.put(encryptedData)
        
        // Write recipient count
        newBuffer.putInt(remainingRecipients.size)
        
        // Write remaining recipients
        remainingRecipients.forEach { (recipientId, encryptedKey) ->
            val recipientIdBytes = recipientId.toByteArray()
            newBuffer.putInt(recipientIdBytes.size)
            newBuffer.put(recipientIdBytes)
            newBuffer.put(encryptedKey)
        }
        
        return newBuffer.array()
    }
}

/**
 * Tracks replication health across mesh nodes
 */
class ReplicationTracker {
    private val fileReplications = ConcurrentHashMap<String, List<String>>()
    private val nodeHealth = ConcurrentHashMap<String, Float>()

    fun trackReplication(filePath: String, nodeIds: List<String>) {
        fileReplications[filePath] = nodeIds
    }

    fun getFileHealth(filePath: String): Float {
        val nodes = fileReplications[filePath] ?: return 0f
        val healthyNodes = nodes.count { nodeId ->
            nodeHealth.getOrDefault(nodeId, 1.0f) > 0.5f
        }
        return if (nodes.isNotEmpty()) healthyNodes.toFloat() / nodes.size else 0f
    }

    fun getOverallHealth(): Float {
        if (fileReplications.isEmpty()) return 1.0f
        val totalHealth = fileReplications.keys.sumOf { getFileHealth(it).toDouble() }
        return (totalHealth / fileReplications.size).toFloat()
    }

    fun updateNodeHealth(nodeId: String, health: Float) {
        nodeHealth[nodeId] = health
    }
}