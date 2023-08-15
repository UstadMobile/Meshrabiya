package com.ustadmobile.meshrabiya.ext

import android.companion.CompanionDeviceManager
import android.net.MacAddress
import android.os.Build


@Suppress("DEPRECATION") //Must use deprecated .assocations to support pre-SDK33
fun CompanionDeviceManager.isAssociatedWithCompat(
    bssid: String
) : Boolean {
    return if(Build.VERSION.SDK_INT >= 33) {
        val knownAdd = MacAddress.fromString(bssid)
        myAssociations.any { it.deviceMacAddress == knownAdd }
    }else {
        bssid in this.associations
    }
}