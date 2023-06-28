package com.ustadmobile.meshrabiya.vnet.wifi

import com.ustadmobile.meshrabiya.vnet.WifiRole


data class MeshrabiyaWifiState(
    val wifiRole: WifiRole = WifiRole.NONE,
    val wifiDirectGroupStatus: LocalHotspotStatus = LocalHotspotStatus.STOPPED,
    val localOnlyHotspotStatus: LocalHotspotStatus = LocalHotspotStatus.STOPPED,
    val config: HotspotConfig? = null,
    val errorCode: Int = 0,
)
