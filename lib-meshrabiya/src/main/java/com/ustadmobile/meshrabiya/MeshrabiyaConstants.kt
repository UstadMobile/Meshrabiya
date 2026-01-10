
package com.ustadmobile.meshrabiya

import java.util.UUID
import com.ustadmobile.meshrabiya.service.compute.model.TaskType

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.ustadmobile.meshrabiya.storage.StorageAllocation

object MeshrabiyaConstants {
        /**
     * Returns the default (fixed) replica count for all files/chunks.
     */
    fun getDefaultReplicaCount(): Int = 4
    private const val DEFAULT_BROADCAST_TTL_MS = 60_000L
    fun getBroadcastTtlMs(): Long {
        return prefs?.getLong("broadcast_ttl_ms", DEFAULT_BROADCAST_TTL_MS) ?: DEFAULT_BROADCAST_TTL_MS
    }
    fun setBroadcastTtlMs(ttl: Long) {
        prefs?.edit()?.putLong("broadcast_ttl_ms", ttl)?.apply()
    }
    const val LOG_TAG = "Meshrabiya"
    const val VERSION = "0.1d11"
    val UUID_BUSY = UUID(0, 0)

    // --- Settings logic migrated from MeshSettings ---
    private var prefs: SharedPreferences? = null
    private const val KEY_DROP_FOLDER_PATH = "drop_folder_path"

    fun init(context: Context) {
        prefs = context.getSharedPreferences("mesh_settings", Context.MODE_PRIVATE)
    }

    fun getReplicaCount(): Int {
        return prefs?.getInt("replica_count", 3) ?: 3
    }

    fun setReplicaCount(count: Int) {
        prefs?.edit()?.putInt("replica_count", count)?.apply()
    }

    fun getTimeoutMs(): Long {
        return prefs?.getLong("timeout_ms", 5000L) ?: 5000L
    }

    fun setTimeoutMs(timeout: Long) {
        prefs?.edit()?.putLong("timeout_ms", timeout)?.apply()
    }

    fun getMaxRetries(): Int {
        return prefs?.getInt("max_retries", 3) ?: 3
    }

    fun setMaxRetries(retries: Int) {
        prefs?.edit()?.putInt("max_retries", retries)?.apply()
    }

    fun getChunkSizeKb(): Int = prefs?.getInt("chunk_size_kb", 256) ?: 256
    fun setChunkSizeKb(size: Int) = prefs?.edit()?.putInt("chunk_size_kb", size)?.apply()

    // If true, avoid splitting files into chunks when storing. Default: false
    fun getNoChunking(): Boolean = prefs?.getBoolean("no_chunking", false) ?: false
    fun setNoChunking(noChunking: Boolean) = prefs?.edit()?.putBoolean("no_chunking", noChunking)?.apply()

    // Replication configuration for distributed storage
    fun getMinimalReplicaCount(): Int = prefs?.getInt("minimal_replica_count", 1) ?: 1
    fun setMinimalReplicaCount(count: Int) = prefs?.edit()?.putInt("minimal_replica_count", count)?.apply()

    fun getStandardReplicaCount(): Int = prefs?.getInt("standard_replica_count", 3) ?: 3
    fun setStandardReplicaCount(count: Int) = prefs?.edit()?.putInt("standard_replica_count", count)?.apply()

    fun getHighReplicaCount(): Int = prefs?.getInt("high_replica_count", 5) ?: 5
    fun setHighReplicaCount(count: Int) = prefs?.edit()?.putInt("high_replica_count", count)?.apply()

    fun getCriticalReplicaCount(): Int = prefs?.getInt("critical_replica_count", 7) ?: 7
    fun setCriticalReplicaCount(count: Int) = prefs?.edit()?.putInt("critical_replica_count", count)?.apply()


    private const val DEFAULT_ECOSYSTEM_GOSSIP_PORT = 8647
    fun getEcosystemGossipPort(): Int {
        return prefs?.getInt("ecosystem_gossip_port", DEFAULT_ECOSYSTEM_GOSSIP_PORT) ?: DEFAULT_ECOSYSTEM_GOSSIP_PORT
    }

    fun setEcosystemGossipPort(port: Int) {
        prefs?.edit()?.putInt("ecosystem_gossip_port", port)?.apply()
    }

    private const val DEFAULT_CONNECTION_POOL_SIZE = 8

    fun getConnectionPoolSize(): Int {
        return prefs?.getInt("connection_pool_size", DEFAULT_CONNECTION_POOL_SIZE) ?: DEFAULT_CONNECTION_POOL_SIZE
    }

    fun setConnectionPoolSize(size: Int) {
        prefs?.edit()?.putInt("connection_pool_size", size)?.apply()
    }
    fun setDropFolderPath(path: String) {
        prefs?.edit()?.putString(KEY_DROP_FOLDER_PATH, path)?.apply()
    }

    fun getDropFolderPath(): String {
        return prefs?.getString(KEY_DROP_FOLDER_PATH, "") ?: ""
    }

    // Phase 1: Task completion retry constants
    const val TASK_COMPLETION_RETRY_PERIOD_MS = 30000L  // 30 seconds
    const val TASK_COMPLETION_RETRY_INTERVAL_MS = 5000L // 5 seconds

    // --- TaskType enablement persistence ---
    private const val ENABLED_TASK_TYPES_KEY = "enabled_task_types"

    /**
     * Returns whether the given TaskType is enabled (default: true if not set).
     */
    fun isTaskTypeEnabled(taskType: TaskType): Boolean {
        val enabledMap = getAllTaskTypeEnabled()
        return enabledMap[taskType] ?: true
    }

    /**
     * Sets enabled status for a TaskType and persists the map.
     */
    fun setTaskTypeEnabled(taskType: TaskType, enabled: Boolean) {
        val enabledMap = getAllTaskTypeEnabled().toMutableMap()
        enabledMap[taskType] = enabled
        saveTaskTypeEnabledMap(enabledMap)
    }

    /**
     * Returns a map of TaskType to enabled status, auto-adapting to enum changes.
     */
    fun getAllTaskTypeEnabled(): Map<TaskType, Boolean> {
        val raw = prefs?.getString(ENABLED_TASK_TYPES_KEY, null)
        val result = mutableMapOf<TaskType, Boolean>()
        val allTypes = TaskType.values()
        if (raw != null) {
            raw.split(",").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val type = allTypes.find { it.name == parts[0] }
                    if (type != null) {
                        result[type] = parts[1] == "1"
                    }
                }
            }
        }
        // Add any new TaskTypes (default enabled)
        allTypes.forEach { type ->
            if (!result.containsKey(type)) result[type] = true
        }
        return result
    }

    /**
     * Persists the TaskType enabled map as a comma-separated string.
     */
    private fun saveTaskTypeEnabledMap(map: Map<TaskType, Boolean>) {
        val encoded = map.entries.joinToString(",") { "${it.key.name}:${if (it.value) "1" else "0"}" }
        prefs?.edit()?.putString(ENABLED_TASK_TYPES_KEY, encoded)?.apply()
    }

    // --- Compute Layer Participation (Global Enable/Disable) ---
    private const val COMPUTE_LAYER_ENABLED_KEY = "compute_layer_enabled"

    // --- User Identity Persistence ---
    private const val USER_ID_KEY = "user_id"
    private const val USER_PUBLIC_KEY_KEY = "user_public_key"
    private const val USER_NICKNAME_KEY = "nickname"

    fun getUserId(): String? = prefs?.getString(USER_ID_KEY, null)
    fun setUserId(id: String) { prefs?.edit()?.putString(USER_ID_KEY, id)?.apply() }

    fun getUserPublicKey(): String? = prefs?.getString(USER_PUBLIC_KEY_KEY, null)
    fun setUserPublicKey(pubKey: String) { prefs?.edit()?.putString(USER_PUBLIC_KEY_KEY, pubKey)?.apply() }

    fun getNickname(): String? = prefs?.getString(USER_NICKNAME_KEY, null)
    fun setNickname(nickname: String) { prefs?.edit()?.putString(USER_NICKNAME_KEY, nickname)?.apply() }

    /**
     * Returns whether the compute layer is enabled (default: true if not set).
     */
    fun isComputeLayerParticipating(): Boolean {
        return prefs?.getBoolean(COMPUTE_LAYER_ENABLED_KEY, true) ?: true
    }

    /**
     * Sets whether the compute layer is enabled (persistently).
     */
    fun setComputeLayerParticipatingEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(COMPUTE_LAYER_ENABLED_KEY, enabled)?.apply()
    }

    private const val STORAGE_ALLOCATIONS_KEY = "storage_allocations"

    fun setStorageAllocations(allocations: List<StorageAllocation>) {
        val json = Gson().toJson(allocations)
        prefs?.edit()?.putString(STORAGE_ALLOCATIONS_KEY, json)?.apply()
    }

    fun getStorageAllocations(): List<StorageAllocation> {
        val json = prefs?.getString(STORAGE_ALLOCATIONS_KEY, null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<StorageAllocation>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
}