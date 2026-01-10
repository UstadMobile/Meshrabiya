package com.ustadmobile.meshrabiya.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual

@Serializable
data class ServiceAnnouncement(
    val serviceId: String,
    val serviceType: ServiceType,
    val version: String,
    val sizeKB: Int,
    val capabilities: List<String>,
    @Contextual val resourceRequirements: ResourceRequirements,
    @Contextual val executionProfile: ExecutionProfile
) {
    // TODO Is this the right sivision of service types.  should there be just ML, JAVA, PYTJON, WOrkflow,LITERT, 
    // with some kind of subtyping for ML_TEXT_RECOGNITION, ML_OBJECT_DETECTION, ML_TRANSLATION, ML_KIT_NATIVE, ML_KIT_CUSTOM,
    
    @Serializable
    enum class ServiceType {
        PYTHON, JAVA, ML_KIT_NATIVE, ML_KIT_CUSTOM, WORKFLOW,
        ML_TEXT_RECOGNITION, ML_OBJECT_DETECTION, ML_TRANSLATION
    }
}