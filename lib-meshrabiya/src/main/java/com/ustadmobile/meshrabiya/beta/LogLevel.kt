package com.ustadmobile.meshrabiya.beta

/**
 * Log levels for beta testing with consent-based filtering.
 * 
 * Consent levels:
 * - DISABLED: Only ERROR logs
 * - BASIC: WARN, ERROR logs  
 * - DETAILED: WARN, ERROR, INFO logs
 * - FULL: All logs including DEBUG
 * 
 * Standard log levels for compatibility:
 * - DEBUG, INFO, WARN, ERROR
 */
enum class LogLevel(val priority: Int) {
    // Consent-based levels
    DISABLED(1),
    BASIC(2),
    DETAILED(3),
    FULL(4),
    
    // Standard log levels (mapped to Android Log priorities)
    DEBUG(10),
    INFO(20),
    WARN(30),
    ERROR(40);
    
    /**
     * Check if this log level should be captured given the current consent level
     */
    fun shouldLog(consentLevel: LogLevel): Boolean {
        return when (consentLevel) {
            DISABLED -> this == ERROR
            BASIC -> this in setOf(WARN, ERROR)
            DETAILED -> this in setOf(WARN, ERROR, INFO)
            FULL -> true // All logs
            else -> true // For standard levels, always log
        }
    }
} 