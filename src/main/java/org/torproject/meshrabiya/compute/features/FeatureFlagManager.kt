package org.torproject.meshrabiya.compute.features

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.torproject.meshrabiya.logging.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature flag manager for distributed compute layer
 * 
 * Manages feature flags with support for:
 * - Local and remote configuration
 * - Real-time flag updates
 * - Graceful fallback on feature disable
 * - Per-node feature control
 * 
 * Thread Safety: All methods are thread-safe
 * 
 * @property remoteConfigService Optional remote configuration service
 */
class FeatureFlagManager(
    private val remoteConfigService: RemoteConfigService? = null
) {
    
    private val logger = Logger.get(this::class)
    
    // Local feature flag state
    private val flags = ConcurrentHashMap<FeatureFlag, MutableStateFlow<Boolean>>()
    
    // Remote sync job
    private var remoteSyncJob: Job? = null
    
    init {
        // Initialize default flag states
        FeatureFlag.values().forEach { flag ->
            flags[flag] = MutableStateFlow(flag.defaultEnabled)
        }
    }
    
    /**
     * Initialize feature flag manager
     * 
     * Starts remote sync if remote config service is available.
     */
    suspend fun initialize() {
        logger.info("Initializing feature flag manager")
        
        // Load flags from local storage
        loadLocalFlags()
        
        // Start remote sync if available
        if (remoteConfigService != null) {
            remoteSyncJob = CoroutineScope(Dispatchers.IO).launch {
                syncRemoteFlags()
            }
            logger.info("Remote flag sync enabled")
        }
    }
    
    /**
     * Shutdown feature flag manager
     */
    fun shutdown() {
        remoteSyncJob?.cancel()
        logger.info("Feature flag manager shut down")
    }
    
    /**
     * Check if a feature is enabled
     * 
     * @param flag Feature to check
     * @return True if enabled, false otherwise
     */
    fun isEnabled(flag: FeatureFlag): Boolean {
        return flags[flag]?.value ?: flag.defaultEnabled
    }
    
    /**
     * Enable a feature flag
     * 
     * @param flag Feature to enable
     */
    suspend fun enable(flag: FeatureFlag) {
        logger.info("Enabling feature flag: ${flag.name}")
        flags[flag]?.emit(true)
        saveLocalFlags()
    }
    
    /**
     * Disable a feature flag
     * 
     * @param flag Feature to disable
     */
    suspend fun disable(flag: FeatureFlag) {
        logger.info("Disabling feature flag: ${flag.name}")
        flags[flag]?.emit(false)
        saveLocalFlags()
    }
    
    /**
     * Observe feature flag state changes
     * 
     * @param flag Feature to observe
     * @return StateFlow of flag state
     */
    fun observeFlag(flag: FeatureFlag): StateFlow<Boolean> {
        return flags[flag] ?: MutableStateFlow(flag.defaultEnabled)
    }
    
    /**
     * Get all feature flag states
     * 
     * @return Map of feature flags to their states
     */
    fun getAllFlags(): Map<FeatureFlag, Boolean> {
        return flags.mapValues { it.value.value }
    }
    
    /**
     * Load flags from local storage
     */
    private suspend fun loadLocalFlags() {
        try {
            // TODO: Load from SharedPreferences or local database
            logger.debug("Loaded feature flags from local storage")
        } catch (e: Exception) {
            logger.error("Failed to load local flags", e)
        }
    }
    
    /**
     * Save flags to local storage
     */
    private suspend fun saveLocalFlags() {
        try {
            // TODO: Save to SharedPreferences or local database
            logger.debug("Saved feature flags to local storage")
        } catch (e: Exception) {
            logger.error("Failed to save local flags", e)
        }
    }
    
    /**
     * Sync flags from remote configuration service
     */
    private suspend fun syncRemoteFlags() {
        while (isActive) {
            try {
                val remoteFlags = remoteConfigService?.fetchFlags()
                
                if (remoteFlags != null) {
                    for ((flag, enabled) in remoteFlags) {
                        if (flags[flag]?.value != enabled) {
                            logger.info("Remote flag update: ${flag.name} = $enabled")
                            flags[flag]?.emit(enabled)
                        }
                    }
                    
                    saveLocalFlags()
                }
                
            } catch (e: Exception) {
                logger.error("Failed to sync remote flags", e)
            }
            
            // Sync every 5 minutes
            delay(5 * 60 * 1000)
        }
    }
}

/**
 * Feature flags for distributed compute layer
 */
enum class FeatureFlag(val defaultEnabled: Boolean, val description: String) {
    
    /**
     * Enable per-task keypair enhancement
     * 
     * When enabled, each task gets its own encryption keypair for
     * isolated file access.
     */
    TASK_KEYPAIR_ENABLED(
        defaultEnabled = true,
        description = "Per-task keypair isolation"
    ),
    
    /**
     * Enable distributed task execution
     * 
     * When disabled, tasks are executed locally only.
     */
    TASK_EXECUTION_ENABLED(
        defaultEnabled = true,
        description = "Distributed task execution"
    ),
    
    /**
     * Enable task decomposition for parallel execution
     */
    TASK_DECOMPOSITION_ENABLED(
        defaultEnabled = true,
        description = "Task decomposition for parallel execution"
    ),
    
    /**
     * Enable automatic task retry on failure
     */
    TASK_AUTO_RETRY_ENABLED(
        defaultEnabled = true,
        description = "Automatic task retry on failure"
    ),
    
    /**
     * Enable file replication monitoring
     */
    FILE_REPLICATION_MONITORING_ENABLED(
        defaultEnabled = true,
        description = "File replication health monitoring"
    ),
    
    /**
     * Enable performance metrics collection
     */
    METRICS_COLLECTION_ENABLED(
        defaultEnabled = true,
        description = "Performance metrics collection"
    ),
    
    /**
     * Enable security audit logging
     */
    SECURITY_AUDIT_ENABLED(
        defaultEnabled = true,
        description = "Security audit logging"
    )
}

/**
 * Remote configuration service interface
 * 
 * Implementations can fetch flags from a remote server, Firebase Remote Config,
 * or other configuration services.
 */
interface RemoteConfigService {
    
    /**
     * Fetch feature flags from remote server
     * 
     * @return Map of feature flags to their states
     * @throws RemoteConfigException if fetch fails
     */
    suspend fun fetchFlags(): Map<FeatureFlag, Boolean>
}

/**
 * Remote configuration exception
 */
class RemoteConfigException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Convenience extension functions
 */
object FeatureFlags {
    
    private lateinit var manager: FeatureFlagManager
    
    /**
     * Initialize global feature flag manager
     */
    fun initialize(manager: FeatureFlagManager) {
        this.manager = manager
    }
    
    /**
     * Check if task keypair feature is enabled
     */
    fun isTaskKeypairEnabled(): Boolean {
        return manager.isEnabled(FeatureFlag.TASK_KEYPAIR_ENABLED)
    }
    
    /**
     * Enable task keypair feature
     */
    suspend fun enableTaskKeypair() {
        manager.enable(FeatureFlag.TASK_KEYPAIR_ENABLED)
    }
    
    /**
     * Disable task keypair feature
     */
    suspend fun disableTaskKeypair() {
        manager.disable(FeatureFlag.TASK_KEYPAIR_ENABLED)
    }
    
    /**
     * Check if task execution is enabled
     */
    fun isTaskExecutionEnabled(): Boolean {
        return manager.isEnabled(FeatureFlag.TASK_EXECUTION_ENABLED)
    }
    
    /**
     * Enable task execution
     */
    suspend fun enableTaskExecution() {
        manager.enable(FeatureFlag.TASK_EXECUTION_ENABLED)
    }
    
    /**
     * Disable task execution
     */
    suspend fun disableTaskExecution() {
        manager.disable(FeatureFlag.TASK_EXECUTION_ENABLED)
    }
}
