package com.ustadmobile.meshrabiya.mmcp

/**
 * MeshRole enum for mesh node roles, including ML_SERVER for ML-capable nodes.
 */
enum class MeshRole {
    // Gateway Roles
    TOR_GATEWAY,
    CLEARNET_GATEWAY,
    I2P_GATEWAY,

    // Router Roles
    I2P_ROUTER,
    TOR_RELAY,
    MESH_ROUTER,

    // Service Roles
    STORAGE_NODE,
    COMPUTE_NODE,
    COORDINATOR,

    // Specialized Roles
    SEEDING_SERVICE,
    EXECUTION_PLANNER,
    SERVICE_REGISTRY,

    // ML server role
    ML_SERVER,

    // Base Role
    MESH_PARTICIPANT
}
