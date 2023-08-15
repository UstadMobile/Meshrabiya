package com.ustadmobile.meshrabiya.vnet.datagram

import com.ustadmobile.meshrabiya.vnet.VirtualNode
import java.net.DatagramSocketImpl
import java.net.DatagramSocketImplFactory

class VirtualDatagramSocketImplFactory(
    private val node: VirtualNode,
): DatagramSocketImplFactory {

    override fun createDatagramSocketImpl(): DatagramSocketImpl {

        TODO("Not yet implemented")
    }

}