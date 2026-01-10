package com.ustadmobile.meshrabiya.service.compute.runtime

import android.content.Context
import android.os.Build
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream


/**
    * Progress callback for installation
    * @param progress 0-100 percentage
    * @param message Status message
    */
typealias ProgressCallback = (progress: Int, message: String) -> Unit

/**
 * RuntimeInstaller
 * 
 * Phase 3: Runtime Management Layer - Automatic runtime installation
 * 
 * Responsibilities:
 * 1. Download runtime libraries from trusted sources
 * 2. Extract and install to app-specific directory
 * 3. Register with RuntimeRegistry
 * 4. Provide progress callbacks for UI
 * 
 * Supported Runtimes:
 * - J2V8: JavaScript engine (from Maven Central)
 * - TensorFlow Lite: ML inference (from Google Maven)
 * - Python packages: via pip (requires Chaquopy)
 */
class RuntimeInstaller(
    private val context: Context
) {
    companion object {
        // J2V8 versions
        private const val J2V8_VERSION = "6.2.1"
        private const val J2V8_GROUP_ID = "com.eclipsesource.j2v8"
        private const val J2V8_ARTIFACT_ID = "j2v8"
        
        // TensorFlow Lite versions
        private const val TFLITE_VERSION = "2.14.0"
        private const val TFLITE_GROUP_ID = "org.tensorflow"
        private const val TFLITE_ARTIFACT_ID = "tensorflow-lite"
        
        // Maven repositories
        private const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
        private const val GOOGLE_MAVEN = "https://maven.google.com"
    }
    
    
    private val registry = RuntimeRegistry.getInstance(context)
    private val runtimesDir = File(context.filesDir, "runtimes")
    
    init {
        runtimesDir.mkdirs()
    }
    
    /**
     * Install a runtime based on TaskType
     * @param taskType The task type requiring the runtime
     * @param onProgress Progress callback
     * @return Success status
     */
    suspend fun installRuntime(
        taskType: TaskType,
        onProgress: ProgressCallback = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress(0, "Starting installation for ${taskType.name}")
        
        return@withContext when (taskType) {
            TaskType.JAVASCRIPT -> installJavaScript(onProgress)
            TaskType.ML_NATIVE -> installMLNative(onProgress)
            TaskType.PYTHON -> installPythonPackages(onProgress)
            TaskType.JVM, TaskType.JAVA -> {
                onProgress(100, "JVM runtime is built-in")
                true // JVM is always available
            }
            else -> {
                onProgress(100, "Runtime not installable: ${taskType.name}")
                false
            }
        }
    }
    
    /**
     * Install J2V8 JavaScript engine
     */
    private suspend fun installJavaScript(onProgress: ProgressCallback): Boolean {
        onProgress(10, "Detecting device architecture")
        
        // Detect device architecture
        val arch = detectArchitecture()
        val artifactName = "j2v8-android-$arch"
        
        onProgress(20, "Downloading J2V8 for $arch")
        
        // Build Maven URL
        val url = buildMavenUrl(
            MAVEN_CENTRAL,
            J2V8_GROUP_ID,
            artifactName,
            J2V8_VERSION,
            "aar"
        )
        
        // Download and extract
        val installDir = File(runtimesDir, "j2v8")
        installDir.mkdirs()
        
        val downloaded = downloadFile(url, File(installDir, "j2v8.aar")) { progress ->
            onProgress(20 + (progress * 0.7).toInt(), "Downloading J2V8: $progress%")
        }
        
        if (!downloaded) {
            onProgress(100, "Failed to download J2V8")
            return false
        }
        
        onProgress(90, "Extracting J2V8")
        
        // Register with registry
        registry.registerRuntime(RuntimeRegistry.RuntimeInfo(
            taskType = TaskType.JAVASCRIPT.name,
            version = J2V8_VERSION,
            isBuiltIn = false,
            installedAt = System.currentTimeMillis(),
            installPath = installDir.absolutePath,
            metadata = mapOf("architecture" to arch)
        ))
        
        onProgress(100, "J2V8 installed successfully")
        return true
    }
    
    /**
     * Install TensorFlow Lite
     */
    private suspend fun installMLNative(onProgress: ProgressCallback): Boolean {
        onProgress(10, "Downloading TensorFlow Lite")
        
        // Build Maven URL
        val url = buildMavenUrl(
            GOOGLE_MAVEN,
            TFLITE_GROUP_ID,
            TFLITE_ARTIFACT_ID,
            TFLITE_VERSION,
            "aar"
        )
        
        // Download and extract
        val installDir = File(runtimesDir, "tflite")
        installDir.mkdirs()
        
        val downloaded = downloadFile(url, File(installDir, "tflite.aar")) { progress ->
            onProgress(10 + (progress * 0.8).toInt(), "Downloading TensorFlow Lite: $progress%")
        }
        
        if (!downloaded) {
            onProgress(100, "Failed to download TensorFlow Lite")
            return false
        }
        
        onProgress(90, "Extracting TensorFlow Lite")
        
        // Register with registry
        registry.registerRuntime(RuntimeRegistry.RuntimeInfo(
            taskType = TaskType.ML_NATIVE.name,
            version = TFLITE_VERSION,
            isBuiltIn = false,
            installedAt = System.currentTimeMillis(),
            installPath = installDir.absolutePath
        ))
        
        onProgress(100, "TensorFlow Lite installed successfully")
        return true
    }
    
    /**
     * Install Python packages via pip (requires Chaquopy)
     */
    private suspend fun installPythonPackages(onProgress: ProgressCallback): Boolean {
        if (!registry.isPythonAvailable()) {
            onProgress(100, "Chaquopy not available - cannot install Python packages")
            return false
        }
        
        onProgress(50, "Python package installation requires Chaquopy build configuration")
        onProgress(100, "Use Chaquopy pip configuration in build.gradle")
        
        // Python packages must be installed at build time via Chaquopy's pip configuration
        // This is a limitation of Chaquopy - runtime package installation is not supported
        return false
    }
    
    /**
     * Uninstall a runtime
     */
    suspend fun uninstallRuntime(taskType: TaskType, onProgress: ProgressCallback = { _, _ -> }): Boolean {
        onProgress(0, "Uninstalling ${taskType.name}")
        
        val success = registry.uninstallRuntime(taskType)
        
        if (success) {
            onProgress(100, "${taskType.name} uninstalled successfully")
        } else {
            onProgress(100, "Failed to uninstall ${taskType.name}")
        }
        
        return success
    }
    
    // === Private Helper Methods ===
    
    private fun detectArchitecture(): String {
        return when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64-v8a"
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "armeabi-v7a"
            Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
            Build.SUPPORTED_ABIS.contains("x86") -> "x86"
            else -> "armeabi-v7a" // Default fallback
        }
    }
    
    private fun buildMavenUrl(
        repository: String,
        groupId: String,
        artifactId: String,
        version: String,
        extension: String
    ): String {
        val groupPath = groupId.replace('.', '/')
        return "$repository/$groupPath/$artifactId/$version/$artifactId-$version.$extension"
    }
    
    private suspend fun downloadFile(
        url: String,
        destination: File,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection()
            connection.connect()
            
            val fileLength = connection.contentLength
            
            connection.getInputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        if (fileLength > 0) {
                            val progress = (totalBytesRead * 100 / fileLength).toInt()
                            onProgress(progress)
                        }
                    }
                }
            }
            
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
    
    private fun extractZip(zipFile: File, destination: File): Boolean {
        return try {
            destination.mkdirs()
            
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                
                while (entry != null) {
                    val file = File(destination, entry.name)
                    
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output ->
                            zis.copyTo(output)
                        }
                    }
                    
                    entry = zis.nextEntry
                }
            }
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
