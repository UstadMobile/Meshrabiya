package com.ustadmobile.meshrabiya.vnet.hardware

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel

/**
 * Detects ML and AI acceleration capabilities on the device.
 * 
 * This class queries various Android APIs to determine:
 * - NNAPI accelerator devices (GPU, DSP, NPU)
 * - ML Kit feature availability
 * - GPU capabilities (OpenGL ES, Vulkan)
 * - Hardware acceleration support
 * 
 * Implementation based on official Android documentation:
 * - NNAPI Device Discovery: https://developer.android.com/ndk/guides/neuralnetworks
 * - ML Kit Model Installation: https://developers.google.com/ml-kit/tips/installation-paths
 * - GPU Delegates: https://www.tensorflow.org/lite/android/delegates/gpu
 */
class MLCapabilityDetector(
    private val context: Context,
    private val betaLogger: BetaTestLogger? = null
) {
    
    /**
     * Detects all ML capabilities available on the device.
     * 
     * Returns:
     * - First: List of capability strings (e.g., "nnapi_gpu", "mlkit_text_recognition")
     * - Second: Boolean indicating if custom model support is available
     */
    fun detectCapabilities(): Pair<List<String>, Boolean> {
        val capabilities = mutableListOf<String>()
        var customModelSupport = false
        
        try {
            // Detect NNAPI accelerators
            capabilities.addAll(detectNNAPIDevices())
            
            // Detect GPU capabilities
            capabilities.addAll(detectGPUCapabilities())
            
            // Detect ML Kit features
            capabilities.addAll(detectMLKitFeatures())
            
            // Check for custom model support (TensorFlow Lite)
            customModelSupport = detectCustomModelSupport()
            
            betaLogger?.log(
                LogLevel.INFO,
                "MLCapabilityDetector",
                "Detected ${capabilities.size} ML capabilities, custom model support: $customModelSupport"
            )
            
        } catch (e: Exception) {
            betaLogger?.log(
                LogLevel.ERROR,
                "MLCapabilityDetector",
                "Error detecting ML capabilities: ${e.message}"
            )
        }
        
        return Pair(capabilities.distinct(), customModelSupport)
    }
    
    /**
     * Detects NNAPI (Neural Networks API) accelerator devices.
     * Available on Android 8.1 (API 27) and higher.
     * 
     * Note: NNAPI was deprecated in Android 15, but still available on most devices.
     * This method uses feature detection rather than direct NNAPI calls to avoid
     * NDK dependencies.
     */
    private fun detectNNAPIDevices(): List<String> {
        val devices = mutableListOf<String>()
        
        // NNAPI is available from Android 8.1 (API 27)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            devices.add("nnapi_available")
            
            // Indicate feature level for better capability assessment
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> devices.add("nnapi_level_31") // Android 12+
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> devices.add("nnapi_level_30") // Android 11
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> devices.add("nnapi_level_29") // Android 10
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> devices.add("nnapi_level_28") // Android 9
                else -> devices.add("nnapi_level_27") // Android 8.1
            }
            
            betaLogger?.log(
                LogLevel.DEBUG,
                "MLCapabilityDetector",
                "NNAPI available (API level ${Build.VERSION.SDK_INT})"
            )
        }
        
        return devices
    }
    
    /**
     * Detects GPU capabilities for ML acceleration.
     * Checks for OpenGL ES and Vulkan support.
     */
    private fun detectGPUCapabilities(): List<String> {
        val capabilities = mutableListOf<String>()
        
        try {
            val pm = context.packageManager
            
            // Check OpenGL ES version
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            activityManager?.deviceConfigurationInfo?.let { configInfo ->
                val glVersion = configInfo.reqGlEsVersion
                
                when {
                    glVersion >= 0x30002 -> capabilities.add("gles_3.2")
                    glVersion >= 0x30001 -> capabilities.add("gles_3.1")
                    glVersion >= 0x30000 -> capabilities.add("gles_3.0")
                    glVersion >= 0x20000 -> capabilities.add("gles_2.0")
                }
                
                if (glVersion >= 0x30000) {
                    capabilities.add("gpu_acceleration")
                }
                
                betaLogger?.log(
                    LogLevel.DEBUG,
                    "MLCapabilityDetector",
                    "OpenGL ES version: ${Integer.toHexString(glVersion)}"
                )
            }
            
            // Check Vulkan support (Android 7.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)) {
                    capabilities.add("vulkan_available")
                    
                    // Check for Vulkan 1.1 support (Android 9+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        if (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000)) {
                            capabilities.add("vulkan_1.1")
                        } else if (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x400000)) {
                            capabilities.add("vulkan_1.0")
                        }
                    }
                    
                    betaLogger?.log(
                        LogLevel.DEBUG,
                        "MLCapabilityDetector",
                        "Vulkan hardware support detected"
                    )
                }
            }
            
        } catch (e: Exception) {
            betaLogger?.log(
                LogLevel.WARN,
                "MLCapabilityDetector",
                "Error detecting GPU capabilities: ${e.message}"
            )
        }
        
        return capabilities
    }
    
    /**
     * Detects ML Kit features available on the device.
     * 
     * This checks for Play Services presence and common ML Kit features.
     * Actual model availability requires ModuleInstallClient API checks at runtime.
     */
    private fun detectMLKitFeatures(): List<String> {
        val features = mutableListOf<String>()
        
        try {
            val pm = context.packageManager
            
            // Check if Google Play Services is available (required for unbundled ML Kit models)
            val playServicesAvailable = try {
                pm.getPackageInfo("com.google.android.gms", 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            
            if (playServicesAvailable) {
                features.add("mlkit_play_services")
                
                // Common ML Kit features that can be available via Play Services
                // Note: Actual availability requires runtime checks via ModuleInstallClient
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { // API 21+
                    // Vision APIs
                    features.add("mlkit_barcode_scanning")
                    features.add("mlkit_face_detection")
                    features.add("mlkit_text_recognition")
                    features.add("mlkit_image_labeling")
                    
                    // Language APIs
                    features.add("mlkit_language_id")
                    features.add("mlkit_translation")
                    features.add("mlkit_smart_reply")
                }
                
                betaLogger?.log(
                    LogLevel.DEBUG,
                    "MLCapabilityDetector",
                    "Google Play Services available - ML Kit features can be used"
                )
            } else {
                betaLogger?.log(
                    LogLevel.DEBUG,
                    "MLCapabilityDetector",
                    "Google Play Services not available - using bundled models only"
                )
            }
            
        } catch (e: Exception) {
            betaLogger?.log(
                LogLevel.WARN,
                "MLCapabilityDetector",
                "Error detecting ML Kit features: ${e.message}"
            )
        }
        
        return features
    }
    
    /**
     * Checks if custom TensorFlow Lite model support is available.
     * This indicates the device can run custom ML models, not just ML Kit APIs.
     */
    private fun detectCustomModelSupport(): Boolean {
        return try {
            // Check if device has sufficient resources for custom models
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memoryInfo)
            
            // Require at least 1GB available RAM for custom model support
            val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
            val hasMemory = availableMemoryMB >= 1024
            
            // Custom models work best on Android 7.0+ with sufficient memory
            val hasOSSupport = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            
            val supported = hasMemory && hasOSSupport
            
            betaLogger?.log(
                LogLevel.DEBUG,
                "MLCapabilityDetector",
                "Custom model support: $supported (memory: ${availableMemoryMB}MB, API: ${Build.VERSION.SDK_INT})"
            )
            
            supported
            
        } catch (e: Exception) {
            betaLogger?.log(
                LogLevel.WARN,
                "MLCapabilityDetector",
                "Error checking custom model support: ${e.message}"
            )
            false
        }
    }
}
