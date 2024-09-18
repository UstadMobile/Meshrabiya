package com.meshrabiya.lib_vpn


import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer

class MeshrabiyaVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var fileInputStream: FileInputStream
    private lateinit var fileOutputStream: FileOutputStream
    private val builder = Builder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logMessage("VPN Service started")
        setupVpn()
        return START_STICKY
    }

    /**
     * Configures the VPN connection with address, routes, DNS servers, and session settings.
     * Establishes the VPN interface and sets up input and output streams.
     */
    private fun setupVpn() {
        try {
            builder.apply {
                addAddress(VPN_ADDRESS, VPN_ADDRESS_PREFIX_LENGTH)
                addRoute(ROUTE_ADDRESS, ROUTE_PREFIX_LENGTH)
                addDnsServer(PRIMARY_DNS)
                addDnsServer(SECONDARY_DNS)
                setSession(VPN_SESSION_NAME)
                setMtu(MTU_VALUE)
                allowFamily(OsConstants.AF_INET)
                allowFamily(OsConstants.AF_INET6)
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                logMessage("Failed to establish VPN interface: vpnInterface is null")
                throw IllegalStateException("Failed to establish VPN interface.")
            }

            fileInputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            fileOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            logMessage("VPN interface established successfully")
            startHandlingTraffic()

        } catch (e: Exception) {
            logMessage("Error setting up VPN: ${e.message ?: "Unknown error"}")
            // Optionally rethrow or handle specific exceptions
        }
    }

    /**
     * Starts a new thread to handle traffic.
     */
    private fun startHandlingTraffic() {
        Thread { handleTraffic() }.start()
    }

    /**
     * Continuously reads and processes traffic from the VPN interface.
     */
    private fun handleTraffic() {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        while (true) {
            try {
                buffer.clear()
                val length = readFromVpnInterface(buffer)
                if (length > 0) {
                    processPacket(buffer, length)
                }
            } catch (e: IOException) {
                logMessage("Error handling traffic: ${e.message}")
                break
            }
        }
    }

    /**
     * Reads data from the VPN interface into the provided buffer.
     * @return the number of bytes read or -1 in case of an error.
     */
    private fun readFromVpnInterface(buffer: ByteBuffer): Int {
        return try {
            fileInputStream.channel.read(buffer)
        } catch (e: IOException) {
            logMessage("Error reading from VPN interface: ${e.message}")
            -1
        }
    }

    /**
     * Processes a packet by determining its IP version and handling accordingly.
     * @param buffer the buffer containing the packet data.
     * @param length the length of the packet data.
     */
    private fun processPacket(buffer: ByteBuffer, length: Int) {
        buffer.flip()

        when (val ipVersion = (buffer.get(0).toInt() ushr 4).toByte()) {
            IPV4_VERSION -> handleIPv4Packet(buffer, length)
            IPV6_VERSION -> handleIPv6Packet(buffer, length)
            else -> logMessage("Unsupported IP version: $ipVersion")
        }
    }

    /**
     * Handles an IPv4 packet by extracting the destination IP and port, and processing it based on the network type.
     * @param buffer the buffer containing the IPv4 packet data.
     * @param length the length of the packet data.
     */
    private fun handleIPv4Packet(buffer: ByteBuffer, length: Int) {
        val destinationIp = buildIPv4Address(buffer)
        val (destinationPort, protocolName) = parseIPv4Header(buffer)

        logMessage("IPv4 Packet - Destination: ${destinationIp.hostAddress}:$destinationPort, Protocol: $protocolName")

        if (isVirtualNetwork(destinationIp)) {
            handleVirtualNetworkPacket(buffer, length)
        } else {
            writeToVpnInterface(buffer)
        }
    }

    /**
     * Builds an IPv4 address from the provided buffer.
     * @param buffer the buffer containing the packet data.
     * @return the constructed IPv4 address.
     */
    private fun buildIPv4Address(buffer: ByteBuffer): InetAddress {
        val addressBytes = buffer.array().copyOfRange(16, 20)
        return InetAddress.getByAddress(addressBytes)
    }

    /**
     * Parses the IPv4 header to extract the destination port and protocol name.
     * @param buffer the buffer containing the IPv4 packet data.
     * @return a pair of the destination port and the protocol name.
     */
    private fun parseIPv4Header(buffer: ByteBuffer): Pair<Int, String> {
        val protocol = buffer.get(9).toInt()
        val isTcp = protocol == IPPROTO_TCP
        val isUdp = protocol == IPPROTO_UDP

        val ipHeaderLength = (buffer.get(0).toInt() and 0x0F) * 4
        val destinationPort = if (isTcp || isUdp) {
            ((buffer.get(ipHeaderLength + 2).toInt() and 0xFF) shl 8) or
                    (buffer.get(ipHeaderLength + 3).toInt() and 0xFF)
        } else {
            -1
        }

        val protocolName = when {
            isTcp -> "TCP"
            isUdp -> "UDP"
            else -> "Unknown"
        }

        return Pair(destinationPort, protocolName)
    }

    /**
     * Handles an IPv6 packet by extracting the destination IP and port, and processing it based on the network type.
     * @param buffer the buffer containing the IPv6 packet data.
     * @param length the length of the packet data.
     */
    private fun handleIPv6Packet(buffer: ByteBuffer, length: Int) {
        val destinationIp = buildIPv6Address(buffer)
        val (destinationPort, protocolName) = parseIPv6Header(buffer)

        logMessage("IPv6 Packet - Destination: [${destinationIp.hostAddress}]:$destinationPort, Protocol: $protocolName")

        if (isVirtualNetwork(destinationIp)) {
            handleVirtualNetworkPacket(buffer, length)
        } else {
            writeToVpnInterface(buffer)
        }
    }

    /**
     * Builds an IPv6 address from the provided buffer.
     * @param buffer the buffer containing the packet data.
     * @return the constructed IPv6 address.
     */
    private fun buildIPv6Address(buffer: ByteBuffer): InetAddress {
        val addressBytes = buffer.array().copyOfRange(24, 40)
        return InetAddress.getByAddress(addressBytes)
    }

    /**
     * Parses the IPv6 header to extract the destination port and protocol name.
     * @param buffer the buffer containing the IPv6 packet data.
     * @return a pair of the destination port and the protocol name.
     */
    private fun parseIPv6Header(buffer: ByteBuffer): Pair<Int, String> {
        val protocol = buffer.get(6).toInt()
        val isTcp = protocol == IPPROTO_TCP
        val isUdp = protocol == IPPROTO_UDP
        val isIcmpv6 = protocol == IPPROTO_ICMPV6

        val destinationPort = if (isTcp || isUdp) {
            ((buffer.get(40).toInt() and 0xFF) shl 8) or
                    (buffer.get(41).toInt() and 0xFF)
        } else {
            -1
        }

        val protocolName = when {
            isTcp -> "TCP"
            isUdp -> "UDP"
            isIcmpv6 -> "ICMPv6"
            else -> "Unknown"
        }

        return Pair(destinationPort, protocolName)
    }

    /**
     * Checks if the destination IP is part of the virtual network.
     * @param destinationIp the destination IP address.
     * @return true if the IP is part of the virtual network, false otherwise.
     */
    private fun isVirtualNetwork(destinationIp: InetAddress): Boolean {
        return destinationIp.isSiteLocalAddress
    }

    /**
     * Handles packets that are destined for the virtual network.
     * @param buffer the buffer containing the packet data.
     * @param length the length of the packet data.
     */
    private fun handleVirtualNetworkPacket(buffer: ByteBuffer, length: Int) {
        logMessage("Handling packet for virtual network ")
    }

    /**
     * Writes the provided buffer data to the VPN interface.
     * @param buffer the buffer containing the packet data.
     */
    private fun writeToVpnInterface(buffer: ByteBuffer) {
        try {
            fileOutputStream.channel.write(buffer)
        } catch (e: IOException) {
            logMessage("Error writing to VPN interface: ${e.message}")
        }
    }

    /**
     * Called when the VPN service is revoked. Closes resources and performs cleanup.
     */
    override fun onRevoke() {
        logMessage("VPN Service revoked")
        try {
            fileInputStream.close()
            fileOutputStream.close()
            vpnInterface?.close()
        } catch (e: IOException) {
            logMessage("Error closing resources: ${e.message}")
        }
        super.onRevoke()
    }

    /**
     * Logs messages for debugging and informational purposes.
     * @param message the message to log.
     */
    private fun logMessage(message: String) {
        Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "MeshrabiyaVpnService"
        private val VPN_ADDRESS = InetAddress.getByName("10.0.0.2")
        private const val VPN_ADDRESS_PREFIX_LENGTH = 24
        private val ROUTE_ADDRESS = InetAddress.getByName("192.168.0.0")
        private const val ROUTE_PREFIX_LENGTH = 24
        private val PRIMARY_DNS = InetAddress.getByName("1.1.1.1")
        private val SECONDARY_DNS = InetAddress.getByName("8.8.8.8")
        private const val VPN_SESSION_NAME = "MeshrabiyaVPN"
        private const val MTU_VALUE = 1500
        private const val BUFFER_SIZE = 32767
        private const val IPV4_VERSION: Byte = 4
        private const val IPV6_VERSION: Byte = 6
        private const val IPPROTO_TCP = 6
        private const val IPPROTO_UDP = 17
        private const val IPPROTO_ICMPV6 = 58
    }
}