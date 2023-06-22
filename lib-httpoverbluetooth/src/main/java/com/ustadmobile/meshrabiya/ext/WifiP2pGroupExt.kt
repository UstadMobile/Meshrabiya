package com.ustadmobile.meshrabiya.ext

import android.net.wifi.p2p.WifiP2pGroup
import com.ustadmobile.meshrabiya.vnet.localhotspot.LocalHotspotConfigCompat

fun WifiP2pGroup.toPrettyString(): String {
    return buildString {
        append("WifiP2pGroup: groupOwner = $isGroupOwner, networkName=$networkName, passphrase=${passphrase}")
    }
}

fun WifiP2pGroup.groupToHotspotConfigCompat(): LocalHotspotConfigCompat? {
    val name = networkName
    val pass = passphrase
    return if(name != null && pass != null){
        LocalHotspotConfigCompat(ssid = name, passphrase = pass)
    }else {
        null
    }
}