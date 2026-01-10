package com.ustadmobile.meshrabiya.mmcp

import org.junit.Test
import org.junit.Assert.*
import java.util.*


class EnhancedGossipMessageTest {

    @Test
    fun testCreateNodeAnnouncement() {
        val message = EnhancedGossipMessageFactory.createNodeAnnouncement(
            nodeId = "test-node-1",
            nodeType = NodeType.SMARTPHONE,
            deviceInfo = DeviceInfo(
                androidVersion = "13",
                apiLevel = 33,
                totalRAM = 8 * 1024 * 1024 * 1024L,
                cpuCores = 8,
                cpuArchitecture = "arm64",
                availableStorage = 128 * 1024 * 1024 * 1024L,
                hasGPS = true,
                hasCellular = true
            ),
            meshRoles = setOf(MeshRole.MESH_PARTICIPANT, MeshRole.STORAGE_NODE),
            fitnessScore = 0.85f,
            centralityScore = 0.72f,
            resourceCapabilities = ResourceCapabilities(
                availableCPU = 0.8f,
                availableRAM = 6 * 1024 * 1024 * 1024L,
                availableBandwidth = 100 * 1024 * 1024L,
                storageOffered = 64 * 1024 * 1024 * 1024L,
                batteryLevel = 90,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_HIGH,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 90,
                isCharging = true,
                estimatedTimeRemaining = null,
                temperatureCelsius = 30,
                health = BatteryHealth.GOOD,
                chargingSource = ChargingSource.AC
            ),
            thermalState = ThermalState.COOL
        )
        assertEquals("test-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.NODE_ANNOUNCEMENT, message.messageType)
        assertEquals(MessagePriority.NORMAL, message.priority)
        val payload = message.payload as NodeStatePayload
        assertEquals("test-node-1", payload.nodeId)
        assertTrue(payload.meshRoles.contains(MeshRole.MESH_PARTICIPANT))
        assertTrue(payload.meshRoles.contains(MeshRole.STORAGE_NODE))
    }

    @Test
    fun testCreateServiceAdvertisement() {
        val message = EnhancedGossipMessageFactory.createServiceAdvertisement(
            serviceId = "ml-service-1",
            serviceName = "Machine Learning Inference",
            serviceType = ServiceType.ML_INFERENCE,
            hostNodeId = "compute-node-1",
            endpoints = mapOf(
                "http" to ServiceEndpoint(
                    protocol = "HTTP",
                    address = "192.168.1.100",
                    port = 8080,
                    path = "/api/ml",
                    authRequired = false,
                    tlsSupported = true
                )
            ),
            capabilities = ServiceCapabilities(maxRequests = 1000)
        )
        assertEquals("compute-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.SERVICE_ADVERTISEMENT, message.messageType)
        val payload = message.payload as ServicePayload
        assertEquals("ml-service-1", payload.serviceId)
        assertEquals(ServiceType.ML_INFERENCE, payload.serviceType)
        assertEquals("compute-node-1", payload.hostNodeId)
        assertEquals(1, payload.endpoints.size)
    }

    @Test
    fun testCreateI2PTunnelRequest() {
        val message = createI2PTunnelRequest(
            requestingNodeId = "i2p-client-1",
            destination = "i2p-destination-1",
            tunnelType = TunnelType.CLIENT
        )
        assertEquals("i2p-client-1", message.sourceNodeId)
        assertEquals(GossipMessageType.I2P_TUNNEL_REQUEST, message.messageType)
        val payload = message.payload as I2PPayload
        assertEquals(I2PAction.TUNNEL_REQUEST, payload.action)
        assertEquals("i2p-client-1", payload.nodeId)
        assertNotNull(payload.tunnelRequests)
        assertEquals("i2p-destination-1", payload.tunnelRequests!![0].destination)
        assertEquals(TunnelType.CLIENT, payload.tunnelRequests!![0].tunnelType)
    }

    @Test
    fun testCreateHeartbeat() {
        val timestamp = System.currentTimeMillis()
        val message = EnhancedGossipMessageFactory.createHeartbeat(
            nodeId = "heartbeat-node-1",
            timestamp = timestamp
        )
        assertEquals("heartbeat-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.HEARTBEAT, message.messageType)
        assertEquals(MessagePriority.BACKGROUND, message.priority)
        assertEquals(timestamp, message.timestamp)
        val payload = message.payload as NodeStatePayload
        assertEquals("heartbeat-node-1", payload.nodeId)
    }

    @Test
    fun testCreateEmergencyBroadcast() {
        val message = EnhancedGossipMessageFactory.createEmergencyBroadcast(
            sourceNodeId = "emergency-node-1",
            message = "Network partition detected"
        )
        assertEquals("emergency-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.EMERGENCY_BROADCAST, message.messageType)
        assertEquals(MessagePriority.EMERGENCY, message.priority)
        assertEquals(10, message.ttl)
        val payload = message.payload as NodeStatePayload
        assertEquals("emergency-node-1", payload.nodeId)
        assertEquals(ThermalState.CRITICAL, payload.thermalState)
        assertEquals(PowerState.BATTERY_CRITICAL, payload.resourceCapabilities.powerState)
    }

    @Test
    fun testCreateNetworkMetrics() {
        val metrics = mapOf(
            "fitness" to 0.78f,
            "centrality" to 0.65f,
            "connectionQuality" to 0.92f,
            "latency" to 45.0f,
            "cpu" to 0.45f,
            "ram" to 0.67f,
            "bandwidth" to 0.83f,
            "storage" to 0.34f,
            "battery" to 0.72f,
            "temperature" to 32.0f,
            "thermalThrottling" to 0.1f
        )
        val message = EnhancedGossipMessageFactory.createNetworkMetrics(
            nodeId = "metrics-node-1",
            metrics = metrics
        )
        assertEquals("metrics-node-1", message.sourceNodeId)
        assertEquals(GossipMessageType.NETWORK_METRICS, message.messageType)
        assertEquals(MessagePriority.LOW, message.priority)
        val payload = message.payload as NodeStatePayload
        assertEquals("metrics-node-1", payload.nodeId)
        assertEquals(0.78f, payload.fitnessScore, 0.01f)
        assertEquals(0.65f, payload.centralityScore, 0.01f)
        assertEquals(0.92f, payload.connectionQuality, 0.01f)
        assertEquals(45L, payload.networkLatency.avgLatency)
    }

    @Test
    fun testMessageEquality() {
        val message1 = EnhancedGossipMessageFactory.createNodeAnnouncement(
            nodeId = "test-node",
            nodeType = NodeType.SMARTPHONE,
            deviceInfo = DeviceInfo(
                androidVersion = "13",
                apiLevel = 33,
                totalRAM = 4 * 1024 * 1024 * 1024L,
                cpuCores = 4,
                cpuArchitecture = "arm64",
                availableStorage = 64 * 1024 * 1024 * 1024L,
                hasGPS = true,
                hasCellular = true
            ),
            meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
            fitnessScore = 0.5f,
            centralityScore = 0.0f,
            resourceCapabilities = ResourceCapabilities(
                availableCPU = 0.5f,
                availableRAM = 0L,
                availableBandwidth = 0L,
                storageOffered = 0L,
                batteryLevel = 100,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_HIGH,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 100,
                isCharging = false,
                estimatedTimeRemaining = null,
                temperatureCelsius = 25,
                health = BatteryHealth.GOOD,
                chargingSource = null
            ),
            thermalState = ThermalState.COOL
        )
        val message2 = EnhancedGossipMessageFactory.createNodeAnnouncement(
            nodeId = "test-node",
            nodeType = NodeType.SMARTPHONE,
            deviceInfo = DeviceInfo(
                androidVersion = "13",
                apiLevel = 33,
                totalRAM = 4 * 1024 * 1024 * 1024L,
                cpuCores = 4,
                cpuArchitecture = "arm64",
                availableStorage = 64 * 1024 * 1024 * 1024L,
                hasGPS = true,
                hasCellular = true
            ),
            meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
            fitnessScore = 0.5f,
            centralityScore = 0.0f,
            resourceCapabilities = ResourceCapabilities(
                availableCPU = 0.5f,
                availableRAM = 0L,
                availableBandwidth = 0L,
                storageOffered = 0L,
                batteryLevel = 100,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_HIGH,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 100,
                isCharging = false,
                estimatedTimeRemaining = null,
                temperatureCelsius = 25,
                health = BatteryHealth.GOOD,
                chargingSource = null
            ),
            thermalState = ThermalState.COOL
        )
        assertNotEquals(message1, message2)
        assertNotEquals(message1.hashCode(), message2.hashCode())
    }

}

