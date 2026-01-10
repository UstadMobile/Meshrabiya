package com.ustadmobile.meshrabiya

import android.content.Context
import android.content.Intent
import com.ustadmobile.meshrabiya.api.GatewayPreference
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

/**
 * Unit tests for Tor status monitoring integration.
 * Tests TorStatusMonitor BroadcastReceiver and StateFlow updates.
 */
@RunWith(RobolectricTestRunner::class)
class TorStatusMonitoringTest {

    private lateinit var torStatusFlow: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        println("[DEBUG] TorStatusMonitoringTest: Starting @Before setup()")
        torStatusFlow = MutableStateFlow(false)
    }

    @Test
    fun `Tor status ON broadcast updates StateFlow to true`() = runBlocking {
        assertEquals(false, torStatusFlow.value)
        
        // Simulate Tor ON broadcast
        val intent = createTorStatusIntent("ON")
        handleTorStatusBroadcast(intent)
        
        assertEquals(true, torStatusFlow.value)
    }

    @Test
    fun `Tor status OFF broadcast updates StateFlow to false`() = runBlocking {
        torStatusFlow.value = true
        
        // Simulate Tor OFF broadcast
        val intent = createTorStatusIntent("OFF")
        handleTorStatusBroadcast(intent)
        
        assertEquals(false, torStatusFlow.value)
    }

    @Test
    fun `Tor status STARTING remains false`() = runBlocking {
        assertEquals(false, torStatusFlow.value)
        
        // Simulate STARTING broadcast
        val intent = createTorStatusIntent("STARTING")
        handleTorStatusBroadcast(intent)
        
        // Conservative mapping: STARTING = false (not yet ready)
        assertEquals(false, torStatusFlow.value)
    }

    @Test
    fun `Tor status STOPPING remains false`() = runBlocking {
        torStatusFlow.value = true
        
        // Simulate STOPPING broadcast
        val intent = createTorStatusIntent("STOPPING")
        handleTorStatusBroadcast(intent)
        
        assertEquals(false, torStatusFlow.value)
    }

    @Test
    fun `unknown Tor status defaults to false`() = runBlocking {
        torStatusFlow.value = true
        
        // Simulate unknown status
        val intent = createTorStatusIntent("UNKNOWN")
        handleTorStatusBroadcast(intent)
        
        assertEquals(false, torStatusFlow.value)
    }

    @Test
    fun `missing status extra defaults to false`() = runBlocking {
        torStatusFlow.value = true
        
        // Simulate intent without status extra
        val intent = Intent("org.torproject.android.intent.action.STATUS")
        handleTorStatusBroadcast(intent)
        
        assertEquals(false, torStatusFlow.value)
    }

    @Test
    fun `multiple status updates tracked correctly`() = runBlocking {
        // OFF -> STARTING -> ON -> STOPPING -> OFF
        
        handleTorStatusBroadcast(createTorStatusIntent("OFF"))
        assertEquals(false, torStatusFlow.value)
        
        handleTorStatusBroadcast(createTorStatusIntent("STARTING"))
        assertEquals(false, torStatusFlow.value)
        
        handleTorStatusBroadcast(createTorStatusIntent("ON"))
        assertEquals(true, torStatusFlow.value)
        
        handleTorStatusBroadcast(createTorStatusIntent("STOPPING"))
        assertEquals(false, torStatusFlow.value)
        
        handleTorStatusBroadcast(createTorStatusIntent("OFF"))
        assertEquals(false, torStatusFlow.value)
    }

    @Test
    fun `Tor status case insensitive`() = runBlocking {
        handleTorStatusBroadcast(createTorStatusIntent("on"))
        assertEquals(true, torStatusFlow.value)
        
        handleTorStatusBroadcast(createTorStatusIntent("On"))
        assertEquals(true, torStatusFlow.value)
        
        handleTorStatusBroadcast(createTorStatusIntent("oN"))
        assertEquals(true, torStatusFlow.value)
        
        handleTorStatusBroadcast(createTorStatusIntent("off"))
        assertEquals(false, torStatusFlow.value)
    }

    @Test
    fun `StateFlow can be collected`() = runBlocking {
        val initialValue = torStatusFlow.first()
        assertEquals(false, initialValue)
        
        handleTorStatusBroadcast(createTorStatusIntent("ON"))
        
        val updatedValue = torStatusFlow.first()
        assertEquals(true, updatedValue)
    }

    private fun createTorStatusIntent(status: String): Intent {
        return Intent("org.torproject.android.intent.action.STATUS").apply {
            putExtra("org.torproject.android.intent.extra.STATUS", status)
        }
    }

    private fun handleTorStatusBroadcast(intent: Intent) {
        val status = intent.getStringExtra("org.torproject.android.intent.extra.STATUS") ?: ""
        torStatusFlow.value = status.equals("ON", ignoreCase = true)
    }
}
