@file:Suppress("DEPRECATION") //Must use deprecated classes to support pre-SDK29 devices

package com.ustadmobile.meshrabiya.ext

import android.annotation.SuppressLint
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger



@SuppressLint("MissingPermission") //Permissions will be set by the app, not the library
fun WifiManager.addOrLookupNetwork(
    config: WifiConfiguration,
    logger: MNetLogger,
): Int {
    val existingNetwork = configuredNetworks.firstOrNull {
        it.SSID == config.SSID && it.status != WifiConfiguration.Status.DISABLED
    }

    logger(Log.DEBUG, "addOrLookupNetwork: existingNetworkId=${existingNetwork?.networkId}", null)
    return existingNetwork?.networkId ?: addNetwork(config)
}