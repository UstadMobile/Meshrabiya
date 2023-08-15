package com.ustadmobile.meshrabiya.testapp.server

import android.content.Context
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.test.TestVirtualNode
import com.ustadmobile.meshrabiya.test.assertFileContentsAreEqual
import com.ustadmobile.meshrabiya.test.connectTo
import com.ustadmobile.meshrabiya.test.newFileWithRandomData
import com.ustadmobile.meshrabiya.writeRandomData
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class TestAppServerInstrumentedTest {

    @field:Rule
    @JvmField
    val tempDir = TemporaryFolder()

    private val logger = MNetLoggerStdout()

    data class TestAppServerNode(
        val testNode: TestVirtualNode,
        val appContext: Context,
    ) {
        val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .socketFactory(testNode.socketFactory)
            .build()

        val testServer: TestAppServer = TestAppServer(
            appContext = appContext,
            httpClient = okHttpClient,
            mLogger = testNode.logger,
            port = 0,
            name = testNode.addressAsInt.addressToDotNotation(),
            localVirtualAddr = testNode.address,
        ).also {
            it.start()
        }
    }

    @Test
    fun givenFileSentFromNode_whenAccepted_thenShouldTransferToOtherNode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        val json = Json {
            encodeDefaults = true
        }
        val testNode1 = TestAppServerNode(
            testNode = TestVirtualNode(
                logger = logger,
                localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, 1).ip4AddressToInt(),
                json = json,
            ),
            appContext = appContext
        )

        val testNode2 = TestAppServerNode(
            testNode = TestVirtualNode(
                logger = logger,
                localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, 2).ip4AddressToInt(),
                json = json,
            ),
            appContext = appContext
        )

        try {
            testNode1.testNode.connectTo(testNode2.testNode)

            val randomFile = tempDir.newFileWithRandomData(1024 * 1024, "random.dat")
            val destFile = tempDir.newFile()

            randomFile.writeRandomData(1024 * 1024)

            val outgoingTransfer = testNode1.testServer.addOutgoingTransfer(
                uri = randomFile.toUri(),
                toNode = testNode2.testNode.address,
                toPort = testNode2.testServer.localPort
            )

            runBlocking {
                testNode2.testServer.incomingTransfers.filter {
                    it.isNotEmpty()
                }.test(name = "Received incoming transfer request", timeout = 5.seconds) {
                    val incomingTransfer = awaitItem().first()
                    Assert.assertEquals(incomingTransfer.id, outgoingTransfer.id)

                    val runTime = measureTime {
                        testNode2.testServer.acceptIncomingTransfer(
                            transfer = incomingTransfer,
                            destFile = destFile,
                            fromPort = testNode1.testServer.localPort,
                        )
                    }
                    println("Downloaded file in $runTime")

                    cancelAndIgnoreRemainingEvents()
                }

                testNode1.testServer.outgoingTransfers.filter {
                    it.isNotEmpty() && it.first().status == TestAppServer.Status.COMPLETED
                }.test(timeout = 5.seconds, name = "Testnode 1 outgoing tranfer status is complete") {
                    val xfer = awaitItem().first()
                    Assert.assertEquals(TestAppServer.Status.COMPLETED, xfer.status)
                }

                testNode2.testServer.incomingTransfers.filter {
                    it.isNotEmpty()
                }.test(timeout = 5.seconds, name = "Incoming Transfer status is completed") {
                    val incomingTransfer = awaitItem().first()
                    Assert.assertEquals(TestAppServer.Status.COMPLETED, incomingTransfer.status)
                }

                assertFileContentsAreEqual(randomFile, destFile)
            }
        }finally {
            testNode1.testNode.close()
            testNode2.testNode.close()
        }
    }




}
