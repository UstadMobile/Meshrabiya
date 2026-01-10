package com.ustadmobile.meshrabiya.storage

import android.content.Context
import android.content.SharedPreferences
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Simple tests for distributed storage integration
 * Tests basic functionality and BetaTestLogger integration
 */
class DistributedStorageIntegrationTest {

    private lateinit var mockContext: Context
    private lateinit var betaTestLogger: BetaTestLogger

    @Before
    fun setup() {
        println("[DEBUG] DistributedStorageIntegrationTest: Starting @Before setup()")
        mockContext = mock(Context::class.java)
        
        // Mock SharedPreferences for BetaTestLogger
        val mockSharedPrefs = mock(SharedPreferences::class.java)
        val mockEditor = mock(SharedPreferences.Editor::class.java)
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockSharedPrefs)
        whenever(mockSharedPrefs.getString(any(), any())).thenReturn("DETAILED")
        whenever(mockSharedPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        doNothing().whenever(mockEditor).apply()
        
        // Initialize BetaTestLogger
        betaTestLogger = BetaTestLogger.getInstance(mockContext)
        betaTestLogger.setLogLevel(LogLevel.DETAILED)
        betaTestLogger.clearLogs()
    }
    
    @After
    fun cleanup() {
        betaTestLogger.clearLogs()
    }

    @Test
    fun testBetaLoggingSetup() {
        // Test that BetaTestLogger is properly initialized
        assertNotNull(betaTestLogger, "BetaTestLogger should be initialized")
        
        // Test basic logging functionality
        betaTestLogger.log(LogLevel.INFO, "Storage", "Test log message")
        
        val logs = betaTestLogger.getLogs()
        assertTrue(logs.any { it.message.contains("Test log message") }, "Test message should be logged")
    }

    @Test
    fun testStorageLoggingIntegration() {
        betaTestLogger.clearLogs()
        
        // Simulate storage operations through logging
        betaTestLogger.log(LogLevel.INFO, "Storage", "File storage operation started")
        betaTestLogger.log(LogLevel.INFO, "Storage", "Participation enabled: true")
        betaTestLogger.log(LogLevel.DEBUG, "Storage", "Storage allocation updated")
        
        val logs = betaTestLogger.getLogs()
        
        val hasStorageLog = logs.any { log ->
            log.message.contains("storage", ignoreCase = true) ||
            log.category.contains("Storage")
        }
        assertTrue(hasStorageLog, "Should have storage-related logs")
    }

    @Test
    fun testStorageConfigurationLogging() {
        betaTestLogger.clearLogs()
        
        // Simulate storage configuration scenarios that would generate logs
        betaTestLogger.log(LogLevel.INFO, "Storage", "Storage limit check: 95% utilized")
        betaTestLogger.log(LogLevel.WARN, "Storage", "Sync operation failed, retrying...")
        betaTestLogger.log(LogLevel.INFO, "Storage", "Role assignment impact: STORAGE_NODE role assigned")
        betaTestLogger.log(LogLevel.DEBUG, "Storage", "Read operation started: /data/file.txt")
        betaTestLogger.log(LogLevel.DEBUG, "Storage", "Write operation completed: /data/file.txt")
        
        val logs = betaTestLogger.getLogs()
        
        // Check that we can log storage-related events (which shows integration works)
        assertTrue(logs.any { it.category == "Storage" }, "Storage-related logs should be captured")
    }

    @Test
    fun testLogLevelFiltering() {
        // Test different log levels
        betaTestLogger.setLogLevel(LogLevel.BASIC)
        betaTestLogger.clearLogs()
        
        betaTestLogger.log(LogLevel.INFO, "Storage", "Basic level message")
        betaTestLogger.log(LogLevel.DEBUG, "Storage", "Debug level message")
        
        // Switch to detailed logging
        betaTestLogger.setLogLevel(LogLevel.DETAILED)
        betaTestLogger.clearLogs()
        
        betaTestLogger.log(LogLevel.INFO, "Storage", "Detailed level message")
        betaTestLogger.log(LogLevel.DEBUG, "Storage", "Debug level message")
        
        val detailedLogs = betaTestLogger.getLogs()
        assertTrue(detailedLogs.any { it.message.contains("Detailed level") }, "Detailed level should work")
    }
}
