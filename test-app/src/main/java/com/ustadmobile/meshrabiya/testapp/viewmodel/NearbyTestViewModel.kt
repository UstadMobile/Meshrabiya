package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualNodeDatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.nearby.GoogleNearbyVirtualNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import java.net.DatagramPacket

class NearbyTestViewModel(application: Application) : AndroidViewModel(application) {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs


    private val nearbyNetwork: GoogleNearbyVirtualNetwork

    init {
        val mockVirtualNode = object : VirtualNode(0) {
            override fun route(packet: VirtualPacket, datagramPacket: DatagramPacket?, virtualNodeDatagramSocket: VirtualNodeDatagramSocket?) {
                log("MockVirtualNode: Routing packet ${packet.header}")
            }

            override val meshrabiyaWifiManager: MeshrabiyaWifiManager
                get() = TODO("Not yet implemented")

            // Implement other required methods from VirtualNode
        }

        nearbyNetwork = GoogleNearbyVirtualNetwork(
            context = application,
            virtualNode = mockVirtualNode,
            virtualAddress = InetAddress.getByName("192.168.0.1"),
            maxConnections = 3,
            serviceId = "com.ustadmobile.meshrabiya.test"
        )
    }

    fun startNearbyNetwork() {
        viewModelScope.launch {
            log("Starting Nearby network...")
            nearbyNetwork.startAdvertising()
            nearbyNetwork.startDiscovery()
        }
    }

    fun stopNearbyNetwork() {
        viewModelScope.launch {
            log("Stopping Nearby network...")
            nearbyNetwork.close()
        }
    }

    fun sendTestPacket() {
        viewModelScope.launch {
            log("Sending test packet...")
            val testPacket = createTestPacket()
            val nextHop = nearbyNetwork.getConnectedEndpoints().firstOrNull()
            if (nextHop != null) {
                nearbyNetwork.send(testPacket, nextHop)
                log("Test packet sent to $nextHop")
            } else {
                log("No connected endpoints to send test packet")
            }
        }
    }

    fun sendBroadcastMessage() {
        viewModelScope.launch {
            log("Sending broadcast message...")
            val broadcastPacket = createBroadcastPacket()
            nearbyNetwork.send(broadcastPacket, InetAddress.getByName("255.255.255.255"))
            log("Broadcast message sent")
        }
    }

    private fun createTestPacket(): VirtualPacket {
        val header = VirtualPacketHeader(
            toAddr = InetAddress.getByName("192.168.0.2").address.ip4AddressToInt(),
            fromAddr = nearbyNetwork.virtualAddress.address.ip4AddressToInt(),
            toPort = 0,
            fromPort = 0,
            lastHopAddr = nearbyNetwork.virtualAddress.address.ip4AddressToInt(), // Set last hop to self
            hopCount = 0, // Start with 0 hops
            maxHops = 10, // Set a reasonable max hops value
            payloadSize = "Test message".toByteArray().size // Use Int for payload size
        )
        return VirtualPacket.fromHeaderAndPayloadData(
            header = header,
            data = "Test message".toByteArray(),
            payloadOffset = VirtualPacketHeader.HEADER_SIZE
        )
    }

    private fun createBroadcastPacket(): VirtualPacket {
        val header = VirtualPacketHeader(
            toAddr = InetAddress.getByName("255.255.255.255").address.ip4AddressToInt(),
            fromAddr = nearbyNetwork.virtualAddress.address.ip4AddressToInt(),
            toPort = 0,
            fromPort = 0,
            lastHopAddr = nearbyNetwork.virtualAddress.address.ip4AddressToInt(), // Set last hop to self
            hopCount = 0, // Start with 0 hops
            maxHops = 10, // Set a reasonable max hops value
            payloadSize = "Broadcast message".toByteArray().size // Use Int for payload size
        )
        return VirtualPacket.fromHeaderAndPayloadData(
            header = header,
            data = "Broadcast message".toByteArray(),
            payloadOffset = VirtualPacketHeader.HEADER_SIZE
        )
    }

    private fun log(message: String) {
        _logs.value = _logs.value + message
    }
}