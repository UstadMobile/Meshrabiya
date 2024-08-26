package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshrabiya.lib_nearby.nearby.GoogleNearbyVirtualNetwork
import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualNodeDatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress

class NearbyTestViewModel(application: Application) : AndroidViewModel(application) {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _discoveredEndpoints = MutableStateFlow<List<String>>(emptyList())
    val discoveredEndpoints: StateFlow<List<String>> = _discoveredEndpoints

    private val _connectedEndpoints = MutableStateFlow<List<InetAddress>>(emptyList())
    val connectedEndpoints: StateFlow<List<InetAddress>> = _connectedEndpoints

    private val _isNetworkRunning = MutableStateFlow(false)
    val isNetworkRunning: StateFlow<Boolean> = _isNetworkRunning

    private val _connectionStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val connectionStatus: StateFlow<Map<String, Boolean>> = _connectionStatus

    private val nearbyNetwork: GoogleNearbyVirtualNetwork


    init {
        val mockVirtualNode = object : VirtualNode(0) {
            override fun route(packet: VirtualPacket, datagramPacket: DatagramPacket?, virtualNodeDatagramSocket: VirtualNodeDatagramSocket?) {
                log("MockVirtualNode: Routing packet ${packet.header}")
            }

            override val meshrabiyaWifiManager: MeshrabiyaWifiManager
                get() = TODO("Not yet implemented")
        }

        nearbyNetwork = GoogleNearbyVirtualNetwork(
            context = application,
            virtualNode = mockVirtualNode,
            virtualAddress = InetAddress.getByName("192.168.0.1"),
            maxConnections = 3,
            serviceId = "com.ustadmobile.meshrabiya.test",
            onConnectionStatusChanged = { endpointId, isConnected ->
                viewModelScope.launch {
                    updateConnectionStatus(endpointId, isConnected)
                }
            }
        )
    }
    fun startNearbyNetwork() {
        viewModelScope.launch {
            try {
                log("Starting Nearby network...")
                nearbyNetwork.start()
                _isNetworkRunning.value = true
                log("Nearby network started successfully")
                updateDiscoveredEndpoints()
                updateConnectedEndpoints()
            } catch (e: Exception) {
                log("Failed to start Nearby network: ${e.message}")
                _isNetworkRunning.value = false
            }
        }
    }

    fun refreshDiscoveredEndpoints() {
        viewModelScope.launch {
            updateDiscoveredEndpoints()
        }
    }

    private fun updateDiscoveredEndpoints() {
        _discoveredEndpoints.value = nearbyNetwork.getDiscoveredEndpoints()
        log("Discovered endpoints updated: ${_discoveredEndpoints.value.joinToString()}")
    }

    private fun updateConnectedEndpoints() {
        _connectedEndpoints.value = nearbyNetwork.getConnectedEndpoints()
        log("Connected endpoints updated: ${_connectedEndpoints.value.joinToString { it.hostAddress }}")
    }

    fun stopNearbyNetwork() {
        viewModelScope.launch {
            log("Stopping Nearby network...")
            nearbyNetwork.close()
            _isNetworkRunning.value = false
            _discoveredEndpoints.value = emptyList()
            _connectedEndpoints.value = emptyList()
        }
    }
    private fun updateConnectionStatus(endpointId: String, isConnected: Boolean) {
        viewModelScope.launch {
            _connectionStatus.value = _connectionStatus.value.toMutableMap().apply {
                if (isConnected) {
                    put(endpointId, true)
                } else {
                    remove(endpointId)
                }
            }
            log("Connection status changed for $endpointId: ${if (isConnected) "Connected" else "Disconnected"}")
            updateConnectedEndpoints()
        }
    }
    fun connectToEndpoint(endpointId: String) {
        viewModelScope.launch {
            try {
                log("Requesting connection to endpoint: $endpointId")
                nearbyNetwork.requestConnection(endpointId)
            } catch (e: Exception) {
                log("Failed to request connection: ${e.message}")
            }
        }
    }


    fun sendTestMessage(endpointId: String) {
        viewModelScope.launch {
            try {
                nearbyNetwork.sendTestMessage(endpointId)
                log("Test message sent to $endpointId")
            } catch (e: Exception) {
                log("Failed to send test message to $endpointId: ${e.message}")
            }
        }
    }


    private fun createTestPacket(): VirtualPacket {
        val header = VirtualPacketHeader(
            toAddr = InetAddress.getByName("192.168.0.2").address.ip4AddressToInt(),
            fromAddr = nearbyNetwork.virtualAddress.address.ip4AddressToInt(),
            toPort = 0,
            fromPort = 0,
            lastHopAddr = nearbyNetwork.virtualAddress.address.ip4AddressToInt(),
            hopCount = 0,
            maxHops = 10,
            payloadSize = "Test message".toByteArray().size
        )
        return VirtualPacket.fromHeaderAndPayloadData(
            header = header,
            data = "Test message".toByteArray(),
            payloadOffset = VirtualPacketHeader.HEADER_SIZE
        )
    }



    private fun log(message: String) {
        _logs.value = _logs.value + message
    }
}