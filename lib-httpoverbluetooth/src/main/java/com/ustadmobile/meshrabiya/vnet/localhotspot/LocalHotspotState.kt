package com.ustadmobile.meshrabiya.vnet.localhotspot


data class LocalHotspotState(
    val status: LocalHotspotStatus,
    val config: LocalHotspotConfigCompat? = null,
    val errorCode: Int = 0,
)
