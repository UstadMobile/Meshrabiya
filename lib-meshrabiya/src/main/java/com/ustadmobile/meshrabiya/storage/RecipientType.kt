package com.ustadmobile.meshrabiya.storage

import kotlinx.serialization.Serializable

// import java.security.PublicKey

/**
 * Recipient type for file access control.
 * 
 * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md Section 4
 * 
 * USER recipients: Long-lived user keypairs (persistent nodes)
 * TASK recipients: Ephemeral task keypairs (temporary, auto-expire)
 */
@Serializable
enum class RecipientType {
    /**
     * Long-lived user/node keypairs.
     * Used for persistent node identities and user accounts.
     * Never expires automatically.
     */
    USER,
    
    /**
     * Ephemeral task keypairs.
     * Generated per-task, expires after task completion or timeout.
     * Provides task-level data isolation from compute node operators.
     */
    TASK
}

/**
 * Recipient entry with type information.
 * 
 * @property publicKey PGP public key (Base64-encoded or key ID)
 * @property recipientType Type of recipient (USER or TASK)
 * @property expiresAt Optional expiration timestamp (milliseconds since epoch). 
 *                     Required for TASK recipients, optional for USER recipients.
 * @property taskId Optional task ID for TASK recipients.
 */
@Serializable
data class RecipientEntry(
    val publicKey: String,
    val recipientType: RecipientType,
    val recipientId: String, // Used for both USER and TASK
    val expiresAt: Long? = null
) {
    init {
        // Validation: TASK recipients must have expiration and recipientId
        if (recipientType == RecipientType.TASK) {
            require(expiresAt != null) { "TASK recipients must have expiration time" }
            require(recipientId.isNotBlank()) { "TASK recipients must have recipientId" }
        }
        // USER recipients must have recipientId (userId)
        if (recipientType == RecipientType.USER) {
            require(recipientId.isNotBlank()) { "USER recipients must have recipientId (userId)" }
        }
    }

   
    /**
     * Checks if this recipient is expired.
     * USER recipients never expire (returns false).
     * TASK recipients expire based on expiresAt timestamp.
     */
    fun isExpired(): Boolean {
        if (recipientType == RecipientType.USER) return false
        val expiry = expiresAt ?: return false
        return System.currentTimeMillis() > expiry
    }
  

     
}

