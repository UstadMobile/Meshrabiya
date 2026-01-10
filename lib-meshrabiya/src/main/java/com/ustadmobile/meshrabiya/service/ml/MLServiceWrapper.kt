
package com.ustadmobile.meshrabiya.service.ml

import com.ustadmobile.meshrabiya.model.ServiceAnnouncement

/**
 * Production-ready interface for ML service wrappers.
 * Implementations should provide ML inference, resource reporting, and service announcement logic.
 *
 * NOTE: Default implementations are provided for convenience so existing wrapper
 * classes compile without modification. Concrete wrappers should override where appropriate.
 */
interface MLServiceWrapper {
    /**
     * Runs inference on the provided input and returns the result.
     */
    suspend fun process(input: MLServiceInput): MLServiceResult

    /**
     * Returns a ServiceAnnouncement describing the ML service, its capabilities, and requirements.
     */
    fun getServiceAnnouncement(): ServiceAnnouncement

    /**
     * Returns true if the service is available and ready to process requests.
     * Default: available.
     */
    fun isAvailable(): Boolean {
        return true
    }

    /**
     * Returns a human-readable name for the ML service.
     * Default: "unknown" - wrappers should override to provide a meaningful name.
     */
    fun getServiceName(): String {
        return "unknown"
    }
}