package com.ustadmobile.meshrabiya.mmcp

import java.security.PublicKey
import java.util.*
import kotlin.time.Duration

/**
 * Factory class for creating enhanced gossip messages
 */
object EnhancedGossipMessageFactory {
    
    /**
     * Create a node announcement message with comprehensive node information
     */
    fun createNodeAnnouncement(
        nodeId: String,
        nodeType: NodeType,
        deviceInfo: DeviceInfo,
        meshRoles: Set<MeshRole>,
        fitnessScore: Float,
        centralityScore: Float,
        resourceCapabilities: ResourceCapabilities,
        batteryInfo: BatteryInfo,
        thermalState: ThermalState,
        connectedPeers: Map<String, ConnectionInfo> = emptyMap(),
        availableServices: List<ServiceCapability> = emptyList(),
        i2pCapabilities: I2PCapabilities? = null,
        storageCapabilities: StorageCapabilities? = null,
        computeCapabilities: ComputeCapabilities? = null,
        priority: MessagePriority = MessagePriority.NORMAL
    ): EnhancedGossipMessage {
        return EnhancedGossipMessage(
            messageType = GossipMessageType.NODE_ANNOUNCEMENT,
            sourceNodeId = nodeId,
            originatorNodeId = nodeId,
            priority = priority,
            payload = NodeStatePayload(
                nodeId = nodeId,
                nodeType = nodeType,
                deviceInfo = deviceInfo,
                meshRoles = meshRoles,
                fitnessScore = fitnessScore.coerceIn(0.0f, 1.0f),
                centralityScore = centralityScore.coerceIn(0.0f, 1.0f),
                connectionQuality = 1.0f,
                networkLatency = NetworkLatencyInfo(),
                resourceCapabilities = resourceCapabilities,
                batteryInfo = batteryInfo,
                thermalState = thermalState,
                connectedPeers = connectedPeers,
                gatewayCapabilities = GatewayCapabilities(),
                routingCapabilities = RoutingCapabilities(),
                availableServices = availableServices,
                serviceLoad = emptyMap(),
                i2pCapabilities = i2pCapabilities,
                i2pTunnelInfo = null,
                storageInfo = storageCapabilities,
                computeInfo = computeCapabilities,
                lastUpdated = System.currentTimeMillis(),
                sequenceNumber = 1L
            )
        )
    }
    
    /**
     * Create a service advertisement message
     */
    fun createServiceAdvertisement(
        serviceId: String,
        serviceName: String,
        serviceType: ServiceType,
        hostNodeId: String,
        endpoints: Map<String, ServiceEndpoint>,
        capabilities: ServiceCapabilities,
        currentLoad: Float = 0.0f,
        maxCapacity: Int = 100,
        priority: MessagePriority = MessagePriority.NORMAL
    ): EnhancedGossipMessage {
        return EnhancedGossipMessage(
            messageType = GossipMessageType.SERVICE_ADVERTISEMENT,
            sourceNodeId = hostNodeId,
            originatorNodeId = hostNodeId,
            priority = priority,
            payload = ServicePayload(
                serviceId = serviceId,
                serviceName = serviceName,
                serviceType = serviceType,
                hostNodeId = hostNodeId,
                endpoints = endpoints,
                capabilities = capabilities,
                currentLoad = currentLoad.coerceIn(0.0f, 1.0f),
                maxCapacity = maxCapacity,
                qosMetrics = QoSMetrics(),
                requirements = null,
                availability = ServiceAvailability.AVAILABLE
            )
        )
    }
    
    /**
     * Create a compute task request message
     */
    fun createComputeTaskRequest(
        taskId: String,
        taskType: ComputeTaskType,
        requesterNodeId: String,
        requirements: ComputeRequirements,
        deadline: Long? = null,
        priority: TaskPriority = TaskPriority.NORMAL,
        dependencies: List<String> = emptyList(),
        targetRuntime: RuntimeEnvironment = RuntimeEnvironment.JAVA_KOTLIN
    ): EnhancedGossipMessage {
        return EnhancedGossipMessage(
            messageType = GossipMessageType.COMPUTE_TASK_REQUEST,
            sourceNodeId = requesterNodeId,
            originatorNodeId = requesterNodeId,
            priority = when (priority) {
                TaskPriority.URGENT -> MessagePriority.HIGH
                TaskPriority.HIGH -> MessagePriority.HIGH
                TaskPriority.NORMAL -> MessagePriority.NORMAL
                TaskPriority.LOW -> MessagePriority.LOW
            },
            payload = ComputeTaskPayload(
                taskId = taskId,
                taskType = taskType,
                requesterNodeId = requesterNodeId,
                requirements = requirements,
                executionPlan = null,
                taskData = null,
                deadline = deadline,
                priority = priority,
                dependencies = dependencies,
                targetRuntimeEnvironment = targetRuntime
            )
        )
    }
    
    /**
     * Create an I2P router advertisement message
     */
    fun createI2PRouterAdvertisement(
        nodeId: String,
        i2pCapabilities: I2PCapabilities,
        routerInfo: I2PRouterInfo,
        priority: MessagePriority = MessagePriority.NORMAL
    ): EnhancedGossipMessage {
        return EnhancedGossipMessage(
            messageType = GossipMessageType.I2P_ROUTER_ADVERTISEMENT,
            sourceNodeId = nodeId,
            originatorNodeId = nodeId,
            priority = priority,
            payload = I2PPayload(
                action = I2PAction.ROUTER_ADVERTISEMENT,
                nodeId = nodeId,
                i2pRouterInfo = routerInfo,
                tunnelRequests = null,
                tunnelOffers = null,
                networkDbInfo = null,
                routerStatus = i2pCapabilities.routerStatus
            )
        )
    }
    
    /**
     * Create a storage advertisement message
     */
    fun createStorageAdvertisement(
        nodeId: String,
        storageCapabilities: StorageCapabilities,
        priority: MessagePriority = MessagePriority.NORMAL
    ): EnhancedGossipMessage {
        return EnhancedGossipMessage(
            messageType = GossipMessageType.STORAGE_ADVERTISEMENT,
            sourceNodeId = nodeId,
            originatorNodeId = nodeId,
            priority = priority,
            payload = StoragePayload(
                action = StorageAction.OFFER_STORAGE,
                nodeId = nodeId,
                storageOffered = storageCapabilities.totalOffered,
                storageUsed = storageCapabilities.currentlyUsed,
                dataReferences = null,
                replicationRequests = null,
                seedingTasks = null,
                storagePolicy = null
            )
        )
    }
    
    /**
     * Create a quorum proposal message
     */
    fun createQuorumProposal(
        quorumId: String,
        proposingNodeId: String,
        memberNodes: Set<String>,
        quorumType: QuorumType,
        roleAssignments: Map<String, MeshRole>,
        decisionDeadline: Long,
        priority: MessagePriority = MessagePriority.HIGH
    ): EnhancedGossipMessage {
        return EnhancedGossipMessage(
            messageType = GossipMessageType.QUORUM_PROPOSAL,
            sourceNodeId = proposingNodeId,
            originatorNodeId = proposingNodeId,
            priority = priority,
            payload = QuorumPayload(
                quorumId = quorumId,
                action = QuorumAction.PROPOSE_FORMATION,
                proposingNodeId = proposingNodeId,
                memberNodes = memberNodes,
                quorumType = quorumType,
                roleAssignments = roleAssignments,
                consensus = null,
                votingRound = 1,
                decisionDeadline = decisionDeadline
            )
        )
    }
    
    /**
     * Create a heartbeat message
     */
    fun createHeartbeat(
        nodeId: String,
        timestamp: Long = System.currentTimeMillis(),
        priority: MessagePriority = MessagePriority.BACKGROUND
    ): EnhancedGossipMessage {
        return EnhancedGossipMessage(
            messageType = GossipMessageType.HEARTBEAT,
            sourceNodeId = nodeId,
            originatorNodeId = nodeId,
            timestamp = timestamp,
            priority = priority,
            payload = NodeStatePayload(
                nodeId = nodeId,
                nodeType = NodeType.SMARTPHONE,
                deviceInfo = getCurrentDeviceInfo(),
                meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
                fitnessScore = 0.5f,
                centralityScore = 0.0f,
                connectionQuality = 1.0f,
                networkLatency = NetworkLatencyInfo(),
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
                thermalState = ThermalState.COOL,
                connectedPeers = emptyMap(),
                gatewayCapabilities = GatewayCapabilities(),
                routingCapabilities = RoutingCapabilities(),
                availableServices = emptyList(),
                serviceLoad = emptyMap(),
                i2pCapabilities = null,
                i2pTunnelInfo = null,
                storageInfo = null,
                computeInfo = null,
                lastUpdated = timestamp,
                sequenceNumber = 1L
            )
        )
    }
    
    /**
     * Create an emergency broadcast message
     */
    fun createEmergencyBroadcast(
        sourceNodeId: String,
        message: String,
        priority: MessagePriority = MessagePriority.EMERGENCY
    ): EnhancedGossipMessage {
        return EnhancedGossipMessage(
            messageType = GossipMessageType.EMERGENCY_BROADCAST,
            sourceNodeId = sourceNodeId,
            originatorNodeId = sourceNodeId,
            priority = priority,
            ttl = 10, // Higher TTL for emergency messages
            payload = NodeStatePayload(
                nodeId = sourceNodeId,
                nodeType = NodeType.SMARTPHONE,
                deviceInfo = getCurrentDeviceInfo(),
                meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
                fitnessScore = 0.0f,
                centralityScore = 0.0f,
                connectionQuality = 0.0f,
                networkLatency = NetworkLatencyInfo(),
                resourceCapabilities = ResourceCapabilities(
                    availableCPU = 0.0f,
                    availableRAM = 0L,
                    availableBandwidth = 0L,
                    storageOffered = 0L,
                    batteryLevel = 0,
                    thermalThrottling = true,
                    powerState = PowerState.BATTERY_CRITICAL,
                    networkInterfaces = emptySet()
                ),
                batteryInfo = BatteryInfo(
                    level = 0,
                    isCharging = false,
                    estimatedTimeRemaining = null,
                    temperatureCelsius = 100,
                    health = BatteryHealth.POOR,
                    chargingSource = null
                ),
                thermalState = ThermalState.CRITICAL,
                connectedPeers = emptyMap(),
                gatewayCapabilities = GatewayCapabilities(),
                routingCapabilities = RoutingCapabilities(),
                availableServices = emptyList(),
                serviceLoad = emptyMap(),
                i2pCapabilities = null,
                i2pTunnelInfo = null,
                storageInfo = null,
                computeInfo = null,
                lastUpdated = System.currentTimeMillis(),
                sequenceNumber = 1L
            )
        )
    }
    
    /**
     * Create a network metrics message
     */
    fun createNetworkMetrics(
        nodeId: String,
        metrics: Map<String, Float>,
        priority: MessagePriority = MessagePriority.LOW
    ): EnhancedGossipMessage {
        return EnhancedGossipMessage(
            messageType = GossipMessageType.NETWORK_METRICS,
            sourceNodeId = nodeId,
            originatorNodeId = nodeId,
            priority = priority,
            payload = NodeStatePayload(
                nodeId = nodeId,
                nodeType = NodeType.SMARTPHONE,
                deviceInfo = getCurrentDeviceInfo(),
                meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
                fitnessScore = metrics["fitness"] ?: 0.5f,
                centralityScore = metrics["centrality"] ?: 0.0f,
                connectionQuality = metrics["connectionQuality"] ?: 1.0f,
                networkLatency = NetworkLatencyInfo(metrics["latency"]?.toLong() ?: 0L),
                resourceCapabilities = ResourceCapabilities(
                    availableCPU = metrics["cpu"] ?: 0.5f,
                    availableRAM = (metrics["ram"] ?: 0.5f).toLong(),
                    availableBandwidth = (metrics["bandwidth"] ?: 0.5f).toLong(),
                    storageOffered = (metrics["storage"] ?: 0.5f).toLong(),
                    batteryLevel = (metrics["battery"] ?: 0.5f).toInt(),
                    thermalThrottling = metrics["thermalThrottling"] ?: 0.0f > 0.8f,
                    powerState = PowerState.BATTERY_HIGH,
                    networkInterfaces = emptySet()
                ),
                batteryInfo = BatteryInfo(
                    level = (metrics["battery"] ?: 0.5f).toInt(),
                    isCharging = false,
                    estimatedTimeRemaining = null,
                    temperatureCelsius = (metrics["temperature"] ?: 25.0f).toInt(),
                    health = BatteryHealth.GOOD,
                    chargingSource = null
                ),
                thermalState = ThermalState.COOL,
                connectedPeers = emptyMap(),
                gatewayCapabilities = GatewayCapabilities(),
                routingCapabilities = RoutingCapabilities(),
                availableServices = emptyList(),
                serviceLoad = emptyMap(),
                i2pCapabilities = null,
                i2pTunnelInfo = null,
                storageInfo = null,
                computeInfo = null,
                lastUpdated = System.currentTimeMillis(),
                sequenceNumber = 1L
            )
        )
    }
    
    /**
     * Get default device information for testing and basic usage
     */
    private fun getCurrentDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            androidVersion = "13",
            apiLevel = 33,
            totalRAM = 4_000_000_000L,
            cpuCores = 8,
            cpuArchitecture = "arm64-v8a",
            availableStorage = 50_000_000_000L,
            hasGPS = true,
            hasCellular = true
        )
    }
}

// Extension function for creating messages with default values
fun createSimpleNodeAnnouncement(
    nodeId: String,
    fitnessScore: Float = 0.5f,
    centralityScore: Float = 0.0f
): EnhancedGossipMessage {
    return EnhancedGossipMessageFactory.createNodeAnnouncement(
        nodeId = nodeId,
        nodeType = NodeType.SMARTPHONE,
        deviceInfo = DeviceInfo(
            androidVersion = "13",
            apiLevel = 33,
            totalRAM = 4_000_000_000L,
            cpuCores = 8,
            cpuArchitecture = "arm64-v8a",
            availableStorage = 50_000_000_000L,
            hasGPS = true,
            hasCellular = true
        ),
        meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
        fitnessScore = fitnessScore,
        centralityScore = centralityScore,
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
}
