package com.ustadmobile.meshrabiya.vnet.wifi

data class LocalHotspotRequest(
    val preferredBand: ConnectBand,
    val preferredType: HotspotType,
) {
}