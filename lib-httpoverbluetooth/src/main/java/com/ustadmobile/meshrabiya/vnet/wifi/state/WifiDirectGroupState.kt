package com.ustadmobile.meshrabiya.vnet.wifi.state

import com.ustadmobile.meshrabiya.vnet.wifi.HotspotConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus

data class WifiDirectGroupState(
    val hotspotStatus: HotspotStatus = HotspotStatus.STOPPED,
    val error: Int = 0,
    val config: HotspotConfig? = null,
) {
}