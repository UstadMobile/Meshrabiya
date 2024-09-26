package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.asInetAddress
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketNextHop
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


class OriginatingMessageManager(
    //TODO: change this to a function that provides a list of inetaddresses
    localNodeInetAddr: InetAddress,
    private val logger: MNetLogger,
    private val scheduledExecutorService: ScheduledExecutorService,
    private val nextMmcpMessageId: () -> Int,
    private val getWifiState: () -> MeshrabiyaWifiState,
    private val pingTimeout: Int = 15_000,
    private val originatingMessageNodeLostThreshold: Int = 10000,
    lostNodeCheckInterval: Int = 1_000,
) {

    //  A - B - C - E
    //   \ D



    private val logPrefix ="[OriginatingMessageManager for ${localNodeInetAddr}] "

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val localNodeAddress = localNodeInetAddr.requireAddressAsInt()

    /**
     * The currently known latest originator messages that can be used to route traffic.
     */
    private val originatorMessages: MutableMap<Int, VirtualNode.LastOriginatorMessage> = ConcurrentHashMap()

    private val _state = MutableStateFlow<Map<Int, VirtualNode.LastOriginatorMessage>>(emptyMap())

    val state: Flow<Map<Int, VirtualNode.LastOriginatorMessage>> = _state.asStateFlow()

    private val receivedMessages: Flow<VirtualNode.LastOriginatorMessage> = MutableSharedFlow(
        replay = 1 , extraBufferCapacity = 0, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    data class PendingPing(
        val ping: MmcpPing,
        val toVirtualAddr: Int,
        val timesent: Long
    )

    data class PingTime(
        val nodeVirtualAddr: Int,
        val pingTime: Short,
        val timeReceived: Long,
    )

    private val pendingPings = CopyOnWriteArrayList<PendingPing>()

    private val neighborPingTimes: MutableMap<Int, PingTime> = ConcurrentHashMap()

    private val sendOriginatingMessageRunnable = Runnable {
        val originatingMessage = makeOriginatingMessage()

        logger(
            priority = Log.VERBOSE,
            message = { "$logPrefix sending originating message " +
                    "messageId=${originatingMessage.messageId} sentTime=${originatingMessage.sentTime}"
            }
        )

        // Create the packet to be sent
        val packet = originatingMessage.toVirtualPacket(
            toAddr = ADDR_BROADCAST,
            fromAddr = localNodeAddress,
            lastHopAddr = localNodeAddress,
            hopCount = 1,
        )

        // Get known addresses
        val knownAddresses = getKnownInetAddresses()

        // Loop through each address and send the packet
        knownAddresses.forEach { inetAddress ->
            val neighbor = originatorMessages[inetAddress.requireAddressAsInt()]
            try {
                // Send the packet to each known neighbor
                neighbor?.receivedFromInterface?.send(
                    virtualPacket = packet,
                    nextHopAddress = inetAddress
                )
            } catch (e: Exception) {
                logger(Log.WARN, "$logPrefix : sendOriginatingMessagesRunnable: exception sending to " +
                        inetAddress.toString(), e)
            }
        }
    }

    private fun getKnownInetAddresses(): List<InetAddress> {
        // Obtain all network interfaces and filter for usable addresses
        return NetworkInterface.getNetworkInterfaces().toList().flatMap { ni ->
            ni.inetAddresses.toList().filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
        }
    }

    private val pingNeighborsRunnable = Runnable {
        val neighbors = neighbors()
        neighbors.forEach {
            val neighborVirtualAddr = it.first
            val lastOrigininatorMessage = it.second
            val pingMessage = MmcpPing(messageId = nextMmcpMessageId())
            pendingPings.add(PendingPing(pingMessage, neighborVirtualAddr, System.currentTimeMillis()))
            logger(
                priority = Log.VERBOSE,
                message = { "$logPrefix pingNeighborsRunnable: send ping to ${neighborVirtualAddr.addressToDotNotation()}" }
            )

            it.second.receivedFromSocket.send(
                nextHopAddress = lastOrigininatorMessage.lastHopRealInetAddr,
                nextHopPort = lastOrigininatorMessage.lastHopRealPort,
                virtualPacket = pingMessage.toVirtualPacket(
                    toAddr = neighborVirtualAddr,
                    fromAddr = localNodeAddress,
                    lastHopAddr = localNodeAddress,
                    hopCount = 1,
                )
            )
        }

        //Remove expired pings
        val pingTimeoutThreshold = System.currentTimeMillis() - pingTimeout
        pendingPings.removeIf { it.timesent < pingTimeoutThreshold }
    }

    private val checkLostNodesRunnable = Runnable {
        val timeNow = System.currentTimeMillis()
        val nodesLost = originatorMessages.entries.filter {
            (timeNow - it.value.timeReceived) > originatingMessageNodeLostThreshold
        }

        nodesLost.forEach {
            logger(Log.DEBUG, {"$logPrefix : checkLostNodesRunnable: " +
                    "Lost ${it.key.addressToDotNotation()} - no contact for ${timeNow - it.value.timeReceived}ms"})
            originatorMessages.remove(it.key)
        }

        _state.takeIf { !nodesLost.isEmpty() }?.value = originatorMessages.toMap()
    }

    private val sendOriginatorMessagesFuture = scheduledExecutorService.scheduleAtFixedRate(
        sendOriginatingMessageRunnable, 1000, 3000, TimeUnit.MILLISECONDS
    )

    private val pingNeighborsFuture = scheduledExecutorService.scheduleAtFixedRate(
        pingNeighborsRunnable, 1000, 10000, TimeUnit.MILLISECONDS
    )

    private val checkLostNodesFuture = scheduledExecutorService.scheduleAtFixedRate(
        checkLostNodesRunnable, lostNodeCheckInterval.toLong(), lostNodeCheckInterval.toLong(), TimeUnit.MILLISECONDS
    )

    @Volatile
    private var closed = false


    private fun makeOriginatingMessage(): MmcpOriginatorMessage {
        return MmcpOriginatorMessage(
            messageId = nextMmcpMessageId(),
            pingTimeSum = 0,
            connectConfig = getWifiState().connectConfig,
            sentTime = System.currentTimeMillis()
        )
    }


    private fun assertNotClosed() {
        if(closed)
            throw IllegalStateException("$logPrefix is closed!")
    }


    fun onReceiveOriginatingMessage(
        mmcpMessage: MmcpOriginatorMessage,
        datagramPacket: DatagramPacket,
        datagramSocket: VirtualNodeDatagramSocket,
        virtualPacket: VirtualPacket,
    ): Boolean {
        assertNotClosed()
        //Dont keep originator messages in our own table for this node
        logger(
            Log.VERBOSE,
            message= {
                "$logPrefix received originating message from " +
                        "${virtualPacket.header.fromAddr.addressToDotNotation()} via " +
                        virtualPacket.header.lastHopAddr.addressToDotNotation()
            }
        )


        val connectionPingTime = neighborPingTimes[virtualPacket.header.lastHopAddr]?.pingTime ?: 0
        MmcpOriginatorMessage.takeIf { connectionPingTime != 0.toShort() }
            ?.incrementPingTimeSum(virtualPacket, connectionPingTime)

        val currentOriginatorMessage = originatorMessages[virtualPacket.header.fromAddr]


        //Update this only if it is more recent and/or better. It might be that we are getting it back
        //via some other (suboptimal) route with more hops
        val currentlyKnownSentTime = (currentOriginatorMessage?.originatorMessage?.sentTime ?: 0)
        val currentlyKnownHopCount = (currentOriginatorMessage?.hopCount ?: Byte.MAX_VALUE)
        val receivedFromRealInetAddr = datagramPacket.address
        val receivedFromSocket = datagramSocket
        val isMoreRecentOrBetter = mmcpMessage.sentTime > currentlyKnownSentTime
                || mmcpMessage.sentTime == currentlyKnownSentTime && virtualPacket.header.hopCount < currentlyKnownHopCount
        val isNewNeighbor = virtualPacket.header.hopCount == 1.toByte() &&
                !originatorMessages.containsKey(virtualPacket.header.fromAddr)

        logger(
            Log.VERBOSE,
            message = {
                "$logPrefix received originating message from " +
                        "${virtualPacket.header.fromAddr.addressToDotNotation()} via ${virtualPacket.header.lastHopAddr.addressToDotNotation()}" +
                        " messageId=${mmcpMessage.messageId} " +
                        " hopCount=${virtualPacket.header.hopCount} sentTime=${mmcpMessage.sentTime} " +
                        " Currently known: senttime=$currentlyKnownSentTime  hop count = $currentlyKnownHopCount " +
                        "isMoreRecentOrBetter=$isMoreRecentOrBetter "
            }
        )

        if(currentOriginatorMessage == null || isMoreRecentOrBetter) {
            originatorMessages[virtualPacket.header.fromAddr] = VirtualNode.LastOriginatorMessage(
                originatorMessage = mmcpMessage.copyWithPingTimeIncrement(connectionPingTime),
                timeReceived = System.currentTimeMillis(),
                lastHopAddr = virtualPacket.header.lastHopAddr,
                hopCount = virtualPacket.header.hopCount,
                lastHopRealInetAddr = receivedFromRealInetAddr,
                receivedFromSocket = receivedFromSocket,
                lastHopRealPort = datagramPacket.port
            )
            logger(
                Log.VERBOSE,
                message = {
                    "$logPrefix update originator messages: " +
                            "currently known nodes = ${originatorMessages.keys.joinToString { it.addressToDotNotation() }}"
                }
            )

            _state.value = originatorMessages.toMap()
        }

        if(isNewNeighbor) {
            //trigger immediate sending of originator messages so it can see us
            scheduledExecutorService.submit(sendOriginatingMessageRunnable)
        }

        return isMoreRecentOrBetter
    }

    fun onPongReceived(
        fromVirtualAddr: Int,
        pong: MmcpPong,
    ) {
        val pendingPingPredicate : (PendingPing) -> Boolean = {
            it.ping.messageId == pong.replyToMessageId && it.toVirtualAddr == fromVirtualAddr
        }

        val pendingPing = pendingPings.firstOrNull(pendingPingPredicate)

        if(pendingPing == null){
            logger(Log.WARN, "$logPrefix : onPongReceived : pong from " +
                    "${fromVirtualAddr.addressToDotNotation()} does not match any known sent ping")
            return
        }

        val timeNow = System.currentTimeMillis()

        //Sometimes unit tests will run very quickly, and test may fail if ping time is 0
        val pingTime = maxOf((timeNow - pendingPing.timesent).toShort(), 1)
        logger(
            Log.VERBOSE, {"$logPrefix received ping from ${fromVirtualAddr.addressToDotNotation()} " +
                "pingTime=$pingTime"}
        )

        neighborPingTimes[fromVirtualAddr] = PingTime(
            nodeVirtualAddr = fromVirtualAddr,
            pingTime = pingTime,
            timeReceived = timeNow,
        )

        pendingPings.removeIf(pendingPingPredicate)
    }

    fun findOriginatingMessageFor(addr: Int): VirtualNode.LastOriginatorMessage? {
        return originatorMessages[addr]
    }


    fun lookupNextHopForChainSocket(address: InetAddress, port: Int): ChainSocketNextHop {
        val addressInt = address.requireAddressAsInt()

        val originatorMessage = originatorMessages[addressInt]

        return when {
            //Destination address is this node
            addressInt == localNodeAddress -> {
                ChainSocketNextHop(InetAddress.getLoopbackAddress(), port, true, null)
            }

            //Destination is a direct neighbor (final destination) - connect to the actual socket itself
            originatorMessage != null && originatorMessage.hopCount == 1.toByte() -> {
                ChainSocketNextHop(originatorMessage.lastHopRealInetAddr, port, true,
                        originatorMessage.receivedFromSocket.boundNetwork)
            }

            //Destination is not a direct neighbor, but we have a route there
            originatorMessage != null -> {
                ChainSocketNextHop(originatorMessage.lastHopRealInetAddr,
                    originatorMessage.lastHopRealPort, false,
                    originatorMessage.receivedFromSocket.boundNetwork)
            }

            //No route available to reach the given address
            else -> {
                logger(Log.ERROR, "$logPrefix : No route to virtual host: $address")
                throw NoRouteToHostException("No route to virtual host $address")
            }
        }
    }


    /**
     * Run the process to add a new neighbor (e.g. after a Wifi station connection is established).
     *
     * This will send originating messages to the neighbor node and wait until we receive an
     * originating message reply (up until a timeout)
     *
     * @param neighborRealInetAddr the InetAddress of the neighbor (e.g. real IP address)
     * @param neighborRealPort The port on which the neighbor is running VirtualNodeDatagramSocket
     * @param socket our VirtualNodeDatagramSocket through which we will attempt to communicate with
     *        the new neighbor - this is often the socket bound to a Network object after a new
     *        wifi connection is established
     * @param timeout the timeout (in ms) for the new connection to be established. If the timeout
     *        is exceeded an exception will be thrown
     * @param sendInterval the interval period for sending out originating messages to the new neighbor
     */
    suspend fun addNeighbor(
        neighborRealInetAddr: InetAddress,
        neighborRealPort: Int,
        socket: VirtualNodeDatagramSocket,
        timeout: Int = 15_000,
        sendInterval: Int = 1_000,
    ) {
        logger(Log.DEBUG, "$logPrefix: addNeighbor - sending originating messages out")

        //send originating packets out to the other device until we get something back from it
        val sendOriginatingMessageJob = scope.launch {
            try {
                val originatingMessage = makeOriginatingMessage()
                socket.send(
                    nextHopAddress = neighborRealInetAddr,
                    nextHopPort = neighborRealPort,
                    virtualPacket = originatingMessage.toVirtualPacket(
                        toAddr = ADDR_BROADCAST,
                        fromAddr = localNodeAddress,
                        lastHopAddr = localNodeAddress,
                        hopCount = 1,
                    )
                )
            }catch(e: Exception) {
                logger(Log.WARN, "$logPrefix : addNeighbor : exception trying to send originating message", e)
            }

            delay(sendInterval.toLong())
        }

        try {
            withTimeout(timeout.toLong()) {
                val replyMessage = receivedMessages.filter {
                    it.lastHopRealInetAddr == neighborRealInetAddr && it.lastHopRealPort == neighborRealPort
                }.first()
                logger(Log.DEBUG, "$logPrefix addNeighbor - received originating message reply " +
                        "from ${replyMessage.lastHopAddr.addressToDotNotation()}")
            }
        }finally {
            sendOriginatingMessageJob.cancel()
        }

    }

    fun neighbors() : List<Pair<Int, VirtualNode.LastOriginatorMessage>> {
        return originatorMessages.filter { it.value.hopCount == 1.toByte() }.map {
            it.key to it.value
        }
    }


    fun close(){
        sendOriginatorMessagesFuture.cancel(true)
        pingNeighborsFuture.cancel(true)
        checkLostNodesFuture.cancel(true)
        scope.cancel("$logPrefix closed")
        closed = true
    }

}