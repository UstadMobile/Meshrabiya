package com.ustadmobile.meshrabiya.service.security

import android.util.Log
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import kotlinx.serialization.json.Json
import android.content.Context

import com.ustadmobile.meshrabiya.model.DeviceCapabilities
// import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.model.ExecutionProfile
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import  com.ustadmobile.meshrabiya.service.compute.model.ServiceManifest

/**
 * DECENTRALIZED SERVICE SIGNING using Ed25519 keys
 * 
 * Leverages existing .onion address cryptography from Tor:
 * - Each service author has an Ed25519 keypair  
 * - Public key derives .onion address (service author identity)
 * - Services signed with author's private key
 * - Verification uses public key + .onion address
 * 
 * NO CENTRAL AUTHORITY NEEDED - fully decentralized trust model
 */
class DecentralizedServiceSigning {
    
    companion object {
        private const val TAG = "DecentralizedServiceSigning"
        
        /**
         * RECOMMENDED SERVICE BUNDLE FORMAT:
         * 
         * service-bundle.zip:
         * ├── manifest.json          # Service metadata + signature info
         * ├── service/
         * │   ├── main.py           # Main executable
         * │   ├── requirements.txt  # Dependencies
         * │   └── assets/           # Additional files
         * └── signatures/
         *     ├── author.sig        # Author's signature over service files
         *     └── author.pub        # Author's public key (Ed25519)
         * 
         * WHY THIS FORMAT:
         * - Standard ZIP (built into Android)
         * - Separates code from signatures
         * - Supports multiple signers (future: community review)
         * - Easy to verify individual components
         */
    }
    
    
    @Serializable
    data class InputDef(
        val name: String,
        val type: String,
        val description: String? = null
    )
    
    @Serializable
    data class OutputDef(
        val name: String,
        val type: String,
        val description: String? = null
    )
    
    @Serializable
    data class AuthorInfo(
        val onionAddress: String,        // Author's .onion identity
        val publicKeyBase64: String,     // Ed25519 public key
        val displayName: String? = null, // Optional human-readable name
        val contactInfo: String? = null  // Optional contact (.onion address, etc.)
    )
    
    @Serializable  
    data class SignatureInfo(
        val algorithm: String = "Ed25519",
        val signatureBase64: String,     // Signature over service files
        val signedHash: String,          // SHA-256 of all service files
        val timestamp: Long              // When signed
    )
    
    @Serializable
    data class FileHash(
        val path: String,
        val sha256: String,
        val size: Long
    )
    
    /**
     * Create signed service bundle
     */
    fun createSignedBundle(
        serviceFiles: Map<String, ByteArray>, // path -> content
        authorKeyPair: Pair<PrivateKey, PublicKey>,
        authorOnionAddress: String,
        // metadata: ServiceAnnouncement
    ): ByteArray {
        
        // 1. Calculate hashes of all service files
        val fileHashes = serviceFiles.map { (path, content) ->
            FileHash(
                path = path,
                sha256 = calculateSHA256(content),
                size = content.size.toLong()
            )
        }
        
        // 2. Create combined hash of all files
        val combinedHash = calculateCombinedHash(serviceFiles)
        
        // 3. Sign the combined hash
        val signature = signData(combinedHash.toByteArray(), authorKeyPair.first)
        
        // 4. Create manifest
        val manifest = ServiceManifest(
            serviceId = metadata.serviceId,
            name = metadata.serviceId, // Could be different
            version = metadata.version,
            author = AuthorInfo(
                onionAddress = authorOnionAddress,
                publicKeyBase64 = Base64.getEncoder().encodeToString(authorKeyPair.second.encoded),
                displayName = null // Could be provided
            ),
            signature = SignatureInfo(
                signatureBase64 = Base64.getEncoder().encodeToString(signature),
                signedHash = combinedHash,
                timestamp = System.currentTimeMillis()
            ),
            serviceType = metadata.serviceType.name,
            capabilities = metadata.capabilities,
            // resourceRequirements = metadata.resourceRequirements,
            files = fileHashes
        )
        
        // 5. Create ZIP bundle
        return createZipBundle(serviceFiles, manifest)
    }
    
    /**
     * Verify signed service bundle
     */
    fun verifySignedBundle(bundleBytes: ByteArray): VerificationResult {
        try {
            // 1. Extract ZIP contents
            val (serviceFiles, manifest) = extractZipBundle(bundleBytes)
            
            // 2. Verify .onion address matches public key
            val expectedOnionAddress = deriveOnionAddress(manifest.author.publicKeyBase64)
            if (expectedOnionAddress != manifest.author.onionAddress) {
                return VerificationResult.Invalid("Onion address doesn't match public key")
            }
            
            // 3. Verify file hashes
            for (fileHash in manifest.files) {
                val fileContent = serviceFiles[fileHash.path]
                    ?: return VerificationResult.Invalid("Missing file: ${fileHash.path}")
                
                val actualHash = calculateSHA256(fileContent)
                if (actualHash != fileHash.sha256) {
                    return VerificationResult.Invalid("File hash mismatch: ${fileHash.path}")
                }
            }
            
            // 4. Verify signature
            val combinedHash = calculateCombinedHash(serviceFiles)
            if (combinedHash != manifest.signature.signedHash) {
                return VerificationResult.Invalid("Combined hash mismatch")
            }
            
            val publicKeyBytes = Base64.getDecoder().decode(manifest.author.publicKeyBase64)
            val signatureBytes = Base64.getDecoder().decode(manifest.signature.signatureBase64)
            
            if (!verifySignature(combinedHash.toByteArray(), signatureBytes, publicKeyBytes)) {
                return VerificationResult.Invalid("Signature verification failed")
            }
            
            // 5. Check reputation (if available)
            val reputation = getAuthorReputation(manifest.author.onionAddress)
            
            return VerificationResult.Valid(
                authorOnionAddress = manifest.author.onionAddress,
                reputation = reputation,
                signedAt = manifest.signature.timestamp,
                manifest = manifest
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Bundle verification failed", e)
            return VerificationResult.Invalid("Verification error: ${e.message}")
        }
    }
    
    sealed class VerificationResult {
        data class Valid(
            val authorOnionAddress: String,
            val reputation: Float, // 0.0-1.0
            val signedAt: Long,
            val manifest: ServiceManifest
        ) : VerificationResult()
        
        data class Invalid(
            val reason: String
        ) : VerificationResult()
    }
    
    /**
     * Generate Ed25519 keypair for service authors
     */
    fun generateAuthorKeyPair(): Pair<PrivateKey, PublicKey> {
        val keyGen = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = keyGen.generateKeyPair()
        return Pair(keyPair.private, keyPair.public)
    }
    
    private fun signData(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }
    
    private fun verifySignature(data: ByteArray, signature: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return try {
            val keySpec = java.security.spec.X509EncodedKeySpec(publicKeyBytes)
            val keyFactory = java.security.KeyFactory.getInstance("Ed25519")
            val publicKey = keyFactory.generatePublic(keySpec)
            
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(data)
            verifier.verify(signature)
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed", e)
            false
        }
    }
    
    private fun calculateSHA256(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    private fun calculateCombinedHash(files: Map<String, ByteArray>): String {
        // Sort files by path for deterministic hash
        val sortedFiles = files.toSortedMap()
        val combinedData = sortedFiles.map { (path, content) ->
            "$path:${calculateSHA256(content)}"
        }.joinToString(",")
        
        return calculateSHA256(combinedData.toByteArray())
    }
    
    private fun deriveOnionAddress(publicKeyBase64: String): String {
        // Simplified - real implementation would use Tor's address derivation
        // This would calculate the .onion v3 address from the Ed25519 public key
        return "example${publicKeyBase64.take(8)}.onion"
    }
    
    private fun createZipBundle(
        serviceFiles: Map<String, ByteArray>,
        manifest: ServiceManifest
    ): ByteArray {
        // Create ZIP with manifest.json + service files
        // Implementation would use Java's ZipOutputStream
        return ByteArray(0) // Placeholder
    }
    
    private fun extractZipBundle(bundleBytes: ByteArray): Pair<Map<String, ByteArray>, ServiceManifest> {
        // Extract ZIP and parse manifest.json
        // Implementation would use Java's ZipInputStream
            return Pair(
                emptyMap(),
                ServiceManifest(
                    serviceId = "",
                    name = "",
                    version = "",
                    author = AuthorInfo(onionAddress = "", publicKeyBase64 = ""),
                    signature = SignatureInfo(
                        algorithm = "Ed25519",
                        signatureBase64 = "",
                        signedHash = "",
                        timestamp = 0L
                    ),
                    serviceType = "",
                    capabilities = emptyList(),
                    resourceRequirements = ResourceRequirements(minMemoryMB = 0, minStorageMB = 0),
                    files = emptyList(),
                    builtin = false,
                    inputs = emptyList(),
                    outputs = emptyList()
                )
            )
    }
    
    private fun getAuthorReputation(onionAddress: String): Float {
        // Look up reputation from local database or mesh consensus
        return 0.5f // Neutral for unknown authors
    }
}

/**
 * WEB OF TRUST for service authors
 * Uses .onion addresses as persistent identities
 */
class ServiceWebOfTrust(private val context: Context) {
    
    data class TrustRelationship(
        val trusterOnionAddress: String,  // Who is giving trust
        val trusteeOnionAddress: String,  // Who is being trusted
        val trustLevel: Float,            // 0.0-1.0
        val reason: String,               // Why this trust is given
        val timestamp: Long
    )
    
    /**
     * INTEGRATION WITH EXISTING FRIENDS SYSTEM:
     * 
     * Your app already has a friends system with .onion addresses.
     * This can be extended to include service author trust:
     * 
     * 1. Users can mark friends as "trusted service authors"
     * 2. Services from trusted authors auto-approved
     * 3. Web of trust: trust friends' recommendations
     * 4. Reputation propagates through friend network
     */
    
    fun calculateTrustScore(
        authorOnionAddress: String,
        userOnionAddress: String,
        friendsList: List<String> // User's friends' .onion addresses
    ): Float {
        
        // Direct trust (user trusts author directly)
        val directTrust = getDirectTrust(userOnionAddress, authorOnionAddress)
        if (directTrust > 0) {
            return directTrust
        }
        
        // Indirect trust (friends trust this author)
        val friendTrusts = friendsList.mapNotNull { friendOnion ->
            getDirectTrust(friendOnion, authorOnionAddress)
        }
        
        if (friendTrusts.isNotEmpty()) {
            // Average of friends' trust, weighted by user's trust in those friends
            return friendTrusts.average().toFloat() * 0.7f // Reduced weight for indirect trust
        }
        
        // No trust information
        return 0.3f // Low default trust for unknown authors
    }
    
    private fun getDirectTrust(trusterOnion: String, trusteeOnion: String): Float {
        // Look up direct trust relationship
        return 0f // Placeholder
    }
}

/**
 * SERVICE DISTRIBUTION using existing mesh infrastructure
 */
class MeshServiceDistribution(
    private val meshNode: AndroidVirtualNode
) {
    
    /**
     * LEVERAGE EXISTING MESHRABIYA INFRASTRUCTURE:
     * 
     * 1. Service announcements via originator messages (already implemented)
     * 2. Service bundles transferred via existing socket infrastructure
     * 3. DHT-like storage across mesh nodes
     * 4. Automatic replication to nearby nodes
     */
    
    // fun announceService(signedBundle: ByteArray, manifest: DecentralizedServiceSigning.ServiceManifest) {
    //     // Add service announcement to originator message
    //         val serviceAnnouncement = ServiceAnnouncement(
    //             serviceId = manifest.serviceId,
    //             serviceType = ServiceAnnouncement.ServiceType.valueOf(manifest.serviceType.uppercase()),
    //             version = manifest.version,
    //             sizeKB = signedBundle.size / 1024,
    //             capabilities = manifest.capabilities,
    //             resourceRequirements = manifest.resourceRequirements,
    //             executionProfile = ExecutionProfile(
    //                 profileName = "mesh-distributed-service",
    //                 cpuCores = 1,
    //                 gpuEnabled = false,
    //                 memoryMB = 0,
    //                 storageMB = signedBundle.size / 1024
    //             )
    //         )
        
    //     // This would integrate with existing service announcement system
    //     // meshNode.announceService(serviceAnnouncement, signedBundle)
    // }
    
    // fun requestService(serviceId: String, requesterOnionAddress: String): ByteArray? {
    //     // Request service bundle from mesh
    //     // Uses existing mesh routing to find service host
    //     return meshNode.requestServiceBundle(serviceId, requesterOnionAddress)
    // }
}
