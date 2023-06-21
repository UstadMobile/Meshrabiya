package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import org.apache.commons.pool2.ObjectPool
import org.apache.commons.pool2.impl.GenericObjectPool
import java.net.DatagramPacket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VirtualDatagramSocket represents a DatagramSocket that exists on the Virtual network. The router
 * will put packets received in the receive buffer by calling onIncomingPacket. Receive can then be
 * used to receive the packets received, the same as a normal DatagramSocket. Send will convert a
 * datagrampacket into a VirtualPacket, and then send using the VirtualRouter.
 */
class VirtualDatagramSocket(
    private val port: Int = 0,
    private val localVirtualAddress: Int,
    private val router: VirtualRouter,
    private val receiveBufferSize: Int =  512,
    private val logger: MNetLogger,
) {

    private val _localPort = router.allocatePortOrThrow(Protocol.UDP, port)

    private val closed = AtomicBoolean(false)

    val localPort: Int
        get(){
            if(!closed.get())
                return _localPort
            else
                throw IllegalStateException("Socket is closed")
        }

    private val receiveQueue = LinkedBlockingDeque<DatagramPacket>()

    private val receiveBufferPool: ObjectPool<ByteArray> = GenericObjectPool(
        BufferPooledObjectFactory(VirtualPacket.VIRTUAL_PACKET_BUF_SIZE)
    )

    private val sendBufferPool: ObjectPool<ByteArray> = GenericObjectPool(
        BufferPooledObjectFactory(VirtualPacket.VIRTUAL_PACKET_BUF_SIZE)
    )

    /**
     * This function is called by the VirtualRouter when a packet is routed and this socket is
     * the destination.
     */
    internal fun onIncomingPacket(virtualPacket: VirtualPacket) {
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


    fun receive(datagramPacket: DatagramPacket) {
        val bufferPacket = receiveQueue.take()

        try {
            System.arraycopy(bufferPacket.data, bufferPacket.offset, datagramPacket.data,
                datagramPacket.offset, bufferPacket.length)
            datagramPacket.length = bufferPacket.length
            datagramPacket.address = bufferPacket.address
            datagramPacket.port = bufferPacket.port
        }finally {
            receiveBufferPool.returnObject(bufferPacket.data)
        }
    }

    fun send(datagramPacket: DatagramPacket) {
        //need to borrow a buffer
        //convert to virtual packet, then send using router.
        val buffer = sendBufferPool.borrowObject()
        try {
            System.arraycopy(datagramPacket.data, datagramPacket.offset,
                buffer, VirtualPacketHeader.HEADER_SIZE, datagramPacket.length)

            val virtualPacket = VirtualPacket.fromHeaderAndPayloadData(
                header = VirtualPacketHeader(
                    toAddr = datagramPacket.address.requireAddressAsInt(),
                    toPort = datagramPacket.port,
                    fromAddr = localVirtualAddress,
                    fromPort = localPort,
                    hopCount =  0,
                    maxHops = 5,
                    payloadSize = datagramPacket.length
                ),
                data = buffer,
                payloadOffset = VirtualPacketHeader.HEADER_SIZE,
            )
            router.route(virtualPacket)
        }finally {
            sendBufferPool.returnObject(buffer)
        }
    }

    fun close() {
        sendBufferPool.close()
        receiveBufferPool.close()
    }

}