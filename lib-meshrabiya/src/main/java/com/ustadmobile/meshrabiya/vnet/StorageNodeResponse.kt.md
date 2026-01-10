package com.ustadmobile.meshrabiya.vnet

data class StorageNodeResponse(
    val nodeId: String,
    val availableSpace: Long,
    val systemState: String,
    val url: String,
    val latency: Int,
    val fitnessScore: Float
)