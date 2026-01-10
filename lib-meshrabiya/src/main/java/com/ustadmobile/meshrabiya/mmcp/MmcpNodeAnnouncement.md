package com.ustadmobile.meshrabiya.mmcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.NetworkInterface

/**
 * MMCP message for node announcements using the enhanced gossip protocol.
 * Replaces the old MmcpOriginatorMessage with comprehensive node state information.
 */
data class MmcpNodeAnnouncement(
    val nodeId: String,
    val nodeType: NodeType,
    val fitnessScore: Float,
    val centralityScore: Float,
    val meshRoles: Set<MeshRole>,
    val resourceCapabilities: ResourceCapabilities,
    val batteryInfo: BatteryInfo,
    val thermalState: ThermalState,
    val timestamp: Long = System.currentTimeMillis(),
    val sentTime: Long = System.currentTimeMillis(),
    val neighbors: Set<String> = emptySet()
) : MmcpMessage(WHAT_NODE_ANNOUNCEMENT, System.currentTimeMillis().toInt()) {

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write node ID
        val nodeIdBytes = nodeId.toByteArray()
        dos.writeInt(nodeIdBytes.size)
        dos.write(nodeIdBytes)
        
        // Write node type
        dos.writeInt(nodeType.ordinal)
        
        // Write scores
        dos.writeFloat(fitnessScore)
        dos.writeFloat(centralityScore)
        
        // Write mesh roles
        dos.writeInt(meshRoles.size)
        meshRoles.forEach { role ->
            dos.writeInt(role.ordinal)
        }
        
        // Write resource capabilities
        dos.writeFloat(resourceCapabilities.availableCPU)
        dos.writeLong(resourceCapabilities.availableRAM)
        dos.writeLong(resourceCapabilities.availableBandwidth)
        dos.writeLong(resourceCapabilities.storageOffered)
        dos.writeInt(resourceCapabilities.batteryLevel)
        dos.writeBoolean(resourceCapabilities.thermalThrottling)
        dos.writeInt(resourceCapabilities.powerState.ordinal)
        dos.writeInt(resourceCapabilities.networkInterfaces.size)
        resourceCapabilities.networkInterfaces.forEach { ni ->
            val nameBytes = ni.name.toByteArray()
            dos.writeInt(nameBytes.size)
            dos.write(nameBytes)

            val displayNameBytes = (ni.displayName ?: "").toByteArray()
            dos.writeInt(displayNameBytes.size)
            dos.write(displayNameBytes)

            dos.writeInt(ni.mtu)
            dos.writeBoolean(ni.isLoopback)
            dos.writeBoolean(ni.supportsMulticast)
            dos.writeBoolean(ni.isPointToPoint)
            dos.writeBoolean(ni.isVirtual)

            dos.writeInt(ni.interfaceAddresses.size)
            ni.interfaceAddresses.forEach { addr ->
                val addrBytes = addr.toByteArray()
                dos.writeInt(addrBytes.size)
                dos.write(addrBytes)
            }

            dos.writeInt(ni.inetAddresses.size)
            ni.inetAddresses.forEach { addr ->
                val addrBytes = addr.toByteArray()
                dos.writeInt(addrBytes.size)
                dos.write(addrBytes)
            }
        }
        
        // Write battery info
        dos.writeInt(batteryInfo.level)
        dos.writeBoolean(batteryInfo.isCharging)
        dos.writeInt(batteryInfo.temperatureCelsius)
        dos.writeInt(batteryInfo.health.ordinal)
        dos.writeInt(batteryInfo.chargingSource?.ordinal ?: -1)
        
        // Write thermal state
        dos.writeInt(thermalState.ordinal)
        
        // Write timestamp
        dos.writeLong(timestamp)
        // Write sentTime
        dos.writeLong(sentTime)
        // Write neighbors
        dos.writeInt(neighbors.size)
        neighbors.forEach { neighborId ->
            val neighborBytes = neighborId.toByteArray()
            dos.writeInt(neighborBytes.size)
            dos.write(neighborBytes)
        }
        
        return baos.toByteArray()
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpNodeAnnouncement {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip what byte
            
            val messageId = buffer.int
            
            // Read node ID
            val nodeIdSize = buffer.int
            val nodeIdBytes = ByteArray(nodeIdSize)
            buffer.get(nodeIdBytes)
            val nodeId = String(nodeIdBytes)
            
            // Read node type
            val nodeType = NodeType.values()[buffer.int]
            
            // Read scores
            val fitnessScore = buffer.float
            val centralityScore = buffer.float
            
            // Read mesh roles
            val rolesSize = buffer.int
            val meshRoles = mutableSetOf<MeshRole>()
            repeat(rolesSize) {
                meshRoles.add(MeshRole.values()[buffer.int])
            }
            
            // Read resource capabilities
            val availableCPU = buffer.float
            val availableRAM = buffer.long
            val availableBandwidth = buffer.long
            val storageOffered = buffer.long
            val batteryLevel = buffer.int
            val thermalThrottling = buffer.get() != 0.toByte()
            val powerState = PowerState.values()[buffer.int]
            
            val networkInterfacesSize = buffer.int
            val networkInterfaces = mutableSetOf<SerializableNetworkInterfaceInfo>()
            repeat(networkInterfacesSize) {
                val nameSize = buffer.int
                val nameBytes = ByteArray(nameSize)
                buffer.get(nameBytes)
                val name = String(nameBytes)

                val displayNameSize = buffer.int
                val displayNameBytes = ByteArray(displayNameSize)
                buffer.get(displayNameBytes)
                val displayName = String(displayNameBytes)

                val mtu = buffer.int
                val isLoopback = buffer.get().toInt() != 0
                val supportsMulticast = buffer.get().toInt() != 0
                val isPointToPoint = buffer.get().toInt() != 0
                val isVirtual = buffer.get().toInt() != 0

                val interfaceAddressesSize = buffer.int
                val interfaceAddresses = mutableListOf<String>()
                repeat(interfaceAddressesSize) {
                    val addrSize = buffer.int
                    val addrBytes = ByteArray(addrSize)
                    buffer.get(addrBytes)
                    interfaceAddresses.add(String(addrBytes))
                }

                val inetAddressesSize = buffer.int
                val inetAddresses = mutableListOf<String>()
                repeat(inetAddressesSize) {
                    val addrSize = buffer.int
                    val addrBytes = ByteArray(addrSize)
                    buffer.get(addrBytes)
                    inetAddresses.add(String(addrBytes))
                }

                networkInterfaces.add(
                    SerializableNetworkInterfaceInfo(
                        name = name,
                        displayName = displayName,
                        mtu = mtu,
                        isLoopback = isLoopback,
                        supportsMulticast = supportsMulticast,
                        isPointToPoint = isPointToPoint,
                        isVirtual = isVirtual,
                        interfaceAddresses = interfaceAddresses,
                        inetAddresses = inetAddresses
                    )
                )
            }
            
            val resourceCapabilities = ResourceCapabilities(
                availableCPU = availableCPU,
                availableRAM = availableRAM,
                availableBandwidth = availableBandwidth,
                storageOffered = storageOffered,
                batteryLevel = batteryLevel,
                thermalThrottling = thermalThrottling,
                powerState = powerState,
                networkInterfaces = networkInterfaces
            )
            
            // Read battery info
            val level = buffer.int
            val isCharging = buffer.get() != 0.toByte()
            val temperatureCelsius = buffer.int
            val health = BatteryHealth.values()[buffer.int]
            val chargingSourceOrdinal = buffer.int
            val chargingSource = if (chargingSourceOrdinal >= 0) ChargingSource.values()[chargingSourceOrdinal] else null
            
            val batteryInfo = BatteryInfo(
                level = level,
                isCharging = isCharging,
                estimatedTimeRemaining = null, // Not serialized for simplicity
                temperatureCelsius = temperatureCelsius,
                health = health,
                chargingSource = chargingSource
            )
            
            // Read thermal state
            val thermalState = ThermalState.values()[buffer.int]
            
            // Read timestamp
            val timestamp = buffer.long
            // Read sentTime
            val sentTime = buffer.long
            // Read neighbors
            val neighborsSize = buffer.int
            val neighbors = mutableSetOf<String>()
            repeat(neighborsSize) {
                val neighborIdSize = buffer.int
                val neighborIdBytes = ByteArray(neighborIdSize)
                buffer.get(neighborIdBytes)
                neighbors.add(String(neighborIdBytes))
            }
            return MmcpNodeAnnouncement(
                nodeId = nodeId,
                nodeType = nodeType,
                fitnessScore = fitnessScore,
                centralityScore = centralityScore,
                meshRoles = meshRoles,
                resourceCapabilities = resourceCapabilities,
                batteryInfo = batteryInfo,
                thermalState = thermalState,
                timestamp = timestamp,
                sentTime = sentTime,
                neighbors = neighbors
            )
        }
    }
    
    /**
     * Create a copy of this message with updated ping time information
     */
    fun copyWithPingTimeIncrement(pingTime: Long): MmcpNodeAnnouncement {
        return copy(
            timestamp = timestamp + pingTime
        )
    }
}
