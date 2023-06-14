package com.ustadmobile.httpoverbluetooth.vnet

class MNetClientSocket {

    var remoteAddr: String = ""

    val remotePort: Int = 0

    val localPort: Int = 0

    //instream - reads anything where this is the destination node and it matches the local port

    //outstream - use the VNode to send packets

}