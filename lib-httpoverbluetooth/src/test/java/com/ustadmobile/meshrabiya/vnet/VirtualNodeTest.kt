package com.ustadmobile.meshrabiya.vnet

import app.cash.turbine.test
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.ext.requireAsIpv6
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotRequest
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotResponse
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.test.EchoDatagramServer
import com.ustadmobile.meshrabiya.test.TestVirtualNode
import com.ustadmobile.meshrabiya.test.assertByteArrayEquals
import com.ustadmobile.meshrabiya.test.connectTo
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotStatus
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiDirectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verifyBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VirtualNodeTest {


    private val logger = MNetLoggerStdout()

    private val json = Json {
        encodeDefaults = true
    }



}