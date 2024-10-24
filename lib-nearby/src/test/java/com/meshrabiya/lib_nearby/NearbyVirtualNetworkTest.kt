package com.meshrabiya.lib_nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.meshrabiya.lib_nearby.nearby.NearbyVirtualNetwork
import com.ustadmobile.meshrabiya.log.MNetLogger
import junit.framework.TestCase.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import java.net.InetAddress


@ExperimentalCoroutinesApi
class NearbyVirtualNetworkTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockConnectionsClient: ConnectionsClient

    @Mock
    private lateinit var mockLogger: MNetLogger

    private lateinit var nearbyVirtualNetwork: NearbyVirtualNetwork

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(Nearby.getConnectionsClient(mockContext)).thenReturn(mockConnectionsClient)

        nearbyVirtualNetwork = NearbyVirtualNetwork(
            context = mockContext,
            name = "TestDevice",
            serviceId = "TestService",
            virtualIpAddress = InetAddress.getByName("192.168.0.1").hashCode(),
            broadcastAddress = InetAddress.getByName("192.168.0.255").hashCode(),
            logger = mockLogger
        )
    }

    @Test
    fun testStartNetwork() = runTest {
        nearbyVirtualNetwork.start()
        verify(mockConnectionsClient).startAdvertising(
            eq("TestDevice"),
            eq("TestService"),
            any(),
            any()
        )
        verify(mockConnectionsClient).startDiscovery(
            eq("TestService"),
            any(),
            any()
        )
    }

    @Test
    fun testSendMessage() {
        val testEndpointId = "testEndpoint"
        val testMessage = "Hello, World!"
        nearbyVirtualNetwork.sendMessage(testEndpointId, testMessage)
        verify(mockConnectionsClient).sendPayload(eq(testEndpointId), any())
    }

    @Test
    fun testMessageReceivedListener() {
        var receivedEndpointId: String? = null
        var receivedPayload: Payload? = null

        nearbyVirtualNetwork.setOnMessageReceivedListener { endpointId, payload ->
            receivedEndpointId = endpointId
            receivedPayload = payload
        }

        val testEndpointId = "testEndpoint"
        val testPayload = Payload.fromBytes("Test Message".toByteArray())
        nearbyVirtualNetwork.payloadCallback.onPayloadReceived(testEndpointId, testPayload)

        assertEquals(testEndpointId, receivedEndpointId)
        assertNotNull(receivedPayload)
    }

    @Test
    fun testCloseNetwork() {
        nearbyVirtualNetwork.close()
        verify(mockConnectionsClient).stopAdvertising()
        verify(mockConnectionsClient).stopDiscovery()
        verify(mockConnectionsClient).stopAllEndpoints()
    }
}