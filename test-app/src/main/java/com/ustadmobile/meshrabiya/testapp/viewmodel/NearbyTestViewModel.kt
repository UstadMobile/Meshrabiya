package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress


class NearbyTestViewModel(application: Application) : AndroidViewModel(application) {
    private val _isNetworkRunning = MutableStateFlow(false)
    val isNetworkRunning: StateFlow<Boolean> = _isNetworkRunning

    private val _connectedEndpoints = MutableStateFlow<List<Pair<String, InetAddress>>>(emptyList())
    val connectedEndpoints: StateFlow<List<Pair<String, InetAddress>>> = _connectedEndpoints

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private  var nearbyNetwork:NearbyVirtualNetwork

    private val logger = object : MNetLogger() {
        override fun invoke(priority: Int, message: String, exception: Exception?) {
            _logs.value = _logs.value + message
        }

        override fun invoke(priority: Int, message: () -> String, exception: Exception?) {
            invoke(priority, message(), exception)
        }
    }

    init {
        nearbyNetwork = NearbyVirtualNetwork(
            context = application,
            virtualAddress = InetAddress.getByName("169.254.0.1"),
            serviceId = "com.ustadmobile.meshrabiya.test",
            logger = logger
        )
    }

    fun startNetwork() {
        nearbyNetwork.start()
        _isNetworkRunning.value = true
        updateConnectedEndpoints()
    }

    fun stopNetwork() {
        nearbyNetwork.stop()
        _isNetworkRunning.value = false
        _connectedEndpoints.value = emptyList()
        updateConnectedEndpoints()
    }

    fun sendTestPacket(endpointId: String) {
        val testData = "Test packet".toByteArray()
        nearbyNetwork.sendPacket(endpointId, testData)
    }

    fun updateConnectedEndpoints() {
        _connectedEndpoints.value = nearbyNetwork.endpointMap.entries.map { (endpointId, address) ->
            endpointId to address
        }
    }
}