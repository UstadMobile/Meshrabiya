package com.ustadmobile.meshrabiya.ext

import android.net.wifi.WifiConfiguration

@Suppress("Deprecation") //Must use WiFiconfiguration to support pre SDK30 devices
fun WifiConfiguration.prettyPrint(): String {
    return "WifiConfiguratino(ssid=$SSID passphrase=$preSharedKey BSSID=$BSSID)"
}
