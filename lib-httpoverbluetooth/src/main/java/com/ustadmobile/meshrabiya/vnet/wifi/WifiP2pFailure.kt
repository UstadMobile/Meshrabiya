package com.ustadmobile.meshrabiya.vnet.wifi

import android.net.wifi.p2p.WifiP2pManager

enum class WifiP2pFailure(val reason: Int) {

    //Reasons as per
    // https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.ActionListener#onFailure(int)

    P2P_UNSUPPORTED(WifiP2pManager.P2P_UNSUPPORTED),
    ERROR(WifiP2pManager.ERROR),
    BUSY(WifiP2pManager.BUSY),
    OTHER(0);

    companion object {

        fun valueOf(reason: Int): WifiP2pFailure {
            return values().firstOrNull { it.reason == reason } ?: OTHER
        }

        fun reasonToString(reason: Int): String {
            return values().firstOrNull { it.reason == reason }?.name
                ?: "Unknown: reason=$reason"
        }

    }

}