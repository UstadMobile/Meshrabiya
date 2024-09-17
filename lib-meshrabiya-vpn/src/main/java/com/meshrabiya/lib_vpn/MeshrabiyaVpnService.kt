package com.meshrabiya.lib_vpn


import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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

    // Configures the VPN connection parameters
    private fun setupVpn() {
        try {
            builder.apply {
                // Configure the virtual network settings
                addAddress(VPN_ADDRESS, VPN_ADDRESS_PREFIX_LENGTH)
                addRoute(ROUTE_ADDRESS, ROUTE_PREFIX_LENGTH)
                addDnsServer(PRIMARY_DNS)
                addDnsServer(SECONDARY_DNS)
                setSession(VPN_SESSION_NAME)
                setMtu(MTU_VALUE)
                allowFamily(OsConstants.AF_INET)
                allowFamily(OsConstants.AF_INET6)
            }

            // Establish the VPN interface
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                throw IllegalStateException("Failed to establish VPN interface.")
            } else {
                fileInputStream = FileInputStream(vpnInterface!!.fileDescriptor)
                fileOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
                logMessage("VPN interface established successfully")
                startHandlingTraffic()
            }

        } catch (e: Exception) {
            logMessage("Error setting up VPN: ${e.message ?: "Unknown error"}")
        }
    }

    // Initiates traffic handling in a separate thread
    private fun startHandlingTraffic() {
        Thread { handleTraffic() }.start()
    }

    // Main loop for processing network traffic
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

    // Reads data from the VPN interface
    private fun readFromVpnInterface(buffer: ByteBuffer): Int {
        return try {
            fileInputStream.channel.read(buffer)
        } catch (e: IOException) {
            logMessage("Error reading from VPN interface: ${e.message}")
            -1
        }
    }

    // Determines the IP version and processes the packet accordingly
    private fun processPacket(buffer: ByteBuffer, length: Int) {
        buffer.flip()

        val ipVersion = buffer.get(0).toInt() shr 4

        when (ipVersion) {
            4 -> handleIPv4Packet(buffer, length)
            6 -> handleIPv6Packet(buffer, length)
            else -> logMessage("Unsupported IP version: $ipVersion")
        }
    }

    // Processes IPv4 packets
    private fun handleIPv4Packet(buffer: ByteBuffer, length: Int) {
        val destinationIp = buildIPv4Address(buffer)
        val (destinationPort, protocolName) = parseIPv4Header(buffer)

        logMessage("IPv4 Packet - Destination: $destinationIp:$destinationPort, Protocol: $protocolName")

        if (isVirtualNetwork(destinationIp)) {
            handleVirtualNetworkPacket(buffer, length)
        } else {
            writeToVpnInterface(buffer)
        }
    }

    // Constructs the IPv4 address from the packet data
    private fun buildIPv4Address(buffer: ByteBuffer): String {
        return "${buffer.get(16).toInt() and 0xFF}." +
                "${buffer.get(17).toInt() and 0xFF}." +
                "${buffer.get(18).toInt() and 0xFF}." +
                "${buffer.get(19).toInt() and 0xFF}"
    }

    // Extracts information from the IPv4 header
    private fun parseIPv4Header(buffer: ByteBuffer): Pair<Int, String> {
        val protocol = buffer.get(9).toInt()
        val isTcp = protocol == 6
        val isUdp = protocol == 17

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

    // Processes IPv6 packets
    private fun handleIPv6Packet(buffer: ByteBuffer, length: Int) {
        val destinationIp = buildIPv6Address(buffer)
        val (destinationPort, protocolName) = parseIPv6Header(buffer)

        logMessage("IPv6 Packet - Destination: [$destinationIp]:$destinationPort, Protocol: $protocolName")

        if (isVirtualNetwork(destinationIp)) {
            handleVirtualNetworkPacket(buffer, length)
        } else {
            writeToVpnInterface(buffer)
        }
    }

    // Constructs the IPv6 address from the packet data
    private fun buildIPv6Address(buffer: ByteBuffer): String {
        val address = StringBuilder()
        for (i in 24 until 40 step 2) {
            address.append(String.format("%02x", buffer.get(i).toInt() and 0xFF))
            address.append(String.format("%02x", buffer.get(i + 1).toInt() and 0xFF))
            if (i < 38) address.append(":")
        }
        return address.toString()
    }

    // Extracts information from the IPv6 header
    private fun parseIPv6Header(buffer: ByteBuffer): Pair<Int, String> {
        val protocol = buffer.get(6).toInt()
        val isTcp = protocol == 6
        val isUdp = protocol == 17
        val isIcmpv6 = protocol == 58

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

    // Determines if the packet is destined for the virtual network
    private fun isVirtualNetwork(destinationIp: String): Boolean {
        return destinationIp.startsWith("10.")
    }

    // Handles packets destined for the virtual network
    private fun handleVirtualNetworkPacket(buffer: ByteBuffer, length: Int) {
        logMessage("Handling packet for virtual network")
    }

    // Writes packets to the VPN interface
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
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ADDRESS_PREFIX_LENGTH = 24
        private const val ROUTE_ADDRESS = "192.168.0.0"
        private const val ROUTE_PREFIX_LENGTH = 24
        private const val PRIMARY_DNS = "1.1.1.1"
        private const val SECONDARY_DNS = "8.8.8.8"
        private const val VPN_SESSION_NAME = "MeshrabiyaVPN"
        private const val MTU_VALUE = 1500
        private const val BUFFER_SIZE = 32767
    }
}