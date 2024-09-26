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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MeshrabiyaVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var fileInputStream: FileInputStream
    private lateinit var fileOutputStream: FileOutputStream
    private val builder = Builder()
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

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
        }
    }

    /**
     * Starts a new thread to handle traffic.
     */
    private fun startHandlingTraffic() {
        executorService.submit { handleTraffic() }
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
        // The `flip()` method gets the buffer ready to read data.
        // When we write to the buffer, it moves a pointer forward.
        // `flip()` changes the buffer from writing mode to reading mode.
        // It sets the end (limit) to where we stopped writing and resets the start (position) to zero,
        // so we can read the data from the beginning.
        buffer.flip()

        // Check the first byte of the packet to figure out if it's an IPv4 or IPv6 packet.
        // The first few bits of the first byte tell us the IP version (4 or 6).
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
        val header = parseIPv4Header(buffer)

        logMessage("IPv4 Packet - Destination: ${header.destinationIp.hostAddress}:${header.destinationPort}, Protocol: ${header.protocolName}")

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
        return InetAddress.getByAddress(ByteArray(4).also { buffer.get(it, 0, 4) })
    }

    /**
     * Parses the IPv4 header to extract important information such as destination port, origin port, and protocol.
     * Returns an IpHeader data class containing all relevant information.
     */
    private fun parseIPv4Header(buffer: ByteBuffer): IpHeader {
        try {
            val protocol = buffer.get(IPV4_PROTOCOL_OFFSET).toInt()
            val isTcp = protocol == IPPROTO_TCP
            val isUdp = protocol == IPPROTO_UDP

            val ipHeaderLength = (buffer.get(IPV4_HEADER_LENGTH_OFFSET).toInt() and 0x0F) * 4
            val destinationPort = if (isTcp || isUdp) {
                ((buffer.get(ipHeaderLength + 2).toInt() and 0xFF) shl 8) or
                        (buffer.get(ipHeaderLength + 3).toInt() and 0xFF)
            } else {
                -1
            }

            val protocolName = when {
                isTcp -> Protocol.TCP
                isUdp -> Protocol.UDP
                else -> Protocol.UNKNOWN
            }

            return IpHeader(
                destinationIp = buildIPv4Address(buffer),
                destinationPort = destinationPort,

                // NOTE : ---->  Placeholder, real origin logic needed
                originIp = InetAddress.getByName("0.0.0.0"),
                // Placeholder
                originPort = -1,

                protocolName = protocolName
            )
        } catch (e: Exception) {
            logMessage("Error parsing IPv4 header: ${e.message}")
            return IpHeader(InetAddress.getByName("0.0.0.0"), -1, InetAddress.getByName("0.0.0.0"), -1, Protocol.ERROR)
        }
    }

    /**
     * Handles an IPv6 packet by extracting the destination IP and port, and processing it based on the network type.
     * @param buffer the buffer containing the IPv6 packet data.
     * @param length the length of the packet data.
     */
    private fun handleIPv6Packet(buffer: ByteBuffer, length: Int) {
        val destinationIp = buildIPv6Address(buffer)
        val header = parseIPv6Header(buffer)

        logMessage("IPv6 Packet - Destination: [${header.destinationIp.hostAddress}]:${header.destinationPort}, Protocol: ${header.protocolName}")

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
        return InetAddress.getByAddress(ByteArray(16).also { buffer.get(it, 0, 16) })
    }

    /**
     * Parses the IPv6 header to extract important information such as destination port, origin port, and protocol.
     * Returns an IpHeader data class containing all relevant information.
     */
    private fun parseIPv6Header(buffer: ByteBuffer): IpHeader {
        try {
            val protocol = buffer.get(IPV6_PROTOCOL_OFFSET).toInt()
            val isTcp = protocol == IPPROTO_TCP
            val isUdp = protocol == IPPROTO_UDP

            val destinationPort = if (isTcp || isUdp) {
                ((buffer.get(IPV6_PORT_OFFSET).toInt() and 0xFF) shl 8) or
                        (buffer.get(IPV6_PORT_OFFSET + 1).toInt() and 0xFF)
            } else {
                -1
            }

            val protocolName = when {
                isTcp -> Protocol.TCP
                isUdp -> Protocol.UDP
                else -> Protocol.UNKNOWN
            }

            return IpHeader(
                destinationIp = buildIPv6Address(buffer),
                destinationPort = destinationPort,

                // NOTE : ---->  Placeholder, real origin logic needed
                originIp = InetAddress.getByName("::"),
                // Placeholder
                originPort = -1,
                protocolName = protocolName
            )
        } catch (e: Exception) {
            logMessage("Error parsing IPv6 header: ${e.message}")
            return IpHeader(InetAddress.getByName("::"), -1, InetAddress.getByName("::"), -1, Protocol.ERROR)
        }
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

    private fun logMessage(message: String) {
        Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "MeshrabiyaVpnService"
        private const val VPN_SESSION_NAME = "MeshrabiyaVPN"

        // VPN_ADDRESS is the internal IP address used by the VPN for routing data within the VPN network.
        private val VPN_ADDRESS = InetAddress.getByName("10.0.0.2")

        // VPN_ADDRESS_PREFIX_LENGTH specifies the subnet mask for the VPN network. A value of 24 means the VPN uses a 255.255.255.0 subnet mask, allowing for up to 256 IP addresses.
        private const val VPN_ADDRESS_PREFIX_LENGTH = 24

        // ROUTE_ADDRESS is the IP address range for routing local network traffic. It’s used to determine which traffic should go through the local network.
        private val ROUTE_ADDRESS = InetAddress.getByName("192.168.0.0")

        // ROUTE_PREFIX_LENGTH specifies the subnet mask for local network routing. Similar to VPN_ADDRESS_PREFIX_LENGTH, this mask defines how many addresses are in the local network.
        private const val ROUTE_PREFIX_LENGTH = 24

        // PRIMARY_DNS is the IP address of the main DNS server used by the VPN for resolving domain names to IP addresses.
        private val PRIMARY_DNS = InetAddress.getByName("1.1.1.1")

        // SECONDARY_DNS is the IP address of a backup DNS server. If the primary DNS server is unavailable, the VPN will use this server instead.
        private val SECONDARY_DNS = InetAddress.getByName("8.8.8.8")

        // MTU_VALUE stands for Maximum Transmission Unit. It defines the largest size of a packet that can be sent through the VPN without needing to be fragmented. 1500 bytes is a common default size.
        private const val MTU_VALUE = 1500

        // BUFFER_SIZE determines the amount of data that can be read or written at once. 32767 bytes is a size chosen to handle large packets efficiently.
        private const val BUFFER_SIZE = 32767

        private const val IPV4_VERSION: Byte = 4
        private const val IPV6_VERSION: Byte = 6

        // Protocol numbers for different types of network traffic, based on the official IP protocol specifications.
        // These numbers identify whether a packet is using TCP, UDP, or ICMPv6 protocol.
        private const val IPPROTO_TCP = 6 // TCP is protocol number 6 (used for web traffic, file transfer, etc.)
        private const val IPPROTO_UDP = 17 // UDP is protocol number 17 (used for streaming, video games, etc.)

        private const val IPV4_PROTOCOL_OFFSET = 9 // For IPv4 packets, the type of protocol (e.g., TCP, UDP) is found at the 10th byte of the header. This is why the offset is 9.
        private const val IPV4_HEADER_LENGTH_OFFSET = 0 // The length of the IPv4 header itself is encoded in the first byte, but only in the first 4 bits. This is why we use offset 0.
        private const val IPV6_PROTOCOL_OFFSET = 6 // For IPv6 packets, the protocol type is found in the 7th byte of the header, which is why the offset is 6.
        private const val IPV6_PORT_OFFSET = 40 // In IPv6 packets, information about source and destination ports starts after the first 40 bytes of the header, hence the offset is 40.
    }
}

/**
 * Enum class representing the valid protocols (TCP, UDP, ICMPv6, and Unknown).
 */
enum class Protocol {
    TCP, UDP, UNKNOWN, ERROR
}

/**
 * Data class to represent the parsed IP header with necessary information.
 */
data class IpHeader(
    val destinationIp: InetAddress,
    val destinationPort: Int,
    val originIp: InetAddress,
    val originPort: Int,
    val protocolName: Protocol
)