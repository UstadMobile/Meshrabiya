package com.ustadmobile.meshrabiya.service.ml

import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.model.ExecutionProfile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ustadmobile.meshrabiya.service.ml.MLServiceWrapper
import com.ustadmobile.meshrabiya.service.ml.MLServiceInput
import com.ustadmobile.meshrabiya.service.ml.MLServiceResult

class MLKitNativeWrapper(private val serviceId: String) : MLServiceWrapper {
    override fun isAvailable(): Boolean {
        // In production, check if ML Kit is available
        return true
    }

    override fun getServiceName(): String {
        return serviceId
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun process(input: MLServiceInput): MLServiceResult {
        // Ensure a bitmap was provided
        val bitmap = input.bitmap ?: return MLServiceResult.error("No bitmap provided")

        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = recognizer.process(image)
            val result = task.await()
            MLServiceResult.success(mapOf<String, Any>(
                "text" to (result.text ?: "")
            ))
        } catch (e: Exception) {
            MLServiceResult.error("Native model inference failed: ${e.message}")
        }
    }

    override fun getServiceAnnouncement(): ServiceAnnouncement {
        return ServiceAnnouncement(
            serviceId = serviceId,
            serviceType = ServiceAnnouncement.ServiceType.ML_KIT_NATIVE,
            version = "1.0",
            sizeKB = 0,
            capabilities = listOf("text-recognition", "face-detection"),
            resourceRequirements = ResourceRequirements(
                minRAMMB = 256,
                preferredRAMMB = 512,
                requiresGPU = false,
                minStorageMB = 64f,
                minCpuCores = 1,
                minGpu = false
            ),
            executionProfile = ExecutionProfile(
                profileName = "native-model",
                cpuCores = 1,
                gpuEnabled = false,
                memoryMB = 256,
                storageMB = 64
            )
        )
    }
}