package com.ustadmobile.meshrabiya.vnet.netinterface

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import java.io.Closeable
import java.net.InetAddress

/**
 * Represents a Virtual Network Interface which can be implemented by different underlying transport
 * layers.
 *
 * The Virtual Network Interface is responsible to maintain its own mapping of virtual addresses (as
 * per the VirtualPacket.header) to a real address it can use with its own transport layer. This
 * could be:
 *  - If using a TCP/IP link e.g. local WiFi: the real InetAddress and datagram port of a
 *    VirtualNodeDatagramSocket
 *  - If using Google Nearby, the device address
 *  - If using Bluetooth directly, the Bluetooth Mac address of the remote device.
 *
 * When a VirtualPacket is received by the underlying transport layer, The VirtualNetworkInterface
 * should
 *  - update its mapping of virtual address to real transport layer addresses
 *  - call the VirtualNode.route(packet) function
 *
 * A VirtualPacket can be received by the underlying transport when:
 *  - If using a TCP/IP link, using a real DatagramSocket e.g. VirtualNodeDatagramSocket
 *  - If using Google Nearby, using the on incoming data callback
 *  - If using Bluetooth directly, using the GATT callback
 *
 * When an incoming stream is received by the underlying transport layer, the VirtualNetworkInterface
 * should call VirtualNode.route(socket).
 *
 */
interface VirtualNetworkInterface : Closeable {

    val virtualAddress: InetAddress

    /**
     * Send the given VirtualPacket over this VirtualNetworkInterface to the NextHopAddress
     *
     * @param nextHopAddress the Virtual Address of the next hop to which the packet should be sent.
     * @param virtualPacket the VirtualPacket to send. The final destination may (or may not) be the
     * nextHopAddress.
     */
    fun send(
        virtualPacket: VirtualPacket,
        nextHopAddress: InetAddress,
    )

    /**
     * Connect a socket using this VirtualNetworkInterface using the underlying transport. This
     * can be implemented as follows:
     *
     *  - On TCP/IP networks: connect to the real address of the other node's ChainSocketFactory
     *  - On Google Nearby:
     *      Send a stream payload to the other node, using a PipedInputStream, where the PipedInputStream's
     *      input comes from VSocket.outputStream.
     *      Wait (e.g. via completablefuture) for the other node to send a Payload, the InputStream
     *      from the Payload is VSocket.inputStream
     *      Return the VSocket
     *
     * @param nextHopAddress the virtual Address of the next hop to which the stream will be
     * connected, this may or may not be the destination address
     * @param destAddress the final destination address of the stream
     * @param destPort the port on the final destination address
     */
    fun connectSocket(
        nextHopAddress: InetAddress,
        destAddress: InetAddress,
        destPort: Int,
    ): VSocket

}