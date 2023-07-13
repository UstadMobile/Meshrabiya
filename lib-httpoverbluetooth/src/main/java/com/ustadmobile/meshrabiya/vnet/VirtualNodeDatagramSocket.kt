package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.mmcp.MmcpAck
import com.ustadmobile.meshrabiya.mmcp.MmcpHello
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 *
 * VirtualNodeDatagramSocket listens on the real network interface. It uses the executor service
 * to run a thread that will receive all packets, convert them from a DatagramPacket into a
 * VirtualPacket, and then give them to the VirtualRouter.
 *
 * @param socket - the underlying DatagramSocket to use - this can be bound to a network, interface etc if required
 * @param onMmcpHelloReceivedListener - Receives the Hello Event. This will be triggered when a new
 * neighbor connects.
 */
class VirtualNodeDatagramSocket(
    private val socket: DatagramSocket,
    private val localNodeVirtualAddress: Int,
    ioExecutorService: ExecutorService,
    private val router: VirtualRouter,
    private val onMmcpHelloReceivedListener: OnMmcpHelloReceivedListener,
    private val logger: MNetLogger,
    name: String? = null
):  Runnable, Closeable {

    private val future: Future<*>

    private val logPrefix: String

    val localPort: Int = socket.localPort

    data class LinkLocalMmcpReceivedEvent(
        val datagramPacket: DatagramPacket,
        val virtualPacket: VirtualPacket,
        val mmcpMessage: MmcpMessage,
    )

    fun interface LinkLocalMmcpListener {

        fun onLinkLocalMmcpReceived(event: LinkLocalMmcpReceivedEvent)

    }

    /**
     * Used to listen for new (e.g. incoming) connections being established over the datagram socket.
     * The OnMmcpHelloReceivedListener may be invoked multiple times from the same client (e.g. in
     * the event that a response is not received and the client retries).
     */
    fun interface OnMmcpHelloReceivedListener {

        fun onMmcpHelloReceived(
            helloEvent: HelloEvent
        )

    }


    private val listeners: MutableList<LinkLocalMmcpListener> = CopyOnWriteArrayList()

    init {
        logPrefix = buildString {
            append("[VirtualNodeDatagramSocket for ${localNodeVirtualAddress.addressToDotNotation()} ")
            if(name != null)
                append("- $name")
            append("] ")
        }
        future = ioExecutorService.submit(this)
    }

    override fun run() {
        val buffer = ByteArray(VirtualPacket.MAX_PAYLOAD_SIZE)
        logger(Log.DEBUG, "$logPrefix Started on ${socket.localPort} waiting for first packet", null)

        while(!Thread.interrupted()) {
            val rxPacket = DatagramPacket(buffer, 0, buffer.size)
            socket.receive(rxPacket)

            val rxVirtualPacket = VirtualPacket.fromDatagramPacket(rxPacket)

            //A virtual packet with toAddress = 0 means that this packet is only being sent
            // over between two neighbors (linkLocal). This can include initial Hello packet and
            // connection management e.g. pings
            if(rxVirtualPacket.header.toAddr == 0 && rxVirtualPacket.header.toPort == 0) {
                val mmcpMessage = MmcpMessage.fromVirtualPacket(rxVirtualPacket)

                listeners.forEach {
                    it.onLinkLocalMmcpReceived(LinkLocalMmcpReceivedEvent(
                        rxPacket, rxVirtualPacket, mmcpMessage
                    ))
                }

                logger(Log.DEBUG, "$logPrefix received MMCP packet from ${rxPacket.address}/${rxPacket.port} id=${mmcpMessage.messageId} type=${mmcpMessage::class.simpleName}", null)
                when(mmcpMessage) {
                    is MmcpHello -> {
                        logger(Log.DEBUG, "$logPrefix Received hello from ${rxPacket.address}/${rxPacket.port}", null)

                        val replyAck = MmcpAck(
                            messageId = router.nextMmcpMessageId(),
                            ackOfMessageId = mmcpMessage.messageId
                        )

                        val replyDatagram = replyAck.toVirtualPacket(
                            toAddr = 0,
                            fromAddr = localNodeVirtualAddress,
                        ).toDatagramPacket()

                        replyDatagram.address = rxPacket.address
                        replyDatagram.port = rxPacket.port
                        socket.send(replyDatagram)

                        onMmcpHelloReceivedListener.onMmcpHelloReceived(
                            HelloEvent(
                                address = rxPacket.address,
                                port = rxPacket.port,
                                virtualPacket = rxVirtualPacket,
                                mmcpHello = mmcpMessage,
                                socket = this,
                            ),
                        )
                    }

                    is MmcpPing -> {
                        val replyPongPacket = MmcpPong(
                            messageId = router.nextMmcpMessageId(),
                            replyToMessageId = mmcpMessage.messageId,
                        ).toVirtualPacket(
                            toAddr = 0,
                            fromAddr = localNodeVirtualAddress,
                        ).toDatagramPacket()
                        replyPongPacket.address = rxPacket.address
                        replyPongPacket.port = rxPacket.port

                        socket.send(replyPongPacket)
                    }

                    else -> {
                        //do nothing
                    }
                }

                if(mmcpMessage is MmcpAck) {
                    logger(Log.DEBUG, "Ack: messageId = ${mmcpMessage.messageId} from ${rxPacket.address}/${rxPacket.port}", null)
                }
            }else {
                router.route(
                    packet = rxVirtualPacket
                )
            }
        }
    }

    fun addLinkLocalMmmcpListener(listener: LinkLocalMmcpListener) {
        listeners += listener
    }

    fun removeLinkLocalMmcpListener(listener: LinkLocalMmcpListener) {
        listeners -= listener
    }


    fun sendHello(
        messageId: Int,
        nextHopAddress: InetAddress,
        nextHopPort: Int
    ) {
        send(
            nextHopPort = nextHopPort,
            nextHopAddress = nextHopAddress,
            virtualPacket = MmcpHello(messageId).toVirtualPacket(
                toAddr = 0,
                fromAddr = localNodeVirtualAddress,
            )
        )
    }

    /**
     *
     */
    fun send(
        nextHopAddress: InetAddress,
        nextHopPort: Int,
        virtualPacket: VirtualPacket
    ) {
        val datagramPacket = virtualPacket.toDatagramPacket()
        datagramPacket.address = nextHopAddress
        datagramPacket.port = nextHopPort
        socket.send(datagramPacket)
    }


    override fun close() {
        future.cancel(true)
        socket.close()
        listeners.clear()
    }
}