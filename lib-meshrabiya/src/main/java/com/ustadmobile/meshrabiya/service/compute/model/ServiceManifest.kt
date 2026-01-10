package com.ustadmobile.meshrabiya.service.compute.model

import com.ustadmobile.meshrabiya.service.compute.ServicePackageManager.RuntimeSpec
import com.ustadmobile.meshrabiya.service.compute.ServicePackageManager.ResourceSpec
import com.ustadmobile.meshrabiya.service.compute.ServicePackageManager.SignatureInfo
import com.ustadmobile.meshrabiya.service.compute.ServicePackageManager.ValidationSpec
import com.ustadmobile.meshrabiya.service.compute.ServicePackageManager.ModelFileSpec
import com.ustadmobile.meshrabiya.service.compute.ServicePackageManager.AssetFileSpec
import com.ustadmobile.meshrabiya.service.compute.ServicePackageManager.DependencySpec
import com.ustadmobile.meshrabiya.service.compute.ServicePackageManager.TestCaseSpec
import com.ustadmobile.meshrabiya.service.compute.CPUIntensity
import com.ustadmobile.meshrabiya.vnet.hardware.ThermalState  // Use canonical ThermalState
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import com.ustadmobile.meshrabiya.model.ExecutionProfile

/**
 * Represents the manifest for a distributed compute service.
 * Describes service type, version, author, signature, runtime requirements, device profile,
 * resource requirements, platform support, files, and whether the service is builtin.
 */
// data class ServiceManifest(
//     val serviceType: ServiceType,
//     val version: String,
//     val author: String,
//     val signature: String?,
//     val runtimeRequired: List<String> = emptyList(),
//     val runtimeOptional: List<String> = emptyList(),
//     val deviceProfile: String? = null,
//     val resourceRequirements: ResourceRequirements,
//     val platformSupport: List<String> = emptyList(),
//     val files: List<String> = emptyList(),
//     val builtin: Boolean = false
// )

// @Serializable
// data class ServiceManifest(
//     val serviceId: String,
//     val name: String,
//     val version: String,
//     val author: AuthorInfo,
//     val signature: SignatureInfo,
//     val serviceType: String,
//     val capabilities: List<String>,
//     @Contextual val resourceRequirements: ResourceRequirements,
//     val files: List<FileHash>, // Hash of each file for integrity
//     val builtin: Boolean = false, // True for built-in functions, disables signature/author/files
//     val inputs: List<InputDef> = emptyList(),
//     val outputs: List<OutputDef> = emptyList()
// )


// @Serializable
// data class ServiceManifest(
//     val packageId: String,
//     val serviceType: String,
//     val runtimeRequired: List<Runtime>,
//     val runtimeOptional: List<Runtime>,
//     val deviceProfile: DeviceProfile,
//     val resourceRequirements: ResourceRequirements
// )

/**
* SERVICE PACKAGE MANIFEST SCHEMA
* 
* Standardized manifest format for all distributed compute services
*/
@Serializable
data class ServiceManifest(
    // REQUIRED FIELDS
    val packageId: String,                    // Unique service identifier (reverse domain)
    val packageVersion: String,               // Semantic versioning (1.0.0)
    val manifestVersion: String,              // Manifest schema version
    val serviceName: String,                  // Human-readable service name
    val serviceDescription: String,           // What the service does
    val authorOnionAddress: String,           // .onion address of service author
    val createdTimestamp: Long,               // Unix timestamp of creation
    
// SERVICE EXECUTION
    val entryPoint: String,                   // Main service class/function
    val runtime: RuntimeSpec,                 // Programming language and runtime
    val runtimeRequired: List<String>,        // Always-available runtimes ("jvm", "native")
    val runtimeOptional: List<String>,        // Modular runtimes ("python", "nodejs", "go", "rust", "wasm")
    val deviceProfile: String,                // "flagship", "mid-range", "budget"
    val serviceType: String,                  // "workflow", "ml", "data_processing", etc.
val supportedPlatforms: List<String>,     // ["android", "linux", "any"]
val requiredPermissions: List<String>,    // Android permissions needed
val resourceRequirements: ResourceSpec,   // CPU, memory, storage needs
val executionTimeoutSeconds: Int,         // Maximum execution time
    
    // SECURITY & VALIDATION
    val signatureInfo: SignatureInfo,         // Cryptographic signatures
    val sandboxProfile: String,               // Sandbox restriction level
    val allowedSyscalls: List<String>,        // Permitted system calls
    val inputValidation: ValidationSpec,      // Input format requirements
    val outputFormat: String,                 // Expected output format
    
    // MODEL & ASSETS
    val modelFiles: List<ModelFileSpec>,      // ML models included
    val assetFiles: List<AssetFileSpec>,      // Additional assets
    val dependencies: List<DependencySpec>,   // External dependencies
    
    // DISTRIBUTION & DISCOVERY
    val tags: List<String>,                   // Searchable tags
    val category: String,                     // Service category
    val licenseType: String,                  // License (MIT, GPL, etc)
    val sourceCodeUrl: String?,               // Optional source repository
    val documentationUrl: String?,            // Optional documentation
    
    // OPTIONAL METADATA
    val changeLog: String?,                   // Version changes
    val testCases: List<TestCaseSpec>?,       // Built-in test cases
    val compatibilityNotes: String?          // Platform-specific notes
)


/**
 * Enum representing the type of service.
 */
enum class ServiceType {
    PYTHON,
    // LITERT,
    HYBRID,
    JAVA,
    NDK,
    WORKFLOW,
    STORAGE
}

/**
 * Execution profile for a service.
 * Indicates if execution is deterministic, requires zero-knowledge proof, and access level.
 */
// data class ExecutionProfile(
//     val deterministic: Boolean = false,
//     val zkpRequired: Boolean = false,
//     val accessLevel: String = "user"
// )

/**
 * Metadata for a service, including manifest, execution profile, inputs, outputs, and capabilities.
 */
data class ServiceMeta(
    val serviceId: String,
    val manifest: ServiceManifest,
    val executionProfile: ExecutionProfile,
    val inputs: List<ServiceInput> = emptyList(),
    val outputs: List<ServiceOutput> = emptyList(),
    val capabilities: Set<ServiceCapability> = emptySet()
)

/**
 * Represents an input to a service.
 */
data class ServiceInput(
    val name: String,
    val type: String,
    val required: Boolean = true
)

/**
 * Represents an output from a service.
 */
data class ServiceOutput(
    val name: String,
    val type: String
)

/**
 * Enum representing the capabilities of a service.
 */
enum class ServiceCapability {
    ML,
    CV,
    NLP,
    STORAGE,
    AUDIO,
    SIGNAL,
    CRYPTO,
    WORKFLOW,
    JAVA,
    NDK
}

// Note: ResourceRequirements, CPUIntensity, and ThermalState are now imported from SupportTypes.kt
// to avoid redeclaration errors. Import them as needed:
// import com.ustadmobile.meshrabiya.service.compute.ResourceRequirements
// import com.ustadmobile.meshrabiya.service.compute.CPUIntensity
// import com.ustadmobile.meshrabiya.service.compute.ThermalState
