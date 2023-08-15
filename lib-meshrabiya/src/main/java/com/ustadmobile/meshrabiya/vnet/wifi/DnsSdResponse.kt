package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.wifi.p2p.WifiP2pDevice

data class DnsSdResponse(
    val instanceName: String,
    val registrationType: String,
    val device: WifiP2pDevice,
)
