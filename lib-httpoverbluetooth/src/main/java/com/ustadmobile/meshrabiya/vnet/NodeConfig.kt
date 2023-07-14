package com.ustadmobile.meshrabiya.vnet

data class NodeConfig(
    val maxHops: Int,
    val originatingMessageInterval: Long = 3000,
    val originatingMessageInitialDelay: Long = 1000,
) {
    companion object {
        val DEFAULT_CONFIG = NodeConfig(
            maxHops = 5,
        )
    }

}