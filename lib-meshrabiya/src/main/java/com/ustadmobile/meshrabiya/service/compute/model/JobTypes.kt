package com.ustadmobile.meshrabiya.service.compute.model

import com.ustadmobile.meshrabiya.service.compute.PythonLibrary
import com.ustadmobile.meshrabiya.service.compute.InferenceConfig
import com.ustadmobile.meshrabiya.service.compute.Precision

/**
 * Enum representing the type of distributed job.
 */
enum class JobType {
    IMAGE_PROCESSING,
    DATA_ANALYSIS,
    ML_PIPELINE,
    SENSOR_FUSION,
    COLLABORATIVE_FILTERING
    // DISTRIBUTED_STORAGE removed as per implementation plan
}

/**
 * Enum representing aggregation strategies for distributed compute results.
 */
enum class AggregationStrategy {
    SIMPLE_CONCAT,
    MAJORITY_VOTE,
    WEIGHTED_AVERAGE,
    ENSEMBLE_COMBINE
}

/**
 * Enum representing specialized capabilities of a node.
 */
enum class SpecializedCapability {
    IMAGE_PROCESSING,
    AUDIO_PROCESSING,
    NLP,
    COMPUTER_VISION,
    SIGNAL_PROCESSING,
    CRYPTOGRAPHY,
    SCIENTIFIC_COMPUTING
}

// Note: PythonLibrary, InferenceConfig, and Precision are now imported from SupportTypes.kt
// to avoid redeclaration errors