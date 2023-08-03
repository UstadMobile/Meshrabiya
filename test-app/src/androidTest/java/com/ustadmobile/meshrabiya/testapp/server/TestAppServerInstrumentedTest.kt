package com.ustadmobile.meshrabiya.testapp.server

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.ext.toPem
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.test.TestVirtualNode
import com.ustadmobile.meshrabiya.test.connectTo
import com.ustadmobile.meshrabiya.vnet.quic.generateKeyPair
import com.ustadmobile.meshrabiya.vnet.quic.generateX509Cert
import com.ustadmobile.meshrabiya.writeRandomData
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.luminis.http3.libnethttp.H3HttpClient
import net.luminis.httpclient.AndroidH3Factory
import net.luminis.quic.QuicClientConnection
import net.luminis.quic.QuicConnection
import net.luminis.quic.QuicStream
import net.luminis.quic.Version
import net.luminis.quic.log.Logger
import net.luminis.quic.log.SysOutLogger
import net.luminis.quic.server.ApplicationProtocolConnection
import net.luminis.quic.server.ServerConnector
import net.luminis.tls.env.PlatformMapping
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.Security
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

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
                logger = MNetLoggerStdout(Log.WARN),
                localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, 1).ip4AddressToInt(),
                json = json,
            ),
            appContext = appContext
        )

        val testNode2 = TestAppServerNode(
            testNode = TestVirtualNode(
                logger = MNetLoggerStdout(Log.WARN),
                localNodeAddress = byteArrayOf(169.toByte(), 254.toByte(), 1, 2).ip4AddressToInt(),
                json = json,
            ),
            appContext = appContext
        )

        try {
            testNode1.testNode.connectTo(testNode2.testNode)


            val wwwDir = tempDir.newFolder()
            val randomFile = File(wwwDir, "random.dat")
            randomFile.writeRandomData(100 * 1024 * 1024)

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

                    val runTime = measureTime {
                        testNode2.testServer.acceptIncomingTransfer(incomingTransfer, destFile)
                        //Assert.assertArrayEquals(randomBytes, destFile.readBytes())
                    }
                    println("Downloaded file in $runTime")

                    cancelAndIgnoreRemainingEvents()
                }

                testNode1.testServer.outgoingTransfers.filter {
                    it.isNotEmpty()
                }.test(timeout = 5.seconds) {
                    val xfer = awaitItem().first()
                    Assert.assertEquals(TestAppServer.Status.COMPLETED, xfer.status)
                }

                testNode2.testServer.incomingTransfers.filter {
                    it.isNotEmpty()
                }.test(timeout = 5.seconds) {
                    val incomingTransfer = awaitItem().first()
                    Assert.assertEquals(TestAppServer.Status.COMPLETED, incomingTransfer.status)
                }

            }
        }finally {
            testNode1.testNode.close()
            testNode2.testNode.close()
        }
    }

    @Test
    fun givenFileSentFromPlainSocket_whenAccepted_thenShouldTransferToOtherNode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        PlatformMapping.usePlatformMapping(PlatformMapping.Platform.Android)
        Security.addProvider(BouncyCastleProvider())

        val h3Factory = AndroidH3Factory()
        val testServer1 = TestAppServer.newTestServerWithRandomKey(
            appContext = appContext,
            h3Factory = h3Factory,
            http3Client = h3Factory.newClientBuilder()
                .disableCertificateCheck()
                .build(),
        ).also {
            it.start()
        }

        val testServer2 = TestAppServer.newTestServerWithRandomKey(
            appContext = appContext,
            h3Factory = h3Factory,
            http3Client = h3Factory.newClientBuilder()
                .disableCertificateCheck()
                .build(),
        ).also {
            it.start()
        }

        val randomFile = tempDir.newFile("random.dat")
        randomFile.writeRandomData(100 * 1024 * 1024)

        val downloadFile = tempDir.newFile("download.dat")

        val outgoingTransfer = testServer2.addOutgoingTransfer(
            uri = randomFile.toUri(),
            toNode = InetAddress.getByName("127.0.0.1"),
            toPort = testServer1.localPort,
            fileName = "random.dat"
        )!!

        runBlocking {
            testServer1.incomingTransfers.filter {
                it.isNotEmpty()
            }.test(timeout = 5.seconds) {
                val incomingTransfer = awaitItem().first()
                Assert.assertEquals(incomingTransfer.id, outgoingTransfer.id)

                val runTime = measureTime {
                    testServer1.acceptIncomingTransfer(incomingTransfer, downloadFile, fromPort = testServer2.localPort)
                }
                println("Downloaded file in $runTime")
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun givenFileSocket_whenAccepted_thenShouldTransferToOtherNode() {
        val serverSocket = ServerSocket(8083)

        val randomFile = tempDir.newFile("random.dat")
        randomFile.writeRandomData(100 * 1024 * 1024)

        val downloadFile = tempDir.newFile("download.dat")

        Thread {
            val client = serverSocket.accept()
            val clientOutStream = client.getOutputStream()
            FileInputStream(randomFile).use { fileIn ->
                fileIn.copyTo(clientOutStream)
                clientOutStream.flush()
            }
            client.close()
        }.start()
        println(serverSocket.localPort)

        val transferTime = measureTime {
            val clientSocket = Socket("127.0.0.1", serverSocket.localPort)
            clientSocket.getInputStream().use { clientIn ->
                FileOutputStream(downloadFile).use { downloadFileOut ->
                    clientIn.copyTo(downloadFileOut)
                    downloadFileOut.flush()
                }
            }
        }

        println("Downloaded file in $transferTime")
    }



    internal class FileWriterConnection(
        quicConnection: QuicConnection?, log: Logger, private val fromFile: File
    ): ApplicationProtocolConnection {
        override fun acceptPeerInitiatedStream(quicStream: QuicStream) {
            Thread {
                FileInputStream(fromFile).use { fileIn ->
                    quicStream.outputStream.use {quicOut ->
                        fileIn.copyTo(quicOut)
                    }
                }
            }.start()
        }
    }

    @Test
    fun quicSpeedTest() {
        PlatformMapping.usePlatformMapping(PlatformMapping.Platform.Android)
        Security.addProvider(BouncyCastleProvider())

        val socket = DatagramSocket(0)

        val keyPair = generateKeyPair()
        val certificate = generateX509Cert(keyPair)
        val keyIn = ByteArrayInputStream(keyPair.private.toPem().encodeToByteArray())
        val certIn = ByteArrayInputStream(certificate.toPem().encodeToByteArray())

        val randomDataFile = tempDir.newFile("random.dat")
        randomDataFile.writeRandomData(100 * 1024 * 1024)

        val log = SysOutLogger()
        val serverConnector = ServerConnector(
            socket, certIn, keyIn, listOf(Version.QUIC_version_1), false, log
        )
        serverConnector.registerApplicationProtocol("file") { protocol, quicConnection ->
            FileWriterConnection(quicConnection, log, randomDataFile)
        }

        serverConnector.start()



        val downloadFile = tempDir.newFile("download.dat")

        val connection = QuicClientConnection.newBuilder()
            .uri(URI.create("file://127.0.0.1:${socket.localPort}"))
            .logger(log)
            .noServerCertificateCheck()
            .build()
        connection.connect(5000, "file")

        val transferTime = measureTime {
            val quicStream: QuicStream = connection.createStream(true)
            quicStream.inputStream.also { quicIn ->
                FileOutputStream(downloadFile).use {downloadFileOut ->
                    val requestData = "fileme".toByteArray(StandardCharsets.US_ASCII)
                    quicStream.outputStream.write(requestData)
                    quicStream.outputStream.close()
                    quicIn.copyTo(downloadFileOut)
                }
            }
            connection.closeAndWait()
        }

        println("Transfer in $transferTime")

    }

}
