package com.ustadmobile.httpoverbluetooth.vnet

class MNetServerSocket(
    port: Int
) {

    suspend fun accept(port: Int): MNetServerSocketClient {
        //create new VServerSocket - put it in the list

        //Wait for an incoming connect
        //e.g.
        //_incomingPackets.filter { it.dest == thisNode && it.destPort == port && it.op == CONNECT }
        // SEND ACCEPT (portnum) to client
        // return VServerSocketClient

        TODO()
    }


    fun close() {

    }


}