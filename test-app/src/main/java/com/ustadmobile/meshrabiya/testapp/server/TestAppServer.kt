package com.ustadmobile.meshrabiya.testapp.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ustadmobile.meshrabiya.ext.toPem
import com.ustadmobile.meshrabiya.vnet.quic.generateKeyPair
import com.ustadmobile.meshrabiya.vnet.quic.generateX509Cert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.luminis.http3.libnethttp.H3Factory
import net.luminis.http3.libnethttp.H3HttpClient
import net.luminis.http3.server.HttpRequestHandler
import net.luminis.http3.server.HttpServerRequest
import net.luminis.http3.server.HttpServerResponse
import net.luminis.quic.Version
import net.luminis.quic.log.Logger
import net.luminis.quic.log.SysOutLogger
import net.luminis.quic.server.ServerConnector
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The TestAppServer is used to send/receive files between nodes. Flow as follows:
 * 1. The sender
 */
class TestAppServer(
    private val appContext: Context,
    private val h3Factory: H3Factory,
    private val http3Client: H3HttpClient,
    private val serverSocket: DatagramSocket = DatagramSocket(),
    certIn: InputStream,
    keyIn: InputStream,
    logger: Logger = SysOutLogger(),
) {
    data class OutgoingTransfer(
        val id: Int,
        val name: String,
        val uri: Uri,
        val toHost: InetAddress,
    )

    data class IncomingTransfer(
        val id: Int,
        val fromHost: InetAddress,
        val name: String,
    )

    private val transferIdAtomic = AtomicInteger()

    private val serverConnector: ServerConnector

    private val _outgoingTransfers = MutableStateFlow(emptyList<OutgoingTransfer>())

    val outgoingTransfers: Flow<List<OutgoingTransfer>> = _outgoingTransfers.asStateFlow()

    val _incomingTransfers = MutableStateFlow(emptyList<IncomingTransfer>())

    val incomingTransfers: Flow<List<IncomingTransfer>> = _incomingTransfers.asStateFlow()

    val localPort: Int
        get() = serverSocket.localPort

    inner class TestAppRequestHandler: HttpRequestHandler {
        override fun handleRequest(request: HttpServerRequest, response: HttpServerResponse) {
            val path = request.path()
            if(path.startsWith("/download/")) {
                val xferId = path.substringAfterLast("/").toInt()
                val outgoingXfer = _outgoingTransfers.value.first {
                    it.id == xferId
                }

                response.setStatus(200)
                appContext.contentResolver.openInputStream(outgoingXfer.uri)?.use {
                    it.copyTo(response.outputStream)
                }
            }else if(path.startsWith("/send")) {
                val searchParams = path.substringAfter("?").split("&")
                    .map {
                        it.substringBefore("=") to it.substringAfter("=")
                    }.toMap()

                val id = searchParams["id"]
                val filename = searchParams["filename"]

                if(id != null && filename != null) {
                    val incomingTransfer = IncomingTransfer(
                        id = id.toInt(),
                        fromHost = request.clientAddress(),
                        name = filename
                    )

                    _incomingTransfers.update { prev ->
                        buildList {
                            add(incomingTransfer)
                            addAll(prev)
                        }
                    }
                    response.setStatus(200)
                    response.outputStream.write("OK".encodeToByteArray())
                }else {
                    response.setStatus(400)
                    response.outputStream.write("Bad request".encodeToByteArray())
                }
            }

            else if(path.startsWith("/meshtest/")) {
                //process internally
                response.setStatus(200)
            }
        }
    }

    init {
        serverConnector = ServerConnector(
            serverSocket, certIn, keyIn, listOf(Version.QUIC_version_1), false, logger,
        )

        serverConnector.registerApplicationProtocol("h3") { protocol, connection ->
            h3Factory.newHttp3ServerConnection(connection, TestAppRequestHandler())
        }
    }

    fun start() {
        serverConnector.start()
    }

    /**
     * Add an outgoing transfer. This is done using a Uri so that we don't have to make our own
     * copy of the file the user wants to transfer.
     */
    fun addOutgoingTransfer(
        uri: Uri,
        toNode: InetAddress,
        fileName: String? = null,
    ): OutgoingTransfer? {
        val transferId = transferIdAtomic.incrementAndGet()

        val effectiveFileName = fileName ?: appContext.contentResolver.query(
            uri, null, null, null, null
        )?.use { cursor ->
            var colIndex = 0
            if(cursor.moveToFirst() &&
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).also { colIndex = it } >= 1
            ) {
                cursor.getString(colIndex)
            }else {
                null
            }
        }

        if(effectiveFileName == null) {
            println("ERROR: filename is null")
            return null
        }

        val outgoingTransfer = OutgoingTransfer(
            id = transferId,
            name = effectiveFileName,
            uri = uri ,
            toHost = toNode,
        )

        //tell the other side about the transfer
        val request = h3Factory.newRequestBuilder()
            .uri(URI("https://${toNode.hostName}:$DEFAULT_PORT/" +
                    "send?id=$transferId&filename=${URLEncoder.encode(effectiveFileName, "UTF-8")}"))
            .build()
        val response = http3Client.send(request, h3Factory.bodyHandlers().ofString())
        println(response.body())

        _outgoingTransfers.update { prev ->
            buildList {
                add(outgoingTransfer)
                addAll(prev)
            }
        }

        return outgoingTransfer
    }

    fun acceptIncomingTransfer(
        transfer: IncomingTransfer,
        destFile: File,
    ) {
        val request = h3Factory.newRequestBuilder()
            .uri(URI("https://${transfer.fromHost.hostAddress}:$DEFAULT_PORT/download/${transfer.id}"))
            .build()

        val response = http3Client.send(request,
            h3Factory.bodyHandlers().ofFile(destFile.toPath()))
        response.body()
    }

    companion object {

        const val DEFAULT_PORT = 4242

        fun newTestServerWithRandomKey(
            appContext: Context,
            h3Factory: H3Factory,
            http3Client: H3HttpClient,
            socket: DatagramSocket = DatagramSocket(),
            logger: Logger = SysOutLogger(),
        ): TestAppServer {
            val keyPair = generateKeyPair()
            val certificate = generateX509Cert(keyPair)
            val keyIn = ByteArrayInputStream(keyPair.private.toPem().encodeToByteArray())
            val certIn = ByteArrayInputStream(certificate.toPem().encodeToByteArray())

            return TestAppServer(appContext, h3Factory, http3Client, socket, certIn, keyIn, logger)
        }
    }

}