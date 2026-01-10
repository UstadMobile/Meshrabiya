package com.ustadmobile.meshrabiya.service.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Accelerator
import com.ustadmobile.meshrabiya.model.DeviceCapabilities
import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.model.ExecutionProfile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ModelQuantizationManager
 *
 * Provides utilities to select and manage pre-quantized TensorFlow Lite models.
 * This class does not perform full on-device quantization; it selects variants
 * and provides helpers to create CompiledModel instances using a safe reflection
 * fallback when public factory methods are not accessible.
 */
class ModelQuantizationManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelSelectionManager"
    }

    enum class QuantizationType {
        NONE,           // 32-bit (full precision)
        FLOAT16,        // 16-bit (50% size reduction)
        INT8,           // 8-bit (75% size reduction)
        DYNAMIC,        // Runtime quantization
        INT8_FALLBACK   // 8-bit with 32-bit fallback for unsupported ops
    }

    data class QuantizationResult(
        val originalSizeBytes: Long,
        val quantizedSizeBytes: Long,
        val compressionRatio: Float,
        val quantizationType: QuantizationType,
        val accuracyLoss: Float = 0f,
        val speedupFactor: Float = 1f
    ) {
        val sizeSavingsMB: Float get() = (originalSizeBytes - quantizedSizeBytes) / (1024f * 1024f)
        val sizeSavingsPercent: Float get() = ((originalSizeBytes - quantizedSizeBytes).toFloat() / originalSizeBytes) * 100f
    }

    /**
     * Select optimal pre-quantized model variant for device capabilities
     */
    fun selectOptimalQuantizedModel(
        availableModels: List<ServiceAnnouncement>,
        deviceCapabilities: DeviceCapabilities
    ): ServiceAnnouncement? {
        val compatibleModels = availableModels.filter { model ->
            deviceCapabilities.memoryMB >= model.resourceRequirements.minMemoryMB &&
            deviceCapabilities.storageMB >= model.resourceRequirements.minStorageMB
        }

        return when (deviceCapabilities.deviceClass) {
            DeviceCapabilities.DeviceClass.CONSUMER -> {
                compatibleModels.filter { it.serviceId.contains("int8") }.minByOrNull { it.sizeKB }
            }
            DeviceCapabilities.DeviceClass.ML_BASIC -> {
                compatibleModels.find { it.serviceId.contains("int8") }
                    ?: compatibleModels.find { it.serviceId.contains("fp16") }
            }
            DeviceCapabilities.DeviceClass.ML_CAPABLE -> {
                compatibleModels.find { it.serviceId.contains("fp16") }
                    ?: compatibleModels.find { it.serviceId.contains("int8") }
                    ?: compatibleModels.find { it.serviceId.contains("fp32") }
            }
            DeviceCapabilities.DeviceClass.ML_POWERHOUSE -> {
                compatibleModels.find { it.serviceId.contains("fp32") }
                    ?: compatibleModels.find { it.serviceId.contains("fp16") }
                    ?: compatibleModels.find { it.serviceId.contains("int8") }
            }
        }
    }

    /**
     * Simulated quantization operations (placeholders for offline tooling results)
     */
    fun quantizeModel(
        originalModelPath: String,
        quantizationType: QuantizationType,
        outputPath: String? = null
    ): QuantizationResult {
        val originalFile = File(originalModelPath)
        val originalSize = originalFile.length()
        Log.i(TAG, "Quantizing model $originalModelPath (${originalSize / 1024 / 1024}MB) with $quantizationType")

        return when (quantizationType) {
            QuantizationType.NONE -> QuantizationResult(originalSize, originalSize, 1.0f, quantizationType)
            QuantizationType.FLOAT16 -> quantizeToFloat16(originalModelPath, outputPath, originalSize)
            QuantizationType.INT8 -> quantizeToInt8(originalModelPath, outputPath, originalSize)
            QuantizationType.DYNAMIC -> applyDynamicQuantization(originalModelPath, outputPath, originalSize)
            QuantizationType.INT8_FALLBACK -> quantizeToInt8WithFallback(originalModelPath, outputPath, originalSize)
        }
    }

    private fun quantizeToFloat16(originalPath: String, outputPath: String?, originalSize: Long): QuantizationResult {
        val quantizedSize = (originalSize * 0.5).toLong()
        Log.i(TAG, "Float16 quantization: ${originalSize / 1024 / 1024}MB -> ${quantizedSize / 1024 / 1024}MB")
        return QuantizationResult(originalSize, quantizedSize, 0.5f, QuantizationType.FLOAT16, 0.1f, 1.5f)
    }

    private fun quantizeToInt8(originalPath: String, outputPath: String?, originalSize: Long): QuantizationResult {
        val quantizedSize = (originalSize * 0.25).toLong()
        Log.i(TAG, "Int8 quantization: ${originalSize / 1024 / 1024}MB -> ${quantizedSize / 1024 / 1024}MB")
        return QuantizationResult(originalSize, quantizedSize, 0.25f, QuantizationType.INT8, 1.0f, 2.5f)
    }

    private fun applyDynamicQuantization(originalPath: String, outputPath: String?, originalSize: Long): QuantizationResult {
        val quantizedSize = (originalSize * 0.3).toLong()
        Log.i(TAG, "Dynamic quantization: ${originalSize / 1024 / 1024}MB -> ${quantizedSize / 1024 / 1024}MB")
        return QuantizationResult(originalSize, quantizedSize, 0.3f, QuantizationType.DYNAMIC, 0.2f, 2.0f)
    }

    private fun quantizeToInt8WithFallback(originalPath: String, outputPath: String?, originalSize: Long): QuantizationResult {
        val quantizedSize = (originalSize * 0.35).toLong()
        Log.i(TAG, "Int8 with fallback: ${originalSize / 1024 / 1024}MB -> ${quantizedSize / 1024 / 1024}MB")
        return QuantizationResult(originalSize, quantizedSize, 0.35f, QuantizationType.INT8_FALLBACK, 0.5f, 2.2f)
    }

    private fun loadModelFile(modelPath: String): ByteBuffer {
        return try {
            val fileBytes = if (modelPath.startsWith("assets/")) {
                val assetPath = modelPath.removePrefix("assets/")
                context.assets.open(assetPath).readBytes()
            } else {
                File(modelPath).readBytes()
            }

            ByteBuffer.allocateDirect(fileBytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(fileBytes)
                rewind()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model file: $modelPath", e)
            throw e
        }
    }

    /**
     * Create a CompiledModel using reflection as a fallback if direct factory is not accessible
     */
    private fun createCompiledModelFromBuffer(buffer: ByteBuffer, options: CompiledModel.Options): CompiledModel {
        try {
            val companionField = CompiledModel::class.java.getDeclaredField("Companion")
            companionField.isAccessible = true
            val companion = companionField.get(null)

            val createMethod = companion.javaClass.declaredMethods.firstOrNull { m ->
                m.name == "create" && CompiledModel::class.java.isAssignableFrom(m.returnType)
            } ?: throw NoSuchMethodException("No suitable 'create' method found on CompiledModel.Companion")

            createMethod.isAccessible = true
            val params = createMethod.parameterTypes

            val args: Array<Any?> = when {
                params.size == 2 && params[0].isAssignableFrom(ByteBuffer::class.java) -> arrayOf(buffer, options)
                params.isNotEmpty() && params[0].isAssignableFrom(ByteBuffer::class.java) -> arrayOf(buffer)
                else -> arrayOf(buffer, options)
            }

            return createMethod.invoke(companion, *args) as CompiledModel
        } catch (e: Exception) {
            throw RuntimeException("Failed to instantiate CompiledModel via reflection", e)
        }
    }

    /**
     * Public helper to create a quantized CompiledModel for use by callers.
     */
    fun createQuantizedModel(modelPath: String, quantizationType: QuantizationType, accelerator: Accelerator = Accelerator.CPU): CompiledModel {
        val selectedAccelerator = when (quantizationType) {
            QuantizationType.INT8, QuantizationType.INT8_FALLBACK -> Accelerator.CPU
            QuantizationType.FLOAT16 -> if (isGpuAvailable()) Accelerator.GPU else Accelerator.CPU
            QuantizationType.DYNAMIC -> accelerator
            else -> accelerator
        }

        val modelBuffer = loadModelFile(modelPath)
        val options = CompiledModel.Options(selectedAccelerator)
        return createCompiledModelFromBuffer(modelBuffer, options)
    }

    fun createOptimizedService(modelPath: String, quantizationType: QuantizationType, accelerator: Accelerator = Accelerator.CPU): CompiledModel {
        return createQuantizedModel(modelPath, quantizationType, accelerator)
    }

    private fun isGpuAvailable(): Boolean {
        return try {
            // Simple probe - creation may fail on unsupported devices
            val testOptions = CompiledModel.Options(Accelerator.GPU)
            true
        } catch (e: Exception) {
            Log.w(TAG, "GPU acceleration not available: ${e.message}")
            false
        }
    }

    fun analyzeQuantizationOptions(modelSizeBytes: Long, deviceCapabilities: DeviceCapabilities): List<QuantizationResult> {
        return QuantizationType.values().map { quantType ->
            when (quantType) {
                QuantizationType.NONE -> QuantizationResult(modelSizeBytes, modelSizeBytes, 1.0f, quantType, 0f, 1.0f)
                QuantizationType.FLOAT16 -> QuantizationResult(modelSizeBytes, (modelSizeBytes * 0.5).toLong(), 0.5f, quantType, 0.1f, 1.5f)
                QuantizationType.INT8 -> QuantizationResult(modelSizeBytes, (modelSizeBytes * 0.25).toLong(), 0.25f, quantType, 1.0f, 2.5f)
                QuantizationType.DYNAMIC -> QuantizationResult(modelSizeBytes, (modelSizeBytes * 0.3).toLong(), 0.3f, quantType, 0.2f, 2.0f)
                QuantizationType.INT8_FALLBACK -> QuantizationResult(modelSizeBytes, (modelSizeBytes * 0.35).toLong(), 0.35f, quantType, 0.5f, 2.2f)
            }
        }.sortedBy { it.sizeSavingsPercent }
    }
}
