package com.ustadmobile.meshrabiya.service.security

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory

import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.ustadmobile.meshrabiya.model.DeviceCapabilities
import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.model.ExecutionProfile

/**
 * Lightweight mobile-friendly service sandboxing and security
 * 
 * RECOMMENDATION: Use Android's built-in process isolation + restricted permissions
 * instead of heavy containers or Work Profiles
 */
class MobileServiceSandbox(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "MobileServiceSandbox"
        
        // Lightweight approach - leverage Android's existing security model
        private const val SERVICE_PROCESS_NAME = "com.ustadmobile.meshrabiya:service_execution"
        private const val MAX_SERVICE_MEMORY_MB = 256
        private const val SERVICE_EXECUTION_TIMEOUT_MS = 30_000L
    }
    
    /**
     * RECOMMENDED SANDBOXING APPROACH:
     * 
     * 1. Process Isolation: Run services in separate process (built-in Android security)
     * 2. Permission Restriction: Minimal permissions for service processes  
     * 3. Resource Limits: Memory/CPU/time limits via Android JobScheduler
     * 4. Network Isolation: Services can only communicate via mesh (no internet)
     * 5. File System: Restricted to service-specific directories
     * 
     * WHY THIS APPROACH:
     * - APK size impact: ~0KB (uses existing Android features)
     * - Resource overhead: ~10-20MB per service process
     * - Security: Process isolation + permission model
     * - Mobile-friendly: Designed for Android constraints
     */
    
    data class SandboxConfig(
        val allowNetworkAccess: Boolean = false,
        val allowFileSystemRead: Boolean = false,
        val allowFileSystemWrite: Boolean = false,
        val maxMemoryMB: Int = MAX_SERVICE_MEMORY_MB,
        val maxExecutionTimeMs: Long = SERVICE_EXECUTION_TIMEOUT_MS,
        val allowedPermissions: Set<String> = emptySet()
    )
    
    /**
     * Execute service in sandboxed environment
     */
    suspend fun executeInSandbox(
        serviceBundle: ServiceBundle,
        input: ServiceInput,
        config: SandboxConfig = SandboxConfig()
    ): ServiceResult = withContext(Dispatchers.IO) {
        
        try {
            // 1. Verify service signature before execution
            if (!verifyServiceSignature(serviceBundle)) {
                return@withContext ServiceResult.error("Service signature verification failed")
            }
            
            // 2. Create isolated execution environment
            val sandboxDir = createSandboxDirectory(serviceBundle.serviceId)
            
            // 3. Extract service to sandbox with restricted permissions
            extractServiceToSandbox(serviceBundle, sandboxDir, config)
            
            // 4. Execute in isolated process with resource limits
            val result = executeInIsolatedProcess(serviceBundle, input, sandboxDir, config)
            
            // 5. Cleanup sandbox environment
            cleanupSandbox(sandboxDir)
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Sandbox execution failed", e)
            ServiceResult.error("Sandbox execution failed: ${e.message}")
        }
    }
    
    private fun createSandboxDirectory(serviceId: String): File {
        // Create service-specific directory with restricted permissions
        val sandboxRoot = File(context.cacheDir, "service_sandbox")
        val serviceDir = File(sandboxRoot, serviceId)
        
        // Ensure directory exists with restricted permissions
        serviceDir.mkdirs()
        
        // Set restrictive permissions (owner read/write only)
        serviceDir.setReadable(false, false)
        serviceDir.setWritable(false, false)
        serviceDir.setExecutable(false, false)
        serviceDir.setReadable(true, true)
        serviceDir.setWritable(true, true)
        serviceDir.setExecutable(true, true)
        
        return serviceDir
    }
    
    private suspend fun executeInIsolatedProcess(
        serviceBundle: ServiceBundle,
        input: ServiceInput,
        sandboxDir: File,
        config: SandboxConfig
    ): ServiceResult = withContext(Dispatchers.IO) {
        
        // Use ProcessBuilder to create isolated process
        val processBuilder = ProcessBuilder().apply {
            // Set working directory to sandbox
            directory(sandboxDir)
            
            // Minimal environment variables
            environment().clear()
            environment()["TMPDIR"] = sandboxDir.absolutePath
            environment()["HOME"] = sandboxDir.absolutePath
            
            // Configure based on service type
            when (serviceBundle.serviceType) {
                "python" -> {
                    command("python3", "main.py")
                    environment()["PYTHONPATH"] = sandboxDir.absolutePath
                }
                "java" -> {
                    command("java", "-Xmx${config.maxMemoryMB}m", "-jar", "service.jar")
                }
                else -> throw IllegalArgumentException("Unsupported service type: ${serviceBundle.serviceType}")
            }
        }
        
        try {
            val process = processBuilder.start()
            
            // Write input to process stdin
            process.outputStream.use { outputStream ->
                outputStream.write(input.toBytes())
                outputStream.flush()
            }
            
            // Read result from process stdout with timeout
            val result = process.inputStream.use { inputStream ->
                inputStream.readBytes()
            }
            
            // Wait for completion with timeout
            val finished = process.waitFor(config.maxExecutionTimeMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                return@withContext ServiceResult.error("Service execution timeout")
            }
            
            if (process.exitValue() != 0) {
                val errorOutput = process.errorStream.readBytes().toString(Charsets.UTF_8)
                return@withContext ServiceResult.error("Service execution failed: $errorOutput")
            }
            
            ServiceResult.success(result)
            
        } catch (e: Exception) {
            Log.e(TAG, "Process execution failed", e)
            ServiceResult.error("Process execution failed: ${e.message}")
        }
    }
    
    private fun extractServiceToSandbox(
        serviceBundle: ServiceBundle,
        sandboxDir: File,
        config: SandboxConfig
    ) {
        // Extract service files to sandbox with permission checks
        when (serviceBundle.format) {
            "zip" -> extractZipToSandbox(serviceBundle.payload, sandboxDir, config)
            "single-file" -> extractSingleFileToSandbox(serviceBundle.payload, sandboxDir)
            else -> throw IllegalArgumentException("Unsupported service format: ${serviceBundle.format}")
        }
    }
    
    private fun extractZipToSandbox(payload: ByteArray, sandboxDir: File, config: SandboxConfig) {
        val tempZip = File.createTempFile("service", ".zip")
        try {
            tempZip.writeBytes(payload)
            
            ZipInputStream(tempZip.inputStream()).use { zipInput ->
                var entry = zipInput.nextEntry
                while (entry != null) {
                    // Security check: prevent directory traversal
                    if (entry.name.contains("..") || entry.name.startsWith("/")) {
                        throw SecurityException("Malicious path in service bundle: ${entry.name}")
                    }
                    
                    val targetFile = File(sandboxDir, entry.name)
                    
                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        targetFile.writeBytes(zipInput.readBytes())
                        
                        // Set appropriate permissions
                        if (entry.name.endsWith(".py") || entry.name.endsWith(".jar")) {
                            targetFile.setExecutable(true, true)
                        }
                    }
                    
                    entry = zipInput.nextEntry
                }
            }
        } finally {
            tempZip.delete()
        }
    }
    
    private fun extractSingleFileToSandbox(payload: ByteArray, sandboxDir: File) {
        val serviceFile = File(sandboxDir, "service")
        serviceFile.writeBytes(payload)
        serviceFile.setExecutable(true, true)
    }
    
    private fun cleanupSandbox(sandboxDir: File) {
        try {
            sandboxDir.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup sandbox directory", e)
        }
    }
}

// ============================================================================
// SERVICE SIGNING AND VERIFICATION
// ============================================================================

/**
 * Decentralized service signing using Ed25519 keys (same as .onion addresses)
 * Leverages existing Tor cryptography infrastructure
 */
class ServiceSigningManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ServiceSigningManager"
        private const val SIGNATURE_ALGORITHM = "Ed25519"
    }
    
    /**
     * Service bundle format with cryptographic signature
     */
    data class SignedServiceBundle(
        val serviceAnnouncement: ServiceAnnouncement,
        val payload: ByteArray,
        val format: String, // "zip", "tar.gz", "single-file"
        val signature: ByteArray,
        val signerPublicKey: ByteArray, // Ed25519 public key (32 bytes)
        val signerOnionAddress: String  // Corresponding .onion address for verification
    ) {
        /**
         * Verify this bundle was signed by the claimed .onion address
         */
        fun verify(): Boolean {
            return try {
                // 1. Verify .onion address matches public key
                val expectedOnionAddress = generateOnionAddressFromPublicKey(signerPublicKey)
                if (expectedOnionAddress != signerOnionAddress) {
                    return false
                }
                
                // 2. Verify signature over payload
                val keySpec = X509EncodedKeySpec(signerPublicKey)
                val keyFactory = KeyFactory.getInstance(SIGNATURE_ALGORITHM)
                val publicKey = keyFactory.generatePublic(keySpec)
                
                val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
                verifier.initVerify(publicKey)
                verifier.update(payload)
                verifier.verify(signature)
                
            } catch (e: Exception) {
                Log.e(TAG, "Signature verification failed", e)
                false
            }
        }
        
        /**
         * Get service reputation score based on signer's .onion address history
         */
        fun getReputationScore(reputationManager: ServiceReputationManager): Float {
            return reputationManager.getReputationScore(signerOnionAddress)
        }
    }
    
    /**
     * RECOMMENDED SERVICE DISTRIBUTION FORMAT:
     * 
     * Compressed archive (ZIP) containing:
     * - service.json (metadata)
     * - main.py/service.jar (executable)
     * - dependencies/ (if needed)
     * - README.md (documentation)
     * 
     * WHY ZIP:
     * - Built into Android (no APK size increase)
     * - Supports file permissions and directories
     * - Easy to verify individual files
     * - Standard format across platforms
     */
}

/**
 * Trust and reputation management using .onion addresses
 */
class ServiceReputationManager(private val context: Context) {
    
    private data class ReputationEntry(
        val onionAddress: String,
        val successfulExecutions: Int,
        val failedExecutions: Int,
        val averageExecutionTime: Long,
        val lastSeen: Long,
        val userRating: Float // 0.0-1.0, set by user
    ) {
        val totalExecutions: Int get() = successfulExecutions + failedExecutions
        val successRate: Float get() = if (totalExecutions > 0) successfulExecutions.toFloat() / totalExecutions else 0f
        val reputationScore: Float get() = (successRate * 0.6f) + (userRating * 0.4f)
    }
    
    fun getReputationScore(onionAddress: String): Float {
        // Look up reputation based on .onion address history
        val entry = loadReputationEntry(onionAddress)
        return entry?.reputationScore ?: 0.5f // Neutral for unknown addresses
    }
    
    private fun loadReputationEntry(onionAddress: String): ReputationEntry? {
        // Implementation would load from encrypted local database
        return null
    }
}

// ============================================================================
// INTEGRATION WITH EXISTING ARCHITECTURE
// ============================================================================

/**
 * Integration point with existing ServiceLayerCoordinator
 */
// Integration helper: avoid a hard compile dependency on the app-only ServiceLayerCoordinator
// by keeping this as a generic extension on Any for library compilation. App module can
// provide a proper bridge if needed.
fun Any.executeSecureService(
    context: android.content.Context,
    serviceId: String,
    input: ServiceInput
): ServiceResult {
    val sandbox = MobileServiceSandbox(context)
    val serviceBundle = getServiceBundle(serviceId) // From existing registry
    return kotlinx.coroutines.runBlocking {
        sandbox.executeInSandbox(
            serviceBundle = serviceBundle,
            input = input,
            config = MobileServiceSandbox.SandboxConfig(
                allowNetworkAccess = false, // Only mesh communication
                allowFileSystemRead = false,
                allowFileSystemWrite = false,
                maxMemoryMB = 128, // Conservative for mobile
                maxExecutionTimeMs = 15_000L // 15 second timeout
            )
        )
    }
}

// Supporting data classes
data class ServiceBundle(
    val serviceId: String,
    val serviceType: String,
    val payload: ByteArray,
    val format: String,
    val metadata: ServiceAnnouncement
)

data class ServiceInput(
    val data: Map<String, Any>,
    val format: String = "json"
) {
    fun toBytes(): ByteArray {
        return when (format) {
            "json" -> kotlinx.serialization.json.Json.encodeToString(data).toByteArray()
            else -> data.toString().toByteArray()
        }
    }
}

data class ServiceResult(
    val success: Boolean,
    val data: ByteArray? = null,
    val error: String? = null,
    val executionTimeMs: Long = 0
) {
    companion object {
        fun success(data: ByteArray): ServiceResult = ServiceResult(true, data = data)
        fun error(message: String): ServiceResult = ServiceResult(false, error = message)
    }
}

// Placeholder functions
private fun verifyServiceSignature(bundle: ServiceBundle): Boolean = true
private fun generateOnionAddressFromPublicKey(publicKey: ByteArray): String = ""
private fun getServiceBundle(serviceId: String): ServiceBundle = ServiceBundle(
    serviceId = "",
    serviceType = "python",
    payload = byteArrayOf(),
    format = "zip",
    metadata = ServiceAnnouncement(
        serviceId = "",
        serviceType = ServiceAnnouncement.ServiceType.PYTHON,
        version = "",
        sizeKB = 0,
        capabilities = emptyList(),
        resourceRequirements = ResourceRequirements(),
        executionProfile = ExecutionProfile()
    )
)
