package com.ustadmobile.meshrabiya.vnet.datagram

import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.BufferPooledObjectFactory
import com.ustadmobile.meshrabiya.vnet.Protocol
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import org.apache.commons.pool2.ObjectPool
import org.apache.commons.pool2.impl.GenericObjectPool
import java.net.DatagramPacket
import java.net.DatagramSocketImpl
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VirtualDatagramSocketImpl can be passed to a DatagramSocket as a constructor parameter. This
 * allows the creation of an actual DatagramSocket that will send/receive traffic over the virtual
 * network.
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

    private val receiveBufferPool: ObjectPool<ByteArray> = GenericObjectPool(
        BufferPooledObjectFactory(VirtualPacket.VIRTUAL_PACKET_BUF_SIZE)
    )

    private val sendBufferPool: ObjectPool<ByteArray> = GenericObjectPool(
        BufferPooledObjectFactory(VirtualPacket.VIRTUAL_PACKET_BUF_SIZE)
    )

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

        val buffer = receiveBufferPool.borrowObject()

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

        //need to borrow a buffer
        //convert to virtual packet, then send using router.
        val buffer = sendBufferPool.borrowObject()
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
            sendBufferPool.returnObject(buffer)
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
            receiveBufferPool.returnObject(bufferPacket.data)
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
            sendBufferPool.close()
            receiveBufferPool.close()
            router.deallocatePort(Protocol.UDP, localPort)
        }
    }
}