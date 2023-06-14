package com.ustadmobile.httpoverbluetooth.vnet

class MNetPacket(
    val fromAddr: String,
    val fromPort: Int,
    //represents what is happening - e.g. CONNECT, ACCEPT, ACK, XFER
    val op: Int,
    val toAddr: String,
    val toPort: Int,
    val nextHopAddr: String,
    val hopCount: Int,
    val maxHops: Int,
    val payload: ByteArray
) {
}