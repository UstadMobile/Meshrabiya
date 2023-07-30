package com.ustadmobile.meshrabiya.testapp.server

import android.content.Context
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.test.TestVirtualNode
import com.ustadmobile.meshrabiya.test.connectTo
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.luminis.http3.libnethttp.H3HttpClient
import net.luminis.httpclient.AndroidH3Factory
import net.luminis.tls.env.PlatformMapping
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.Security
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class TestAppServerInstrumentedTest {

    @field:Rule
    @JvmField
    val tempDir = TemporaryFolder()


    data class TestAppServerNode(
        val testNode: TestVirtualNode,
        val appContext: Context,
    ) {
        val h3Factory = AndroidH3Factory()

        val h3Client: H3HttpClient = h3Factory.newClientBuilder()
            .disableCertificateCheck()
            .datagramSocketFactory {
                testNode.createBoundDatagramSocket(0)
            }
            .build()

        val testServer: TestAppServer = TestAppServer.newTestServerWithRandomKey(
            appContext = appContext,
            h3Factory = h3Factory,
            http3Client = h3Client,
            socket = testNode.createBoundDatagramSocket(TestAppServer.DEFAULT_PORT),
        ).also {
            it.start()
        }
    }

    @Test
    fun givenFileSentFromNode_whenAccepted_thenShouldTransferToOtherNode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        PlatformMapping.usePlatformMapping(PlatformMapping.Platform.Android)
        Security.addProvider(BouncyCastleProvider())

        val json = Json {
            encodeDefaults = true
        }
        val testNode1 = TestAppServerNode(
            testNode = TestVirtualNode(
                localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, 1).ip4AddressToInt(),
                json = json,
            ),
            appContext = appContext
        )

        val testNode2 = TestAppServerNode(
            testNode = TestVirtualNode(
                localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, 2).ip4AddressToInt(),
                json = json,
            ),
            appContext = appContext
        )

        try {
            testNode1.testNode.connectTo(testNode2.testNode)


            val wwwDir = tempDir.newFolder()
            val randomFile = File(wwwDir, "random.dat")
            val randomBytes = Random.nextBytes(5000)
            randomFile.writeBytes(randomBytes)

            val outgoingTransfer = testNode1.testServer.addOutgoingTransfer(
                uri = randomFile.toUri(),
                toNode = testNode2.testNode.localNodeInetAddress,
                fileName = "random.dat"
            )!!

            runBlocking {
                testNode2.testServer.incomingTransfers.filter {
                    it.isNotEmpty()
                }.test(timeout = 5.seconds) {
                    val incomingTransfer = awaitItem().first()
                    Assert.assertEquals(incomingTransfer.id, outgoingTransfer.id)

                    val destFile = tempDir.newFile()

                    testNode2.testServer.acceptIncomingTransfer(incomingTransfer, destFile)
                    Assert.assertArrayEquals(randomBytes, destFile.readBytes())

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }finally {
            testNode1.testNode.close()
            testNode2.testNode.close()
        }
    }

}
