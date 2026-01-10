package com.ustadmobile.meshrabiya.service

data class MeshMessage(
    val recipientNodeId: String,
    val messageType: String,
    val payload: Map<String, Any>
)
