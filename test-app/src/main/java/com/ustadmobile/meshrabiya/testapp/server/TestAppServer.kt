package com.ustadmobile.meshrabiya.testapp.server

import android.content.Context
import android.net.Uri
import android.util.Log
import com.ustadmobile.meshrabiya.ext.appendOrReplace
import com.ustadmobile.meshrabiya.ext.copyToExactlyOrThrow
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.testapp.ext.getUriNameAndSize
import com.ustadmobile.meshrabiya.testapp.ext.updateItem
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.luminis.http3.libnethttp.H3HttpResponse
import net.luminis.http3.libnethttp.H3HttpResponse.H3BodyHandler
import net.luminis.http3.libnethttp.H3HttpResponse.H3BodySubscriber
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.headersContentLength
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
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
    private val httpClient: OkHttpClient,
    private val mLogger: MNetLogger,
    private val name: String,
    private val port: Int = 0,
    private val localVirtualAddr: InetAddress,
) : NanoHTTPD(port) {

    private val logPrefix: String = "[TestAppServer - $name] "

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

    private val _outgoingTransfers = MutableStateFlow(emptyList<OutgoingTransfer>())

    val outgoingTransfers: Flow<List<OutgoingTransfer>> = _outgoingTransfers.asStateFlow()

    val _incomingTransfers = MutableStateFlow(emptyList<IncomingTransfer>())

    val incomingTransfers: Flow<List<IncomingTransfer>> = _incomingTransfers.asStateFlow()

    val localPort: Int
        get() = super.getListeningPort()


    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        mLogger(Log.INFO, "$logPrefix : ${session.method} ${session.uri}")

        if(path.startsWith("/download/")) {
            val xferId = path.substringAfterLast("/").toInt()
            val outgoingXfer = _outgoingTransfers.value.first {
                it.id == xferId
            }

            val response = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/octet",
                appContext.contentResolver.openInputStream(outgoingXfer.uri),
                outgoingXfer.size.toLong()
            )

            return response

            /*
            TODO for NanoHTTPD : monitor the input stream to show progress
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
             */
        }else if(path.startsWith("/send")) {
            val searchParams = session.queryParameterString.split("&")
                .map {
                    it.substringBefore("=") to it.substringAfter("=")
                }.toMap()

            val id = searchParams["id"]
            val filename = searchParams["filename"]
            val size = searchParams["size"]?.toInt() ?: -1
            val fromAddr = searchParams["from"]

            if(id != null && filename != null && fromAddr != null) {
                val incomingTransfer = IncomingTransfer(
                    id = id.toInt(),
                    fromHost = InetAddress.getByName(fromAddr),
                    name = filename,
                    size = size
                )

                _incomingTransfers.update { prev ->
                    buildList {
                        add(incomingTransfer)
                        addAll(prev)
                    }
                }

                mLogger(Log.INFO, "$logPrefix Added request id $id for $filename from ${incomingTransfer.fromHost}")
                return newFixedLengthResponse("OK")
            }else {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad request")
            }
        }else {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found: $path")
        }
    }


    init {

    }

    /**
     * Add an outgoing transfer. This is done using a Uri so that we don't have to make our own
     * copy of the file the user wants to transfer.
     */
    fun addOutgoingTransfer(
        uri: Uri,
        toNode: InetAddress,
        toPort: Int = DEFAULT_PORT,
        fileName: String? = null,
    ): OutgoingTransfer {
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
        val request = Request.Builder().url("http://${toNode.hostAddress}:$toPort/" +
                "send?id=$transferId&filename=${URLEncoder.encode(effectiveName, "UTF-8")}" +
                "&size=${nameAndSize.size}&from=${localVirtualAddr.hostAddress}")
            .build()

        val response = httpClient.newCall(request).execute()
        println(response.body?.string())

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
        fromPort: Int = DEFAULT_PORT,
    ) {
        val startTime = System.currentTimeMillis()
        _incomingTransfers.update { prev ->
            prev.updateItem(
                updatePredicate = { it.id == transfer.id },
                function = { item ->
                    item.copy(
                        status = TestAppServer.Status.IN_PROGRESS,
                    )
                }
            )
        }

        val request = Request.Builder()
            .url("http://${transfer.fromHost.hostAddress}:$fromPort/download/${transfer.id}")
            .build()

        val response = httpClient.newCall(request).execute()
        val fileSize = response.headersContentLength()
        response.body?.byteStream()?.use { responseIn ->
            FileOutputStream(destFile).use { fileOut ->
                responseIn.copyToExactlyOrThrow(fileOut, response.headersContentLength())
            }
        }
        response.close()

        val transferDurationMs = (System.currentTimeMillis() - startTime).toInt()
        _incomingTransfers.update { prev ->
            prev.updateItem(
                updatePredicate = { it.id == transfer.id },
                function = { item ->
                    item.copy(
                        transferTime = transferDurationMs,
                        status = TestAppServer.Status.COMPLETED,
                        transferred = fileSize.toInt()
                    )
                }
            )
        }

        val sizeTransferred = destFile.length()
        println("TestAppServer: acceptIncomingTransfer: Done!!!: Received ${sizeTransferred} bytes in ${transferDurationMs}ms")
    }

    companion object {

        const val DEFAULT_PORT = 4242

    }

}