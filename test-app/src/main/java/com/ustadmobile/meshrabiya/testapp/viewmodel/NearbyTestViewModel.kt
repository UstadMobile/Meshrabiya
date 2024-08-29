package com.ustadmobile.meshrabiya.testapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random


class NearbyTestViewModel(application: Application) : AndroidViewModel(application) {
    private val _isNetworkRunning = MutableStateFlow(false)
    val isNetworkRunning: StateFlow<Boolean> = _isNetworkRunning

    private val _endpoints = MutableStateFlow<List<NearbyVirtualNetwork.EndpointInfo>>(emptyList())
    val endpoints: StateFlow<List<NearbyVirtualNetwork.EndpointInfo>> = _endpoints

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private lateinit var nearbyNetwork: NearbyVirtualNetwork

    private val logger = object : MNetLogger() {
        override fun invoke(priority: Int, message: String, exception: Exception?) {
            val logMessage = "${MNetLogger.priorityLabel(priority)}: $message"
            _logs.value = _logs.value + logMessage
        }

        override fun invoke(priority: Int, message: () -> String, exception: Exception?) {
            invoke(priority, message(), exception)
        }
    }

    init {
        initializeNearbyNetwork()
    }

    private fun initializeNearbyNetwork() {
        val virtualIpAddress = "169.254.${Random.nextInt(1, 255)}.${Random.nextInt(1, 255)}"
        val broadcastAddress = "169.254.255.255"

        nearbyNetwork = NearbyVirtualNetwork(
            context = getApplication(),
            name = "Device-${Random.nextInt(1000)}",
            serviceId = "com.ustadmobile.meshrabiya.test",
            virtualIpAddress = virtualIpAddress,
            broadcastAddress = broadcastAddress,
            logger = logger
        )
    }

    fun startNetwork() {
        nearbyNetwork.start()
        _isNetworkRunning.value = true
        observeEndpoints()
    }

    fun stopNetwork() {
        nearbyNetwork.stop()
        _isNetworkRunning.value = false
    }

    private fun observeEndpoints() {
        viewModelScope.launch {
            nearbyNetwork.endpointStatusFlow.collect { endpointMap ->
                _endpoints.value = endpointMap.values.toList()
            }
        }
    }
}