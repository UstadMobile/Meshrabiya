package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshrabiya.lib_nearby.nearby.GoogleNearbyVirtualNetwork
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualNodeDatagramSocket
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress


class NearbyTestViewModel(application: Application) : AndroidViewModel(application) {

    private val network: GoogleNearbyVirtualNetwork
    private val _isNetworkRunning = MutableStateFlow(false)
    val isNetworkRunning: StateFlow<Boolean> = _isNetworkRunning

    private val _connectedEndpoints = MutableStateFlow<List<Pair<String, InetAddress>>>(emptyList())
    val connectedEndpoints: StateFlow<List<Pair<String, InetAddress>>> = _connectedEndpoints

    private val _messages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val messages: StateFlow<List<Pair<String, String>>> = _messages

    init {
        val virtualNode = object : VirtualNode(0) {
            override val meshrabiyaWifiManager: MeshrabiyaWifiManager
                get() = TODO("Not yet implemented")

            override fun route(packet: VirtualPacket, datagramPacket: DatagramPacket?, virtualNodeDatagramSocket: VirtualNodeDatagramSocket?) {
                val message = String(packet.data, packet.dataOffset, packet.datagramPacketSize)
                viewModelScope.launch {
                    _messages.emit(_messages.value + (packet.header.fromAddr.toString() to message))
                }
            }
        }

        // Get the local InetAddress dynamically
        val localAddress =virtualNode.address
        Log.e("NearbyViewModel", "virtual: $localAddress")

        if (localAddress != null) {
            network = GoogleNearbyVirtualNetwork(
                context = application,
                virtualNode = virtualNode,
                virtualAddress = localAddress, // Ensure this method is correct
                maxConnections = 3,
                serviceId = "com.ustadmobile.meshrabiya.test"
            )
        } else {
            throw IllegalStateException("Unable to determine local InetAddress")
        }
    }



    fun startNetwork() {
        viewModelScope.launch {
            try {
                network.startAdvertising()
                network.startDiscovery()
                _isNetworkRunning.value = true
                updateConnectedEndpoints()
            } catch (e: Exception) {
                Log.e("NearbyViewModel", "Error starting network: ${e.message}")
            }
        }
    }

    fun stopNetwork() {
        viewModelScope.launch {
            try {
                network.close()
                _isNetworkRunning.value = false
                _connectedEndpoints.value = emptyList()
                _messages.value = emptyList()
            } catch (e: Exception) {
                Log.e("NearbyViewModel", "Error stopping network: ${e.message}")
            }
        }
    }

    fun sendMessage(message: String) {
        Log.e("NearbyViewModel", message)

        viewModelScope.launch {
            try {
                network.sendMessage(message)
                _messages.emit(_messages.value + ("Me" to message))
            } catch (e: Exception) {
                Log.e("NearbyViewModel", "Error sending message: ${e}")
            }
        }
    }


    private fun updateConnectedEndpoints() {
        viewModelScope.launch {
            while (isNetworkRunning.value) {
                _connectedEndpoints.value = network.getConnectedEndpoints()
                delay(5000) // Update every 5 seconds
            }
        }
    }
}
