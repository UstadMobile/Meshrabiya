package com.ustadmobile.meshrabiya.vnet.nearby

import android.content.Context
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
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
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
    private val endpointMap = ConcurrentHashMap<InetAddress, String>()
    private val streamMap = ConcurrentHashMap<String, Pair<PipedInputStream, PipedOutputStream>>()
    private val isRunning = AtomicBoolean(false)

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            coroutineScope.launch {
                if (endpointMap.size < maxConnections) {
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                } else {
                    connectionsClient.rejectConnection(endpointId)
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                // Connection successful, update mapping if needed
            } else {
                // Handle connection failure
                endpointMap.entries.removeIf { it.value == endpointId }
            }
        }

        override fun onDisconnected(endpointId: String) {
            endpointMap.entries.removeIf { it.value == endpointId }
            streamMap.remove(endpointId)?.let { (input, output) ->
                input.close()
                output.close()
            }
            if (isRunning.get()) {
                startDiscovery() // Try to maintain the desired number of connections
            }
        }
    }

    fun getConnectedEndpoints(): List<InetAddress> {
        return endpointMap.keys.toList()
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> handleBytesPayload(endpointId, payload)
                Payload.Type.STREAM -> handleStreamPayload(endpointId, payload)
                else -> { /* Handle other payload types if needed */ }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Handle transfer updates if needed
        }
    }

    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        payload.asBytes()?.let { bytes ->
            try {
                val virtualPacket = VirtualPacket.fromData(bytes, 0)
                // Convert Int to InetAddress
                val lastHopAddress = InetAddress.getByAddress(virtualPacket.header.lastHopAddr.toByteArray())
                endpointMap[lastHopAddress] = endpointId
                routePacket(virtualPacket)
            } catch (e: Exception) {
                // Log error and possibly disconnect problematic endpoint
            }
        }
    }

    private fun routePacket(packet: VirtualPacket) {
        val destAddress = InetAddress.getByAddress(packet.header.toAddr.toByteArray())

        when {
            destAddress == virtualAddress -> {
                // Packet is for this node, process it locally
                processLocalPacket(packet)
            }
            packet.isBroadcast() -> {
                // Broadcast packet, process locally and forward to all neighbors
                processLocalPacket(packet)
                forwardBroadcastPacket(packet)
            }
            endpointMap.containsKey(destAddress) -> {
                // Destination is a direct neighbor, forward directly
                forwardPacket(packet, destAddress)
            }
            else -> {
                // Destination is not a direct neighbor, use routing table to find next hop
                val nextHop = findNextHop(destAddress)
                if (nextHop != null) {
                    forwardPacket(packet, nextHop)
                } else {
                    // No route to destination, drop the packet
                    // Optionally, you could send an ICMP "Destination Unreachable" message back to the source
                }
            }
        }
    }

    private fun processLocalPacket(packet: VirtualPacket) {
        // Process the packet locally
        // This might involve passing it to a higher layer protocol handler
        virtualNode.route(packet)
    }

    private fun forwardBroadcastPacket(packet: VirtualPacket) {
        endpointMap.keys.forEach { neighborAddress ->
            forwardPacket(packet, neighborAddress)
        }
    }


    private fun forwardPacket(packet: VirtualPacket, nextHopAddress: InetAddress) {
        // Update the last hop address in the packet header
        // Convert InetAddress to Int before passing it
        packet.updateLastHopAddrAndIncrementHopCountInData(virtualAddress.address.ip4AddressToInt())
        // Send the packet to the next hop
        send(packet, nextHopAddress)
    }


    private fun findNextHop(destAddress: InetAddress): InetAddress? {
        // This is a simplified routing table lookup
        // In a real implementation, you would maintain a routing table and use it here
        // For now, we'll just return a random neighbor as the next hop
        return endpointMap.keys.randomOrNull()
    }


    private fun Int.toByteArray(): ByteArray {
        return byteArrayOf(
            (this shr 24 and 0xFF).toByte(),
            (this shr 16 and 0xFF).toByte(),
            (this shr 8 and 0xFF).toByte(),
            (this and 0xFF).toByte()
        )
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
                    // Handle stream copy failure
                } finally {
                    inputStream.close()
                }
            }
        }
    }

     fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(
            virtualAddress.hostAddress,
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnFailureListener { e ->
            // Handle advertising start failure
            isRunning.set(false)
        }
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
                        ).addOnFailureListener { /* Handle connection request failure */ }
                    }
                }

                override fun onEndpointLost(endpointId: String) {
                    // Handle lost endpoint
                }
            },
            discoveryOptions
        ).addOnFailureListener { e ->
            // Handle discovery start failure
            isRunning.set(false)
        }
    }

    override fun send(virtualPacket: VirtualPacket, nextHopAddress: InetAddress) {
        val endpointId = endpointMap[nextHopAddress] ?: return
        val payload = Payload.fromBytes(virtualPacket.data.copyOfRange(virtualPacket.dataOffset, virtualPacket.dataOffset + virtualPacket.datagramPacketSize))
        connectionsClient.sendPayload(endpointId, payload)
            .addOnFailureListener { /* Handle send failure */ }
    }

    override fun connectSocket(nextHopAddress: InetAddress, destAddress: InetAddress, destPort: Int): VSocket {
        val endpointId = endpointMap[nextHopAddress] ?: throw IOException("No route to host")
        val streamId = Random.nextLong().toString()

        val outputStream = PipedOutputStream()
        val inputForPayload = PipedInputStream(outputStream)

        val (inputStream, outputForPayload) = streamMap.getOrPut(endpointId) {
            PipedInputStream().let { it to PipedOutputStream(it) }
        }

        connectionsClient.sendPayload(endpointId, Payload.fromStream(inputForPayload))
            .addOnFailureListener { /* Handle send failure */ }

        return object : VSocket {
            override fun inputStream() {
                // Return the InputStream
                inputStream
            }

            override fun outputStream() {
                // Return the OutputStream
                outputStream
            }

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
            connectionsClient.stopAllEndpoints()
            streamMap.values.forEach { (input, output) ->
                input.close()
                output.close()
            }
            streamMap.clear()
            endpointMap.clear()
        }
    }
}

