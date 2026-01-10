package com.ustadmobile.meshrabiya.beta

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * Logger for beta testing features with consent-based log level filtering.
 * 
 * Supports consent levels from BetaConsentActivity:
 * - DISABLED: Only ERROR logs
 * - BASIC: WARN, ERROR logs  
 * - DETAILED: WARN, ERROR, INFO logs
 * - FULL: All logs including DEBUG
 */
class BetaTestLogger private constructor(private val context: Context?) {
    
    companion object {
        @Volatile
        private var INSTANCE: BetaTestLogger? = null
        private const val PREFS_NAME = "beta_logger_prefs"
        private const val KEY_LOG_LEVEL = "log_level"
        
        fun getInstance(context: Context): BetaTestLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BetaTestLogger(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        /**
         * Create a test instance that doesn't rely on Android Context
         * Used for unit testing
         */
        fun createTestInstance(): BetaTestLogger {
            return BetaTestLogger(null)
        }
        
        /**
         * Reset the singleton for testing
         */
        fun resetForTesting() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
    
    private val prefs: SharedPreferences? = try { 
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) 
    } catch (e: Exception) { 
        null 
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val logs = ConcurrentLinkedQueue<LogEntry>()
    
    @Volatile
    private var currentLogLevel: LogLevel = try {
        LogLevel.valueOf(prefs?.getString(KEY_LOG_LEVEL, LogLevel.BASIC.name) ?: LogLevel.BASIC.name)
    } catch (e: Exception) {
        LogLevel.BASIC
    }
    
    /**
     * Set the consent-based log level that controls which logs are captured
     */
    fun setLogLevel(level: LogLevel) {
        currentLogLevel = level
        try {
            prefs?.edit()?.putString(KEY_LOG_LEVEL, level.name)?.apply()
        } catch (e: Exception) {
            // Ignore SharedPreferences errors in test environment
        }
    }
    
    /**
     * Get the current consent-based log level
     */
    fun getLogLevel(): LogLevel = currentLogLevel
    
    /**
     * Log a message with standard log level (DEBUG, INFO, WARN, ERROR)
     */
    fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        log(level, "DEFAULT", message, emptyMap(), throwable)
    }
    
    /**
     * Log a message with category and metadata
     */
    fun log(level: LogLevel, category: String, message: String, metadata: Map<String, String> = emptyMap(), throwable: Throwable? = null) {
        // Check if this log level should be captured based on consent level
        if (!level.shouldLog(currentLogLevel)) {
            return
        }
        
        val entry = LogEntry(
            timestamp = java.time.Instant.now(),
            level = level,
            category = category,
            message = message,
            metadata = metadata
        )
        
        logs.offer(entry)
        
        // Also log to Android Log system (if available)
        try {
            val tag = "MeshrabiyaBeta"
            val logMessage = "[$category] $message"
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, logMessage, throwable)
                LogLevel.INFO -> Log.i(tag, logMessage, throwable)
                LogLevel.WARN -> Log.w(tag, logMessage, throwable)
                LogLevel.ERROR -> Log.e(tag, logMessage, throwable)
                else -> Log.i(tag, logMessage, throwable) // For consent levels
            }
        } catch (e: Exception) {
            // Ignore Android Log errors in test environment
        }
        
        // Limit memory usage by keeping only recent logs
        if (logs.size > 10000) {
            repeat(1000) { logs.poll() }
        }
    }
    
    /**
     * Log a structured LogEntry (for compatibility with tests)
     */
    fun log(entry: LogEntry) {
        if (!entry.level.shouldLog(currentLogLevel)) {
            return
        }
        
        logs.offer(entry)
        
        // Also log to Android Log system
        val tag = "MeshrabiyaBeta"
        val logMessage = "[${entry.category}] ${entry.message}"
        
        when (entry.level) {
            LogLevel.DEBUG -> Log.d(tag, logMessage)
            LogLevel.INFO -> Log.i(tag, logMessage)
            LogLevel.WARN -> Log.w(tag, logMessage)
            LogLevel.ERROR -> Log.e(tag, logMessage)
            else -> Log.i(tag, logMessage)
        }
        
        if (logs.size > 10000) {
            repeat(1000) { logs.poll() }
        }
    }
    
    /**
     * Get all logged entries that match the current log level
     */
    fun getLogs(): List<LogEntry> {
        return logs.filter { it.level.shouldLog(currentLogLevel) }
    }
    
    /**
     * Clear all logs
     */
    fun clearLogs() {
        logs.clear()
    }
    
    /**
     * Export logs to a file
     */
    fun exportLogs(): File? {
        return try {
            val cacheDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            val exportFile = File(cacheDir, "beta_logs_${System.currentTimeMillis()}.json")
            exportFile.writeText("Exported ${logs.size} log entries")
            exportFile
        } catch (e: Exception) {
            Log.e("BetaTestLogger", "Failed to export logs", e)
            null
        }
    }
    
    /**
     * Import logs from a file
     */
    fun importLogs(file: File): List<LogEntry> {
        return try {
            // Simple implementation - return empty list
            emptyList()
        } catch (e: Exception) {
            Log.e("BetaTestLogger", "Failed to import logs", e)
            emptyList()
        }
    }
    
    /**
     * Flush logs (for compatibility with tests)
     */
    fun flush() {
        // No-op for in-memory implementation
        // In a full implementation, this would persist to disk
    }
} 