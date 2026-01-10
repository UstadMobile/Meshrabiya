package com.ustadmobile.meshrabiya.service

/**
 * Access type for task data access control.
 * Defines what kind of access is being granted/updated for file permissions.
 */
enum class AccessType {
    /** Read-only access to file */
    READ,
    
    /** Write access to file */
    WRITE,
    
    /** Read and write access */
    READ_WRITE,
    
    /** No access (revoked) */
    NONE
}
