package com.ustadmobile.meshrabiya.beta

import android.content.Context
import android.content.SharedPreferences
import org.junit.Test
import org.junit.Before
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Integration tests demonstrating BetaTestLogger consent-based filtering
 * as requested for integration with BetaConsentActivity
 */
class BetaLoggerIntegrationTest {

    private lateinit var mockContext: Context
    private lateinit var betaTestLogger: BetaTestLogger

    @Before
    fun setup() {
        println("[DEBUG] BetaLoggerIntegrationTest: Starting @Before setup()")
        mockContext = mock(Context::class.java)
        
        // Mock SharedPreferences for BetaTestLogger
        val mockSharedPrefs = mock(SharedPreferences::class.java)
        val mockEditor = mock(SharedPreferences.Editor::class.java)
        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockSharedPrefs)
        whenever(mockSharedPrefs.getString(any(), any())).thenReturn("DETAILED")
        whenever(mockSharedPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        doNothing().whenever(mockEditor).apply()
        
        betaTestLogger = BetaTestLogger.getInstance(mockContext)
    }

    @Test
    fun testConsentLevelDisabled_OnlyErrorLogsCapt() {
        // DISABLED: Only ERROR logs should be captured
        betaTestLogger.setLogLevel(LogLevel.DISABLED)
        betaTestLogger.clearLogs()
        
        // Log messages at different levels
        betaTestLogger.log(LogLevel.DEBUG, "Debug message - should be filtered")
        betaTestLogger.log(LogLevel.INFO, "Info message - should be filtered") 
        betaTestLogger.log(LogLevel.WARN, "Warning message - should be filtered")
        betaTestLogger.log(LogLevel.ERROR, "Error message - should be captured")
        
        val capturedLogs = betaTestLogger.getLogs()
        
        // Verify only ERROR logs are captured
        assertEquals(1, capturedLogs.size, "Should only capture 1 ERROR log")
        assertTrue(capturedLogs.all { it.level == LogLevel.ERROR }, 
            "DISABLED consent should only capture ERROR logs")
        assertTrue(capturedLogs.any { it.message.contains("Error message") },
            "Should contain the ERROR log")
    }

    @Test 
    fun testConsentLevelBasic_WarnAndErrorLogsCapt() {
        // BASIC: WARN and ERROR logs should be captured
        betaTestLogger.setLogLevel(LogLevel.BASIC)
        betaTestLogger.clearLogs()
        
        // Log messages at different levels
        betaTestLogger.log(LogLevel.DEBUG, "Debug message - should be filtered")
        betaTestLogger.log(LogLevel.INFO, "Info message - should be filtered")
        betaTestLogger.log(LogLevel.WARN, "Warning message - should be captured")
        betaTestLogger.log(LogLevel.ERROR, "Error message - should be captured")
        
        val capturedLogs = betaTestLogger.getLogs()
        val logLevels = capturedLogs.map { it.level }.toSet()
        
        // Verify only WARN and ERROR logs are captured
        assertEquals(2, capturedLogs.size, "Should capture 2 logs (WARN + ERROR)")
        assertTrue(logLevels == setOf(LogLevel.WARN, LogLevel.ERROR),
            "BASIC consent should only capture WARN/ERROR logs")
        assertFalse(logLevels.contains(LogLevel.DEBUG), "Should not contain DEBUG logs")
        assertFalse(logLevels.contains(LogLevel.INFO), "Should not contain INFO logs")
    }

    @Test
    fun testConsentLevelDetailed_WarnErrorInfoLogsCapt() {
        // DETAILED: WARN, ERROR, and INFO logs should be captured
        betaTestLogger.setLogLevel(LogLevel.DETAILED)
        betaTestLogger.clearLogs()
        
        // Log messages at different levels
        betaTestLogger.log(LogLevel.DEBUG, "Debug message - should be filtered")
        betaTestLogger.log(LogLevel.INFO, "Info message - should be captured")
        betaTestLogger.log(LogLevel.WARN, "Warning message - should be captured")
        betaTestLogger.log(LogLevel.ERROR, "Error message - should be captured")
        
        val capturedLogs = betaTestLogger.getLogs()
        val logLevels = capturedLogs.map { it.level }.toSet()
        
        // Verify WARN, ERROR, and INFO logs are captured
        assertEquals(3, capturedLogs.size, "Should capture 3 logs (INFO + WARN + ERROR)")
        assertTrue(logLevels == setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            "DETAILED consent should capture INFO/WARN/ERROR logs")
        assertFalse(logLevels.contains(LogLevel.DEBUG), "Should not contain DEBUG logs")
    }

    @Test
    fun testConsentLevelFull_AllLogsCapt() {
        // FULL: All logs including DEBUG should be captured
        betaTestLogger.setLogLevel(LogLevel.FULL)
        betaTestLogger.clearLogs()
        
        // Log messages at all levels
        betaTestLogger.log(LogLevel.DEBUG, "Debug message - should be captured")
        betaTestLogger.log(LogLevel.INFO, "Info message - should be captured")
        betaTestLogger.log(LogLevel.WARN, "Warning message - should be captured")
        betaTestLogger.log(LogLevel.ERROR, "Error message - should be captured")
        
        val capturedLogs = betaTestLogger.getLogs()
        val logLevels = capturedLogs.map { it.level }.toSet()
        
        // Verify all log levels are captured
        assertEquals(4, capturedLogs.size, "Should capture all 4 logs")
        assertTrue(logLevels.contains(LogLevel.DEBUG), "FULL consent should capture DEBUG logs")
        assertTrue(logLevels.contains(LogLevel.INFO), "FULL consent should capture INFO logs")
        assertTrue(logLevels.contains(LogLevel.WARN), "FULL consent should capture WARN logs")
        assertTrue(logLevels.contains(LogLevel.ERROR), "FULL consent should capture ERROR logs")
    }

    @Test
    fun testConsentLevelPersistence() {
        // Test that log level persists when getting new logger instances
        betaTestLogger.setLogLevel(LogLevel.BASIC)
        
        // Get a new instance - should have the same log level
        val newLoggerInstance = BetaTestLogger.getInstance(mockContext)
        assertEquals(LogLevel.BASIC, newLoggerInstance.getLogLevel(), 
            "Log level should persist across logger instances")
        
        // Change level in new instance
        newLoggerInstance.setLogLevel(LogLevel.FULL)
        
        // Original instance should also reflect the change
        assertEquals(LogLevel.FULL, betaTestLogger.getLogLevel(),
            "Log level changes should be reflected in all instances")
    }

    @Test
    fun testRealTimeFiltering() {
        // Test that logs are filtered immediately at capture time, not just at retrieval
        betaTestLogger.setLogLevel(LogLevel.BASIC)
        betaTestLogger.clearLogs()
        
        // Log messages at different levels
        betaTestLogger.log(LogLevel.DEBUG, "Debug - should not appear")
        betaTestLogger.log(LogLevel.INFO, "Info - should not appear")
        betaTestLogger.log(LogLevel.WARN, "Warning - should appear")
        betaTestLogger.log(LogLevel.ERROR, "Error - should appear")
        
        val logs = betaTestLogger.getLogs()
        
        // Should only have 2 logs (WARN and ERROR) - filtering happened at capture time
        assertEquals(2, logs.size, "Should only capture WARN and ERROR logs")
        assertTrue(logs.any { it.level == LogLevel.WARN && it.message.contains("Warning") },
            "Should contain the WARN log")
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("Error") },
            "Should contain the ERROR log")
    }

    @Test
    fun testConsentLevelChangeDuringExecution() {
        // Test that changing consent levels during operation affects subsequent logging
        betaTestLogger.setLogLevel(LogLevel.DISABLED)
        betaTestLogger.clearLogs()
        
        // Log during DISABLED - only ERROR should be captured
        betaTestLogger.log(LogLevel.INFO, "Info during DISABLED")
        betaTestLogger.log(LogLevel.ERROR, "Error during DISABLED")
        
        val disabledLogs = betaTestLogger.getLogs()
        assertTrue(disabledLogs.all { it.level == LogLevel.ERROR }, 
            "During DISABLED, should only capture ERROR logs")
        
        // Change to DETAILED - should capture INFO, WARN, ERROR
        betaTestLogger.setLogLevel(LogLevel.DETAILED)
        
        betaTestLogger.log(LogLevel.DEBUG, "Debug during DETAILED")
        betaTestLogger.log(LogLevel.INFO, "Info during DETAILED")
        betaTestLogger.log(LogLevel.WARN, "Warning during DETAILED")
        
        val detailedLogs = betaTestLogger.getLogs()
        val detailedLogLevels = detailedLogs.map { it.level }.toSet()
        
        assertTrue(detailedLogLevels.contains(LogLevel.INFO), "Should now capture INFO logs")
        assertTrue(detailedLogLevels.contains(LogLevel.WARN), "Should now capture WARN logs")
        assertTrue(detailedLogLevels.contains(LogLevel.ERROR), "Should still capture ERROR logs")
        assertFalse(detailedLogLevels.contains(LogLevel.DEBUG), "Should not capture DEBUG logs")
    }
}
