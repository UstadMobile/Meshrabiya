package com.ustadmobile.meshrabiya.ext

import android.net.wifi.p2p.WifiP2pConfig
import android.os.Build
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand

fun WifiP2pConfig.prettyPrint(): String {
    return if(Build.VERSION.SDK_INT >= 30) {
        "WifiP2pConfig (networkName=${networkName} networkId=$networkId passphrase=$passphrase " +
                "groupOwnerBand=${ConnectBand.fromFlag(groupOwnerBand.toByte())})"
    }else {
        toString()
    }
}
