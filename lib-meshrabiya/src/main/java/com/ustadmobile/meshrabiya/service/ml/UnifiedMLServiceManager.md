package com.ustadmobile.meshrabiya.service.ml
import android.util.Log
import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
// import com.google.ai.edge.litert.CompiledModel
// import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import com.ustadmobile.meshrabiya.model.DeviceCapabilities
import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.model.ExecutionProfile

/**
 * Unified ML service manager supporting all three ML tiers:
 * 1. ML Kit Native (Google-optimized, 0 additional storage)
 * 2. ML Kit Custom (Firebase-managed models)
 * 3. LiteRT Direct (Full TensorFlow Lite flexibility)
 */
class UnifiedMLServiceManager(
    private val context: Context,
    private val deviceCapabilities: DeviceCapabilities
) {
    
    companion object {
        private const val TAG = "UnifiedMLServiceManager"
    }
    
    // Tier 1: ML Kit Native Services (always available)
    private val mlKitNativeServices = ConcurrentHashMap<String, MLServiceWrapper>()
    
    // Tier 2: ML Kit Custom Models (Firebase or bundled)
    private val mlKitCustomServices = ConcurrentHashMap<String, MLKitCustomWrapper>()
    
    // Tier 3: Direct LiteRT Services (TensorFlow Lite models)
    // private val literTServices = ConcurrentHashMap<String, LiteRTWrapper>()
    
    private val isInitialized = CompletableDeferred<Boolean>()
    
    /**
     * Initialize ML services based on device capabilities
     */
    suspend fun initialize(localServiceLibrary: LocalDeviceServiceLibrary? = null) {
        withContext(Dispatchers.IO) {
            try {
                initializeMLKitNativeServices()
                initializeMLKitCustomServices()
                // initializeLiteRTServices()

                // Register all available ML Kit services in Service Library if provided
                localServiceLibrary?.let { lib ->
                    getAvailableServices().forEach { announcement ->
                        val manifest = LocalDeviceServiceLibrary.ServiceManifest(
                            packageId = announcement.serviceId,
                            serviceType = announcement.serviceType.name,
                            runtimeRequired = listOf(LocalDeviceServiceLibrary.Runtime.JVM),
                            runtimeOptional = emptyList(),
                            deviceProfile = LocalDeviceServiceLibrary.DeviceProfile.FLAGSHIP,
                            resourceRequirements = announcement.resourceRequirements
                        )
                        lib.addService(manifest)
                    }
                }

                isInitialized.complete(true)
                Log.i(TAG, "ML services initialized and registered in Service Library")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ML services", e)
                isInitialized.complete(false)
            }
        }
    }
    
    /**
     * Process ML request using optimal tier
     */
    suspend fun processMLRequest(
        serviceType: String,
        serviceId: String,
        input: MLServiceInput
    ): MLServiceResult {
        // Ensure initialization is complete
        isInitialized.await()
        
        return when (serviceType) {
            "ml-kit-native" -> processMLKitNative(serviceId, input)
            "ml-kit-custom" -> processMLKitCustom(serviceId, input)
            // "litert" -> processLiteRT(serviceId, input) // DEPRECATED: LiteRT support deferred
            else -> MLServiceResult.error("Unknown service type: $serviceType")
        }
    }
    
    /**
     * Get available ML services on this device
     */
    fun getAvailableServices(): List<ServiceAnnouncement> {
        val services = mutableListOf<ServiceAnnouncement>()
        
        // Add ML Kit native services
        mlKitNativeServices.forEach { (serviceId, wrapper) ->
            services.add(wrapper.getServiceAnnouncement())
        }
        
        // Add ML Kit custom services
        mlKitCustomServices.forEach { (serviceId, wrapper) ->
            services.add(wrapper.getServiceAnnouncement())
        }
        
        // Add LiteRT services
        // literTServices.forEach { (serviceId, wrapper) ->
        //     services.add(wrapper.getServiceAnnouncement())
        // }
        
        return services
    }
    
    private fun initializeMLKitNativeServices() {
        // Tier 1: Always available ML Kit services
        
    // Text Recognition (always available)
    mlKitNativeServices["text-recognition"] = TextRecognitionWrapper()
        
        // Face Detection (if sufficient memory)
        if (deviceCapabilities.memoryMB > 2000) {
            mlKitNativeServices["face-detection"] = FaceDetectionWrapper()
        }
        
        // Object Detection and Translation (high-end devices)
        if (deviceCapabilities.memoryMB > 4000) {
            mlKitNativeServices["object-detection"] = ObjectDetectionWrapper()
            mlKitNativeServices["translation"] = TranslationWrapper()
        }
        
        Log.i(TAG, "Initialized ${mlKitNativeServices.size} ML Kit native services")
    }
    
    private fun initializeMLKitCustomServices() {
        // Tier 2: Custom models via ML Kit API
        if (!deviceCapabilities.mlKitCustomSupport) return
        
        if (deviceCapabilities.memoryMB > 3000) {
            // Custom image labeling
            mlKitCustomServices["custom-image-classifier"] = 
                MLKitCustomWrapper("custom-image-classifier", "models/custom_classifier.tflite")
        }

        if (deviceCapabilities.memoryMB > 4000) {
            // Custom object detection
            mlKitCustomServices["custom-object-detector"] =
                MLKitCustomWrapper("custom-object-detector", "models/custom_detector.tflite")
        }
        
        Log.i(TAG, "Initialized ${mlKitCustomServices.size} ML Kit custom services")
    }
    
    // private fun initializeLiteRTServices() {
    //     // Tier 3: Direct TensorFlow Lite models
    //     if (!deviceCapabilities.hasLiteRT) return
        
    //     // Lightweight models for all LiteRT-capable devices
    //     if (deviceCapabilities.memoryMB > 1000) {
    //         literTServices["sentiment-analyzer"] = 
    //             LiteRTWrapper(context, "models/sentiment_analysis.tflite", createCompiledModelOptions())
    //     }
        
    //     // Medium models for capable devices
    //     if (deviceCapabilities.memoryMB > 3000) {
    //         literTServices["named-entity-recognizer"] =
    //             LiteRTWrapper(context, "models/ner_model.tflite", createCompiledModelOptions())
    //     }
        
    //     // Large models for powerhouse devices
    //     if (deviceCapabilities.memoryMB > 6000) {
    //         literTServices["document-summarizer"] =
    //             LiteRTWrapper(context, "models/summarization_large.tflite", createCompiledModelOptions())
    //     }
        
    //     Log.i(TAG, "Initialized ${literTServices.size} LiteRT services")
    // }
    
    private fun createCompiledModelOptions(): CompiledModel.Options {
        // Choose accelerator based on device capabilities
        val accelerator = when {
            deviceCapabilities.hasGPUAcceleration -> Accelerator.GPU
            else -> Accelerator.CPU
        }

        return CompiledModel.Options(accelerator)
    }
    
    private suspend fun processMLKitNative(serviceId: String, input: MLServiceInput): MLServiceResult {
        val wrapper = mlKitNativeServices[serviceId] 
            ?: return MLServiceResult.error("Service not available: $serviceId")
        
        return withContext(Dispatchers.Default) {
            wrapper.process(input)
        }
    }
    
    private suspend fun processMLKitCustom(serviceId: String, input: MLServiceInput): MLServiceResult {
        val wrapper = mlKitCustomServices[serviceId]
            ?: return MLServiceResult.error("Custom service not available: $serviceId")
        
        return withContext(Dispatchers.Default) {
            wrapper.process(input)
        }
    }
    
    // private suspend fun processLiteRT(serviceId: String, input: MLServiceInput): MLServiceResult {
    //     val wrapper = literTServices[serviceId]
    //         ?: return MLServiceResult.error("LiteRT service not available: $serviceId")
        
    //     return withContext(Dispatchers.Default) {
    //         wrapper.process(input)
    //     }
    // }
}

// Base interface for ML service wrappers
// ...existing code...

// Tier 1: ML Kit Native Implementation
class TextRecognitionWrapper : MLServiceWrapper {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    companion object { private const val TAG = "TextRecognitionWrapper" }

    override suspend fun process(input: MLServiceInput): MLServiceResult {
        return try {
            val bitmap = input.bitmap ?: return MLServiceResult.error("No bitmap provided")
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()

            MLServiceResult.success(mapOf<String, Any>(
                "text" to (result.text ?: ""),
                "confidence" to 0.95f // ML Kit doesn't provide confidence, use default
            ))
        } catch (e: Exception) {
            MLServiceResult.error("Text recognition failed: ${e.message}")
        }
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "text-recognition",
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_NATIVE,
            version = "1.0.0",
            sizeKB = 0, // Via Google Play Services
            capabilities = listOf("ocr", "text-extraction", "real-time"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 512,
                minStorageMB = 0
            ),
            executionProfile = ExecutionProfile(
                profileName = "native-model",
                cpuCores = 1,
                gpuEnabled = false,
                memoryMB = 150,
                storageMB = 0
            )
        )
    }
}

// Placeholder implementations for other wrappers
class FaceDetectionWrapper : MLServiceWrapper {
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        // Implementation using ML Kit Face Detection API
        return MLServiceResult.success(mapOf("faces" to emptyList<Any>()))
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "face-detection",
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_NATIVE,
            version = "1.0.0",
            sizeKB = 20 * 1024, // 20MB via Play Services
            capabilities = listOf("face-detection", "landmarks", "real-time"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 2000,
                minStorageMB = 0
            ),
            executionProfile = ExecutionProfile(
                profileName = "face-detection",
                cpuCores = 1,
                gpuEnabled = false,
                memoryMB = 500,
                storageMB = 0
            )
        )
    }
}

class ObjectDetectionWrapper : MLServiceWrapper {
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        return MLServiceResult.success(mapOf("objects" to emptyList<Any>()))
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "object-detection",
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_NATIVE,
            version = "1.0.0",
            sizeKB = 45 * 1024,
            capabilities = listOf("object-detection", "tracking", "classification"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 4000,
                minStorageMB = 0
            ),
            executionProfile = ExecutionProfile(
                profileName = "object-detection",
                cpuCores = 2,
                gpuEnabled = false,
                memoryMB = 800,
                storageMB = 0
            )
        )
    }
}

class TranslationWrapper : MLServiceWrapper {
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        return MLServiceResult.success(mapOf<String, Any>("translated_text" to (input.text ?: "")))
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "translation",
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_NATIVE,
            version = "1.0.0",
            sizeKB = 30 * 1024,
            capabilities = listOf("translation", "multilingual", "offline"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 4000,
                minStorageMB = 0
            ),
            executionProfile = ExecutionProfile(
                profileName = "translation",
                cpuCores = 2,
                gpuEnabled = false,
                memoryMB = 1200,
                storageMB = 0
            )
        )
    }
}

// Tier 2: ML Kit Custom Model Wrappers
class CustomImageLabelingWrapper(private val modelPath: String) : MLServiceWrapper {
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        // Implementation using ML Kit Custom Image Labeling
        return MLServiceResult.success(mapOf("labels" to emptyList<Any>()))
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "custom-image-classifier",
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_CUSTOM,
            version = "1.0.0",
            sizeKB = 15 * 1024,
            capabilities = listOf("image-classification", "custom-labels"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 3000,
                minStorageMB = 15 * 1024
            ),
            executionProfile = ExecutionProfile(
                profileName = "custom-image-classifier",
                cpuCores = 2,
                gpuEnabled = false,
                memoryMB = 1500,
                storageMB = 15 * 1024
            )
        )
    }
}

class CustomObjectDetectionWrapper(private val modelPath: String) : MLServiceWrapper {
    override suspend fun process(input: MLServiceInput): MLServiceResult {
        return MLServiceResult.success(mapOf("detections" to emptyList<Any>()))
    }
    
    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = "custom-object-detector", 
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_CUSTOM,
            version = "1.0.0",
            sizeKB = 35 * 1024,
            capabilities = listOf("object-detection", "custom-classes"),
            resourceRequirements = ResourceRequirements(
                minMemoryMB = 4000,
                minStorageMB = 35 * 1024
            ),
            executionProfile = ExecutionProfile(
                profileName = "custom-object-detector",
                cpuCores = 2,
                gpuEnabled = false,
                memoryMB = 2500,
                storageMB = 35 * 1024
            )
        )
    }
}

// Tier 3: Direct LiteRT Implementation
// class LiteRTWrapper(
//     private val context: Context,
//     private val modelPath: String,
//     private val options: CompiledModel.Options
// ) : MLServiceWrapper {

//     companion object { private const val TAG = "LiteRTWrapper" }

//     private var compiledModel: CompiledModel? = null

//     override suspend fun process(input: MLServiceInput): MLServiceResult {
//         return try {
//             val model = compiledModel ?: loadModel()

//             // Perform inference using LiteRT buffer-based API
//             val result = performInference(model, input)

//             MLServiceResult.success(mapOf<String, Any>("result" to result))
//         } catch (e: Exception) {
//             Log.e(TAG, "LiteRT inference failed", e)
//             MLServiceResult.error("LiteRT inference failed: ${e.message}")
//         }
//     }

//     private fun loadModel(): CompiledModel {
//         try {
//             // Load model from assets
//             val modelFile = context.assets.open(modelPath)
//             val modelBytes = modelFile.readBytes()
//             modelFile.close()

//             val created = LitertCompiledModelFactory.tryCreateFromBytes(modelBytes, options)
//             if (created == null) {
//                 throw IllegalStateException("Failed to create CompiledModel - no compatible factory available")
//             }
//             this.compiledModel = created
//             return created
//         } catch (e: Exception) {
//             Log.e(TAG, "Failed to load LiteRT model: $modelPath", e)
//             throw e
//         }
//     }

//     private fun performInference(model: CompiledModel, input: MLServiceInput): Any {
//         // Create input and output buffers
//         val inputBuffers = model.createInputBuffers()
//         val outputBuffers = model.createOutputBuffers()

//         try {
//             // Simplified placeholder implementation
//             return "inference_result_placeholder"
//         } finally {
//             inputBuffers.forEach { it.close() }
//             outputBuffers.forEach { it.close() }
//         }
//     }

//     fun cleanup() {
//         try {
//             compiledModel?.close()
//             compiledModel = null
//         } catch (e: Exception) {
//             Log.w(TAG, "Error cleaning up LiteRT model", e)
//         }
//     }

//     override fun getServiceAnnouncement(): ServiceAnnouncement {
//         return ServiceAnnouncement(
//             serviceId = modelPath.substringAfterLast("/").substringBeforeLast("."),
//             serviceType = ServiceAnnouncement.ServiceType.LITERT,
//             version = "1.0.0",
//             sizeKB = 25 * 1024, // Estimated model size
//             capabilities = listOf("custom-inference", "tensorflow-lite"),
//             resourceRequirements = ResourceRequirements(
//                 minMemoryMB = 1000,
//                 minStorageMB = 25 * 1024
//             ),
//             executionProfile = ExecutionProfile(
//                 profileName = modelPath.substringAfterLast("/").substringBeforeLast("."),
//                 cpuCores = 1,
//                 gpuEnabled = false,
//                 memoryMB = 500,
//                 storageMB = 25 * 1024
//             )
//         )
//     }
// }

// Data classes for ML service I/O
// ...existing code...

// Task.await moved to TaskAwait.kt
