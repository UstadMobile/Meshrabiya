
package com.ustadmobile.meshrabiya.service.ml

import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.model.ExecutionProfile
import com.ustadmobile.meshrabiya.service.ml.MLServiceWrapper
import com.ustadmobile.meshrabiya.service.ml.MLServiceInput
import com.ustadmobile.meshrabiya.service.ml.MLServiceResult

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class MLKitCustomWrapper(
    private val serviceId: String,
    private val modelPath: String
) : MLServiceWrapper {
    private var interpreter: Interpreter? = null

    override fun isAvailable(): Boolean {
        // Check if model file exists and is loadable
        return try {
            val file = File(modelPath)
            file.exists() && file.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    override fun getServiceName(): String {
        return serviceId
    }

    override suspend fun process(input: MLServiceInput): MLServiceResult {
        // Real ML Kit custom model inference using TensorFlow Lite Interpreter
        return try {
            if (interpreter == null) {
                interpreter = Interpreter(loadModelFile(modelPath))
            }
            val bitmap = input.bitmap
            if (bitmap == null) {
                return MLServiceResult.error("Input bitmap required for custom model inference")
            }
            val inputBuffer = convertBitmapToByteBuffer(bitmap)
            val outputMap = HashMap<Int, Any>()
            // Example: output for classification (adjust shape as needed)
            val outputArray = Array(1) { FloatArray(100) } // Assume 100 labels
            interpreter?.run(inputBuffer, outputArray)
            val labels = outputArray[0].mapIndexed { idx, score ->
                if (score > 0.5f) "label$idx" else null
            }.filterNotNull()
            MLServiceResult.success(mapOf("labels" to labels, "scores" to outputArray[0].toList()))
        } catch (e: Exception) {
            MLServiceResult.error("Custom model inference failed: ${e.message}")
        }
    }

    private fun loadModelFile(path: String): ByteBuffer {
        val file = File(path)
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        val mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        inputStream.close()
        return mappedByteBuffer
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val inputSize = bitmap.width * bitmap.height * 3 // RGB
        val byteBuffer = ByteBuffer.allocateDirect(inputSize)
        byteBuffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixel in pixels) {
            byteBuffer.put(((pixel shr 16) and 0xFF).toByte()) // R
            byteBuffer.put(((pixel shr 8) and 0xFF).toByte())  // G
            byteBuffer.put((pixel and 0xFF).toByte())         // B
        }
        byteBuffer.rewind()
        return byteBuffer
    }

    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = serviceId,
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_CUSTOM,
            version = "1.0",
            sizeKB = 0,
            capabilities = listOf("custom-model", "image-labeling"),
            resourceRequirements = ResourceRequirements(
                minRAMMB = 512,
                preferredRAMMB= 512,
                minStorageMB = 128,
                minCpuCores = 1,
                minGpu = true
            ),
            executionProfile = ExecutionProfile(
                profileName = "custom-model",
                cpuCores = 1,
                gpuEnabled = true,
                memoryMB = 512,
                storageMB = 128
            )
        )
    }
}