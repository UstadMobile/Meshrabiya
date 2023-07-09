package com.ustadmobile.meshrabiya.vnet.wifi.state

import com.ustadmobile.meshrabiya.vnet.WifiRole
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType


data class MeshrabiyaWifiState(
    val wifiRole: WifiRole = WifiRole.NONE,
    val wifiDirectState: WifiDirectState = WifiDirectState(),
    val localOnlyHotspotState: LocalOnlyHotspotState = LocalOnlyHotspotState(),
    val errorCode: Int = 0,
) {
    val config: WifiConnectConfig?
        get() = if(wifiRole == WifiRole.WIFI_DIRECT_GROUP_OWNER) {
            wifiDirectState.config
        }else if(wifiRole == WifiRole.LOCAL_ONLY_HOTSPOT) {
            localOnlyHotspotState.config
        }else {
            null
        }

    val hotspotStartedOrStarting: Boolean
        get() = config != null ||
                    wifiDirectState.hotspotStatus == HotspotStatus.STARTING ||
                    localOnlyHotspotState.status == HotspotStatus.STARTING


    /**
     * Determine the type of hotspot that should be created if a request is made. If a hotspot
     */
    val hotspotTypeToCreate: HotspotType?
        get() {
            return if(config != null)
                //Hotspot already available- nothing to create
                null
            else if(
                //WifiDirect Group or Local Only hotspot already being created, do nothing
                wifiDirectState.hotspotStatus == HotspotStatus.STARTING ||
                        localOnlyHotspotState.status == HotspotStatus.STARTING
            ) {
                null
            } else if(wifiRole == WifiRole.WIFI_DIRECT_GROUP_OWNER) {
                //We are probably a station on a local only hotspot, so we need to create a wifi direct group
                HotspotType.WIFIDIRECT_GROUP
            }else {
                //TEMPORARY for wifi direct testing ONLY
                HotspotType.WIFIDIRECT_GROUP
                //HotspotType.LOCALONLY_HOTSPOT
            }

        }

}
