package com.ustadmobile.meshrabiya.testapp.server

import android.content.Context
import android.net.Uri
import com.ustadmobile.meshrabiya.ext.appendOrReplace
import com.ustadmobile.meshrabiya.ext.toPem
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.testapp.ext.getUriNameAndSize
import com.ustadmobile.meshrabiya.testapp.ext.updateItem
import com.ustadmobile.meshrabiya.vnet.quic.generateKeyPair
import com.ustadmobile.meshrabiya.vnet.quic.generateX509Cert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.luminis.http3.libnethttp.H3Factory
import net.luminis.http3.libnethttp.H3HttpClient
import net.luminis.http3.libnethttp.H3HttpResponse
import net.luminis.http3.libnethttp.H3HttpResponse.H3BodyHandler
import net.luminis.http3.libnethttp.H3HttpResponse.H3BodySubscriber
import net.luminis.http3.server.HttpRequestHandler
import net.luminis.http3.server.HttpServerRequest
import net.luminis.http3.server.HttpServerResponse
import net.luminis.quic.Version
import net.luminis.quic.log.Logger
import net.luminis.quic.log.SysOutLogger
import net.luminis.quic.server.ServerConnector
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
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
    private val mLogger: MNetLogger,
    h3Logger: Logger = SysOutLogger(),
) {

    enum class Status {
        PENDING, IN_PROGRESS, COMPLETED, FAILED
    }
    data class OutgoingTransfer(
        val id: Int,
        val name: String,
        val uri: Uri,
        val toHost: InetAddress,
        val status: Status = Status.PENDING,
        val size: Int,
        val transferred: Int = 0,
    )

    data class IncomingTransfer(
        val id: Int,
        val fromHost: InetAddress,
        val name: String,
        val status: Status = Status.PENDING,
        val size: Int,
        val transferred: Int = 0,
        val transferTime: Int = 1,
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
                appContext.contentResolver.openInputStream(outgoingXfer.uri)?.use { inStream ->
                    val buf = ByteArray(8 * 1024)
                    var bytesRead: Int
                    val outStream = response.outputStream
                    var totalTransferred = 0
                    var lastUpdateTime = 0L
                    while(inStream.read(buf).also { bytesRead = it } != -1) {
                        outStream.write(buf, 0, bytesRead)
                        totalTransferred += bytesRead
                        val timeNow = System.currentTimeMillis()
                        if(timeNow - lastUpdateTime > 500) {
                            _outgoingTransfers.update { prev ->
                                prev.appendOrReplace(
                                    item = outgoingXfer.copy(
                                        transferred = totalTransferred,
                                        status = Status.IN_PROGRESS,
                                    ),
                                    replace = { it.id == xferId }
                                )
                            }
                            lastUpdateTime = timeNow
                        }
                    }

                    _outgoingTransfers.update { prev ->
                        prev.updateItem(
                            updatePredicate = { it.id == xferId },
                            function = { item ->
                                item.copy(
                                    status = Status.COMPLETED,
                                    transferred = item.size,
                                )
                            }
                        )
                    }
                }
            }else if(path.startsWith("/send")) {
                val searchParams = path.substringAfter("?").split("&")
                    .map {
                        it.substringBefore("=") to it.substringAfter("=")
                    }.toMap()

                val id = searchParams["id"]
                val filename = searchParams["filename"]
                val size = searchParams["size"]?.toInt() ?: -1

                if(id != null && filename != null) {
                    val incomingTransfer = IncomingTransfer(
                        id = id.toInt(),
                        fromHost = request.clientAddress(),
                        name = filename,
                        size = size
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
            serverSocket, certIn, keyIn, listOf(Version.QUIC_version_1), false, h3Logger,
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

        val nameAndSize = appContext.contentResolver.getUriNameAndSize(uri)
        val effectiveName = nameAndSize.name ?: "unknown"

        val outgoingTransfer = OutgoingTransfer(
            id = transferId,
            name = effectiveName,
            uri = uri ,
            toHost = toNode,
            size = nameAndSize.size.toInt(),
        )


        //tell the other side about the transfer
        val requestUri = URI("https://${toNode.hostAddress}:$DEFAULT_PORT/" +
                "send?id=$transferId&filename=${URLEncoder.encode(effectiveName, "UTF-8")}&size=${nameAndSize.size}")


        val request = h3Factory.newRequestBuilder()
            .uri(requestUri)
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

    @Suppress("Since15") //Flow classes are supported by desugarnig
    inner class AcceptTransferBodyHandler(
        private val file: File,
        private val incomingTransfer: IncomingTransfer,
    ): H3BodyHandler<File> {

        inner class BodySubscriber: H3BodySubscriber<File> {

            private lateinit var outputStream: FileOutputStream

            private val future = CompletableFuture<File>()

            private var lastUpdateTime = 0L

            private var totalTransferred = 0
            override fun onSubscribe(subscription: java.util.concurrent.Flow.Subscription) {
                subscription.request(Long.MAX_VALUE)
                outputStream = FileOutputStream(file)
            }

            override fun onNext(buffers: List<ByteBuffer>) {
                buffers.forEach {
                    val buffer = it.array()
                    outputStream.write(buffer)
                    totalTransferred += buffer.size
                }

                val timeNow = System.currentTimeMillis()
                if(timeNow - lastUpdateTime > 500) {
                    _incomingTransfers.update { prev ->
                        prev.appendOrReplace(
                            item = incomingTransfer.copy(
                                transferred = totalTransferred,
                                status = Status.IN_PROGRESS
                            ),
                            replace = { it.id == incomingTransfer.id }
                        )
                    }
                    lastUpdateTime = timeNow
                }
            }

            override fun onComplete() {
                outputStream.flush()
                outputStream.close()
                _incomingTransfers.update { prev ->
                    prev.updateItem(
                        updatePredicate = { it.id == incomingTransfer.id },
                        function = { item ->
                            item.copy(
                                status = Status.COMPLETED,
                                transferred = item.size,
                            )
                        }
                    )
                }
                future.complete(file)
            }

            override fun getBody(): CompletionStage<File> {
                return future
            }

            override fun onError(p0: Throwable?) {
                p0?.printStackTrace()
            }
        }

        override fun apply(p0: H3HttpResponse.H3ResponseInfo?): H3BodySubscriber<File> {
            return BodySubscriber()
        }

    }

    fun acceptIncomingTransfer(
        transfer: IncomingTransfer,
        destFile: File,
    ) {
        val startTime = System.currentTimeMillis()

        val request = h3Factory.newRequestBuilder()
            .uri(URI("https://${transfer.fromHost.hostAddress}:$DEFAULT_PORT/download/${transfer.id}"))
            .build()

        val response = http3Client.send(
            request, AcceptTransferBodyHandler(destFile, transfer)
        )
        response.body()

        val transferDurationMs = (System.currentTimeMillis() - startTime).toInt()
        _incomingTransfers.update { prev ->
            prev.updateItem(
                updatePredicate = { it.id == transfer.id },
                function = { item ->
                    item.copy(
                        transferTime = transferDurationMs,
                    )
                }
            )
        }

        val sizeTransferred = destFile.length()
        println("TestAppServer: acceptIncomingTransfer: Done!!!: Received ${sizeTransferred} bytes in ${transferDurationMs}ms")
    }

    companion object {

        const val DEFAULT_PORT = 4242

        fun newTestServerWithRandomKey(
            appContext: Context,
            h3Factory: H3Factory,
            http3Client: H3HttpClient,
            socket: DatagramSocket = DatagramSocket(),
            logger: Logger = SysOutLogger(),
            mLogger: MNetLogger = MNetLoggerStdout(),
        ): TestAppServer {
            val keyPair = generateKeyPair()
            val certificate = generateX509Cert(keyPair)
            val keyIn = ByteArrayInputStream(keyPair.private.toPem().encodeToByteArray())
            val certIn = ByteArrayInputStream(certificate.toPem().encodeToByteArray())

            return TestAppServer(appContext, h3Factory, http3Client, socket, certIn, keyIn, mLogger, logger)
        }
    }

}