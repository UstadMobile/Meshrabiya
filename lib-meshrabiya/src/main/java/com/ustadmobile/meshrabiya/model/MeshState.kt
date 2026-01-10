package com.ustadmobile.meshrabiya.model

/**
 * Represents the current state of the mesh network.
 */
enum class MeshState {
    INITIALIZING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
    UNKNOWN
}