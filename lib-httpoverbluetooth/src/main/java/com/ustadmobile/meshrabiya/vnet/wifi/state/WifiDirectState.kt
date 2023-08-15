package com.ustadmobile.meshrabiya.vnet.wifi.state

import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus

data class WifiDirectState(
    val hotspotStatus: HotspotStatus = HotspotStatus.STOPPED,
    val error: Int = 0,
    val config: WifiConnectConfig? = null,
) {
}