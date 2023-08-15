package com.ustadmobile.meshrabiya.vnet.datagram

import android.util.Log
import androidx.core.util.Pools.SynchronizedPool
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.Protocol
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import java.net.DatagramPacket
import java.net.DatagramSocketImpl
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VirtualDatagramSocketImpl can be passed to a DatagramSocket as a constructor parameter
 * (the DatagramSocketImpl). This allows any DatagramSocket class to be used to pass data over
 * the virtual network.
 */
open class VirtualDatagramSocketImpl(
    private val router: VirtualRouter,
    private val localVirtualAddress: Int,
    private val logger: MNetLogger,
): DatagramSocketImpl() {
    private val logPrefix: String
        get() = "[VirtualDatagramSocketImpl] "

    private val closed = AtomicBoolean(false)

    private val receiveQueue = LinkedBlockingDeque<DatagramPacket>()

    private val receiveBufferPool = SynchronizedPool<ByteArray>(RECEIVE_BUFFER_SIZE)

    private val sendBufferPool = SynchronizedPool<ByteArray>(SEND_BUFFER_SIZE)

    val boundPort: Int
        get() = localPort

    private fun assertNotClosed() {
        if(closed.get())
            throw IllegalStateException("VirtualDatagramSocket assertNotClosed fail: $localPort is closed!")
    }

    /**
     * This function is called by the VirtualRouter when a packet is routed and this socket is
     * the destination.
     */
    internal fun onIncomingPacket(virtualPacket: VirtualPacket) {
        if(closed.get())
            return // do nothing

        logger(Log.VERBOSE,
            message = {"$logPrefix incoming virtual packet=${virtualPacket.datagramPacketSize} bytes " +
                    "from ${virtualPacket.header.fromAddr.addressToDotNotation()}:" +
                    "${virtualPacket.header.fromPort} "}
        )

        val buffer = receiveBufferPool.acquire() ?: ByteArray(VirtualPacket.VIRTUAL_PACKET_BUF_SIZE)

        //Copy from the virtual packet into the pool buffer
        System.arraycopy(virtualPacket.data, virtualPacket.payloadOffset,
            buffer, VirtualPacketHeader.HEADER_SIZE, virtualPacket.header.payloadSize)

        val datagramPacket = DatagramPacket(buffer, VirtualPacketHeader.HEADER_SIZE,
            virtualPacket.header.payloadSize)
        datagramPacket.address = InetAddress.getByAddress(
            virtualPacket.header.fromAddr.addressToByteArray())
        datagramPacket.port = virtualPacket.header.fromPort
        datagramPacket.length = virtualPacket.header.payloadSize
        receiveQueue.put(datagramPacket)
    }

    override fun setOption(optID: Int, value: Any?) {

    }

    override fun getOption(optId: Int): Any? {
        return null
    }

    override fun create() {

    }

    public override fun bind(lport: Int, laddr: InetAddress) {
        logger(Log.VERBOSE, { "$logPrefix bind laddr=$laddr lport=$lport"})
        localPort = router.allocateUdpPortOrThrow(this, lport)
    }

    public override fun send(p: DatagramPacket) {
        assertNotClosed()
        logger(Log.VERBOSE,
            message = {"$logPrefix send packet size=${p.length} bytes to ${p.address}:${p.port}"}
        )

        //need to borrow a buffer
        //convert to virtual packet, then send using router.
        val buffer = sendBufferPool.acquire() ?: ByteArray(VirtualPacket.VIRTUAL_PACKET_BUF_SIZE)
        try {
            System.arraycopy(p.data, p.offset,
                buffer, VirtualPacketHeader.HEADER_SIZE, p.length)

            val virtualPacket = VirtualPacket.fromHeaderAndPayloadData(
                header = VirtualPacketHeader(
                    toAddr = p.address.requireAddressAsInt(),
                    toPort = p.port,
                    fromAddr = localVirtualAddress,
                    fromPort = localPort,
                    lastHopAddr = 0,
                    hopCount =  0,
                    maxHops = 5,
                    payloadSize = p.length
                ),
                data = buffer,
                payloadOffset = VirtualPacketHeader.HEADER_SIZE,
            )
            router.route(virtualPacket)
        }finally {
            sendBufferPool.release(buffer)
        }
    }

    override fun peek(i: InetAddress): Int {
        TODO()
    }

    override fun peekData(p0: DatagramPacket?): Int {
        TODO("Not yet implemented")
    }

    public override fun receive(p: DatagramPacket) {
        assertNotClosed()

        val bufferPacket = receiveQueue.take()

        try {
            System.arraycopy(bufferPacket.data, bufferPacket.offset, p.data,
                p.offset, bufferPacket.length)
            p.length = bufferPacket.length
            p.address = bufferPacket.address
            p.port = bufferPacket.port
        }finally {
            receiveBufferPool.release(bufferPacket.data)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun setTTL(p0: Byte) {
        TODO("Not yet implemented")
    }

    @Deprecated("Deprecated in Java")
    override fun getTTL(): Byte {
        TODO("Not yet implemented")
    }

    override fun setTimeToLive(p0: Int) {
        TODO("Not yet implemented")
    }

    override fun getTimeToLive(): Int {
        TODO("Not yet implemented")
    }

    override fun join(p0: InetAddress?) {
        TODO("Not yet implemented")
    }

    override fun leave(p0: InetAddress?) {
        TODO("Not yet implemented")
    }

    override fun joinGroup(p0: SocketAddress?, p1: NetworkInterface?) {
        TODO("Not yet implemented")
    }

    override fun leaveGroup(p0: SocketAddress?, p1: NetworkInterface?) {
        TODO("Not yet implemented")
    }

    override fun close() {
        if(!closed.getAndSet(true)) {
            router.deallocatePort(Protocol.UDP, localPort)
        }
    }

    companion object {

        const val RECEIVE_BUFFER_SIZE = 512

        const val SEND_BUFFER_SIZE = 512

    }
}