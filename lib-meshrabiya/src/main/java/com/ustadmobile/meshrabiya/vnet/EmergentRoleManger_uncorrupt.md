package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.vnet.hardware.DeviceCapabilityManager
import com.ustadmobile.meshrabiya.vnet.hardware.AndroidDeviceCapabilityManager
import com.ustadmobile.meshrabiya.mmcp.MeshRole
import com.ustadmobile.meshrabiya.mmcp.ResourceCapabilities
import com.ustadmobile.meshrabiya.mmcp.BatteryInfo
import com.ustadmobile.meshrabiya.mmcp.ThermalState
import com.ustadmobile.meshrabiya.mmcp.PowerState
import com.ustadmobile.meshrabiya.mmcp.MmcpGatewayAnnouncement
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Data class capturing comprehensive node capabilities for role assignment
 */
data class NodeCapabilitySnapshot(
    val nodeId: String,
    val resources: ResourceCapabilities,
    val batteryInfo: BatteryInfo,
    val thermalState: ThermalState,
    val networkQuality: Float, // 0.0-1.0
    val stability: Float, // 0.0-1.0 based on uptime/connectivity history
    val timestamp: Long = System.currentTimeMillis()
) {
    fun hasStableConnection(): Boolean = networkQuality > 0.7f && stability > 0.6f
    
    val availableCPU: Float get() = resources.availableCPU
    val storageOffered: Long get() = resources.storageOffered
    val batteryLevel: Int get() = batteryInfo.level
    val isCharging: Boolean get() = batteryInfo.isCharging
}

/**
 * Device capabilities for role assignment calculation
 */
data class DeviceCapabilities(
    val storageAvailable: Long,
    val processingPower: Float, // 0.0-1.0
    val batteryInfo: BatteryInfo,
    val thermalState: ThermalState,
    val networkQuality: Float, // 0.0-1.0
    val stability: Float // 0.0-1.0
)

/**
 * Global mesh intelligence for informed role decisions
 */
data class MeshIntelligence(
    val totalNodes: Int,
    val activeGateways: Int,
    val activeStorageNodes: Int,
    val activeComputeNodes: Int,
    val networkLoad: Float, // 0.0-1.0
    val storageUtilization: Float, // 0.0-1.0
    val computeUtilization: Float, // 0.0-1.0
    val timestamp: Long = System.currentTimeMillis()
) {
    val needsMoreGateways: Boolean get() = activeGateways < (totalNodes * 0.2f) || networkLoad > 0.8f
    val needsMoreStorage: Boolean get() = activeStorageNodes < (totalNodes * 0.3f) || storageUtilization > 0.8f
    val needsMoreCompute: Boolean get() = activeComputeNodes < (totalNodes * 0.25f) || computeUtilization > 0.8f
}

/**
 * Represents a planned transition in roles
 */
data class RoleTransition(
    val toAdd: Set<MeshRole>,
    val toRemove: Set<MeshRole>
)

/**
 * Complete plan for role transitions with timing and fallbacks
 */
data class RoleTransitionPlan(
    val addRoles: Set<MeshRole>,
    val removeRoles: Set<MeshRole>,
    val transitionDeadline: Long,
    val fallbackNodes: Map<MeshRole, List<String>>
)

/**
 * Enhanced emergent role manager that builds on the existing MeshRoleManager
 * Uses global mesh intelligence for smart, decentralized role assignment
 */
class EmergentRoleManager(
    private val virtualNode: VirtualNode,
    private val context: Context,
    private val meshRoleManager: MeshRoleManager,
    private val meshTrafficRouter: Any? = null, // Accept any traffic router for integration
    private val distributedStorageManager: Any? = null, // Accept storage manager for integration
    private val deviceCapabilityManager: DeviceCapabilityManager? = null // Hardware metrics collector
) {
    private val logger = try { BetaTestLogger.getInstance(context) } catch (e: Exception) { null }
    
    // Initialize hardware capability manager if not provided
    private val hardwareManager: DeviceCapabilityManager by lazy {
        deviceCapabilityManager ?: AndroidDeviceCapabilityManager(context, logger ?: BetaTestLogger.getInstance(context))
    }
    
    private fun safeLog(level: LogLevel, message: String, throwable: Throwable? = null) {
        try {
            logger?.log(level, message, throwable)
        } catch (e: Exception) {
            // Ignore logging errors in test environment
        }
    }
    
    private val _currentMeshRoles = MutableStateFlow<Set<MeshRole>>(setOf(MeshRole.MESH_PARTICIPANT))
    val currentMeshRoles: StateFlow<Set<MeshRole>> = _currentMeshRoles.asStateFlow()
    
    private val _meshIntelligence = MutableStateFlow(
        MeshIntelligence(
            totalNodes = 1,
            activeGateways = 0,
            activeStorageNodes = 0,
            activeComputeNodes = 0,
            networkLoad = 0.0f,
            storageUtilization = 0.0f,
            computeUtilization = 0.0f
        )
    )
    val meshIntelligence: StateFlow<MeshIntelligence> = _meshIntelligence.asStateFlow()

    private val _isRoleTransitionInProgress = MutableStateFlow(false)
    val isRoleTransitionInProgress: StateFlow<Boolean> = _isRoleTransitionInProgress.asStateFlow()

    private val _preferredRoles = MutableStateFlow<Set<MeshRole>>(emptySet())

    /**
     * Main entry point: determine optimal roles based on capabilities and mesh needs
     */
    fun determineOptimalRoles(
        nodeCapabilities: NodeCapabilitySnapshot = getCurrentCapabilities(),
        meshIntelligence: MeshIntelligence = this.meshIntelligence.value,
        currentRoles: Set<MeshRole> = currentMeshRoles.value
    ): RoleTransitionPlan {
        
        val targetRoles = calculateTargetRoles(nodeCapabilities, meshIntelligence)
        val transitions = planGracefulTransitions(currentRoles, targetRoles)
        
        return RoleTransitionPlan(
            addRoles = transitions.toAdd,
            removeRoles = transitions.toRemove,
            transitionDeadline = calculateTransitionTime(transitions),
            fallbackNodes = identifyFallbackNodes(meshIntelligence, transitions.toRemove)
        )
    }
    
    /**
     * Core algorithm: calculate target roles based on fitness and mesh needs
     */
    private fun calculateTargetRoles(
        node: NodeCapabilitySnapshot, 
        mesh: MeshIntelligence
    ): Set<MeshRole> {
        val roles = mutableSetOf<MeshRole>()
        val userPreferences = _preferredRoles.value
        
        // Base participation - everyone gets this
        roles.add(MeshRole.MESH_PARTICIPANT)
        
        // Calculate normalized fitness score (0.0-1.0)
        val fitness = calculateNormalizedFitness(node)
        
        safeLog(LogLevel.DEBUG, "Node fitness: $fitness, Mesh needs: gateways=${mesh.needsMoreGateways}, storage=${mesh.needsMoreStorage}, compute=${mesh.needsMoreCompute}")
        safeLog(LogLevel.DEBUG, "User preferences: $userPreferences")
        
        // Gateway roles (exclusive - pick one based on capabilities and preferences)
        if (node.hasStableConnection() && fitness > 0.8 && mesh.needsMoreGateways) {
            val gatewayRole = selectBestGatewayRole(node, mesh, userPreferences)
            roles.add(gatewayRole)
            safeLog(LogLevel.INFO, "Assigned gateway role: $gatewayRole")
        }
        
        // Storage role (additive) - consider user preference
        if (node.storageOffered > 1_000_000L && // At least 1MB offered
            fitness > 0.4 && 
            mesh.needsMoreStorage &&
            node.thermalState !in setOf(ThermalState.THROTTLING, ThermalState.CRITICAL) &&
            (userPreferences.isEmpty() || MeshRole.STORAGE_NODE in userPreferences)) {
            roles.add(MeshRole.STORAGE_NODE)
            safeLog(LogLevel.INFO, "Assigned storage role")
        }
        
        // Compute role (additive, but consider thermal state, battery, and preferences)
        if (node.availableCPU > 0.3f && 
            node.thermalState !in setOf(ThermalState.THROTTLING, ThermalState.CRITICAL) && 
            (node.isCharging || node.batteryLevel > 30) &&
            mesh.needsMoreCompute &&
            (userPreferences.isEmpty() || MeshRole.COMPUTE_NODE in userPreferences)) {
            roles.add(MeshRole.COMPUTE_NODE)
            safeLog(LogLevel.INFO, "Assigned compute role")
        }
        
        // Router roles based on connectivity
        if (fitness > 0.6 && virtualNode.neighbors().size >= 2) {
            roles.add(MeshRole.MESH_ROUTER)
            safeLog(LogLevel.INFO, "Assigned router role")
        }
        
        // Coordinator role for highly connected, stable nodes
        if (fitness > 0.85 && 
            node.hasStableConnection() && 
            virtualNode.neighbors().size >= 3 &&
            (userPreferences.isEmpty() || MeshRole.COORDINATOR in userPreferences)) {
            roles.add(MeshRole.COORDINATOR)
            safeLog(LogLevel.INFO, "Assigned coordinator role")
        }
        
        return roles
    }
    
    /**
     * Select the best gateway role based on node capabilities, mesh needs, and user preferences
     */
    private fun selectBestGatewayRole(
        node: NodeCapabilitySnapshot, 
        mesh: MeshIntelligence,
        userPreferences: Set<MeshRole>
    ): MeshRole {
        val gatewayRoles = setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
        val preferredGateways = userPreferences.intersect(gatewayRoles)
        
        // If user has gateway preferences, honor them first
        if (preferredGateways.isNotEmpty()) {
            return preferredGateways.first()
        }
        
        // Otherwise, use capability-based selection
        return when {
            !meshRoleManager.userAllowsTorProxy && node.resources.availableBandwidth > 10_000_000L -> MeshRole.CLEARNET_GATEWAY // >10Mbps when Tor not allowed
            meshRoleManager.userAllowsTorProxy -> MeshRole.TOR_GATEWAY
            node.resources.availableBandwidth > 10_000_000L -> MeshRole.CLEARNET_GATEWAY // >10Mbps fallback
            else -> MeshRole.TOR_GATEWAY // Default to Tor for privacy
        }
    }
    
    /**
     * Calculate normalized fitness score (0.0-1.0) from node capabilities
     */
    private fun calculateNormalizedFitness(node: NodeCapabilitySnapshot): Float {
        val batteryScore = when {
            node.isCharging -> 1.0f
            node.batteryLevel > 70 -> 0.9f
            node.batteryLevel > 30 -> 0.6f
            else -> 0.3f
        }
        
        val thermalScore = when (node.thermalState) {
            ThermalState.COOL -> 1.0f
            ThermalState.WARM -> 0.8f
            ThermalState.HOT -> 0.5f
            ThermalState.THROTTLING -> 0.2f
            ThermalState.CRITICAL -> 0.1f
        }
        
        val connectivityScore = node.networkQuality
        val stabilityScore = node.stability
        
        // Weighted combination
        return (batteryScore * 0.3f + 
                thermalScore * 0.2f + 
                connectivityScore * 0.3f + 
                stabilityScore * 0.2f).coerceIn(0.0f, 1.0f)
    }
    
    /**
     * Plan graceful transitions between role sets
     */
    private fun planGracefulTransitions(
        currentRoles: Set<MeshRole>, 
        targetRoles: Set<MeshRole>
    ): RoleTransition {
        val toAdd = targetRoles - currentRoles
        val toRemove = currentRoles - targetRoles
        
        // Filter out roles that would cause service disruption
        val safeToRemove = toRemove.filter { role ->
            when (role) {
                MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY -> {
                    // Only remove gateway roles if there are other gateways
                    meshIntelligence.value.activeGateways > 1
                }
                MeshRole.COORDINATOR -> {
                    // Always safe to remove coordinator role
                    true
                }
                else -> true
            }
        }.toSet()
        
        return RoleTransition(toAdd, safeToRemove)
    }
    
    /**
     * Calculate appropriate transition timing
     */
    private fun calculateTransitionTime(transitions: RoleTransition): Long {
        val baseDelay = when {
            transitions.toRemove.any { it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY) } -> {
                5.minutes.inWholeMilliseconds // Give more time for gateway transitions
            }
            transitions.toRemove.isNotEmpty() -> {
                2.minutes.inWholeMilliseconds // Standard transition time
            }
            else -> {
                30_000L // Quick addition of new roles
            }
        }
        return System.currentTimeMillis() + baseDelay
    }
    
    /**
     * Identify fallback nodes for critical roles
     */
    private fun identifyFallbackNodes(
        mesh: MeshIntelligence, 
        rolesToRemove: Set<MeshRole>
    ): Map<MeshRole, List<String>> {
        // This would query the mesh for other capable nodes
        // For now, return empty as this requires mesh-wide coordination
        return emptyMap()
    }
    
    /**
     * Get current node capabilities including real hardware metrics and dynamic storage information
     */
    private fun getCurrentCapabilities(): NodeCapabilitySnapshot {
        return try {
            // Use real hardware metrics if available
            val nodeId = virtualNode.addressAsInt.toString()
            val snapshot = runBlocking { 
                hardwareManager.getCapabilitySnapshot(nodeId) 
            }
            
            // Enhance with storage information from DistributedStorageManager
            val storageOffered = calculateAvailableStorage()
            val enhancedResources = snapshot.resources.copy(
                storageOffered = maxOf(snapshot.resources.storageOffered, storageOffered)
            )
            
            val enhancedSnapshot = snapshot.copy(resources = enhancedResources)
            
            logger?.log(LogLevel.INFO, "EmergentRoleManager", 
                "Hardware capabilities: CPU=${(enhancedSnapshot.resources.availableCPU * 100).toInt()}% available, " +
                "Battery=${enhancedSnapshot.batteryInfo.level}%, " +
                "Storage=${enhancedSnapshot.resources.storageOffered / (1024 * 1024)}MB offered, " +
                "Thermal=${enhancedSnapshot.thermalState}, " +
                "Stability=${(enhancedSnapshot.stability * 100).toInt()}%")
            
            enhancedSnapshot
            
        } catch (e: Exception) {
            // Fallback to legacy implementation if hardware manager fails
            logger?.log(LogLevel.BASIC, "EmergentRoleManager", 
                "Hardware metrics unavailable, using fallback: ${e.message}")
            
            val fitnessScore = meshRoleManager.calculateFitnessScore()
            val storageOffered = calculateAvailableStorage()
            
            val resources = ResourceCapabilities(
                availableCPU = 0.5f, // Fallback: assume moderate CPU availability
                availableRAM = Runtime.getRuntime().freeMemory(),
                availableBandwidth = 10_000_000L, // Fallback: assume 10 Mbps
                storageOffered = storageOffered,
                batteryLevel = fitnessScore.batteryLevel.toInt().coerceIn(0, 100),
                thermalThrottling = false, // Fallback: assume no throttling
                powerState = if (fitnessScore.batteryLevel > 0.7f) PowerState.BATTERY_HIGH else PowerState.BATTERY_MEDIUM,
                networkInterfaces = emptySet() // Fallback: no interface info
            )
            
            val batteryInfo = BatteryInfo(
                level = fitnessScore.batteryLevel.toInt().coerceIn(0, 100),
                isCharging = false, // Fallback: assume not charging
                estimatedTimeRemaining = null,
                temperatureCelsius = 25, // Fallback: room temperature
                health = com.ustadmobile.meshrabiya.mmcp.BatteryHealth.GOOD,
                chargingSource = null
            )
            
            NodeCapabilitySnapshot(
                nodeId = virtualNode.addressAsInt.toString(),
                resources = resources,
                batteryInfo = batteryInfo,
                thermalState = ThermalState.COOL, // Fallback: assume cool
                networkQuality = (fitnessScore.signalStrength / 100.0f).coerceIn(0.0f, 1.0f),
                stability = 0.8f // Fallback: assume good stability
            )
        }
    }
    
    /**
     * Calculate available storage based on user participation settings
     */
    private fun calculateAvailableStorage(): Long {
        return try {
            // Try to get storage capabilities from DistributedStorageManager if available
            distributedStorageManager?.let { storageManager ->
                val getStorageCapabilitiesMethod = storageManager.javaClass.getMethod("getStorageCapabilities")
                val capabilities = getStorageCapabilitiesMethod.invoke(storageManager)
                
                // Get totalOffered field using reflection
                val totalOfferedField = capabilities.javaClass.getDeclaredField("totalOffered")
                totalOfferedField.isAccessible = true
                totalOfferedField.getLong(capabilities)
            } ?: 100_000_000L // Default 100MB if no storage manager
        } catch (e: Exception) {
            safeLog(LogLevel.DEBUG, "Could not access DistributedStorageManager, using default storage value")
            100_000_000L // Fallback value
        }
    }
    
    /**
     * Create device capabilities with dynamic storage calculation
     */
    private fun createDeviceCapabilities(batteryInfo: BatteryInfo, fitnessScore: FitnessScore): DeviceCapabilities {
        return DeviceCapabilities(
            storageAvailable = calculateAvailableStorage(),
            processingPower = (fitnessScore.batteryLevel / 100.0f).coerceAtMost(1.0f),
            batteryInfo = batteryInfo,
            thermalState = ThermalState.COOL, // TODO: Get from thermal API
            networkQuality = (fitnessScore.signalStrength.toFloat() / 100.0f).coerceIn(0.0f, 1.0f),
            stability = 0.8f // TODO: Calculate from uptime/connectivity history
        )
    }
    
    /**
     * Update mesh intelligence from gossip messages
     */
    fun updateMeshIntelligence(intelligence: MeshIntelligence) {
        _meshIntelligence.value = intelligence
        safeLog(LogLevel.DEBUG, "Updated mesh intelligence: $intelligence")
    }
    
    /**
     * Process received node announcement to update mesh intelligence
     */
    fun processNodeAnnouncement(nodeId: String, meshRoles: Set<MeshRole>) {
        val current = _meshIntelligence.value
        
        // Count active roles
        val activeGateways = if (meshRoles.any { it in setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY) }) {
            current.activeGateways + 1
        } else {
            current.activeGateways
        }
        
        val activeStorageNodes = if (MeshRole.STORAGE_NODE in meshRoles) {
            current.activeStorageNodes + 1
        } else {
            current.activeStorageNodes
        }
        
        val activeComputeNodes = if (MeshRole.COMPUTE_NODE in meshRoles) {
            current.activeComputeNodes + 1
        } else {
            current.activeComputeNodes
        }
        
        val totalNodes = virtualNode.neighbors().size + 1 // Include self
        
        val updated = current.copy(
            totalNodes = totalNodes,
            activeGateways = activeGateways.coerceAtMost(totalNodes),
            activeStorageNodes = activeStorageNodes.coerceAtMost(totalNodes),
            activeComputeNodes = activeComputeNodes.coerceAtMost(totalNodes),
            networkLoad = estimateNetworkLoad(),
            storageUtilization = estimateStorageUtilization(),
            computeUtilization = estimateComputeUtilization()
        )
        
        _meshIntelligence.value = updated
        safeLog(LogLevel.DEBUG, "Updated mesh intelligence from node $nodeId: $updated")
    }
    
    private fun estimateNetworkLoad(): Float {
        // TODO: Implement actual network load estimation
        return 0.3f
    }
    
    private fun estimateStorageUtilization(): Float {
        // TODO: Implement actual storage utilization estimation
        return 0.2f
    }
    
    private fun estimateComputeUtilization(): Float {
        // TODO: Implement actual compute utilization estimation
        return 0.1f
    }
    
    /**
     * Apply a role transition plan
     */
    fun applyTransitionPlan(plan: RoleTransitionPlan) {
        val currentRoles = _currentMeshRoles.value.toMutableSet()
        
        // Add new roles
        currentRoles.addAll(plan.addRoles)
        
        // Remove old roles
        currentRoles.removeAll(plan.removeRoles)
        
        _currentMeshRoles.value = currentRoles
        
        // Handle gateway role transitions
        handleGatewayRoleTransitions(plan.addRoles, plan.removeRoles)
        
        safeLog(LogLevel.INFO, "Applied role transition: +${plan.addRoles}, -${plan.removeRoles}")
        safeLog(LogLevel.INFO, "Current roles: $currentRoles")
    }
    
    /**
     * Handle gateway role transitions and configure traffic routing
     */
    private fun handleGatewayRoleTransitions(addedRoles: Set<MeshRole>, removedRoles: Set<MeshRole>) {
        try {
            // Check for gateway role additions
            when {
                MeshRole.TOR_GATEWAY in addedRoles -> {
                    safeLog(LogLevel.INFO, "EmergentRole: Activating Tor gateway routing")
                    activateGatewayRouting(GatewayMode.TOR_GATEWAY)
                    CoroutineScope(Dispatchers.IO).launch { announceGatewayCapability() }
                }
                MeshRole.CLEARNET_GATEWAY in addedRoles -> {
                    safeLog(LogLevel.INFO, "EmergentRole: Activating clearnet gateway routing")
                    activateGatewayRouting(GatewayMode.CLEARNET_GATEWAY)
                    CoroutineScope(Dispatchers.IO).launch { announceGatewayCapability() }
                }
            }
            
            // Check for gateway role removals
            val gatewayRolesRemoved = removedRoles.intersect(
                setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY, MeshRole.I2P_GATEWAY)
            )
            
            if (gatewayRolesRemoved.isNotEmpty()) {
                safeLog(LogLevel.INFO, "EmergentRole: Deactivating gateway routing")
                deactivateGatewayRouting()
            }
            
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "EmergentRole: Failed to handle gateway role transitions: ${e.message}")
        }
    }
    
    /**
     * Activate gateway routing based on mode
     */
    private fun activateGatewayRouting(mode: GatewayMode) {
        try {
            // Use reflection to call methods on the traffic router to avoid compile-time dependencies
            meshTrafficRouter?.let { router ->
                try {
                    when (mode) {
                        GatewayMode.TOR_GATEWAY -> {
                            val enableMethod = router.javaClass.getMethod("enableGatewayRouting", Any::class.java)
                            val routingModeClass = Class.forName("com.ustadmobile.orbotmeshrabiyaintegration.MeshTrafficRouter\$RoutingMode")
                            val torOnlyMode = routingModeClass.enumConstants?.find { it.toString() == "TOR_ONLY" }
                            enableMethod.invoke(router, torOnlyMode)
                            safeLog(LogLevel.INFO, "Tor gateway routing activated via MeshTrafficRouter")
                        }
                        GatewayMode.CLEARNET_GATEWAY -> {
                            val enableMethod = router.javaClass.getMethod("enableGatewayRouting", Any::class.java)
                            val routingModeClass = Class.forName("com.ustadmobile.orbotmeshrabiyaintegration.MeshTrafficRouter\$RoutingMode")
                            val clearnetMode = routingModeClass.enumConstants?.find { it.toString() == "CLEARNET_DIRECT" }
                            enableMethod.invoke(router, clearnetMode)
                            safeLog(LogLevel.INFO, "Clearnet gateway routing activated via MeshTrafficRouter")
                        }
                        GatewayMode.NONE -> {
                            // No action needed
                        }
                    }
                } catch (reflectionException: Exception) {
                    safeLog(LogLevel.WARN, "Failed to activate gateway routing via reflection: ${reflectionException.message}")
                    // Fall back to basic logging
                    safeLog(LogLevel.INFO, "Gateway routing requested: $mode (MeshTrafficRouter not available)")
                }
            } ?: run {
                safeLog(LogLevel.INFO, "Gateway routing requested: $mode (no traffic router provided)")
            }
            
            // Also enable gateway handling in AndroidVirtualNode if available
            if (virtualNode is AndroidVirtualNode) {
                val androidNode = virtualNode as AndroidVirtualNode
                // The AndroidVirtualNode should work with MeshTrafficRouter automatically
                safeLog(LogLevel.DEBUG, "EmergentRole: AndroidVirtualNode gateway integration active")
            }
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "EmergentRole: Failed to activate gateway routing: ${e.message}")
        }
    }
    
    /**
     * Deactivate gateway routing
     */
    private fun deactivateGatewayRouting() {
        try {
            meshTrafficRouter?.let { router ->
                try {
                    val disableMethod = router.javaClass.getMethod("disableGatewayRouting")
                    disableMethod.invoke(router)
                    safeLog(LogLevel.INFO, "Gateway routing deactivated via MeshTrafficRouter")
                } catch (reflectionException: Exception) {
                    safeLog(LogLevel.WARN, "Failed to deactivate gateway routing via reflection: ${reflectionException.message}")
                    // Fall back to basic logging
                    safeLog(LogLevel.INFO, "Gateway routing deactivation requested (MeshTrafficRouter not available)")
                }
            } ?: run {
                safeLog(LogLevel.INFO, "Gateway routing deactivation requested (no traffic router provided)")
            }
            
            if (virtualNode is AndroidVirtualNode) {
                val androidNode = virtualNode as AndroidVirtualNode
                safeLog(LogLevel.DEBUG, "AndroidVirtualNode gateway integration deactivated")
            }
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "Failed to deactivate gateway routing: ${e.message}", e)
        }
    }
    
    /**
     * Announce gateway capability to the mesh network
     * Implements comprehensive logging consistent with project standards
     */
    private suspend fun announceGatewayCapability() {
        val startTime = System.currentTimeMillis()
        
        try {
            safeLog(LogLevel.DETAILED, "Starting gateway capability announcement")
            
            // Determine gateway type based on available protocols
            val gatewayType = when {
                hasI2PSupport() -> {
                    safeLog(LogLevel.DETAILED, "I2P support detected, using I2P gateway")
                    MmcpGatewayAnnouncement.GatewayType.I2P
                }
                hasTorSupport() -> {
                    safeLog(LogLevel.DETAILED, "Tor support detected, using Tor gateway")
                    MmcpGatewayAnnouncement.GatewayType.TOR
                }
                else -> {
                    safeLog(LogLevel.DETAILED, "Using clearnet gateway as fallback")
                    MmcpGatewayAnnouncement.GatewayType.CLEARNET
                }
            }
            
            // Estimate network capacity with performance tracking
            val capacityStartTime = System.currentTimeMillis()
            val bandwidthCapacity = estimateNetworkCapacity()
            val capacityTime = System.currentTimeMillis() - capacityStartTime
            
            safeLog(LogLevel.DETAILED, 
                "Network capacity estimated in ${capacityTime}ms: " +
                "upload=${bandwidthCapacity.uploadMbps}Mbps, " +
                "download=${bandwidthCapacity.downloadMbps}Mbps")
            
            // Measure network latency with performance tracking
            val latencyStartTime = System.currentTimeMillis()
            val networkLatency = measureNetworkLatency()
            val latencyTime = System.currentTimeMillis() - latencyStartTime
            
            safeLog(LogLevel.DETAILED,
                "Network latency measured in ${latencyTime}ms: " +
                "ping=${networkLatency.averageMs}ms, jitter=${networkLatency.jitterMs}ms")
            
            // Get supported protocols for gateway type
            val supportedProtocols = getSupportedProtocols(gatewayType)
            safeLog(LogLevel.DETAILED, 
                "Supported protocols for $gatewayType: ${supportedProtocols.joinToString(", ")}")
            
            // Create gateway announcement message
            val announcement = MmcpGatewayAnnouncement(
                nodeId = virtualNode.addressAsInt.toString(),
                gatewayType = gatewayType,
                capacity = bandwidthCapacity,
                latency = networkLatency,
                isActive = true,
                supportedProtocols = supportedProtocols,
                requestedMessageId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            )
            
            // Performance tracking for serialization
            val serializationStartTime = System.currentTimeMillis()
            val serializedSize = announcement.toBytes().size
            val serializationTime = System.currentTimeMillis() - serializationStartTime
            
            safeLog(LogLevel.DETAILED,
                "Gateway announcement serialized in ${serializationTime}ms, size=${serializedSize} bytes")
            
            // Broadcast announcement to mesh network - using generic send method
            try {
                // Convert announcement to bytes and send as MMCP message
                virtualNode.sendMessage(announcement)
                val totalTime = System.currentTimeMillis() - startTime
                safeLog(LogLevel.INFO, "Gateway capability announced successfully - " +
                    "gatewayType: $gatewayType, " +
                    "protocols: ${supportedProtocols.joinToString(",")}, " +
                    "uploadBandwidth: ${bandwidthCapacity.uploadMbps}Mbps, " +
                    "downloadBandwidth: ${bandwidthCapacity.downloadMbps}Mbps, " +
                    "latency: ${networkLatency.averageMs}ms, " +
                    "totalTimeMs: $totalTime")
            } catch (e: Exception) {
                safeLog(LogLevel.WARN, "Failed to broadcast gateway announcement: ${e.message}", e)
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            safeLog(LogLevel.ERROR, "Failed to announce gateway capability: ${e.message}", e)
        }
    }
    
    /**
     * Estimate current network capacity based on device capabilities
     * Implements performance tracking consistent with project standards
     */
    private suspend fun estimateNetworkCapacity(): MmcpGatewayAnnouncement.BandwidthCapacity {
        val startTime = System.currentTimeMillis()
        
        try {
            // Get estimated bandwidth from device capability manager
            val estimatedBandwidth = deviceCapabilityManager?.getEstimatedBandwidth() ?: 1000000L // 1MB/s default
            
            // Convert to upload/download estimates based on typical ratios
            val uploadBytesPerSecond = (estimatedBandwidth * 0.1).toLong() // 10% of total for upload
            val downloadBytesPerSecond = (estimatedBandwidth * 0.9).toLong() // 90% of total for download
            
            val endTime = System.currentTimeMillis()
            
            safeLog(LogLevel.DETAILED,
                "Network capacity estimation completed in ${endTime - startTime}ms - " +
                "totalBandwidth: $estimatedBandwidth, uploadBps: $uploadBytesPerSecond, downloadBps: $downloadBytesPerSecond")
            
            return MmcpGatewayAnnouncement.BandwidthCapacity(
                uploadMbps = uploadBytesPerSecond / 1_000_000f,
                downloadMbps = downloadBytesPerSecond / 1_000_000f
            )
            
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            
            safeLog(LogLevel.WARN,
                "Failed to estimate network capacity, using defaults: ${e.message}", e)
            
            // Return reasonable defaults
            return MmcpGatewayAnnouncement.BandwidthCapacity(
                uploadMbps = 1f,   // 1 Mbps default
                downloadMbps = 10f  // 10 Mbps default
            )
        }
    }
    
    /**
     * Measure current network latency with comprehensive performance tracking
     */
    private suspend fun measureNetworkLatency(): MmcpGatewayAnnouncement.NetworkLatency {
        val startTime = System.currentTimeMillis()
        
        try {
            // Simulate latency measurement - in real implementation this would ping external hosts
            val networkInterfaces = deviceCapabilityManager?.getNetworkInterfaces() ?: emptyList()
            
            // Calculate estimated latency based on network interface types
            val averagePingMs = when {
                networkInterfaces.any { it.displayName?.contains("wifi", ignoreCase = true) == true } -> {
                    safeLog(LogLevel.DETAILED, "WiFi interface detected, using WiFi latency estimate")
                    30 // WiFi typical latency
                }
                networkInterfaces.any { it.displayName?.contains("mobile", ignoreCase = true) == true } -> {
                    safeLog(LogLevel.DETAILED, "Mobile interface detected, using mobile latency estimate") 
                    80 // Mobile typical latency
                }
                else -> {
                    safeLog(LogLevel.DETAILED, "Unknown interface, using default latency estimate")
                    50 // Default latency
                }
            }
            
            val jitterMs = averagePingMs / 10 // Estimate jitter as 10% of average ping
            
            val endTime = System.currentTimeMillis()
            
            safeLog(LogLevel.DETAILED,
                "Network latency measurement completed in ${endTime - startTime}ms - " +
                "averagePingMs: $averagePingMs, jitterMs: $jitterMs, interfaceCount: ${networkInterfaces.size}")
            
            return MmcpGatewayAnnouncement.NetworkLatency(
                averageMs = averagePingMs,
                jitterMs = jitterMs
            )
            
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            
            safeLog(LogLevel.WARN,
                "Failed to measure network latency, using defaults: ${e.message}", e)
            
            // Return reasonable defaults
            return MmcpGatewayAnnouncement.NetworkLatency(
                averageMs = 100,
                jitterMs = 10
            )
        }
    }
    
    /**
     * Check if I2P support is available
     */
    private fun hasI2PSupport(): Boolean {
        return try {
            // Check for I2P service availability
            val hasSupport = false // TODO: Implement actual I2P service check
            safeLog(LogLevel.DETAILED, "I2P support check: $hasSupport")
            hasSupport
        } catch (e: Exception) {
            safeLog(LogLevel.WARN, "I2P support check failed: ${e.message}", e)
            false
        }
    }
    
    /**
     * Check if Tor support is available  
     */
    private fun hasTorSupport(): Boolean {
        return try {
            // Check for Tor service availability
            val hasSupport = true // Default to true for demo purposes
            safeLog(LogLevel.DETAILED, "Tor support check: $hasSupport")
            hasSupport
        } catch (e: Exception) {
            safeLog(LogLevel.WARN, "Tor support check failed: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get supported protocols for gateway type
     */
    private fun getSupportedProtocols(gatewayType: MmcpGatewayAnnouncement.GatewayType): Set<String> {
        return when (gatewayType) {
            MmcpGatewayAnnouncement.GatewayType.CLEARNET -> setOf("HTTP", "HTTPS", "DNS", "FTP")
            MmcpGatewayAnnouncement.GatewayType.TOR -> setOf("HTTP", "HTTPS", "SOCKS5")
            MmcpGatewayAnnouncement.GatewayType.I2P -> setOf("HTTP", "HTTPS", "I2P")
        }
    }
    
    /**
     * Generate unique message ID
     */
    private fun generateMessageId(): Int {
        return (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    }
    
    // Enums for gateway types and modes
    enum class GatewayMode {
        NONE,
        CLEARNET_GATEWAY,
        TOR_GATEWAY
    }
    
    enum class GatewayType {
        CLEARNET,
        TOR,
        I2P
    }
    
    /**
     * Main update function - call this periodically to reassess roles
     */
    fun updateRoles() {
        try {
            _isRoleTransitionInProgress.value = true
            
            val plan = determineOptimalRoles()
            
            if (plan.addRoles.isNotEmpty() || plan.removeRoles.isNotEmpty()) {
                safeLog(LogLevel.INFO, "Role transition needed: +${plan.addRoles}, -${plan.removeRoles}")
                applyTransitionPlan(plan)
            }
            
            // Also update the legacy role manager
            meshRoleManager.updateRole()
            
        } catch (e: Exception) {
            safeLog(LogLevel.ERROR, "Error updating roles: ${e.message}")
        } finally {
            _isRoleTransitionInProgress.value = false
        }
    }

    // Accessor methods for UI integration
    fun getCurrentMeshRoles(): Set<MeshRole> = _currentMeshRoles.value
    
    fun getMeshIntelligence(): MeshIntelligence = _meshIntelligence.value
    
    fun isRoleTransitionInProgress(): Boolean = _isRoleTransitionInProgress.value
    
    fun setPreferredRoles(roles: Set<MeshRole>) {
        _preferredRoles.value = roles
        safeLog(LogLevel.INFO, "User set preferred roles: $roles")
    }
    
    fun getPreferredRoles(): Set<MeshRole> = _preferredRoles.value
    
    // Hardware monitoring lifecycle management
    
    /**
     * Start hardware monitoring for real-time capability updates
     * Call this when the EmergentRoleManager becomes active
     */
    fun startHardwareMonitoring() {
        try {
            hardwareManager.startMonitoring(30000L) // 30 second intervals
            safeLog(LogLevel.INFO, "Started hardware monitoring for role optimization")
        } catch (e: Exception) {
            safeLog(LogLevel.BASIC, "Failed to start hardware monitoring: ${e.message}")
        }
    }
    
    /**
     * Stop hardware monitoring to conserve battery
     * Call this when the EmergentRoleManager is no longer needed
     */
    fun stopHardwareMonitoring() {
        try {
            hardwareManager.stopMonitoring()
            safeLog(LogLevel.INFO, "Stopped hardware monitoring")
        } catch (e: Exception) {
            safeLog(LogLevel.BASIC, "Failed to stop hardware monitoring: ${e.message}")
        }
    }
    
    /**
     * Check if hardware monitoring is currently active
     */
    fun isHardwareMonitoring(): Boolean = hardwareManager.isMonitoring()
    
    /**
     * Get current device capabilities snapshot for debugging/UI display
     */
    fun getDeviceCapabilities(): NodeCapabilitySnapshot? {
        return try {
            runBlocking { 
                hardwareManager.getCapabilitySnapshot(virtualNode.addressAsInt.toString()) 
            }
        } catch (e: Exception) {
            safeLog(LogLevel.BASIC, "Failed to get device capabilities: ${e.message}")
            null
        }
    }
}