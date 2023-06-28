package com.ustadmobile.meshrabiya.vnet.wifi

import kotlinx.coroutines.flow.Flow


interface MeshrabiyaWifiManager {

    val state: Flow<MeshrabiyaWifiState>

    val is5GhzSupported: Boolean

    suspend fun requestHotspot(
        requestMessageId: Int,
        request: LocalHotspotRequest
    ): LocalHotspotResponse


    suspend fun connectToHotspot(
        config: HotspotConfig
    )

}