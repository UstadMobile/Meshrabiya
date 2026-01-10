package com.ustadmobile.meshrabiya.service.compute.model

import com.ustadmobile.meshrabiya.service.compute.PythonLibrary
import com.ustadmobile.meshrabiya.service.compute.InferenceConfig
import com.ustadmobile.meshrabiya.service.compute.Precision

/**
 * Represents a search result for a service in the mesh ecosystem.
 */
data class ServiceSearchResult(
    val serviceId: String,
    val manifest: ServiceManifest,
    val executionProfile: ExecutionProfile,
    val capabilities: Set<ServiceCapability>,
    val nodeId: String
)


/**
 * Converts a ServiceLibraryEntry and nodeId to a ServiceSearchResult.
 */
fun toSearchResult(entry: ServiceLibraryEntry, nodeId: String): ServiceSearchResult {
    return ServiceSearchResult(
        serviceId = entry.serviceId,
        manifest = ServiceManifest(
            serviceType = ServiceType.PYTHON, // TODO: Map actual type
            version = entry.version,
            author = entry.maintainer.displayName,
            signature = entry.signature,
            resourceRequirements = entry.resourceRequirements,
            builtin = false // TODO: Map actual value
        ),
        executionProfile = ExecutionProfile(deterministic = true), // TODO: Map actual profile
        capabilities = entry.categories.map { ServiceCapability.valueOf(it) }.toSet(), // TODO: Map actual capabilities
        nodeId = nodeId
    )
}

// DEPRECATED: Legacy LibraryEntry model. All usages should migrate to ServiceLibraryEntry.
// sealed class LibraryEntry { ... }
    /**
     * Python service entry.
     */
    data class PythonServiceEntry(
        override val serviceId: String,
        val scriptCode: String,
        val libraries: Set<PythonLibrary>,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    /**
     * LiteRT service entry.
     */
    // data class LiteRTServiceEntry(
    //     override val serviceId: String,
    //     val modelId: String,
    //     val modelConfig: LiteRTConfig,
    //     override val manifest: ServiceManifest,
    //     override val executionProfile: ExecutionProfile,
    //     override val inputs: List<ServiceInput>,
    //     override val outputs: List<ServiceOutput>,
    //     override val capabilities: Set<ServiceCapability>
    // ) : LibraryEntry()

    /**
     * Hybrid service entry.
     */
    data class HybridServiceEntry(
        override val serviceId: String,
        val pythonPreprocessing: PythonServiceEntry?,
        // val liteRTInference: LiteRTServiceEntry,
        val pythonPostprocessing: PythonServiceEntry?,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    /**
     * Java service entry.
     */
    data class JavaServiceEntry(
        override val serviceId: String,
        val className: String,
        val jarPath: String,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    /**
     * NDK service entry.
     */
    data class NDKServiceEntry(
        override val serviceId: String,
        val soPath: String,
        val entryFunction: String,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    /**
     * Workflow service entry.
     */
    data class WorkflowServiceEntry(
        override val serviceId: String,
        val steps: List<String>,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()
}

// Note: PythonLibrary, InferenceConfig, and Precision are now imported from SupportTypes.kt
// to avoid redeclaration errors
