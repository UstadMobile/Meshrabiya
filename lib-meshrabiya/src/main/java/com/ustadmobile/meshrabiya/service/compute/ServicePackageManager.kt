package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.PrivateKey
import java.security.PublicKey
// import com.ustadmobile.meshrabiya.service.security.DecentralizedServiceSigning
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import com.ustadmobile.meshrabiya.service.compute.model.ServiceManifest

/**
 * SERVICE PACKAGE MANAGEMENT SYSTEM
 * 
 * Handles packaging, validation, and installation of distributed compute services.
 * Supports both compressed archives for distribution and local testing workflows.
 */
class ServicePackageManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ServicePackageManager"
        
        // Package format constants
        const val PACKAGE_EXTENSION = ".meshsvc"
        const val MANIFEST_FILENAME = "manifest.json"
        const val SERVICE_CODE_DIR = "service/"
        const val MODEL_DATA_DIR = "models/"
        const val ASSETS_DIR = "assets/"
        const val METADATA_DIR = "meta/"
        
        // Local testing constants
        const val LOCAL_TEST_DIR = "local_test_services"
        const val DEVELOPMENT_SIGNATURE = "DEV_UNSIGNED"
        
        // Validation constants
        const val MAX_PACKAGE_SIZE_MB = 50
        const val MAX_MODEL_SIZE_MB = 30
        const val SUPPORTED_PACKAGE_VERSION = "1.0"
    }
    
    
    
    @Serializable
    data class RuntimeSpec(
        val language: String,                    // "kotlin", "java", "python", "javascript", "native"
        val languageVersion: String,             // "1.8", "11", "3.9", "ES2020", "C++17"
        val runtime: String,                     // "jvm", "python", "nodejs", "native", "wasm"
        val runtimeVersion: String,              // "17", "3.9.7", "16.14.0", "1.0"
        val executionMode: String,               // "interpreted", "compiled", "bytecode", "native"
        val sandboxCompatible: Boolean,          // Can run in restricted sandbox
        val requiresNativeLibs: Boolean,         // Needs native libraries
        val crossPlatform: Boolean               // Same code runs on different platforms
    )
    
    @Serializable
    data class ResourceSpec(
        val minMemoryMB: Int,
        val maxMemoryMB: Int,
        val estimatedCpuUsage: Float,        // 0.0 to 1.0
        val storageRequiredMB: Int,
        val networkBandwidthKbps: Int?,
        val requiresGPU: Boolean = false,        // GPU acceleration needed
        val requiresSpecialHardware: List<String> = listOf() // ["camera", "sensors", "nfc"]
    )
    
    @Serializable
    data class SignatureInfo(
        val packageHash: String,                 // SHA-256 of entire package
        val authorSignature: String,             // Ed25519 signature by author
        val signatureAlgorithm: String,          // "Ed25519"
        val trustedBy: List<String>,             // List of endorsing .onion addresses
        val auditReports: List<AuditReport>?     // Third-party security audits
    )
    
    @Serializable
    data class ValidationSpec(
        val inputSchema: String,                 // JSON schema for inputs
        val maxInputSizeBytes: Int,
        val allowedInputTypes: List<String>,
        val sanitizationRequired: Boolean
    )
    
    @Serializable
    data class ModelFileSpec(
        val filename: String,
        val modelType: String,                   // "tensorflow", "pytorch", "onnx"
        val sizeBytes: Long,
        val sha256Hash: String,
        val compressionType: String?             // "gzip", "brotli", null
    )
    
    @Serializable
    data class AssetFileSpec(
        val filename: String,
        val purpose: String,                     // "config", "data", "resource"
        val sizeBytes: Long,
        val sha256Hash: String
    )
    
    @Serializable
    data class DependencySpec(
        val name: String,
        val version: String,
        val repository: String,                  // "maven", "pypi", "npm", "apt", "custom"
        val platform: String,                    // "jvm", "python", "nodejs", "native", "any"
        val optional: Boolean = false,
        val downloadUrl: String? = null,         // Custom download location
        val integrity: String? = null            // SHA-256 hash for verification
    )
    
    @Serializable
    data class TestCaseSpec(
        val name: String,
        val inputData: String,                   // Base64 encoded test input
        val expectedOutput: String,              // Expected result
        val timeoutSeconds: Int
    )
    
    @Serializable
    data class AuditReport(
        val auditorOnionAddress: String,
        val auditDate: Long,
        val securityRating: String,              // "SAFE", "CAUTION", "UNSAFE"
        val reportUrl: String?
    )
    
    /**
     * PACKAGE CREATION FOR DISTRIBUTION
     * 
     * Creates a compressed .meshsvc package for I2P/BitTorrent distribution
     */
    suspend fun createServicePackage(
        manifest: ServiceManifest,
        serviceCodePath: String,
        modelDataPath: String?,
        assetsPath: String?,
        outputPath: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Creating service package: ${manifest.packageId}")
            
            // Validate manifest
            validateManifest(manifest)
            
            val packageFile = File(outputPath)
            packageFile.parentFile?.mkdirs()
            
            ZipOutputStream(FileOutputStream(packageFile)).use { zipOut ->
                // Add manifest
                val manifestJson = Json.encodeToString(ServiceManifest.serializer(), manifest)
                addToZip(zipOut, MANIFEST_FILENAME, manifestJson.toByteArray())
                
                // Add service code
                addDirectoryToZip(zipOut, serviceCodePath, SERVICE_CODE_DIR)
                
                // Add model data if provided
                modelDataPath?.let { path ->
                    addDirectoryToZip(zipOut, path, MODEL_DATA_DIR)
                }
                
                // Add assets if provided
                assetsPath?.let { path ->
                    addDirectoryToZip(zipOut, path, ASSETS_DIR)
                }
                
                // Add metadata
                addMetadataToZip(zipOut, manifest)
            }
            
            // Verify package integrity
            val packageHash = calculateFileHash(packageFile)
            Log.i(TAG, "Package created successfully: $outputPath (hash: $packageHash)")
            
            Result.success(packageHash)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create service package", e)
            Result.failure(e)
        }
    }
    
    /**
     * LOCAL TESTING WORKFLOW
     * 
     * Allows developers to test services locally before distribution
     */
    suspend fun createLocalTestPackage(
        manifest: ServiceManifest,
        serviceCodePath: String,
        modelDataPath: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Create development version of manifest
            val devManifest = manifest.copy(
                signatureInfo = SignatureInfo(
                    packageHash = "DEV_HASH",
                    authorSignature = DEVELOPMENT_SIGNATURE,
                    signatureAlgorithm = "DEV_UNSIGNED",
                    trustedBy = listOf("LOCAL_DEVELOPMENT"),
                    auditReports = null
                ),
                packageVersion = "${manifest.packageVersion}-DEV"
            )
            
            val testDir = File(context.filesDir, LOCAL_TEST_DIR)
            testDir.mkdirs()
            
            val packagePath = File(testDir, "${manifest.packageId}-dev$PACKAGE_EXTENSION").absolutePath
            
            Log.d(TAG, "Creating local test package: $packagePath")
            return@withContext createServicePackage(
                devManifest,
                serviceCodePath,
                modelDataPath,
                null, // No assets for local testing
                packagePath
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create local test package", e)
            Result.failure(e)
        }
    }
    
    /**
     * PACKAGE VALIDATION AND INSTALLATION
     * 
     * Validates and installs service packages from I2P/BitTorrent distribution
     */
    suspend fun validateAndInstallPackage(packagePath: String): Result<ServiceManifest> = withContext(Dispatchers.IO) {
        try {
            val packageFile = File(packagePath)
            
            // Check package size
            if (packageFile.length() > MAX_PACKAGE_SIZE_MB * 1024 * 1024) {
                return@withContext Result.failure(SecurityException("Package too large: ${packageFile.length()} bytes"))
            }
            
            ZipFile(packageFile).use { zipFile ->
                // Extract and validate manifest
                val manifestEntry = zipFile.getEntry(MANIFEST_FILENAME)
                    ?: return@withContext Result.failure(IllegalArgumentException("No manifest found"))
                
                val manifestJson = zipFile.getInputStream(manifestEntry).readBytes().toString(Charsets.UTF_8)
                val manifest = Json.decodeFromString(ServiceManifest.serializer(), manifestJson)
                
                // Validate manifest
                validateManifest(manifest)
                
                // Verify signatures (skip for development packages)
                if (manifest.signatureInfo.authorSignature != DEVELOPMENT_SIGNATURE) {
                    verifyPackageSignature(packageFile, manifest)
                }
                
                // Validate package contents
                validatePackageContents(zipFile, manifest)
                
                // Install package to secure location
                installValidatedPackage(zipFile, manifest)
                
                Log.i(TAG, "Package installed successfully: ${manifest.packageId}")
                Result.success(manifest)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Package validation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Create a signed service bundle (ZIP) and corresponding Meshrabiya ServiceAnnouncement.
     * Returns the signed bundle bytes and the Meshrabiya announcement so callers can publish it.
     */
    // suspend fun createSignedBundleForPackage(
    //     packagePath: String,
    //     authorKeyPair: Pair<PrivateKey, PublicKey>,
    //     authorOnionAddress: String
    // ): Result<Pair<ByteArray, com.ustadmobile.meshrabiya.model.ServiceAnnouncement>> = withContext(Dispatchers.IO) {
    //     try {
    //         val packageFile = File(packagePath)
    //         if (!packageFile.exists()) return@withContext Result.failure(FileNotFoundException("Package not found: $packagePath"))

    //         // Load manifest from package
    //         val manifest = loadManifestFromPackage(packageFile)

    //         // Build in-memory map of service files (path -> bytes) from the ZIP
    //         val serviceFiles = mutableMapOf<String, ByteArray>()
    //         ZipFile(packageFile).use { zip ->
    //             val entries = zip.entries()
    //             while (entries.hasMoreElements()) {
    //                 val entry = entries.nextElement()
    //                 if (!entry.isDirectory) {
    //                     val data = zip.getInputStream(entry).readBytes()
    //                     serviceFiles[entry.name] = data
    //                 }
    //             }
    //         }

    //         // Convert manifest -> Meshrabiya ServiceAnnouncement using the centralized interop helper
    //         val announcement = manifest.toMeshrabiyaAnnouncement(sizeKB = (packageFile.length() / 1024).toInt())

    //         // Create signed bundle using Meshrabiya signing helper
    //         val signer = DecentralizedServiceSigning()
    //         val signedBundle = signer.createSignedBundle(serviceFiles, authorKeyPair, authorOnionAddress, announcement)

    //         Result.success(Pair(signedBundle, announcement))
    //     } catch (e: Exception) {
    //         Log.e(TAG, "Failed to create signed bundle for package", e)
    //         Result.failure(e)
    //     }
    // }
    
    /**
     * LOCAL TESTING EXECUTION
     * 
     * Execute a locally developed service for testing
     */
    suspend fun testLocalService(
        packageId: String,
        testInput: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val testDir = File(context.filesDir, LOCAL_TEST_DIR)
            val packageFile = File(testDir, "$packageId-dev$PACKAGE_EXTENSION")
            
            if (!packageFile.exists()) {
                return@withContext Result.failure(FileNotFoundException("Local test package not found: $packageId"))
            }
            
            Log.d(TAG, "Testing local service: $packageId")
            
            // Load and execute the service in sandbox
            val manifest = loadManifestFromPackage(packageFile)
            val result = executeServiceInSandbox(packageFile, manifest, testInput, isLocalTest = true)
            
            Log.i(TAG, "Local test completed for: $packageId")
            Result.success(result)
            
        } catch (e: Exception) {
            Log.e(TAG, "Local service test failed", e)
            Result.failure(e)
        }
    }
    
    // TODO: User-friendly local testing workflow
    // Need to create a UI that allows developers to:
    // 1. Import service code from project directory
    // 2. Define test inputs and expected outputs
    // 3. Run tests in isolated sandbox environment
    // 4. View detailed execution logs and performance metrics
    // 5. Validate service against security requirements
    // 6. Generate signed package for distribution
    // 7. Preview how service will appear in discovery system
    
    // TODO: Local development server
    // Create a local HTTP server that mimics the I2P discovery system:
    // - Serves local test packages for testing distribution
    // - Simulates package download and installation flow
    // - Allows testing of service discovery and reputation
    // - Provides debugging tools for package validation
    
    /**
     * PRIVATE HELPER METHODS
     */
    
    private fun validateManifest(manifest: ServiceManifest) {
        require(manifest.manifestVersion == SUPPORTED_PACKAGE_VERSION) {
            "Unsupported manifest version: ${manifest.manifestVersion}"
        }
        require(manifest.packageId.matches(Regex("^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)*$"))) {
            "Invalid package ID format: ${manifest.packageId}"
        }
        require(manifest.resourceRequirements.maxMemoryMB <= 64) {
            "Memory requirement too high: ${manifest.resourceRequirements.maxMemoryMB}MB"
        }
        require(manifest.executionTimeoutSeconds <= 30) {
            "Execution timeout too long: ${manifest.executionTimeoutSeconds}s"
        }
    }
    
    private fun addToZip(zipOut: ZipOutputStream, entryName: String, data: ByteArray) {
        val entry = ZipEntry(entryName)
        entry.size = data.size.toLong()
        zipOut.putNextEntry(entry)
        zipOut.write(data)
        zipOut.closeEntry()
    }
    
    private fun addDirectoryToZip(zipOut: ZipOutputStream, sourcePath: String, zipPath: String) {
        val sourceDir = File(sourcePath)
        if (!sourceDir.exists()) return
        
        sourceDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = file.relativeTo(sourceDir).path
                val zipEntryPath = "$zipPath$relativePath"
                addToZip(zipOut, zipEntryPath, file.readBytes())
            }
        }
    }
    
    private fun addMetadataToZip(zipOut: ZipOutputStream, manifest: ServiceManifest) {
        // Add package creation metadata
        val metadata = JSONObject().apply {
            put("createdBy", "Orbot-Meshrabiya ServicePackageManager")
            put("createdAt", System.currentTimeMillis())
            put("packageFormat", "meshsvc-1.0")
            put("compression", "zip")
        }
        addToZip(zipOut, "${METADATA_DIR}package.json", metadata.toString().toByteArray())
    }
    
    private fun calculateFileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    private fun verifyPackageSignature(packageFile: File, manifest: ServiceManifest) {
        // 1. Calculate package hash
        val calculatedHash = calculateFileHash(packageFile)
        if (calculatedHash != manifest.signatureInfo.packageHash) {
            throw SecurityException("Package hash mismatch: expected ${manifest.signatureInfo.packageHash}, got $calculatedHash")
        }

        // 2. Verify Ed25519 signature using author's public key (assume authorOnionAddress is used to fetch public key)
        // For demo, assume public key is embedded in manifest.signatureInfo.authorSignature (not secure, but placeholder)
        // In production, fetch the public key from a trusted source or manifest
        val signatureBase64 = manifest.signatureInfo.authorSignature
        val signatureBytes = try { Base64.getDecoder().decode(signatureBase64) } catch (e: Exception) { throw SecurityException("Invalid signature encoding") }

        // For demonstration, assume public key is provided in manifest.signatureInfo.trustedBy[0] (not secure)
        val publicKeyBase64 = manifest.signatureInfo.trustedBy.firstOrNull() ?: throw SecurityException("No public key available for verification")
        val publicKeyBytes = try { Base64.getDecoder().decode(publicKeyBase64) } catch (e: Exception) { throw SecurityException("Invalid public key encoding") }

        // Add BouncyCastle provider if not present
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        val keySpec = X509EncodedKeySpec(publicKeyBytes)
        val keyFactory = java.security.KeyFactory.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val publicKey = keyFactory.generatePublic(keySpec)

        val sig = Signature.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        sig.initVerify(publicKey)
        sig.update(calculatedHash.toByteArray())
        val verified = sig.verify(signatureBytes)
        if (!verified) {
            throw SecurityException("Ed25519 signature verification failed")
        }
        // 4. (Optional) Check web of trust endorsements (not implemented)
    }
    
    private fun validatePackageContents(zipFile: ZipFile, manifest: ServiceManifest) {
        // Verify all declared files exist
        manifest.modelFiles.forEach { modelSpec ->
            val entry = zipFile.getEntry("$MODEL_DATA_DIR${modelSpec.filename}")
                ?: throw IllegalArgumentException("Declared model file not found: ${modelSpec.filename}")
            
            if (entry.size != modelSpec.sizeBytes) {
                throw IllegalArgumentException("Model file size mismatch: ${modelSpec.filename}")
            }
        }
        
        manifest.assetFiles.forEach { assetSpec ->
            val entry = zipFile.getEntry("$ASSETS_DIR${assetSpec.filename}")
                ?: throw IllegalArgumentException("Declared asset file not found: ${assetSpec.filename}")
        }
    }
    
    private fun installValidatedPackage(zipFile: ZipFile, manifest: ServiceManifest) {
        // Extract package to secure application directory
        val installDir = File(context.filesDir, "installed_services/${manifest.packageId}")
        installDir.mkdirs()
        
        zipFile.entries().asSequence().forEach { entry ->
            if (!entry.isDirectory) {
                val outputFile = File(installDir, entry.name)
                outputFile.parentFile?.mkdirs()
                
                zipFile.getInputStream(entry).use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        
        Log.d(TAG, "Package extracted to: ${installDir.absolutePath}")
    }
    
    private fun loadManifestFromPackage(packageFile: File): ServiceManifest {
        ZipFile(packageFile).use { zipFile ->
            val manifestEntry = zipFile.getEntry(MANIFEST_FILENAME)
                ?: throw IllegalArgumentException("No manifest found in package")
            
            val manifestJson = zipFile.getInputStream(manifestEntry).readBytes().toString(Charsets.UTF_8)
            return Json.decodeFromString(ServiceManifest.serializer(), manifestJson)
        }
    }
    
    private fun executeServiceInSandbox(
        packageFile: File,
        manifest: ServiceManifest,
        input: String,
        isLocalTest: Boolean
    ): String {
        // Integrate with BulletproofSandbox for actual execution (placeholder implementation)
        Log.d(TAG, "Executing service ${manifest.packageId} in sandbox (test mode: $isLocalTest)")
        // In production, this would launch the service in a secure sandboxed environment
        // For now, simulate execution and return a mock result
        return "[SANDBOXED] Executed service ${manifest.packageId} with input: $input"
    }
}
