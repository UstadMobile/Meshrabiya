package com.ustadmobile.meshrabiya.vnet.localhotspot


data class LocalHotspotState(
    val status: LocalHotspotStatus,
    val config: HotspotConfig? = null,
    val errorCode: Int = 0,
)
