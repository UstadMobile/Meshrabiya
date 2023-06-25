package com.ustadmobile.meshrabiya.ext

import android.net.wifi.p2p.WifiP2pGroup
import com.ustadmobile.meshrabiya.vnet.localhotspot.HotspotConfig

fun WifiP2pGroup.toPrettyString(): String {
    return buildString {
        append("WifiP2pGroup: interface=${`interface`} groupOwner = $isGroupOwner, networkName=$networkName, passphrase=${passphrase}")
    }
}
