package com.ustadmobile.meshrabiya.vnet.socket

import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.readChainInitResponse
import com.ustadmobile.meshrabiya.ext.writeChainSocketInitRequest
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.test.FileEchoSocketServer
import com.ustadmobile.meshrabiya.test.assertFileContentsAreEqual
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import com.ustadmobile.meshrabiya.vnet.randomApipaAddr
import com.ustadmobile.meshrabiya.writeRandomData
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class ChainSocketServerTest {

    @JvmField
    @Rule
    val tempFolder = TemporaryFolder()

    private val mNetLogger = MNetLoggerStdout()

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
            ServerSocket(0), Executors.newCachedThreadPool(), chainSocketFactory, "test", mNetLogger
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


    private fun testViaHop(
        onMakeChainSocket: ChainSocketFactory.(address: InetAddress, port: Int) -> ChainSocketFactory.ChainSocketResult
    ) {
        val randomDataFile = tempFolder.newFile().also {
            it.writeRandomData(1024 * 1024)
        }

        val randomFileSocketServer = FileEchoSocketServer(randomDataFile, 0)

        val destAddr = InetAddress.getByAddress(randomApipaAddr().addressToByteArray())

        val chainServerSocket1 = ServerSocket(0)
        val chainServerSocket2 = ServerSocket(0)

        val virtualRouter1: VirtualRouter = mock {
            on { address }.thenReturn(InetAddress.getByAddress(randomApipaAddr().addressToByteArray()))
            on { networkPrefixLength }.thenReturn(16)
            on { lookupNextHopForChainSocket(any(), any()) }.thenReturn(ChainSocketNextHop(
                address = InetAddress.getLoopbackAddress(),
                port = chainServerSocket2.localPort,
                isFinalDest = false,
            ))
        }

        val virtualRouter2: VirtualRouter = mock {
            on { address }.thenReturn(InetAddress.getByAddress(randomApipaAddr().addressToByteArray()))
            on { networkPrefixLength }.thenReturn(16)
            on { lookupNextHopForChainSocket(any(), any()) }.thenAnswer {
                ChainSocketNextHop(
                    address = InetAddress.getLoopbackAddress(),
                    port = it.arguments[1] as Int,
                    isFinalDest = true
                )
            }
        }

        //ChainSocketFactory2 represents the node that is a neighbor to the final destination
        val chainSocketFactory2 = spy(ChainSocketFactoryImpl(virtualRouter2, logger = mNetLogger))
        val chainSocketServer2 = ChainSocketServer(
            chainServerSocket2, Executors.newCachedThreadPool(), chainSocketFactory2,
            "server2", mNetLogger, onMakeChainSocket
        )

        //ChainSocketFactory1 represents the node that makes the request that will run via ChainSocketFactory2
        val chainSocketFactory1 = spy(ChainSocketFactoryImpl(virtualRouter1, logger = mNetLogger))
        val chainSocketServer1 = ChainSocketServer(
            chainServerSocket1, Executors.newCachedThreadPool(), chainSocketFactory1,
            "server1", mNetLogger, onMakeChainSocket
        )

        val clientSocket = Socket(InetAddress.getLoopbackAddress(), chainSocketServer1.localPort)

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
        verify(virtualRouter1, atLeastOnce()).lookupNextHopForChainSocket(destAddr, randomFileSocketServer.localPort)
        verify(virtualRouter2, atLeastOnce()).lookupNextHopForChainSocket(destAddr, randomFileSocketServer.localPort)


        chainSocketServer1.close()
        chainSocketServer2.close()
    }

    @Test(timeout = 5000)
    fun givenRequestToConnectToNonNeighbor_whenConnected_thenWillConnectAndRelayViaSecondServer() {
        testViaHop(
            onMakeChainSocket = {address, port, ->
                createChainSocket(address, port)
            }
        )
    }

    @Test(timeout = 500000)
    fun givenRequestToConnectToNonNeighborUsingChainSocketImpl_whenConnected_thenWillConnectAndRelayViaSecondServer() {
        testViaHop(
            onMakeChainSocket = {address, port ->
                ChainSocketFactory.ChainSocketResult(
                    socket = createSocket().also {
                        it.bind(InetSocketAddress(0))
                        it.connect(InetSocketAddress(address, port))
                    },
                    nextHop = (this as ChainSocketFactoryImpl).virtualRouter.lookupNextHopForChainSocket(address, port)
                )
            }
        )
    }

}