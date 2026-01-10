package com.ustadmobile.meshrabiya.service.compute

/**
 * Result of task execution.
 *
 * @property success Whether execution completed successfully
 * @property resultMessage Optional result message (JSON/XML/text)
 * @property resultMessageType Type of result message (json/xml/text)
 * @property outputFiles List of output file paths relative to task filesDir
 * @property executionTimeMs Time taken to execute in milliseconds
 * @property errorType Type of error if execution failed
 * @property errorMessage Detailed error message if execution failed
 */
data class ExecutionResult(
    val success: Boolean,
    val resultMessage: String? = null,
    val resultMessageType: String? = null,
    val outputFiles: List<String> = emptyList(),
    val executionTimeMs: Long,
    val errorType: ExecutionErrorType? = null,
    val errorMessage: String? = null
)

/**
 * Types of execution errors
 */
enum class ExecutionErrorType {
    /** Code bundle validation failed */
    INVALID_CODE_BUNDLE,
    
    /** Runtime execution error (exception, crash, etc.) */
    RUNTIME_ERROR,
    
    /** Timeout exceeded */
    TIMEOUT,
    
    /** Insufficient resources (memory, storage, etc.) */
    RESOURCE_EXHAUSTED,
    
    /** Sandbox security violation */
    SECURITY_VIOLATION,
    
    /** Unknown or unclassified error */
    UNKNOWN
}
