package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.mmcp.MmcpAck
import com.ustadmobile.meshrabiya.mmcp.MmcpHello
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
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
 * @param onMmcpHelloReceivedListener - Receives the Hello Event. This will be triggered when a new
 * neighbor connects.
 */
class VirtualNodeDatagramSocket(
    port: Int,
    private val localNodeVirtualAddress: Int,
    ioExecutorService: ExecutorService,
    private val router: VirtualRouter,
    private val onMmcpHelloReceivedListener: OnMmcpHelloReceivedListener,
    private val logger: MNetLogger,
    name: String? = null
) : DatagramSocket(port), Runnable {

    private val future: Future<*>

    private val logPrefix: String

    fun interface PacketReceivedListener {

        fun onPacketReceived(packet: DatagramPacket)

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


    private val listeners: MutableList<PacketReceivedListener> = CopyOnWriteArrayList()

    init {
        logPrefix = buildString {
            append("[VirtualNodeSocket for ${localNodeVirtualAddress.addressToDotNotation()} ")
            if(name != null)
                append("- $name")
            append("] ")
        }
        future = ioExecutorService.submit(this)
    }

    override fun run() {
        val buffer = ByteArray(VirtualPacket.MAX_PAYLOAD_SIZE)
        logger(Log.DEBUG, "$logPrefix Started on $localPort waiting for first packet", null)

        while(!Thread.interrupted()) {
            val rxPacket = DatagramPacket(buffer, 0, buffer.size)
            receive(rxPacket)

            listeners.forEach {
                it.onPacketReceived(rxPacket)
            }

            val rxVirtualPacket = VirtualPacket.fromDatagramPacket(rxPacket)

            //Respond to Hello from new nodes with a packet so they can get our virtual address
            if(rxVirtualPacket.header.toAddr == 0 && rxVirtualPacket.header.toPort == 0) {
                val mmcpPacket = MmcpMessage.fromVirtualPacket(rxVirtualPacket)
                logger(Log.DEBUG, "$logPrefix received MMCP packet from ${rxPacket.address}/${rxPacket.port} id=${mmcpPacket.messageId} type=${mmcpPacket::class.simpleName}", null)
                if(mmcpPacket is MmcpHello) {
                    logger(Log.DEBUG, "$logPrefix Received hello from ${rxPacket.address}/${rxPacket.port}", null)

                    val replyAck = MmcpAck(
                        messageId = router.nextMmcpMessageId(),
                        ackOfMessageId = mmcpPacket.messageId
                    )
                    val replyDatagram = replyAck.toVirtualPacket(
                        toAddr = 0,
                        fromAddr = localNodeVirtualAddress,
                    ).toDatagramPacket()
                    replyDatagram.address = rxPacket.address
                    replyDatagram.port = rxPacket.port
                    send(replyDatagram)

                    onMmcpHelloReceivedListener.onMmcpHelloReceived(
                        HelloEvent(
                            address = rxPacket.address,
                            port = rxPacket.port,
                            virtualPacket = rxVirtualPacket,
                            mmcpHello = mmcpPacket,
                        ),
                    )
                }

                if(mmcpPacket is MmcpAck) {
                    logger(Log.DEBUG, "Ack: messageId = ${mmcpPacket.messageId} from ${rxPacket.address}/${rxPacket.port}", null)
                }
            }else {
                router.route(
                    packet = rxVirtualPacket
                )
            }
        }
    }

    fun addPacketReceivedListener(listener: PacketReceivedListener) {
        listeners += listener
    }

    fun removePacketReceivedListener(listener: PacketReceivedListener) {
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
        send(datagramPacket)
    }


    override fun close() {
        future.cancel(true)
        super.close()
        listeners.clear()
    }
}