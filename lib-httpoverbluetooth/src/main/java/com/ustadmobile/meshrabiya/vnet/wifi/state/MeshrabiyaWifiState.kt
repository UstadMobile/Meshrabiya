package com.ustadmobile.meshrabiya.vnet.wifi.state

import com.ustadmobile.meshrabiya.vnet.WifiRole
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType


data class MeshrabiyaWifiState(
    val wifiRole: WifiRole = WifiRole.NONE,
    val wifiDirectState: WifiDirectState = WifiDirectState(),
    val errorCode: Int = 0,
) {
    val config: WifiConnectConfig?
        get() = wifiDirectState.config


    /**
     * Determine the type of hotspot that should be created if a request is made to start one.
     * Currently only WifiDirect group is supported.
     */
    val hotspotTypeToCreate: HotspotType?
        get() {
            return if(config != null)
                //Hotspot already available- nothing to create
                null
            else if(
                //WifiDirect Group or Local Only hotspot already being created, do nothing
                wifiDirectState.hotspotStatus == HotspotStatus.STARTING
            ) {
                null
            } else {
                //HotspotType.LOCALONLY_HOTSPOT
                HotspotType.WIFIDIRECT_GROUP
            }

        }

}
