package com.meshrabiya.lib_nearby.nearby


import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

class NearbyVirtualNetwork(
    private val context: Context,
    private val name: String,
    private val serviceId: String,
    private val strategy: Strategy = Strategy.P2P_CLUSTER,
    private val logger: MNetLogger
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)

    data class EndpointInfo(
        val endpointId: String,
        var status: EndpointStatus = EndpointStatus.DISCONNECTED,
        var ipAddress: String? = null,
        var isOutgoing: Boolean = false
    )

    enum class EndpointStatus {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING
    }

    private val _endpointStatusFlow = MutableStateFlow<Map<String, EndpointInfo>>(emptyMap())
    val endpointStatusFlow = _endpointStatusFlow.asStateFlow()

    private val desiredOutgoingConnections = 3

    fun start() {
        startAdvertising()
        startDiscovery()
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(
            name,
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            log("Started advertising successfully")
        }.addOnFailureListener { e ->
            log("Failed to start advertising", e)
        }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            log("Started discovery successfully")
        }.addOnFailureListener { e ->
            log("Failed to start discovery", e)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            log("Connection initiated with endpoint: $endpointId")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            updateEndpointStatus(endpointId, EndpointStatus.CONNECTING)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                log("Successfully connected to endpoint: $endpointId")
                updateEndpointStatus(endpointId, EndpointStatus.CONNECTED)
                sendIpAddressPacket(endpointId)
            } else {
                log("Failed to connect to endpoint: $endpointId. Reason: ${result.status}")
                updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
            }
        }

        override fun onDisconnected(endpointId: String) {
            log("Disconnected from endpoint: $endpointId")
            updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
        }
    }

    private fun sendIpAddressPacket(endpointId: String) {
        val localIpAddress = getLocalIpAddress()
        log("Sending local IP address: $localIpAddress to endpoint: $endpointId")

        val ipExchangePacket = IpExchangePacket(localIpAddress)
        val payload = Payload.fromBytes(ipExchangePacket.toByteArray())

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener { log("Sent IP address to $endpointId") }
            .addOnFailureListener { e -> log("Failed to send IP address to $endpointId", e) }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            log("Endpoint found: $endpointId")
            addEndpoint(endpointId)
            checkAndInitiateConnection(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            log("Endpoint lost: $endpointId")
            removeEndpoint(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> handleBytesPayload(endpointId, payload)
                else -> log("Received unsupported payload type from: $endpointId")
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
        }
    }

    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        payload.asBytes()?.let { bytes ->
            if (bytes.isNotEmpty() && bytes[0].toInt() == IP_EXCHANGE_PACKET_TYPE) {
                val ipExchangePacket = IpExchangePacket.fromByteArray(bytes)
                if (ipExchangePacket != null) {
                    updateEndpointIpAddress(endpointId, ipExchangePacket.ipAddress)
                    log("Received IP address from $endpointId: ${ipExchangePacket.ipAddress}")
                } else {
                    log("Failed to parse IP exchange packet from $endpointId")
                }
            } else {
                log("Received non-IP exchange packet from $endpointId")
            }
        }
    }

    private fun getLocalIpAddress(): String {
        return "169.254.${Random.nextInt(1, 255)}.${Random.nextInt(1, 255)}"
    }

    private fun updateEndpointIpAddress(endpointId: String, ipAddress: String) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                this[endpointId]?.ipAddress = ipAddress
            }
        }
    }

    private fun updateEndpointStatus(endpointId: String, status: EndpointStatus) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                this[endpointId]?.status = status
            }
        }
    }

    private fun addEndpoint(endpointId: String) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                this[endpointId] = EndpointInfo(endpointId)
            }
        }
    }

    private fun removeEndpoint(endpointId: String) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                remove(endpointId)
            }
        }
    }

    private fun checkAndInitiateConnection(endpointId: String) {
        val connectedEndpoints = _endpointStatusFlow.value.values.count { it.status == EndpointStatus.CONNECTED && it.isOutgoing }
        if (connectedEndpoints < desiredOutgoingConnections) {
            requestConnection(endpointId)
        }
    }

    private fun requestConnection(endpointId: String) {
        connectionsClient.requestConnection(name, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                log("Connection request sent to endpoint: $endpointId")
                updateEndpointStatus(endpointId, EndpointStatus.CONNECTING)
                _endpointStatusFlow.update { currentMap ->
                    currentMap.toMutableMap().apply {
                        this[endpointId]?.isOutgoing = true
                    }
                }
            }
            .addOnFailureListener { e ->
                log("Failed to request connection to endpoint: $endpointId", e)
            }
    }

    fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _endpointStatusFlow.value = emptyMap()
        log("Stopped advertising, discovery, all connections, and cleared endpoints")
    }

    private fun log(message: String, exception: Exception? = null) {
        val prefix = "[NearbyVirtualNetwork:$name] "
        if (exception != null) {
            logger(Log.ERROR, "$prefix$message", exception)
        } else {
            logger(Log.DEBUG, "$prefix$message")
        }
    }
}

private const val IP_EXCHANGE_PACKET_TYPE = 1
data class IpExchangePacket(val ipAddress: String) {
    fun toByteArray(): ByteArray {
        return byteArrayOf(IP_EXCHANGE_PACKET_TYPE.toByte()) + ipAddress.toByteArray()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): IpExchangePacket? {
            if (bytes[0].toInt() != IP_EXCHANGE_PACKET_TYPE) return null
            val ipAddress = String(bytes.slice(1 until bytes.size).toByteArray())
            return IpExchangePacket(ipAddress)
        }
    }
}