package com.ustadmobile.meshrabiya.ext

import android.net.wifi.SoftApConfiguration
import android.os.Build
import androidx.annotation.RequiresApi


val SoftApConfiguration.ssidCompat: String ?
    @RequiresApi(30)
    get() {
        return if(Build.VERSION.SDK_INT >= 33) {
            //As per https://developer.android.com/reference/android/net/wifi/WifiSsid#toString()
            // Any WiFi ssid that is in UTF-8 will be as a string with quotes.
            // No support for ssid with non UTF-8 SSID.
            wifiSsid.toString().removeSurrounding("\"")
        }else {
            @Suppress("DEPRECATION") //Required to support pre-SDK33
            ssid
        }
    }

@RequiresApi(30)
fun SoftApConfiguration.prettyPrint() : String{
    return "SoftApConfiguration(ssid=$ssidCompat passphrase=$passphrase bssid=$bssid " +
            "hidden=$isHiddenSsid securityType=$securityType)"
}