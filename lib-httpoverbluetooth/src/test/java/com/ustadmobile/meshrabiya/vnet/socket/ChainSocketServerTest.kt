package com.ustadmobile.meshrabiya.vnet.socket

import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.readChainInitResponse
import com.ustadmobile.meshrabiya.ext.writeChainSocketInitRequest
import com.ustadmobile.meshrabiya.ext.writeChainSocketInitResponse
import com.ustadmobile.meshrabiya.test.FileEchoSocketServer
import com.ustadmobile.meshrabiya.test.assertFileContentsAreEqual
import com.ustadmobile.meshrabiya.vnet.randomApipaAddr
import com.ustadmobile.meshrabiya.writeRandomData
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class ChainSocketServerTest {

    @JvmField
    @Rule
    val tempFolder = TemporaryFolder()

    @Test(timeout = 5000)
    fun givenRequestToConnectToNeighbor_whenConnected_thenWillConnectAndRelay() {
        val randomDataFile = tempFolder.newFile().also {
            it.writeRandomData(1024 * 1024)
        }
        val randomFileSocketServer = FileEchoSocketServer(randomDataFile, 0)

        val chainSocketFactory : ChainSocketFactory = mock {
            on { createChainSocket(any(), any()) }.thenAnswer {
                val socket = Socket(InetAddress.getLoopbackAddress(), randomFileSocketServer.localPort)
                ChainSocketFactory.ChainSocketResult(socket,
                    ChainSocketNextHop(
                        address = InetAddress.getLoopbackAddress(),
                        port = randomFileSocketServer.localPort,
                        isFinalDest = true
                    )
                )
            }
        }

        val chainSocketServer = ChainSocketServer(
            ServerSocket(0), Executors.newCachedThreadPool(), chainSocketFactory
        )

        val clientSocket = Socket(InetAddress.getLoopbackAddress(), chainSocketServer.localPort)
        clientSocket.getOutputStream().writeChainSocketInitRequest(
            ChainSocketInitRequest(
                virtualDestAddr = InetAddress.getByAddress(randomApipaAddr().addressToByteArray()),
                virtualDestPort = randomFileSocketServer.localPort,
                fromAddr = InetAddress.getLoopbackAddress(),
            )
        )
        val clientInput = clientSocket.getInputStream()
        val initResponse = clientInput.readChainInitResponse()

        val downloadFile = tempFolder.newFile()
        FileOutputStream(downloadFile).use {
            clientSocket.getInputStream().copyTo(it)
        }
        clientSocket.close()
        assertFileContentsAreEqual(randomDataFile, downloadFile)
        Assert.assertEquals(200, initResponse.statusCode)
    }

    @Test
    fun givenRequestToConnectToNonNeighbor_whenConnected_thenWillConnectAndRelay() {

    }

}