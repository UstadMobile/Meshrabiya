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
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
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
import java.nio.BufferUnderflowException
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
    private val executorService: ExecutorService = Executors.newCachedThreadPool()
) : VirtualNetworkInterface {

    private val connectionsClient = Nearby.getConnectionsClient(context)
    val endpointMap = ConcurrentHashMap<String, InetAddress>()
    private val streamMap = ConcurrentHashMap<String, Pair<PipedInputStream, PipedOutputStream>>()
    private val isRunning = AtomicBoolean(false)
    private val ipAddressPool = IPAddressPool("192.168.0.0", 24)

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            coroutineScope.launch {
                if (endpointMap.size < maxConnections) {
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                        .addOnFailureListener { e -> handleError("Failed to accept connection", e) }
                } else {
                    connectionsClient.rejectConnection(endpointId)
                        .addOnFailureListener { e -> handleError("Failed to reject connection", e) }
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val dynamicIp = ipAddressPool.getNextAvailableIp(endpointId)
                endpointMap[endpointId] = dynamicIp
                Log.d("NearbyNetwork", "Connected to $endpointId with IP: $dynamicIp")
                if (endpointMap.size < maxConnections) {
                    startDiscovery() // Continue discovery if not at max connections
                }
            } else {
                handleError("Connection failed", Exception(result.status.statusMessage))
            }
        }

        override fun onDisconnected(endpointId: String) {
            endpointMap.remove(endpointId)
            ipAddressPool.releaseIp(endpointId)
            streamMap.remove(endpointId)?.let { (input, output) ->
                input.close()
                output.close()
            }
            if (isRunning.get() && endpointMap.size < maxConnections) {
                startDiscovery() // Restart discovery if below max connections
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> handleBytesPayload(endpointId, payload)
                Payload.Type.STREAM -> handleStreamPayload(endpointId, payload)
                else -> handleError(
                    "Unsupported payload type",
                    Exception("Received unsupported payload type")
                )
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Handle transfer updates if needed
        }
    }

    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        payload.asBytes()?.let { bytes ->
            try {
                if (bytes.size < VirtualPacketHeader.HEADER_SIZE) {
                    handleError("Received payload is too small to contain a valid VirtualPacket", Exception("Payload size: ${bytes.size}"))
                    return
                }

                // Create VirtualPacket from the received bytes
                val virtualPacket = VirtualPacket.fromData(bytes, 0)

                // Process the packet
                routePacket(virtualPacket)
            } catch (e: BufferUnderflowException) {
                handleError("Buffer underflow while handling byte payload from endpoint $endpointId", e)
            } catch (e: Exception) {
                handleError("Error processing byte payload from endpoint $endpointId", e)
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
                    handleError("Error handling stream payload", e)
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
                val nextHop = findNextHop(destAddress, packet.header.fromAddr.toByteArray())
                if (nextHop != null) {
                    forwardPacket(packet, nextHop)
                } else {
                    handleError("No route to destination", Exception("Unable to route packet to $destAddress"))
                }
            }
        }
    }



    private fun findNextHop(destAddress: InetAddress, sourceAddress: ByteArray): InetAddress? {
        // Improved routing logic
        val sourceIp = InetAddress.getByAddress(sourceAddress)
        return endpointMap.values
            .filter { it != sourceIp }
            .minByOrNull { it.address.fold(0) { acc, byte -> acc + (byte.toInt() and 0xFF) xor (destAddress.address[acc % 4].toInt() and 0xFF) } }
    }

    fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(
            virtualAddress.hostAddress,
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnFailureListener { e -> handleError("Failed to start advertising", e) }
    }

    fun startDiscovery() {
        if (endpointMap.size >= maxConnections) return

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    if (endpointMap.size < maxConnections) {
                        connectionsClient.requestConnection(
                            virtualAddress.hostAddress,
                            endpointId,
                            connectionLifecycleCallback
                        ).addOnFailureListener { e ->
                            handleError(
                                "Failed to request connection",
                                e
                            )
                        }
                    }
                }

                override fun onEndpointLost(endpointId: String) {
                    // Handle lost endpoint
                }
            },
            discoveryOptions
        ).addOnFailureListener { e -> handleError("Failed to start discovery", e) }
    }

    override fun connectSocket(
        nextHopAddress: InetAddress,
        destAddress: InetAddress,
        destPort: Int
    ): VSocket {
        // Stream setup code
        val endpointId = endpointMap.entries.find { it.value == nextHopAddress }?.key
            ?: throw IOException("No route to host")
        val streamId = Random.nextLong().toString()

        val outputStream = PipedOutputStream()
        val inputForPayload = PipedInputStream(outputStream)

        val inputStream = PipedInputStream()
        val outputForPayload = PipedOutputStream(inputStream)

        streamMap[streamId] = inputStream to outputStream

        val initMessage = byteArrayOf(1) + "STREAM_INIT:$streamId:${destAddress.hostAddress}:$destPort".toByteArray()
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(initMessage))
            .addOnSuccessListener {
                connectionsClient.sendPayload(endpointId, Payload.fromStream(inputForPayload))
            }
            .addOnFailureListener { e ->
                streamMap.remove(streamId)
                throw IOException("Failed to initialize stream: ${e.message}")
            }

        return object : VSocket {
            override fun inputStream(): InputStream = inputStream
            override fun outputStream(): OutputStream = outputStream
            override fun close() {
                try {
                    inputStream.close()
                    outputStream.close()
                } finally {
                    streamMap.remove(streamId)
                    connectionsClient.sendPayload(
                        endpointId,
                        Payload.fromBytes("STREAM_CLOSE:$streamId".toByteArray())
                    )
                }
            }
        }
    }


    private fun handleError(message: String, error: Exception) {
        Log.e("NearbyNetwork", "$message: ${error.message}")
        // Implement additional error handling logic here
    }

    override fun send(virtualPacket: VirtualPacket, nextHopAddress: InetAddress) {
        val endpointId = endpointMap.entries.find { it.value == nextHopAddress }?.key ?: run {
            handleError("No route to host", IOException("No endpoint found for address $nextHopAddress"))
            return
        }

        val payload = Payload.fromBytes(
            virtualPacket.data.copyOfRange(virtualPacket.dataOffset, virtualPacket.dataOffset + virtualPacket.datagramPacketSize)
        )

        connectionsClient.sendPayload(endpointId, payload)
            .addOnFailureListener { e -> handleError("Failed to send payload", e) }
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

    override fun close() {
        if (isRunning.compareAndSet(true, false)) {
            coroutineScope.cancel()
            executorService.shutdown()
            connectionsClient.stopAllEndpoints()
            streamMap.values.forEach { (input, output) ->
                input.close()
                output.close()
            }
            streamMap.clear()
            endpointMap.clear()
        }
    }

    fun sendMessage(message: String) {
        val maxPayloadSize = VirtualPacket.MAX_PAYLOAD_SIZE // Use the constant defined in VirtualPacket
        val messageBytes = message.toByteArray()
        val numChunks = (messageBytes.size + maxPayloadSize - 1) / maxPayloadSize

        // Loop to send each chunk
        for (i in 0 until numChunks) {
            val start = i * maxPayloadSize
            val end = minOf(start + maxPayloadSize, messageBytes.size)
            val chunk = messageBytes.copyOfRange(start, end)

            val packet = VirtualPacket.fromData(chunk, 0)
            sendPacketToAllEndpoints(packet)
        }
    }

    private fun sendPacketToAllEndpoints(packet: VirtualPacket) {
        endpointMap.entries.forEach { (endpointId, address) ->
            try {
                send(packet, address)
                Log.d("GoogleNearbyVirtualNetwork", "Message chunk sent to $endpointId with IP: $address")
            } catch (e: Exception) {
                Log.e("GoogleNearbyVirtualNetwork", "Error sending message chunk to $endpointId: ${e.message}", e)
            }
        }
    }

    fun isRunning(): Boolean = isRunning.get()

    fun getConnectedEndpoints(): List<Pair<String, InetAddress>> {
        return endpointMap.entries.map { (endpointId, address) -> endpointId to address }
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
    private val usedIps = ConcurrentHashMap<String, InetAddress>()
    private var lastAssignedIp: Int

    init {
        network = InetAddress.getByName(baseIp)
        lastAssignedIp = ByteBuffer.wrap(network.address).int
    }

    @Synchronized
    fun getNextAvailableIp(deviceId: String): InetAddress {
        return usedIps.getOrPut(deviceId) {
            lastAssignedIp++
            InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(lastAssignedIp).array())
        }
    }

    @Synchronized
    fun releaseIp(deviceId: String) {
        usedIps.remove(deviceId)
    }
}
