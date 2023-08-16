package com.ustadmobile.meshrabiya.ext

import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand

fun WifiP2pGroup.toPrettyString(): String {
    return buildString {
        val frequencyStr = if(Build.VERSION.SDK_INT >= 29) " frequency=$frequency " else ""
        append("WifiP2pGroup: interface=${`interface`} groupOwner = $isGroupOwner, " +
                "networkName=$networkName, passphrase=${passphrase} frequency=$frequencyStr")
    }
}

val WifiP2pGroup.connectBand: ConnectBand
    get() {
        return when {
            Build.VERSION.SDK_INT < 29 -> ConnectBand.BAND_UNKNOWN
            frequency in 2400..2500 -> ConnectBand.BAND_2GHZ
            frequency in 5150 .. 5900 -> ConnectBand.BAND_5GHZ
            else -> ConnectBand.BAND_UNKNOWN
        }
    }


