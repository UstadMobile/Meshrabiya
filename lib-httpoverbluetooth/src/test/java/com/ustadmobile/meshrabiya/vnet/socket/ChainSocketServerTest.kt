package com.ustadmobile.meshrabiya.vnet.socket

import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.readChainInitResponse
import com.ustadmobile.meshrabiya.ext.writeChainSocketInitRequest
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
import org.mockito.kotlin.verify
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
        val destAddr = InetAddress.getByAddress(randomApipaAddr().addressToByteArray())
        clientSocket.getOutputStream().writeChainSocketInitRequest(
            ChainSocketInitRequest(
                virtualDestAddr = destAddr,
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
        verify(chainSocketFactory).createChainSocket(destAddr, randomFileSocketServer.localPort)
    }

    @Test(timeout = 500000)
    fun givenRequestToConnectToNonNeighbor_whenConnected_thenWillConnectAndRelayViaSecondServer() {
        val randomDataFile = tempFolder.newFile().also {
            it.writeRandomData(1024 * 1024)
        }

        val randomFileSocketServer = FileEchoSocketServer(randomDataFile, 0)

        val chainSocketFactory2 : ChainSocketFactory = mock {
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
        val chainSocketServer2 = ChainSocketServer(
            ServerSocket(0), Executors.newCachedThreadPool(), chainSocketFactory2,
        )

        val chainSocketFactory1: ChainSocketFactory = mock {
            on { createChainSocket(any(), any()) }.thenAnswer {
                val socket = Socket(InetAddress.getLoopbackAddress(), chainSocketServer2.localPort)
                ChainSocketFactory.ChainSocketResult(socket,
                    ChainSocketNextHop(
                        address = InetAddress.getLoopbackAddress(),
                        port = chainSocketServer2.localPort,
                        isFinalDest = false
                    )
                )
            }
        }
        val chainSocketServer1 = ChainSocketServer(
            ServerSocket(0), Executors.newCachedThreadPool(), chainSocketFactory1
        )

        val clientSocket = Socket(InetAddress.getLoopbackAddress(), chainSocketServer1.localPort)
        val destAddr = InetAddress.getByAddress(randomApipaAddr().addressToByteArray())
        clientSocket.getOutputStream().writeChainSocketInitRequest(
            ChainSocketInitRequest(
                virtualDestAddr = destAddr,
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
        verify(chainSocketFactory1).createChainSocket(destAddr, randomFileSocketServer.localPort)
        verify(chainSocketFactory2).createChainSocket(destAddr, randomFileSocketServer.localPort)
    }

}