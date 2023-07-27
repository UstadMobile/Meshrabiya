package com.ustadmobile.meshrabiya.testapp.server

import com.ustadmobile.meshrabiya.ext.toPem
import com.ustadmobile.meshrabiya.vnet.quic.generateKeyPair
import com.ustadmobile.meshrabiya.vnet.quic.generateX509Cert
import net.luminis.http3.server.HttpRequestHandler
import net.luminis.http3.server.HttpServerRequest
import net.luminis.http3.server.HttpServerResponse
import net.luminis.http3.server.file.FileServer
import net.luminis.httpclient.AndroidH3Factory
import net.luminis.quic.Version
import net.luminis.quic.log.Logger
import net.luminis.quic.log.SysOutLogger
import net.luminis.quic.server.ServerConnector
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.DatagramSocket

class TestAppServer(
    wwwDir: File,
    private val serverSocket: DatagramSocket = DatagramSocket(),
    certIn: InputStream,
    keyIn: InputStream,
    logger: Logger = SysOutLogger(),
) {

    private val serverConnector: ServerConnector

    val localPort: Int
        get() = serverSocket.localPort

    private val fileServer: FileServer

    private val h3Factory = AndroidH3Factory()

    init {
        serverConnector = ServerConnector(
            serverSocket, certIn, keyIn, listOf(Version.QUIC_version_1), false, logger,
        )

        fileServer = FileServer(wwwDir)
        serverConnector.registerApplicationProtocol("h3") { protocol, connection ->
            h3Factory.newHttp3ServerConnection(connection, TestAppRequestHandler(fileServer))
        }
    }


    class TestAppRequestHandler(
        private val fileServer: FileServer,
    ): HttpRequestHandler {
        override fun handleRequest(request: HttpServerRequest, response: HttpServerResponse) {
            val path = request.path()
            if(path.startsWith("/meshtest/")) {
                //process internally
                response.setStatus(200)

            }else {
                fileServer.handleRequest(request, response)
            }
        }
    }

    fun start() {
        serverConnector.start()
    }

    companion object {

        fun newTestServerWithRandomKey(
            wwwDir: File,
            socket: DatagramSocket = DatagramSocket(),
            logger: Logger = SysOutLogger(),
        ): TestAppServer {
            val keyPair = generateKeyPair()
            val certificate = generateX509Cert(keyPair)
            val keyIn = ByteArrayInputStream(keyPair.private.toPem().encodeToByteArray())
            val certIn = ByteArrayInputStream(certificate.toPem().encodeToByteArray())

            return TestAppServer(wwwDir, socket, certIn, keyIn, logger)
        }
    }

}