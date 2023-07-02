package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.wifi.p2p.WifiP2pManager

class WifiDirectError(private val errorCode: Int) {

    override fun toString(): String {
        return errorString(errorCode)
    }

    companion object {

        /**
         * Possible errors as per https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.ActionListener
         */
        fun errorString(reason: Int) : String {
           return when(reason) {
               WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
               WifiP2pManager.BUSY -> "BUSY"
               WifiP2pManager.ERROR -> "ERROR"
               else -> "Unknown reason: $reason"
           }
        }

    }

}