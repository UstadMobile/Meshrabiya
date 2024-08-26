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
import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.netinterface.VSocket
import com.ustadmobile.meshrabiya.vnet.netinterface.VirtualNetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class GoogleNearbyVirtualNetwork(
    private val context: Context,
    private val virtualNode: VirtualNode,
    override val virtualAddress: InetAddress,
    private val maxConnections: Int = 3,
    private val serviceId: String = "com.ustadmobile.meshrabiya",
    private val strategy: Strategy = Strategy.P2P_STAR,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val executorService: ExecutorService = Executors.newCachedThreadPool(),
    private val onConnectionStatusChanged: (String, Boolean) -> Unit
) : VirtualNetworkInterface {

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val endpointMap = ConcurrentHashMap<String, InetAddress>()
    private val discoveredEndpoints = ConcurrentHashMap<String, DiscoveredEndpointInfo>()
    private val streamMap = ConcurrentHashMap<String, Pair<PipedInputStream, PipedOutputStream>>()
    private val isRunning = AtomicBoolean(false)
    private val ipAddressPool = IPAddressPool("192.168.0.0", 24)

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d("NearbyNetwork", "Connection initiated with: $endpointId (${connectionInfo.endpointName})")
            coroutineScope.launch {
                if (endpointMap.size < maxConnections) {
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                    Log.d("NearbyNetwork", "Accepted connection with: $endpointId")
                } else {
                    connectionsClient.rejectConnection(endpointId)
                    Log.d("NearbyNetwork", "Rejected connection with: $endpointId (max connections reached)")
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val dynamicIp = ipAddressPool.getNextAvailableIp()
                endpointMap[endpointId] = dynamicIp
                Log.d("NearbyNetwork", "Successfully connected to: $endpointId. Assigned IP: $dynamicIp")
                onConnectionStatusChanged(endpointId, true)
            } else {
                Log.e("NearbyNetwork", "Failed to connect to: $endpointId. Reason: ${result.status}")
                val ipToRelease = endpointMap.remove(endpointId)
                ipAddressPool.releaseIp(ipToRelease)
                onConnectionStatusChanged(endpointId, false)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d("NearbyNetwork", "Disconnected from: $endpointId")
            val ipToRelease = endpointMap.remove(endpointId)
            ipAddressPool.releaseIp(ipToRelease)
            streamMap.remove(endpointId)?.let { (input, output) ->
                input.close()
                output.close()
            }
            onConnectionStatusChanged(endpointId, false)
        }
    }

    private fun onConnectionStatusChanged(endpointId: String, b: Boolean) {

    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val receivedMessage = String(payload.asBytes()!!)
                    Log.d("NearbyNetwork", "Received message from $endpointId: $receivedMessage")
                    handleBytesPayload(endpointId, payload)
                }
                Payload.Type.STREAM -> {
                    Log.d("NearbyNetwork", "Received STREAM payload from: $endpointId")
                    handleStreamPayload(endpointId, payload)
                }
                else -> {
                    Log.d("NearbyNetwork", "Received unsupported payload type from: $endpointId")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            Log.d("NearbyNetwork", "Payload transfer update from $endpointId: ${update.status}")
        }
    }
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            startAdvertising()
            startDiscovery()
        }
    }
    private fun startAdvertising() {
        Log.d("NearbyNetwork", "Starting advertising")
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(
            virtualAddress.hostAddress,
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.d("NearbyNetwork", "Advertising started successfully")
        }.addOnFailureListener { e ->
            Log.e("NearbyNetwork", "Failed to start advertising: ${e.message}")
            isRunning.set(false)
        }
    }

    private fun startDiscovery() {
        Log.d("NearbyNetwork", "Starting discovery")
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    Log.d("NearbyNetwork", "Endpoint found: $endpointId")
                    discoveredEndpoints[endpointId] = info
                    if (endpointMap.size < maxConnections) {
                        requestConnection(endpointId)
                    }
                }

                override fun onEndpointLost(endpointId: String) {
                    Log.d("NearbyNetwork", "Endpoint lost: $endpointId")
                    discoveredEndpoints.remove(endpointId)
                }
            },
            discoveryOptions
        ).addOnSuccessListener {
            Log.d("NearbyNetwork", "Discovery started successfully")
        }.addOnFailureListener { e ->
            Log.e("NearbyNetwork", "Failed to start discovery: ${e.message}")
            isRunning.set(false)
        }
    }

    fun getDiscoveredEndpoints(): List<String> {
        return discoveredEndpoints.keys.toList()
    }

    fun requestConnection(endpointId: String) {
        if (endpointMap.size >= maxConnections) {
            Log.d("NearbyNetwork", "Max connections reached, not requesting new connection")
            return
        }

        Log.d("NearbyNetwork", "Requesting connection to endpoint: $endpointId")
        connectionsClient.requestConnection(
            virtualAddress.hostAddress,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            Log.d("NearbyNetwork", "Connection request sent successfully to: $endpointId")
        }.addOnFailureListener { e ->
            Log.e("NearbyNetwork", "Failed to request connection to: $endpointId. Error: ${e.message}")
        }
    }

    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        payload.asBytes()?.let { bytes ->
            try {
                val virtualPacket = VirtualPacket.fromData(bytes, 0)
                val lastHopAddress = InetAddress.getByAddress(virtualPacket.header.lastHopAddr.toByteArray())
                endpointMap[endpointId] = lastHopAddress
                routePacket(virtualPacket)
            } catch (e: Exception) {
                Log.e("NearbyNetwork", "Error handling bytes payload: ${e.message}")
                // Consider notifying the ViewModel about this error
            }
        }
    }

    private fun handleStreamPayload(endpointId: String, payload: Payload) {
        payload.asStream()?.asInputStream()?.let { inputStream ->
            val (existingPipedInput, existingPipedOutput) = streamMap.getOrPut(endpointId) {
                PipedInputStream().let { it to PipedOutputStream(it) }
            }
            executorService.submit {
                try {
                    inputStream.copyTo(existingPipedOutput)
                } catch (e: IOException) {
                    Log.e("NearbyNetwork", "Error copying stream: ${e.message}")
                } finally {
                    inputStream.close()
                }
            }
        }
    }

    private fun routePacket(packet: VirtualPacket) {
        val destAddress = InetAddress.getByAddress(packet.header.toAddr.toByteArray())

        when {
            destAddress == virtualAddress -> processLocalPacket(packet)
            packet.isBroadcast() -> {
                processLocalPacket(packet)
                forwardBroadcastPacket(packet)
            }
            endpointMap.containsValue(destAddress) -> forwardPacket(packet, destAddress)
            else -> {
                val nextHop = findNextHop(destAddress)
                if (nextHop != null) {
                    forwardPacket(packet, nextHop)
                } else {
                    Log.d("NearbyNetwork", "No route to destination: $destAddress")
                }
            }
        }
    }

    private fun processLocalPacket(packet: VirtualPacket) {
        virtualNode.route(packet)
    }

    private fun forwardBroadcastPacket(packet: VirtualPacket) {
        endpointMap.values.forEach { neighborAddress ->
            forwardPacket(packet, neighborAddress)
        }
    }

    private fun forwardPacket(packet: VirtualPacket, nextHopAddress: InetAddress) {
        packet.updateLastHopAddrAndIncrementHopCountInData(virtualAddress.address.ip4AddressToInt())
        send(packet, nextHopAddress)
    }
    fun sendTestMessage(endpointId: String) {
        val message = "Test message from ${virtualAddress.hostAddress}"
        val payload = Payload.fromBytes(message.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                Log.d("NearbyNetwork", "Test message sent successfully to: $endpointId")
            }
            .addOnFailureListener { e ->
                Log.e("NearbyNetwork", "Failed to send test message to $endpointId: ${e.message}")
            }
    }
    private fun findNextHop(destAddress: InetAddress): InetAddress? {
        return endpointMap.values.randomOrNull()
    }

    override fun send(virtualPacket: VirtualPacket, nextHopAddress: InetAddress) {
        val endpointId = endpointMap.entries.find { it.value == nextHopAddress }?.key ?: return
        val payload = Payload.fromBytes(virtualPacket.data.copyOfRange(virtualPacket.dataOffset, virtualPacket.dataOffset + virtualPacket.datagramPacketSize))
        connectionsClient.sendPayload(endpointId, payload)
            .addOnFailureListener { e -> Log.e("NearbyNetwork", "Failed to send payload: ${e.message}") }
    }

    override fun connectSocket(nextHopAddress: InetAddress, destAddress: InetAddress, destPort: Int): VSocket {
        val endpointId = endpointMap.entries.find { it.value == nextHopAddress }?.key
            ?: throw IOException("No route to host")
        val streamId = Random.nextLong().toString()

        val outputStream = PipedOutputStream()
        val inputForPayload = PipedInputStream(outputStream)

        val (inputStream, outputForPayload) = streamMap.getOrPut(endpointId) {
            PipedInputStream().let { it to PipedOutputStream(it) }
        }

        connectionsClient.sendPayload(endpointId, Payload.fromStream(inputForPayload))
            .addOnFailureListener { e -> Log.e("NearbyNetwork", "Failed to send stream payload: ${e.message}") }

        return object : VSocket {
            override fun inputStream(): InputStream = inputStream
            override fun outputStream(): OutputStream = outputStream
            override fun close() {
                inputStream.close()
                outputStream.close()
                streamMap.remove(endpointId)
            }
        }
    }
    override fun close() {
        if (isRunning.compareAndSet(true, false)) {
            coroutineScope.cancel()
            executorService.shutdown()
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            streamMap.values.forEach { (input, output) ->
                input.close()
                output.close()
            }
            streamMap.clear()
            endpointMap.clear()
            discoveredEndpoints.clear()
        }
    }

    fun getConnectedEndpoints(): List<InetAddress> {
        return endpointMap.values.toList()
    }

    private fun Int.toByteArray(): ByteArray {
        return byteArrayOf(
            (this shr 24 and 0xFF).toByte(),
            (this shr 16 and 0xFF).toByte(),
            (this shr 8 and 0xFF).toByte(),
            (this and 0xFF).toByte()
        )
    }
}

class IPAddressPool(baseIp: String, maskBits: Int) {
    private val network: InetAddress
    private val availableIps: MutableSet<InetAddress>

    init {
        network = InetAddress.getByName(baseIp)
        val numAddresses = 1 shl (32 - maskBits)
        availableIps = (1 until numAddresses).map {
            InetAddress.getByAddress(network.address.let { addr ->
                ByteBuffer.wrap(addr).putInt(ByteBuffer.wrap(addr).int + it).array()
            })
        }.toMutableSet()
    }

    @Synchronized
    fun getNextAvailableIp(): InetAddress {
        return availableIps.first().also { availableIps.remove(it) }
    }

    @Synchronized
    fun releaseIp(ip: InetAddress?) {
        ip?.let { availableIps.add(it) }
    }
}