package com.ustadmobile.meshrabiya.vnet

import app.cash.turbine.test
import com.ustadmobile.meshrabiya.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.requireAsIpv6
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotRequest
import com.ustadmobile.meshrabiya.mmcp.MmcpHotspotResponse
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.test.connectTo
import com.ustadmobile.meshrabiya.vnet.VirtualRouter.Companion.ADDR_BROADCAST
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
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet6Address
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VirtualNodeTest {

    class TestVirtualNode(
        uuidMask: UUID = UUID.randomUUID(),
        port: Int = 0,
        logger: MNetLogger,
        override val hotspotManager: MeshrabiyaWifiManager = mock { },
        json: Json,
        config: NodeConfig = NodeConfig(maxHops = 5),
    ) : VirtualNode(
        uuidMask = uuidMask,
        port = port,
        logger = logger,
        json = json,
        config = config,
    )

    class PipeSocket(
        override val inStream: InputStream,
        override val outputStream: OutputStream,
    ): ISocket {
        override fun close() {
            inStream.close()
            outputStream.close()
        }
    }

    private val logger = MNetLogger { priority, message, exception ->
        println(buildString {
            append(message)
            if(exception != null) {
                append(" ")
                append(exception.stackTraceToString())
            }
        })
    }

    private val json = Json {
        encodeDefaults = true
    }

    @Test
    fun givenTwoVirtualNodesConnectedOverIoStreamSocket_whenPingSent_thenReplyWillBeReceived() {
        val executor = Executors.newCachedThreadPool()
        val json = Json { encodeDefaults = true }
        val node1 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
        )

        val node2 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
        )

        val node1ToNode2Out = PipedOutputStream()
        val node2FromNode1In = PipedInputStream(node1ToNode2Out)

        val node2ToNode1Out = PipedOutputStream()
        val node1FromNode2In = PipedInputStream(node2ToNode1Out)

        val node1Socket = PipeSocket(node1FromNode2In, node1ToNode2Out)
        val node2Socket = PipeSocket(node2FromNode1In, node2ToNode1Out)


        //needs to run on executor... otherwise will get stuck
        executor.submit { node1.handleNewSocketConnection(node1Socket) }
        executor.submit { node2.handleNewSocketConnection(node2Socket) }

        val node1ToNode2Ping = MmcpPing(Random.nextInt())

        val latch = CountDownLatch(1)

        node1.addPongListener(object: PongListener{
            override fun onPongReceived(fromNode: Int, pong: MmcpPong) {
                latch.countDown()
            }
        })

        node1.route(
            node1ToNode2Ping.toVirtualPacket(
                toAddr = node2.localNodeAddress,
                fromAddr = node1.localNodeAddress
            )
        )

        latch.await(5000, TimeUnit.MILLISECONDS)

        executor.shutdown()
    }

    @Test
    fun givenTwoVirtualNodesConnectedOverDatagramSocket_whenPingSent_thenReplyWillBeReceived() {
        val json = Json { encodeDefaults = true }
        val node1 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
        )
        val node2 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
        )

        node1.connectTo(node2)

        val latch = CountDownLatch(1)
        val pongMessage = AtomicReference<MmcpPong>()
        val node1ToNode2Ping = MmcpPing(Random.nextInt())

        node1.addPongListener(object: PongListener{
            override fun onPongReceived(fromNode: Int, pong: MmcpPong) {
                if(pong.replyToMessageId == node1ToNode2Ping.messageId) {
                    pongMessage.set(pong)
                    latch.countDown()
                }

            }
        })


        node1.route(
            node1ToNode2Ping.toVirtualPacket(
                toAddr = node2.localNodeAddress,
                fromAddr = node1.localNodeAddress
            )
        )

        latch.await(5000, TimeUnit.MILLISECONDS)
        Assert.assertEquals(node1ToNode2Ping.messageId, pongMessage.get().replyToMessageId)
    }


    @Test
    fun givenMmcpHotspotRequestReceived_whenPacketRouted_thenWillRequestFromHotspotManagerAndReplyWithConfig() {
        val hotspotState = MutableStateFlow(
            MeshrabiyaWifiState(
                wifiDirectState = WifiDirectState(hotspotStatus = HotspotStatus.STOPPED)
            )
        )
        val mockHotspotManager = mock<MeshrabiyaWifiManager> {
            on { state }.thenReturn(hotspotState)
            onBlocking { requestHotspot(any(), any()) }.thenAnswer {
                val messageId = it.arguments.first() as Int
                LocalHotspotResponse(
                    responseToMessageId = messageId,
                    errorCode = 0,
                    config = WifiConnectConfig(
                        nodeVirtualAddr = randomApipaAddr(),
                        ssid = "networkname",
                        passphrase = "secret123",
                        port = 8042,
                        hotspotType = HotspotType.LOCALONLY_HOTSPOT,
                        linkLocalAddr = Inet6Address.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334").requireAsIpv6(),
                    ),
                    redirectAddr = 0,
                )
            }
        }

        val json = Json { encodeDefaults = true }

        val node1 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mockHotspotManager,
            json = json,
        )

        val node2 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
        )

        node1.connectTo(node2)

        //Wait for connection to be established
        runBlocking {
            node1.neighborNodesState.filter { it.isNotEmpty() }.test {
                awaitItem()
            }
        }

        val requestId = Random.nextInt()

        node1.route(
            packet = MmcpHotspotRequest(requestId, LocalHotspotRequest(is5GhzSupported = true))
                .toVirtualPacket(
                    toAddr = node1.localNodeAddress,
                    fromAddr = node2.localNodeAddress
                )
        )

        verifyBlocking(mockHotspotManager, timeout(5000)) {
            requestHotspot(eq(requestId), any())
        }

        runBlocking {
            node2.incomingMmcpMessages.filter {
                it.message.what == MmcpMessage.WHAT_HOTSPOT_RESPONSE
            }.test(timeout = 5.seconds) {
                val message = awaitItem() as MmcpHotspotResponse
                Assert.assertEquals(requestId, message.result.responseToMessageId)
                Assert.assertEquals("networkname", message.result.config?.ssid)
                Assert.assertEquals("secret123", message.result.config?.passphrase)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test(timeout = 10000)
    fun givenConnectedNodes_whenBroadcastIsSent_thenAllWillReceive() {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val nodesToClose = mutableListOf<VirtualNode>()

        try {
            val middleNode = TestVirtualNode(
                logger = logger,
                json = json,
            )
            nodesToClose += middleNode

            val connectedNodes = (0 until 3).map {
                TestVirtualNode(
                    logger = logger,
                    json = json
                ).also {
                    nodesToClose += it
                    it.connectTo(middleNode)
                }
            }

            val pingMessageId = 1000042
            val broadcastPing = MmcpPing(pingMessageId).toVirtualPacket(
                toAddr = ADDR_BROADCAST,
                fromAddr = connectedNodes.first().localNodeAddress
            )

            val otherJobs = (1 until 3).map {nodeIndex ->
                scope.async {
                    connectedNodes[nodeIndex].incomingMmcpMessages.filter {
                        it.message.messageId == pingMessageId
                    }.first()
                }
            }

            val firstNode = connectedNodes.first()

            firstNode.route(broadcastPing)

            runBlocking { awaitAll(*otherJobs.toTypedArray()) }
        }finally {
            scope.cancel()
            nodesToClose.forEach { it.close() }
        }
    }

    /**
     * Integration test to ensure that when there are two connected virtual node neighbors they will
     * ping each other and update their state.
     */
    @Test
    fun givenTwoNodes_whenConnected_thenPingTimesWillBeDetermined() {
        val node1 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
        )
        val node2 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            logger = logger,
            hotspotManager = mock { },
            json = json,
        )

        fun VirtualNode.assertPingTimeDetermined(otherNode: VirtualNode) {
            runBlocking {
                neighborNodesState.filter { neighbors ->
                    (neighbors.firstOrNull { it.remoteAddress == otherNode.localNodeAddress }?.pingTime ?: 0) > 0
                }.test(timeout = 5000.milliseconds) {
                    val pingTime = awaitItem().first { it.remoteAddress == otherNode.localNodeAddress }.pingTime
                    Assert.assertTrue(
                        "${localNodeAddress.addressToDotNotation()} -> " +
                            "${otherNode.localNodeAddress.addressToDotNotation()} ping time > 0",
                        pingTime > 0
                    )
                    println("Determined ping time from ${localNodeAddress.addressToDotNotation()} " +
                            "-> ${otherNode.localNodeAddress.addressToDotNotation()} = ${pingTime}ms")
                }
            }
        }

        try {
            node1.connectTo(node2)
            node1.assertPingTimeDetermined(node2)
            node2.assertPingTimeDetermined(node1)
        }finally {
            node1.close()
            node2.close()
        }

    }



}