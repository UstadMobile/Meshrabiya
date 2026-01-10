package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import android.util.Log
import java.io.File

/**
 * ServiceDevelopmentTools - small, self-contained utilities used by the app build.
 * This file was simplified to fix prior syntax corruption and provide stable helpers
 * referenced across the codebase (descriptions, resource estimates, supported platforms).
 */
class ServiceDevelopmentTools(
    private val context: Context,
    private val packageManager: ServicePackageManager
) {
    companion object {
        private const val TAG = "ServiceDevTools"
        const val DEV_WORKSPACE_DIR = "service_development"
        const val TEST_RESULTS_DIR = "test_results"
    }

    // Minimal public helpers used elsewhere in the project
    fun getServiceTypeDescription(serviceType: ServiceType): String = when (serviceType) {
        ServiceType.ML_INFERENCE -> "- Loading and running ML models\n- Preprocessing input data\n- Postprocessing outputs"
        ServiceType.ML_TRAINING -> "- Distributed training\n- Gradient aggregation\n- Checkpointing"
        ServiceType.DATA_ANALYSIS -> "- Statistical analysis\n- Reporting\n- Data validation"
        ServiceType.IMAGE_PROCESSING -> "- Image filtering\n- Format conversion\n- Basic CV ops"
        ServiceType.CRYPTOGRAPHIC -> "- Encryption/Decryption\n- Signing\n- Hashing"
        ServiceType.SCIENTIFIC_COMPUTING -> "- Numerical simulations\n- Scientific algorithms"
        else -> "- General compute tasks\n- Data processing\n- Algorithm execution"
    }

    fun getResourceRequirement(serviceType: ServiceType, resource: String): String = when (resource) {
        "memory" -> when (serviceType) {
            ServiceType.ML_INFERENCE, ServiceType.ML_TRAINING -> "128"
            ServiceType.IMAGE_PROCESSING, ServiceType.VIDEO_PROCESSING -> "64"
            ServiceType.SCIENTIFIC_COMPUTING -> "64"
            else -> "32"
        }
        "cpu" -> when (serviceType) {
            ServiceType.ML_TRAINING, ServiceType.SCIENTIFIC_COMPUTING -> "90"
            ServiceType.ML_INFERENCE, ServiceType.IMAGE_PROCESSING -> "80"
            else -> "50"
        }
        "storage" -> when (serviceType) {
            ServiceType.ML_INFERENCE, ServiceType.ML_TRAINING -> "50"
            ServiceType.IMAGE_PROCESSING -> "20"
            else -> "10"
        }
        else -> "Unknown"
    }

    fun isLanguageCrossPlatform(language: ProgrammingLanguage): Boolean = when (language) {
        ProgrammingLanguage.KOTLIN, ProgrammingLanguage.JAVA, ProgrammingLanguage.PYTHON,
        ProgrammingLanguage.JAVASCRIPT, ProgrammingLanguage.TYPESCRIPT, ProgrammingLanguage.WEBASSEMBLY -> true
        ProgrammingLanguage.NATIVE_CPP, ProgrammingLanguage.NATIVE_C -> false
        else -> true
    }

    fun isLanguageSandboxCompatible(language: ProgrammingLanguage): Boolean = when (language) {
        ProgrammingLanguage.NATIVE_CPP, ProgrammingLanguage.NATIVE_C -> false
        else -> true
    }

    fun getSupportedPlatforms(language: ProgrammingLanguage): List<String> = when (language) {
        ProgrammingLanguage.KOTLIN, ProgrammingLanguage.JAVA -> listOf("Android", "Linux", "Any JVM")
        ProgrammingLanguage.PYTHON -> listOf("Android (via Chaquopy)", "Linux", "Any Python runtime")
        ProgrammingLanguage.JAVASCRIPT -> listOf("Android (via Node.js)", "Linux", "Any Node.js runtime")
        ProgrammingLanguage.WEBASSEMBLY -> listOf("Android", "Linux", "Any WASM runtime")
        ProgrammingLanguage.NATIVE_CPP -> listOf("Android (NDK)", "Linux (x86_64)")
        else -> listOf("Android", "Linux")
    }

    // Minimal README generator used by developer flows. Keep simple and safe.
    fun generateProjectReadme(projectDir: File, packageId: String, serviceName: String, serviceType: ServiceType, language: ProgrammingLanguage) {
        val readme = buildString {
            appendLine("# $serviceName")
            appendLine()
            appendLine("Package ID: $packageId")
            appendLine("Service Type: ${serviceType.name}")
            appendLine("Language: ${language.name}")
            appendLine()
            appendLine("Description:")
            appendLine(getServiceTypeDescription(serviceType))
            appendLine()
            appendLine("Resource requirements: memory=${getResourceRequirement(serviceType, "memory")}MB, cpu=${getResourceRequirement(serviceType, "cpu")}%")
        }
        projectDir.mkdirs()
        File(projectDir, "README.md").writeText(readme)
        Log.i(TAG, "Generated README for $packageId at ${projectDir.absolutePath}")
    }

    // Lightweight templates and scaffolding helpers (kept intentionally small and stable)
    fun createProjectStructure(projectDir: File, language: ProgrammingLanguage) {
        when (language) {
            ProgrammingLanguage.KOTLIN, ProgrammingLanguage.JAVA -> {
                File(projectDir, "service/src/main").mkdirs()
                File(projectDir, "service/src/test").mkdirs()
            }
            ProgrammingLanguage.PYTHON -> File(projectDir, "service").mkdirs()
            else -> File(projectDir, "service").mkdirs()
        }
    }

    fun generateBuildConfiguration(projectDir: File, language: ProgrammingLanguage) {
        // Intentionally minimal: create a placeholder build file for local development
        val build = when (language) {
            ProgrammingLanguage.KOTLIN, ProgrammingLanguage.JAVA -> "plugins { id(\"org.jetbrains.kotlin.jvm\") }"
            else -> ""
        }
        File(projectDir, "service/build.gradle.kts").writeText(build)
    }

    // Light-weight enum types used in this file only (copied references to keep API stable for other callers)
    enum class ServiceType {
        ML_INFERENCE, ML_TRAINING, ML_PREPROCESSING,
        DATA_ANALYSIS, DATA_TRANSFORMATION, DATA_VALIDATION, DATA_AGGREGATION,
        MATHEMATICAL, SCIENTIFIC_COMPUTING, OPTIMIZATION, NUMERICAL_ANALYSIS,
        CRYPTOGRAPHIC, ENCRYPTION, DIGITAL_SIGNATURE, HASH_COMPUTATION, KEY_DERIVATION,
        IMAGE_PROCESSING, VIDEO_PROCESSING, AUDIO_PROCESSING, TEXT_PROCESSING,
        API_PROXY, WEB_SCRAPING, CONTENT_ANALYSIS, BLOCKCHAIN_ANALYSIS, FINANCIAL_MODELING,
        RISK_ANALYSIS, FORMAT_CONVERSION, COMPRESSION, VALIDATION, MONITORING, CUSTOM
    }

    enum class ProgrammingLanguage { KOTLIN, JAVA, PYTHON, JAVASCRIPT, TYPESCRIPT, NATIVE_CPP, NATIVE_C, RUST, GO, WEBASSEMBLY }
}
    
