
package com.ustadmobile.meshrabiya.service.ml

import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.model.ExecutionProfile
import com.ustadmobile.meshrabiya.model.DeviceCapabilities

/**
 * CORRECTED: Model quantization workflow for service library
 * 
 * Quantization happens OFFLINE during development, not on mobile devices
 */

// ============================================================================
// DEVELOPMENT-TIME QUANTIZATION (Python/Desktop)
// ============================================================================

/**
 * This would be done on a developer machine, NOT on Android devices:
 * 
 * 1. Developer starts with original 32-bit model (e.g., 200MB)
 * 2. Uses TensorFlow Lite Converter to create quantized versions
 * 3. Uploads different versions to service library
 * 
 * Example Python script (runs on developer machine):
 * 
import com.ustadmobile.meshrabiya.model.DeviceCapabilities
import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.model.ExecutionProfile
 * 
 * # Convert original model to different quantization levels
 * converter = tf.lite.TFLiteConverter.from_saved_model("original_model/")
 * 
 * # Full precision (baseline)
 * tflite_model = converter.convert()
 * with open("model_fp32.tflite", "wb") as f:
 *     f.write(tflite_model)
 * 
 * # Float16 quantization (50% size reduction)
 * converter.optimizations = [tf.lite.Optimize.DEFAULT]
 * converter.target_spec.supported_types = [tf.float16]
 * tflite_fp16 = converter.convert()
 * with open("model_fp16.tflite", "wb") as f:
 *     f.write(tflite_fp16)
 * 
 * # Int8 quantization (75% size reduction)
 * converter.target_spec.supported_types = [tf.int8]
 * converter.inference_input_type = tf.int8
 * converter.inference_output_type = tf.int8
 * tflite_int8 = converter.convert()
 * with open("model_int8.tflite", "wb") as f:
 *     f.write(tflite_int8)
 */

// ============================================================================
// SERVICE LIBRARY ENTRIES (Multiple Quantization Levels)
// ============================================================================

/**
 * Service library would contain multiple versions of the same model
 * with different quantization levels for different device capabilities
 */
class PreQuantizedModelService {
    
    companion object {
        /**
         * Service library entries for the same logical service
         * but with different quantization levels
         */
        fun createQuantizedServiceVariants(): List<ServiceAnnouncement> {
            return listOf(
                // High-end devices: Full precision
                ServiceAnnouncement(
                    serviceId = "sentiment-analyzer-fp32",
                    serviceType = ServiceAnnouncement.ServiceType.LITERT,
                    version = "1.0.0",
                    sizeKB = 200 * 1024, // 200MB
                    capabilities = listOf("sentiment-analysis", "high-accuracy"),
                    resourceRequirements = ResourceRequirements(
                        minMemoryMB = 6000,
                        minStorageMB = 250 * 1024 // Extra space for runtime
                    ),
                    executionProfile = ExecutionProfile(
                        profileName = "fp32",
                        cpuCores = 4,
                        gpuEnabled = true,
                        memoryMB = 6000,
                        storageMB = 250 * 1024
                    )
                ),
                // Mid-range devices: Float16 quantization
                ServiceAnnouncement(
                    serviceId = "sentiment-analyzer-fp16",
                    serviceType = ServiceAnnouncement.ServiceType.LITERT,
                    version = "1.0.0",
                    sizeKB = 100 * 1024, // 100MB (50% smaller)
                    capabilities = listOf("sentiment-analysis", "medium-accuracy"),
                    resourceRequirements = ResourceRequirements(
                        minMemoryMB = 3000,
                        minStorageMB = 120 * 1024
                    ),
                    executionProfile = ExecutionProfile(
                        profileName = "fp16",
                        cpuCores = 2,
                        gpuEnabled = true,
                        memoryMB = 3000,
                        storageMB = 120 * 1024
                    )
                ),
                // Low-end devices: Int8 quantization
                ServiceAnnouncement(
                    serviceId = "sentiment-analyzer-int8",
                    serviceType = ServiceAnnouncement.ServiceType.LITERT,
                    version = "1.0.0",
                    sizeKB = 50 * 1024, // 50MB (75% smaller)
                    capabilities = listOf("sentiment-analysis", "mobile-optimized"),
                    resourceRequirements = ResourceRequirements(
                        minMemoryMB = 1500,
                        minStorageMB = 60 * 1024
                    ),
                    executionProfile = ExecutionProfile(
                        profileName = "int8",
                        cpuCores = 1,
                        gpuEnabled = false,
                        memoryMB = 1500,
                        storageMB = 60 * 1024
                    )
                )
            )
        }
    }
}

// ============================================================================
// RUNTIME MODEL SELECTION (What Actually Runs on Android)
// ============================================================================

/**
 * Android devices select the appropriate pre-quantized model
 * based on their capabilities - NO quantization happens on device
 */
class RuntimeModelSelector(
    private val deviceCapabilities: DeviceCapabilities
) {
    
    /**
     * Select best pre-quantized model variant for this device
     */
    fun selectOptimalModelVariant(
        baseServiceId: String,
        availableVariants: List<ServiceAnnouncement>
    ): ServiceAnnouncement? {
        
        // Filter variants that can run on this device
        val compatibleVariants = availableVariants.filter { variant ->
            variant.serviceId.startsWith(baseServiceId) &&
            canRunOnDevice(variant)
        }
        
        // Select best variant based on device class
        return when (deviceCapabilities.deviceClass) {
            DeviceCapabilities.DeviceClass.ML_POWERHOUSE -> {
                // Prefer highest accuracy (fp32 > fp16 > int8)
                compatibleVariants.find { it.serviceId.contains("fp32") }
                    ?: compatibleVariants.find { it.serviceId.contains("fp16") }
                    ?: compatibleVariants.find { it.serviceId.contains("int8") }
            }
            
            DeviceCapabilities.DeviceClass.ML_CAPABLE -> {
                // Balance accuracy and performance (fp16 > int8 > fp32)
                compatibleVariants.find { it.serviceId.contains("fp16") }
                    ?: compatibleVariants.find { it.serviceId.contains("int8") }
                    ?: compatibleVariants.find { it.serviceId.contains("fp32") }
            }
            
            DeviceCapabilities.DeviceClass.ML_BASIC -> {
                // Prefer efficiency (int8 > fp16)
                compatibleVariants.find { it.serviceId.contains("int8") }
                    ?: compatibleVariants.find { it.serviceId.contains("fp16") }
            }
            
            DeviceCapabilities.DeviceClass.CONSUMER -> {
                // Only smallest models
                compatibleVariants.find { it.serviceId.contains("int8") }
            }
        }
    }
    
    private fun canRunOnDevice(variant: ServiceAnnouncement): Boolean {
        return deviceCapabilities.memoryMB >= variant.resourceRequirements.minMemoryMB &&
               deviceCapabilities.storageMB >= variant.resourceRequirements.minStorageMB
    }
}

// ============================================================================
// LIBRARY MANAGEMENT (How Pre-Quantized Models Are Distributed)
// ============================================================================

/**
 * Service library contains pre-built quantized models
 * Devices download the appropriate variant for their capabilities
 */
class QuantizedModelLibrary {
    
    data class ModelFamily(
        val baseServiceId: String,
        val description: String,
        val variants: Map<String, ModelVariant>
    )
    
    data class ModelVariant(
        val quantizationType: String, // "fp32", "fp16", "int8"
        val accuracyScore: Float,     // Benchmark accuracy
        val sizeReduction: Float,     // Size reduction from fp32
        val speedupFactor: Float,     // Inference speedup
        val serviceAnnouncement: ServiceAnnouncement
    )
    
    /**
     * Example: Sentiment Analysis model family
     */
    fun getSentimentAnalysisFamily(): ModelFamily {
        return ModelFamily(
            baseServiceId = "sentiment-analyzer",
            description = "Text sentiment classification",
            variants = mapOf(
                "fp32" to ModelVariant(
                    quantizationType = "fp32",
                    accuracyScore = 0.95f,
                    sizeReduction = 0f,
                    speedupFactor = 1.0f,
                    serviceAnnouncement = PreQuantizedModelService.createQuantizedServiceVariants()[0]
                ),
                "fp16" to ModelVariant(
                    quantizationType = "fp16", 
                    accuracyScore = 0.94f, // Minimal accuracy loss
                    sizeReduction = 0.5f,   // 50% smaller
                    speedupFactor = 1.5f,   // 1.5x faster
                    serviceAnnouncement = PreQuantizedModelService.createQuantizedServiceVariants()[1]
                ),
                "int8" to ModelVariant(
                    quantizationType = "int8",
                    accuracyScore = 0.92f, // Small accuracy loss
                    sizeReduction = 0.75f,  // 75% smaller
                    speedupFactor = 2.5f,   // 2.5x faster
                    serviceAnnouncement = PreQuantizedModelService.createQuantizedServiceVariants()[2]
                )
            )
        )
    }
}
