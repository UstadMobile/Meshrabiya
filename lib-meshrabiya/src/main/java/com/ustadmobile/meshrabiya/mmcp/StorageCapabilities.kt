package com.ustadmobile.meshrabiya.mmcp

/**
 * Storage capabilities for mesh nodes offering distributed storage.
 * 
 * Used by DeviceCapabilityManager to report storage metrics.
 * Replaces the incomplete MmcpStorageAdvertisement system with a
 * simpler, fitness-focused capability model.
 * 
 * Changes from deprecated version:
 * - REMOVED: currentlyUsed (not useful for client node selection)
 * - REMOVED: replicationFactor (handled by storage manager)
 * - REMOVED: accessPatterns (incorporated into fitness calculation)
 * - ADDED: localStorageAvailableMB (useful for client evaluations)
 */
data class StorageCapabilities(
    /**
     * Total storage space offered to mesh network (bytes)
     */
    val totalOffered: Long,
    
    /**
     * Available local storage space (MB)
     * More useful for client node evaluations than currentlyUsed
     */
    val localStorageAvailableMB: Long,
    
    /**
     * Whether this node supports compression
     */
    val compressionSupported: Boolean,
    
    /**
     * Whether this node supports encryption
     */
    val encryptionSupported: Boolean
    
    // REMOVED: replicationFactor - handled by DistributedStorageManager, not node capability
    // REMOVED: currentlyUsed - less useful than localStorageAvailableMB for client decisions
    // REMOVED: accessPatterns - I/O performance incorporated into fitness calculation
)
