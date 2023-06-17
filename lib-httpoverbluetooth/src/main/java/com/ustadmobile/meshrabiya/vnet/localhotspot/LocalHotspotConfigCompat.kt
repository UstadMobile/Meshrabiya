package com.ustadmobile.meshrabiya.vnet.localhotspot

import android.net.wifi.WifiManager
import android.os.Build

//Thanks, Google.
class LocalHotspotConfigCompat(
    val ssid: String?,
    val passphrase: String?,
) {
}

fun WifiManager.LocalOnlyHotspotReservation.toLocalHotspotConfig(): LocalHotspotConfigCompat {
    return if(Build.VERSION.SDK_INT >= 30) {
        val softApConfig = softApConfiguration
        LocalHotspotConfigCompat(
            ssid = softApConfig.ssid,
            passphrase = softApConfig.passphrase,
        )
    }else {
        val wifiConfig = wifiConfiguration
        LocalHotspotConfigCompat(
            ssid = wifiConfig?.SSID,
            passphrase = wifiConfig?.preSharedKey?.removeSurrounding("\"")
        )
    }
}
