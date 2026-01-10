package com.ustadmobile.meshrabiya.beta

import java.io.Serializable

/**
 * Data class representing a log entry in the beta testing system.
 * Used by BetaTestLogger for structured logging with consent-based filtering.
 */
data class LogEntry(
    val timestamp: java.time.Instant,
    val level: LogLevel,
    val category: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap()
) : Serializable {
    
    /**
     * Secondary constructor for backwards compatibility with long timestamp
     */
    constructor(
        timestamp: Long,
        level: LogLevel,
        category: String,
        message: String,
        metadata: Map<String, String> = emptyMap()
    ) : this(
        timestamp = java.time.Instant.ofEpochMilli(timestamp),
        level = level,
        category = category,
        message = message,
        metadata = metadata
    )
    
    /**
     * Get timestamp as milliseconds for backwards compatibility
     */
    fun getTimestampMillis(): Long = timestamp.toEpochMilli()
}
