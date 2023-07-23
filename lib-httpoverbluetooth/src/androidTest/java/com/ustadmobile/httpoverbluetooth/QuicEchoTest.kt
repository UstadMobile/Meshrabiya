package com.ustadmobile.httpoverbluetooth

import android.util.Log
import com.ustadmobile.meshrabiya.HttpOverBluetoothConstants.LOG_TAG
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.ip4AddressToInt
import com.ustadmobile.meshrabiya.ext.toPem
import com.ustadmobile.meshrabiya.test.TestVirtualNode
import com.ustadmobile.meshrabiya.test.connectTo
import com.ustadmobile.meshrabiya.vnet.quic.generateX509Cert
import com.ustadmobile.meshrabiya.vnet.quic.generateKeyPair
import kotlinx.serialization.json.Json
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
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramSocket
import java.security.Security
import java.security.Signature
import java.util.UUID


class QuicEchoTest {

    private fun registerProtocolHandler(serverConnector: ServerConnector, log: Logger) {
        serverConnector.registerApplicationProtocol(
            "echo"
        ) { protocol: String?, quicConnection: QuicConnection? ->
            EchoProtocolConnection(
                quicConnection,
                log
            )
        }
    }

    internal class EchoProtocolConnection(quicConnection: QuicConnection?, log: Logger) :
        ApplicationProtocolConnection {
        private val log: Logger

        init {
            this.log = log
        }

        override fun acceptPeerInitiatedStream(quicStream: QuicStream) {
            Thread { handleEchoRequest(quicStream) }.start()
        }

        private fun handleEchoRequest(quicStream: QuicStream) {
            try {
                // Note that this implementation is not safe to use in the wild, as attackers can crash the server by sending arbitrary large requests.
                val bytesRead = quicStream.inputStream.readBytes()
                println("Read echo request with " + bytesRead.size + " bytes of data.")
                quicStream.outputStream.write(bytesRead)
                quicStream.outputStream.close()
            } catch (e: IOException) {
                log.error("Reading quic stream failed", e)
            }
        }
    }

    @Test
    fun quicTest() {
        PlatformMapping.usePlatformMapping(PlatformMapping.Platform.Android)
        Security.addProvider(BouncyCastleProvider())

        val algo = Signature.getInstance("SHA256withRSA/PSS")
        Assert.assertNotNull(algo)

        val socket = DatagramSocket(0)

        val keyPair = generateKeyPair()
        val certificate = generateX509Cert(keyPair)
        val keyIn = ByteArrayInputStream(keyPair.private.toPem().encodeToByteArray())
        val certIn = ByteArrayInputStream(certificate.toPem().encodeToByteArray())

        Assert.assertNotNull(certIn)
        Assert.assertNotNull(keyIn)
        val log = SysOutLogger()
        val serverConnector = ServerConnector(
            socket, certIn, keyIn, listOf(Version.QUIC_version_1), false, log
        )

        registerProtocolHandler(serverConnector, log)
        serverConnector.start()
        log.info("Started echo server on port " + socket.localPort)

        val echoClient = SimpleEchoClient(socket.localPort)
        echoClient.run()
    }

    @Test
    fun quicTestOverVirtualNetwork() {
        PlatformMapping.usePlatformMapping(PlatformMapping.Platform.Android)
        Security.addProvider(BouncyCastleProvider())

        val algo = Signature.getInstance("SHA256withRSA/PSS")
        Assert.assertNotNull(algo)

        val json = Json {
            encodeDefaults = true
        }

        val node1 = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            hotspotManager = mock { },
            json = json,
            localNodeAddress = byteArrayOf(
                169.toByte(),
                254.toByte(),
                1,
                (1).toByte()
            ).ip4AddressToInt()
        )

        val node2  = TestVirtualNode(
            uuidMask = UUID.randomUUID(),
            hotspotManager = mock { },
            json = json,
            localNodeAddress = byteArrayOf(
                169.toByte(),
                254.toByte(),
                1,
                (2).toByte()
            ).ip4AddressToInt()
        )

        node1.connectTo(node2)

        val serverSocket = node2.createBoundDatagramSocket(80)


        val keyPair = generateKeyPair()
        val certificate = generateX509Cert(keyPair)
        val keyIn = ByteArrayInputStream(keyPair.private.toPem().encodeToByteArray())
        val certIn = ByteArrayInputStream(certificate.toPem().encodeToByteArray())

        val log = SysOutLogger()
        val serverConnector = ServerConnector(
            serverSocket, certIn, keyIn, listOf(Version.QUIC_version_1), false, log
        )

        registerProtocolHandler(serverConnector, log)
        serverConnector.start()
        log.info("Started echo server on port " + serverSocket.localPort)
        Log.i(LOG_TAG, "Started echo server on port " + serverSocket.localPort)

        val echoClient = SimpleEchoClient(
            serverSocket.localPort,
            serverHost = node2.localNodeAddress.addressToDotNotation(),
            socketFactory =  {
                node1.createDatagramSocket()
            }
        )
        echoClient.run()


    }

}