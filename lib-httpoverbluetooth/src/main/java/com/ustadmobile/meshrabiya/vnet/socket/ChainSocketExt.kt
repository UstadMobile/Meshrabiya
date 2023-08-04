package com.ustadmobile.meshrabiya.vnet.socket

import com.ustadmobile.meshrabiya.ext.readChainInitResponse
import com.ustadmobile.meshrabiya.ext.writeChainSocketInitRequest
import java.io.IOException
import java.net.Socket

/**
 * Where this socket is not connected to the intended final destination, then we need to write the
 * ChainInitRequest to the output
 */
fun Socket.initializeChainIfNotFinalDest(
    chainInitRequest: ChainSocketInitRequest,
    nextHop: ChainSocketNextHop,
) {
    if(!nextHop.isFinalDest) {
        println("${nextHop.address}:${nextHop.port} is not final destination - write init request and get response")
        getOutputStream().writeChainSocketInitRequest(chainInitRequest)
        val initResponse = getInputStream().readChainInitResponse()
        println("${nextHop.address}:${nextHop.port} got init response")

        if(initResponse.statusCode != 200){
            throw IOException("Could not init chain socket: status code = ${initResponse.statusCode}")
        }
    }
}
