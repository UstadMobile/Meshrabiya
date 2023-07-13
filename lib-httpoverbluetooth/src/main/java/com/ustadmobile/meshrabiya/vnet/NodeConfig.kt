package com.ustadmobile.meshrabiya.vnet

data class NodeConfig(
    val maxHops: Int,
) {
    companion object {
        val DEFAULT_CONFIG = NodeConfig(
            maxHops = 5,
        )
    }

}