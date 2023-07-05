package com.ustadmobile.meshrabiya.vnet.wifi

class WifiDirectException(
    message: String, reason: Int
): Exception(
    message + ": " + WifiDirectError(reason).toString()
)
