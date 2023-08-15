package com.ustadmobile.meshrabiya.vnet.wifi

class WifiDirectException(
    message: String,
    val wifiDirectFailReason: Int
): Exception(
    message + ": " + WifiDirectError(wifiDirectFailReason).toString()
)
