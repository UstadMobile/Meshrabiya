package com.ustadmobile.meshrabiya.model

data class ExecutionProfile(
    val profileName: String = "default",
    val cpuCores: Int = 1,
    val gpuEnabled: Boolean = false,
    val memoryMB: Int = 0,
    val storageMB: Int = 0
)
