package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.mmcp.MmcpPong

interface PongListener {

    fun onPongReceived(
        fromNode: Int,
        pong: MmcpPong,
    )

}