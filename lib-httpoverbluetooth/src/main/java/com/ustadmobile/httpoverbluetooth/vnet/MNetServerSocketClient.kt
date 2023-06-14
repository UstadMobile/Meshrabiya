package com.ustadmobile.httpoverbluetooth.vnet

abstract class MNetServerSocketClient(
    val vNode: MNode,
    val remoteAddr: String,
    val remotePort: Int
) {

    val state: Int = 0

    //inStream = PipedStream that is written to by VNode when matching packet arrives (e.g. from = remoteAddr /remotePort)

    //outstream inner class - write will split the data into packets, then call vNode.sendPacket (to remoteaddr/remoteport)

}