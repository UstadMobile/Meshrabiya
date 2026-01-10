package com.ustadmobile.meshrabiya.vnet

/**
 * Enum representing all possible mesh node roles in the Meshrabiya library.
 * This list is derived from all usages in EmergentRoleManager and related files.
 */
enum class MeshRole {
    MESH_PARTICIPANT,    // Base role for all mesh nodes
    STORAGE_NODE,        // Node offering distributed storage
    COMPUTE_NODE,        // Node offering compute resources
    MESH_ROUTER,         // Node routing mesh traffic
    TOR_GATEWAY,         // Node sharing Tor gateway
    CLEARNET_GATEWAY,    // Node sharing clearnet Internet gateway
    I2P_GATEWAY          // Node sharing I2P gateway
}