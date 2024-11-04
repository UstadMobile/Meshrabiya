package com.ustadmobile.meshrabiya.testapp.domain

import android.content.Context
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.testapp.viewmodel.NearbyTestViewModel.Companion.DEVICE_NAME_SUFFIX_LIMIT
import com.ustadmobile.meshrabiya.testapp.viewmodel.NearbyTestViewModel.Companion.NETWORK_SERVICE_ID
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import java.net.InetAddress
import kotlin.random.Random

class AddNearbyNetworkUseCase(
    private val virtualNode: VirtualNode,
    private val context: Context,
    private val logger: MNetLogger
) {

    operator fun invoke() {
        virtualNode.addVirtualNetworkInterface(
            NearbyVirtualNetwork(
                context = context,
                name = "Device-${Random.nextInt(DEVICE_NAME_SUFFIX_LIMIT)}",
                serviceId = NETWORK_SERVICE_ID,
                virtualIpAddress = virtualNode.addressAsInt,
                broadcastAddress = InetAddress.getByName("255.255.255.255").requireAddressAsInt(),
                logger = logger,
            ) { virtualPacket ->
                virtualNode.route(virtualPacket)
            }.also {
                it.start()
            }
        )
    }

}