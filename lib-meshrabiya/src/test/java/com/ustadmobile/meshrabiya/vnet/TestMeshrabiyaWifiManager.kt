package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Test implementation of MeshrabiyaWifiManager that simulates realistic WiFi behavior
 * without requiring Android framework components. This preserves the testing value
 * by simulating real networking scenarios like latency, connection failures, etc.
 */
class TestMeshrabiyaWifiManager(
    private val simulateLatency: Boolean = true,
    private val simulateFailures: Boolean = true,
    private val baseLatencyMs: Long = 50L,
    private val failureRate: Float = 0.1f // 10% failure rate
) : MeshrabiyaWifiManager {
    
    private val _state = MutableStateFlow(MeshrabiyaWifiState())
    private val requestCounter = AtomicInteger(0)
    private val totalRequests = AtomicLong(0)
    private val failedRequests = AtomicLong(0)
    
    override val state: Flow<MeshrabiyaWifiState> = _state
    override val is5GhzSupported: Boolean = true
    
    // Simulate realistic hotspot behavior
    override suspend fun requestHotspot(
        requestMessageId: Int, 
        request: LocalHotspotRequest
    ): LocalHotspotResponse {
        totalRequests.incrementAndGet()
        
        // Simulate network latency
        if (simulateLatency) {
            val latency = baseLatencyMs + (Math.random() * baseLatencyMs).toLong()
            delay(latency)
        }
        
        // Simulate occasional failures based on failure rate
        val shouldFail = simulateFailures && Math.random() < failureRate
        
        return if (shouldFail) {
            failedRequests.incrementAndGet()
            LocalHotspotResponse(
                responseToMessageId = requestMessageId,
                errorCode = -1, // Simulate error
                config = null,
                redirectAddr = 0
            )
        } else {
            LocalHotspotResponse(
                responseToMessageId = requestMessageId,
                errorCode = 0,
                config = null, // In real tests, this would be a valid config
                redirectAddr = 0
            )
        }
    }
    
    override suspend fun deactivateHotspot() {
        if (simulateLatency) {
            delay(baseLatencyMs / 2)
        }
    }
    
    override suspend fun connectToHotspot(config: WifiConnectConfig, timeout: Long) {
        if (simulateLatency) {
            val connectionTime = baseLatencyMs * 2 + (Math.random() * baseLatencyMs).toLong()
            delay(connectionTime)
        }
        
        // Simulate connection success/failure
        if (simulateFailures && Math.random() < failureRate) {
            throw RuntimeException("Simulated connection failure")
        }
    }
    
    // Test metrics for validation
    fun getTestMetrics(): TestMetrics = TestMetrics(
        totalRequests = totalRequests.get(),
        failedRequests = failedRequests.get(),
        successRate = if (totalRequests.get() > 0) {
            (totalRequests.get() - failedRequests.get()).toDouble() / totalRequests.get()
        } else 0.0
    )
    
    // Simulate specific network conditions
    fun simulateNetworkCondition(condition: NetworkCondition) {
        when (condition) {
            NetworkCondition.HIGH_LATENCY -> {
                // State updates to reflect high latency condition
            }
            NetworkCondition.POOR_SIGNAL -> {
                // State updates to reflect poor signal
            }
            NetworkCondition.NETWORK_CONGESTION -> {
                // State updates to reflect congestion
            }
        }
    }
    
    data class TestMetrics(
        val totalRequests: Long,
        val failedRequests: Long,
        val successRate: Double
    )
    
    enum class NetworkCondition {
        HIGH_LATENCY,
        POOR_SIGNAL,
        NETWORK_CONGESTION
    }
}
